import time
from types import SimpleNamespace

from core.utils import turn_metrics as turn_metrics_module
from core.utils.turn_metrics import TurnMetrics


def test_vad_metrics_include_acoustic_gate_and_eos_fields(monkeypatch):
    clock = {"value": 100.0}
    monkeypatch.setattr(turn_metrics_module.time, "monotonic", lambda: clock["value"])
    monkeypatch.setattr(turn_metrics_module.time, "time", lambda: 1000.0)
    metrics = TurnMetrics()
    observation = SimpleNamespace(
        speech_probability=0.92,
        rms_dbfs=-38.0,
        decoded_samples=512,
    )
    decision = SimpleNamespace(
        noise_floor_dbfs=-50.0,
        snr_db=12.0,
        speech_started=True,
        speech_ended=False,
        rejection_reason=None,
    )
    metrics.record_vad_frame(
        True,
        1,
        20,
        observation=observation,
        decision=decision,
        gate_mode="enforce",
        preroll_ms=320,
    )
    clock["value"] = 100.35
    metrics.record_asr_final_text(2)
    metrics.begin_turn([b"x"], "opus")
    metrics.record_asr(7, True, 2)

    payload = metrics.build_user_metrics()
    vad = payload["stages"]["vad"]
    assert vad["decoded_duration_ms"] == 32
    assert vad["speech_gate"]["mode"] == "enforce"
    assert vad["speech_gate"]["preroll_ms"] == 320
    assert vad["speech_gate"]["snr_db_avg"] == 12.0
    assert payload["stages"]["asr"]["provider_finalize_ms"] == 7
    assert payload["stages"]["asr"]["eos_to_final_text_ms"] >= 0


def test_shadow_gate_eos_does_not_replace_actual_segmentation_eos(monkeypatch):
    clock = {"value": 100.0}
    monkeypatch.setattr(turn_metrics_module.time, "monotonic", lambda: clock["value"])
    monkeypatch.setattr(turn_metrics_module.time, "time", lambda: 1000.0)
    metrics = TurnMetrics()
    observation = SimpleNamespace(
        speech_probability=0.92,
        rms_dbfs=-38.0,
        decoded_samples=512,
    )
    started = SimpleNamespace(
        noise_floor_dbfs=-50.0,
        snr_db=12.0,
        speech_started=True,
        speech_ended=False,
        rejection_reason=None,
    )
    metrics.record_vad_frame(
        True, 1, 20, observation=observation, decision=started, gate_mode="shadow"
    )
    clock["value"] = 100.35
    ended = SimpleNamespace(
        noise_floor_dbfs=-50.0,
        snr_db=2.0,
        speech_started=False,
        speech_ended=True,
        rejection_reason="silence",
    )
    metrics.record_vad_frame(
        False, 1, 20, observation=observation, decision=ended, gate_mode="shadow"
    )
    clock["value"] = 101.0
    metrics.begin_turn([b"x"], "opus")

    vad = metrics.build_user_metrics()["stages"]["vad"]
    assert vad["speech_to_eos_ms"] == 1000
    assert vad["speech_gate"]["shadow_speech_to_eos_ms"] == 349


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
    asr = payload["stages"]["asr"]
    assert asr["provider_finalize_ms"] == 3
    assert asr["final_text_from_speech_ms"] >= 10
    assert payload["input"]["audio_duration_ms"] == 26 * 60
    assert asr["first_text_from_speech_ms"] >= 10
    assert asr["first_text_from_speech_ms"] < asr["final_text_from_speech_ms"]
    assert asr["first_text_length"] == 2
    assert asr["tail_after_audio_ms"] == max(
        0,
        asr["final_text_from_speech_ms"] - payload["input"]["audio_duration_ms"],
    )


def test_asr_response_ms_uses_final_text_time_not_later_wait(monkeypatch):
    current = {"monotonic": 100.0}
    monkeypatch.setattr(
        turn_metrics_module.time,
        "monotonic",
        lambda: current["monotonic"],
    )
    monkeypatch.setattr(turn_metrics_module.time, "time", lambda: 1000.0)

    metrics = TurnMetrics()
    for _ in range(10):
        metrics.record_vad_frame(True, 1, 20)
    current["monotonic"] = 101.0
    metrics.record_asr_first_text(2)
    current["monotonic"] = 103.0
    metrics.record_asr_final_text(5)
    metrics.begin_turn([b"x"] * 10, "opus")

    current["monotonic"] = 110.0
    metrics.record_asr(7, True, 5)

    asr = metrics.build_user_metrics()["stages"]["asr"]
    assert asr["first_text_from_speech_ms"] == 1000
    assert asr["final_text_from_speech_ms"] == 3000
    assert asr["provider_finalize_ms"] == 7
    assert asr["tail_after_audio_ms"] == 2400


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

    metrics.record_llm_complete("你在常州新北区工作。", None, False, 0, depth=0)

    payload = metrics.build_assistant_metrics("你在常州新北区工作。")
    tokens = payload["trace"]["totals"]["tokens"]
    assert tokens["prompt_tokens"] > 0
    assert tokens["completion_tokens"] > 0
    assert tokens["total_tokens"] == tokens["prompt_tokens"] + tokens["completion_tokens"]
    assert tokens["source"] == "estimated_input_output_chars"


def test_trace_preserves_recursive_llm_and_tool_steps(monkeypatch):
    current = {"monotonic": 100.0}
    monkeypatch.setattr(
        turn_metrics_module.time,
        "monotonic",
        lambda: current["monotonic"],
    )

    metrics = TurnMetrics()
    metrics.ensure_text_turn()
    metrics.record_llm_started(True, 0, dialogue=[{"role": "user", "content": "状态"}], functions=[])
    current["monotonic"] = 100.2
    metrics.record_llm_first_chunk(depth=0)
    current["monotonic"] = 101.1
    metrics.record_llm_complete("", None, True, 1, depth=0)
    metrics.record_tool_call("self_get_device_status", 965, action="REQLLM", depth=0)

    current["monotonic"] = 102.1
    metrics.record_llm_started(True, 1, dialogue=[{"role": "tool", "content": "{}"}], functions=[])
    current["monotonic"] = 102.4
    metrics.record_llm_first_chunk(depth=1)
    current["monotonic"] = 103.9
    metrics.record_llm_complete("设备正常", None, False, 0, depth=1)

    payload = metrics.build_assistant_metrics("设备正常")
    steps = payload["trace"]["steps"]
    assert [step["type"] for step in steps] == ["llm", "tool", "llm"]
    assert steps[0]["depth"] == 0
    assert steps[0]["total_ms"] == 1099
    assert steps[1]["name"] == "self_get_device_status"
    assert steps[1]["total_ms"] == 965
    assert steps[2]["depth"] == 1
    assert steps[2]["total_ms"] == 1800
    totals = payload["trace"]["totals"]
    assert totals["llm_call_count"] == 2
    assert totals["llm_model_ms"] == 2899
    assert totals["tool_call_count"] == 1
    assert totals["tool_ms"] == 965
    assert totals["model_tool_chain_ms"] == 3900


def test_tts_trace_separates_first_audio_generation_and_report_ready(monkeypatch):
    current = {"monotonic": 200.0}
    monkeypatch.setattr(
        turn_metrics_module.time,
        "monotonic",
        lambda: current["monotonic"],
    )

    metrics = TurnMetrics()
    turn_id = metrics.ensure_text_turn()
    metrics.bind_sentence("sentence-1", turn_id)
    metrics.record_tts_start("sentence-1")
    current["monotonic"] = 200.191
    metrics.record_tts_audio_frame(20)
    metrics.record_tts_audio_frame(21)
    current["monotonic"] = 202.024
    metrics.record_tts_generation_complete()
    current["monotonic"] = 203.248
    metrics.record_audio_delivery_complete()

    payload = metrics.build_assistant_metrics("测试")
    step = payload["trace"]["steps"][0]
    assert step["type"] == "tts"
    assert step["request_to_first_audio_ms"] == 191
    assert step["provider_generation_ms"] == 2024
    assert step["report_ready_ms"] == 3247
    assert step["audio_duration_ms"] == 120
    delivery = payload["trace"]["steps"][1]
    assert delivery["type"] == "audio_delivery"
    assert delivery["boundary"] == "server_websocket_send_complete"
    assert delivery["from_first_audio_ms"] == 3056
    assert delivery["device_playback_confirmed"] is False


def test_late_previous_tts_completion_does_not_clear_current_turn_metrics():
    metrics = TurnMetrics()

    previous_turn_id = metrics.ensure_text_turn()
    metrics.bind_sentence("sentence-previous", previous_turn_id)
    metrics.record_llm_started(False, 0, turn_id=previous_turn_id)
    metrics.record_llm_complete(
        "上一轮回复", None, False, 0, depth=0, turn_id=previous_turn_id
    )
    metrics.record_tts_start("sentence-previous")
    metrics.record_tts_audio_frame(20, "sentence-previous")

    current_turn_id = metrics.begin_turn([b"x"] * 10, "opus")
    metrics.record_asr(3, True, 5, turn_id=current_turn_id)
    metrics.bind_sentence("sentence-current", current_turn_id)
    metrics.record_llm_started(False, 0, turn_id=current_turn_id)
    metrics.record_llm_complete(
        "当前轮回复", None, False, 0, depth=0, turn_id=current_turn_id
    )
    metrics.record_tts_start("sentence-current")
    metrics.record_tts_audio_frame(21, "sentence-current")

    metrics.record_tts_generation_complete("sentence-previous")
    metrics.record_audio_delivery_complete("sentence-previous")
    previous_payload = metrics.build_assistant_metrics(
        "上一轮回复", sentence_id="sentence-previous"
    )

    metrics.record_tts_generation_complete("sentence-current")
    metrics.record_audio_delivery_complete("sentence-current")
    current_payload = metrics.build_assistant_metrics(
        "当前轮回复", sentence_id="sentence-current"
    )

    assert previous_payload["turn_id"] == previous_turn_id
    assert current_payload["turn_id"] == current_turn_id
    assert [step["type"] for step in previous_payload["trace"]["steps"]] == [
        "llm",
        "tts",
        "audio_delivery",
    ]
    assert [step["type"] for step in current_payload["trace"]["steps"]] == [
        "llm",
        "tts",
        "audio_delivery",
    ]
    assert current_payload["trace"]["totals"]["llm_call_count"] == 1


def test_discarded_sentence_late_tts_events_do_not_attach_to_new_turn():
    metrics = TurnMetrics()
    previous_turn_id = metrics.ensure_text_turn()
    metrics.bind_sentence("sentence-previous", previous_turn_id)
    metrics.discard_sentence("sentence-previous")

    current_turn_id = metrics.begin_turn([b"x"], "opus")
    metrics.bind_sentence("sentence-current", current_turn_id)
    metrics.record_tts_start("sentence-previous")
    metrics.record_audio_delivery_complete("sentence-previous")

    current_payload = metrics.build_user_metrics(current_turn_id)
    assert current_payload["trace"]["steps"] == []


def test_interrupted_sentence_keeps_partial_latency_trace_until_report():
    metrics = TurnMetrics()
    turn_id = metrics.ensure_text_turn()
    metrics.bind_sentence("sentence-interrupted", turn_id)
    metrics.record_llm_started(False, 0, turn_id=turn_id)
    metrics.record_llm_first_chunk(turn_id=turn_id)
    metrics.record_llm_complete(
        "已经开始回答", None, False, 0, depth=0, turn_id=turn_id
    )
    metrics.record_tts_start("sentence-interrupted")
    metrics.record_tts_audio_frame(20, "sentence-interrupted")

    metrics.record_interruption("sentence-interrupted", "aec_barge_in")
    metrics.record_interruption("sentence-interrupted", "aec_barge_in")
    payload = metrics.build_assistant_metrics(
        "已经开始回答", sentence_id="sentence-interrupted"
    )

    assert payload["turn_id"] == turn_id
    assert payload["outcome"]["status"] == "aborted"
    assert payload["outcome"]["reason"] == "aec_barge_in"
    interruption_steps = [
        step for step in payload["trace"]["steps"] if step["type"] == "interruption"
    ]
    assert len(interruption_steps) == 1
    assert interruption_steps[0]["response_audio_started"] is True
    assert interruption_steps[0]["generated_audio_duration_ms"] == 60
    assert payload["trace"]["totals"]["llm_call_count"] == 1
    assert payload["trace"]["totals"]["output_audio_duration_ms"] == 60


def test_message_sequence_orders_user_tools_and_assistant_within_one_turn():
    metrics = TurnMetrics()
    turn_id = metrics.ensure_text_turn()
    metrics.bind_sentence("sentence-sequence", turn_id)

    metrics.reserve_user_message(turn_id)
    tool_call = metrics.build_link_metrics("tool", turn_id=turn_id)
    tool_result = metrics.build_link_metrics("tool", turn_id=turn_id)
    user = metrics.build_user_metrics(turn_id)
    assistant = metrics.build_assistant_metrics(
        "完成", sentence_id="sentence-sequence"
    )

    assert [
        (item["message_role"], item["message_sequence"])
        for item in (user, tool_call, tool_result, assistant)
    ] == [
        ("user", 1),
        ("tool", 2),
        ("tool", 3),
        ("assistant", 4),
    ]


def test_streaming_asr_partial_is_kept_for_next_turn_while_previous_turn_pending():
    metrics = TurnMetrics()
    previous_turn_id = metrics.ensure_text_turn()
    metrics.bind_sentence("sentence-previous", previous_turn_id)

    metrics.record_vad_frame(True, 1, 20)
    metrics.record_asr_first_text(2)
    current_turn_id = metrics.begin_turn([b"x"] * 2, "opus")
    metrics.record_asr(3, True, 4, turn_id=current_turn_id)

    previous_payload = metrics.build_user_metrics(previous_turn_id)
    current_payload = metrics.build_user_metrics(current_turn_id)
    assert "asr" not in previous_payload["stages"]
    assert current_payload["stages"]["asr"]["first_text_length"] == 2
