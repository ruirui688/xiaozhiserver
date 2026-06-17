import openai
import json
from config.logger import setup_logging
from core.utils.util import check_model_key
from core.providers.vllm.base import VLLMProviderBase

TAG = __name__
logger = setup_logging()

DEFAULT_VISION_PROMPT = """你是设备的视觉助手，用自然、口语化的中文回答用户关于图片的问题。
先给一句直接结论，再补充2到3个关键细节。
不要使用编号、标题、报告格式或“以下是”“画面的主体是”“占据了图像”等生硬表达。
如果画面不清楚，要直接说明不确定，只描述能看清的内容。"""


class VLLMProvider(VLLMProviderBase):
    def __init__(self, config):
        self.model_name = config.get("model_name")
        self.api_key = config.get("api_key")
        self.prompt = (config.get("prompt") or DEFAULT_VISION_PROMPT).strip()
        if "base_url" in config:
            self.base_url = config.get("base_url")
        else:
            self.base_url = config.get("url")

        param_defaults = {
            "max_tokens": (500, int),
            "temperature": (0.7, lambda x: round(float(x), 1)),
            "top_p": (1.0, lambda x: round(float(x), 1)),
        }

        for param, (default, converter) in param_defaults.items():
            value = config.get(param)
            try:
                setattr(
                    self,
                    param,
                    converter(value) if value not in (None, "") else default,
                )
            except (ValueError, TypeError):
                setattr(self, param, default)

        model_key_msg = check_model_key("VLLM", self.api_key)
        if model_key_msg:
            logger.bind(tag=TAG).error(model_key_msg)
        self.client = openai.OpenAI(api_key=self.api_key, base_url=self.base_url)

    def _build_question(self, question):
        question = (question or "").strip()
        if not question:
            question = "请描述当前画面。"
        return (
            f"用户的问题：{question}\n\n"
            "请只基于图片内容回答，使用简洁自然的中文。"
        )

    def response(self, question, base64_image):
        try:
            messages = [
                {"role": "system", "content": self.prompt},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": self._build_question(question)},
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:image/jpeg;base64,{base64_image}"
                            },
                        },
                    ],
                }
            ]

            response = self.client.chat.completions.create(
                model=self.model_name,
                messages=messages,
                stream=False,
                max_tokens=self.max_tokens,
                temperature=self.temperature,
                top_p=self.top_p,
            )

            return response.choices[0].message.content

        except Exception as e:
            logger.bind(tag=TAG).error(f"Error in response generation: {e}")
            raise
