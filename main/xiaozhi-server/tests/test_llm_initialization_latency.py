import json
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from config import settings

settings.config_file_valid = True

from core.observability.session_telemetry import SessionTelemetry, latency_span
from core.providers.llm.openai import openai as openai_llm
from core.utils import llm


def observer():
    logger = Mock()
    logger.bind.return_value = logger
    return SimpleNamespace(session_telemetry=SessionTelemetry("session-test", logger, "test")), logger


def records(logger):
    return [json.loads(call.args[0]) for call in logger.info.call_args_list]


@pytest.mark.parametrize("cached", [False, True])
def test_provider_import_and_constructor_spans_are_separate(monkeypatch, cached):
    conn, logger = observer()
    module_name = "core.providers.llm.openai.openai"
    provider = object()
    module = SimpleNamespace(LLMProvider=Mock(return_value=provider))
    if cached:
        monkeypatch.setitem(llm.sys.modules, module_name, module)
    else:
        monkeypatch.delitem(llm.sys.modules, module_name, raising=False)
    importer = Mock(return_value=module)
    monkeypatch.setattr(llm.importlib, "import_module", importer)
    with latency_span(conn, "init.create.llm"):
        assert llm.create_instance("openai", {"api_key": "secret-not-logged"}) is provider
    assert importer.call_count == (0 if cached else 1)
    events = records(logger)
    imported = next(event for event in events if event["stage"] == "init.llm.provider_import.end")
    assert imported["cache_hit"] is cached
    assert imported["status"] == "completed"
    assert "init.llm.provider_construct.end" in {event["stage"] for event in events}
    assert "secret-not-logged" not in str(events)


def test_client_construction_failure_is_preserved_without_logging_secret(monkeypatch):
    conn, logger = observer()
    error = RuntimeError("secret-not-logged")
    monkeypatch.setattr(openai_llm.openai, "OpenAI", Mock(side_effect=error))
    with pytest.raises(RuntimeError) as caught:
        with latency_span(conn, "init.create.llm"):
            openai_llm.LLMProvider({"api_key": "test-key", "base_url": "https://example.test/v1"})
    assert caught.value is error
    events = records(logger)
    assert next(event for event in events if event["stage"] == "init.llm.client_construct.end")["status"] == "interrupted"
    assert "secret-not-logged" not in str(events)


def test_no_observer_does_not_change_provider_creation(monkeypatch):
    client = object()
    constructor = Mock(return_value=client)
    monkeypatch.setattr(openai_llm.openai, "OpenAI", constructor)
    provider = openai_llm.LLMProvider({"api_key": "test-key", "base_url": "https://example.test/v1"})
    assert provider.client is client
    constructor.assert_called_once()
