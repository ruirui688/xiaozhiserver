"""Route a connection to either the legacy pipeline or a realtime session."""

from typing import Any, Dict

from core.conversation.mode import get_selected_llm_config, is_realtime_conversation
from core.providers.realtime.qwen_omni_realtime import (
    QwenOmniRealtimeSession,
    SUPPORTED_ADAPTERS,
)


class ConversationRouter:
    def __init__(self, conn, session_factory=None):
        self.conn = conn
        self.session_factory = session_factory or QwenOmniRealtimeSession
        self.session = None

    @property
    def is_realtime(self) -> bool:
        return is_realtime_conversation(self.conn.config)

    async def initialize(self) -> bool:
        if not self.is_realtime:
            return False
        config = get_selected_llm_config(self.conn.config) or {}
        adapter = str(config.get("type") or config.get("adapter_code") or "").lower()
        if adapter not in SUPPORTED_ADAPTERS:
            raise ValueError(f"不支持的 Realtime adapter: {adapter or '<empty>'}")
        self.session = self.session_factory(self.conn, config)
        await self.session.initialize()
        return True

    async def handle_audio(self, audio: bytes) -> bool:
        return bool(self.session and await self.session.handle_audio(audio))

    async def handle_listen(self, message: Dict[str, Any]) -> bool:
        return bool(self.session and await self.session.handle_listen(message))

    async def handle_text(self, text: str) -> bool:
        return bool(self.session and await self.session.handle_text(text))

    async def handle_abort(self) -> bool:
        return bool(self.session and await self.session.handle_abort())

    async def handle_image(self, question: str, image: bytes) -> str:
        if self.session is None:
            raise RuntimeError("Realtime 会话尚未就绪")
        return await self.session.handle_image(question, image)

    async def refresh_tools(self) -> None:
        if self.session is not None:
            await self.session.refresh_tools()

    async def close(self) -> None:
        if self.session is not None:
            await self.session.close()
            self.session = None
