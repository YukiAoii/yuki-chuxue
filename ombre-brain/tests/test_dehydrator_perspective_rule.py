"""Perspective-preservation prompt regressions for dehydration."""

from dehydrator import _PROMPT_VERSION, _perspective_rule


def test_perspective_rule_guards_reverse_subject_flip():
    rule = _perspective_rule("小明")

    assert _PROMPT_VERSION >= 4
    assert "严禁把「小明」的动作/情绪归给「我」" in rule
    assert "原文省略主语时，先从紧邻上下文判断归属" in rule
    assert "禁止靠猜补一个「我」" in rule
    assert "✗ 错（主语翻转）：我嚎啕大哭后把库建好了" in rule
    assert "✓ 对（归属正确）：小明嚎啕大哭后把库建好了" in rule
