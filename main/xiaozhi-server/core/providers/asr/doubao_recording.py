import asyncio
import base64
import math
import os
import time
import uuid
from typing import List, Optional, Tuple

import aiohttp

from config.logger import setup_logging
from core.providers.asr.base import ASRProviderBase
from core.providers.asr.dto.dto import InterfaceType

TAG = __name__
logger = setup_logging()


class ASRProvider(ASRProviderBase):
    def __init__(self, config: dict, delete_audio_file: bool):
        super().__init__()
        self.interface_type = InterfaceType.NON_STREAM
        self.api_key = config.get("api_key")
        if not self.api_key:
            raise ValueError("豆包录音识别模型2.0需要配置 api_key")

        self.resource_id = config.get("resource_id", "volc.seedasr.auc")
        self.submit_url = config.get(
            "submit_url",
            "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit",
        )
        self.query_url = config.get(
            "query_url",
            "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query",
        )
        self.context_text = config.get("context_text", "")
        self.output_dir = config.get("output_dir", "tmp/")
        self.delete_audio_file = delete_audio_file
        self.http_timeout_seconds = self._positive_seconds(
            config,
            "http_timeout_seconds",
            10,
        )
        self.query_interval_seconds = self._positive_seconds(
            config,
            "query_interval_seconds",
            0.2,
        )
        self.query_timeout_seconds = self._positive_seconds(
            config,
            "query_timeout_seconds",
            15,
        )
        os.makedirs(self.output_dir, exist_ok=True)

    @staticmethod
    def _positive_seconds(config: dict, key: str, default: float) -> float:
        try:
            value = float(config.get(key, default))
        except (TypeError, ValueError) as exc:
            raise ValueError(f"{key} 必须是大于 0 的数字") from exc
        if not math.isfinite(value) or value <= 0:
            raise ValueError(f"{key} 必须是大于 0 的数字")
        return value

    def _headers(self, request_id: str, *, sequence: Optional[str] = None) -> dict:
        headers = {
            "X-Api-Key": self.api_key,
            "X-Api-Resource-Id": self.resource_id,
            "X-Api-Request-Id": request_id,
            "Content-Type": "application/json",
        }
        if sequence is not None:
            headers["X-Api-Sequence"] = sequence
        return headers

    def _build_submit_payload(self, wav_bytes: bytes, request_id: str) -> dict:
        payload = {
            "user": {"uid": "recording_asr_service"},
            "request": {
                "reqid": request_id,
                "sequence": 1,
                "show_utterances": True,
            },
            "audio": {
                "format": "wav",
                "codec": "pcm",
                "rate": 16000,
                "bits": 16,
                "channel": 1,
                "data": base64.b64encode(wav_bytes).decode("ascii"),
            },
        }
        if self.context_text:
            payload["request"]["context"] = self.context_text
        return payload

    async def _post_json(
        self,
        session: aiohttp.ClientSession,
        url: str,
        *,
        headers: dict,
        payload: dict,
        timeout_seconds: float,
        stage: str,
    ) -> dict:
        timeout = aiohttp.ClientTimeout(total=timeout_seconds)
        try:
            async with session.post(
                url,
                headers=headers,
                json=payload,
                timeout=timeout,
            ) as response:
                if response.status >= 400:
                    raise RuntimeError(f"{stage}失败: HTTP {response.status}")
                try:
                    result = await response.json(content_type=None)
                except (ValueError, aiohttp.ContentTypeError) as exc:
                    raise RuntimeError(f"{stage}失败: 响应不是有效 JSON") from exc
        except asyncio.TimeoutError as exc:
            raise TimeoutError(f"{stage}超时（{timeout_seconds:.1f}s）") from exc
        except aiohttp.ClientError as exc:
            raise RuntimeError(f"{stage}网络请求失败: {type(exc).__name__}") from exc

        if not isinstance(result, dict):
            raise RuntimeError(f"{stage}失败: 响应不是 JSON 对象")
        return result

    async def speech_to_text(
        self,
        opus_data: List[bytes],
        session_id: str,
        audio_format="opus",
        artifacts=None,
    ) -> Tuple[Optional[str], Optional[str]]:
        if artifacts is None or not artifacts.pcm_bytes:
            return "", None

        request_id = str(uuid.uuid4())
        wav_bytes = self._pcm_to_wav(artifacts.pcm_bytes)
        if not wav_bytes:
            return "", artifacts.file_path

        start_time = time.monotonic()
        submit_elapsed = 0.0
        query_count = 0
        stage = "submit"
        try:
            # requests 默认读取系统代理；trust_env=True 保留这一部署兼容性。
            async with aiohttp.ClientSession(trust_env=True) as session:
                submit_payload = await self._post_json(
                    session,
                    self.submit_url,
                    headers=self._headers(request_id, sequence="-1"),
                    payload=self._build_submit_payload(wav_bytes, request_id),
                    timeout_seconds=self.http_timeout_seconds,
                    stage="提交录音识别",
                )
                submit_elapsed = time.monotonic() - start_time
                logger.bind(tag=TAG).debug(
                    f"录音识别提交完成: elapsed={submit_elapsed:.3f}s"
                )

                text = self._extract_text(submit_payload)
                if text:
                    self._log_success(start_time, submit_elapsed, query_count, text)
                    return text, artifacts.file_path

                stage = "query"
                deadline = time.monotonic() + self.query_timeout_seconds
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        break
                    await asyncio.sleep(min(self.query_interval_seconds, remaining))

                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        break
                    query_count += 1
                    query_payload = await self._post_json(
                        session,
                        self.query_url,
                        headers=self._headers(request_id),
                        payload={"request": {"reqid": request_id}},
                        timeout_seconds=min(self.http_timeout_seconds, remaining),
                        stage="查询录音识别结果",
                    )
                    text = self._extract_text(query_payload)
                    if text:
                        self._log_success(start_time, submit_elapsed, query_count, text)
                        return text, artifacts.file_path
                    if self._is_failed(query_payload):
                        provider_code = query_payload.get("code")
                        provider_status = str(
                            query_payload.get("status")
                            or query_payload.get("message")
                            or ""
                        ).lower()
                        if provider_status not in {"failed", "failure", "error"}:
                            provider_status = ""
                        raise RuntimeError(
                            "录音识别失败: "
                            f"provider_code={provider_code}, "
                            f"provider_status={provider_status}"
                        )

                raise TimeoutError(
                    f"查询录音识别结果超时（{self.query_timeout_seconds:.1f}s）"
                )
        except Exception as e:
            elapsed = time.monotonic() - start_time
            logger.bind(tag=TAG).error(
                "录音识别失败: "
                f"stage={stage}, elapsed={elapsed:.3f}s, "
                f"error_type={type(e).__name__}, reason={e}"
            )
            return "", artifacts.file_path

    @staticmethod
    def _log_success(
        start_time: float,
        submit_elapsed: float,
        query_count: int,
        text: str,
    ) -> None:
        logger.bind(tag=TAG).debug(
            "录音识别完成: "
            f"total={time.monotonic() - start_time:.3f}s, "
            f"submit={submit_elapsed:.3f}s, queries={query_count}, "
            f"text_length={len(text)}"
        )

    @staticmethod
    def _extract_text(payload: dict) -> str:
        if not isinstance(payload, dict):
            return ""
        result = payload.get("result") or {}
        if isinstance(result, dict):
            text = result.get("text")
            if text:
                return str(text)
            utterances = result.get("utterances") or []
            if isinstance(utterances, list):
                return "".join(
                    str(item.get("text") or "")
                    for item in utterances
                    if isinstance(item, dict)
                ).strip()
        text = payload.get("text")
        return str(text).strip() if text else ""

    @staticmethod
    def _is_failed(payload: dict) -> bool:
        if not isinstance(payload, dict):
            return False
        code = payload.get("code")
        if isinstance(code, int) and code not in (0, 1000, 20000000):
            return True
        status = str(payload.get("status") or payload.get("message") or "").lower()
        return status in {"failed", "failure", "error"}
