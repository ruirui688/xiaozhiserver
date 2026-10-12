"""Only manager-issued capabilities can establish billing ownership."""

import contextvars
import functools
import inspect
import json
from contextlib import contextmanager
from dataclasses import dataclass, replace


current_scope = contextvars.ContextVar("billing_scope", default=None)


@dataclass(frozen=True)
class BillingScope:
    context_json: str
    selected_json: str
    purpose: str
    session_id: str = ""
    turn_id: str = ""
    sentence_id: str = ""

    def context(self):
        return json.loads(self.context_json)

    def model(self, service_type, provider=None):
        models = self.context().get("models", [])
        if isinstance(models, dict):
            models = [dict(value, model_id=key) for key, value in models.items() if isinstance(value, dict)]
        service = "LLM" if service_type in {"REALTIME", "VLLM"} else service_type
        candidates = [m for m in models if str(m.get("service_type", "")).upper() == service]
        selected = json.loads(self.selected_json).get(service)
        explicit_id = getattr(provider, "_billing_model_id", None)
        if explicit_id:
            return next((m for m in candidates if m.get("model_id") == explicit_id), None)
        # Dedicated intent/memory models may differ from the conversational LLM.
        model_name = getattr(provider, "model_name", None)
        if model_name:
            matching = [m for m in candidates if m.get("model_name") == model_name]
            if len(matching) == 1:
                return matching[0]
        return next((m for m in candidates if m.get("model_id") == selected), None)


def snapshot(conn=None, *, config=None, purpose="CONVERSATION", session_id=None,
             turn_id=None, sentence_id=None):
    config = config if config is not None else getattr(conn, "config", {})
    runtime_purpose = (config or {}).get("_billing_purpose")
    if runtime_purpose in {"BENCHMARK", "MODEL_TEST"}:
        purpose = runtime_purpose if purpose == "CONVERSATION" else f"{runtime_purpose}_{purpose}"
    context = (config or {}).get("billing_context")
    if not isinstance(context, dict) or not all(context.get(k) for k in ("context_id", "models")) or not (context.get("context_token") or context.get("token")):
        return None
    context_purpose = context.get("purpose")
    if context_purpose in {"BENCHMARK", "MODEL_TEST", "MODEL_ASSISTANT"} and not purpose.startswith(context_purpose):
        purpose = context_purpose if purpose == "CONVERSATION" else f"{context_purpose}_{purpose}"
    return BillingScope(
        json.dumps(context, separators=(",", ":")),
        json.dumps((config or {}).get("selected_module", {})),
        purpose,
        str(session_id or getattr(conn, "session_id", "") or ""),
        str(turn_id or ""),
        str(sentence_id or getattr(conn, "sentence_id", "") or ""),
    )


def bind_provider(provider, conn, purpose="CONVERSATION"):
    """Freeze the provider's owning configuration before it starts background work."""
    provider._billing_scope = snapshot(conn, purpose=purpose)


@contextmanager
def usage_scope(scope, purpose=None):
    if scope is not None and purpose is not None:
        scope = replace(scope, purpose=purpose)
    token = current_scope.set(scope)
    try:
        yield
    finally:
        current_scope.reset(token)


def connection_scope(purpose, conn_arg=0):
    """Capture at entry, including work moved with asyncio.to_thread."""
    def decorate(function):
        def capture(args, kwargs):
            conn = args[conn_arg] if len(args) > conn_arg else kwargs.get("conn")
            return snapshot(conn, purpose=purpose, turn_id=kwargs.get("turn_id"))
        if inspect.iscoroutinefunction(function):
            @functools.wraps(function)
            async def async_wrapped(*args, **kwargs):
                with usage_scope(capture(args, kwargs)):
                    return await function(*args, **kwargs)
            return async_wrapped
        @functools.wraps(function)
        def wrapped(*args, **kwargs):
            with usage_scope(capture(args, kwargs)):
                return function(*args, **kwargs)
        return wrapped
    return decorate


def provider_scope(purpose):
    def decorate(function):
        @functools.wraps(function)
        async def wrapped(self, *args, **kwargs):
            with usage_scope(getattr(self, "_billing_scope", None), purpose):
                return await function(self, *args, **kwargs)
        return wrapped
    return decorate


async def scoped_awaitable(awaitable, scope):
    with usage_scope(scope):
        return await awaitable


class _ScopedStream:
    def __init__(self, stream, scope):
        self.stream, self.scope = iter(stream), scope

    def __iter__(self):
        return self

    def __next__(self):
        with usage_scope(self.scope):
            return next(self.stream)

    def close(self):
        close = getattr(self.stream, "close", None)
        if close:
            with usage_scope(self.scope):
                close()


def conversation_stream(conn, stream, turn_id, sentence_id):
    scope = current_scope.get()
    if scope:
        scope = replace(scope, turn_id=str(turn_id or ""), sentence_id=str(sentence_id or ""))
        scopes = getattr(conn, "_billing_sentence_scopes", None)
        if scopes is None:
            scopes = conn._billing_sentence_scopes = {}
        scopes[sentence_id] = scope
        while len(scopes) > 256:
            scopes.pop(next(iter(scopes)))
    return _ScopedStream(stream, scope)
