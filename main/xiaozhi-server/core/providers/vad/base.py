from abc import ABC, abstractmethod
from typing import Optional


class VADProviderBase(ABC):
    @abstractmethod
    def is_vad(self, conn, data) -> bool:
        """检测音频数据中的语音活动"""
        pass

    def get_last_observation(self, conn):
        return None

    def get_last_decision(self, conn):
        return None

    def is_speech_gate_enforced(self, conn=None) -> bool:
        return False
