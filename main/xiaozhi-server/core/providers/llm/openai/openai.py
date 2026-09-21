import httpx
import openai
import json
import math
import time
from openai.types import CompletionUsage
from config.logger import setup_logging
from core.utils.util import check_model_key
from core.providers.llm.base import LLMProviderBase
from core.observability.session_telemetry import contextual_latency_span
from urllib.parse import urlparse

TAG = __name__
logger = setup_logging()

# 需要禁用思考模式的平台域名及其对应参数（默认关闭思考模式）
THINKING_DISABLED_DOMAINS = {
    "aliyuncs.com": {"enable_thinking": False},
    "bigmodel.cn": {"thinking": {"type": "disabled"}},
    "moonshot.cn": {"thinking": {"type": "disabled"}},
    "volces.com": {"thinking": {"type": "disabled"}},
}


class LLMProvider(LLMProviderBase):
    supports_native_function_call = True
    supports_tool_choice = True
    supports_parallel_tool_calls = True

    def __init__(self, config):
        tool_choice_support = config.get("supports_tool_choice", True)
        self.supports_tool_choice = str(tool_choice_support).strip().lower() not in {
            "0",
            "false",
            "no",
            "off",
        }
        parallel_tool_support = config.get("supports_parallel_tool_calls", True)
        self.supports_parallel_tool_calls = str(parallel_tool_support).strip().lower() not in {
            "0",
            "false",
            "no",
            "off",
        }
        self.model_name = config.get("model_name")
        self.api_key = config.get("api_key")
        if "base_url" in config:
            self.base_url = config.get("base_url")
        else:
            self.base_url = config.get("url")
        
        timeout_config = config.get("timeout")
        if isinstance(timeout_config, dict):
            # 细粒度超时配置
            custom_timeout = httpx.Timeout(
                pool=timeout_config.get("pool", 2.0),
                connect=timeout_config.get("connect", 3.0),
                write=timeout_config.get("write", 5.0),
                read=timeout_config.get("read", 15.0)
            )
        elif isinstance(timeout_config, (int, float)) and timeout_config > 0:
            # 兼容旧的单一超时配置（整数或浮点数）
            custom_timeout = httpx.Timeout(timeout_config)
        else:
            # 语音链路默认采用短超时，避免单个上游请求长期占满连接线程池。
            custom_timeout = httpx.Timeout(
                pool=2.0,
                connect=3.0,
                write=5.0,
                read=15.0,
            )

        self.stream_total_timeout_seconds = self._positive_seconds(
            config,
            "stream_total_timeout_seconds",
            30,
        )

        self.thinking = self._normalize_thinking_config(config.get("thinking"))
        self.reasoning_effort = self._normalize_reasoning_effort(
            config.get("reasoning_effort")
        )

        param_defaults = {
            "max_tokens": int,
            "temperature": lambda x: round(float(x), 1),
            "top_p": lambda x: round(float(x), 1),
            "frequency_penalty": lambda x: round(float(x), 1),
        }

        for param, converter in param_defaults.items():
            value = config.get(param)
            try:
                setattr(
                    self,
                    param,
                    converter(value) if value not in (None, "") else None,
                )
            except (ValueError, TypeError):
                setattr(self, param, None)

        self.last_usage = None

        logger.debug(
            f"意图识别参数初始化: {self.temperature}, {self.max_tokens}, {self.top_p}, {self.frequency_penalty}"
        )

        model_key_msg = check_model_key("LLM", self.api_key)
        if model_key_msg:
            logger.bind(tag=TAG).error(model_key_msg)
        with contextual_latency_span("init.llm.client_construct"):
            self.client = openai.OpenAI(api_key=self.api_key, base_url=self.base_url, timeout=custom_timeout)

    @staticmethod
    def normalize_dialogue(dialogue):
        """自动修复 dialogue 中缺失 content 的消息"""
        for msg in dialogue:
            if "role" in msg and "content" not in msg:
                msg["content"] = ""
        return dialogue

    @staticmethod
    def _positive_seconds(config, key, default):
        try:
            value = float(config.get(key, default))
        except (TypeError, ValueError) as exc:
            raise ValueError(f"{key} 必须是大于 0 的数字") from exc
        if not math.isfinite(value) or value <= 0:
            raise ValueError(f"{key} 必须是大于 0 的数字")
        return value

    def _iter_stream_with_deadline(self, stream):
        """Iterate a synchronous SDK stream within a total wall-clock budget.

        The SDK's HTTP read timeout remains responsible for a stream that never
        yields its first chunk. This deadline bounds streams that keep yielding
        partial data without finishing.
        """
        deadline = time.monotonic() + self.stream_total_timeout_seconds
        iterator = iter(stream)
        while True:
            if time.monotonic() >= deadline:
                raise TimeoutError(
                    "LLM流式响应超过总时限"
                    f"（{self.stream_total_timeout_seconds:.1f}s）"
                )
            try:
                chunk = next(iterator)
            except StopIteration:
                return
            if time.monotonic() >= deadline:
                raise TimeoutError(
                    "LLM流式响应超过总时限"
                    f"（{self.stream_total_timeout_seconds:.1f}s）"
                )
            yield chunk

    @staticmethod
    def _normalize_thinking_config(value):
        if value in (None, ""):
            return None

        if isinstance(value, str):
            value = value.strip()
            if not value:
                return None
            if value.startswith("{"):
                try:
                    value = json.loads(value)
                except json.JSONDecodeError:
                    pass

        if isinstance(value, dict):
            thinking_type = str(value.get("type", "")).strip().lower()
        elif isinstance(value, bool):
            thinking_type = "enabled" if value else "disabled"
        else:
            thinking_type = str(value).strip().lower()

        aliases = {
            "enable": "enabled",
            "true": "enabled",
            "on": "enabled",
            "1": "enabled",
            "disable": "disabled",
            "false": "disabled",
            "off": "disabled",
            "0": "disabled",
        }
        thinking_type = aliases.get(thinking_type, thinking_type)
        if thinking_type not in ("enabled", "disabled"):
            return None
        return {"type": thinking_type}

    @staticmethod
    def _normalize_reasoning_effort(value):
        if value in (None, ""):
            return None
        effort = str(value).strip().lower()
        aliases = {"low": "high", "medium": "high", "xhigh": "max"}
        effort = aliases.get(effort, effort)
        return effort if effort in ("high", "max") else None

    def _is_deepseek(self):
        parsed_url = urlparse(self.base_url or "")
        return "api.deepseek.com" in parsed_url.netloc

    def _is_deepseek_thinking_enabled(self):
        return self._is_deepseek() and (self.thinking or {}).get("type") == "enabled"

    def _apply_provider_options(self, request_params: dict):
        """按供应商补充非标准 OpenAI 参数。"""
        if self._is_deepseek():
            if self.thinking:
                request_params.setdefault("extra_body", {})["thinking"] = self.thinking
            if self._is_deepseek_thinking_enabled() and self.reasoning_effort:
                request_params["reasoning_effort"] = self.reasoning_effort
            return

        self._apply_thinking_disabled(request_params)

    def _apply_thinking_disabled(self, request_params: dict):
        """根据域名自动禁用思考模式"""
        parsed_url = urlparse(self.base_url)
        domain = parsed_url.netloc
        for disabled_domain, params in THINKING_DISABLED_DOMAINS.items():
            if disabled_domain in domain:
                request_params.setdefault("extra_body", {}).update(params)
                logger.bind(tag=TAG).info(f"为域名 {domain} 禁用思考模式，参数: {params}")
                break

    @staticmethod
    def _usage_value(usage_info, key):
        """读取 OpenAI 标准字段或兼容端返回的扩展字段。"""
        if isinstance(usage_info, dict):
            return usage_info.get(key)
        value = getattr(usage_info, key, None)
        if value is not None:
            return value
        model_extra = getattr(usage_info, "model_extra", None)
        return model_extra.get(key) if isinstance(model_extra, dict) else None

    @classmethod
    def _build_usage(cls, usage_info):
        usage = {
            "prompt_tokens": cls._usage_value(usage_info, "prompt_tokens"),
            "completion_tokens": cls._usage_value(usage_info, "completion_tokens"),
            "total_tokens": cls._usage_value(usage_info, "total_tokens"),
        }
        cache_hit_tokens = cls._usage_value(
            usage_info, "prompt_cache_hit_tokens"
        )
        cache_miss_tokens = cls._usage_value(
            usage_info, "prompt_cache_miss_tokens"
        )
        if cache_hit_tokens is not None or cache_miss_tokens is not None:
            usage["prompt_cache_hit_tokens"] = cache_hit_tokens
            usage["prompt_cache_miss_tokens"] = cache_miss_tokens
            cache_input_tokens = int(cache_hit_tokens or 0) + int(
                cache_miss_tokens or 0
            )
            if cache_input_tokens > 0:
                usage["prompt_cache_hit_rate"] = round(
                    int(cache_hit_tokens or 0) / cache_input_tokens,
                    6,
                )
        return usage

    @staticmethod
    def _log_usage(usage):
        cache_summary = ""
        if "prompt_cache_hit_tokens" in usage:
            hit_tokens = usage.get("prompt_cache_hit_tokens") or 0
            miss_tokens = usage.get("prompt_cache_miss_tokens") or 0
            hit_rate = usage.get("prompt_cache_hit_rate")
            rate_text = f"{hit_rate:.2%}" if hit_rate is not None else "未知"
            cache_summary = (
                f"，缓存命中 {hit_tokens}，未命中 {miss_tokens}，"
                f"命中率 {rate_text}"
            )
        logger.bind(tag=TAG).info(
            f"Token 消耗：输入 {usage.get('prompt_tokens', '未知')}，"
            f"输出 {usage.get('completion_tokens', '未知')}，"
            f"共计 {usage.get('total_tokens', '未知')}{cache_summary}"
        )

    def response(self, session_id, dialogue, **kwargs):
        dialogue = self.normalize_dialogue(dialogue)
        self.last_usage = None

        request_params = {
            "model": self.model_name,
            "messages": dialogue,
            "stream": True,
        }

        # 添加可选参数,只有当参数不为None时才添加
        optional_params = {
            "max_tokens": kwargs.get("max_tokens", self.max_tokens),
            "temperature": kwargs.get("temperature", self.temperature),
            "top_p": kwargs.get("top_p", self.top_p),
            "frequency_penalty": kwargs.get("frequency_penalty", self.frequency_penalty),
        }

        for key, value in optional_params.items():
            if self._is_deepseek_thinking_enabled() and key in (
                "temperature",
                "top_p",
                "frequency_penalty",
            ):
                continue
            if value is not None:
                request_params[key] = value

        self._apply_provider_options(request_params)

        responses = self.client.chat.completions.create(**request_params)

        is_active = True
        try:            
            for chunk in self._iter_stream_with_deadline(responses):
                usage_info = getattr(chunk, "usage", None)
                if isinstance(usage_info, CompletionUsage):
                    self.last_usage = self._build_usage(usage_info)
                    self._log_usage(self.last_usage)
                    continue
                try:
                    delta = chunk.choices[0].delta if getattr(chunk, "choices", None) else None
                    content = getattr(delta, "content", "") if delta else ""
                except IndexError:
                    content = ""
                if content:
                    if "<think>" in content:
                        is_active = False
                        content = content.split("<think>")[0]
                    if "</think>" in content:
                        is_active = True
                        content = content.split("</think>")[-1]
                    if is_active:
                        yield content
        finally:
            responses.close()

    def response_with_functions(self, session_id, dialogue, functions=None, **kwargs):
        dialogue = self.normalize_dialogue(dialogue)
        self.last_usage = None

        request_params = {
            "model": self.model_name,
            "messages": dialogue,
            "stream": True,
            "tools": functions,
        }
        tool_choice = kwargs.get("tool_choice")
        if tool_choice is not None:
            request_params["tool_choice"] = tool_choice
        parallel_tool_calls = kwargs.get("parallel_tool_calls")
        if parallel_tool_calls is not None:
            request_params["parallel_tool_calls"] = bool(parallel_tool_calls)

        optional_params = {
            "max_tokens": kwargs.get("max_tokens", self.max_tokens),
            "temperature": kwargs.get("temperature", self.temperature),
            "top_p": kwargs.get("top_p", self.top_p),
            "frequency_penalty": kwargs.get("frequency_penalty", self.frequency_penalty),
        }

        for key, value in optional_params.items():
            if self._is_deepseek_thinking_enabled() and key in (
                "temperature",
                "top_p",
                "frequency_penalty",
            ):
                continue
            if value is not None:
                request_params[key] = value

        self._apply_provider_options(request_params)

        stream = self.client.chat.completions.create(**request_params)

        try:
            for chunk in self._iter_stream_with_deadline(stream):
                if getattr(chunk, "choices", None):
                    delta = chunk.choices[0].delta
                    content = getattr(delta, "content", "")
                    tool_calls = getattr(delta, "tool_calls", None)
                    yield content, tool_calls
                elif isinstance(getattr(chunk, "usage", None), CompletionUsage):
                    usage_info = getattr(chunk, "usage", None)
                    self.last_usage = self._build_usage(usage_info)
                    self._log_usage(self.last_usage)
        finally:
            stream.close()
