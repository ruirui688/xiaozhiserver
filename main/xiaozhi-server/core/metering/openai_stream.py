"""Meter individual HTTP attempts, retaining usage even when a stream is closed."""

import time
import uuid
import openai

from .recorder import begin_call


def record_openai_usage(call, usage):
    if hasattr(usage, "model_dump"):
        usage = usage.model_dump()
    if not isinstance(usage, dict):
        return
    complete = usage.get("prompt_tokens") is not None and usage.get("completion_tokens") is not None
    for name, metric in {
        "prompt_tokens": "input_tokens", "completion_tokens": "output_tokens", "total_tokens": "total_tokens",
        "prompt_cache_hit_tokens": "cached_input_tokens", "prompt_cache_miss_tokens": "cache_miss_input_tokens",
        "input_tokens": "input_tokens", "output_tokens": "output_tokens",
    }.items():
        if usage.get(name) is not None:
            call.usage(metric, usage[name], complete=complete)
    for group in ("prompt_tokens_details", "completion_tokens_details", "input_token_details", "output_token_details"):
        details = usage.get(group)
        if isinstance(details, dict):
            for name in ("cached_tokens", "audio_tokens", "text_tokens", "reasoning_tokens", "accepted_prediction_tokens", "rejected_prediction_tokens"):
                if details.get(name) is not None:
                    direction = "input" if group.startswith(("prompt", "input")) else "output"
                    metric = (f"cached_{direction}_tokens" if name == "cached_tokens" else
                              f"{direction}_{name}" if name in {"audio_tokens", "text_tokens"} else name)
                    call.usage(metric, details[name])


def _retryable(exc):
    if isinstance(exc, openai.APIConnectionError):
        return True
    if isinstance(exc, openai.APIStatusError):
        hint = exc.response.headers.get("x-should-retry")
        if hint in {"true", "false"}:
            return hint == "true"
        return exc.status_code in {408, 409, 429} or exc.status_code >= 500
    return False


def metered_stream(provider, request_params):
    client = provider.client
    # Make SDK transport attempts visible instead of one misleading logical call.
    retries = max(0, int(getattr(client, "max_retries", 0)))
    if hasattr(client, "with_options"):
        client = client.with_options(max_retries=0)
    logical_call_id = str(uuid.uuid4())
    for attempt in range(retries + 1):
        call = begin_call(provider, "LLM", logical_call_id=logical_call_id)
        stream = None
        status = "UNKNOWN"
        try:
            try:
                stream = client.chat.completions.create(**request_params)
            except Exception as exc:
                status = "FAILED"
                response = getattr(exc, "response", None)
                if response is not None:
                    call.request_id(response.headers.get("x-request-id"))
                if attempt < retries and _retryable(exc):
                    call.finish(status)
                    time.sleep(min(0.5 * 2 ** attempt, 8))
                    continue
                raise
            response = getattr(stream, "response", None)
            request_id = None
            if response is not None:
                request_id = response.headers.get("x-request-id")
                call.request_id(request_id)
            for chunk in provider._iter_stream_with_deadline(stream):
                call.request_id(request_id or getattr(chunk, "id", None))
                record_openai_usage(call, getattr(chunk, "usage", None))
                yield chunk
            status = "SUCCESS"
            return
        except GeneratorExit:
            status = "CANCELLED"
            raise
        except BaseException:
            status = "FAILED"
            raise
        finally:
            try:
                if stream is not None:
                    stream.close()
            finally:
                call.finish(status)
