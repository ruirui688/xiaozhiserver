"""Verify the shared Volcengine corpus contract without calling the provider."""

import asyncio
import json
import sys
from pathlib import Path
from unittest.mock import Mock

import pytest

SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from config import settings

settings.config_file_valid = True

from core.providers.asr import doubao_recording, doubao_stream


@pytest.mark.parametrize("module", [doubao_stream, doubao_recording])
@pytest.mark.parametrize(
    "table_id,context",
    [
        (None, None),
        ("", ""),
        (" \t", " \n"),
        (" test-table ", None),
        (None, '{"hotwords":[{"word":"示例专有词"}]}'),
        ("test-table", '{"hotwords":[{"word":"示例专有词"}]}'),
        ("test-table", ' {"context_type":"dialog_ctx","context_data":[{"text":"私有场景"}]} '),
    ],
)
def test_corpus_survives_wire_serialization_without_logging_content(
    module, table_id, context, tmp_path, monkeypatch
):
    logger = Mock()
    monkeypatch.setattr(module, "logger", logger)
    provider = module.ASRProvider(
        {
            "api_key": "private-test-key",
            "output_dir": str(tmp_path),
            "boosting_table_id": table_id,
            "context_text": context,
        },
        delete_audio_file=True,
    )
    try:
        if module is doubao_stream:
            payload = provider.construct_request("test-request")
        else:
            payload = provider._build_submit_payload(b"wav", "test-request")
        request = json.loads(json.dumps(payload))["request"]
        assert "context" not in request
        expected = {}
        if (table_id or "").strip():
            expected["boosting_table_id"] = table_id.strip()
        if (context or "").strip():
            expected["context"] = context.strip()
        if expected:
            assert request["corpus"] == expected
            if "context" in expected:
                assert isinstance(request["corpus"]["context"], str)
                assert json.loads(request["corpus"]["context"]) == json.loads(context)
        else:
            assert "corpus" not in request

        log_output = str(logger.mock_calls)
        for private_value in ("private-test-key", "test-table", "示例专有词", "私有场景"):
            assert private_value not in log_output
    finally:
        if module is doubao_stream:
            asyncio.run(provider.close())
