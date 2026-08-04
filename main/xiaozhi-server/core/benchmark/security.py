"""Credential-safe error rendering for benchmark API and sample results."""

import re


def sanitize_benchmark_error(error, limit=500):
    message = str(error or "模型评测执行失败")
    message = re.sub(r"(?i)Bearer\s+[^\s,;}]+", "Bearer ***", message)
    message = re.sub(
        r"(?i)(authorization|api[_-]?key|access[_-]?token|secret)"
        r"(\s*[:=]\s*)(['\"]?)([^\s,;}]+)(['\"]?)",
        r"\1\2***",
        message,
    )
    return message[:limit]
