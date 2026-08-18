from enum import Enum


class TextMessageType(Enum):
    """消息类型枚举"""
    HELLO = "hello"
    ABORT = "abort"
    LISTEN = "listen"
    IOT = "iot"
    MCP = "mcp"
    SERVER = "server"
    PING = "ping"
    EXTERNAL_TTS = "external_tts"
    TTS_ACK = "tts_ack"
    SPEECH_ROUTE_REQUEST = "speech_route_request"
    FACE_RECOGNITION = "face_recognition"
    RECEPTION = "reception"
