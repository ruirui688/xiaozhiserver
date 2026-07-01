import json
import requests
from typing import TYPE_CHECKING

from config.logger import setup_logging
from plugins_func.register import register_function, ToolType, ActionResponse, Action

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()

SEARCH_FROM_LOCAL_KNOWLEDGE_FUNCTION_DESC = {
    "type": "function",
    "function": {
        "name": "search_from_local_knowledge",
        "description": (
            "从本地知识库中查询事实、资料、文档内容或业务知识。"
            "不要用于播放音乐、换歌、查询歌单/音乐库、控制设备或执行系统动作；"
            "用户要听歌、播放歌曲、换一首或问本地有哪些歌时，应使用音乐播放相关工具。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "question": {
                    "type": "string",
                    "description": (
                        "要查询知识库的问题。必须基于用户当前原话和对话中已经明确的上下文生成，"
                        "不要擅自加入用户没有提到的对象、品类、场景或用途。"
                    ),
                }
            },
            "required": ["question"],
        },
    },
}


# TODO: 知识库结果与记忆结果是两个独立的来源，LLM 应当交叉验证两者的一致性。
# 当用户询问个性化信息(记忆)和事实性知识(知识库)时，目前的实现分别返回独立的上下文块。
# 未来应增加编排层(Orchestration Layer)，自动合并/去重/对比知识库与记忆的结果，
# 并在两者冲突时通知用户。


@register_function(
    "search_from_local_knowledge",
    SEARCH_FROM_LOCAL_KNOWLEDGE_FUNCTION_DESC,
    ToolType.SYSTEM_CTL,
)
def search_from_local_knowledge(conn: "ConnectionHandler", question=None):
    question = str(question) if question is not None else ""
    knowledge_config = conn.config.get("plugins", {}).get("search_from_local_knowledge", {})
    dataset_ids = knowledge_config.get("dataset_ids", [])
    api_config = conn.config.get("manager-api", {})
    api_url = api_config.get("url")
    api_secret = api_config.get("secret")

    if not api_url or not api_secret:
        logger.bind(tag=TAG).error("manager-api配置缺失，无法查询本地知识库")
        return ActionResponse(Action.RESPONSE, None, "本地知识库配置错误，请稍后再试")
    if not dataset_ids:
        return ActionResponse(Action.RESPONSE, None, "未配置本地知识库数据集")

    headers = {"Authorization": f"Bearer {api_secret}", "Content-Type": "application/json"}
    payload = {"question": question, "dataset_ids": dataset_ids}

    try:
        response = requests.post(
            f"{api_url}/config/knowledge/retrieval",
            json=payload,
            headers=headers,
            timeout=8,
        )
        response.encoding = "utf-8"
        response.raise_for_status()
        result = response.json()

        if result.get("code") != 0:
            return ActionResponse(Action.RESPONSE, None, result.get("msg", "本地知识库查询失败"))

        chunks = result.get("data", {}).get("chunks", [])
        contents = []
        for chunk in chunks:
            content = chunk.get("content", "")
            document_name = chunk.get("document_name")
            if content:
                if document_name:
                    contents.append(f"来源：{document_name}\n{content}")
                else:
                    contents.append(str(content))

        if contents:
            context_text = f"# 关于问题【{question}】查到本地知识库如下\n"
            context_text += "\n\n---\n\n".join(contents[:5])
        else:
            context_text = "根据本地知识库查询结果，没有相关信息。"
        return ActionResponse(Action.REQLLM, context_text, None)

    except requests.exceptions.RequestException as e:
        logger.bind(tag=TAG).error(f"本地知识库请求失败：{str(e)}")
        return ActionResponse(Action.RESPONSE, None, "本地知识库接口请求失败，请稍后再试")
    except (json.JSONDecodeError, ValueError) as e:
        logger.bind(tag=TAG).error(f"本地知识库响应解析失败：{str(e)}")
        return ActionResponse(Action.RESPONSE, None, "本地知识库响应解析失败")
    except Exception as e:
        logger.bind(tag=TAG).error(f"本地知识库处理异常：{str(e)}")
        return ActionResponse(Action.RESPONSE, None, f"本地知识库处理异常：{str(e)}")
