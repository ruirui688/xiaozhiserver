# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Identity

This is a **heavily customized fork** of `xiaozhi-esp32-server` — an end-to-end voice AI platform for ESP32 smart hardware. Four modules work together:

- **Python real-time service** (207 files) — WebSocket voice pipeline: VAD → ASR → SemanticControl → LLM → TTS
- **Java management API** (372 files) — Spring Boot 3.4, model configs, benchmark orchestration, auth
- **Vue 2 Web console** (23 views) — Element UI admin panel
- **Vue 3 uni-app mobile** — cross-platform (H5/iOS/Android/WeChat)

Production deployment uses Aliyun ACR container images; no source builds on production.

## How to Work Here

1. **Read AGENTS.md first** — it is the authoritative rules document. This CLAUDE.md is supplementary.
2. **Run the startup protocol** before touching any file:
   ```bash
   git status --short --branch
   git remote -v
   ```
3. **Route your task** using AGENTS.md §2 before reading docs or code
4. **Identify file ownership** — don't touch files outside your task scope
5. **Verify** with the appropriate module command before declaring done

## Python Constraints

`main/xiaozhi-server` MUST use Conda env `xiaozhi-esp32-server`. Never use system `python`/`python3`/`pip`/`pytest`.

```bash
conda run -n xiaozhi-esp32-server python -m compileall .
conda run -n xiaozhi-esp32-server pytest
```

If the Conda env is unavailable, state "not verified in project Conda runtime" — never substitute system Python.

## Code Modification Principles

- **Don't grow the giants.** These files are already too large and should NOT be expanded:
  - `main/xiaozhi-server/core/connection.py` (2060 lines)
  - `main/manager-api/.../ModelBenchmarkServiceImpl.java`
  - `main/manager-web/src/views/ModelConfig.vue`
  - `main/manager-api/.../ModelAssistantServiceImpl.java`
  - Instead, add new logic in dedicated helper/module/component files

- **Don't mix concerns.** Model benchmark UI goes in `ModelBenchmarkDialog.vue`, not `ModelConfig.vue`. ASR/TTS/VLLM benchmark logic goes in `core/benchmark/`, not `runner.py` alone.

- **Don't commit drafts.** Delete placeholder providers, `raise NotImplementedError`, `# TODO: call real API`, and unregistered DB migrations before committing.

- **Match surrounding code style.** Copy the comment density, naming conventions, and indentation of adjacent code.

## Key Architectural Concepts

### Voice Pipeline (per connection)
```
Opus audio frames → VAD segmentation → ASR text → SemanticCenter (deterministic pre-check)
  → Intent/LLM chat + tool calls → TTS synthesis → audio back to device
```

### Three tool invocation modes
1. **SemanticControl** — Python Router + PolicyEngine for high-confidence commands (stop, game exit)
2. **function_call** — Native LLM tool calling (OpenAI-compatible tools field)
3. **intent_llm** — Separate intent LLM (fallback when main LLM tool calling is unstable)

### Three MCP chains
1. **External MCP Endpoint** — tools from backend-configured endpoints
2. **Server-side MCP** — tools from local MCP server config
3. **Device-side MCP** — tools declared by ESP32 device (features.mcp=true)

### Configuration flow
```
manager-web/manager-mobile → manager-api → MySQL/Redis
  → xiaozhi-server pulls per-device config via HTTP /config/*
  → initializes VAD, ASR, LLM, TTS, Memory, Intent, Plugins, MCP per connection
```

## What Claude Must Never Do

- ❌ Run `git reset --hard`, `git clean -fd`, `git stash`, `git rebase` without explicit user authorization
- ❌ Push to `upstream` (official repo) — `upstream` is fetch-only
- ❌ Commit real API keys, tokens, passwords, or production configs
- ❌ Revert/overwrite/format files unrelated to the current task
- ❌ Remove untracked files unless confirmed to be drafts/cache/runtime artifacts
- ❌ Start long-running services or call external model APIs as default verification
- ❌ Create placeholder migrations, empty providers, fake tests, or un-runnable draft files
- ❌ Delete tests, downgrade verification, or silently swallow errors to pass a build

## Verification Priority

Use commands that finish on their own — don't start long-running services unless the user explicitly asks.

| What changed | Minimum verification |
|---|---|
| Python files | `cd main/xiaozhi-server && conda run -n xiaozhi-esp32-server python -m compileall .` |
| Python + tests exist | `conda run -n xiaozhi-esp32-server pytest` |
| Java files | `cd main/manager-api && mvn test` |
| Web files | `cd main/manager-web && npm run build` |
| Mobile files | `cd main/manager-mobile && pnpm build:h5` |
| Shell scripts | `bash -n scripts/dev scripts/start-dev.sh` |
| DB migrations | Check `db.changelog-master.yaml` is updated |
| Docs | Check relative links resolve correctly |

## Output Format

After completing work, report:
1. Files changed (with paths)
2. Verifications performed (and their results)
3. Verifications not performed and why
4. Any pre-existing uncommitted changes or residual risks found
5. Whether drafts, untracked runtime artifacts, or sensitive info were cleaned up
6. If multi-agent: each sub-agent's scope and the final integration result

Be concise. Don't dump irrelevant docs or command output.
