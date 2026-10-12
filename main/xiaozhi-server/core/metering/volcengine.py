"""Known supplier fields only; local measurements are labelled separately."""

import json


def asr_usage(call, payload, final=False):
    if not isinstance(payload, dict):
        return
    audio = payload.get("audio_info")
    if isinstance(audio, dict) and audio.get("duration") is not None:
        call.usage("audio_duration", audio["duration"], "milliseconds", complete=final)


def tts_usage(call, payload):
    if isinstance(payload, (str, bytes)):
        try:
            payload = json.loads(payload)
        except (ValueError, UnicodeError):
            return
    if not isinstance(payload, dict):
        return
    usage = payload.get("usage")
    if isinstance(usage, dict) and usage.get("text_words") is not None:
        call.usage("billable_characters", usage["text_words"], "characters", complete=True)


def realtime_usage(call, payload):
    if not isinstance(payload, dict):
        return
    usage = payload.get("usage")
    if not isinstance(usage, dict):
        return
    for metric in ("input_tokens", "output_tokens", "total_tokens"):
        if usage.get(metric) is not None:
            call.usage(metric, usage[metric], complete=usage.get("total_tokens") is not None)
    for direction in ("input", "output"):
        detail = usage.get(f"{direction}_token_details") or usage.get(f"{direction}_tokens_details")
        if isinstance(detail, dict):
            for metric in ("text_tokens", "audio_tokens", "cached_tokens"):
                if detail.get(metric) is not None:
                    canonical = f"cached_{direction}_tokens" if metric == "cached_tokens" else f"{direction}_{metric}"
                    call.usage(canonical, detail[metric])
