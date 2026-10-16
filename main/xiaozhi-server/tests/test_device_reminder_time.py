"""Structured time and retry contracts; no network, database or paid models."""

import asyncio
from datetime import datetime, timezone

import pytest

from test_device_reminder import _conn
from core.notifications import reminder_time
from core.notifications.reminder_time import ReminderTimeError, resolve_creation_time
from plugins_func.functions import device_reminder as plugin


NOW = datetime(2026, 10, 15, 6, 0, tzinfo=timezone.utc).timestamp()


def resolve(conn=None, **kwargs):
    params = dict(request_id="call-1", title="开会", now=NOW)
    params.update(kwargs)
    return resolve_creation_time(conn or _conn(), **params)


@pytest.mark.parametrize("amount,unit,seconds", [
    (5, "seconds", 5), (2, "minutes", 120), (2, "hours", 7200),
    (1, "days", 86400), (365, "days", reminder_time.YEAR_SECONDS),
])
def test_relative_is_server_time_not_model_epoch(amount, unit, seconds):
    result = resolve(delay={"amount": amount, "unit": unit}, now=NOW + 0.25)
    assert result.timestamp == NOW + 0.25 + seconds
    assert result.timezone_name == "Asia/Shanghai"


@pytest.mark.parametrize("fields", [
    {}, {"delay": {"amount": 2, "unit": "minutes"}, "remind_timestamp": NOW + 120},
    {"delay": {"amount": 2, "unit": "minutes"}, "remind_at": "2026-10-15T14:02+08:00"},
    {"remind_timestamp": NOW + 120, "remind_at": "2026-10-15T14:02+08:00"},
    {"delay": {}}, {"delay": "两分钟后"},
    {"delay": {"amount": True, "unit": "minutes"}},
    {"delay": {"amount": "2", "unit": "minutes"}},
    {"delay": {"amount": 2.5, "unit": "minutes"}},
    {"delay": {"amount": float("nan"), "unit": "minutes"}},
    {"delay": {"amount": float("inf"), "unit": "seconds"}},
    {"delay": {"amount": -2, "unit": "minutes"}},
    {"delay": {"amount": 0, "unit": "minutes"}},
    {"delay": {"amount": 2, "unit": "months"}},
    {"delay": {"amount": 2, "unit": []}},
    {"delay": {"amount": 2, "unit": "minutes", "anchor": NOW}},
    {"delay": {"amount": 4, "unit": "seconds"}},
    {"delay": {"amount": 366, "unit": "days"}},
    {"remind_timestamp": NOW + 4},
    {"remind_timestamp": NOW + reminder_time.YEAR_SECONDS + 1},
    {"remind_timestamp": True}, {"remind_timestamp": float("inf")},
    {"remind_timestamp": 10 ** 1000},
    {"remind_timestamp": datetime(2025, 10, 15, tzinfo=timezone.utc).timestamp()},
    {"remind_at": "2026-10-15T14:02:00"},
    {"remind_at": "2026-10-15T14:02:00", "timezone": " "},
    {"remind_at": "2026-10-15"}, {"remind_at": "明天两点"},
    {"remind_at": "2026-02-30T14:02:00+08:00"},
    {"remind_at": "2026-10-15T14:02:00-00:00"},
    {"remind_at": "2026-10-15T14:02:00+08:60"},
    {"remind_at": "2026-10-15T14:02:00+24:00"},
    {"remind_at": "2026-10-15T14:02:00+08:00", "timezone": "America/New_York"},
    {"delay": {"amount": 2, "unit": "minutes"}, "timezone": "Mars/Base"},
])
def test_invalid_or_ambiguous_input_consumes_no_anchor(fields):
    conn = _conn()
    with pytest.raises(ReminderTimeError):
        resolve(conn, **fields)
    assert not getattr(conn, "_reminder_create_anchors", {})


@pytest.mark.parametrize("fields", [
    {"remind_at": "2026-10-15T14:02:00+08:00"},
    {"remind_at": "2026-10-15T06:02Z"},
    {"remind_at": "2026-10-15T14:02:00", "timezone": "Asia/Shanghai"},
    {"remind_at": "2026-10-15T02:02:00-04:00", "timezone": "America/New_York"},
    {"remind_timestamp": str(int(NOW + 120))},
])
def test_absolute_and_legacy_formats_match_same_instant(fields):
    assert resolve(**fields).timestamp == NOW + 120


@pytest.mark.parametrize("local", ["2027-03-14T02:30:00", "2026-11-01T01:30:00"])
def test_dst_gap_or_overlap_requires_explicit_offset(local):
    with pytest.raises(ReminderTimeError, match="夏令时"):
        resolve(remind_at=local, timezone="America/New_York")


def test_dst_explicit_offsets_disambiguate_overlap_and_reject_gap():
    early = resolve(remind_at="2026-11-01T01:30:00-04:00", timezone="America/New_York")
    late = resolve(remind_at="2026-11-01T01:30:00-05:00", timezone="America/New_York")
    assert late.timestamp - early.timestamp == 3600
    with pytest.raises(ReminderTimeError, match="不一致"):
        resolve(remind_at="2027-03-14T02:30:00-05:00", timezone="America/New_York")


@pytest.mark.parametrize("seconds", [5, reminder_time.YEAR_SECONDS])
def test_legacy_window_includes_exact_bounds(seconds):
    assert resolve(remind_timestamp=NOW + seconds).timestamp == NOW + seconds


def test_retry_keeps_original_time_even_after_due_and_wall_clock_change():
    conn = _conn()
    first = resolve(conn, delay={"amount": 2, "unit": "minutes"})
    for now in (NOW + 10, NOW + 119, NOW + 1000, NOW - 1000):
        retry = resolve(conn, delay={"amount": 2, "unit": "minutes"}, now=now)
        assert retry.timestamp == first.timestamp == NOW + 120
    assert len(conn._reminder_create_anchors) == 1


@pytest.mark.parametrize("changed", [
    {"title": "喝水"}, {"timezone": "UTC"},
    {"delay": {"amount": 3, "unit": "minutes"}},
    {"delay": None, "remind_timestamp": NOW + 120},
    {"recurrence": {"frequency": "DAILY"}},
])
def test_reusing_request_id_cannot_change_payload(changed):
    conn = _conn()
    fields = {"delay": {"amount": 2, "unit": "minutes"}}
    resolve(conn, **fields)
    fields.update(changed)
    with pytest.raises(ReminderTimeError, match="内容不能变更"):
        resolve(conn, **fields)


@pytest.mark.parametrize("identity", ["agent", "device"])
def test_request_id_reuse_after_identity_change_fails(identity):
    conn = _conn()
    resolve(conn, delay={"amount": 2, "unit": "minutes"})
    if identity == "agent":
        conn.config["agent_id"] = "other-agent"
    else:
        conn.device_id = "other-device"
    with pytest.raises(ReminderTimeError, match="内容不能变更"):
        resolve(conn, delay={"amount": 2, "unit": "minutes"})


def test_capacity_does_not_evict_anchors_and_connections_are_isolated(monkeypatch):
    monkeypatch.setattr(reminder_time, "MAX_CREATE_ANCHORS", 2)
    conn = _conn()
    for index in range(2):
        resolve(conn, request_id=f"call-{index}", delay={"amount": 2, "unit": "minutes"})
    with pytest.raises(ReminderTimeError, match="上限"):
        resolve(conn, request_id="call-2", delay={"amount": 2, "unit": "minutes"})
    assert resolve(conn, now=NOW + 10, delay={"amount": 2, "unit": "minutes"}).timestamp == NOW + 120
    assert len(conn._reminder_create_anchors) == 2
    assert resolve(_conn(), now=NOW + 10, delay={"amount": 2, "unit": "minutes"}).timestamp == NOW + 130


def test_relative_recurrence_until_uses_original_anchor_on_retry():
    conn = _conn()
    fields = {"delay": {"amount": 2, "unit": "minutes"},
              "recurrence": {"frequency": "DAILY", "until": "2026-10-15T06:02:00Z"}}
    first = resolve(conn, **fields)
    assert resolve(conn, now=NOW + 300, **fields).timestamp == first.timestamp
    with pytest.raises(ReminderTimeError, match="重复规则"):
        resolve(_conn(), now=NOW + 300, **fields)


def test_timeout_retry_forwards_identical_payload_and_backend_remains_authority(monkeypatch):
    conn = _conn()
    calls = []
    clock = [NOW]

    async def backend(agent, action, **payload):
        calls.append((agent, action, payload))
        if len(calls) == 1:
            raise TimeoutError()
        return {"reminderId": "saved", "status": "PENDING", **payload}

    monkeypatch.setattr(plugin.time, "time", lambda: clock[0])
    monkeypatch.setattr(plugin, "execute_reminder_action", backend)
    fields = dict(title="开会", request_id="call-1", delay={"amount": 2, "unit": "minutes"})
    first = asyncio.run(plugin.device_reminder(conn, "create", **fields))
    assert "未确认" in first.response and "已设置" not in first.response
    clock[0] += 119
    retry = asyncio.run(plugin.device_reminder(conn, "create", **fields))
    assert calls[0] == calls[1]
    assert calls[0][2]["remindAt"] == "2026-10-15T06:02:00Z"
    assert "2026年10月15日14点02分" in retry.response


def test_invalid_inputs_never_call_backend(monkeypatch):
    async def forbidden(*args, **kwargs):
        raise AssertionError("Invalid schedule must not reach backend")

    monkeypatch.setattr(plugin, "execute_reminder_action", forbidden)
    result = asyncio.run(plugin.device_reminder(
        _conn(), "create", title="开会", request_id="call-1",
        delay={"amount": 2, "unit": "minutes"}, remind_timestamp=NOW + 120))
    assert "只能提供一种" in result.response


def test_tool_schema_exposes_structured_time_not_trusted_anchor_or_request_id():
    schema = plugin.DEVICE_REMINDER_TOOL_DESCRIPTION["function"]["parameters"]
    properties = schema["properties"]
    assert {"delay", "remind_at"} <= set(properties)
    assert "remind_timestamp" not in properties  # Runtime-only legacy compatibility.
    assert properties["delay"]["required"] == ["amount", "unit"]
    assert properties["delay"]["additionalProperties"] is False
    assert "request_id" not in properties and "anchor" not in properties
    assert schema["required"] == ["action"]  # list/cancel need no time.


def test_concurrent_retries_freeze_anchor_before_network_await(monkeypatch):
    conn = _conn()
    clock = [NOW]
    calls = []

    async def backend(agent, action, **payload):
        calls.append(payload)
        clock[0] += 10
        await asyncio.sleep(0)
        return {"reminderId": "saved", "status": "PENDING", **payload}

    monkeypatch.setattr(plugin.time, "time", lambda: clock[0])
    monkeypatch.setattr(plugin, "execute_reminder_action", backend)

    async def run():
        return await asyncio.gather(*(plugin.device_reminder(
            conn, "create", title="开会", request_id="same-call",
            delay={"amount": 2, "unit": "minutes"}) for _ in range(3)))

    responses = asyncio.run(run())
    assert len(calls) == 3
    assert calls[0] == calls[1] == calls[2]
    assert all("已设置" in item.response for item in responses)
    assert len(conn._reminder_create_anchors) == 1


def test_cancellation_after_dispatch_preserves_retry_anchor(monkeypatch):
    conn = _conn()
    clock = [NOW]
    calls = []

    async def backend(agent, action, **payload):
        calls.append(payload)
        if len(calls) == 1:
            raise asyncio.CancelledError()
        return {"reminderId": "saved", "status": "PENDING", **payload}

    monkeypatch.setattr(plugin.time, "time", lambda: clock[0])
    monkeypatch.setattr(plugin, "execute_reminder_action", backend)
    fields = dict(title="开会", request_id="same-call", delay={"amount": 2, "unit": "minutes"})
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(plugin.device_reminder(conn, "create", **fields))
    clock[0] += 130
    assert "已设置" in asyncio.run(plugin.device_reminder(conn, "create", **fields)).response
    assert calls[0] == calls[1]
