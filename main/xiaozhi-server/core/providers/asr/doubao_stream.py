import json
import gzip
import math
import time
import uuid
import asyncio
import websockets
import opuslib_next
from core.providers.asr.base import ASRProviderBase
from config.logger import setup_logging
from core.providers.asr.dto.dto import InterfaceType
from typing import Any, Dict, TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()


class ASRProvider(ASRProviderBase):
    def __init__(self, config, delete_audio_file):
        super().__init__()
        self.interface_type = InterfaceType.STREAM
        self.config = config
        self.text = ""
        self.utterances = []
        self.decoder = opuslib_next.Decoder(16000, 1)
        self.asr_ws = None
        self.forward_task = None
        self.is_processing = False  # 添加处理状态标志
        self._is_stopping = False  # 添加停止标志，防止竞态条件
        self._stop_sent_event = asyncio.Event()
        self._stop_sent_at = None
        self._stop_sent_times = {}

        self.api_key = config.get("api_key")
        if not self.api_key:
            raise ValueError("豆包流式语音识别模型2.0需要配置 api_key")
        self.resource_id = config.get("resource_id", "volc.seedasr.sauc.duration")

        self.context_text = config.get("context_text", "")
        self.output_dir = config.get("output_dir", "tmp/")
        self.delete_audio_file = delete_audio_file

        self.ws_url = config.get(
            "ws_url", "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        )
        self.uid = config.get("uid", "streaming_asr_service")
        self.workflow = config.get(
            "workflow", "audio_in,resample,partition,vad,fe,decode,itn,nlu_punctuate"
        )
        self.result_type = config.get("result_type", "single")
        self.format = config.get("format", "pcm")
        self.codec = config.get("codec", "pcm")
        self.rate = config.get("sample_rate", 16000)
        self.bits = config.get("bits", 16)
        self.channel = config.get("channel", 1)
        self.init_timeout_seconds = self._positive_seconds(
            config, "init_timeout_seconds", 10
        )
        self.response_timeout_seconds = self._positive_seconds(
            config, "response_timeout_seconds", 15
        )
        self.final_response_timeout_seconds = self._positive_seconds(
            config, "final_response_timeout_seconds", 5
        )

    @staticmethod
    def _positive_seconds(config, key, default):
        try:
            value = float(config.get(key, default))
        except (TypeError, ValueError) as exc:
            raise ValueError(f"{key} 必须是大于 0 的数字") from exc
        if not math.isfinite(value) or value <= 0:
            raise ValueError(f"{key} 必须是大于 0 的数字")
        return value

    async def open_audio_channels(self, conn):
        await super().open_audio_channels(conn)

    async def receive_audio(self, conn: "ConnectionHandler", audio, audio_have_voice):
        # 先调用父类方法处理基础逻辑
        await super().receive_audio(conn, audio, audio_have_voice)

        current_audio_sent = False

        # 如果本次有声音，且之前没有建立连接
        if audio_have_voice and self.asr_ws is None and not self.is_processing:
            try:
                if (
                    conn.client_voice_stop
                    and conn.client_listen_mode != "manual"
                ):
                    logger.bind(tag=TAG).warning(
                        "新ASR会话开始前发现残留停止标志，已清除以避免立即发送LAST"
                    )
                    conn.client_voice_stop = False
                self.is_processing = True
                self._is_stopping = False
                self._stop_sent_event = asyncio.Event()
                self._stop_sent_at = None
                self.text = ""
                self.utterances = []
                # 建立新的WebSocket连接
                headers = self.token_auth()
                safe_headers = {
                    key: "***"
                    if key.lower() in ("authorization", "api-key", "x-api-key")
                    else value
                    for key, value in headers.items()
                }
                logger.bind(tag=TAG).info(f"正在连接ASR服务，headers: {safe_headers}")

                self.asr_ws = await websockets.connect(
                    self.ws_url,
                    additional_headers=headers,
                    max_size=1000000000,
                    ping_interval=None,
                    ping_timeout=None,
                    close_timeout=10,
                )

                # 发送初始化请求
                request_params = self.construct_request(str(uuid.uuid4()))
                try:
                    payload_bytes = str.encode(json.dumps(request_params))
                    payload_bytes = gzip.compress(payload_bytes)
                    full_client_request = self.generate_header()
                    full_client_request.extend((len(payload_bytes)).to_bytes(4, "big"))
                    full_client_request.extend(payload_bytes)

                    logger.bind(tag=TAG).info(f"发送初始化请求: {request_params}")
                    await self.asr_ws.send(full_client_request)

                    # 等待初始化响应
                    try:
                        init_res = await asyncio.wait_for(
                            self.asr_ws.recv(), timeout=self.init_timeout_seconds
                        )
                    except asyncio.TimeoutError as exc:
                        raise TimeoutError(
                            "等待ASR初始化响应超时: "
                            f"timeout={self.init_timeout_seconds:.1f}s"
                        ) from exc
                    result = self.parse_response(init_res)
                    logger.bind(tag=TAG).info(f"收到初始化响应: {result}")

                    # 检查初始化响应
                    if "code" in result and result["code"] != 1000:
                        error_msg = f"ASR服务初始化失败: {result.get('payload_msg', {}).get('error', '未知错误')}"
                        logger.bind(tag=TAG).error(error_msg)
                        raise Exception(error_msg)

                except Exception as e:
                    logger.bind(tag=TAG).error(f"发送初始化请求失败: {str(e)}")
                    if hasattr(e, "__cause__") and e.__cause__:
                        logger.bind(tag=TAG).error(f"错误原因: {str(e.__cause__)}")
                    raise e

                # 启动接收ASR结果的异步任务
                session_ws = self.asr_ws
                stop_sent_event = self._stop_sent_event
                self.forward_task = asyncio.create_task(
                    self._forward_asr_results(conn, session_ws, stop_sent_event)
                )

                # 发送缓存的音频数据
                if conn.asr_audio and len(conn.asr_audio) > 0:
                    cached_audio_frames = list(conn.asr_audio[-10:])
                    for index, cached_audio in enumerate(cached_audio_frames):
                        if self._is_stopping or self.asr_ws is not session_ws:
                            break
                        try:
                            await self._send_audio_frame(cached_audio, session_ws)
                            if index == len(cached_audio_frames) - 1:
                                current_audio_sent = True
                        except Exception as e:
                            logger.bind(tag=TAG).info(
                                f"发送缓存音频数据时发生错误: {e}"
                            )

            except Exception as e:
                logger.bind(tag=TAG).error(f"建立ASR连接失败: {str(e)}")
                if hasattr(e, "__cause__") and e.__cause__:
                    logger.bind(tag=TAG).error(f"错误原因: {str(e.__cause__)}")
                if self.asr_ws:
                    await self.asr_ws.close()
                    self.asr_ws = None
                self.is_processing = False
                self._is_stopping = False
                self._stop_sent_event.clear()
                self._stop_sent_at = None
                conn.reset_audio_states()
                return

        # 发送当前音频数据
        if (
            self.asr_ws
            and self.is_processing
            and not self._is_stopping
            and not current_audio_sent
        ):
            try:
                await self._send_audio_frame(audio, self.asr_ws)
            except Exception as e:
                logger.bind(tag=TAG).info(f"发送音频数据时发生错误: {e}")

        # 自动/实时模式依靠本地 VAD 判停。当前静音尾帧发送后立即提交
        # LAST，接收任务继续等待火山返回最终 definite 结果。
        if (
            conn.client_voice_stop
            and self.asr_ws
            and self.is_processing
            and not self._is_stopping
        ):
            await self._send_stop_request()

    async def _send_audio_frame(self, opus_audio, session_ws=None):
        target_ws = session_ws or self.asr_ws
        if target_ws is None:
            raise RuntimeError("ASR WebSocket 未连接")
        pcm_frame = self.decoder.decode(opus_audio, 960)
        payload = gzip.compress(pcm_frame)
        audio_request = bytearray(self.generate_audio_default_header())
        audio_request.extend(len(payload).to_bytes(4, "big"))
        audio_request.extend(payload)
        await target_ws.send(audio_request)

    async def _recv_asr_result(self, session_ws, stop_sent_event):
        """Wait for a result and switch to the final-result budget after LAST."""
        recv_task = asyncio.create_task(session_ws.recv())
        if stop_sent_event.is_set():
            final_timeout = self._remaining_final_response_time(stop_sent_event)
            return await asyncio.wait_for(
                recv_task, timeout=final_timeout
            )

        stop_task = asyncio.create_task(stop_sent_event.wait())
        try:
            done, _ = await asyncio.wait(
                {recv_task, stop_task},
                timeout=self.response_timeout_seconds,
                return_when=asyncio.FIRST_COMPLETED,
            )
            if recv_task in done:
                return recv_task.result()
            if stop_task in done:
                final_timeout = self._remaining_final_response_time(stop_sent_event)
                return await asyncio.wait_for(
                    recv_task, timeout=final_timeout
                )

            recv_task.cancel()
            raise TimeoutError(
                "等待ASR中间结果超时: "
                f"timeout={self.response_timeout_seconds:.1f}s"
            )
        finally:
            if not stop_task.done():
                stop_task.cancel()
            if not recv_task.done():
                recv_task.cancel()
            await asyncio.gather(recv_task, stop_task, return_exceptions=True)

    def _remaining_final_response_time(self, stop_sent_event=None):
        stop_sent_at = self._stop_sent_times.get(
            stop_sent_event, self._stop_sent_at
        )
        if stop_sent_at is None:
            return self.final_response_timeout_seconds
        remaining = self.final_response_timeout_seconds - (
            time.monotonic() - stop_sent_at
        )
        if remaining <= 0:
            raise TimeoutError("等待ASR最终结果超过总时限")
        return remaining

    @staticmethod
    def _connection_close_details(exc):
        close_frame = getattr(exc, "rcvd", None) or getattr(exc, "sent", None)
        return (
            getattr(close_frame, "code", None),
            getattr(close_frame, "reason", ""),
        )

    async def _forward_asr_results(
        self, conn: "ConnectionHandler", session_ws=None, stop_sent_event=None
    ):
        session_ws = session_ws or self.asr_ws
        stop_sent_event = stop_sent_event or self._stop_sent_event
        audio_state_released = False
        try:
            while session_ws and not conn.stop_event.is_set():
                # 获取当前连接的音频数据
                audio_data = conn.asr_audio
                try:
                    # 每收到一个中间结果都会重新计算等待时间。发送 LAST 后，
                    # 同一个 recv 会切换到更短且独立的最终结果等待预算。
                    response = await self._recv_asr_result(
                        session_ws, stop_sent_event
                    )
                    result = self.parse_response(response)
                    logger.bind(tag=TAG).debug(f"收到ASR结果: {result}")

                    if "payload_msg" in result:
                        payload = result["payload_msg"]
                        # 检查是否是错误码1013（无有效语音）
                        if "code" in payload and payload["code"] == 1013:
                            # 静默处理，不记录错误日志
                            continue

                        if "result" in payload:
                            result_body = payload["result"]
                            partial_text = result_body.get("text")
                            if partial_text:
                                self.record_first_text_metric(conn, partial_text)
                            utterances = payload["result"].get("utterances", [])
                            # 检查duration和空文本的情况
                            if (
                                payload.get("audio_info", {}).get("duration", 0)
                                > 2000
                                and not utterances
                                and not payload["result"].get("text")
                                and conn.client_listen_mode != "manual"
                            ):
                                logger.bind(tag=TAG).error(f"识别文本：空")
                                self.text = ""
                                turn_metrics = getattr(conn, "turn_metrics", None)
                                if turn_metrics is not None:
                                    turn_metrics.discard_vad_segment()
                                conn.reset_audio_states()
                                audio_state_released = True
                                break

                            # 专门处理没有文本的识别结果（手动模式下可能已经识别完成但是没松按键）
                            elif not payload["result"].get("text") and not utterances:
                                if (
                                    conn.client_listen_mode == "manual"
                                    and conn.client_voice_stop
                                    and len(audio_data) > 15
                                ):
                                    logger.bind(tag=TAG).debug(
                                        "消息结束收到停止信号，触发处理"
                                    )
                                    audio_snapshot = list(audio_data)
                                    conn.reset_audio_states(preserve_voiceprint=True)
                                    audio_state_released = True
                                    await self.handle_voice_stop(
                                        conn, audio_snapshot
                                    )
                                    return

                            for utterance in utterances:
                                if utterance.get("definite", False):
                                    current_text = utterance["text"]
                                    self.record_first_text_metric(conn, current_text)
                                    self.record_final_text_metric(conn, current_text)
                                    normalized_utterance = _normalize_utterance(
                                        utterance
                                    )
                                    logger.bind(tag=TAG).info(
                                        f"识别到文本: {current_text}"
                                    )

                                    # 手动模式下累积识别结果
                                    if conn.client_listen_mode == "manual":
                                        self.utterances.append(normalized_utterance)
                                        if self.text:
                                            self.text += current_text
                                        else:
                                            self.text = current_text

                                        # 在接收消息中途时收到停止信号
                                        if conn.client_voice_stop and len(audio_data) > 0:
                                            logger.bind(tag=TAG).debug(
                                                "消息中途收到停止信号，触发处理"
                                            )
                                            audio_snapshot = list(audio_data)
                                            conn.reset_audio_states(preserve_voiceprint=True)
                                            audio_state_released = True
                                            await self.handle_voice_stop(
                                                conn, audio_snapshot
                                            )
                                            return
                                        break
                                    else:
                                        # 自动模式下直接覆盖
                                        self.text = current_text
                                        self.utterances = [normalized_utterance]
                                        if len(audio_data) > 0:
                                            audio_snapshot = list(audio_data)
                                            conn.reset_audio_states(preserve_voiceprint=True)
                                            audio_state_released = True
                                            await self.handle_voice_stop(
                                                conn, audio_snapshot
                                            )
                                        return
                        elif "error" in payload:
                            error_msg = payload.get("error", "未知错误")
                            logger.bind(tag=TAG).error(f"ASR服务返回错误: {error_msg}")
                            break

                except websockets.ConnectionClosed as exc:
                    close_code, close_reason = self._connection_close_details(exc)
                    logger.bind(tag=TAG).info(
                        "ASR服务连接已关闭: "
                        f"code={close_code}, "
                        f"reason={close_reason}, "
                        f"stop_sent={stop_sent_event.is_set()}"
                    )
                    self.is_processing = False
                    break
                except (asyncio.TimeoutError, TimeoutError) as exc:
                    if stop_sent_event.is_set():
                        logger.bind(tag=TAG).error(
                            "发送结束帧后等待ASR最终结果超时: "
                            f"timeout={self.final_response_timeout_seconds:.1f}s"
                        )
                    else:
                        logger.bind(tag=TAG).error(
                            str(exc)
                            or (
                                "等待ASR中间结果超时: "
                                f"timeout={self.response_timeout_seconds:.1f}s"
                            )
                        )
                    self.is_processing = False
                    break
                except Exception as e:
                    logger.bind(tag=TAG).error(f"处理ASR结果时发生错误: {str(e)}")
                    if hasattr(e, "__cause__") and e.__cause__:
                        logger.bind(tag=TAG).error(f"错误原因: {str(e.__cause__)}")
                    self.is_processing = False
                    break

        except Exception as e:
            logger.bind(tag=TAG).error(f"ASR结果转发任务发生错误: {str(e)}")
            if hasattr(e, "__cause__") and e.__cause__:
                logger.bind(tag=TAG).error(f"错误原因: {str(e.__cause__)}")
        finally:
            try:
                if session_ws:
                    await session_ws.close()
            except Exception as exc:
                logger.bind(tag=TAG).warning(f"关闭ASR会话连接失败: {exc}")
            finally:
                self._stop_sent_times.pop(stop_sent_event, None)
                # handle_voice_stop() 会通过 stop_ws_connection() 先清空 asr_ws，
                # 因此用每轮独立事件判断当前会话身份，避免漏清理当前轮或误清理新轮。
                if self._stop_sent_event is stop_sent_event:
                    stop_was_sent = stop_sent_event.is_set()
                    self.asr_ws = None
                    self.is_processing = False
                    self._is_stopping = False
                    stop_sent_event.clear()
                    self._stop_sent_at = None
                    self.text = ""
                    self.utterances = []
                    self.forward_task = None
                    # 只允许当前会话清理连接状态，避免旧任务影响重连后的新会话。
                    # 未发送 LAST 的断链/超时属于异常结束，保留本轮音频给后续重连回放。
                    if not audio_state_released and stop_was_sent:
                        conn.reset_audio_states()
                    elif not audio_state_released:
                        logger.bind(tag=TAG).warning(
                            "ASR会话在未发送LAST前结束，保留音频缓存用于重连重放"
                        )

    def stop_ws_connection(self):
        if self.asr_ws:
            asyncio.create_task(self.asr_ws.close())
            self.asr_ws = None
        self.is_processing = False
        self._is_stopping = False
        self._stop_sent_event.clear()
        self._stop_sent_at = None
        self.text = ""
        self.utterances = []

    async def _send_stop_request(self):
        """发送最后一个音频帧以通知服务器结束"""
        if self._is_stopping or not self.asr_ws or not self.is_processing:
            return False

        session_ws = self.asr_ws
        stop_sent_event = self._stop_sent_event
        self._is_stopping = True  # 先标记为停止状态，阻止后续音频发送
        try:
            # 发送结束标记的音频帧（gzip压缩的空数据）
            empty_payload = gzip.compress(b"")
            last_audio_request = bytearray(
                self.generate_last_audio_default_header()
            )
            last_audio_request.extend(len(empty_payload).to_bytes(4, "big"))
            last_audio_request.extend(empty_payload)
            await session_ws.send(last_audio_request)
            self._stop_sent_at = time.monotonic()
            self._stop_sent_times[stop_sent_event] = self._stop_sent_at
            stop_sent_event.set()
            logger.bind(tag=TAG).debug("已发送结束音频帧")
            return True
        except Exception as e:
            if self.asr_ws is session_ws:
                self._is_stopping = False
                self._stop_sent_at = None
            logger.bind(tag=TAG).warning(f"发送结束音频帧时出错: {e}")
            return False

    def construct_request(self, reqid):
        req = {
            "user": {"uid": self.uid},
            "request": {
                "reqid": reqid,
                "workflow": self.workflow,
                "show_utterances": True,
                "result_type": self.result_type,
                "sequence": 1,
            },
            "audio": {
                "format": self.format,
                "codec": self.codec,
                "rate": self.rate,
                "bits": self.bits,
                "channel": self.channel,
                "sample_rate": self.rate,
            },
        }

        if self.context_text:
            req["request"]["context"] = self.context_text

        logger.bind(tag=TAG).debug(
            f"构造请求参数: {json.dumps(req, ensure_ascii=False)}"
        )
        return req

    def token_auth(self):
        return {
            "X-Api-Key": self.api_key,
            "X-Api-Resource-Id": self.resource_id,
            "X-Api-Connect-Id": str(uuid.uuid4()),
        }

    def generate_header(
        self,
        version=0x01,
        message_type=0x01,
        message_type_specific_flags=0x00,
        serial_method=0x01,
        compression_type=0x01,
        reserved_data=0x00,
        extension_header: bytes = b"",
    ):
        header = bytearray()
        header_size = int(len(extension_header) / 4) + 1
        header.append((version << 4) | header_size)
        header.append((message_type << 4) | message_type_specific_flags)
        header.append((serial_method << 4) | compression_type)
        header.append(reserved_data)
        header.extend(extension_header)
        return header

    def generate_audio_default_header(self):
        return self.generate_header(
            version=0x01,
            message_type=0x02,
            message_type_specific_flags=0x00,
            serial_method=0x01,
            compression_type=0x01,
        )

    def generate_last_audio_default_header(self):
        return self.generate_header(
            version=0x01,
            message_type=0x02,
            message_type_specific_flags=0x02,
            serial_method=0x01,
            compression_type=0x01,
        )

    def parse_response(self, res: bytes) -> dict:
        try:
            # 检查响应长度
            if len(res) < 4:
                logger.bind(tag=TAG).error(f"响应数据长度不足: {len(res)}")
                return {"error": "响应数据长度不足"}

            # 获取消息头
            header = res[:4]
            message_type = header[1] >> 4

            # 如果是错误响应
            if message_type == 0x0F:  # SERVER_ERROR_RESPONSE
                code = int.from_bytes(res[4:8], "big", signed=False)
                msg_length = int.from_bytes(res[8:12], "big", signed=False)
                error_msg = json.loads(res[12:].decode("utf-8"))
                return {
                    "code": code,
                    "msg_length": msg_length,
                    "payload_msg": error_msg,
                }

            # 获取JSON数据
            try:
                # 检查字节8-11是否为有效的JSON长度字段
                # 格式：4字节头 + 4字节序列号 + 4字节长度 + JSON数据
                length = int.from_bytes(res[8:12], "big")
                if length > 0 and length <= len(res) - 12:
                    # 有长度字段，从字节12开始读取指定长度的JSON
                    json_data = res[12:12 + length].decode("utf-8")
                else:
                    # 无长度字段或长度无效，尝试直接解析
                    json_data = res[8:].decode("utf-8")
                result = json.loads(json_data)
                logger.bind(tag=TAG).debug(f"成功解析JSON响应: {result}")
                return {"payload_msg": result}
            except (UnicodeDecodeError, json.JSONDecodeError) as e:
                logger.bind(tag=TAG).error(f"JSON解析失败: {str(e)}")
                logger.bind(tag=TAG).error(f"原始数据: {res}")
                raise

        except Exception as e:
            logger.bind(tag=TAG).error(f"解析响应失败: {str(e)}")
            logger.bind(tag=TAG).error(f"原始响应数据: {res.hex()}")
            raise

    async def speech_to_text(self, opus_data, session_id, audio_format, artifacts=None):
        result = self.text
        utterances = [
            _normalize_utterance(item)
            for item in self.utterances
            if isinstance(item, dict)
        ]
        self.text = ""  # 清空text
        self.utterances = []
        if result and _has_structured_utterance_data(utterances):
            return {
                "content": result,
                "utterances": utterances,
                "provider_metadata": {
                    "provider": "doubao_stream",
                    "show_utterances": True,
                },
            }, None
        return result, None

    async def close(self):
        """资源清理方法"""
        if self.asr_ws:
            await self.asr_ws.close()
            self.asr_ws = None
        if self.forward_task:
            self.forward_task.cancel()
            try:
                await self.forward_task
            except asyncio.CancelledError:
                pass
            self.forward_task = None
        self.is_processing = False
        self._is_stopping = False
        self._stop_sent_event.clear()
        self._stop_sent_at = None
        self._stop_sent_times.clear()
        self.text = ""
        self.utterances = []

        # 显式释放decoder资源
        if hasattr(self, "decoder") and self.decoder is not None:
            try:
                del self.decoder
                self.decoder = None
                logger.bind(tag=TAG).debug("Doubao decoder resources released")
            except Exception as e:
                logger.bind(tag=TAG).debug(f"释放Doubao decoder资源时出错: {e}")


def _normalize_utterance(item: Dict[str, Any]) -> Dict[str, Any]:
    normalized = dict(item)
    normalized["text"] = str(normalized.get("text") or "").strip()
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


def _has_structured_utterance_data(utterances) -> bool:
    if not utterances:
        return False
    structured_keys = {
        "provider_speaker",
        "speaker",
        "speaker_id",
        "speakerId",
        "speaker_label",
        "speakerLabel",
        "role",
        "start_time",
        "startTime",
        "start_ms",
        "startMs",
        "begin_time",
        "beginTime",
        "end_time",
        "endTime",
        "end_ms",
        "endMs",
        "stop_time",
        "stopTime",
    }
    return any(
        isinstance(item, dict) and any(key in item for key in structured_keys)
        for item in utterances
    )


def _first_present(payload: Dict[str, Any], *keys: str) -> Any:
    for key in keys:
        value = payload.get(key)
        if value is not None:
            return value
    return None
