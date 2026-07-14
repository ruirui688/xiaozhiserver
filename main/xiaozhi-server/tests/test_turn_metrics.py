import time

from core.utils.turn_metrics import TurnMetrics


def test_asr_metrics_separate_response_tail_and_finalize_latency():
    metrics = TurnMetrics()
    for _ in range(26):
        metrics.record_vad_frame(True, 1, 20)
    time.sleep(0.01)
    metrics.record_asr_first_text(2)
    metrics.begin_turn([b"x"] * 26, "opus")
    time.sleep(0.01)

    metrics.record_asr(3, True, 5)

    payload = metrics.build_user_metrics()
    asr = payload["latency_ms"]["asr"]
    assert asr["finalize_ms"] == 3
    assert asr["response_ms"] >= 10
    assert asr["total_ms"] == asr["response_ms"]
    assert asr["audio_duration_ms"] == payload["input"]["estimated_audio_duration_ms"]
    assert asr["first_text_ms"] >= 10
    assert asr["first_text_ms"] < asr["response_ms"]
    assert asr["first_text_length"] == 2
    assert asr["tail_latency_ms"] == max(
        0,
        asr["response_ms"] - payload["input"]["estimated_audio_duration_ms"],
    )


def test_llm_estimated_tokens_include_input_and_output():
    metrics = TurnMetrics()
    metrics.ensure_text_turn()
    metrics.record_llm_started(
        tools_enabled=True,
        depth=0,
        dialogue=[
            {"role": "system", "content": "你是小智。"},
            {"role": "user", "content": "我在哪里工作？"},
        ],
        functions=[
            {
                "type": "function",
                "function": {"name": "get_weather", "parameters": {}},
            }
        ],
    )

    metrics.record_llm_complete("你在常州新北区工作。", None, False, 0)

    payload = metrics.build_assistant_metrics("你在常州新北区工作。")
    tokens = payload["tokens"]
    assert tokens["prompt_tokens"] > 0
    assert tokens["completion_tokens"] > 0
    assert tokens["total_tokens"] == tokens["prompt_tokens"] + tokens["completion_tokens"]
    assert tokens["source"] == "estimated_input_output_chars"
