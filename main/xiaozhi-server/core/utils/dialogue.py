import uuid
import re
import threading
from typing import List, Dict
from datetime import datetime
from core.utils.current_time import get_current_datetime
from core.session.turn_context import build_contextual_user_content


class Message:
    def __init__(
            self,
            role: str,
            content: str = None,
            uniq_id: str = None,
            tool_calls=None,
            tool_call_id=None,
            is_temporary=False,
            turn_context=None,
    ):
        self.uniq_id = uniq_id if uniq_id is not None else str(uuid.uuid4())
        self.role = role
        self.content = content
        self.tool_calls = tool_calls
        self.tool_call_id = tool_call_id
        self.is_temporary = is_temporary  # 标记临时消息（如工具调用提醒）
        self.turn_context = turn_context


class Dialogue:
    def __init__(self, max_history_turns: int = 0):
        self.dialogue: List[Message] = []
        # 获取当前时间
        self.current_time = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        # 最大对话轮次数，0 表示不限制。每轮 = 1 user + 1 assistant (可能 + tool)
        self.max_history_turns = max_history_turns
        self._lock = threading.Lock()

    def put(self, message: Message):
        with self._lock:
            self.dialogue.append(message)

    def getMessages(self, m, dialogue):
        if m.tool_calls is not None:
            dialogue.append({"role": m.role, "tool_calls": m.tool_calls})
        elif m.role == "tool":
            dialogue.append(
                {
                    "role": m.role,
                    "tool_call_id": (
                        str(uuid.uuid4()) if m.tool_call_id is None else m.tool_call_id
                    ),
                    "content": m.content,
                }
            )
        else:
            content = m.content
            if m.role == "user" and m.turn_context is not None:
                content = build_contextual_user_content(content, m.turn_context)
            dialogue.append({"role": m.role, "content": content})

    def get_llm_dialogue(self) -> List[Dict[str, str]]:
        # 直接调用get_llm_dialogue_with_memory，传入None作为memory_str
        # 这样确保说话人功能在所有调用路径下都生效
        return self.get_llm_dialogue_with_memory(None, None)

    def update_system_message(self, new_content: str):
        """更新或添加系统消息"""
        with self._lock:
            # 查找第一个系统消息
            system_msg = next((msg for msg in self.dialogue if msg.role == "system"), None)
            if system_msg:
                system_msg.content = new_content
            else:
                self.dialogue.append(Message(role="system", content=new_content))

    def _ensure_tool_calls_complete(self, messages: List[Message]) -> List[Message]:
        """
        确保所有 tool_calls 都有对应的 tool 响应
        修复被打断导致的悬空 tool_calls，防止大模型 API 报 400 错误
        """
        pending_tool_calls = set()
        result = []

        for msg in messages:
            result.append(msg)

            if msg.role == "assistant" and msg.tool_calls:
                for tc in msg.tool_calls:
                    tc_id = tc.get("id") if isinstance(tc, dict) else getattr(tc, "id", None)
                    if tc_id:
                        pending_tool_calls.add(tc_id)

            elif msg.role == "tool" and msg.tool_call_id:
                pending_tool_calls.discard(msg.tool_call_id)

        for missing_id in pending_tool_calls:
            dummy_tool_msg = Message(
                role="tool",
                content='{"status": "interrupted", "message": "动作已取消/被打断"}',
                tool_call_id=missing_id
            )
            result.append(dummy_tool_msg)

        return result

    def get_llm_dialogue_with_memory(
            self,
            memory_str: str = None,
            voiceprint_config: dict = None,
            current_speaker: str = None,
            current_speaker_id: str = None,
    ) -> List[Dict[str, str]]:
        with self._lock:
            # 构建对话
            dialogue = []

            # 添加系统提示和记忆
            system_message = next(
                (msg for msg in self.dialogue if msg.role == "system"), None
            )

            if system_message:
                # 以 <context> 为分界点，拆分静态 system prompt 和动态上下文
                # 静态部分（规则、身份等）保持不变，可命中前缀缓存
                # 动态部分（时间、天气、记忆等）作为第二条 system 消息，保持 system 权威性
                full_prompt = system_message.content
                context_match = re.search(r"<context>", full_prompt)
                if context_match:
                    static_part = full_prompt[:context_match.start()]
                    dynamic_part = full_prompt[context_match.start():]
                else:
                    static_part = full_prompt
                    dynamic_part = ""

                # 第一段：静态 system prompt（前缀缓存可命中）
                dialogue.append({"role": "system", "content": static_part})

            # 第二段：few-shot 示例（会话内不变，也是缓存前缀的一部分）
            non_system_messages = [m for m in self.dialogue if m.role != "system"]
            fewshot_messages = [m for m in non_system_messages if m.is_temporary]
            complete_fewshot = self._ensure_tool_calls_complete(fewshot_messages)
            for m in complete_fewshot:
                self.getMessages(m, dialogue)

            # 第三段：动态上下文 system prompt（时间、记忆、说话人等）
            # 保持 system 角色以确保模型权威性，不降级为 user
            if system_message and dynamic_part:
                # 替换时间占位符
                dynamic_part = dynamic_part.replace(
                    "{{current_time}}", get_current_datetime()
                )

                # 填充记忆
                if memory_str is not None:
                    dynamic_part = re.sub(
                        r"<memory>.*?</memory>",
                        f"<memory>\n{memory_str}\n</memory>",
                        dynamic_part,
                        flags=re.DOTALL,
                    )

                # 追加说话人信息
                try:
                    current_speaker_name = (current_speaker or "").strip()
                    speakers = (voiceprint_config or {}).get("speakers", [])
                    if current_speaker_name and current_speaker_name != "未知说话人":
                        dynamic_part += "\n<speakers_info>"
                        dynamic_part += f"\n当前说话人：{current_speaker_name}"
                        dynamic_part += (
                            "\n约束：当前用户、刚刚说话的人、正在和你说话的人都指向上述说话人；"
                            "这是当前轮内部身份事实，不要把历史对话、记忆或描述中的其他人名当成当前说话人；"
                            "不要主动提及声纹识别过程、置信度或标签，也不要因为每轮收到身份信息就机械地称呼姓名；"
                            "只有用户询问自己是谁或刚刚是谁在说话时，才直接按当前身份回答。"
                        )
                        for speaker in speakers:
                            try:
                                if isinstance(speaker, dict):
                                    speaker_id = str(
                                        speaker.get("speaker_id") or ""
                                    ).strip()
                                    name = str(
                                        speaker.get("name")
                                        or speaker.get("source_name")
                                        or ""
                                    ).strip()
                                    description = str(
                                        speaker.get("description")
                                        or speaker.get("introduce")
                                        or ""
                                    ).strip()
                                else:
                                    parts = str(speaker).split(",", 2)
                                    speaker_id = parts[0].strip() if parts else ""
                                    name = parts[1].strip() if len(parts) >= 2 else ""
                                    description = (
                                        parts[2].strip() if len(parts) >= 3 else ""
                                    )
                                if current_speaker_id:
                                    is_current = speaker_id == current_speaker_id
                                else:
                                    is_current = name == current_speaker_name
                                if is_current and name:
                                    dynamic_part += f"\n- {name}：{description}"
                                    break
                            except Exception:
                                pass
                        dynamic_part += "\n</speakers_info>"
                except Exception:
                    pass

                dialogue.append({"role": "system", "content": dynamic_part})

            # 第四段：实际对话历史（不含 few-shot），超过最大轮数时截断旧轮次
            actual_messages = [m for m in non_system_messages if not m.is_temporary]
            actual_messages = self._truncate_history(actual_messages)
            complete_actual = self._ensure_tool_calls_complete(actual_messages)
            for m in complete_actual:
                self.getMessages(m, dialogue)

            return dialogue

    def _truncate_history(self, messages: List[Message]) -> List[Message]:
        """截断对话历史，保留最近的 N 轮对话（每轮从 user 消息开始）。

        超过 max_history_turns 时，删除最旧的轮次。
        max_history_turns=0 时不限制。
        """
        if self.max_history_turns <= 0:
            return messages

        # 从后往前统计 user 消息数量，找到保留的起始位置
        user_count = 0
        keep_from = 0
        for i in range(len(messages) - 1, -1, -1):
            if messages[i].role == "user":
                user_count += 1
                if user_count >= self.max_history_turns:
                    keep_from = i
                    break

        if keep_from > 0:
            return messages[keep_from:]
        return messages
