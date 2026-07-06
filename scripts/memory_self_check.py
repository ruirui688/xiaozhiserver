#!/usr/bin/env python
"""Managed memory self-check harness for real chat history files.

The script reads external history files, redacts sensitive tokens, and produces
an aggregate report. It does not copy raw chat logs into the repository.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


DEFAULT_HISTORY_FILES = [
    "/mnt/c/Users/23019/Downloads/history (5).txt",
    "/mnt/c/Users/23019/Downloads/history (6).txt",
]

SECRET_PATTERNS = [
    re.compile(
        r"(?i)\b(api[_-]?key|secret[_-]?key|access[_-]?token|authorization|bearer|password)"
        r"\s*[:=：]\s*([^\s,，。；;]{8,})"
    ),
    re.compile(r"\bsk-[A-Za-z0-9_-]{16,}\b"),
]


@dataclass(frozen=True)
class GoldCase:
    label: str
    aliases: tuple[str, ...]
    sensitivity: str = "normal"


GOLD_FACTS = [
    GoldCase("black_torpedo6_bike", ("鱼雷6", "黑色", "自行车")),
    GoldCase("wanted_white_bike", ("白色", "鱼雷6")),
    GoldCase("tomato_sauce_dumplings", ("番茄酱", "饺子")),
    GoldCase("programmer_or_coding", ("开发",)),
    GoldCase("company_jinyi_danyang", ("锦衣公司", "丹阳")),
    GoldCase("leader_zhong_wenjie", ("钟文杰",), "sensitive"),
    GoldCase("work_schedule", ("上班", "6")),
    GoldCase("france_football", ("法国",)),
    GoldCase("favorite_singers", ("孙燕姿", "周杰伦", "林俊杰")),
    GoldCase("driving_subject_two", ("六次",), "sensitive"),
    GoldCase("move_to_ningbo", ("宁波", "浙江")),
    GoldCase("lunar_birthday", ("农历", "六月二十"), "sensitive"),
    GoldCase("secret_do_not_tell_leader", ("不想上班", "钟文杰"), "sensitive"),
]

IGNORE_RISKS = [
    GoldCase("assistant_memory_claim", ("记住了", "锁芯片")),
    GoldCase("weather_or_tool_result", ("天气", "工具", "搜索")),
    GoldCase("local_person_inference", ("镇江本地人",)),
    GoldCase("insult_or_joke", ("傻逼", "笨蛋")),
]

FORGET_CASES = [
    GoldCase("forget_secret", ("不想上班", "钟文杰"), "sensitive"),
    GoldCase("forget_driving_test", ("六次",), "sensitive"),
    GoldCase("forget_company", ("锦衣公司", "丹阳"), "sensitive"),
    GoldCase("forget_birthday", ("农历", "六月二十"), "sensitive"),
    GoldCase("forget_bike", ("鱼雷6", "自行车")),
]


def read_text(path: Path) -> str:
    data = path.read_bytes()
    for encoding in ("utf-8", "utf-8-sig", "gb18030"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def redact(text: str) -> str:
    redacted = text
    redacted = re.sub(
        r"(?i)(api[_-]?key|secret[_-]?key|access[_-]?token|authorization|bearer|password)"
        r"(\s*[:=：]\s*)([^\s,，。；;]+)",
        r"\1\2***",
        redacted,
    )
    redacted = re.sub(r"\bsk-[A-Za-z0-9_-]{16,}\b", "sk-***", redacted)
    return redacted


def contains_all(text: str, aliases: Iterable[str]) -> bool:
    return all(contains_alias(text, alias) for alias in aliases)


def contains_alias(text: str, alias: str) -> bool:
    if alias == "六次":
        return any(token in text for token in ("六次", "第六次", "6次", "第6次"))
    return alias in text


def evaluate_cases(text: str, cases: list[GoldCase]) -> list[dict[str, str | bool]]:
    return [
        {
            "label": case.label,
            "matched": contains_all(text, case.aliases),
            "sensitivity": case.sensitivity,
        }
        for case in cases
    ]


def count_secret_hits(text: str) -> int:
    return sum(len(pattern.findall(text)) for pattern in SECRET_PATTERNS)


def post_events_if_requested(text: str, args: argparse.Namespace) -> dict[str, int | str]:
    if not args.post_events:
        return {"posted": 0, "skipped": "post disabled"}
    if not args.manager_url or not args.server_secret or not args.agent_id:
        return {"posted": 0, "skipped": "manager_url/server_secret/agent_id required"}

    lines = [line.strip() for line in text.splitlines() if line.strip()]
    posted = 0
    for idx, line in enumerate(lines[: args.max_events]):
        payload = {
            "eventId": hashlib.sha256(f"memory-self-check|{idx}|{line}".encode("utf-8")).hexdigest(),
            "agentId": args.agent_id,
            "macAddress": args.mac_address,
            "sessionId": args.session_id,
            "chatType": 1,
            "eventType": "chat",
            "contentText": line,
        }
        req = urllib.request.Request(
            args.manager_url.rstrip("/") + "/config/memory/events",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + args.server_secret,
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                if response.status < 400:
                    posted += 1
        except urllib.error.URLError as exc:
            return {"posted": posted, "skipped": f"post failed: {exc.__class__.__name__}"}
    return {"posted": posted}


def deepseek_request(api_key: str, path: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        "https://api.deepseek.com" + path,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + api_key,
        },
        method="GET" if payload is None else "POST",
    )
    with urllib.request.urlopen(req, timeout=120) as response:
        return json.loads(response.read().decode("utf-8"))


def run_deepseek_assessment(text: str, args: argparse.Namespace) -> dict:
    api_key = os.getenv(args.deepseek_api_key_env, "")
    if not args.deepseek_live:
        return {"enabled": False}
    if not api_key:
        return {"enabled": True, "ok": False, "error": f"missing env {args.deepseek_api_key_env}"}

    result: dict = {"enabled": True, "model": args.deepseek_model}
    try:
        models = deepseek_request(api_key, "/models")
        available = [item.get("id") for item in models.get("data", []) if isinstance(item, dict)]
        result["models_available"] = available
        result["model_list_ok"] = args.deepseek_model in available
    except Exception as exc:  # noqa: BLE001 - report sanitized class name only
        result["model_list_ok"] = False
        result["model_list_error"] = exc.__class__.__name__

    prompt = f"""
你是长期记忆治理评测器。请只基于下面聊天记录中“用户明确说过或确认过”的内容，输出 JSON。
不要把助手猜测、工具结果、联网搜索内容、助手承诺“记住了”、玩笑、辱骂、寒暄写成用户记忆。
敏感信息可以识别，但要标注 sensitivity=sensitive；API key/token/password/secret 永远不要写入 facts。
如果用户同时表达“原本想要/偏好 X，但最终选择/得到 Y”，不要只保留最终状态；应把原始偏好和最终状态合并为同一条可读事实。
用户要求对第三方保密的秘密、生日、驾考挫折等隐私经历必须标为 sensitivity=sensitive。第三方保密型秘密必须 should_inject=false。

请输出：
{{
  "facts": [
    {{
      "canonical_text": "单条长期事实",
      "category": "中文分类",
      "sensitivity": "normal|personal|sensitive",
      "should_inject": true,
      "reason": "少于20字"
    }}
  ],
  "ignored": [
    {{"text": "被忽略的原因概括", "reason": "assistant_inference|tool_result|low_value|unsafe_secret|joke"}}
  ],
  "forget_tombstones": [
    {{"pattern_text": "可用于阻止回流的忘记规则", "reason": "少于20字"}}
  ]
}}

聊天记录：
{text[:args.deepseek_max_chars]}
"""
    payload = {
        "model": args.deepseek_model,
        "messages": [
            {"role": "system", "content": "你只返回合法 JSON，不要 Markdown。"},
            {"role": "user", "content": prompt},
        ],
        "response_format": {"type": "json_object"},
        "temperature": 0.0,
        "max_tokens": args.deepseek_max_tokens,
        "stream": False,
    }
    try:
        completion = deepseek_request(api_key, "/chat/completions", payload)
        message = completion.get("choices", [{}])[0].get("message", {})
        content = message.get("content", "{}")
        parsed = json.loads(content)
        facts_text = json.dumps(parsed.get("facts", []), ensure_ascii=False)
        tombstones = parsed.get("forget_tombstones", [])
        forget_result = run_deepseek_forget_scenarios(api_key, args, parsed.get("facts", []))
        if forget_result.get("ok") and isinstance(forget_result.get("tombstones"), list):
            tombstones = forget_result["tombstones"]
        tombstone_text = json.dumps(tombstones, ensure_ascii=False)
        ignored_text = json.dumps(parsed.get("ignored", []), ensure_ascii=False)
        result.update(
            {
                "ok": True,
                "usage": completion.get("usage", {}),
                "facts_count": len(parsed.get("facts", [])),
                "ignored_count": len(parsed.get("ignored", [])),
                "tombstones_count": len(tombstones),
                "forget_live": forget_result,
                "gold_facts": evaluate_cases(facts_text, GOLD_FACTS),
                "forget_cases": evaluate_cases(tombstone_text + "\n" + facts_text, FORGET_CASES),
                "ignore_checks": evaluate_cases(ignored_text + "\n" + facts_text, IGNORE_RISKS),
                "raw_excerpt_redacted": {
                    "facts": parsed.get("facts", [])[: args.deepseek_report_items],
                    "ignored": parsed.get("ignored", [])[: args.deepseek_report_items],
                    "forget_tombstones": tombstones[: args.deepseek_report_items],
                },
            }
        )
    except Exception as exc:  # noqa: BLE001
        result.update({"ok": False, "error": exc.__class__.__name__})
    return result


def run_deepseek_forget_scenarios(api_key: str, args: argparse.Namespace, facts: list[dict]) -> dict:
    scenarios = [
        "忘记我不想让钟文杰知道自己不想上班",
        "忘记我科目二六次才过",
        "忘记我在丹阳锦衣公司工作",
        "忘记我的农历六月二十生日",
        "忘记我的鱼雷6自行车",
    ]
    prompt = f"""
你是长期记忆显式忘记测试器。请根据已有事实和忘记指令，输出 tombstone 规则。
规则用于阻止历史重建把同类事实重新写回。不要新增事实。

已有事实：
{json.dumps(facts, ensure_ascii=False)}

忘记指令：
{json.dumps(scenarios, ensure_ascii=False)}

只返回 JSON：
{{
  "tombstones": [
    {{"pattern_text": "忘记规则核心文本", "reason": "用户要求忘记"}}
  ]
}}
"""
    payload = {
        "model": args.deepseek_model,
        "messages": [
            {"role": "system", "content": "你只返回合法 JSON，不要 Markdown。"},
            {"role": "user", "content": prompt},
        ],
        "response_format": {"type": "json_object"},
        "temperature": 0.0,
        "max_tokens": 2000,
        "stream": False,
    }
    try:
        completion = deepseek_request(api_key, "/chat/completions", payload)
        parsed = json.loads(completion.get("choices", [{}])[0].get("message", {}).get("content", "{}"))
        tombstones = parsed.get("tombstones", [])
        return {
            "ok": True,
            "usage": completion.get("usage", {}),
            "tombstones": tombstones,
            "cases": evaluate_cases(json.dumps(tombstones, ensure_ascii=False), FORGET_CASES),
        }
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": exc.__class__.__name__}


def main() -> int:
    parser = argparse.ArgumentParser(description="Self-check managed memory with real chat histories.")
    parser.add_argument("files", nargs="*", default=DEFAULT_HISTORY_FILES)
    parser.add_argument("--report", help="Write JSON report to this path.")
    parser.add_argument("--post-events", action="store_true", help="POST sanitized user lines to manager-api.")
    parser.add_argument("--manager-url", default=os.getenv("MEMORY_SELF_CHECK_MANAGER_URL", ""))
    parser.add_argument("--server-secret", default=os.getenv("MEMORY_SELF_CHECK_SERVER_SECRET", ""))
    parser.add_argument("--agent-id", default=os.getenv("MEMORY_SELF_CHECK_AGENT_ID", ""))
    parser.add_argument("--mac-address", default=os.getenv("MEMORY_SELF_CHECK_MAC", "SELF_CHECK_DEVICE"))
    parser.add_argument("--session-id", default=os.getenv("MEMORY_SELF_CHECK_SESSION", "memory-self-check"))
    parser.add_argument("--max-events", type=int, default=200)
    parser.add_argument("--deepseek-live", action="store_true", help="Call DeepSeek API for live extraction.")
    parser.add_argument("--deepseek-api-key-env", default="DEEPSEEK_API_KEY")
    parser.add_argument("--deepseek-model", default="deepseek-v4-pro")
    parser.add_argument("--deepseek-max-chars", type=int, default=120000)
    parser.add_argument("--deepseek-max-tokens", type=int, default=8000)
    parser.add_argument("--deepseek-report-items", type=int, default=40)
    args = parser.parse_args()

    loaded = []
    combined_parts = []
    for raw_path in args.files:
        path = Path(raw_path)
        if not path.exists():
            loaded.append({"path": str(path), "exists": False})
            continue
        text = read_text(path)
        combined_parts.append(text)
        loaded.append({
            "path": str(path),
            "exists": True,
            "bytes": path.stat().st_size,
            "lines": len(text.splitlines()),
        })

    combined = "\n".join(combined_parts)
    sanitized = redact(combined)
    gold = evaluate_cases(sanitized, GOLD_FACTS)
    ignore = evaluate_cases(sanitized, IGNORE_RISKS)
    forget = evaluate_cases(sanitized, FORGET_CASES)
    report = {
        "files": loaded,
        "input": {
            "lines": len(combined.splitlines()),
            "chars": len(combined),
            "secret_pattern_hits_before_redaction": count_secret_hits(combined),
            "secret_pattern_hits_after_redaction": count_secret_hits(sanitized),
        },
        "gold_facts": gold,
        "ignore_risks": ignore,
        "forget_cases": forget,
        "summary": {
            "gold_matched": sum(1 for item in gold if item["matched"]),
            "gold_total": len(gold),
            "ignore_risks_present": sum(1 for item in ignore if item["matched"]),
            "forget_cases_covered": sum(1 for item in forget if item["matched"]),
        },
        "event_posting": post_events_if_requested(sanitized, args),
        "deepseek_live": run_deepseek_assessment(sanitized, args),
        "notes": [
            "Raw chat text is not included in this report.",
            "Rotate any API key that appeared in screenshots or source histories before enabling real model calls.",
        ],
    }

    output = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(output + "\n", encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
