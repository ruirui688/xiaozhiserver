import os
import re
import math
import uuid
import queue
import asyncio
import threading
import traceback
import concurrent.futures

from core.utils import p3
from datetime import datetime
from core.utils import textUtils
from typing import Callable, Any
from abc import ABC, abstractmethod
from config.logger import setup_logging
from core.utils import opus_encoder_utils
from core.utils.tts import MarkdownCleaner, convert_percentage_to_range
from core.utils.output_counter import add_device_output
from core.handle.reportHandle import enqueue_tts_report
from core.handle.sendAudioHandle import sendAudioMessage, _audio_completion_timeout
from core.utils.util import audio_bytes_to_data_stream, audio_to_data_stream
from core.providers.tts.dto.dto import (
    TTSMessageDTO,
    SentenceType,
    ContentType,
    InterfaceType,
)

TAG = __name__
logger = setup_logging()


class _TTSMessageQueue(queue.Queue):
    """Notify the owning provider when a queued TTS turn actually starts."""

    def __init__(self, owner):
        super().__init__()
        self._owner = owner

    def get(self, block=True, timeout=None):
        message = super().get(block=block, timeout=timeout)
        self._owner._notify_tts_message_dequeued(message)
        return message


class TTSProviderBase(ABC):
    def __init__(self, config, delete_audio_file):
        self.interface_type = InterfaceType.NON_STREAM
        self.conn = None
        self.delete_audio_file = delete_audio_file
        self.audio_file_type = "wav"
        self.output_file = config.get("output_dir", "tmp/")
        self.tts_timeout = int(config.get("tts_timeout", 15))
        self.audio_send_timeout_seconds = self._positive_timeout(
            config.get("audio_send_timeout_seconds", 5),
            "audio_send_timeout_seconds",
        )
        self.tts_text_queue = _TTSMessageQueue(self)
        self.tts_audio_queue = queue.Queue()
        self.tts_audio_first_sentence = True
        self.before_stop_play_files = []
        self.report_on_last = False
        self._suppress_report_audio = False
        self._final_report_lock = threading.Lock()
        self._finalized_report_sentence_ids = set()
        self._finalized_report_sentence_order = []
        self._report_states = {}
        self._exact_text_sentence_ids = set()
        # sentence_id 到文本的映射，用于流式TTS获取正确的字幕文本
        self._sentence_text_map = {}
        # 加载替换词，用于一次性正则替换
        raw_words = config.get("correct_words", [])
        self.correct_words = {}
        for item in raw_words:
            parts = item.split("|", 1)
            if len(parts) == 2:
                self.correct_words[parts[0]] = parts[1]
        # 构建正则表达式，使用最长匹配优先（排序后转义拼接）
        if self.correct_words:
            # 按key长度降序排列，长的先匹配，避免短词部分干扰
            sorted_keys = sorted(self.correct_words.keys(), key=len, reverse=True)
            pattern_str = "|".join(re.escape(k) for k in sorted_keys)
            self._correct_words_pattern = re.compile(pattern_str)
            # 构建反向替换正则，用于将TTS服务返回的替换后文本还原为原始文本（字幕显示）
            reverse_map = {v: k for k, v in self.correct_words.items()}
            sorted_reverse_keys = sorted(reverse_map.keys(), key=len, reverse=True)
            reverse_pattern_str = "|".join(re.escape(k) for k in sorted_reverse_keys)
            self._reverse_words_pattern = re.compile(reverse_pattern_str)
            self._reverse_words_map = reverse_map
            # 流式滑动窗口：按首字分组的替换词字典，用于快速查找
            self._words_by_first_char = {}
            for key in sorted_keys:  # 使用已按长度降序排列的keys，确保长词优先匹配
                first_char = key[0] if key else ""
                if first_char not in self._words_by_first_char:
                    self._words_by_first_char[first_char] = []
                self._words_by_first_char[first_char].append(key)
        else:
            self._correct_words_pattern = None
            self._reverse_words_pattern = None
            self._reverse_words_map = None

        # 流式滑动窗口：待匹配的缓存文本
        self._pending_prefix = ""
        self.tts_text_buff = []
        self.punctuations = (
            "。",
            "？",
            "?",
            "！",
            "!",
            "；",
            ";",
            "：",
        )
        self.first_sentence_punctuations = (
            "，",
            "~",
            "、",
            ",",
            "。",
            "？",
            "?",
            "！",
            "!",
            "；",
            ";",
            "：",
        )
        self.tts_stop_request = False
        self.processed_chars = 0
        self.is_first_sentence = True

    @staticmethod
    def _positive_timeout(value, field_name):
        try:
            timeout = float(value)
        except (TypeError, ValueError) as exc:
            raise ValueError(f"{field_name} 必须是大于 0 的数字") from exc
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError(f"{field_name} 必须是大于 0 的数字")
        return timeout

    def generate_filename(self, extension=".wav"):
        return os.path.join(
            self.output_file,
            f"tts-{datetime.now().date()}@{uuid.uuid4().hex}{extension}",
        )

    def _record_turn_metric(self, method_name, *args):
        metrics = getattr(getattr(self, "conn", None), "turn_metrics", None)
        method = getattr(metrics, method_name, None)
        if method is None:
            return
        try:
            method(*args)
        except Exception as exc:
            logger.bind(tag=TAG).debug(
                f"记录TTS链路指标失败: method={method_name}, error={type(exc).__name__}"
            )

    def handle_opus(self, opus_data: bytes):
        logger.bind(tag=TAG).debug(f"推送数据到队列里面帧数～～ {len(opus_data)}")
        try:
            if self.conn is not None:
                self._record_turn_metric(
                    "record_tts_audio_frame",
                    len(opus_data),
                    getattr(self, "current_sentence_id", None),
                )
        except Exception as e:
            logger.bind(tag=TAG).debug(f"记录TTS音频指标失败: {e}")
        self.tts_audio_queue.put((
            SentenceType.MIDDLE,
            opus_data,
            None,
            getattr(self, 'current_sentence_id', None),
            self._suppress_report_audio,
        ))

    def handle_audio_file(self, file_audio: bytes, text):
        self.before_stop_play_files.append((file_audio, text))

    def to_tts_stream(self, text, opus_handler: Callable[[bytes], None] = None) -> None:
        try:
            if self.conn is not None:
                self._record_turn_metric(
                    "record_tts_start", getattr(self, 'current_sentence_id', None)
                )
        except Exception as e:
            logger.bind(tag=TAG).debug(f"记录TTS开始指标失败: {e}")
        # 保留原始文本用于显示/上报
        original_text = text
        log_text = self._text_for_log(original_text)
        text = self._prepare_synthesis_text(text)
        max_repeat_time = 5
        if self.delete_audio_file:
            # 需要删除文件的直接转为音频数据
            while max_repeat_time > 0:
                try:
                    audio_bytes = asyncio.run(self.text_to_speak(text, None))
                    if audio_bytes:
                        # 使用原始文本用于显示/上报
                        self.tts_audio_queue.put((SentenceType.FIRST, None, original_text, getattr(self, 'current_sentence_id', None)))
                        audio_bytes_to_data_stream(
                            audio_bytes,
                            file_type=self.audio_file_type,
                            is_opus=True,
                            callback=opus_handler,
                            sample_rate=self.conn.sample_rate,
                            opus_encoder=self.opus_encoder,
                        )
                        break
                    else:
                        max_repeat_time -= 1
                except Exception as e:
                    logger.bind(tag=TAG).warning(
                        f"语音生成失败{5 - max_repeat_time + 1}次: {log_text}，错误: {e}"
                    )
                    max_repeat_time -= 1
            if max_repeat_time > 0:
                logger.bind(tag=TAG).info(
                    f"语音生成成功: {log_text}，重试{5 - max_repeat_time}次"
                )
            else:
                logger.bind(tag=TAG).error(
                    f"语音生成失败: {log_text}，请检查网络或服务是否正常"
                )
            return None
        else:
            tmp_file = self.generate_filename()
            try:
                while not os.path.exists(tmp_file) and max_repeat_time > 0:
                    try:
                        asyncio.run(self.text_to_speak(text, tmp_file))
                    except Exception as e:
                        logger.bind(tag=TAG).warning(
                            f"语音生成失败{5 - max_repeat_time + 1}次: {log_text}，错误: {e}"
                        )
                        # 未执行成功，删除文件
                        if os.path.exists(tmp_file):
                            os.remove(tmp_file)
                        max_repeat_time -= 1

                if max_repeat_time > 0:
                    logger.bind(tag=TAG).info(
                        f"语音生成成功: {log_text}:{tmp_file}，重试{5 - max_repeat_time}次"
                    )
                else:
                    logger.bind(tag=TAG).error(
                        f"语音生成失败: {log_text}，请检查网络或服务是否正常"
                    )
                self.tts_audio_queue.put((SentenceType.FIRST, None, original_text, getattr(self, 'current_sentence_id', None)))
                self._process_audio_file_stream(tmp_file, callback=opus_handler)
            except Exception as e:
                logger.bind(tag=TAG).error(f"Failed to generate TTS file: {e}")
                return None
    
    def to_tts(self, text):
        # 保留原始文本用于日志/显示
        original_text = text
        log_text = self._text_for_log(original_text)
        text = self._prepare_synthesis_text(text)
        max_repeat_time = 5
        if self.delete_audio_file:
            # 需要删除文件的直接转为音频数据
            while max_repeat_time > 0:
                try:
                    audio_bytes = asyncio.run(self.text_to_speak(text, None))
                    if audio_bytes:
                        audio_datas = []
                        audio_bytes_to_data_stream(
                            audio_bytes,
                            file_type=self.audio_file_type,
                            is_opus=True,
                            callback=lambda data: audio_datas.append(data),
                            sample_rate=self.conn.sample_rate,
                        )
                        return audio_datas
                    else:
                        max_repeat_time -= 1
                except Exception as e:
                    logger.bind(tag=TAG).warning(
                        f"语音生成失败{5 - max_repeat_time + 1}次: {log_text}，错误: {e}"
                    )
                    max_repeat_time -= 1
            if max_repeat_time > 0:
                logger.bind(tag=TAG).info(
                    f"语音生成成功: {log_text}，重试{5 - max_repeat_time}次"
                )
            else:
                logger.bind(tag=TAG).error(
                    f"语音生成失败: {log_text}，请检查网络或服务是否正常"
                )
            return None
        else:
            tmp_file = self.generate_filename()
            try:
                while not os.path.exists(tmp_file) and max_repeat_time > 0:
                    try:
                        asyncio.run(self.text_to_speak(text, tmp_file))
                    except Exception as e:
                        logger.bind(tag=TAG).warning(
                            f"语音生成失败{5 - max_repeat_time + 1}次: {log_text}，错误: {e}"
                        )
                        # 未执行成功，删除文件
                        if os.path.exists(tmp_file):
                            os.remove(tmp_file)
                        max_repeat_time -= 1

                if max_repeat_time > 0:
                    logger.bind(tag=TAG).info(
                        f"语音生成成功: {log_text}:{tmp_file}，重试{5 - max_repeat_time}次"
                    )
                else:
                    logger.bind(tag=TAG).error(
                        f"语音生成失败: {log_text}，请检查网络或服务是否正常"
                    )

                return tmp_file
            except Exception as e:
                logger.bind(tag=TAG).error(f"Failed to generate TTS file: {e}")
                return None

    @abstractmethod
    async def text_to_speak(self, text, output_file):
        pass

    def audio_to_pcm_data_stream(
        self, audio_file_path, callback: Callable[[Any], Any] = None
    ):
        """音频文件转换为PCM编码"""
        return audio_to_data_stream(audio_file_path, is_opus=False, callback=callback, sample_rate=self.conn.sample_rate, opus_encoder=None)

    def audio_to_opus_data_stream(
        self, audio_file_path, callback: Callable[[Any], Any] = None
    ):
        """音频文件转换为Opus编码"""
        return audio_to_data_stream(audio_file_path, is_opus=True, callback=callback, sample_rate=self.conn.sample_rate, opus_encoder=self.opus_encoder)

    def tts_one_sentence(
        self,
        conn,
        content_type,
        content_detail=None,
        content_file=None,
        sentence_id=None,
    ):
        """发送一句话"""
        if not sentence_id:
            if conn.sentence_id:
                sentence_id = conn.sentence_id
            else:
                sentence_id = str(uuid.uuid4().hex)
                conn.sentence_id = sentence_id
        # 对于单句的文本，进行分段处理
        segments = re.split(r"([。！？!?；;\n])", content_detail)
        for seg in segments:
            self.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id=sentence_id,
                    sentence_type=SentenceType.MIDDLE,
                    content_type=content_type,
                    content_detail=seg,
                    content_file=content_file,
                )
            )

    async def open_audio_channels(self, conn):
        self.conn = conn

        # 根据conn的sample_rate创建编码器，如果子类已经创建则不覆盖（IndexTTS接口返回为24kHZ-待重采样处理）
        if not hasattr(self, 'opus_encoder') or self.opus_encoder is None:
            self.opus_encoder = opus_encoder_utils.OpusEncoderUtils(
                sample_rate=conn.sample_rate, channels=1, frame_size_ms=60
            )

        # tts 消化线程
        self.tts_priority_thread = threading.Thread(
            target=self.tts_text_priority_thread, daemon=True
        )
        self.tts_priority_thread.start()

        # 音频播放 消化线程
        self.audio_play_priority_thread = threading.Thread(
            target=self._audio_play_priority_thread, daemon=True
        )
        self.audio_play_priority_thread.start()

    def store_tts_text(self, sentence_id, text):
        """存储指定 sentence_id 对应的文本，用于流式TTS获取正确的字幕文本

        Args:
            sentence_id: 会话ID
            text: 要存储的文本
        """
        if sentence_id and text:
            self._sentence_text_map[sentence_id] = text
            # 只保留最近 5 个，防止内存泄漏
            if len(self._sentence_text_map) > 5:
                oldest = next(iter(self._sentence_text_map))
                del self._sentence_text_map[oldest]
            # 字幕文本可能在 provider 收到首包后立即清理；聊天记录文本
            # 必须保留到正常 LAST 或 Abort 其中一路成功完成最终上报。
            with self._final_report_lock:
                state = self._report_states.pop(sentence_id, None) or {
                    "started": False,
                }
                state["text"] = text
                self._report_states[sentence_id] = state
                while len(self._report_states) > 128:
                    oldest = next(iter(self._report_states))
                    del self._report_states[oldest]

    def store_tts_preview(self, sentence_id, text):
        """Store an early non-empty subtitle without finalizing report text."""
        if not sentence_id or not text:
            return
        self._sentence_text_map[sentence_id] = text
        if len(self._sentence_text_map) > 5:
            oldest = next(iter(self._sentence_text_map))
            del self._sentence_text_map[oldest]

    def mark_exact_text(self, sentence_id):
        """Bypass server-side rewriting for an authenticated external TTS turn."""
        if not sentence_id:
            return
        self._exact_text_sentence_ids.add(sentence_id)
        while len(self._exact_text_sentence_ids) > 128:
            self._exact_text_sentence_ids.pop()

    def mark_skip_memory_event(self, sentence_id):
        if not sentence_id:
            return
        with self._final_report_lock:
            state = self._report_states.setdefault(sentence_id, {})
            state["skip_memory_event"] = True

    def clear_exact_text(self, sentence_id):
        if sentence_id:
            self._exact_text_sentence_ids.discard(sentence_id)

    def _is_exact_text(self, sentence_id=None):
        resolved = sentence_id or getattr(self, "current_sentence_id", None)
        return bool(resolved and resolved in self._exact_text_sentence_ids)

    def _prepare_synthesis_text(self, text):
        if self._is_exact_text():
            return text
        prepared = MarkdownCleaner.clean_markdown(text)
        if self._correct_words_pattern:
            prepared = self._correct_words_pattern.sub(
                lambda match: self.correct_words[match.group(0)], prepared
            )
        return prepared

    def _clean_synthesis_text(self, text):
        """Clean markup before streaming correction without applying it twice."""
        if self._is_exact_text():
            return text
        return MarkdownCleaner.clean_markdown(text)

    def _text_for_synthesis(self, text):
        if self._is_exact_text():
            return text
        return textUtils.get_string_no_punctuation_or_emoji(text)

    def _text_for_log(self, text, sentence_id=None):
        if self._is_exact_text(sentence_id):
            return f"<external_tts:{len(str(text or ''))} chars>"
        return text

    def _notify_tts_message_dequeued(self, message):
        if getattr(message, "sentence_type", None) is not SentenceType.FIRST:
            return
        coordinator = getattr(getattr(self, "conn", None), "external_tts_coordinator", None)
        if coordinator is not None:
            coordinator.mark_synthesizing(self.conn, message.sentence_id)

    def _mark_external_tts_failed(self, sentence_id=None):
        conn = getattr(self, "conn", None)
        coordinator = getattr(conn, "external_tts_coordinator", None)
        resolved_sentence_id = sentence_id or getattr(
            self, "current_sentence_id", None
        )
        if coordinator is not None:
            coordinator.mark_failed(
                conn,
                resolved_sentence_id,
                "TTS synthesis failed",
            )
        self._schedule_reply_failure(
            "tts_synthesis_failed", resolved_sentence_id
        )

    def _schedule_reply_failure(self, reason, sentence_id=None):
        """Converge normal and external generations after provider failures."""
        conn = getattr(self, "conn", None)
        lifecycle = getattr(conn, "reply_lifecycle", None)
        loop = getattr(conn, "loop", None)
        if lifecycle is None or loop is None or loop.is_closed():
            return
        snapshot = lifecycle.snapshot()
        if not snapshot.is_active:
            return
        if sentence_id and snapshot.sentence_id not in {None, sentence_id}:
            return
        from core.handle.sendAudioHandle import terminate_pending_reply

        coroutine = terminate_pending_reply(
            conn, reason, generation=snapshot.generation
        )
        try:
            asyncio.run_coroutine_threadsafe(coroutine, loop)
        except Exception:
            coroutine.close()

    def get_tts_text(self, sentence_id):
        """获取指定 sentence_id 对应的文本

        Args:
            sentence_id: 会话ID

        Returns:
            str: 对应的文本，如果不存在返回 None
        """
        return self._sentence_text_map.get(sentence_id)

    def clear_tts_text(self, sentence_id):
        """清除指定 sentence_id 的文本

        Args:
            sentence_id: 会话ID
        """
        if sentence_id in self._sentence_text_map:
            del self._sentence_text_map[sentence_id]

    def get_report_text(self, sentence_id):
        if not sentence_id:
            return None
        with self._final_report_lock:
            state = self._report_states.get(sentence_id) or {}
            return state.get("text")

    def _get_report_metrics(self, sentence_id):
        if not sentence_id:
            return None
        with self._final_report_lock:
            state = self._report_states.get(sentence_id) or {}
            return state.get("metrics")

    def _remember_report_metrics(self, sentence_id, metrics):
        if not sentence_id or metrics is None:
            return
        with self._final_report_lock:
            state = self._report_states.setdefault(sentence_id, {})
            state["metrics"] = metrics

    def mark_report_started(self, sentence_id):
        """Record the real FIRST boundary independently for each sentence."""
        if not sentence_id:
            return
        with self._final_report_lock:
            state = self._report_states.pop(sentence_id, None) or {}
            state["started"] = True
            self._report_states[sentence_id] = state

    def _report_started(self, sentence_id):
        if not sentence_id:
            return False
        with self._final_report_lock:
            state = self._report_states.get(sentence_id) or {}
            return bool(state.get("started"))

    def _clear_report_state(self, sentence_id):
        if not sentence_id:
            return
        with self._final_report_lock:
            self._report_states.pop(sentence_id, None)

    def _claim_final_report(self, sentence_id):
        if not sentence_id:
            return True
        with self._final_report_lock:
            if sentence_id in self._finalized_report_sentence_ids:
                return False
            self._finalized_report_sentence_ids.add(sentence_id)
            self._finalized_report_sentence_order.append(sentence_id)
            while len(self._finalized_report_sentence_order) > 128:
                expired = self._finalized_report_sentence_order.pop(0)
                self._finalized_report_sentence_ids.discard(expired)
            return True

    def _release_final_report(self, sentence_id):
        if not sentence_id:
            return
        with self._final_report_lock:
            self._finalized_report_sentence_ids.discard(sentence_id)
            try:
                self._finalized_report_sentence_order.remove(sentence_id)
            except ValueError:
                pass

    def _enqueue_final_report(
        self, sentence_id, report_text, report_audio, metrics=None
    ):
        if not report_text or not self._claim_final_report(sentence_id):
            return False
        return self._enqueue_claimed_final_report(
            sentence_id,
            report_text,
            report_audio,
            metrics=metrics,
        )

    def _enqueue_claimed_final_report(
        self, sentence_id, report_text, report_audio, metrics=None
    ):
        with self._final_report_lock:
            report_state = self._report_states.get(sentence_id) or {}
            skip_memory_event = bool(report_state.get("skip_memory_event"))
        if skip_memory_event:
            queued = enqueue_tts_report(
                self.conn,
                report_text,
                report_audio,
                metrics=metrics,
                skip_memory_event=True,
            )
        else:
            queued = enqueue_tts_report(
                self.conn,
                report_text,
                report_audio,
                metrics=metrics,
            )
        if queued:
            self._clear_report_state(sentence_id)
            return True
        # build_assistant_metrics consumes the turn. Retain the resulting payload
        # so a delayed LAST or retry does not lose the latency trace.
        self._remember_report_metrics(sentence_id, metrics)
        self._release_final_report(sentence_id)
        return False

    def finalize_interrupted_report(self, sentence_id):
        """Persist a partial assistant turn even when abort removes its LAST."""
        if not sentence_id:
            return False
        report_text = self.get_report_text(sentence_id) or self.get_tts_text(
            sentence_id
        )
        turn_metrics = getattr(self.conn, "turn_metrics", None)
        # Pending replies are interruptible like upstream, but a generated text
        # that never crossed the TTS FIRST boundary was not heard by the user.
        if not self._report_started(sentence_id):
            if turn_metrics is not None:
                turn_metrics.discard_sentence(sentence_id)
            self._clear_report_state(sentence_id)
            return False
        # Do not consume the final-report claim when a provider has not supplied
        # reportable text. A delayed normal LAST must still be allowed to report.
        if not report_text:
            return False
        # Claim before consuming TurnMetrics. Abort and normal LAST can race;
        # only the winner may build the final metrics and enqueue the record.
        if not self._claim_final_report(sentence_id):
            return False
        metrics = self._get_report_metrics(sentence_id)
        if metrics is None:
            metrics = (
                turn_metrics.build_assistant_metrics(
                    report_text,
                    sentence_id=sentence_id,
                )
                if turn_metrics is not None
                else None
            )
        return self._enqueue_claimed_final_report(
            sentence_id,
            report_text,
            [],
            metrics=metrics,
        )

    def _audio_message_timeout(self, sentence_type):
        """Return the thread-side wait budget for one audio message.

        LAST includes draining the rate-controlled audio queue.  That drain has
        its own duration-aware timeout in sendAudioHandle, so the thread-side
        guard must not expire first and cancel an otherwise healthy playback.
        """
        if sentence_type == SentenceType.LAST:
            completion_timeout = _audio_completion_timeout(
                self.conn,
                getattr(self.conn, "audio_rate_controller", None),
            )
            return completion_timeout + self.audio_send_timeout_seconds
        return self.audio_send_timeout_seconds

    def _restore_original_text(self, text):
        if not self._reverse_words_pattern or not text:
            return text
        return self._reverse_words_pattern.sub(
            lambda m: self._reverse_words_map[m.group(0)], text
        )

    # 这里默认是非流式的处理方式
    # 流式处理方式请在子类中重写
    def tts_text_priority_thread(self):
        while not self.conn.stop_event.is_set():
            message = None
            try:
                message = self.tts_text_queue.get(timeout=1)
                with self.conn._abort_lock:
                    _aborted = self.conn.client_abort
                if _aborted:
                    logger.bind(tag=TAG).info("收到打断信息，终止TTS文本处理线程")
                    continue
                # 过滤旧消息：检查sentence_id是否匹配
                if message.sentence_id != self.conn.sentence_id:
                    continue
                if message.sentence_type == SentenceType.FIRST:
                    self.current_sentence_id = message.sentence_id
                    self.tts_stop_request = False
                    self.processed_chars = 0
                    self.tts_text_buff = []
                    self.is_first_sentence = True
                    self.tts_audio_first_sentence = True
                elif ContentType.TEXT == message.content_type:
                    self.tts_text_buff.append(message.content_detail)
                    segment_text = self._get_segment_text()
                    if segment_text:
                        self.to_tts_stream(segment_text, opus_handler=self.handle_opus)
                elif ContentType.FILE == message.content_type:
                    self._process_remaining_text_stream(opus_handler=self.handle_opus)
                    tts_file = message.content_file
                    if tts_file and os.path.exists(tts_file):
                        self._process_audio_file_stream(
                            tts_file, callback=self.handle_opus, suppress_report=True
                        )
                if message.sentence_type == SentenceType.LAST:
                    self._process_remaining_text_stream(opus_handler=self.handle_opus)
                    self._record_turn_metric(
                        "record_tts_generation_complete", message.sentence_id
                    )
                    self.tts_audio_queue.put(
                        (message.sentence_type, [], message.content_detail, message.sentence_id)
                    )

            except queue.Empty:
                continue
            except Exception as e:
                logger.bind(tag=TAG).error(
                    f"处理TTS文本失败: {str(e)}, 类型: {type(e).__name__}, 堆栈: {traceback.format_exc()}"
                )
                self._mark_external_tts_failed(
                    getattr(message, "sentence_id", None)
                )
                continue

    def _audio_play_priority_thread(self):
        # 需要上报的文本和音频列表
        enqueue_text = None
        enqueue_audio = []
        while not self.conn.stop_event.is_set():
            text = None
            sentence_id = None
            try:
                try:
                    item = self.tts_audio_queue.get(timeout=0.1)
                    skip_report_audio = False
                    generation = None
                    terminal_reason = None
                    if len(item) == 7:
                        (
                            sentence_type,
                            audio_datas,
                            text,
                            sentence_id,
                            skip_report_audio,
                            generation,
                            terminal_reason,
                        ) = item
                    elif len(item) == 6:
                        (
                            sentence_type,
                            audio_datas,
                            text,
                            sentence_id,
                            skip_report_audio,
                            generation,
                        ) = item
                    elif len(item) == 5:
                        sentence_type, audio_datas, text, sentence_id, skip_report_audio = item
                    elif len(item) == 4:
                        sentence_type, audio_datas, text, sentence_id = item
                    else:
                        sentence_type, audio_datas, text = item
                        sentence_id = None

                    lifecycle = getattr(self.conn, "reply_lifecycle", None)
                    if generation is not None and lifecycle is not None:
                        snapshot = lifecycle.snapshot()
                        if (
                            not snapshot.is_active
                            or snapshot.generation != generation
                            or snapshot.sentence_id not in {None, sentence_id}
                        ):
                            logger.bind(tag=TAG).debug(
                                "跳过失效generation的TTS音频边界 | "
                                f"generation={generation} | sentence_id={sentence_id}"
                            )
                            continue
                except queue.Empty:
                    if self.conn.stop_event.is_set():
                        break
                    continue

                with self.conn._abort_lock:
                    _aborted = self.conn.client_abort
                if _aborted:
                    logger.bind(tag=TAG).debug("收到打断信号，跳过当前音频数据")
                    enqueue_text, enqueue_audio = None, []
                    continue

                pending_report = None
                # 收到下一个文本开始或会话结束时准备上报；LAST 的最终指标
                # 必须等服务端音频发送队列排空后再构建。
                if sentence_type is not SentenceType.MIDDLE:
                    if self.report_on_last:
                        # 累积模式：适用于全程只有一个语音流的TTS（如seed-tts-2.0）
                        # FIRST时只记录文本，音频持续累积，仅在LAST时统一上报
                        if text:
                            enqueue_text = text
                        if sentence_type == SentenceType.LAST:
                            pending_report = (
                                enqueue_text,
                                list(enqueue_audio),
                                True,
                            )
                    else:
                        # 非累积模式：每个句子分别上报
                        if enqueue_text is not None:
                            pending_report = (
                                enqueue_text,
                                list(enqueue_audio),
                                sentence_type == SentenceType.LAST,
                            )
                        enqueue_audio = []
                        enqueue_text = text

                # 收集上报音频数据
                if isinstance(audio_datas, bytes) and not skip_report_audio:
                    enqueue_audio.append(audio_datas)

                # 发送音频
                future = asyncio.run_coroutine_threadsafe(
                    sendAudioMessage(
                        self.conn,
                        sentence_type,
                        audio_datas,
                        text,
                        sentence_id,
                        generation=generation,
                        terminal_reason=terminal_reason,
                    ),
                    self.conn.loop,
                )
                # 追踪待处理的 future，供 close() 取消
                if not hasattr(self, "_pending_futures"):
                    self._pending_futures = set()
                self._pending_futures.add(future)
                future.add_done_callback(self._pending_futures.discard)
                message_timeout = self._audio_message_timeout(sentence_type)
                try:
                    future.result(timeout=message_timeout)
                except concurrent.futures.TimeoutError:
                    future.cancel()
                    with self.conn._abort_lock:
                        aborted = self.conn.client_abort
                    if self.conn.stop_event.is_set() or aborted:
                        logger.bind(tag=TAG).debug("连接停止或会话取消，终止等待音频发送")
                        continue
                    logger.bind(tag=TAG).error(
                        "设备音频发送超时: "
                        f"timeout={message_timeout:.1f}s"
                    )
                    coordinator = getattr(
                        self.conn, "external_tts_coordinator", None
                    )
                    if coordinator is not None:
                        coordinator.mark_failed(
                            self.conn,
                            sentence_id,
                            "TTS audio delivery timed out",
                        )
                    self._schedule_reply_failure(
                        "tts_audio_delivery_timeout", sentence_id
                    )
                    continue
                except concurrent.futures.CancelledError:
                    if self.conn.stop_event.is_set():
                        break
                    with self.conn._abort_lock:
                        aborted = self.conn.client_abort
                    if aborted:
                        logger.bind(tag=TAG).debug("会话取消，音频发送任务已终止")
                        continue
                    raise

                if sentence_type == SentenceType.LAST:
                    self._record_turn_metric(
                        "record_audio_delivery_complete", sentence_id
                    )

                if pending_report is not None:
                    report_text, report_audio, final_report = pending_report
                    if final_report:
                        final_text = report_text or self.get_report_text(sentence_id)
                        if final_text and self._claim_final_report(sentence_id):
                            turn_metrics = getattr(self.conn, "turn_metrics", None)
                            metrics = self._get_report_metrics(sentence_id)
                            if metrics is None and turn_metrics is not None:
                                metrics = turn_metrics.build_assistant_metrics(
                                    self.get_report_text(sentence_id)
                                    or self.get_tts_text(sentence_id)
                                    or final_text,
                                    sentence_id=sentence_id,
                                )
                            self._enqueue_claimed_final_report(
                                sentence_id,
                                final_text,
                                report_audio,
                                metrics=metrics,
                            )
                    else:
                        turn_metrics = getattr(self.conn, "turn_metrics", None)
                        metrics = (
                            turn_metrics.build_link_metrics(
                                "assistant",
                                sentence_id=sentence_id,
                            )
                            if turn_metrics is not None
                            else None
                        )
                        enqueue_tts_report(
                            self.conn,
                            report_text,
                            report_audio,
                            metrics=metrics,
                        )
                    if self.report_on_last and final_report:
                        enqueue_audio = []
                        enqueue_text = None

                if sentence_type == SentenceType.LAST:
                    self.clear_exact_text(sentence_id)

                # 记录输出和报告
                if self.conn.max_output_size > 0 and text:
                    add_device_output(self.conn.headers.get("device-id"), len(text))

            except Exception as e:
                if self.conn.stop_event.is_set():
                    break
                logger.bind(tag=TAG).error(
                    f"audio_play_priority_thread: text_present={bool(text)}, "
                    f"error_type={type(e).__name__}, reason={e}"
                )
                coordinator = getattr(self.conn, "external_tts_coordinator", None)
                if coordinator is not None:
                    coordinator.mark_failed(
                        self.conn,
                        sentence_id,
                        "TTS audio delivery failed",
                    )
                self._schedule_reply_failure(
                    "tts_audio_delivery_failed", sentence_id
                )

    async def start_session(self, session_id):
        pass

    async def finish_session(self, session_id):
        pass

    async def close(self):
        """资源清理方法"""
        self._sentence_text_map.clear()
        with self._final_report_lock:
            self._report_states.clear()
            self._finalized_report_sentence_ids.clear()
            self._finalized_report_sentence_order.clear()
        self._exact_text_sentence_ids.clear()
        # 取消所有待处理的 run_coroutine_threadsafe futures，避免挂起的协程阻塞关闭
        pending = getattr(self, "_pending_futures", set())
        for fut in list(pending):
            if not fut.done():
                fut.cancel()
        pending.clear()
        if hasattr(self, "ws") and self.ws:
            await self.ws.close()

    def _get_segment_text(self):
        # An external TTS request must reach the provider as one unchanged text
        # value. Wait for LAST instead of punctuation-based server segmentation.
        if self._is_exact_text():
            return None
        # 合并当前全部文本并处理未分割部分
        full_text = "".join(self.tts_text_buff)
        current_text = full_text[self.processed_chars :]  # 从未处理的位置开始
        last_punct_pos = -1

        # 根据是否是第一句话选择不同的标点符号集合
        punctuations_to_use = (
            self.first_sentence_punctuations
            if self.is_first_sentence
            else self.punctuations
        )

        for punct in punctuations_to_use:
            pos = current_text.rfind(punct)
            if (pos != -1 and last_punct_pos == -1) or (
                pos != -1 and pos < last_punct_pos
            ):
                last_punct_pos = pos

        if last_punct_pos != -1:
            segment_text_raw = current_text[: last_punct_pos + 1]
            segment_text = self._text_for_synthesis(segment_text_raw)
            self.processed_chars += len(segment_text_raw)  # 更新已处理字符位置

            # 如果是第一句话，在找到第一个逗号后，将标志设置为False
            if self.is_first_sentence:
                self.is_first_sentence = False

            return segment_text
        elif self.tts_stop_request and current_text:
            segment_text = current_text
            self.is_first_sentence = True  # 重置标志
            return segment_text
        else:
            return None

    def _process_audio_file_stream(
        self, tts_file, callback: Callable[[Any], Any], suppress_report: bool = False
    ) -> None:
        """处理音频文件并转换为指定格式

        Args:
            tts_file: 音频文件路径
            callback: 文件处理函数
        """
        previous_suppress_report_audio = self._suppress_report_audio
        self._suppress_report_audio = suppress_report
        try:
            if tts_file.endswith(".p3"):
                p3.decode_opus_from_file_stream(tts_file, callback=callback)
            elif self.conn.audio_format == "pcm":
                self.audio_to_pcm_data_stream(tts_file, callback=callback)
            else:
                self.audio_to_opus_data_stream(tts_file, callback=callback)
        finally:
            self._suppress_report_audio = previous_suppress_report_audio

        if (
            self.delete_audio_file
            and tts_file is not None
            and os.path.exists(tts_file)
            and tts_file.startswith(self.output_file)
        ):
            os.remove(tts_file)

    def _process_before_stop_play_files(self):
        for audio_datas, text in self.before_stop_play_files:
            self.tts_audio_queue.put((SentenceType.MIDDLE, audio_datas, text, getattr(self, 'current_sentence_id', None)))
        self.before_stop_play_files.clear()
        self.tts_audio_queue.put((SentenceType.LAST, [], None, getattr(self, 'current_sentence_id', None)))

    def _process_remaining_text_stream(
        self, opus_handler: Callable[[bytes], None] = None
    ):
        """处理剩余的文本并生成语音

        Returns:
            bool: 是否成功处理了文本
        """
        full_text = "".join(self.tts_text_buff)
        remaining_text = full_text[self.processed_chars :]
        if remaining_text:
            segment_text = self._text_for_synthesis(remaining_text)
            if segment_text:
                self.to_tts_stream(segment_text, opus_handler=opus_handler)
                self.processed_chars += len(full_text)
                return True
        return False

    def _apply_percentage_params(self, config):
        """根据子类定义的 TTS_PARAM_CONFIG 批量应用百分比参数"""
        for config_key, attr_name, min_val, max_val, base_val, transform in self.TTS_PARAM_CONFIG:
            if config_key in config:
                val = convert_percentage_to_range(config[config_key], min_val, max_val, base_val)
                setattr(self, attr_name, transform(val) if transform else val)

    def _match_stream_text(self, text):
        """流式文本滑动窗口匹配，用于处理跨分片的替换词

        Args:
            text: 输入的文本片段

        Returns:
            tuple: (确定的文本列表, 剩余待匹配的前缀)
        """
        if self._is_exact_text():
            return [text] if text else [], ""
        if not self.correct_words or not text:
            return [text] if text else [], ""

        result = []
        pending = self._pending_prefix
        i = 0

        while i < len(text):
            char = text[i]

            # 尝试：pending + 当前字符 是否能匹配替换词
            test_text = pending + char

            matched = False
            # 遍历可能匹配的替换词
            candidates = self._words_by_first_char.get(pending[0], []) if pending else self._words_by_first_char.get(char, [])
            for key in candidates:
                if test_text == key:
                    # 完整匹配，替换后发送
                    result.append(self.correct_words[key])
                    pending = ""
                    matched = True
                    break
                elif key.startswith(test_text):
                    # 是替换词的前缀，继续等待
                    pending = test_text
                    matched = True
                    break

            if matched:
                i += 1
                continue

            # 没有匹配到更长的词，pending 的内容确定可以发送
            if pending:
                result.append(pending)
                pending = ""

            # 检查当前字符是否是某个替换词的开头
            if char in self._words_by_first_char:
                pending = char
            else:
                result.append(char)

            i += 1

        return result, pending

    def reset_stream_state(self):
        """重置流式处理状态，用于会话开始时清理残留状态"""
        self._pending_prefix = ""
