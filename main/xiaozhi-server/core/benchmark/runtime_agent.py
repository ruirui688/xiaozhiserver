import asyncio
import copy
import json
import queue
import threading
import time
import uuid
from types import SimpleNamespace

from config.logger import setup_logging
from core.connection import ConnectionHandler
from core.handle.receiveAudioHandle import startToChat
from core.providers.tts.dto.dto import ContentType, TTSMessageDTO, SentenceType
from core.utils.correct_words import parse_correct_words
from core.utils.llm import create_instance as create_llm_instance
from core.utils.model_config import normalize_runtime_config


TAG = __name__


class BenchmarkRuntimeAgent:
    """Run benchmark text through the same connection runtime used by device debug."""

    def __init__(self, base_config, model, provider_type, model_config, agent_context):
        self.base_config = base_config
        self.model = model
        self.provider_type = provider_type
        self.model_config = model_config
        self.agent_context = agent_context or {}
        self.device_context = self.agent_context.get("device_context") or {}
        self.logger = setup_logging()
        self.conn = None
        self.tts = None

    async def initialize(self):
        mac_address = self.device_context.get("mac_address")
        if not mac_address:
            raise RuntimeError("未选择真实测试设备，无法按设备运行时上下文评测")

        client_id = self.device_context.get("client_id") or f"manager-benchmark-{uuid.uuid4().hex}"
        config = copy.deepcopy(self.base_config or {})
        config.setdefault("server", {})
        config.setdefault("selected_module", {})

        self.conn = ConnectionHandler(config, None, None, None, None, None, _BenchmarkServer(config))
        self.conn.loop = asyncio.get_running_loop()
        self.conn.headers = {
            "device-id": mac_address,
            "client-id": client_id,
        }
        self.conn.device_id = mac_address
        self.conn.client_ip = "127.0.0.1"
        self.conn.websocket = _BenchmarkWebSocket(self.conn.headers)
        self.conn.features = {"mcp": False, "emoji": True}
        self.conn.welcome_msg = self.conn._build_welcome_msg()
        self.conn.sample_rate = self.conn.welcome_msg["audio_params"]["sample_rate"]
        self.conn.first_activity_time = time.time() * 1000
        self.conn.last_activity_time = time.time() * 1000

        should_initialize = await self.conn._initialize_private_config_async()
        if not should_initialize or self.conn.initialization_failed:
            raise RuntimeError("真实设备运行时配置初始化失败")
        if self.conn.need_bind:
            raise RuntimeError("测试设备未绑定或绑定状态异常，无法加载真实智能体上下文")

        self._apply_agent_runtime_config()
        self._override_candidate_llm()
        self.tts = _BenchmarkRuntimeTTS()
        self.conn.tts = self.tts
        self.conn.llm = create_llm_instance(self.provider_type, self.model_config)
        self.conn.llm._billing_model_id = self.model.get("id")
        self.conn._initialize_memory()
        self.conn._initialize_intent()
        self.conn._init_prompt_enhancement()
        self.conn._inject_tool_call_fewshot()
        await self._wait_tool_handler_ready()
        return self

    async def ask(self, text, timeout=120):
        if self.conn is None or self.tts is None:
            raise RuntimeError("BenchmarkRuntimeAgent 尚未初始化")
        before_index = len(self.tts.messages)
        await startToChat(self.conn, text)
        await self._wait_for_response(before_index, timeout)
        return self.tts.get_text_since(before_index)

    async def close(self):
        if not self.conn:
            return
        try:
            await self.conn.close(self.conn.websocket)
        except Exception:
            pass

    def _override_candidate_llm(self):
        candidate_name = self.model.get("id") or "BenchmarkCandidateLLM"
        config = self.conn.config
        config.setdefault("selected_module", {})["LLM"] = candidate_name
        config.setdefault("model_config", {})[candidate_name] = {
            "model_type": "LLM",
            "provider_code": self.provider_type,
            "typed_config": copy.deepcopy(self.model_config),
        }
        normalize_runtime_config(config)

    def _apply_agent_runtime_config(self):
        runtime_config = self.agent_context.get("runtime_config")
        if not isinstance(runtime_config, dict) or not runtime_config:
            return

        config = self.conn.config
        self._overlay_mapping(config, runtime_config, "model_config")
        self._overlay_mapping(config, runtime_config, "selected_module")
        for module_type in ("VAD", "ASR", "TTS", "LLM", "VLLM", "Memory", "Intent"):
            self._overlay_mapping(config, runtime_config, module_type)

        for key in (
            "prompt",
            "summaryMemory",
            "mcp_endpoint",
            "context_providers",
            "voiceprint",
            "device_max_output_size",
            "chat_history_conf",
            "billing_context",
            "_billing_purpose",
        ):
            if key in runtime_config and runtime_config.get(key) is not None:
                config[key] = copy.deepcopy(runtime_config[key])
        if runtime_config.get("device_max_output_size") is not None:
            self.conn.max_output_size = int(runtime_config["device_max_output_size"])
        if runtime_config.get("chat_history_conf") is not None:
            self.conn.chat_history_conf = int(runtime_config["chat_history_conf"])

        plugins = self._normalize_plugins(runtime_config.get("plugins"))
        if plugins is not None:
            config["plugins"] = plugins
            self._sync_intent_functions(plugins)

        if runtime_config.get("correct_words") is not None:
            self._apply_correct_words(runtime_config.get("correct_words"))
        normalize_runtime_config(config)

    def _overlay_mapping(self, target, source, key):
        if key not in source or source.get(key) is None:
            return
        value = copy.deepcopy(source[key])
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            target[key].update(value)
        else:
            target[key] = value

    def _normalize_plugins(self, plugins):
        if plugins is None:
            return None
        if not isinstance(plugins, dict):
            return copy.deepcopy(plugins)

        normalized = {}
        for name, plugin_config in plugins.items():
            if isinstance(plugin_config, str):
                try:
                    normalized[name] = json.loads(plugin_config)
                except Exception:
                    normalized[name] = plugin_config
            else:
                normalized[name] = copy.deepcopy(plugin_config)
        return normalized

    def _sync_intent_functions(self, plugins):
        if not isinstance(plugins, dict):
            return
        selected_intent = self.conn.config.get("selected_module", {}).get("Intent")
        if not selected_intent or selected_intent == "Intent_nointent":
            return
        intent_config = self.conn.config.get("Intent", {})
        selected_intent_config = intent_config.get(selected_intent)
        if isinstance(selected_intent_config, dict):
            selected_intent_config["functions"] = list(plugins.keys())

    def _apply_correct_words(self, correct_words):
        config = self.conn.config
        config["correct_words"] = copy.deepcopy(correct_words)
        self.conn.correct_word_replacements = parse_correct_words(correct_words)

        select_tts_module = config.get("selected_module", {}).get("TTS")
        tts_config = config.get("TTS", {})
        if select_tts_module in tts_config and isinstance(tts_config[select_tts_module], dict):
            tts_config[select_tts_module]["correct_words"] = copy.deepcopy(correct_words)

        tts_model_config = (
            config.get("model_config", {})
            .get(select_tts_module, {})
            .get("typed_config")
        )
        if isinstance(tts_model_config, dict):
            tts_model_config["correct_words"] = copy.deepcopy(correct_words)

    async def _wait_tool_handler_ready(self):
        handler = getattr(self.conn, "func_handler", None)
        if handler is None:
            return
        for _ in range(100):
            if getattr(handler, "finish_init", False):
                return
            await asyncio.sleep(0.05)

    async def _wait_for_response(self, before_index, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.tts.has_finished_since(before_index):
                return
            if self.conn.executor:
                pending = getattr(self.conn.executor, "_work_queue", None)
                if pending is not None and pending.qsize() == 0 and self.tts.has_text_since(before_index):
                    await asyncio.sleep(0.2)
                    if self.tts.has_finished_since(before_index) or not self.tts.has_pending_text():
                        return
            await asyncio.sleep(0.05)
        raise TimeoutError("真实设备智能体运行时评测超时")


class _BenchmarkRuntimeTTS:
    def __init__(self):
        self.tts_text_queue = _CapturingQueue(self)
        self.tts_audio_queue = queue.Queue()
        self.messages = []
        self.finished_sentence_ids = set()
        self._sentence_text_map = {}
        self.conn = None
        self.stop_event = threading.Event()
        self.tts_audio_first_sentence = True

    async def open_audio_channels(self, conn):
        self.conn = conn

    async def close(self):
        self.stop_event.set()

    def store_tts_text(self, sentence_id, text):
        if sentence_id and text:
            self._sentence_text_map[sentence_id] = text

    def get_tts_text(self, sentence_id):
        return self._sentence_text_map.get(sentence_id)

    def clear_tts_text(self, sentence_id):
        self._sentence_text_map.pop(sentence_id, None)

    def tts_one_sentence(self, conn, content_type, content_detail=None, content_file=None, sentence_id=None):
        sentence_id = sentence_id or conn.sentence_id or uuid.uuid4().hex
        conn.sentence_id = sentence_id
        self.tts_text_queue.put(TTSMessageDTO(
            sentence_id=sentence_id,
            sentence_type=SentenceType.MIDDLE,
            content_type=content_type,
            content_detail=content_detail,
            content_file=content_file,
        ))

    def capture(self, message):
        if not isinstance(message, TTSMessageDTO):
            return
        if message.content_type == ContentType.TEXT and message.content_detail:
            self.messages.append({
                "sentence_id": message.sentence_id,
                "text": str(message.content_detail),
                "sentence_type": getattr(message.sentence_type, "name", str(message.sentence_type)),
            })
        if message.sentence_type == SentenceType.LAST:
            self.finished_sentence_ids.add(message.sentence_id)

    def get_text_since(self, index):
        text = "".join(item.get("text", "") for item in self.messages[index:])
        return text.strip()

    def has_text_since(self, index):
        return any(item.get("text") for item in self.messages[index:])

    def has_finished_since(self, index):
        sentence_ids = {
            item.get("sentence_id")
            for item in self.messages[index:]
            if item.get("sentence_id")
        }
        return any(sentence_id in self.finished_sentence_ids for sentence_id in sentence_ids)

    def has_pending_text(self):
        return not self.tts_text_queue.empty()


class _CapturingQueue(queue.Queue):
    def __init__(self, owner):
        super().__init__()
        self.owner = owner

    def put(self, item, block=True, timeout=None):
        self.owner.capture(item)
        return super().put(item, block=block, timeout=timeout)


class _BenchmarkWebSocket:
    def __init__(self, headers):
        self.request = SimpleNamespace(headers=headers, path="/xiaozhi/v1/?from=benchmark")
        self.remote_address = ("127.0.0.1", 0)
        self.sent = []
        self.closed = False

    async def send(self, data):
        if isinstance(data, str):
            try:
                self.sent.append(json.loads(data))
            except Exception:
                self.sent.append(data)

    async def close(self):
        self.closed = True


class _BenchmarkServer:
    def __init__(self, config):
        self.config = config
        self.logger = setup_logging()
