import json
import uuid
import asyncio
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from core.utils.dialogue import Message
from core.providers.tts.dto.dto import ContentType
from plugins_func.register import Action, ActionResponse
from core.handle.sendAudioHandle import (
    schedule_reply_termination,
    send_stt_message,
)
from core.handle.reportHandle import enqueue_tool_report
from core.utils.correct_words import apply_correct_words
from core.providers.intent.intent_llm.routing import IntentRequestSnapshot
from core.providers.tts.dto.dto import TTSMessageDTO, SentenceType

TAG = __name__


def _finish_intent_task(conn, future, generation, empty_reason):
    try:
        error = future.exception()
    except Exception:
        schedule_reply_termination(
            conn, "intent_task_cancelled", generation=generation
        )
        return
    if error is not None:
        conn.logger.bind(tag=TAG).error(
            f"意图任务执行失败: errorType={type(error).__name__}"
        )
        schedule_reply_termination(
            conn, "intent_task_failed", generation=generation
        )
        return
    try:
        spoken = future.result()
    except Exception:
        spoken = False
    if spoken is not True:
        schedule_reply_termination(conn, empty_reason, generation=generation)


def _bind_current_sentence_metrics(conn, turn_id=None):
    turn_metrics = getattr(conn, "turn_metrics", None)
    if turn_metrics is not None:
        turn_metrics.bind_sentence(getattr(conn, "sentence_id", None), turn_id)


async def handle_user_intent(
    conn: "ConnectionHandler", text, turn_context=None, turn_id=None
):
    text = apply_correct_words(conn, text)
    original_text = text
    # 预处理输入文本，处理可能的JSON格式
    try:
        if text.strip().startswith("{") and text.strip().endswith("}"):
            parsed_data = json.loads(text)
            if isinstance(parsed_data, dict) and "content" in parsed_data:
                text = str(parsed_data["content"] or "")  # 仅分类和游戏参数使用净文本
                conn.current_speaker = parsed_data.get("speaker")  # 保留说话人信息
                conn.current_speaker_id = parsed_data.get("speaker_id") or None
    except (json.JSONDecodeError, TypeError):
        pass

    conn._intent_request_marker = object()

    active_game_tool = None
    requested_game_tool = None
    if hasattr(conn, "game_session_manager") and conn.game_session_manager:
        active_game_tool = conn.game_session_manager.get_active_tool()
        requested_game_tool = conn.game_session_manager.get_requested_game_tool(text)
    if requested_game_tool and conn.func_handler and conn.func_handler.has_tool(requested_game_tool):
        if active_game_tool and requested_game_tool != active_game_tool:
            conn.game_session_manager.clear_active_games()
        intent_result = json.dumps(
            {
                "function_call": {
                    "name": requested_game_tool,
                    "arguments": {"user_message": text},
                }
            },
            ensure_ascii=False,
        )
        conn.sentence_id = str(uuid.uuid4().hex)
        _bind_current_sentence_metrics(conn, turn_id)
        return await process_intent_result(
            conn,
            intent_result,
            original_text,
            turn_context=turn_context,
            turn_id=turn_id,
        )

    if active_game_tool and conn.func_handler and conn.func_handler.has_tool(active_game_tool):
        function_name = active_game_tool
        intent_result = json.dumps(
            {
                "function_call": {
                    "name": function_name,
                    "arguments": {"user_message": text},
                }
            },
            ensure_ascii=False,
        )
        conn.sentence_id = str(uuid.uuid4().hex)
        _bind_current_sentence_metrics(conn, turn_id)
        return await process_intent_result(
            conn,
            intent_result,
            original_text,
            turn_context=turn_context,
            turn_id=turn_id,
        )

    if conn.intent_type == "function_call":
        # 使用支持function calling的聊天方法,不再进行意图分析
        return False
    # 使用LLM进行意图分析
    request_snapshot = IntentRequestSnapshot.capture(conn)
    intent_result = await analyze_intent_with_llm(conn, text)
    if not request_snapshot.is_current(conn):
        # Classification now runs off the event loop. A cancelled/superseded
        # request must not resume a tool or start a fresh main-model reply.
        return True
    if not intent_result:
        return False
    # 会话开始时生成sentence_id
    conn.sentence_id = str(uuid.uuid4().hex)
    _bind_current_sentence_metrics(conn, turn_id)
    # 处理各种意图
    return await process_intent_result(
        conn,
        intent_result,
        original_text,
        turn_context=turn_context,
        turn_id=turn_id,
    )


async def analyze_intent_with_llm(conn: "ConnectionHandler", text):
    """使用LLM分析用户意图"""
    if not hasattr(conn, "intent") or not conn.intent:
        conn.logger.bind(tag=TAG).warning("意图识别服务未初始化")
        return None

    # 对话历史记录
    dialogue = conn.dialogue
    try:
        snapshot = getattr(dialogue, "snapshot_for_memory", None)
        history = snapshot() if callable(snapshot) else list(dialogue.dialogue)
        intent_result = await conn.intent.detect_intent(conn, history, text)
        return intent_result
    except Exception as e:
        conn.logger.bind(tag=TAG).error(f"意图识别失败: errorType={type(e).__name__}")

    return None


async def process_intent_result(
    conn: "ConnectionHandler",
    intent_result,
    original_text,
    turn_context=None,
    turn_id=None,
):
    """处理意图识别结果"""
    try:
        # 尝试将结果解析为JSON
        intent_data = json.loads(intent_result)

        # 检查是否有function_call
        if "function_call" in intent_data:
            # 直接从意图识别获取了function_call
            conn.logger.bind(tag=TAG).debug(
                f"检测到function_call格式的意图结果: {intent_data['function_call']['name']}"
            )
            function_name = intent_data["function_call"]["name"]
            if function_name in {"continue_chat", "result_for_context"}:
                # Legacy classifiers may still return result_for_context. Let
                # the normal main-model path use the complete current context.
                return False

            if not conn.func_handler or not conn.func_handler.has_tool(function_name):
                conn.logger.bind(tag=TAG).warning(
                    f"意图模型返回未注册工具，已回退主对话: {function_name}"
                )
                return False

            function_args = {}
            if "arguments" in intent_data["function_call"]:
                function_args = intent_data["function_call"]["arguments"]
                if function_args is None:
                    function_args = {}
            # 确保参数是字符串格式的JSON
            if isinstance(function_args, dict):
                function_args = json.dumps(function_args)

            function_call_data = {
                "name": function_name,
                "id": str(uuid.uuid4().hex),
                "arguments": function_args,
            }

            request_snapshot = IntentRequestSnapshot.capture(conn)
            reply_generation = await send_stt_message(conn, original_text)
            if not request_snapshot.is_current(conn, check_reply=False):
                return True
            if getattr(conn, "reply_lifecycle", None) is not None and reply_generation is None:
                return True
            conn.client_abort = False

            # 准备工具调用参数
            tool_input = {}
            if function_args:
                if isinstance(function_args, str):
                    tool_input = json.loads(function_args) if function_args else {}
                elif isinstance(function_args, dict):
                    tool_input = function_args

            # 上报工具调用
            enqueue_tool_report(conn, function_name, tool_input, turn_id=turn_id)

            # 使用executor执行函数调用和结果处理
            tool_abort_generation = conn.abort_generation
            tool_snapshot = IntentRequestSnapshot.capture(conn)
            tool_handler = conn.func_handler
            tool_dialogue = conn.dialogue
            tool_call_timeout = int(conn.config.get("tool_call_timeout", 30))

            async def execute_current_tool():
                if not tool_snapshot.is_current(conn):
                    return None
                return await tool_handler.handle_llm_function_call(conn, function_call_data)

            def process_function_call():
                if not tool_snapshot.is_current(conn):
                    return False
                tool_dialogue.put(
                    Message(
                        role="user",
                        content=original_text,
                        turn_context=turn_context,
                    )
                )
                
                # 使用统一工具处理器处理所有工具调用
                try:
                    result = asyncio.run_coroutine_threadsafe(
                        execute_current_tool(),
                        conn.loop,
                    ).result(timeout=tool_call_timeout)
                except Exception as e:
                    conn.logger.bind(tag=TAG).error(f"工具调用失败: errorType={type(e).__name__}")
                    result = ActionResponse(
                        action=Action.ERROR, result="工具调用超时，请一会再试下哈", response="工具调用超时，请一会再试下哈"
                    )

                # The context-reset tool intentionally clears the current speaker;
                # its acknowledgement still belongs to the unchanged reply/session.
                if not tool_snapshot.is_current(
                    conn, check_speaker=function_name != "reset_conversation_context"
                ):
                    return False

                # 上报工具调用结果
                spoken = function_name == "play_music"
                if result:
                    report_result = result.result if result.result else result.response
                    enqueue_tool_report(
                        conn,
                        function_name,
                        tool_input,
                        str(report_result) if report_result else None,
                        report_tool_call=False,
                        turn_id=turn_id,
                    )

                    if result.action == Action.RESPONSE:  # 直接回复前端
                        text = result.response
                        if text is not None:
                            spoken = speak_txt(conn, text)
                    elif result.action == Action.REQLLM:  # 调用函数后再请求llm生成回复
                        # All tool results need a matching assistant tool_call.
                        # Reuse the main reply lifecycle instead of appending an
                        # orphan tool message and treating tool data as a prompt.
                        if (conn.client_abort
                                or conn.abort_generation != tool_abort_generation):
                            return False
                        return conn.chat(
                            None,
                            allow_tools=False,
                            turn_id=turn_id,
                            reply_generation=reply_generation,
                            tool_result=result.result or "",
                            tool_call={
                                "id": function_call_data["id"],
                                "type": "function",
                                "function": {
                                    "name": function_name,
                                    "arguments": function_args or "{}",
                                },
                            },
                            trusted_follow_up_system_prompt=getattr(
                                result, "next_llm_system_prompt", None
                            ),
                        )
                    elif (
                        result.action == Action.NOTFOUND
                        or result.action == Action.ERROR
                    ):
                        text = result.response if result.response else result.result
                        if text is not None:
                            spoken = speak_txt(conn, text)
                    elif function_name != "play_music":
                        # For backward compatibility with original code
                        # 获取当前最新的文本索引
                        text = result.response
                        if text is None:
                            text = result.result
                        if text is not None:
                            spoken = speak_txt(conn, text)
                return spoken

            # 将函数执行放在线程池中
            try:
                future = conn.executor.submit(process_function_call)
            except Exception:
                schedule_reply_termination(
                    conn,
                    "intent_submit_failed",
                    generation=reply_generation,
                )
                return True
            future.add_done_callback(
                lambda item: _finish_intent_task(
                    conn,
                    item,
                    reply_generation,
                    "tool_no_spoken_result",
                )
            )
            return True
        return False
    except json.JSONDecodeError as e:
        conn.logger.bind(tag=TAG).error(f"处理意图结果时出错: {e}")
        return False


def speak_txt(conn: "ConnectionHandler", text):
    if text is None or not str(text).strip():
        return False
    # 记录文本到 sentence_id 映射
    conn.tts.store_tts_text(conn.sentence_id, text)

    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=conn.sentence_id,
            sentence_type=SentenceType.FIRST,
            content_type=ContentType.ACTION,
        )
    )
    conn.tts.tts_one_sentence(conn, ContentType.TEXT, content_detail=text)
    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=conn.sentence_id,
            sentence_type=SentenceType.LAST,
            content_type=ContentType.ACTION,
        )
    )
    conn.dialogue.put(Message(role="assistant", content=text))
    return True
