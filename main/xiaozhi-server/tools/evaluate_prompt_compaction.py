"""Opt-in full-template compaction comparison; no production files are changed.

The before file is a trusted local source snapshot, not user-uploaded content.
Role and model credentials stay on the server. Only synthetic model responses
and numeric usage return. At most 20 calls, no retries or model tool execution.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core.game import GameSessionManager
from core.game.prompt_profiles import default_configs
from tools.evaluate_activity_prompts import CASES


def build_cases(before):
    old_profiles, old_freeform = {}, {}
    exec(compile(before["profiles"], "trusted-before-profiles", "exec"), old_profiles)
    exec(compile(before["freeform"], "trusted-before-freeform", "exec"), old_freeform)
    old_freeform.update(PROFILES=old_profiles["PROFILES"], default_configs=old_profiles["default_configs"])
    cases = []
    for name, profile, history, rubric in CASES:
        prompts = []
        for old in (True, False):
            manager = GameSessionManager()
            if old:
                manager._freeform_game_manager = old_freeform["FreeformGameManager"](manager)
            prompt = None
            for role, content in history:
                if role == "user":
                    prompt = manager.build_freeform_game_prompt(content, {profile: {"custom_plugin": True}})
            prompts.append(prompt)
        cases.append({"case": name, "history": history, "rubric": rubric, "activities": prompts, "rich": False})
    cases += [
        {"case": "ordinary", "history": [["user", "用一句话解释为什么会下雨。"]], "activities": [None, None], "rich": False},
        {"case": "identity_memory", "history": [["user", "我喜欢散步。"], ["assistant", "记得留意天气。"], ["user", "你知道现在是谁在和你说话吗？"]], "activities": [None, None], "rich": True},
    ]
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--before", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--ssh", required=True)
    parser.add_argument("--device", required=True)
    args = parser.parse_args()
    if not args.live:
        parser.error("--live is required for up to 20 paid calls")
    before = json.loads(Path(args.before).read_text())
    payload = {"templates": [before["template"], Path("agent-base-prompt.txt").read_text()],
               "speaker_rules": [before["speaker_rules"], "\n仅代表当前轮身份，不代表历史发言者；勿混用他人资料、透露内部标签或每轮重复称呼。"],
               "cases": build_cases(before), "device": args.device}
    program = r'''
import json, re, time, yaml, requests
from jinja2 import Template
from core.utils.model_config import apply_legacy_runtime_view
try:
    private = yaml.safe_load(open('data/.config.yaml'))['manager-api']
    r = requests.post(private['url'].rstrip('/')+'/config/agent-models',
        headers={'Authorization':'Bearer '+private['secret']},
        json={'macAddress':PAYLOAD['device'],'clientId':'prompt-compaction-readonly','selectedModule':{}},timeout=(5,15))
    r.raise_for_status(); body=r.json()
    if body.get('code')!=0: raise ValueError('configuration unavailable')
    config=apply_legacy_runtime_view(body['data'])
    provider=config['LLM'][config['selected_module']['LLM']]
    url=(provider.get('base_url') or provider.get('url') or '').rstrip('/')
    if not url.startswith('https://') or provider.get('type')!='openai': raise ValueError('unsupported endpoint')
    tools=[{'type':'function','function':{'name':'direct_answer','description':'直接回答用户，将回复写入response。',
        'parameters':{'type':'object','properties':{'response':{'type':'string'}},'required':['response']}}}]
    for index, case in enumerate(PAYLOAD['cases']):
        for variant in ((0,1) if index%2==0 else (1,0)):
            rich=case['rich']
            prompt=Template(PAYLOAD['templates'][variant]).render(
                base_prompt=config.get('prompt',''),language='中文',emoji_enabled=False,
                tools_enabled=rich,available_tools_prompt='当前会话实际注册的工具仅有：direct_answer。',
                voiceprint_enabled=rich,memory_context_enabled=rich,local_context_enabled=rich,
                current_time='2026-09-23 12:00 北京时间',today_date='2026-09-23',today_weekday='星期三',lunar_date='农历测试快照',
                local_address='测试城市',weather_info='测试数据：2026-09-23，多云，预报只含今天。',
                dynamic_context='测试资料：周三开放。' if rich else '')
            if rich:
                prompt=prompt.replace('<memory>\n</memory>','<memory>测试用户喜欢散步。</memory>')
                prompt+='\n<speakers_info>\n当前说话人：测试用户'+PAYLOAD['speaker_rules'][variant]+'\n</speakers_info>'
            # Use the same static/dynamic split as Dialogue, without exporting role text.
            at=prompt.find('<context>')
            messages=[{'role':'system','content':prompt[:at] if at>=0 else prompt}]
            if rich:
                messages += [
                    {'role':'user','content':'给我讲个故事吧'},
                    {'role':'assistant','content':None,'tool_calls':[{'id':'fixture-call','type':'function','function':{'name':'direct_answer','arguments':'{"response":"你想听哪类故事？"}'}}]},
                    {'role':'tool','tool_call_id':'fixture-call','content':'已直接回复'}]
            if at>=0:messages.append({'role':'system','content':prompt[at:]})
            messages += [{'role':role,'content':text} for role,text in case['history']]
            activity=case['activities'][variant]
            if activity:messages.insert(len(messages)-1,{'role':'system','content':activity})
            params={'model':provider['model_name'],'messages':messages,'temperature':0.3,'max_tokens':450,'stream':False}
            if provider.get('thinking')=='disabled':params['thinking']={'type':'disabled'}
            if rich:params.update(tools=tools,tool_choice='none')
            start=time.monotonic()
            response=requests.post(url+'/chat/completions',headers={'Authorization':'Bearer '+provider['api_key']},json=params,timeout=(5,45))
            response.raise_for_status(); result=response.json();choice=result['choices'][0]
            print(json.dumps({'case':case['case'],'variant':'before' if variant==0 else 'after',
                'model':provider['model_name'],'temperature':0.3,'max_tokens':450,'thinking':provider.get('thinking'),
                'elapsed_ms':round((time.monotonic()-start)*1000,2),'usage':result.get('usage'),
                'finish_reason':choice.get('finish_reason'),'response':choice['message'].get('content'),
                'system_chars':sum(len(m['content']) for m in messages if m['role']=='system')},ensure_ascii=False),flush=True)
except Exception as exc:
    print(json.dumps({'error_type':type(exc).__name__}),flush=True)
    raise SystemExit(1)
'''
    source = "PAYLOAD=" + repr(payload) + "\n" + program
    with Path(args.output).open("x", encoding="utf-8") as output:
        process = subprocess.Popen(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", args.ssh,
            "sudo -n docker exec -i xiaozhi-esp32-server python -"], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        process.stdin.write(source)
        process.stdin.close()
        try:
            for line in process.stdout:
                row = json.loads(line)
                output.write(json.dumps(row, ensure_ascii=False) + "\n")
                output.flush()
                print(row.get("case", "error"), row.get("variant"), row.get("usage", {}).get("prompt_tokens"), row.get("error_type", ""), flush=True)
            if process.wait() != 0:
                raise SystemExit("Remote evaluation failed; no retries")
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait()


if __name__ == "__main__":
    main()
