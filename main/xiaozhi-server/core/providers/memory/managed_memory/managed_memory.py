"""
智控台治理记忆。

该 provider 不在 Python 进程内保存私有记忆，也不直接接入第三方记忆库。
聊天事件上报、候选抽取、事实确认、审计和运行时查询统一由 manager-api
长期记忆治理层负责。
"""

from ..base import MemoryProviderBase, logger

TAG = __name__


class MemoryProvider(MemoryProviderBase):
    def __init__(self, config, summary_memory=None):
        super().__init__(config)

    async def save_memory(self, msgs, session_id=None):
        logger.bind(tag=TAG).debug(
            "managed_memory mode: memory events are governed by manager-api."
        )
        return None

    async def query_memory(self, query: str) -> str:
        logger.bind(tag=TAG).debug(
            "managed_memory mode: runtime query is delegated to manager-api."
        )
        return ""
