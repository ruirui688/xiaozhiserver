"""Connection-local search admission; no conversation content is retained."""
from collections import OrderedDict
import hashlib
import threading
import time
import unicodedata

WINDOW_SECONDS = 20.0
MAX_ATTEMPTS = 2
_MAX_TRACKED_TURNS = 4
_STATE_LOCK = threading.Lock()


def _agent_identity(conn):
    return getattr(conn, "agent_id", None) or (getattr(conn, "config", None) or {}).get("agent_id")


def _query_digest(query):
    normalized = " ".join(unicodedata.normalize("NFKC", str(query or "")).casefold().split())
    return hashlib.sha256(normalized.encode("utf-8")).digest() if normalized else None


def claim_search(conn, query):
    """Atomically claim an attempt; rejected calls never consume an attempt.

    Call at dispatch time, before starting I/O. The returned allowance belongs
    to the sentence observed here; finishing a request never mutates state.
    A missing sentence id permits one call for the connection lifetime.
    """
    digest = _query_digest(query)
    with _STATE_LOCK:
        now = time.monotonic()
        states = getattr(conn, "_web_search_budgets", None)
        if states is None:
            states = OrderedDict()
            conn._web_search_budgets = states
        sentence = getattr(conn, "sentence_id", None)
        key = str(sentence) if sentence else None
        # Keep the missing-id state separately so cycling turns cannot reset it.
        state = (getattr(conn, "_web_search_missing_budget", None) if key is None
                 else states.get(key))
        attempt = len(state["queries"]) if state else 0
        remaining = max(0.0, WINDOW_SECONDS - (now - state["started"])) if state else WINDOW_SECONDS
        reason = ""
        if getattr(conn, "client_abort", False):
            reason = "aborted"
        elif state and state["agent_id"] != _agent_identity(conn):
            reason = "agent_changed"
        elif digest is None:
            reason = "empty_query"
        elif remaining <= 0:
            reason = "deadline_exceeded"
        elif state and digest in state["queries"]:
            reason = "duplicate_query"
        elif attempt >= (MAX_ATTEMPTS if key else 1):
            reason = "attempt_limit"
        elif attempt and not state["refinement_allowed"]:
            reason = "refinement_not_granted"
        if reason:
            return {"allowed": False, "attempt": attempt,
                    "remaining_seconds": remaining, "reason": reason}
        if state is None:
            state = {"started": now, "queries": set(), "refinement_allowed": False,
                     "agent_id": _agent_identity(conn)}
            if key is None:
                conn._web_search_missing_budget = state
            else:
                states[key] = state
                while len(states) > _MAX_TRACKED_TURNS:
                    states.popitem(last=False)
        state["queries"].add(digest)
        state["refinement_allowed"] = False
        return {"allowed": True, "attempt": attempt + 1,
                "remaining_seconds": remaining, "reason": "allowed",
                "_token": state, "sentence_id": key}


def grant_refinement(conn, claim):
    """Grant the one follow-up only after an evidence-empty first result."""
    with _STATE_LOCK:
        key = claim.get("sentence_id")
        state = claim.get("_token")
        current = getattr(conn, "sentence_id", None)
        if (not claim.get("allowed") or claim.get("attempt") != 1 or not key
                or not current or str(current) != key or getattr(conn, "client_abort", False)
                or getattr(conn, "_web_search_budgets", {}).get(key) is not state):
            return False
        if (len(state["queries"]) != 1
                or state["agent_id"] != _agent_identity(conn)
                or time.monotonic() - state["started"] >= WINDOW_SECONDS):
            return False
        state["refinement_allowed"] = True
        return True
