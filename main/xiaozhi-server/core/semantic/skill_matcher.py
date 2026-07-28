"""Deterministic matching for agent-scoped fixed-response skills."""

from dataclasses import dataclass
from typing import Any, Iterable, Optional, Tuple

from core.semantic.normalizer import TextNormalizer


@dataclass(frozen=True)
class SkillRule:
    rule_type: str
    terms: Tuple[str, ...]
    normalized_terms: Tuple[str, ...]


@dataclass(frozen=True)
class SemanticSkill:
    skill_id: Any
    name: str
    trigger_rules: Tuple[SkillRule, ...]
    response_text: str
    priority: int = 0
    sort: int = 0


@dataclass(frozen=True)
class SkillMatch:
    skill: SemanticSkill
    rule: SkillRule


class SkillMatcher:
    """Match phrase or all-keyword rules and resolve conflicts stably."""

    def __init__(self, normalizer: Optional[TextNormalizer] = None):
        self.normalizer = normalizer or TextNormalizer()

    def normalize_text(self, text: str) -> str:
        without_address = self.normalizer.strip_leading_wake_address(text)
        return self.normalizer.route_key(without_address)

    def match(
        self, text: str, skills: Iterable[SemanticSkill]
    ) -> Optional[SkillMatch]:
        normalized_text = self.normalize_text(text)
        if not normalized_text:
            return None

        candidates = []
        for skill in skills:
            for rule in skill.trigger_rules:
                if self._rule_matches(normalized_text, rule):
                    candidates.append(SkillMatch(skill=skill, rule=rule))

        if not candidates:
            return None
        return min(candidates, key=self._sort_key)

    @staticmethod
    def _rule_matches(normalized_text: str, rule: SkillRule) -> bool:
        if rule.rule_type == "phrase":
            return bool(
                len(rule.normalized_terms) == 1
                and rule.normalized_terms[0] in normalized_text
            )
        if rule.rule_type == "all_keywords":
            return all(term in normalized_text for term in rule.normalized_terms)
        return False

    @staticmethod
    def _sort_key(match: SkillMatch):
        rule = match.rule
        skill = match.skill
        return (
            -skill.priority,
            -len(rule.normalized_terms),
            -sum(len(term) for term in rule.normalized_terms),
            0 if rule.rule_type == "phrase" else 1,
            skill.sort,
            SkillMatcher._stable_id_key(skill.skill_id),
        )

    @staticmethod
    def _stable_id_key(skill_id: Any):
        if isinstance(skill_id, int):
            return (0, skill_id)
        value = str(skill_id)
        try:
            return (0, int(value))
        except (TypeError, ValueError):
            return (1, value)
