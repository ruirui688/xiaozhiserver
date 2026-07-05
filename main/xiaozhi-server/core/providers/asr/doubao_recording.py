import base64
import os
import time
import uuid
from typing import List, Optional, Tuple

import requests

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
        self.query_interval_seconds = float(config.get("query_interval_seconds", 1))
        self.query_timeout_seconds = int(config.get("query_timeout_seconds", 60))
        os.makedirs(self.output_dir, exist_ok=True)

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

        start_time = time.time()
        try:
            submit_response = requests.post(
                self.submit_url,
                headers=self._headers(request_id, sequence="-1"),
                json=self._build_submit_payload(wav_bytes, request_id),
                timeout=15,
            )
            if submit_response.status_code >= 400:
                raise RuntimeError(
                    f"提交录音识别失败: {submit_response.status_code} {submit_response.text}"
                )

            text = self._extract_text(submit_response.json())
            if text:
                logger.bind(tag=TAG).debug(
                    f"录音识别耗时: {time.time() - start_time:.3f}s | 结果: {text}"
                )
                return text, artifacts.file_path

            deadline = time.time() + self.query_timeout_seconds
            while time.time() < deadline:
                time.sleep(self.query_interval_seconds)
                query_response = requests.post(
                    self.query_url,
                    headers=self._headers(request_id),
                    json={"request": {"reqid": request_id}},
                    timeout=15,
                )
                if query_response.status_code >= 400:
                    raise RuntimeError(
                        f"查询录音识别结果失败: {query_response.status_code} {query_response.text}"
                    )
                payload = query_response.json()
                text = self._extract_text(payload)
                if text:
                    logger.bind(tag=TAG).debug(
                        f"录音识别耗时: {time.time() - start_time:.3f}s | 结果: {text}"
                    )
                    return text, artifacts.file_path
                if self._is_failed(payload):
                    raise RuntimeError(f"录音识别失败: {payload}")

            raise TimeoutError("查询录音识别结果超时")
        except Exception as e:
            logger.bind(tag=TAG).error(f"录音识别失败: {e}")
            return "", artifacts.file_path

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
