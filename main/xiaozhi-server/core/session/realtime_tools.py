"""Trusted tool continuation rules shared by native Realtime adapters."""

import asyncio

from core.providers.tools.follow_up_policy import resolve_tool_follow_up_policy
from core.session.prompt_context import FINAL_ANSWER_INSTRUCTION, with_music_library


class RealtimeToolPolicy:
    def __init__(self, conn):
        self.conn = conn
        self.reset()

    def reset(self):
        self.allow_tools = True
        self.prompts = []
        self.rounds = 0

    def accept(self, results, *, count_round=True):
        policy = resolve_tool_follow_up_policy(results)
        if count_round:
            self.rounds += 1
        self.allow_tools = self.allow_tools and policy.allow_tools and self.rounds < 5
        if policy.system_prompt and policy.system_prompt not in self.prompts:
            self.prompts.append(policy.system_prompt)
        if self.rounds >= 5 and FINAL_ANSWER_INSTRUCTION not in self.prompts:
            self.prompts.append(FINAL_ANSWER_INSTRUCTION)

    def tools(self):
        handler = getattr(self.conn, "func_handler", None)
        return handler.get_functions() if handler and self.allow_tools else []

    def permits(self, name):
        handler = getattr(self.conn, "func_handler", None)
        return bool(self.allow_tools and handler and handler.has_tool(name))

    def prompt(self, base, tools):
        from core.game.interactions import build_active_interaction_prompt

        manager = getattr(self.conn, "prompt_manager", None)
        if manager is not None:
            base = manager.replace_available_tools_prompt(base, tools)
        activity = build_active_interaction_prompt(self.conn)
        sections = [base, activity, *self.prompts]
        prompt = "\n\n".join(section for section in sections if section)
        messages = with_music_library(self.conn, [{"role": "system", "content": prompt}], tools)
        return "\n\n".join(message["content"] for message in messages)


def _generation(session):
    return getattr(session, "_cancel_generation", getattr(session, "_generation", 0))


async def configure_realtime_session(session, prompt, tools, config_override=None):
    """Keep a sent update alive until its ACK, even when its caller is aborted.

    Both protocols acknowledge updates without a request correlation ID. Releasing
    an update's waiter on cancellation would let its late ACK confirm the next
    turn's instructions. The small background operation owns that ACK; callers
    can still cancel promptly without blocking the transport reader.
    """
    if not hasattr(session, "_prompt_update_lock"):
        session._prompt_update_lock = asyncio.Lock()
        session._prompt_update_tasks = set()
    generation, transport = _generation(session), session.transport

    async def update():
        async with session._prompt_update_lock:
            if (generation != _generation(session) or transport is not session.transport
                    or getattr(session, "_closed", False)):
                return
            if config_override is None:
                await transport.configure(prompt, tools)
            else:
                await transport.configure(prompt, tools, config_override)

    task = asyncio.create_task(update(), name="realtime-acknowledged-prompt-update")
    session._prompt_update_tasks.add(task)

    def completed(done):
        session._prompt_update_tasks.discard(done)
        # An aborted caller no longer retrieves an eventual network exception.
        if not done.cancelled():
            done.exception()

    task.add_done_callback(completed)
    await asyncio.shield(task)


async def cancel_realtime_updates(session):
    tasks = list(getattr(session, "_prompt_update_tasks", ()))
    for task in tasks:
        task.cancel()
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)


async def await_realtime_controls(session):
    """Do not send new user input before pending restoration is acknowledged."""
    for name in ("_restore_task", "_session_maintenance_task", "_policy_restore_task"):
        task = getattr(session, name, None)
        if task is not None and task is not asyncio.current_task() and not task.done():
            await asyncio.shield(task)
