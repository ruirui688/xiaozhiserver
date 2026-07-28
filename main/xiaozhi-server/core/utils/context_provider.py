import html
from typing import Dict, Any

import httpx

from config.logger import setup_logging
from core.utils.prompt_budget import compact_dynamic_context

TAG = __name__


class ContextDataProvider:
    """数据上下文填充，负责从配置的API获取数据"""
    
    def __init__(self, config: Dict[str, Any], logger=None):
        self.config = config
        self.logger = logger or setup_logging()
        self.context_data = ""

    def fetch_all(self, device_id: str) -> str:
        """获取所有配置的上下文数据"""
        context_providers = self.config.get("context_providers", [])
        if not context_providers:
            return ""

        formatted_lines = []
        for provider_index, provider in enumerate(context_providers, start=1):
            if not isinstance(provider, dict):
                continue
            url = provider.get("url")
            headers = provider.get("headers", {})

            if not url:
                continue

            try:
                headers = headers.copy() if isinstance(headers, dict) else {}
                # 将 device_id 添加到请求头
                headers["device-id"] = device_id
                
                # 发送请求
                response = httpx.get(url, headers=headers, timeout=3)
                
                if response.status_code == 200:
                    result = response.json()
                    if isinstance(result, dict):
                        if result.get("code") == 0:
                            data = result.get("data")
                            # 格式化数据
                            if isinstance(data, dict):
                                for k, v in data.items():
                                    formatted_lines.append(
                                        f"- {html.escape(str(k), quote=False)}："
                                        f"{html.escape(str(v), quote=False)}"
                                    )
                            elif isinstance(data, list):
                                for item in data:
                                    formatted_lines.append(
                                        f"- {html.escape(str(item), quote=False)}"
                                    )
                            else:
                                formatted_lines.append(
                                    f"- {html.escape(str(data), quote=False)}"
                                )
                        else:
                            self.logger.bind(tag=TAG).warning(
                                f"上下文接口 #{provider_index} 返回业务错误"
                            )
                    else:
                        self.logger.bind(tag=TAG).warning(
                            f"上下文接口 #{provider_index} 返回的不是JSON字典"
                        )
                else:
                    self.logger.bind(tag=TAG).warning(
                        f"上下文接口 #{provider_index} 请求失败: {response.status_code}"
                    )
            except Exception as e:
                self.logger.bind(tag=TAG).error(
                    f"获取上下文数据 #{provider_index} 失败: {type(e).__name__}"
                )

        # 将所有格式化后的行拼接成一个字符串
        self.context_data = compact_dynamic_context("\n".join(formatted_lines))
        if self.context_data:
            self.logger.bind(tag=TAG).debug(
                f"已注入动态上下文数据: context_chars={len(self.context_data)}"
            )
        return self.context_data
