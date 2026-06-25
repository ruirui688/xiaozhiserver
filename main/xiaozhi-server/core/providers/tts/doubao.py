from core.providers.tts.base import TTSProviderBase


DEPRECATION_MESSAGE = (
    "DoubaoTTS HTTP provider 已不再作为本项目有效豆包 TTS 入口；"
    "请使用 TTS.DoubaoSeedTTS2Stream，resource_id 固定为 seed-tts-2.0，"
    "并通过 api_key 发送 X-Api-Key。"
)


class TTSProvider(TTSProviderBase):
    def __init__(self, config, delete_audio_file):
        super().__init__(config, delete_audio_file)
        raise RuntimeError(DEPRECATION_MESSAGE)

    async def text_to_speak(self, text, output_file):
        raise RuntimeError(DEPRECATION_MESSAGE)
