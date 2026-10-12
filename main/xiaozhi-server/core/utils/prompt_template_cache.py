"""Read prompt templates with a versioned, process-local file cache."""

import os


def read_prompt_template(path, cache, cache_type):
    """Stat on refresh; reread only when the file (or an atomic replacement) changes."""
    path = os.path.abspath(os.fspath(path))
    stat = os.stat(path)
    signature = (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)
    key = f"prompt_template:{path}"
    cached = cache.get(cache_type, key)
    if isinstance(cached, dict) and cached.get("signature") == signature:
        return cached["content"]
    with open(path, "r", encoding="utf-8") as source:
        content = source.read()
    cache.set(cache_type, key, {"signature": signature, "content": content})
    return content
