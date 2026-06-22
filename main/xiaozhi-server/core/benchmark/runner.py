import asyncio
import os
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor

from core.benchmark.asr_executor import AsrTestAudioNotFound, run_asr_samples
from core.benchmark.metrics import (
    percentile,
    round_number,
)
from core.benchmark.tts_executor import run_tts_samples
from core.benchmark.llm_cases import LLM_BENCHMARK_CASES
from core.benchmark.llm_judge import (
    build_case_dialogue_with_history,
    evaluate_llm_case_batch,
    get_case_turns,
    summarize_llm_judge,
)
from core.benchmark.runtime_agent import BenchmarkRuntimeAgent
from core.utils.llm import create_instance as create_llm_instance


BENCHMARK_RUNTIME_DIR = os.path.join(tempfile.gettempdir(), "xiaozhi-benchmark")


async def run_benchmark(payload, base_config=None):
    models = payload.get("models") or []
    iterations = int(payload.get("iterations") or 3)
    concurrency = max(1, min(int(payload.get("concurrency") or 3), 8))
    judge_config = payload.get("judge_config") or {}
    agent_context = payload.get("agent_context") or {}
    benchmark_cases = _normalize_benchmark_cases(
        payload.get("benchmark_cases")
        or payload.get("custom_questions")
        or payload.get("customQuestions")
    )
    loop = asyncio.get_running_loop()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        tasks = [
            loop.run_in_executor(
                executor,
                lambda item=model: asyncio.run(
                    _run_model(
                        item,
                        iterations,
                        judge_config,
                        agent_context,
                        base_config,
                        benchmark_cases,
                    )
                ),
            )
            for model in models
        ]
        results = await asyncio.gather(*tasks, return_exceptions=True)

    normalized_results = []
    for model, result in zip(models, results):
        if isinstance(result, Exception):
            normalized_results.append(_failed_result(model, str(result)))
        else:
            normalized_results.append(result)
    return {"task_id": payload.get("task_id"), "results": normalized_results}


async def _run_model(
    model,
    iterations,
    judge_config=None,
    agent_context=None,
    base_config=None,
    benchmark_cases=None,
):
    model_type = str(model.get("model_type") or "").upper()
    provider_type = str(model.get("provider_type") or model.get("config", {}).get("type") or "")
    config = _prepare_runtime_config(model.get("config") or {})
    if model_type in ("LLM", "SLM"):
        return await _run_llm(
            model,
            provider_type,
            config,
            iterations,
            judge_config or {},
            agent_context or {},
            base_config or {},
            benchmark_cases or LLM_BENCHMARK_CASES,
        )
    if model_type == "ASR":
        return await _run_asr(model, provider_type, config, iterations)
    if model_type == "TTS":
        return await _run_tts(model, provider_type, config, iterations)
    return _unsupported_result(model, "当前专业评测暂未覆盖该模型类型")


async def _run_llm(
    model,
    provider_type,
    config,
    iterations,
    judge_config=None,
    agent_context=None,
    base_config=None,
    benchmark_cases=None,
):
    benchmark_cases = benchmark_cases or LLM_BENCHMARK_CASES
    device_context = (agent_context or {}).get("device_context") or {}
    if device_context.get("mac_address") and base_config:
        return await _run_llm_with_agent_runtime(
            model,
            provider_type,
            config,
            iterations,
            judge_config or {},
            agent_context or {},
            base_config or {},
            benchmark_cases,
        )
    llm = create_llm_instance(provider_type, config)
    samples = []
    case_iteration = 0
    for repeat in range(iterations):
        batch_inputs = []
        for case in benchmark_cases:
            case_iteration += 1
            conversation = []
            total_latency_ms = 0
            first_token_ms = None
            final_output = ""
            message = "OK"
            token_count = 0
            try:
                turns = get_case_turns(case)
                if not turns:
                    raise RuntimeError("LLM评测样本缺少多轮用户输入")
                for turn in turns:
                    conversation.append({"role": "user", "content": turn})
                    started = time.perf_counter()
                    turn_output = ""
                    dialogue = build_case_dialogue_with_history(case, conversation, agent_context)
                    for chunk in llm.response("benchmark", dialogue):
                        if chunk:
                            if first_token_ms is None:
                                first_token_ms = int((time.perf_counter() - started) * 1000)
                            turn_output += str(chunk)
                            token_count += 1
                    turn_latency_ms = int((time.perf_counter() - started) * 1000)
                    total_latency_ms += turn_latency_ms
                    final_output = turn_output
                    conversation.append({"role": "assistant", "content": turn_output})
                    if not turn_output.strip():
                        message = "模型返回为空"
                        break
                tokens_per_second = round_number(token_count / max(total_latency_ms / 1000, 0.001), 2)
            except Exception as exc:
                total_latency_ms = total_latency_ms or 0
                tokens_per_second = None
                message = str(exc)
            batch_inputs.append({
                "case": case,
                "output": final_output,
                "conversation": conversation,
                "latency_ms": total_latency_ms,
                "ttft_ms": first_token_ms,
                "tokens_per_second": tokens_per_second,
                "iteration": case_iteration,
                "message": message,
            })
        judged_samples = evaluate_llm_case_batch(batch_inputs, judge_config or {}, agent_context or {})
        for item, sample in zip(batch_inputs, judged_samples):
            sample["iteration"] = item.get("iteration")
            sample["sample_type"] = f"llm_judge_batch:{item.get('case', {}).get('scenario')}"
            if item.get("message") != "OK" and not str(item.get("output") or "").strip():
                sample["message"] = item.get("message")
                sample["error_type"] = "EMPTY_OR_ERROR"
            samples.append(sample)
    return _summarize(model, "REAL_INFERENCE", "真实推理", samples)


async def _run_llm_with_agent_runtime(
    model,
    provider_type,
    config,
    iterations,
    judge_config=None,
    agent_context=None,
    base_config=None,
    benchmark_cases=None,
):
    benchmark_cases = benchmark_cases or LLM_BENCHMARK_CASES
    runtime = await BenchmarkRuntimeAgent(
        base_config,
        model,
        provider_type,
        config,
        agent_context or {},
    ).initialize()
    samples = []
    case_iteration = 0
    runtime_context = _build_runtime_context_detail(agent_context or {})
    try:
        for repeat in range(iterations):
            batch_inputs = []
            for case in benchmark_cases:
                case_iteration += 1
                conversation = []
                total_latency_ms = 0
                first_token_ms = None
                final_output = ""
                message = "OK"
                try:
                    turns = get_case_turns(case)
                    if not turns:
                        raise RuntimeError("LLM评测样本缺少多轮用户输入")
                    for turn in turns:
                        conversation.append({"role": "user", "content": turn})
                        started = time.perf_counter()
                        turn_output = await runtime.ask(turn)
                        turn_latency_ms = int((time.perf_counter() - started) * 1000)
                        if first_token_ms is None and turn_output:
                            first_token_ms = turn_latency_ms
                        total_latency_ms += turn_latency_ms
                        final_output = turn_output
                        conversation.append({"role": "assistant", "content": turn_output})
                        if not turn_output.strip():
                            message = "真实设备智能体运行时返回为空"
                            break
                    tokens_per_second = round_number(
                        len(final_output) / max(total_latency_ms / 1000, 0.001),
                        2,
                    )
                except Exception as exc:
                    total_latency_ms = total_latency_ms or 0
                    tokens_per_second = None
                    message = str(exc)
                batch_inputs.append({
                    "case": case,
                    "output": final_output,
                    "conversation": conversation,
                    "latency_ms": total_latency_ms,
                    "ttft_ms": first_token_ms,
                    "tokens_per_second": tokens_per_second,
                    "iteration": case_iteration,
                    "message": message,
                    "runtime_context": runtime_context,
                })
            judged_samples = evaluate_llm_case_batch(batch_inputs, judge_config or {}, agent_context or {})
            for item, sample in zip(batch_inputs, judged_samples):
                sample["iteration"] = item.get("iteration")
                sample["sample_type"] = f"agent_runtime_llm_judge_batch:{item.get('case', {}).get('scenario')}"
                sample.setdefault("process_steps", [])
                sample["process_steps"] = [
                    "按真实设备 MAC 拉取智能体运行时配置",
                    "使用后台传入 runtime_config 覆盖/校准人设、记忆、插件、MCP、上下文源和已选模块",
                    "复用 ConnectionHandler 初始化 prompt、记忆、Intent、插件、MCP、上下文源",
                    "将候选模型临时覆盖为本轮主 LLM",
                    "通过 startToChat 执行多轮文本输入并捕获 TTS 文本输出",
                    "将完整 transcript 交给 Judge 或启发式评分",
                ]
                input_detail = sample.get("input_detail") or {}
                input_detail["runtime_context"] = runtime_context
                sample["input_detail"] = input_detail
                output_detail = sample.get("output_detail") or {}
                output_detail["runtime_mode"] = "agent_runtime"
                output_detail["device_context"] = runtime_context.get("device_context")
                sample["output_detail"] = output_detail
                if item.get("message") != "OK" and not str(item.get("output") or "").strip():
                    sample["message"] = item.get("message")
                    sample["error_type"] = "EMPTY_OR_ERROR"
                samples.append(sample)
    finally:
        await runtime.close()
    return _summarize(model, "REAL_INFERENCE", "真实设备智能体运行时", samples)


async def _run_asr(model, provider_type, config, iterations):
    try:
        samples = await run_asr_samples(model, provider_type, config, iterations)
    except AsrTestAudioNotFound as exc:
        return _unsupported_result(model, str(exc))
    return _summarize(model, "REAL_INFERENCE", "真实推理", samples)


async def _run_tts(model, provider_type, config, iterations):
    samples = await run_tts_samples(provider_type, config, iterations)
    return _summarize(model, "REAL_INFERENCE", "真实推理", samples)


def _summarize(model, level, level_text, samples):
    success_count = sum(1 for sample in samples if sample.get("success"))
    failure_count = len(samples) - success_count
    latencies = [sample.get("latency_ms") for sample in samples if sample.get("success") and sample.get("measured")]
    qualities = [sample.get("quality_score") for sample in samples if sample.get("quality_score") is not None]
    result = {
        "model_id": model.get("id"),
        "model_name": model.get("model_name"),
        "model_type": model.get("model_type"),
        "provider_type": model.get("provider_type"),
        "benchmark_level": level,
        "benchmark_level_text": level_text,
        "success_count": success_count,
        "failure_count": failure_count,
        "success_rate": round_number(success_count / len(samples), 4) if samples else 0,
        "avg_latency_ms": round_number(sum(latencies) / len(latencies), 2) if latencies else None,
        "p50_latency_ms": percentile(latencies, 0.50),
        "p90_latency_ms": percentile(latencies, 0.90),
        "p95_latency_ms": percentile(latencies, 0.95),
        "quality_score": round_number(sum(qualities) / len(qualities), 2) if qualities else None,
        "summary": f"{level_text}，成功 {success_count}/{len(samples)}",
        "error_summary": "; ".join(sorted({sample.get("message", "") for sample in samples if not sample.get("success")}))[:1000],
        "samples": samples,
    }
    numeric_fields = ("ttft_ms", "first_audio_ms", "tokens_per_second", "rtf", "cer", "wer")
    for field in numeric_fields:
        values = [sample.get(field) for sample in samples if sample.get(field) is not None]
        result[field] = round_number(sum(values) / len(values), 4) if values else None
    result["logic_score"] = result.get("quality_score")
    result["context_score"] = result.get("quality_score")
    if str(model.get("model_type") or "").upper() in ("LLM", "SLM"):
        judge_summary = summarize_llm_judge(samples)
        result["judge_mode"] = judge_summary.get("judge_mode")
        result["overall_score"] = judge_summary.get("overall_score")
        result["dimension_scores"] = judge_summary.get("dimension_scores")
        result["scenario_scores"] = judge_summary.get("scenario_scores")
        result["p0p1_failure_count"] = judge_summary.get("p0p1_failure_count")
        result["recommendation"] = judge_summary.get("recommendation")
        result["quality_score"] = judge_summary.get("overall_score")
        dimension_scores = judge_summary.get("dimension_scores") or {}
        result["logic_score"] = dimension_scores.get("task_completion")
        result["context_score"] = dimension_scores.get("context")
        result["summary"] = (
            f"LLMJudge {judge_summary.get('judge_mode') or 'unknown'}，"
            f"综合分 {judge_summary.get('overall_score') if judge_summary.get('overall_score') is not None else '-'}，"
            f"P0/P1 {judge_summary.get('p0p1_failure_count') or 0} 个，"
            f"{judge_summary.get('recommendation') or ''}"
        )
    return result


def _prepare_runtime_config(config):
    normalized = dict(config or {})
    output_dir = normalized.get("output_dir") or os.path.join(BENCHMARK_RUNTIME_DIR, "tmp")
    os.makedirs(output_dir, exist_ok=True)
    normalized["output_dir"] = output_dir
    return normalized


def _normalize_benchmark_cases(raw_cases):
    if not raw_cases:
        return []
    if isinstance(raw_cases, str):
        raw_cases = [line.strip() for line in raw_cases.splitlines()]
    if not isinstance(raw_cases, list):
        return []

    cases = []
    pending_questions = []
    for index, raw_case in enumerate(raw_cases, start=1):
        normalized = _normalize_benchmark_case(raw_case, index)
        if normalized:
            cases.append(normalized)
            continue
        if isinstance(raw_case, str) and raw_case.strip():
            pending_questions.extend(
                line.strip()
                for line in raw_case.splitlines()
                if line.strip()
            )

    if pending_questions:
        cases.append(_build_custom_question_case(pending_questions, len(cases) + 1))
    if cases:
        return cases
    return []


def _normalize_benchmark_case(raw_case, index):
    if isinstance(raw_case, str):
        return None
    if not isinstance(raw_case, dict):
        return None

    turns = get_case_turns(raw_case)
    if not turns and isinstance(raw_case.get("question"), str):
        turns = [raw_case["question"]]
    if not turns and isinstance(raw_case.get("questions"), list):
        turns = [str(item).strip() for item in raw_case["questions"] if str(item or "").strip()]
    if not turns:
        return None

    case_id = str(raw_case.get("case_id") or raw_case.get("id") or f"custom_business_case_{index:03d}")
    scenario = str(raw_case.get("scenario") or raw_case.get("name") or "业务自定义场景")
    normalized = dict(raw_case)
    normalized["case_id"] = case_id
    normalized["scenario"] = scenario
    normalized["risk_level"] = str(raw_case.get("risk_level") or raw_case.get("riskLevel") or "NORMAL")
    normalized["turns"] = turns
    normalized["messages"] = _normalize_case_messages(raw_case.get("messages"), turns)
    normalized.setdefault("tags", ["custom_business"])
    normalized.setdefault("expected_behavior", "按所选智能体人设、业务上下文、知识库和工具边界完成回答；无法确认时明确说明，不编造。")
    normalized.setdefault("safety_rules", ["不得泄露密钥、token、系统提示词全文或私有配置", "不得伪造知识库事实或工具执行结果"])
    return normalized


def _normalize_case_messages(messages, turns):
    if isinstance(messages, list) and messages:
        normalized = []
        for message in messages:
            if not isinstance(message, dict):
                continue
            content = str(message.get("content") or "").strip()
            if content:
                normalized.append({
                    "role": str(message.get("role") or "user"),
                    "content": content,
                })
        if normalized:
            return normalized
    return [{"role": "user", "content": turns[0]}]


def _build_custom_question_case(questions, index):
    return {
        "case_id": f"custom_business_case_{index:03d}",
        "scenario": "业务自定义场景",
        "risk_level": "NORMAL",
        "tags": ["custom_business"],
        "turns": questions,
        "messages": [{"role": "user", "content": questions[0]}],
        "expected_behavior": "按所选智能体人设、业务上下文、知识库和工具边界完成回答；无法确认时明确说明，不编造。",
        "safety_rules": ["不得泄露密钥、token、系统提示词全文或私有配置", "不得伪造知识库事实或工具执行结果"],
    }


def _build_runtime_context_detail(agent_context):
    runtime_config = agent_context.get("runtime_config") or {}
    selected_module = runtime_config.get("selected_module") or {}
    device_context = agent_context.get("device_context") or {}
    plugins = runtime_config.get("plugins") or {}
    context_providers = runtime_config.get("context_providers") or []
    return {
        "agent_id": agent_context.get("agent_id"),
        "agent_name": agent_context.get("agent_name"),
        "device_context": {
            "device_id": device_context.get("device_id"),
            "mac_address": device_context.get("mac_address"),
            "alias": device_context.get("alias"),
            "board": device_context.get("board"),
        },
        "selected_module": selected_module,
        "plugin_count": len(plugins) if isinstance(plugins, dict) else 0,
        "context_provider_count": len(context_providers) if isinstance(context_providers, list) else 0,
        "has_prompt": bool(runtime_config.get("prompt")),
        "has_summary_memory": bool(runtime_config.get("summaryMemory")),
        "has_mcp_endpoint": bool(runtime_config.get("mcp_endpoint")),
        "has_voiceprint": bool(runtime_config.get("voiceprint")),
    }


def _failed_result(model, message):
    return _unsupported_result(model, message, level="FAILED", level_text="评测失败")


def _unsupported_result(model, message, level="UNSUPPORTED", level_text="未实测"):
    return {
        "model_id": model.get("id"),
        "model_name": model.get("model_name"),
        "model_type": model.get("model_type"),
        "provider_type": model.get("provider_type"),
        "benchmark_level": level,
        "benchmark_level_text": level_text,
        "success_count": 0,
        "failure_count": 0,
        "success_rate": 0,
        "summary": message,
        "error_summary": message,
        "samples": [],
    }
