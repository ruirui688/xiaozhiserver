"""One bounded worker per server process; network retries retain event identity."""

import asyncio
import portalocker
import logging
import os
from pathlib import Path

from .outbox import UsageOutbox
from .recorder import configure
from . import health as collection_health

logger = logging.getLogger(__name__)
PERMANENT_REJECTIONS = {"INVALID_CONTEXT", "INVALID_CALL_TIME", "MODEL_OUTSIDE_CONTEXT", "MODEL_SERVICE_MISMATCH",
                        "EVENT_ID_CONFLICT", "INVALID_EVENT", "INVALID_USAGE"}


class InvalidBillingAcknowledgement(RuntimeError):
    pass


async def deliver_once(outbox, send, observer=None):
    events = outbox.pending()
    if not events:
        return False
    result = await send(events)
    if not isinstance(result, dict):
        raise InvalidBillingAcknowledgement("Invalid billing acknowledgement")
    sent = {event["eventId"] for event in events}
    acknowledged = set(result.get("accepted", [])) | set(result.get("acceptedEventIds", [])) | set(result.get("duplicateEventIds", []))
    outbox.acknowledge(sent & acknowledged)
    rejected = {item["eventId"]: item["reasonCode"] for item in result.get("rejected", [])
                if isinstance(item, dict) and item.get("eventId") in sent - acknowledged
                and item.get("reasonCode") in PERMANENT_REJECTIONS}
    if rejected:
        outbox.quarantine(rejected)
        logger.error("Billing events retained in local quarantine; count=%s reasons=%s", len(rejected), sorted(set(rejected.values())))
    if observer is not None:
        observer.delivery_result(bool(sent & acknowledged), bool(sent - acknowledged - set(rejected)))
    return bool(sent & acknowledged or rejected)


class MeteringService:
    def __init__(self, config):
        self.enabled = bool(config.get("read_config_from_api"))
        self.task = None
        self.health_task = None
        self.outbox = None
        self.lock_file = None
        self.instance_id = None
        self.health = collection_health.CollectorHealth(self.enabled)

    async def start(self):
        if not self.enabled:
            return
        path = Path(os.environ.get("XIAOZHI_BILLING_OUTBOX_PATH") or
                    Path(__file__).resolve().parents[2] / "data" / "billing" / "usage.sqlite3")
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        fd = os.open(str(path) + ".lock", os.O_CREAT | os.O_RDWR, 0o600)
        self.lock_file = os.fdopen(fd, "w")
        portalocker.lock(self.lock_file, portalocker.LOCK_EX | portalocker.LOCK_NB)
        self.outbox = UsageOutbox(str(path), on_persistence_failure=self.health.persistence_failed)
        self.outbox.recover()
        self.instance_id = self.outbox.instance_id()
        configure(self.outbox)
        collection_health.configure(self.health)
        self.task = asyncio.create_task(self._run(), name="billing-outbox")
        self.health_task = asyncio.create_task(self._heartbeat(), name="billing-health")

    async def _send(self, events):
        return await self._post("/config/billing/usage", {"events": events})

    async def _post(self, endpoint, payload):
        from config.manage_api_client import ManageApiClient
        client = ManageApiClient._instance
        if client is None:
            raise RuntimeError("manager-api is unavailable")
        return await client._async_request("POST", endpoint, json=payload)

    async def report_health(self):
        payload = await asyncio.to_thread(self.health.payload, self.instance_id, self.outbox)
        return await self._post("/config/billing/health", payload)

    async def _heartbeat(self):
        # Independent of delivery retries: an idle or backlogged worker must still
        # report. The API records reception time; this is not an end-to-end probe.
        while True:
            try:
                await asyncio.wait_for(self.report_health(), timeout=10)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.warning("Billing health report deferred; errorType=%s", type(exc).__name__)
            await asyncio.sleep(30)

    async def _run(self):
        delay = 1
        while True:
            try:
                delivered = await deliver_once(self.outbox, self._send, self.health)
                delay = 1
                if delivered:
                    continue
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self.health.delivery_failed("INVALID_ACK" if isinstance(exc, InvalidBillingAcknowledgement) else "DELIVERY_FAILED")
                # Capability tokens and request payloads must never enter logs.
                logger.warning("Billing delivery deferred; errorType=%s", type(exc).__name__)
                delay = min(delay * 2, 60)
            await asyncio.sleep(delay)

    async def stop(self):
        tasks = [task for task in (self.task, self.health_task) if task]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        collection_health.configure(None)
        # Provider threads may finish during shutdown. Keep the local connection
        # open until process exit; unfinished rows are recovered as UNKNOWN.
