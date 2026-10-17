"""Conservative, provider-independent search evidence validation."""
import re
from datetime import date, datetime, timedelta, timezone
from html import unescape
from urllib.parse import urlsplit, urlunsplit

TIME_RANGES = {"day": 1, "week": 7, "month": 30, "year": 365}


def parse_date(value):
    # Only structured provider dates; never infer freshness from title/body.
    text = str(value or "")
    if "T" in text:
        try:
            timestamp = datetime.fromisoformat(text.replace("Z", "+00:00"))
            if timestamp.tzinfo:
                return timestamp.astimezone(timezone(timedelta(hours=8))).date()
            # A timestamp without a zone does not establish a Beijing date.
            return None
        except ValueError:
            return None
    match = re.match(r"^(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})(?:日|T|\b)", str(value or ""))
    if match:
        try:
            return date(*map(int, match.groups()))
        except ValueError:
            pass
    return None


def clean_text(value):
    text = str(value or "")
    text = re.sub(r"<(script|style)\b[^>]*>.*?</\1>", "", text, flags=re.I | re.S)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    return " ".join(unescape(text).split())


def filter_evidence(items, time_range, current_time):
    accepted, seen = [], set()
    excluded = {"empty": 0, "duplicate": 0, "stale": 0, "undated": 0}
    today = parse_date(current_time)
    cutoff = today - timedelta(days=TIME_RANGES[time_range] - 1) if today and time_range else None
    for raw in items:
        if not isinstance(raw, dict):
            excluded["empty"] += 1
            continue
        item = dict(raw)
        for key in ("summary", "snippet", "content", "description", "raw_content", "rawContent"):
            item[key] = clean_text(item.get(key))
        evidence = max((item[k] for k in ("summary", "snippet", "content", "description", "raw_content", "rawContent")), key=len)
        if len(re.sub(r"[\W_]", "", evidence)) < 12:
            excluded["empty"] += 1
            continue
        published = next((parse_date(item[k]) for k in ("date", "published_date", "publishedAt", "publishedDate", "time") if parse_date(item.get(k))), None)
        if time_range and (not published or not today):
            excluded["undated"] += 1
            continue
        if cutoff and not cutoff <= published <= today:
            excluded["stale"] += 1
            continue
        link = next((str(item[k]).strip() for k in ("link", "url", "href") if item.get(k)), "")
        try:
            parts = urlsplit(link)
            link = urlunsplit((parts.scheme.lower(), parts.netloc.lower(), parts.path.rstrip('/'), parts.query, ''))
        except ValueError:
            pass
        identity = link or evidence
        if identity in seen or evidence in seen:
            excluded["duplicate"] += 1
            continue
        seen.update((identity, evidence))
        item["_date_verified"] = bool(published)
        accepted.append(item)
    return accepted, excluded
