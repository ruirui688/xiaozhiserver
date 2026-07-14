import asyncio
import ipaddress
import math
import socket
import time
from collections import defaultdict
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urlparse, urlunparse

import aiohttp

from config.logger import setup_logging

TAG = __name__
logger = setup_logging()


@dataclass(frozen=True)
class VoiceprintMatch:
    """一次可信声纹识别结果。"""

    speaker_id: Optional[str]
    name: str
    score: float


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
                f"超时={self.timeout_seconds:.2f}s"
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

    def reset_utterance(self, cancel_task: bool = True) -> None:
        """兼容旧调用；声纹识别不再维护句内采样状态。"""
        return None

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

    async def identify_speaker(
        self, audio_data: bytes, session_id: str
    ) -> Optional[VoiceprintMatch]:
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
                return VoiceprintMatch(None, "未知说话人", score)

            speaker = self.speaker_map.get(speaker_id)
            if not speaker:
                logger.bind(tag=TAG).warning("声纹服务返回了当前智能体候选集之外的说话人")
                return VoiceprintMatch(None, "未知说话人", score)

            logger.bind(tag=TAG).info(
                f"声纹识别成功: {speaker['name']} (相似度: {score:.3f})"
            )
            return VoiceprintMatch(speaker_id, speaker["name"], score)
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
