import json
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from app import build_prompt, extract_json_object, process_request, run_harness, validate_model_config


class MaintenanceAgentTest(unittest.TestCase):
    def test_extracts_plain_and_fenced_json(self):
        self.assertEqual([], extract_json_object('{"proposals":[]}')["proposals"])
        self.assertEqual([], extract_json_object('```json\n{"proposals":[]}\n```')["proposals"])

    def test_rejects_trailing_or_non_object_output(self):
        with self.assertRaises((ValueError, json.JSONDecodeError)):
            extract_json_object('{"proposals":[]} trailing')
        with self.assertRaises(ValueError):
            extract_json_object('[]')

    def test_memory_prompt_forbids_external_knowledge(self):
        prompt = build_prompt("MEMORY", 3, {"facts": []})
        self.assertIn("Do not use external knowledge", prompt)
        self.assertNotIn("Use web_search", prompt)

    def test_operator_guidance_improves_quality_without_replacing_safety_contract(self):
        prompt = build_prompt("KNOWLEDGE", 3, {}, "只保留当前赛季官方公告", "排除旧版本和赛事营销页",
                              [{"role": "USER", "content": "先检查18.1版本"},
                               {"role": "ASSISTANT", "content": "已经找到两条候选"}])
        self.assertIn("只保留当前赛季官方公告", prompt)
        self.assertIn("排除旧版本和赛事营销页", prompt)
        self.assertIn("subordinate to every security", prompt)
        self.assertIn("exclude obsolete patches", prompt)
        self.assertIn("REMOVE_SOURCE", prompt)
        self.assertIn("先检查18.1版本", prompt)
        self.assertIn("已经找到两条候选", prompt)

    def test_request_is_bounded_and_runner_output_is_returned(self):
        calls = []

        def runner(*args):
            calls.append(args)
            return {"proposals": [], "inputChars": 1, "outputChars": 2}

        result = process_request({
            "taskType": "KNOWLEDGE", "runId": "r1", "conversationId": "c1", "resourceId": "kb1",
            "maxProposals": 2, "input": {"topics": ["game"]},
            "customPrompt": "official current patches only", "userInstruction": "find the newest season",
            "conversationHistory": [{"role": "USER", "content": "continue this topic"}],
            "modelConfig": {"apiKey": "managed-key", "baseUrl": "https://api.deepseek.com", "model": "deepseek-chat"},
        }, runner=runner)
        self.assertEqual(1, result["inputChars"])
        self.assertEqual("KNOWLEDGE", calls[0][0])
        self.assertEqual("c1", calls[0][2])
        self.assertEqual("official current patches only", calls[0][6])
        self.assertEqual("find the newest season", calls[0][7])
        self.assertEqual("continue this topic", calls[0][8][0]["content"])
        with self.assertRaises(ValueError):
            process_request({"taskType": "MEMORY", "runId": "r", "resourceId": "a",
                             "maxProposals": 21, "input": {}, "modelConfig": {}}, runner=runner)

    def test_rejects_missing_or_invalid_managed_model_config(self):
        with self.assertRaises(ValueError):
            process_request({"taskType": "KNOWLEDGE", "runId": "r", "resourceId": "k",
                             "maxProposals": 1, "input": {}}, runner=lambda *_: {})
        with self.assertRaises(ValueError):
            process_request({"taskType": "KNOWLEDGE", "runId": "r", "resourceId": "k",
                             "maxProposals": 1, "input": {}, "modelConfig": {
                                 "apiKey": "key", "baseUrl": "file:///tmp/model", "model": "deepseek-chat"
                             }}, runner=lambda *_: {})

    def test_validates_managed_model_config_without_returning_extra_fields(self):
        self.assertEqual({"apiKey": "key", "baseUrl": "", "model": "deepseek-chat"},
                         validate_model_config({"apiKey": " key ", "baseUrl": "",
                                                "model": " deepseek-chat ", "ignored": "value"}))

    def test_passes_managed_credentials_only_to_owned_harness(self):
        calls = []

        class FakeHarness:
            def __init__(self, **kwargs):
                calls.append(kwargs)

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return None

            def run(self, *_args, **_kwargs):
                return SimpleNamespace(final_response='{"proposals":[]}')

        with patch.dict(sys.modules, {"deepseek_harness": SimpleNamespace(DeepSeekHarness=FakeHarness)}):
            result = run_harness("KNOWLEDGE", "run-1", "conversation-1", 2, {}, {
                "apiKey": "managed-key", "baseUrl": "https://api.deepseek.com", "model": "deepseek-chat"
            }, "official only", "latest version", [{"role": "USER", "content": "previous turn"}])

        self.assertEqual([], result["proposals"])
        self.assertEqual("managed-key", calls[0]["api_key"])
        self.assertEqual("https://api.deepseek.com", calls[0]["base_url"])
        self.assertEqual("deepseek-chat", calls[0]["model"])
        self.assertEqual("本轮维护完成，生成 0 条提案。", result["assistantMessage"])


if __name__ == "__main__":
    unittest.main()
