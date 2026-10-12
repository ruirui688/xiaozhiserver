import os, json, uuid
from types import SimpleNamespace
from typing import Any, Dict, List

import requests
from google import generativeai as genai
from google.generativeai import types, GenerationConfig

from core.providers.llm.base import LLMProviderBase
from core.utils.util import check_model_key
from config.logger import setup_logging
from google.generativeai.types import GenerateContentResponse
from requests import RequestException

log = setup_logging()
TAG = __name__


def test_proxy(proxy_url: str, test_url: str) -> bool:
    try:
        resp = requests.get(test_url, proxies={"http": proxy_url, "https": proxy_url})
        return 200 <= resp.status_code < 400
    except RequestException:
        return False


def setup_proxy_env(http_proxy: str | None, https_proxy: str | None):
    """
    分别测试 HTTP 和 HTTPS 代理是否可用，并设置环境变量。
    如果 HTTPS 代理不可用但 HTTP 可用，会将 HTTPS_PROXY 也指向 HTTP。
    """
    test_http_url = "http://www.google.com"
    test_https_url = "https://www.google.com"

    ok_http = ok_https = False

    if http_proxy:
        ok_http = test_proxy(http_proxy, test_http_url)
        if ok_http:
            os.environ["HTTP_PROXY"] = http_proxy
            log.bind(tag=TAG).info(f"配置提供的Gemini HTTPS代理连通成功: {http_proxy}")
        else:
            log.bind(tag=TAG).warning(f"配置提供的Gemini HTTP代理不可用: {http_proxy}")

    if https_proxy:
        ok_https = test_proxy(https_proxy, test_https_url)
        if ok_https:
            os.environ["HTTPS_PROXY"] = https_proxy
            log.bind(tag=TAG).info(f"配置提供的Gemini HTTPS代理连通成功: {https_proxy}")
        else:
            log.bind(tag=TAG).warning(
                f"配置提供的Gemini HTTPS代理不可用: {https_proxy}"
            )

    # 如果https_proxy不可用，但http_proxy可用且能走通https，则复用http_proxy作为https_proxy
    if ok_http and not ok_https:
        if test_proxy(http_proxy, test_https_url):
            os.environ["HTTPS_PROXY"] = http_proxy
            ok_https = True
            log.bind(tag=TAG).info(f"复用HTTP代理作为HTTPS代理: {http_proxy}")

    if not ok_http and not ok_https:
        log.bind(tag=TAG).error(
            f"Gemini 代理设置失败: HTTP 和 HTTPS 代理都不可用，请检查配置"
        )
        raise RuntimeError("HTTP 和 HTTPS 代理都不可用，请检查配置")


class LLMProvider(LLMProviderBase):
    supports_native_function_call = True

    def __init__(self, cfg: Dict[str, Any]):
        self.model_name = cfg.get("model_name", "gemini-2.0-flash")
        self.api_key = cfg["api_key"]
        http_proxy = cfg.get("http_proxy")
        https_proxy = cfg.get("https_proxy")

        model_key_msg = check_model_key("LLM", self.api_key)
        if model_key_msg:
            log.bind(tag=TAG).error(model_key_msg)

        if http_proxy or https_proxy:
            log.bind(tag=TAG).info(
                f"检测到Gemini代理配置，开始测试代理连通性和设置代理环境..."
            )
            setup_proxy_env(http_proxy, https_proxy)
            log.bind(tag=TAG).info(
                f"Gemini 代理设置成功 - HTTP: {http_proxy}, HTTPS: {https_proxy}"
            )
        # 配置API密钥
        genai.configure(api_key=self.api_key)

        # 设置请求超时（秒）
        self.timeout = cfg.get("timeout", 120)  # 默认120秒

        self.gen_cfg = GenerationConfig(
            temperature=0.7,
            top_p=0.9,
            top_k=40,
            max_output_tokens=2048,
        )

    @staticmethod
    def _build_tools(funcs: List[Dict[str, Any]] | None):
        if not funcs:
            return None
        return [
            types.Tool(
                function_declarations=[
                    types.FunctionDeclaration(
                        name=f["function"]["name"],
                        description=f["function"].get("description", ""),
                        parameters=f["function"].get("parameters", {"type": "object"}),
                    )
                    for f in funcs
                ]
            )
        ]

    # Gemini文档提到，无需维护session-id，直接用dialogue拼接而成
    def response(self, session_id, dialogue, **kwargs):
        yield from self._generate(dialogue, None)

    def response_with_functions(self, session_id, dialogue, functions=None):
        yield from self._generate(dialogue, self._build_tools(functions))

    def _generate(self, dialogue, tools):
        role_map = {"assistant": "model", "user": "user"}
        contents: list = []
        system_parts = []
        tool_names = {}

        def append_content(role, parts):
            if contents and contents[-1]["role"] == role:
                contents[-1]["parts"].extend(parts)
            else:
                contents.append({"role": role, "parts": parts})

        # 拼接对话
        for m in dialogue:
            r = m["role"]

            if r == "system":
                text = str(m.get("content") or "")
                if text:
                    system_parts.append(text)
                continue

            if r == "assistant" and "tool_calls" in m:
                parts = []
                if m.get("content"):
                    parts.append({"text": str(m["content"])})
                for tc in m["tool_calls"]:
                    function = tc["function"]
                    tool_names[tc["id"]] = function["name"]
                    arguments = function.get("arguments") or "{}"
                    parts.append({"function_call": {
                        "name": function["name"],
                        "args": json.loads(arguments) if isinstance(arguments, str) else arguments,
                    }})
                append_content("model", parts)
                continue

            if r == "tool":
                name = tool_names.get(m.get("tool_call_id"))
                if not name:
                    raise ValueError("Gemini tool response has no matching function call")
                # Keep arbitrary result text as data inside a native response,
                # never as model-authored prose or a promoted system message.
                append_content("user", [{"function_response": {
                    "name": name,
                    "response": {"result": str(m.get("content") or "")},
                }}])
                continue

            if r not in role_map:
                raise ValueError("Unsupported Gemini message role")
            append_content(role_map[r], [{"text": str(m.get("content") or "")}])

        # Instructions change on every turn. A request-local model prevents
        # concurrent connections from overwriting each other's system rules.
        model = genai.GenerativeModel(
            self.model_name,
            system_instruction="\n\n".join(system_parts) or None,
        )
        stream: GenerateContentResponse = model.generate_content(
            contents=contents,
            generation_config=self.gen_cfg,
            tools=tools,
            stream=True,
            request_options={"timeout": self.timeout},
        )

        try:
            for chunk in stream:
                cand = chunk.candidates[0]
                function_calls = []
                for part in cand.content.parts:
                    # a) 函数调用-通常是最后一段话才是函数调用
                    if getattr(part, "function_call", None):
                        fc = part.function_call
                        function_calls.append(
                            SimpleNamespace(
                                id=uuid.uuid4().hex,
                                type="function",
                                function=SimpleNamespace(
                                    name=fc.name,
                                    arguments=json.dumps(
                                        dict(fc.args), ensure_ascii=False
                                    ),
                                ),
                            )
                        )
                    # b) 普通文本
                    if getattr(part, "text", None):
                        yield part.text if tools is None else (part.text, None)
                if function_calls:
                    yield None, function_calls
                    return

        finally:
            if tools is not None:
                yield None, None  # function‑mode 结束，返回哑包

    # 关闭stream，预留后续打断对话功能的功能方法，官方文档推荐打断对话要关闭上一个流，可以有效减少配额计费和资源占用
    @staticmethod
    def _safe_finish_stream(stream: GenerateContentResponse):
        if hasattr(stream, "resolve"):
            stream.resolve()  # Gemini SDK version ≥ 0.5.0
        elif hasattr(stream, "close"):
            stream.close()  # Gemini SDK version < 0.5.0
        else:
            for _ in stream:  # 兜底耗尽
                pass
