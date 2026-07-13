import asyncio
import io
import ipaddress
import math
import socket
import time
import wave
from collections import defaultdict
from typing import Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urlparse, urlunparse

import aiohttp
import opuslib_next

from config.logger import setup_logging

TAG = __name__
logger = setup_logging()


class VoiceprintProvider:
    """远程声纹识别服务提供者。"""

    def __init__(self, config: dict):
        self.original_url = str(config.get("url") or config.get("base_url") or "").strip()
        self.default_user_id = str(config.get("user_id") or "default").strip()
        self.similarity_threshold = self._bounded_float(
            config.get("similarity_threshold", 0.4), 0.0, 1.0, 0.4
        )
        self.timeout_seconds = self._bounded_float(
            config.get("timeout_seconds", 5), 0.5, 30.0, 5.0
        )
        self.sample_seconds = self._bounded_float(
            config.get("sample_seconds", 1.0), 0.5, 5.0, 1.0
        )
        result_wait_ms = self._bounded_float(
            config.get("result_wait_ms", 300), 0.0, 5000.0, 300.0
        )
        self.result_wait_seconds = result_wait_ms / 1000.0
        self._sample_target_bytes = int(self.sample_seconds * 16000 * 2)
        self._sample_target_bytes -= self._sample_target_bytes % 2
        self._sample_pcm = bytearray()
        self._sample_decoder = None
        self._prefetch_task: Optional[asyncio.Task] = None
        self._prefetch_started_at = 0.0
        self._http_session: Optional[aiohttp.ClientSession] = None

        self.api_key = str(config.get("token") or config.get("api_key") or "").strip()
        self.api_url = self._build_identify_url(self.original_url)
        if not self.api_key:
            self.api_key = self._legacy_token_from_url(self.original_url)

        self.speakers = config.get("speakers") or []
        self.speaker_map: Dict[str, Dict[str, str]] = {}
        self.candidates_by_user: Dict[str, List[str]] = defaultdict(list)
        self._parse_speakers()

        self.enabled = bool(
            self.api_url
            and self.api_key
            and self.default_user_id
            and self.candidates_by_user
            and self._is_public_target(self.api_url)
        )
        if self.enabled:
            logger.bind(tag=TAG).info(
                "声纹识别已启用: "
                f"API={self.api_url}, 说话人={len(self.speaker_map)}个, "
                f"作用域={len(self.candidates_by_user)}个, "
                f"相似度阈值={self.similarity_threshold}, "
                f"采样时长={self.sample_seconds:.2f}s, "
                f"句末等待={self.result_wait_seconds:.3f}s"
            )
        else:
            logger.bind(tag=TAG).warning(
                "声纹识别配置不完整，需配置远程URL、Bearer Token、user_id和说话人"
            )

    @staticmethod
    def _bounded_float(value, minimum: float, maximum: float, default: float) -> float:
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return default
        return min(maximum, max(minimum, parsed))

    @staticmethod
    def _legacy_token_from_url(raw_url: str) -> str:
        if not raw_url:
            return ""
        try:
            return parse_qs(urlparse(raw_url).query).get("key", [""])[0]
        except Exception:
            return ""

    @staticmethod
    def _build_identify_url(raw_url: str) -> Optional[str]:
        if not raw_url:
            return None
        try:
            parsed = urlparse(raw_url)
            if parsed.scheme not in {"http", "https"} or not parsed.netloc:
                return None
            path = (parsed.path or "").rstrip("/")
            for suffix in ("/health", "/docs", "/openapi.json"):
                if path.endswith("/voiceprint" + suffix):
                    path = path[: -len(suffix)]
                    break
            if not path:
                path = "/voiceprint"
            elif not path.endswith("/voiceprint"):
                path += "/voiceprint"
            safe_root = urlunparse((parsed.scheme, parsed.netloc, path, "", "", ""))
            return safe_root + "/identify"
        except Exception:
            return None

    @staticmethod
    def _is_public_target(raw_url: str) -> bool:
        try:
            host = urlparse(raw_url).hostname
            if not host:
                return False
            addresses = {
                item[4][0]
                for item in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
            }
            if not addresses:
                return False
            for value in addresses:
                address = ipaddress.ip_address(value)
                if (
                    address.is_private
                    or address.is_loopback
                    or address.is_link_local
                    or address.is_multicast
                    or address.is_unspecified
                    or address.is_reserved
                ):
                    logger.bind(tag=TAG).warning("声纹识别URL指向非公网地址，已拒绝启用")
                    return False
            return True
        except Exception as error:
            logger.bind(tag=TAG).warning(
                f"声纹识别URL解析失败: errorType={type(error).__name__}"
            )
            return False

    def _parse_speakers(self) -> None:
        for speaker in self.speakers:
            parsed = self._parse_speaker(speaker)
            if not parsed:
                continue
            speaker_id, name, description, remote_user_id = parsed
            if speaker_id in self.speaker_map:
                continue
            self.speaker_map[speaker_id] = {
                "name": name,
                "description": description,
                "user_id": remote_user_id,
            }
            self.candidates_by_user[remote_user_id].append(speaker_id)

    def _parse_speaker(self, speaker) -> Optional[Tuple[str, str, str, str]]:
        try:
            if isinstance(speaker, dict):
                speaker_id = str(speaker.get("speaker_id") or speaker.get("id") or "").strip()
                name = str(speaker.get("name") or speaker.get("source_name") or "").strip()
                description = str(
                    speaker.get("description") or speaker.get("introduce") or ""
                ).strip()
                remote_user_id = str(speaker.get("user_id") or self.default_user_id).strip()
            else:
                parts = str(speaker).split(",", 2)
                if len(parts) < 2:
                    return None
                speaker_id = parts[0].strip()
                name = parts[1].strip()
                description = parts[2].strip() if len(parts) >= 3 else ""
                remote_user_id = self.default_user_id

            if not speaker_id or not name or not remote_user_id:
                return None
            return speaker_id, name, description, remote_user_id
        except Exception as error:
            logger.bind(tag=TAG).warning(
                f"解析声纹说话人配置失败: errorType={type(error).__name__}"
            )
            return None

    def feed_audio_frame(
        self,
        audio_data: bytes,
        audio_have_voice: bool,
        audio_format: str,
        session_id: str,
    ) -> None:
        """累计一句话前段的有效人声，达到目标时长后立即预取身份。"""
        if not self.enabled or not audio_data or self._prefetch_task is not None:
            return

        try:
            if audio_format == "pcm":
                pcm_frame = bytes(audio_data)
            else:
                if self._sample_decoder is None:
                    self._sample_decoder = opuslib_next.Decoder(16000, 1)
                pcm_frame = self._sample_decoder.decode(audio_data, 960)
        except opuslib_next.OpusError as error:
            logger.bind(tag=TAG).debug(
                f"声纹预取Opus解码失败: errorType={type(error).__name__}"
            )
            return
        except Exception as error:
            logger.bind(tag=TAG).warning(
                f"声纹预取音频处理失败: errorType={type(error).__name__}"
            )
            return

        if not audio_have_voice or not pcm_frame:
            return

        remaining = self._sample_target_bytes - len(self._sample_pcm)
        if remaining <= 0:
            return
        self._sample_pcm.extend(pcm_frame[:remaining])
        if len(self._sample_pcm) < self._sample_target_bytes:
            return

        wav_data = self._pcm_to_wav(bytes(self._sample_pcm))
        if not wav_data:
            self.reset_utterance(cancel_task=False)
            return

        self._sample_decoder = None
        self._prefetch_started_at = time.monotonic()
        self._prefetch_task = asyncio.get_running_loop().create_task(
            self.identify_speaker(wav_data, session_id),
            name=f"voiceprint-prefetch-{session_id}",
        )
        logger.bind(tag=TAG).debug(
            f"声纹预取已启动: sample_seconds={self.sample_seconds:.2f}"
        )

    async def resolve_utterance_result(self) -> Optional[str]:
        """句末消费预取结果；短句跳过，未完成时只等待有限预算。"""
        task = self._prefetch_task
        if task is None:
            sampled_seconds = len(self._sample_pcm) / (16000 * 2)
            if sampled_seconds > 0:
                logger.bind(tag=TAG).debug(
                    "有效语音不足，跳过声纹识别: "
                    f"sampled={sampled_seconds:.3f}s, required={self.sample_seconds:.3f}s"
                )
            self.reset_utterance(cancel_task=False)
            return None

        try:
            if task.done():
                result = task.result()
            elif self.result_wait_seconds <= 0:
                task.cancel()
                result = None
            else:
                result = await asyncio.wait_for(
                    task, timeout=self.result_wait_seconds
                )
            elapsed = time.monotonic() - self._prefetch_started_at
            logger.bind(tag=TAG).debug(
                f"声纹预取消费完成: elapsed={elapsed:.3f}s, matched={bool(result)}"
            )
            return result
        except asyncio.TimeoutError:
            logger.bind(tag=TAG).warning(
                "声纹预取超过句末等待预算，当前轮降级为无身份: "
                f"wait={self.result_wait_seconds:.3f}s"
            )
            return None
        except asyncio.CancelledError:
            return None
        except Exception as error:
            logger.bind(tag=TAG).warning(
                f"读取声纹预取结果失败: errorType={type(error).__name__}"
            )
            return None
        finally:
            self.reset_utterance(cancel_task=not task.done())

    def reset_utterance(self, cancel_task: bool = True) -> None:
        """清理当前句的采样器和预取任务。"""
        task = self._prefetch_task
        if cancel_task and task is not None and not task.done():
            task.cancel()
        self._prefetch_task = None
        self._prefetch_started_at = 0.0
        self._sample_pcm.clear()
        self._sample_decoder = None

    @staticmethod
    def _pcm_to_wav(pcm_data: bytes) -> bytes:
        if not pcm_data:
            return b""
        if len(pcm_data) % 2 != 0:
            pcm_data = pcm_data[:-1]
        wav_buffer = io.BytesIO()
        try:
            with wave.open(wav_buffer, "wb") as wav_file:
                wav_file.setnchannels(1)
                wav_file.setsampwidth(2)
                wav_file.setframerate(16000)
                wav_file.writeframes(pcm_data)
            return wav_buffer.getvalue()
        except Exception as error:
            logger.bind(tag=TAG).warning(
                f"声纹预取WAV转换失败: errorType={type(error).__name__}"
            )
            return b""

    async def _get_http_session(self) -> aiohttp.ClientSession:
        if self._http_session is None or self._http_session.closed:
            timeout = aiohttp.ClientTimeout(
                total=self.timeout_seconds,
                connect=min(3.0, self.timeout_seconds),
            )
            self._http_session = aiohttp.ClientSession(
                timeout=timeout,
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Accept": "application/json",
                },
            )
        return self._http_session

    async def identify_speaker(self, audio_data: bytes, session_id: str) -> Optional[str]:
        """在当前智能体的远程 user_id 作用域中识别说话人。"""
        if not self.enabled or not audio_data:
            return None

        request_started_at = time.monotonic()
        try:
            session = await self._get_http_session()
            tasks = [
                self._identify_group(session, remote_user_id, speaker_ids, audio_data)
                for remote_user_id, speaker_ids in self.candidates_by_user.items()
            ]
            responses = await asyncio.gather(*tasks, return_exceptions=True)

            valid_results = []
            for response in responses:
                if isinstance(response, Exception):
                    logger.bind(tag=TAG).warning(
                        f"声纹识别分组请求失败: errorType={type(response).__name__}"
                    )
                    continue
                if response is not None:
                    valid_results.append(response)

            if not valid_results:
                return None

            speaker_id, score = max(valid_results, key=lambda item: item[1])
            elapsed = time.monotonic() - request_started_at
            logger.bind(tag=TAG).info(f"声纹识别耗时: {elapsed:.3f}s")

            if score < self.similarity_threshold or not speaker_id:
                logger.bind(tag=TAG).info(
                    f"声纹识别未达到阈值: score={score:.3f}, threshold={self.similarity_threshold}"
                )
                return "未知说话人"

            speaker = self.speaker_map.get(speaker_id)
            if not speaker:
                logger.bind(tag=TAG).warning("声纹服务返回了当前智能体候选集之外的说话人")
                return "未知说话人"

            logger.bind(tag=TAG).info(
                f"声纹识别成功: {speaker['name']} (相似度: {score:.3f})"
            )
            return speaker["name"]
        except asyncio.TimeoutError:
            elapsed = time.monotonic() - request_started_at
            logger.bind(tag=TAG).warning(f"声纹识别超时: {elapsed:.3f}s")
            return None
        except Exception as error:
            logger.bind(tag=TAG).warning(
                f"声纹识别失败: errorType={type(error).__name__}"
            )
            return None

    async def close(self) -> None:
        self.reset_utterance()
        if self._http_session is not None and not self._http_session.closed:
            await self._http_session.close()
        self._http_session = None

    async def _identify_group(
        self,
        session: aiohttp.ClientSession,
        remote_user_id: str,
        speaker_ids: List[str],
        audio_data: bytes,
    ) -> Optional[Tuple[str, float]]:
        data = aiohttp.FormData()
        data.add_field("user_id", remote_user_id)
        data.add_field("speaker_ids", ",".join(speaker_ids))
        data.add_field(
            "file",
            audio_data,
            filename="audio.wav",
            content_type="audio/wav",
        )

        async with session.post(self.api_url, data=data) as response:
            if response.status != 200:
                error_code = "unknown"
                try:
                    payload = await response.json()
                    error_code = str(payload.get("code") or "unknown")
                except Exception:
                    pass
                logger.bind(tag=TAG).warning(
                    f"声纹识别API错误: HTTP {response.status}, code={error_code}"
                )
                return None

            result = await response.json()
            try:
                score = float(result.get("score") or 0)
            except (TypeError, ValueError):
                score = 0.0
            if not math.isfinite(score):
                score = 0.0
            return str(result.get("speaker_id") or ""), score
