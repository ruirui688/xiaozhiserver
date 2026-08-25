import json
import unittest

from app import build_prompt, extract_json_object, process_request


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

    def test_request_is_bounded_and_runner_output_is_returned(self):
        calls = []

        def runner(*args):
            calls.append(args)
            return {"proposals": [], "inputChars": 1, "outputChars": 2}

        result = process_request({
            "taskType": "KNOWLEDGE", "runId": "r1", "resourceId": "kb1",
            "maxProposals": 2, "input": {"topics": ["game"]},
        }, runner=runner)
        self.assertEqual(1, result["inputChars"])
        self.assertEqual("KNOWLEDGE", calls[0][0])
        with self.assertRaises(ValueError):
            process_request({"taskType": "MEMORY", "runId": "r", "resourceId": "a",
                             "maxProposals": 21, "input": {}}, runner=runner)


if __name__ == "__main__":
    unittest.main()
