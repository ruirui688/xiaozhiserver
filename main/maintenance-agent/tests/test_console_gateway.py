import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TEST_STATE_ROOT = tempfile.mkdtemp(prefix="maintenance-console-test-")
os.environ["MAINTENANCE_CONSOLE_STATE_ROOT"] = TEST_STATE_ROOT

from aiohttp.test_utils import make_mocked_request  # noqa: E402
from aiohttp.web_exceptions import HTTPForbidden  # noqa: E402
from console_gateway import (  # noqa: E402
    CONSOLE_COOKIE_PATH, ConsoleManager, _require_trusted_origin, _rewrite_console_body,
    _rewrite_console_location, _with_frame_ancestors, build_console_prompt,
)


class ConsoleGatewayTest(unittest.TestCase):
    def test_native_prompt_keeps_normal_chat_and_explicit_submission_boundary(self):
        prompt = build_console_prompt("KNOWLEDGE", 4, {"existingSources": []}, "只使用官网")
        self.assertIn("official DeepSeek Harness Web Console", prompt)
        self.assertIn("answer the authenticated operator normally", prompt)
        self.assertIn("operator explicitly asks", prompt)
        self.assertIn("submit_maintenance_proposals", prompt)
        self.assertIn("只使用官网", prompt)

    def test_memory_console_has_no_external_knowledge(self):
        prompt = build_console_prompt("MEMORY", 2, {"facts": []})
        self.assertIn("Do not use external knowledge", prompt)
        self.assertNotIn("You may use web_search", prompt)

    def test_frame_ancestors_replaces_upstream_directive(self):
        with mock.patch.dict(os.environ, {
            "MAINTENANCE_CONSOLE_FRAME_ANCESTORS": "https://manager.example.com"
        }):
            value = _with_frame_ancestors("default-src 'self'; frame-ancestors 'none'")
        self.assertEqual("default-src 'self'; frame-ancestors https://manager.example.com", value)

    def test_native_state_scope_is_stable_and_does_not_expose_resource_id(self):
        first = ConsoleManager._scope_key("KNOWLEDGE", "private-resource-id")
        second = ConsoleManager._scope_key("KNOWLEDGE", "private-resource-id")
        self.assertEqual(first, second)
        self.assertNotIn("private-resource-id", first)

    def test_state_changing_console_requests_require_manager_origin(self):
        trusted = make_mocked_request("POST", "/api/session", headers={
            "Origin": "https://yh.wejoy.link"
        })
        untrusted = make_mocked_request("POST", "/api/session", headers={
            "Origin": "https://attacker.example"
        })
        _require_trusted_origin(trusted)
        with self.assertRaises(HTTPForbidden):
            _require_trusted_origin(untrusted)

    def test_native_absolute_paths_are_scoped_under_same_origin_prefix(self):
        html = (
            b'<script src="/assets/app.js"></script><link href="/manifest.webmanifest">'
            b'<script>fetch("/api/session"); import("/plugins/client.js")</script>'
        )
        rewritten = _rewrite_console_body(html, "text/html; charset=utf-8")
        self.assertIn(b'"/maintenance-console/assets/app.js"', rewritten)
        self.assertIn(b'"/maintenance-console/api/session"', rewritten)
        self.assertIn(b'"/maintenance-console/plugins/client.js"', rewritten)
        self.assertIn(b'"/maintenance-console/manifest.webmanifest"', rewritten)
        self.assertEqual(CONSOLE_COOKIE_PATH, "/maintenance-console/")

    def test_redirects_stay_inside_same_origin_console_prefix(self):
        self.assertEqual(
            "/maintenance-console/conversations/1", _rewrite_console_location("/conversations/1")
        )
        self.assertEqual(
            "/maintenance-console/api/session", _rewrite_console_location("/maintenance-console/api/session")
        )
        self.assertEqual("/maintenance-console", _rewrite_console_location("/maintenance-console"))
        self.assertEqual("https://example.com/elsewhere", _rewrite_console_location(
            "https://example.com/elsewhere"
        ))

    def test_docker_build_installs_native_peer_dependencies_instead_of_skipping_them(self):
        dockerfile = Path("Dockerfile").read_text(encoding="utf-8")
        package_json = Path("console/package.json").read_text(encoding="utf-8")
        self.assertIn("--mount=type=cache,target=/root/.npm", dockerfile)
        self.assertIn("npm install --omit=dev --force", dockerfile)
        self.assertNotIn("legacy-peer-deps", dockerfile)
        self.assertNotIn("install-strategy=shallow", dockerfile)
        self.assertIn('"@deepseek-ai/cordis-plugin-group": "1.0.1"', package_json)
        self.assertIn('"@deepseek-ai/dsh-invariants": "0.1.1-rc.2"', package_json)


def tearDownModule():
    shutil.rmtree(TEST_STATE_ROOT, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
