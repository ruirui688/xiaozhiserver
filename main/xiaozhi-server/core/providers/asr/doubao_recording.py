import asyncio
from core.metering.recorder import begin_call_async
from core.metering.admission import configure_provider
from core.metering.volcengine import asr_usage
import base64
import math
import os
import time
import uuid
from typing import Any, Dict, List, Optional, Tuple

import aiohttp

from config.logger import setup_logging
from core.providers.asr.base import ASRProviderBase
from core.providers.asr.dto.dto import InterfaceType

TAG = __name__
logger = setup_logging()


class ASRProvider(ASRProviderBase):
    def __init__(self, config: dict, delete_audio_file: bool):
        super().__init__()
        configure_provider(self, config)
        self.interface_type = InterfaceType.NON_STREAM
        self.api_key = config.get("api_key") or ("platform-managed" if self._platform_managed else None)
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
        self.context_text = (config.get("context_text") or "").strip()
        self.boosting_table_id = str(config.get("boosting_table_id") or "").strip()
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

    def _headers(self, request_id: str, *, sequence: Optional[str] = None, permit=None) -> dict:
        headers = {
            "X-Api-Key": permit.secret() if permit else self.api_key,
            "X-Api-Resource-Id": permit.runtime.get("resource_id", self.resource_id) if permit else self.resource_id,
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
        corpus = {}
        if self.context_text:
            # 火山协议要求 context 是 corpus 内的 JSON 字符串。
            corpus["context"] = self.context_text
        if self.boosting_table_id:
            corpus["boosting_table_id"] = self.boosting_table_id
        if corpus:
            payload["request"]["corpus"] = corpus
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
        usage_call = await begin_call_async(self, "ASR", request_id=request_id,
            max_usage={"audio_duration": str(math.ceil(len(artifacts.pcm_bytes) / 32))})
        usage_call.usage("submitted_audio_duration", len(artifacts.pcm_bytes) / 32, "milliseconds", "OBSERVED")
        try:
            permit = usage_call.permit
            submit_url = permit.endpoint("submit_url") if permit else self.submit_url
            query_url = permit.endpoint("query_url") if permit else self.query_url
            await asyncio.to_thread(usage_call.start)
            # requests 默认读取系统代理；trust_env=True 保留这一部署兼容性。
            async with aiohttp.ClientSession(trust_env=True) as session:
                submit_payload = await self._post_json(
                    session,
                    submit_url,
                    headers=self._headers(request_id, sequence="-1", permit=permit),
                    payload=self._build_submit_payload(wav_bytes, request_id),
                    timeout_seconds=self.http_timeout_seconds,
                    stage="提交录音识别",
                )
                submit_elapsed = time.monotonic() - start_time
                logger.bind(tag=TAG).debug(
                    f"录音识别提交完成: elapsed={submit_elapsed:.3f}s"
                )

                asr_result = self._extract_result(submit_payload)
                asr_usage(usage_call, submit_payload, final=bool(asr_result))
                if asr_result:
                    usage_call.status = "SUCCESS"
                    self._log_success(
                        start_time,
                        submit_elapsed,
                        query_count,
                        _result_content(asr_result),
                    )
                    return asr_result, artifacts.file_path

                stage = "query"
                deadline = time.monotonic() + self.query_timeout_seconds
                while True:
                    if permit:
                        permit.check()
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
                        query_url,
                        headers=self._headers(request_id, permit=permit),
                        payload={"request": {"reqid": request_id}},
                        timeout_seconds=min(self.http_timeout_seconds, remaining),
                        stage="查询录音识别结果",
                    )
                    asr_result = self._extract_result(query_payload)
                    asr_usage(usage_call, query_payload, final=bool(asr_result))
                    if asr_result:
                        usage_call.status = "SUCCESS"
                        self._log_success(
                            start_time,
                            submit_elapsed,
                            query_count,
                            _result_content(asr_result),
                        )
                        return asr_result, artifacts.file_path
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
        except asyncio.CancelledError:
            usage_call.status = "CANCELLED"
            raise
        except Exception as e:
            usage_call.status = "FAILED"
            elapsed = time.monotonic() - start_time
            logger.bind(tag=TAG).error(
                "录音识别失败: "
                f"stage={stage}, elapsed={elapsed:.3f}s, "
                f"error_type={type(e).__name__}"
            )
            return "", artifacts.file_path
        finally:
            usage_call.finish()

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
    def _extract_result(payload: dict) -> Any:
        if not isinstance(payload, dict):
            return ""
        result = payload.get("result") or {}
        if isinstance(result, dict):
            utterances = result.get("utterances") or []
            if isinstance(utterances, list):
                normalized_utterances = [
                    _normalize_utterance(item)
                    for item in utterances
                    if isinstance(item, dict)
                ]
                content = "".join(
                    str(item.get("text") or "")
                    for item in normalized_utterances
                ).strip()
                if content:
                    return {
                        "content": content,
                        "utterances": normalized_utterances,
                        "provider_metadata": {
                            "provider": "doubao_recording",
                            "show_utterances": True,
                        },
                    }
            text = result.get("text")
            if text:
                return str(text)
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


def _normalize_utterance(item: Dict[str, Any]) -> Dict[str, Any]:
    normalized = dict(item)
    text = str(normalized.get("text") or "").strip()
    normalized["text"] = text
    provider_speaker = _first_present(
        normalized,
        "speaker",
        "speaker_id",
        "speakerId",
        "speaker_label",
        "speakerLabel",
        "role",
    )
    if provider_speaker is not None:
        normalized["provider_speaker"] = str(provider_speaker)
    return normalized


def _first_present(payload: Dict[str, Any], *keys: str) -> Any:
    for key in keys:
        value = payload.get(key)
        if value is not None:
            return value
    return None


def _result_content(result: Any) -> str:
    if isinstance(result, dict):
        return str(result.get("content") or "")
    return str(result or "")
