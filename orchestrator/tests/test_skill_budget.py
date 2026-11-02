from __future__ import annotations

from pathlib import Path

from check_skill_budget import BODY_TOKEN_BUDGET, check_skill, main

EXAMPLE = Path(__file__).resolve().parents[2] / "docs" / "examples" / "SKILL.example.md"


def test_example_skill_passes_and_is_well_under_budget():
    report = check_skill(EXAMPLE, expected_id="authentication.jwt")
    assert report.ok, report.errors
    assert 0 < report.tokens <= BODY_TOKEN_BUDGET


def test_name_must_match_module_id():
    report = check_skill(EXAMPLE, expected_id="authentication.oauth2")
    assert any("must equal module id" in e for e in report.errors)


def _write(tmp_path, text):
    p = tmp_path / "SKILL.md"
    p.write_text(text, encoding="utf-8")
    return p


def test_missing_frontmatter(tmp_path):
    report = check_skill(_write(tmp_path, "# Title\n\n## Provides\n"))
    assert any("frontmatter" in e for e in report.errors)


def test_over_budget_body_fails(tmp_path):
    text = EXAMPLE.read_text(encoding="utf-8") + "\n" + ("filler words go here. " * 120)
    report = check_skill(_write(tmp_path, text))
    assert any("budget" in e for e in report.errors)


def test_missing_and_out_of_order_sections(tmp_path):
    text = EXAMPLE.read_text(encoding="utf-8").replace("## Conflicts", "## Notes")
    report = check_skill(_write(tmp_path, text))
    assert any("'## Conflicts'" in e for e in report.errors)
    swapped = EXAMPLE.read_text(encoding="utf-8").replace("## Provides", "## TMP").replace("## Requires", "## Provides").replace("## TMP", "## Requires")
    report = check_skill(_write(tmp_path, swapped))
    assert any("out of order" in e for e in report.errors)


def test_do_not_line_required(tmp_path):
    text = EXAMPLE.read_text(encoding="utf-8").replace("Do NOT", "Never")
    report = check_skill(_write(tmp_path, text))
    assert any("Do NOT" in e for e in report.errors)


def test_bad_description(tmp_path):
    text = EXAMPLE.read_text(encoding="utf-8").replace("Use when", "Use <when>")
    report = check_skill(_write(tmp_path, text))
    assert any("angle brackets" in e for e in report.errors)


def test_cli_exit_codes(tmp_path):
    assert main([str(EXAMPLE)]) == 0
    bad = _write(tmp_path, "no frontmatter")
    assert main([str(bad)]) == 1
    assert main([]) == 2
