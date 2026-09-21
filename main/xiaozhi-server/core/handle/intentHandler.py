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
    # 预处理输入文本，处理可能的JSON格式
    try:
        if text.strip().startswith("{") and text.strip().endswith("}"):
            parsed_data = json.loads(text)
            if isinstance(parsed_data, dict) and "content" in parsed_data:
                text = parsed_data["content"]  # 提取content用于意图分析
                conn.current_speaker = parsed_data.get("speaker")  # 保留说话人信息
    except (json.JSONDecodeError, TypeError):
        pass

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
            text,
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
            text,
            turn_context=turn_context,
            turn_id=turn_id,
        )

    if conn.intent_type == "function_call":
        # 使用支持function calling的聊天方法,不再进行意图分析
        return False
    # 使用LLM进行意图分析
    intent_result = await analyze_intent_with_llm(conn, text)
    if not intent_result:
        return False
    # 会话开始时生成sentence_id
    conn.sentence_id = str(uuid.uuid4().hex)
    _bind_current_sentence_metrics(conn, turn_id)
    # 处理各种意图
    return await process_intent_result(
        conn,
        intent_result,
        text,
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
        intent_result = await conn.intent.detect_intent(conn, dialogue.dialogue, text)
        return intent_result
    except Exception as e:
        conn.logger.bind(tag=TAG).error(f"意图识别失败: {str(e)}")

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
            if function_name == "continue_chat":
                return False

            if function_name == "result_for_context":
                reply_generation = await send_stt_message(conn, original_text)
                conn.client_abort = False

                def process_context_result():
                    conn.dialogue.put(
                        Message(
                            role="user",
                            content=original_text,
                            turn_context=turn_context,
                        )
                    )

                    from core.utils.current_time import get_current_time_info

                    current_time, today_date, today_weekday, lunar_date = (
                        get_current_time_info()
                    )

                    # 构建带上下文的基础提示
                    context_prompt = f"""当前时间：{current_time}
                                        今天日期：{today_date} ({today_weekday})
                                        今天农历：{lunar_date}

                                        请根据以上信息回答用户的问题：{original_text}"""

                    response = conn.intent.replyResult(context_prompt, original_text)
                    return speak_txt(conn, response)

                try:
                    future = conn.executor.submit(process_context_result)
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
                        "intent_no_spoken_result",
                    )
                )
                return True

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

            reply_generation = await send_stt_message(conn, original_text)
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
            def process_function_call():
                conn.dialogue.put(
                    Message(
                        role="user",
                        content=original_text,
                        turn_context=turn_context,
                    )
                )
                
                # 工具调用超时时间
                tool_call_timeout = int(conn.config.get("tool_call_timeout", 30))
                # 使用统一工具处理器处理所有工具调用
                try:
                    result = asyncio.run_coroutine_threadsafe(
                        conn.func_handler.handle_llm_function_call(
                            conn, function_call_data
                        ),
                        conn.loop,
                    ).result(timeout=tool_call_timeout)
                except Exception as e:
                    conn.logger.bind(tag=TAG).error(f"工具调用失败: {e}")
                    result = ActionResponse(
                        action=Action.ERROR, result="工具调用超时，请一会再试下哈", response="工具调用超时，请一会再试下哈"
                    )

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
                        text = result.result
                        conn.dialogue.put(Message(role="tool", content=text))
                        llm_result = conn.intent.replyResult(text, original_text)
                        if llm_result is None:
                            llm_result = text
                        spoken = speak_txt(conn, llm_result)
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
