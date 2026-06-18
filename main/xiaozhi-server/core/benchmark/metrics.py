import math
import re
from difflib import SequenceMatcher


def percentile(values, ratio):
    values = sorted([v for v in values if v is not None and v >= 0])
    if not values:
        return None
    index = max(0, min(len(values) - 1, math.ceil(len(values) * ratio) - 1))
    return values[index]


def round_number(value, digits=2):
    if value is None:
        return None
    return round(float(value), digits)


def char_error_rate(reference, hypothesis):
    reference = normalize_text(reference)
    hypothesis = normalize_text(hypothesis)
    if not reference:
        return None
    return round_number(1 - SequenceMatcher(None, reference, hypothesis).ratio(), 4)


def word_error_rate(reference, hypothesis):
    ref_words = normalize_text(reference).split()
    hyp_words = normalize_text(hypothesis).split()
    if not ref_words:
        return None
    return round_number(1 - SequenceMatcher(None, ref_words, hyp_words).ratio(), 4)


def normalize_text(text):
    text = str(text or "").strip().lower()
    text = re.sub(r"[\s，。！？、,.!?;；:：\"'“”‘’（）()【】\[\]]+", " ", text)
    return text.strip()


def rule_quality_score(output, required_terms=None, forbidden_terms=None):
    output = str(output or "").strip()
    if not output:
        return 0.0
    score = 0.45
    if 2 <= len(output) <= 500:
        score += 0.15
    if required_terms and all(term in output for term in required_terms):
        score += 0.25
    elif not required_terms:
        score += 0.15
    if forbidden_terms and any(term in output for term in forbidden_terms):
        score -= 0.2
    if output.endswith(("。", "！", "？", ".", "!", "?")):
        score += 0.05
    if "\n" not in output and len(output) <= 120:
        score += 0.05
    return min(1.0, round_number(score, 2))
