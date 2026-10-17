from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

from plugins_func import search_budget as budget


def conn(sentence="turn-1"):
    return SimpleNamespace(sentence_id=sentence, client_abort=False)


def test_two_distinct_attempts_and_no_third():
    connection = conn()
    first = budget.claim_search(connection, "first")
    assert first["attempt"] == 1
    assert budget.grant_refinement(connection, first)
    assert budget.claim_search(connection, "second")["attempt"] == 2
    assert budget.claim_search(connection, "third")["reason"] == "attempt_limit"


def test_normalized_duplicate_does_not_consume_attempt():
    connection = conn()
    first = budget.claim_search(connection, " ＡＢＣ  news ")
    assert first["allowed"]
    assert budget.grant_refinement(connection, first)
    assert budget.claim_search(connection, "abc NEWS")["reason"] == "duplicate_query"
    assert budget.claim_search(connection, "different")["attempt"] == 2


def test_deadline_never_resets(monkeypatch):
    clock = [100.0]
    monkeypatch.setattr(budget.time, "monotonic", lambda: clock[0])
    connection = conn()
    first = budget.claim_search(connection, "one")
    assert first["remaining_seconds"] == 20
    assert budget.grant_refinement(connection, first)
    clock[0] += 19
    assert budget.claim_search(connection, "two")["remaining_seconds"] == 1
    clock[0] += 2
    assert budget.claim_search(connection, "three")["reason"] == "deadline_exceeded"


def test_parallel_claims_cannot_exceed_limit():
    connection = conn()
    with ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(lambda i: budget.claim_search(connection, str(i)), range(24)))
    assert sum(result["allowed"] for result in results) == 1


def test_missing_sentence_has_one_lifetime_attempt():
    connection = conn(None)
    assert budget.claim_search(connection, "one")["allowed"]
    for i in range(8):
        connection.sentence_id = str(i)
        assert budget.claim_search(connection, "one")["allowed"]
    assert len(connection._web_search_budgets) == 4
    connection.sentence_id = None
    assert budget.claim_search(connection, "two")["reason"] == "attempt_limit"


def test_new_sentence_independent_old_sentence_retains_count():
    connection = conn("old")
    first = budget.claim_search(connection, "one")
    assert budget.grant_refinement(connection, first)
    connection.sentence_id = "new"
    new_first = budget.claim_search(connection, "one")
    assert new_first["attempt"] == 1
    assert budget.grant_refinement(connection, new_first)
    connection.sentence_id = "old"
    assert budget.claim_search(connection, "two")["attempt"] == 2
    connection.sentence_id = "new"
    assert budget.claim_search(connection, "two")["attempt"] == 2


def test_abort_and_empty_do_not_start_budget():
    connection = conn()
    connection.client_abort = True
    assert budget.claim_search(connection, "one")["reason"] == "aborted"
    connection.client_abort = False
    assert budget.claim_search(connection, " \n ")["reason"] == "empty_query"
    assert budget.claim_search(connection, "one")["attempt"] == 1


def test_only_empty_result_grant_allows_followup():
    connection = conn()
    first = budget.claim_search(connection, "first")
    assert budget.claim_search(connection, "second")["reason"] == "refinement_not_granted"
    assert budget.grant_refinement(connection, first)
    assert budget.claim_search(connection, "second")["allowed"]
    assert not budget.grant_refinement(connection, first)


def test_old_token_cannot_authorize_new_turn_or_changed_agent():
    connection = conn("old")
    first = budget.claim_search(connection, "first")
    connection.sentence_id = "new"
    current = budget.claim_search(connection, "first")
    assert not budget.grant_refinement(connection, first)
    assert budget.claim_search(connection, "second")["reason"] == "refinement_not_granted"
    connection.agent_id = "changed"
    assert not budget.grant_refinement(connection, current)


def test_expired_or_aborted_token_cannot_grant(monkeypatch):
    clock = [0]
    monkeypatch.setattr(budget.time, "monotonic", lambda: clock[0])
    connection = conn()
    first = budget.claim_search(connection, "first")
    connection.client_abort = True
    assert not budget.grant_refinement(connection, first)
    connection.client_abort = False
    clock[0] = 20
    assert not budget.grant_refinement(connection, first)


def test_agent_change_invalidates_existing_grant():
    connection = conn()
    connection.agent_id = "first-agent"
    first = budget.claim_search(connection, "first")
    assert budget.grant_refinement(connection, first)
    connection.agent_id = "second-agent"
    assert budget.claim_search(connection, "second")["reason"] == "agent_changed"


def test_config_agent_change_invalidates_claim_and_grant():
    connection = conn()
    connection.config = {"agent_id": "first-agent"}
    first = budget.claim_search(connection, "first")
    assert budget.grant_refinement(connection, first)
    connection.config["agent_id"] = "second-agent"
    assert budget.claim_search(connection, "second")["reason"] == "agent_changed"
    assert not budget.grant_refinement(connection, first)
