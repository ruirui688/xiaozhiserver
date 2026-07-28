"""Validated connection-local registry for agent semantic skills."""

from typing import Any, Tuple

from core.semantic.normalizer import TextNormalizer
from core.semantic.skill_matcher import (
    SemanticSkill,
    SkillMatch,
    SkillMatcher,
    SkillRule,
)


class SkillRegistry:
    """Compile manager-api skill configuration into safe runtime objects."""

    MAX_SKILLS = 50
    MAX_RULES_PER_SKILL = 20
    MIN_TERM_CHARS = 2
    MAX_TERM_CHARS = 100
    MAX_RESPONSE_CHARS = 3000

    def __init__(
        self,
        configured_skills=None,
        normalizer: TextNormalizer | None = None,
    ):
        self.normalizer = normalizer or TextNormalizer()
        self.matcher = SkillMatcher(self.normalizer)
        self._skills: Tuple[SemanticSkill, ...] = ()
        self.replace(configured_skills)

    @property
    def skills(self) -> Tuple[SemanticSkill, ...]:
        return self._skills

    def __len__(self) -> int:
        return len(self._skills)

    def replace(self, configured_skills) -> None:
        if not isinstance(configured_skills, list):
            self._skills = ()
            return
        compiled = []
        for index, item in enumerate(configured_skills[: self.MAX_SKILLS]):
            skill = self._compile_skill(item, index)
            if skill is not None:
                compiled.append(skill)
        self._skills = tuple(compiled)

    def match(self, text: str) -> SkillMatch | None:
        return self.matcher.match(text, self._skills)

    def _compile_skill(self, item, index: int) -> SemanticSkill | None:
        if not isinstance(item, dict) or item.get("enabled") is False:
            return None
        name_value = item.get("name")
        response_value = item.get("response_text")
        if not isinstance(name_value, str) or not isinstance(response_value, str):
            return None
        name = name_value.strip()
        response_text = response_value.strip()
        if (
            not name
            or not response_text
            or len(response_text) > self.MAX_RESPONSE_CHARS
        ):
            return None

        rules = []
        configured_rules = item.get("trigger_rules")
        if not isinstance(configured_rules, list):
            return None
        for rule in configured_rules[: self.MAX_RULES_PER_SKILL]:
            compiled_rule = self._compile_rule(rule)
            if compiled_rule is not None:
                rules.append(compiled_rule)
        if not rules:
            return None

        return SemanticSkill(
            skill_id=item.get("id", f"config-{index}"),
            name=name,
            trigger_rules=tuple(rules),
            response_text=response_text,
            priority=self._safe_int(item.get("priority")),
            sort=self._safe_int(item.get("sort")),
        )

    def _compile_rule(self, rule) -> SkillRule | None:
        if not isinstance(rule, dict):
            return None
        rule_type = rule.get("type")
        if rule_type not in {"phrase", "all_keywords"}:
            return None
        terms = self._clean_terms(rule.get("terms"))
        if rule_type == "phrase" and len(terms) != 1:
            return None
        if rule_type == "all_keywords" and not 2 <= len(terms) <= 8:
            return None
        return SkillRule(
            rule_type=rule_type,
            terms=tuple(term for term, _normalized in terms),
            normalized_terms=tuple(normalized for _term, normalized in terms),
        )

    def _clean_terms(self, terms) -> list[tuple[str, str]]:
        if not isinstance(terms, list):
            return []
        cleaned = []
        seen = set()
        for value in terms:
            if not isinstance(value, str):
                continue
            term = value.strip()
            normalized = self.matcher.normalize_text(term)
            if (
                not self.MIN_TERM_CHARS <= len(term) <= self.MAX_TERM_CHARS
                or not normalized
                or normalized in seen
            ):
                continue
            seen.add(normalized)
            cleaned.append((term, normalized))
        return cleaned

    @staticmethod
    def _safe_int(value: Any) -> int:
        try:
            return int(value or 0)
        except (TypeError, ValueError):
            return 0
