import asyncio
import json

from core.api.local_asr_hotwords_handler import LocalAsrHotwordsHandler


class Request:
    def __init__(self, headers=None, body=None):
        self.headers = headers or {}
        self._body = body

    async def json(self):
        return self._body


def handler(tmp_path):
    return LocalAsrHotwordsHandler({"server": {"auth_key": "server", "local_asr_hotwords_path": str(tmp_path / "hotwords.json")}, "manager-api": {"secret": "manager"}})


def test_empty_and_unauthenticated(tmp_path):
    h = handler(tmp_path)
    empty = asyncio.run(h.handle_get(Request({})))
    assert empty.status == 401
    empty = asyncio.run(h.handle_get(Request({"Authorization": "Bearer server"})))
    assert json.loads(empty.text) == {"version": 0, "updated_at": None, "enabled": True, "replacements": []}


def test_save_update_delete_and_version(tmp_path):
    h = handler(tmp_path)
    req = Request({"Authorization": "Bearer manager"}, {"enabled": True, "replacements": [{"source": "小智", "target": "小智助手"}]})
    first = asyncio.run(h.handle_save(req))
    assert json.loads(first.text)["version"] == 1
    second = asyncio.run(h.handle_save(Request(req.headers, {"replacements": []})))
    assert json.loads(second.text)["version"] == 2
    assert json.loads(asyncio.run(h.handle_get(Request(req.headers))).text)["replacements"] == []


def test_reject_duplicate_and_cycle(tmp_path):
    h = handler(tmp_path)
    headers = {"Authorization": "Bearer server"}
    duplicate = {"replacements": [{"source": "a", "target": "b"}, {"source": "a", "target": "c"}]}
    assert asyncio.run(h.handle_save(Request(headers, duplicate))).status == 400
    cycle = {"replacements": [{"source": "a", "target": "b"}, {"source": "b", "target": "a"}]}
    assert asyncio.run(h.handle_save(Request(headers, cycle))).status == 400


def test_sse_version_event(tmp_path):
    h = handler(tmp_path)
    queue = asyncio.Queue()
    h._listeners.add(queue)
    asyncio.run(h.handle_save(Request({"Authorization": "Bearer server"}, {"replacements": []})))
    assert asyncio.run(queue.get()) == 1
