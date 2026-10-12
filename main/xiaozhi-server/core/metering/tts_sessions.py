"""Keep accounting identity separate from the currently playable sentence."""

from dataclasses import replace

from .context import current_scope
from .recorder import begin_call
from .volcengine import tts_usage


def start(provider, session_id):
    calls = getattr(provider, "_billing_tts_calls", None)
    if calls is None:
        calls = provider._billing_tts_calls = {}
    sentence_scopes = getattr(getattr(provider, "conn", None), "_billing_sentence_scopes", {})
    scope = sentence_scopes.get(session_id) or current_scope.get() or getattr(provider, "_billing_scope", None)
    if scope:
        purpose = scope.purpose
        is_external = getattr(provider, "_is_exact_text", None)
        if is_external and is_external(session_id):
            purpose = "EXTERNAL_TTS"
        scope = replace(scope, sentence_id=str(session_id), purpose=purpose)
    call = begin_call(provider, "TTS", scope=scope, request_id=session_id)
    calls[session_id] = call
    return call


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
