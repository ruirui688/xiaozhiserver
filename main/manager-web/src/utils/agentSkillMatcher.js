const MATCH_IGNORED_CHARACTERS = /[\s\u3000.,!?;:'"()[\]{}<>，。！？；：、“”‘’（）【】《》…·—_-]+/g;

export function normalizeSkillText(value) {
  return String(value || "")
    .toLocaleLowerCase()
    .replace(MATCH_IGNORED_CHARACTERS, "");
}

function compareRuleMatch(left, right) {
  return (
    right.keywordCount - left.keywordCount ||
    right.termLength - left.termLength ||
    Number(right.ruleType === "phrase") - Number(left.ruleType === "phrase") ||
    left.ruleIndex - right.ruleIndex
  );
}

export function matchSkillRule(skill, input) {
  const normalizedInput = normalizeSkillText(input);
  if (!normalizedInput || !skill || skill.enabled === false) return null;

  const matches = (Array.isArray(skill.triggerRules) ? skill.triggerRules : [])
    .map((rule, ruleIndex) => {
      const rawTerms = Array.isArray(rule && rule.terms) ? rule.terms : [];
      const terms = rawTerms.map(normalizeSkillText).filter(Boolean);
      if (rule.type === "phrase") {
        if (terms.length !== 1 || !normalizedInput.includes(terms[0])) return null;
      } else if (rule.type === "all_keywords") {
        if (terms.length < 2 || !terms.every((term) => normalizedInput.includes(term))) return null;
      } else {
        return null;
      }
      return {
        rule,
        ruleIndex,
        ruleType: rule.type,
        terms: rawTerms.map((term) => String(term || "").trim()),
        keywordCount: terms.length,
        termLength: terms.reduce((total, term) => total + term.length, 0),
      };
    })
    .filter(Boolean)
    .sort(compareRuleMatch);

  return matches[0] || null;
}

function stableId(value) {
  if (value === null || value === undefined || value === "") return "~";
  return String(value);
}

export function compareSkillMatches(left, right) {
  return (
    Number(right.skill.priority || 0) - Number(left.skill.priority || 0) ||
    right.match.keywordCount - left.match.keywordCount ||
    right.match.termLength - left.match.termLength ||
    Number(right.match.ruleType === "phrase") - Number(left.match.ruleType === "phrase") ||
    Number(left.skill.sort || 0) - Number(right.skill.sort || 0) ||
    stableId(left.skill.id).localeCompare(stableId(right.skill.id), "zh-CN", { numeric: true })
  );
}

export function findMatchedSkill(skills, input) {
  const matches = (Array.isArray(skills) ? skills : [])
    .map((skill) => ({ skill, match: matchSkillRule(skill, input) }))
    .filter((item) => item.match)
    .sort(compareSkillMatches);
  return matches[0] || null;
}

export function describeSkillMatch(result) {
  if (!result || !result.match) return "";
  const separator = result.match.ruleType === "all_keywords" ? " + " : "";
  return result.match.terms.join(separator);
}
