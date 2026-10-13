"""Deterministic pressure and recovery tests: no external models or sleeps."""

from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest

from core.notifications.admission import NotificationAdmission, NotificationLimits


class Clock:
    value = 0.0

    def __call__(self):
        return self.value


def deliver(gate, conn, request_id, writes):
    def submit():
        writes.append(request_id)
        conn.busy = True

    return gate.execute(
        conn, (conn.device_id, request_id), "content-hash", lambda: None,
        submit, lambda device: device.busy,
    )


def test_burst_10000_rejections_do_not_grow_pending_memory_and_rate_recovers():
    clock = Clock()
    gate = NotificationAdmission(NotificationLimits(burst=2, rate_per_second=1), clock=clock)
    writes = []
    first = SimpleNamespace(device_id="first", busy=False)
    second = SimpleNamespace(device_id="second", busy=False)
    assert deliver(gate, first, "one", writes) == (True, "queued")
    assert deliver(gate, second, "two", writes) == (True, "queued")

    def submit(index):
        conn = SimpleNamespace(device_id=f"overflow-{index}", busy=False)
        return deliver(gate, conn, str(index), writes)

    with ThreadPoolExecutor(max_workers=16) as executor:
        assert set(executor.map(submit, range(10000))) == {(False, "overloaded")}
    assert gate.snapshot()["receipts"] == gate.snapshot()["pending"] == 2
    assert len(writes) == 2
    clock.value = 1
    assert submit(10001) == (True, "queued")
    assert len(writes) == 3


def test_pending_limit_holds_after_refill_until_tts_finishes():
    clock = Clock()
    gate = NotificationAdmission(NotificationLimits(max_pending=1), clock=clock)
    first = SimpleNamespace(device_id="first", busy=False)
    second = SimpleNamespace(device_id="second", busy=False)
    writes = []
    assert deliver(gate, first, "one", writes) == (True, "queued")
    clock.value = 100
    assert deliver(gate, second, "two", writes) == (False, "overloaded")
    first.busy = False
    assert deliver(gate, second, "two", writes) == (True, "queued")
    assert gate.snapshot()["pending"] == 1


def test_full_receipt_capacity_preserves_duplicates_until_ttl_expires():
    clock = Clock()
    gate = NotificationAdmission(NotificationLimits(max_receipts=1, receipt_ttl_seconds=900), clock=clock)
    first = SimpleNamespace(device_id="first", busy=False)
    second = SimpleNamespace(device_id="second", busy=False)
    writes = []
    assert deliver(gate, first, "one", writes) == (True, "queued")
    first.busy = False
    clock.value = 10
    assert deliver(gate, second, "two", writes) == (False, "overloaded")
    assert deliver(gate, first, "one", writes) == (True, "duplicate")
    assert writes == ["one"]
    clock.value = 901
    assert deliver(gate, second, "two", writes) == (True, "queued")
    assert gate.snapshot()["receipts"] == 1


def test_device_cooldown_survives_queue_drain_and_resets_after_interval():
    clock = Clock()
    gate = NotificationAdmission(clock=clock)
    conn = SimpleNamespace(device_id="device", busy=False)
    writes = []
    assert deliver(gate, conn, "one", writes) == (True, "queued")
    conn.busy = False
    clock.value = 1
    assert deliver(gate, conn, "two", writes) == (False, "device_busy")
    clock.value = 2
    assert deliver(gate, conn, "two", writes) == (True, "queued")


@pytest.mark.parametrize("key,value", [
    ("burst", 0), ("burst", 1.5), ("rate_per_second", float("inf")),
    ("rate_per_second", float("nan")), ("max_pending", True),
    ("receipt_ttl_seconds", 1), ("max_receipts", 100001),
])
def test_configuration_cannot_disable_bounds_or_use_nonfinite_values(key, value):
    with pytest.raises(ValueError):
        NotificationLimits.from_config({key: value})
