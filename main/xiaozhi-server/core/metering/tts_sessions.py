"""Keep accounting identity separate from the currently playable sentence."""

from dataclasses import replace

from .context import current_scope
from .recorder import begin_call
from .recorder import begin_call_async
from .volcengine import tts_usage


def session_scope(provider, session_id):
    sentence_scopes = getattr(getattr(provider, "conn", None), "_billing_sentence_scopes", {})
    scope = sentence_scopes.get(session_id) or current_scope.get() or getattr(provider, "_billing_scope", None)
    if scope:
        purpose = scope.purpose
        is_external = getattr(provider, "_is_exact_text", None)
        if is_external and is_external(session_id):
            purpose = "EXTERNAL_TTS"
        scope = replace(scope, sentence_id=str(session_id), purpose=purpose)
    return scope


def _remember(provider, session_id, call):
    calls = getattr(provider, "_billing_tts_calls", None)
    if calls is None:
        calls = provider._billing_tts_calls = {}
    calls[session_id] = call
    return call


def start(provider, session_id):
    return _remember(provider, session_id, begin_call(provider, "TTS", scope=session_scope(provider, session_id), request_id=session_id))


async def start_async(provider, session_id, characters=256):
    call = await begin_call_async(provider, "TTS", scope=session_scope(provider, session_id),
                                  request_id=session_id, max_usage={"billable_characters": str(max(1, characters))})
    return _remember(provider, session_id, call)


def received(provider, session_id, payload, status=None):
    calls = getattr(provider, "_billing_tts_calls", {})
    call = calls.get(session_id)
    if call:
        tts_usage(call, payload)
        if status == "CANCELLED":
            # Cancellation ACK may precede the provider's final usage frame.
            call.status = "CANCELLED"
        elif status:
            call.finish("CANCELLED" if call.status == "CANCELLED" else status)
            calls.pop(session_id, None)


def close(provider, status="UNKNOWN"):
    calls = getattr(provider, "_billing_tts_calls", {})
    for call in list(calls.values()):
        call.finish("CANCELLED" if call.status == "CANCELLED" else status)
    calls.clear()
