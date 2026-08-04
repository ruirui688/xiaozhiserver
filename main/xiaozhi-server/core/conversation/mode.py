"""Resolve the selected conversation pipeline from the existing LLM contract."""

from enum import Enum
from typing import Any, Dict, Optional

from core.utils.model_config import get_typed_config, normalize_selected_module


class ConversationMode(str, Enum):
    PIPELINE = "pipeline"
    REALTIME = "realtime"


def get_selected_llm_config(config: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    selected_llm = normalize_selected_module(config).get("LLM")
    if not selected_llm:
        return None
    return get_typed_config(config, "LLM", selected_llm)


def get_conversation_mode(config: Dict[str, Any]) -> ConversationMode:
    llm_config = get_selected_llm_config(config) or {}
    mode = str(llm_config.get("conversation_mode") or "").strip().lower()
    return ConversationMode.REALTIME if mode == "realtime" else ConversationMode.PIPELINE


def is_realtime_conversation(config: Dict[str, Any]) -> bool:
    return get_conversation_mode(config) is ConversationMode.REALTIME
