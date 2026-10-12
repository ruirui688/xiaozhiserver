"""Crash-safe local delivery queue. No prompts, audio, URLs or API keys belong here."""

import json
import os
import sqlite3
import threading
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class UsageOutbox:
    def __init__(self, path, on_persistence_failure=None):
        self._on_persistence_failure = on_persistence_failure
        parent = os.path.dirname(os.path.abspath(path))
        os.makedirs(parent, mode=0o700, exist_ok=True)
        descriptor = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
        os.close(descriptor)
        os.chmod(path, 0o600)
        self._lock = threading.RLock()
        self._delivery_cursor = 0
        self._db = sqlite3.connect(path, check_same_thread=False, timeout=10)
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.execute("PRAGMA synchronous=FULL")
        self._db.execute("CREATE TABLE IF NOT EXISTS usage_outbox (event_id TEXT PRIMARY KEY, active INTEGER NOT NULL, payload TEXT NOT NULL)")
        self._db.execute("CREATE TABLE IF NOT EXISTS usage_rejection (event_id TEXT PRIMARY KEY, reason_code TEXT NOT NULL)")
        self._db.execute("CREATE TABLE IF NOT EXISTS usage_metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        self._db.commit()

    @contextmanager
    def _write(self):
        try:
            with self._lock, self._db:
                yield
        except sqlite3.Error:
            if self._on_persistence_failure:
                self._on_persistence_failure()
            raise

    def instance_id(self):
        """Stable per queue, independent of hostname, credentials or filesystem path."""
        with self._write():
            row = self._db.execute("SELECT value FROM usage_metadata WHERE name='instance_id'").fetchone()
            if row:
                return str(uuid.UUID(row[0]))
            value = str(uuid.uuid4())
            self._db.execute("INSERT INTO usage_metadata VALUES ('instance_id',?)", (value,))
            return value

    def counts(self):
        with self._lock:
            counts = dict(self._db.execute("SELECT active,COUNT(*) FROM usage_outbox GROUP BY active").fetchall())
            return dict(activeCount=counts.get(1, 0), pendingCount=counts.get(0, 0), rejectedCount=counts.get(-1, 0))

    def save(self, event, active):
        with self._write():
            self._db.execute(
                "INSERT INTO usage_outbox VALUES (?, ?, ?) ON CONFLICT(event_id) DO UPDATE SET active=excluded.active, payload=excluded.payload",
                (event["eventId"], int(active), json.dumps(event, separators=(",", ":"))),
            )

    def recover(self):
        """Called under the process-exclusive worker lock, never by a second process."""
        with self._write():
            rows = self._db.execute("SELECT payload FROM usage_outbox WHERE active=1").fetchall()
            for (payload,) in rows:
                event = json.loads(payload)
                event.update(status="UNKNOWN", usageComplete=False, endedAt=utc_now())
                self._db.execute("UPDATE usage_outbox SET active=0,payload=? WHERE event_id=?",
                                 (json.dumps(event), event["eventId"]))

    def pending(self, limit=50):
        with self._lock:
            return [json.loads(row[0]) for row in self._db.execute(
                "SELECT payload FROM usage_outbox WHERE active=0 ORDER BY rowid LIMIT ?", (limit,)
            ).fetchall()]

    def delivery_batch(self, limit=50):
        """Round-robin retries retain failed receipts without starving later rows."""
        with self._lock:
            rows = self._db.execute(
                "SELECT rowid,payload FROM usage_outbox WHERE active=0 AND rowid>? ORDER BY rowid LIMIT ?",
                (self._delivery_cursor, limit),
            ).fetchall()
            if not rows:
                rows = self._db.execute(
                    "SELECT rowid,payload FROM usage_outbox WHERE active=0 ORDER BY rowid LIMIT ?", (limit,)
                ).fetchall()
            self._delivery_cursor = rows[-1][0] if rows else 0
            return [json.loads(row[1]) for row in rows]

    def acknowledge(self, event_ids):
        with self._write():
            self._db.executemany("DELETE FROM usage_outbox WHERE event_id=? AND active=0",
                                 ((event_id,) for event_id in event_ids))

    def quarantine(self, rejected):
        """Keep permanent failures for repair, without starving later events."""
        with self._write():
            for event_id, reason in rejected.items():
                self._db.execute("UPDATE usage_outbox SET active=-1 WHERE event_id=? AND active=0", (event_id,))
                self._db.execute("INSERT OR REPLACE INTO usage_rejection VALUES (?,?)", (event_id, reason))

    def rejected(self):
        with self._lock:
            return self._db.execute("SELECT event_id,reason_code FROM usage_rejection ORDER BY rowid").fetchall()

    def close(self):
        with self._lock:
            self._db.close()
