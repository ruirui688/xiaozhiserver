"""Opt-in, bounded live A/B sample. Not a unit test or a production benchmark.

Run from main/xiaozhi-server with the project Conda environment. Uses synthetic
histories, never device conversations. Credentials are read locally, not logged.
"""
import argparse
import json
from pathlib import Path
import sys
import time
import subprocess

import requests
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core.game import GameSessionManager
from core.game.prompt_profiles import default_configs


CASES = [
    ("idiom_strict", "idiom_chain", [
        ("user", "我们玩严格同字成语接龙，我先出：空空如也。请接一个，不要改规则。"),
    ], "不得以亦步亦趋冒充严格同字接龙；无法可靠接上时承认，不造词。"),
    ("idiom_challenge", "idiom_chain", [
        ("user", "成语接龙，严格同字。我接空空如也。"),
        ("assistant", "也步也趋。接着来。"),
        ("user", "也不也趋是成语吗？你是不是接错了？"),
    ], "承认错误，说明亦步亦趋，不换规则、不编造也字成语。"),
    ("riddle_score", "riddle", [
        ("user", "猜谜语。独立答对2分、提示后答对1分、揭底0分，每完成一题一关。现在0分0关。"),
        ("assistant", "第一题，猜物品：有齿不咬人，专给头发理。"),
        ("user", "给个提示"),
        ("assistant", "梳头时会用到它。"),
        ("user", "梳子。说一下累计分数和关数，先不要出新题。"),
    ], "梳子正确，累计1分1关，不出新题。"),
    ("riddle_user", "riddle", [
        ("user", "猜谜语，换成我出题你猜：千条线，万条线，掉到水里看不见。猜自然现象。"),
    ], "猜雨并解释，等用户确认，不自行计分或反向出题。"),
    ("standup_full", "standup", [
        ("user", "写一段180到250字的职场年会脱口秀，温和搞笑，有观众互动，给完整稿子不要提纲。"),
    ], "完整开场、递进反转、收尾和互动；人工审阅笑点，不靠关键词声称质量。"),
    ("standup_edit", "standup", [
        ("user", "我们写脱口秀，下面三段定稿。"),
        ("assistant", "第一段：我上班最大的运动，是在工位和会议室之间折返跑。\n第二段：老板说要提高效率，于是会议从一小时开到了两小时。\n第三段：愿大家下班有生活，上班有盼头。"),
        ("user", "只替换第二段的笑点，其他两段一个字都不要改。输出修改后的完整三段。"),
    ], "第一、三段原样保留，只替换第二段。"),
    ("crosstalk_full", "crosstalk", [
        ("user", "写一段200到280字的职场对口相声，标注逗哏捧哏，有三翻四抖和收尾底，不要提纲。"),
    ], "两角色承接、三次铺垫后反转、收尾；人工审阅。"),
    ("crosstalk_rehearse", "crosstalk", [
        ("user", "相声排练，稿子如下。"),
        ("assistant", "逗哏：我这闹钟可智能了。\n捧哏：怎么个智能法？\n逗哏：它一响，我就自动请假。\n捧哏：敢情智能的是你呀！"),
        ("user", "你只读捧哏，我读逗哏。先读捧哏第一句，不读角色名，不加解释，等我接话。"),
    ], "只输出怎么个智能法，不输出标签、第二句或演出指令。"),
]


def messages_for(profile, history, enhanced):
    messages = [{"role": "system", "content": "你是中文语音助手小智，请根据对话自然回答用户。"}]
    if enhanced:
        manager = GameSessionManager()
        # This evaluates an explicitly selected profile, not activity routing.
        prompt = manager.build_freeform_game_prompt(None, default_configs(), active_name=profile)
        if not prompt or f'<active_interaction name="{profile}">' not in prompt:
            raise ValueError("expected activity rules are unavailable or over prompt budget")
    else:
        prompt = None
    messages.extend({"role": role, "content": content} for role, content in history)
    if prompt:
        messages.insert(len(messages) - 1, {"role": "system", "content": prompt})
    return messages


def remote_evaluation(args):
    """Send only synthetic prompts over SSH; credentials never leave the server.

    No remote files are written. The deployed container's own Python is used for
    this diagnostic, while the driver runs under the project Conda environment.
    """
    rows = []
    for index, (case, profile, history, rubric) in enumerate(CASES):
        for enhanced in ((False, True) if index % 2 == 0 else (True, False)):
            rows.append({"case": case, "enhanced": enhanced, "rubric": rubric,
                         "messages": messages_for(profile, history, enhanced)})
    program = """
import json, time, yaml, requests
from core.utils.model_config import apply_legacy_runtime_view
try:
    private = yaml.safe_load(open('data/.config.yaml'))['manager-api']
    response = requests.post(private['url'].rstrip('/') + '/config/agent-models',
        headers={'Authorization': 'Bearer ' + private['secret']},
        json={'macAddress': DEVICE, 'clientId': 'prompt-ab-readonly', 'selectedModule': {}},
        timeout=(5, 15))
    response.raise_for_status()
    data = response.json()
    if data.get('code') != 0:
        raise ValueError('configuration unavailable')
    config = apply_legacy_runtime_view(data['data'])
    name = config['selected_module']['LLM']
    provider = config['LLM'][name]
    url = (provider.get('base_url') or provider.get('url') or '').rstrip('/')
    if not url.startswith('https://') or provider.get('type') != 'openai':
        raise ValueError('unsupported endpoint')
    for row in ROWS:
        start = time.monotonic()
        params = {'model': provider['model_name'], 'messages': row['messages'],
                  'temperature': 0.3, 'max_tokens': 650, 'stream': False}
        if provider.get('thinking') == 'disabled':
            params['thinking'] = {'type': 'disabled'}
        response = requests.post(url + '/chat/completions',
            headers={'Authorization': 'Bearer ' + provider['api_key']},
            json=params, timeout=(5, 45))
        response.raise_for_status()
        result = response.json()
        choice = result['choices'][0]
        row.update(model=provider['model_name'], temperature=0.3, max_tokens=650,
            thinking=provider.get('thinking'), usage=result.get('usage'),
            elapsed_ms=round((time.monotonic()-start)*1000, 2),
            finish_reason=choice.get('finish_reason'),
            response=choice['message'].get('content') or '')
        print(json.dumps(row, ensure_ascii=False), flush=True)
except Exception as exc:
    print(json.dumps({'error_type': type(exc).__name__}), flush=True)
    raise SystemExit(1)
"""
    source = "ROWS = " + repr(rows) + "\nDEVICE = " + repr(args.device) + "\n" + program
    with Path(args.output).open("x", encoding="utf-8") as output:
        process = subprocess.Popen(
            ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", args.ssh,
             "sudo -n docker exec -i xiaozhi-esp32-server python -"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True,
        )
        process.stdin.write(source)
        process.stdin.close()
        try:
            for line in process.stdout:
                row = json.loads(line)
                output.write(json.dumps(row, ensure_ascii=False) + "\n")
                output.flush()
                print(row.get('case', 'error'), row.get('enhanced'),
                      row.get('elapsed_ms'), row.get('error_type', ''), flush=True)
            if process.wait() != 0:
                raise SystemExit("Remote evaluation stopped; no automatic retries")
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="explicitly allow up to 16 paid HTTP calls")
    parser.add_argument("--config", default="data/.config.yaml")
    parser.add_argument("--provider", default="DeepSeekLLM")
    parser.add_argument("--ssh", help="use the deployed model without exporting credentials")
    parser.add_argument("--device", help="existing device for authoritative model configuration")
    parser.add_argument("--output", required=True, help="new private runtime JSONL file; never overwritten")
    args = parser.parse_args()
    if not args.live:
        parser.error("--live is required; this is not an offline unit test")
    if args.ssh:
        if not args.device:
            parser.error("--device is required with --ssh")
        return remote_evaluation(args)
    config = yaml.safe_load(Path(args.config).read_text())
    provider = config["LLM"][args.provider]
    key = provider.get("api_key") or ""
    if not key or "你的" in key:
        raise ValueError("configured provider has no usable credentials")
    url = (provider.get("base_url") or provider.get("url") or "").rstrip("/")
    if not url.startswith("https://"):
        raise ValueError("live evaluation requires HTTPS")
    model = provider["model_name"]
    # Exclusive output creation avoids destroying previous samples.
    with Path(args.output).open("x", encoding="utf-8") as output:
        for index, (case, profile, history, rubric) in enumerate(CASES):
            # Alternate ordering to reduce systematic warm-cache/order effects.
            for enhanced in ((False, True) if index % 2 == 0 else (True, False)):
                messages = messages_for(profile, history, enhanced)
                start = time.monotonic()
                try:
                    response = requests.post(url + "/chat/completions", headers={
                        "Authorization": "Bearer " + key,
                    }, json={"model": model, "messages": messages, "temperature": 0.3,
                             "max_tokens": 650, "stream": False}, timeout=(5, 45))
                    response.raise_for_status()
                    data = response.json()
                    text = data["choices"][0]["message"].get("content") or ""
                    row = {"case": case, "enhanced": enhanced, "model": model,
                           "temperature": 0.3, "max_tokens": 650,
                           "elapsed_ms": round((time.monotonic() - start) * 1000, 2),
                           "usage": data.get("usage"), "finish_reason": data["choices"][0].get("finish_reason"),
                           "rubric": rubric, "messages": messages, "response": text}
                    output.write(json.dumps(row, ensure_ascii=False) + "\n")
                    output.flush()
                    print(case, "enhanced" if enhanced else "baseline", row["elapsed_ms"], "ms", flush=True)
                except Exception as exc:
                    # No exception text: upstream errors may echo payload or secrets.
                    print("Stopped:", type(exc).__name__, "no retries", flush=True)
                    raise SystemExit(1)


if __name__ == "__main__":
    main()
