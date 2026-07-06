#!/usr/bin/env python
"""Render managed-memory self-check JSON as a local HTML dashboard."""

from __future__ import annotations

import argparse
import html
import json
from pathlib import Path


def esc(value) -> str:
    return html.escape("" if value is None else str(value))


def badge(text: str, kind: str = "neutral") -> str:
    return f'<span class="badge {kind}">{esc(text)}</span>'


def score_block(title: str, items: list[dict]) -> str:
    matched = sum(1 for item in items if item.get("matched"))
    total = len(items)
    rows = "\n".join(
        f"""
        <tr>
          <td>{esc(item.get("label"))}</td>
          <td>{badge("命中" if item.get("matched") else "缺失", "ok" if item.get("matched") else "warn")}</td>
          <td>{badge(item.get("sensitivity", "normal"), "sensitive" if item.get("sensitivity") == "sensitive" else "neutral")}</td>
        </tr>
        """
        for item in items
    )
    return f"""
    <section>
      <h2>{esc(title)} <small>{matched}/{total}</small></h2>
      <table><tbody>{rows}</tbody></table>
    </section>
    """


def fact_cards(facts: list[dict]) -> str:
    return "\n".join(
        f"""
        <article class="card">
          <div class="card-head">
            {badge(fact.get("category", "未分类"))}
            {badge(fact.get("sensitivity", "normal"), "sensitive" if fact.get("sensitivity") == "sensitive" else "neutral")}
            {badge("可注入" if fact.get("should_inject") else "仅直接询问", "ok" if fact.get("should_inject") else "warn")}
          </div>
          <p>{esc(fact.get("canonical_text"))}</p>
          <small>{esc(fact.get("reason"))}</small>
        </article>
        """
        for fact in facts
    )


def simple_cards(items: list[dict], text_key: str, reason_key: str = "reason") -> str:
    return "\n".join(
        f"""
        <article class="mini-card">
          <p>{esc(item.get(text_key))}</p>
          <small>{esc(item.get(reason_key))}</small>
        </article>
        """
        for item in items
    )


def render(report: dict) -> str:
    live = report.get("deepseek_live", {})
    excerpts = live.get("raw_excerpt_redacted", {})
    facts = excerpts.get("facts", [])
    ignored = excerpts.get("ignored", [])
    tombstones = excerpts.get("forget_tombstones", [])
    summary = report.get("summary", {})
    usage = live.get("usage", {})
    forget_live = live.get("forget_live", {})

    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>智控台治理记忆自检看板</title>
  <style>
    :root {{
      --bg: #f6f7f9;
      --panel: #ffffff;
      --text: #17202a;
      --muted: #667085;
      --line: #d9dee7;
      --ok: #168557;
      --warn: #a15c00;
      --sensitive: #b42318;
      --blue: #2354a6;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: var(--bg);
      color: var(--text);
    }}
    header {{
      padding: 28px 36px 18px;
      background: #fff;
      border-bottom: 1px solid var(--line);
    }}
    h1 {{ margin: 0 0 10px; font-size: 26px; }}
    h2 {{ margin: 0 0 14px; font-size: 18px; }}
    h2 small {{ color: var(--muted); font-weight: 500; }}
    p {{ line-height: 1.55; }}
    .meta {{ display: flex; flex-wrap: wrap; gap: 8px; }}
    .layout {{
      display: grid;
      grid-template-columns: 300px 1fr;
      gap: 18px;
      padding: 18px;
    }}
    section {{
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 16px;
      margin-bottom: 18px;
    }}
    .flow {{
      display: grid;
      gap: 10px;
    }}
    .step {{
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 12px;
      background: #fbfcfe;
    }}
    .step strong {{ display: block; margin-bottom: 4px; }}
    .step span {{ color: var(--muted); font-size: 13px; }}
    .cards {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
      gap: 12px;
    }}
    .card, .mini-card {{
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 12px;
      background: #fff;
    }}
    .card-head {{ display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }}
    .card p, .mini-card p {{ margin: 0 0 8px; }}
    small {{ color: var(--muted); }}
    .badge {{
      display: inline-flex;
      align-items: center;
      min-height: 24px;
      padding: 2px 8px;
      border-radius: 999px;
      background: #eef2f7;
      color: #344054;
      font-size: 12px;
      border: 1px solid transparent;
    }}
    .badge.ok {{ background: #e8f7ef; color: var(--ok); }}
    .badge.warn {{ background: #fff4e5; color: var(--warn); }}
    .badge.sensitive {{ background: #fdecea; color: var(--sensitive); }}
    table {{ width: 100%; border-collapse: collapse; }}
    td {{ padding: 8px 6px; border-top: 1px solid var(--line); font-size: 13px; }}
    @media (max-width: 900px) {{
      .layout {{ grid-template-columns: 1fr; }}
    }}
  </style>
</head>
<body>
  <header>
    <h1>智控台治理记忆自检看板</h1>
    <div class="meta">
      {badge("DeepSeek: " + str(live.get("model", "未启用")), "ok" if live.get("ok") else "warn")}
      {badge("黄金事实 " + str(summary.get("gold_matched", 0)) + "/" + str(summary.get("gold_total", 0)), "ok")}
      {badge("忘记规则 " + str(summary.get("forget_cases_covered", 0)) + "/5", "ok")}
      {badge("事实数 " + str(live.get("facts_count", 0)))}
      {badge("tokens " + str(usage.get("total_tokens", 0)))}
    </div>
  </header>
  <main class="layout">
    <aside>
      <section>
        <h2>处理流程</h2>
        <div class="flow">
          <div class="step"><strong>1. 读取真实聊天</strong><span>{esc(report.get("input", {}).get("lines"))} 行，原文不写入报告。</span></div>
          <div class="step"><strong>2. 脱敏扫描</strong><span>key/token/secret 命中：{esc(report.get("input", {}).get("secret_pattern_hits_after_redaction"))}</span></div>
          <div class="step"><strong>3. 维护模型抽取</strong><span>只保留用户明确说过或确认过的长期事实。</span></div>
          <div class="step"><strong>4. 忽略低质内容</strong><span>助手推断、工具结果、玩笑、寒暄不入库。</span></div>
          <div class="step"><strong>5. 显式忘记</strong><span>生成 tombstone，阻止历史重建回流。</span></div>
        </div>
      </section>
      {score_block("黄金事实断言", live.get("gold_facts", []))}
      {score_block("忘记断言", live.get("forget_cases", []))}
      {score_block("忽略风险", live.get("ignore_checks", []))}
    </aside>
    <div>
      <section>
        <h2>已抽取事实</h2>
        <div class="cards">{fact_cards(facts)}</div>
      </section>
      <section>
        <h2>忘记 Tombstone</h2>
        <div class="cards">{simple_cards(tombstones, "pattern_text")}</div>
      </section>
      <section>
        <h2>忽略项</h2>
        <div class="cards">{simple_cards(ignored, "text")}</div>
      </section>
      <section>
        <h2>运行摘要</h2>
        <p>主调用 tokens：{esc(usage.get("total_tokens"))}；显式忘记 tokens：{esc(forget_live.get("usage", {}).get("total_tokens"))}。</p>
        <p>报告来自本地 runtime 目录；不包含 DeepSeek API key，不包含完整原始聊天记录。</p>
      </section>
    </div>
  </main>
</body>
</html>
"""


def main() -> int:
    parser = argparse.ArgumentParser(description="Render memory self-check dashboard.")
    parser.add_argument("--input", default="runtime/memory-self-check-deepseek-report.json")
    parser.add_argument("--output", default="runtime/memory-self-check-visual.html")
    args = parser.parse_args()

    report = json.loads(Path(args.input).read_text(encoding="utf-8"))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render(report), encoding="utf-8")
    print(output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
