"""
智控台治理记忆。

该 provider 不在 Python 进程内保存私有记忆，也不直接接入第三方记忆库。
聊天事件上报、会话结束后的主动抽取、事实审计和运行时查询统一由 manager-api
长期记忆治理层负责。保存方式参考官方 Memory provider：把完整会话交给
记忆模型自动整理，不要求主对话模型调用记忆工具。

运行时 query_memory 会直接调用 manager-api 查询长期记忆上下文，
为 LLM 提供第二路记忆注入通道（与 connection.py 的 _query_memory_context 互补）。
"""

from ..base import MemoryProviderBase, logger
from config.manage_api_client import process_managed_memory_session, query_managed_memory

TAG = __name__


class MemoryProvider(MemoryProviderBase):
    def __init__(self, config, summary_memory=None):
        super().__init__(config)
        # summary_memory is accepted for provider API compatibility but ignored.
        # managed_memory persists structured facts in manager-api instead of the
        # legacy summary_memory column.
        self.role_id = None
        self.agent_id = None

    def init_memory(
        self,
        role_id,
        llm,
        agent_id=None,
        summary_memory=None,
        save_to_file=True,
    ):
        super().init_memory(role_id=role_id, llm=llm)
        self.agent_id = agent_id

    async def save_memory(self, msgs, session_id=None):
        if not session_id or not msgs:
            return None
        messages = []
        for message in msgs:
            if getattr(message, "is_temporary", False):
                continue
            role = str(getattr(message, "role", "") or "").strip().lower()
            content = getattr(message, "content", None)
            if role not in {"user", "assistant", "tool"} or not content:
                continue
            messages.append({"role": role, "content": str(content)[:8000]})
        if not messages:
            return None
        changed = await process_managed_memory_session(
            self.role_id,
            self.agent_id,
            session_id,
            messages[-200:],
        )
        logger.bind(tag=TAG).info(
            f"主动记忆会话处理完成: session={session_id}, factsChanged={changed}"
        )
        return changed

    async def query_memory(self, query: str) -> str:
        logger.bind(tag=TAG).debug(
            "managed_memory mode: runtime query delegated to manager-api."
        )
        if not self.role_id or not query:
            return ""
        try:
            result = await query_managed_memory(self.role_id, query)
            return result or ""
        except Exception:
            logger.bind(tag=TAG).warning(
                "managed_memory query_memory 调用 manager-api 失败，返回空上下文"
            )
            return ""
