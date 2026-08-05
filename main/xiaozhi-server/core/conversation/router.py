"""Route a connection to either the legacy pipeline or a realtime session."""

from typing import Any, Dict

from core.conversation.mode import get_selected_llm_config, is_realtime_conversation
from core.providers.realtime.registry import (
    create_realtime_session,
    normalize_realtime_adapter,
    realtime_adapter_capabilities,
    supported_realtime_adapters,
)


class ConversationRouter:
    def __init__(self, conn, session_factory=None):
        self.conn = conn
        self.session_factory = session_factory
        self.session = None

    @property
    def is_realtime(self) -> bool:
        return is_realtime_conversation(self.conn.config)

    @property
    def capabilities(self):
        if self.session is not None:
            return getattr(self.session, "capabilities", None)
        config = get_selected_llm_config(self.conn.config) or {}
        return realtime_adapter_capabilities(config)

    async def initialize(self) -> bool:
        if not self.is_realtime:
            return False
        config = get_selected_llm_config(self.conn.config) or {}
        adapter = normalize_realtime_adapter(config)
        if adapter not in supported_realtime_adapters():
            raise ValueError(f"不支持的 Realtime adapter: {adapter or '<empty>'}")
        await self._ensure_tool_runtime(config)
        self.session = (
            self.session_factory(self.conn, config)
            if self.session_factory is not None
            else create_realtime_session(self.conn, config)
        )
        await self.session.initialize()
        return True

    async def _ensure_tool_runtime(self, config: Dict[str, Any]) -> None:
        capabilities = realtime_adapter_capabilities(config)
        if capabilities is None or not capabilities.native_function_calling:
            return
        if getattr(self.conn, "func_handler", None) is None:
            from core.providers.tools.unified_tool_handler import UnifiedToolHandler

            handler = UnifiedToolHandler(self.conn)
            await handler._initialize()
            self.conn.func_handler = handler
        statistics_getter = getattr(self.conn.func_handler, "get_tool_statistics", None)
        statistics = statistics_getter() if callable(statistics_getter) else {}
        total = sum(statistics.values())
        logger = getattr(self.conn, "logger", None)
        if logger is not None:
            logger.bind(tag=__name__).info(
                "Realtime工具运行时已就绪: adapter={}, total={}, sources={}",
                normalize_realtime_adapter(config),
                total,
                statistics,
            )

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

    def voice_catalog(self):
        if self.session is None:
            return []
        getter = getattr(self.session, "voice_catalog", None)
        return list(getter() if callable(getter) else [])

    def current_voice(self):
        if self.session is None:
            return None
        getter = getattr(self.session, "current_voice", None)
        return getter() if callable(getter) else None

    def request_voice_change(self, voice: str) -> bool:
        if self.session is None:
            return False
        requester = getattr(self.session, "request_voice_change", None)
        return bool(requester and requester(voice))

    def request_context_reset(self) -> bool:
        if self.session is None:
            return False
        requester = getattr(self.session, "request_context_reset", None)
        return bool(requester and requester())

    async def reset_context_now(self) -> bool:
        if self.session is None:
            return False
        resetter = getattr(self.session, "reset_context_now", None)
        if resetter is None:
            return False
        return bool(await resetter())

    async def close(self) -> None:
        if self.session is not None:
            await self.session.close()
            self.session = None
