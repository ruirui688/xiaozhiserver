import json
import copy
import os
import re
import uuid
from urllib.parse import urlsplit, urlunsplit
from aiohttp import web
from config.logger import setup_logging
from core.api.base_handler import BaseHandler
from core.utils.util import get_vision_url, is_valid_image_file
from core.utils.vllm import create_instance
from core.utils.model_config import get_runtime_config, get_runtime_type
from config.config_loader import get_private_config_from_api
from core.utils.auth import AuthToken
import base64
from typing import Tuple, Optional
from plugins_func.register import Action

TAG = __name__

# 设置最大文件大小为5MB
MAX_FILE_SIZE = 5 * 1024 * 1024
IMAGE_EXTENSIONS = {
    b"\xff\xd8\xff": ".jpg",
    b"\x89PNG\r\n\x1a\n": ".png",
    b"GIF87a": ".gif",
    b"GIF89a": ".gif",
    b"BM": ".bmp",
    b"RIFF": ".webp",
}


class VisionHandler(BaseHandler):
    def __init__(self, config: dict):
        super().__init__(config)
        # 初始化认证工具
        self.auth = AuthToken(config["server"]["auth_key"])
        self.image_dir = os.path.join(os.getcwd(), "data", "vision")

    def _create_error_response(self, message: str) -> dict:
        """创建统一的错误响应格式"""
        return {"success": False, "message": message}

    def _verify_auth_token(self, request) -> Tuple[bool, Optional[str]]:
        """验证认证token"""
        # 测试模式：允许特定测试令牌或跳过验证
        auth_header = request.headers.get("Authorization", "")
        client_id = request.headers.get("Client-Id", "")

        # 允许测试客户端跳过认证
        if client_id == "web_test_client":
            device_id = request.headers.get("Device-Id", "test_device")
            return True, device_id

        if not auth_header.startswith("Bearer "):
            return False, None

        token = auth_header[7:]  # 移除"Bearer "前缀
        return self.auth.verify_token(token)

    def _get_image_extension(self, image_data: bytes) -> Optional[str]:
        for signature, extension in IMAGE_EXTENSIONS.items():
            if image_data.startswith(signature):
                if signature == b"RIFF" and image_data[8:12] != b"WEBP":
                    continue
                return extension
        return None

    def _build_image_url(self, filename: str) -> str:
        vision_url = get_vision_url(self.config)
        parsed = urlsplit(vision_url)
        marker = "/mcp/vision/explain"
        path = parsed.path or ""
        prefix = path.split(marker)[0] if marker in path else ""
        image_path = f"{prefix}/mcp/vision/images/{filename}"
        return urlunsplit((parsed.scheme, parsed.netloc, image_path, "", ""))

    def _save_image(
        self, device_id: str, image_data: bytes, image_extension: str
    ) -> tuple[str, str]:
        os.makedirs(self.image_dir, exist_ok=True)
        safe_device_id = "".join(
            c if c.isalnum() or c in ("-", "_") else "_" for c in device_id
        ) or "unknown"
        filename = f"{safe_device_id}_{uuid.uuid4().hex}{image_extension}"
        file_path = os.path.join(self.image_dir, filename)
        with open(file_path, "wb") as image_file:
            image_file.write(image_data)

        image_url = self._build_image_url(filename)
        return filename, image_url

    async def handle_post(self, request):
        """处理 MCP Vision POST 请求"""
        response = None  # 初始化response变量
        try:
            # 验证token
            is_valid, token_device_id = self._verify_auth_token(request)
            if not is_valid:
                response = web.Response(
                    text=json.dumps(
                        self._create_error_response("无效的认证token或token已过期")
                    ),
                    content_type="application/json",
                    status=401,
                )
                return response

            # 获取请求头信息
            device_id = request.headers.get("Device-Id", "")
            client_id = request.headers.get("Client-Id", "")
            if device_id != token_device_id:
                raise ValueError("设备ID与token不匹配")
            # 解析multipart/form-data请求
            reader = await request.multipart()

            # 读取question字段
            question_field = await reader.next()
            if question_field is None:
                raise ValueError("缺少问题字段")
            question = await question_field.text()
            self.logger.bind(tag=TAG).debug(f"Question: {question}")

            # 读取图片文件
            image_field = await reader.next()
            if image_field is None:
                raise ValueError("缺少图片文件")

            # 读取图片数据
            image_data = await image_field.read()
            if not image_data:
                raise ValueError("图片数据为空")

            # 检查文件大小
            if len(image_data) > MAX_FILE_SIZE:
                raise ValueError(
                    f"图片大小超过限制，最大允许{MAX_FILE_SIZE/1024/1024}MB"
                )

            # 检查文件格式
            if not is_valid_image_file(image_data):
                raise ValueError(
                    "不支持的文件格式，请上传有效的图片文件（支持JPEG、PNG、GIF、BMP、WEBP格式）"
                )
            image_extension = self._get_image_extension(image_data)
            if image_extension not in {".jpg", ".png", ".gif", ".bmp", ".webp"}:
                raise ValueError("不支持的图片文件格式")

            # 将图片转换为base64编码
            image_base64 = base64.b64encode(image_data).decode("utf-8")

            # 如果开启了智控台，则从智控台获取模型配置
            current_config = copy.deepcopy(self.config)
            read_config_from_api = current_config.get("read_config_from_api", False)
            if read_config_from_api:
                current_config = await get_private_config_from_api(
                    current_config,
                    device_id,
                    client_id,
                )

            select_vllm_module = current_config["selected_module"].get("VLLM")
            if not select_vllm_module:
                raise ValueError("您还未设置默认的视觉分析模块")

            vllm_type = get_runtime_type(current_config, "VLLM", select_vllm_module)
            vllm_config = get_runtime_config(current_config, "VLLM", select_vllm_module)

            if not vllm_type:
                raise ValueError(f"无法找到VLLM模块对应的供应器{vllm_type}")

            vllm = create_instance(vllm_type, vllm_config)

            image_filename, image_url = self._save_image(
                device_id, image_data, image_extension
            )
            result = vllm.response(question, image_base64)

            return_json = {
                "success": True,
                "action": Action.RESPONSE.name,
                "response": result,
                "image_url": image_url,
                "image_id": image_filename,
            }

            response = web.Response(
                text=json.dumps(return_json, separators=(",", ":")),
                content_type="application/json",
            )
        except ValueError as e:
            self.logger.bind(tag=TAG).error(f"MCP Vision POST请求异常: {e}")
            return_json = self._create_error_response(str(e))
            response = web.Response(
                text=json.dumps(return_json, separators=(",", ":")),
                content_type="application/json",
            )
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"MCP Vision POST请求异常: {e}")
            return_json = self._create_error_response("处理请求时发生错误")
            response = web.Response(
                text=json.dumps(return_json, separators=(",", ":")),
                content_type="application/json",
            )
        finally:
            if response:
                self._add_cors_headers(response)
            return response

    async def handle_get(self, request):
        """处理 MCP Vision GET 请求"""
        try:
            vision_explain = get_vision_url(self.config)
            if vision_explain and len(vision_explain) > 0 and "null" != vision_explain:
                message = (
                    f"MCP Vision 接口运行正常，视觉解释接口地址是：{vision_explain}"
                )
            else:
                message = "MCP Vision 接口运行不正常，请打开data目录下的.config.yaml文件，找到【server.vision_explain】，设置好地址"

            response = web.Response(text=message, content_type="text/plain")
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"MCP Vision GET请求异常: {e}")
            return_json = self._create_error_response("服务器内部错误")
            response = web.Response(
                text=json.dumps(return_json, separators=(",", ":")),
                content_type="application/json",
            )
        finally:
            self._add_cors_headers(response)
            return response

    async def handle_image(self, request):
        """提供视觉分析图片下载。"""
        try:
            filename = request.match_info.get("filename", "")
            filename = os.path.basename(filename)
            if not re.match(r"^[A-Za-z0-9_\-]+\.(jpg|jpeg|png|gif|bmp|webp)$", filename, re.IGNORECASE):
                raise web.HTTPBadRequest(text="invalid filename")

            image_dir_real = os.path.realpath(self.image_dir)
            image_path = os.path.realpath(os.path.join(self.image_dir, filename))
            if not image_path.startswith(image_dir_real + os.sep):
                raise web.HTTPForbidden(text="forbidden")
            if not os.path.isfile(image_path):
                raise web.HTTPNotFound(text="image not found")

            response = web.FileResponse(path=image_path)
            response.headers["Cache-Control"] = "private, max-age=86400"
        except web.HTTPError as e:
            response = e
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"MCP Vision 图片下载异常: {e}")
            response = web.Response(text="image download error", status=500)
        finally:
            self._add_cors_headers(response)
            return response
