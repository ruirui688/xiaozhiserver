"""Keep queued LLM failure speech alive until the normal TTS completion."""

from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from core.utils.util import get_system_error_response


def queue_llm_error_reply(conn, sentence_id, generation, abort_generation, depth, text=None):
    if conn.client_abort or conn.abort_generation != abort_generation:
        return False
    snapshot = conn.reply_lifecycle.snapshot()
    if generation is not None and (
        not snapshot.is_active or snapshot.generation != generation
        or snapshot.sentence_id != sentence_id
    ):
        return False
    text = text or get_system_error_response(conn.config)
    conn.tts.store_tts_text(sentence_id, text)
    conn.tts.tts_text_queue.put(TTSMessageDTO(
        sentence_id=sentence_id,
        sentence_type=SentenceType.MIDDLE,
        content_type=ContentType.TEXT,
        content_detail=text,
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
