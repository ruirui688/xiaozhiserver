"""
智控台治理记忆。

该 provider 不在 Python 进程内保存私有记忆，也不直接接入第三方记忆库。
聊天事件上报、候选抽取、事实确认、审计和运行时查询统一由 manager-api
长期记忆治理层负责。

运行时 query_memory 会直接调用 manager-api 查询长期记忆上下文，
为 LLM 提供第二路记忆注入通道（与 connection.py 的 _query_memory_context 互补）。
"""

from ..base import MemoryProviderBase, logger
from config.manage_api_client import query_managed_memory

TAG = __name__


class MemoryProvider(MemoryProviderBase):
    def __init__(self, config, summary_memory=None):
        super().__init__(config)
        # NOTE: summary_memory is accepted for API compatibility but intentionally
        # ignored by managed_memory. The summary pipeline is handled entirely on
        # the Java side (manager-api), which has its own separate summarization
        # process triggered via memory source events. Passing summary_memory here
        # would create a race between the Python-side and Java-side pipelines.
        self.role_id = None

    async def init_memory(self, role_id, llm, summary_memory=None, save_to_file=True):
        self.role_id = role_id

    async def save_memory(self, msgs, session_id=None):
        logger.bind(tag=TAG).debug(
            "managed_memory mode: memory events are governed by manager-api."
        )
        return None

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
