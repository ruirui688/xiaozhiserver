"""Keep queued LLM failure speech alive until the normal TTS completion."""

from core.metering.admission import PlatformAdmissionError
from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from core.utils.util import get_system_error_response

# A managed deployment cannot top up, so a refusal has to tell the user the real next
# step. Only the reason is spoken; no upstream secret, address or payload is included.
ADMISSION_NOTICES = {
    "ALLOWANCE_EXHAUSTED": "服务额度已用完，请在智控台提交额度申请。",
    "WORKSPACE_ALLOWANCE_LIMIT": "本工作空间已达使用上限，请联系负责人。",
    "TEAM_ALLOWANCE_LIMIT": "本工作空间已达使用上限，请联系负责人。",
    "PAYER_FROZEN": "账号服务已暂停，请在智控台查看服务状态。",
    "WORKSPACE_DISABLED": "当前空间已停用，请在智控台查看空间状态。",
    "PENDING_CREDENTIAL": "模型服务待开通，请在智控台查看服务状态。",
    "CREDENTIAL_SERVICE_NOT_READY": "模型服务待开通，请在智控台查看服务状态。",
    "CREDENTIAL_CHANGED": "模型凭据已更新，请稍后重试。",
    "MODEL_NOT_ADMITTED": "当前模型不可用，请在智控台重新选择模型。",
    "PLATFORM_SERVICE_BUDGET_REQUIRED": "模型服务待开通，请在智控台查看服务状态。",
    "PLATFORM_CALL_DENIED": "模型服务暂不可用，请在智控台查看额度与服务状态。",
    "BOUNDED_USAGE_REQUIRED": "本次请求超出可用额度范围，请缩短内容后重试。",
}
MANAGED_NOTICE = "模型服务暂不可用，请在智控台查看额度与服务状态。"


def admission_notice(error):
    """Return the notice for a platform refusal, or None when the error is not one."""
    if not isinstance(error, PlatformAdmissionError):
        return None
    return ADMISSION_NOTICES.get(getattr(error, "code", None), MANAGED_NOTICE)


def queue_llm_error_reply(conn, sentence_id, generation, abort_generation, depth, text=None, error=None):
    if conn.client_abort or conn.abort_generation != abort_generation:
        return False
    snapshot = conn.reply_lifecycle.snapshot()
    if generation is not None and (
        not snapshot.is_active or snapshot.generation != generation
        or snapshot.sentence_id != sentence_id
    ):
        return False
    managed = getattr(getattr(conn, "llm", None), "_platform_managed", False)
    if text is None:
        # A managed refusal gets its real reason; every other failure keeps the generic text.
        text = (admission_notice(error) or MANAGED_NOTICE) if managed else get_system_error_response(conn.config)
    conn.tts.store_tts_text(sentence_id, text)
    conn.tts.tts_text_queue.put(TTSMessageDTO(
        sentence_id=sentence_id,
        sentence_type=SentenceType.MIDDLE,
        content_type=ContentType.FILE if managed else ContentType.TEXT,
        content_detail=text,
        content_file="config/assets/tts_notify.mp3" if managed else None,
        generation=generation,
    ))
    if depth == 0:
        conn.tts.tts_text_queue.put(TTSMessageDTO(
            sentence_id=sentence_id,
            sentence_type=SentenceType.LAST,
            content_type=ContentType.ACTION,
            generation=generation,
        ))
    # The caller must not terminate the generation while TTS is still working.
    return True
