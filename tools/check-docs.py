#!/usr/bin/env python3
"""SimulatorMosire 文档一致性检查（项目维护机械门禁）。

检查目标：
1. 根 `AGENTS.md` 是薄索引，不再把 dated status 叫“最新/唯一入口”；
2. 18 个模块与根 README/AGENTS 一致，每个模块都有局部 `AGENTS.md`；
3. 当前文档与模块 AGENTS 的相对 Markdown 链接存在；
4. `docs/agents/STATUS.md` 存在且有关键字段；
5. 项目设计 / 项目维护分账标志存在；
6. `docs/superpowers/status/*.md` 都标为历史快照；
7. `.agents/skills` 的 active 目录出现在 `docs/agents/SKILLS.md` 目录中；
8. 当前文档不使用 `@` 导入长文。

退出码：0=通过，1=发现不一致，2=用法/IO 错误。
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

POM_NS = "{http://maven.apache.org/POM/4.0.0}"
INLINE_LINK_RE = re.compile(r"!?\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\s*\)")
REFERENCE_LINK_RE = re.compile(r"^\[[^\]\n]+\]:\s*(<[^>\n]+>|\S+)", re.MULTILINE)
FENCE_RE = re.compile(r"^[ \t]*(?P<fence>`{3,}|~{3,})[^\n]*\n.*?^[ \t]*(?P=fence)[ \t]*$", re.MULTILINE | re.DOTALL)
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")
MODULE_COUNT_RE = re.compile(r"(\d+)\s*个\s*`simos-\*`\s*模块")

BASE_DOCS = [
    "AGENTS.md",
    "README.md",
    "docs/AGENTS.md",
    "docs/agents/AGENTS.md",
    "docs/agents/STATUS.md",
    "docs/agents/SKILLS.md",
    "docs/agents/MAINTENANCE_RULINGS.md",
    "docs/agents/WORKFLOW.md",
    "docs/agents/VERIFICATION.md",
    "docs/agents/ENVIRONMENT.md",
    "docs/agents/FRONTEND.md",
    "docs/superpowers/AGENTS.md",
    "docs/superpowers/DESIGN_RULINGS.md",
    ".agents/AGENTS.md",
    ".agents/notes/README.md",
    "tools/AGENTS.md",
    ".superpowers/AGENTS.md",
]

EXIT_PASS = 0
EXIT_FINDINGS = 1
EXIT_USAGE = 2


@dataclass(frozen=True)
class Finding:
    code: str
    path: str
    line: int | None
    message: str

    def render(self) -> str:
        loc = self.path if self.line is None else f"{self.path}:{self.line}"
        return f"FAIL [{self.code}] {loc}: {self.message}"


def strip_code_keep_lines(text: str) -> str:
    def fence_repl(m: re.Match[str]) -> str:
        return "\n" * m.group(0).count("\n")

    def inline_repl(m: re.Match[str]) -> str:
        return " " * len(m.group(0))

    return INLINE_CODE_RE.sub(inline_repl, FENCE_RE.sub(fence_repl, text))


def load_pom(root: Path) -> tuple[list[str], list[Finding]]:
    findings: list[Finding] = []
    pom = root / "pom.xml"
    if not pom.is_file():
        return [], [Finding("POM_MISSING", "pom.xml", None, "根 pom.xml 不存在")]
    try:
        xml = ET.parse(pom)
    except ET.ParseError as exc:
        return [], [Finding("POM_PARSE", "pom.xml", None, f"XML 解析失败: {exc}")]
    modules: list[str] = []
    for node in xml.getroot().findall(f"{POM_NS}modules/{POM_NS}module"):
        name = (node.text or "").strip()
        if name:
            modules.append(name)
    if not modules:
        findings.append(Finding("POM_MODULES", "pom.xml", None, "<modules> 为空"))
    return modules, findings


def current_docs(root: Path, modules: list[str]) -> list[Path]:
    files = [root / rel for rel in BASE_DOCS]
    files.extend(root / module / "AGENTS.md" for module in modules)
    return files


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def check_links(root: Path, files: list[Path]) -> tuple[list[Finding], int]:
    findings: list[Finding] = []
    checked = 0
    root_resolved = root.resolve()
    for path in files:
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        text = strip_code_keep_lines(read_text(path))
        matches: list[tuple[int, str]] = []
        matches.extend((m.start(), m.group(1)) for m in INLINE_LINK_RE.finditer(text))
        matches.extend((m.start(), m.group(1)) for m in REFERENCE_LINK_RE.finditer(text))
        for pos, raw_target in matches:
            target = raw_target.strip()
            if target.startswith("<") and target.endswith(">"):
                target = target[1:-1].strip()
            lower = target.lower()
            if not target or target.startswith("#") or lower.startswith(
                ("http://", "https://", "mailto:", "tel:", "data:", "javascript:")
            ):
                continue
            if "{" in target or "}" in target:
                continue
            target_path = target.split("#", 1)[0].split("?", 1)[0]
            target_path = urllib.parse.unquote(target_path).strip()
            if not target_path:
                continue
            checked += 1
            line = text.count("\n", 0, pos) + 1
            if target_path.startswith("/"):
                findings.append(Finding("LINK_ABSOLUTE", rel, line, f"只允许仓库内相对链接: {target}"))
                continue
            resolved = (path.parent / target_path).resolve()
            try:
                resolved.relative_to(root_resolved)
            except ValueError:
                findings.append(Finding("LINK_ESCAPE", rel, line, f"链接逃出仓库: {target}"))
                continue
            if not resolved.exists():
                findings.append(Finding("DEAD_LINK", rel, line, f"相对链接目标不存在: {target}"))
    return findings, checked


def check_modules(root: Path, modules: list[str]) -> list[Finding]:
    findings: list[Finding] = []
    root_agents = root / "AGENTS.md"
    if not root_agents.is_file():
        return [Finding("FILE_MISSING", "AGENTS.md", None, "根 AGENTS.md 不存在")]
    text = read_text(root_agents)
    for module in modules:
        if module not in text:
            findings.append(Finding("MODULE_MISSING", "AGENTS.md", None, f"缺少模块: {module}"))
        local = root / module / "AGENTS.md"
        if not local.is_file():
            findings.append(Finding("MODULE_AGENTS_MISSING", f"{module}/AGENTS.md", None, "模块局部 AGENTS.md 不存在"))
            continue
        local_text = read_text(local)
        for marker in ("../docs/agents/STATUS.md", "../docs/superpowers/AGENTS.md", "工具归属", "验证"):
            if marker not in local_text:
                findings.append(
                    Finding("MODULE_AGENTS_CONTENT", f"{module}/AGENTS.md", None, f"缺少局部索引标记: {marker}")
                )
    readme = root / "README.md"
    if not readme.is_file():
        findings.append(Finding("FILE_MISSING", "README.md", None, "README.md 不存在"))
    else:
        m = MODULE_COUNT_RE.search(read_text(readme))
        if not m or int(m.group(1)) != len(modules):
            findings.append(
                Finding(
                    "README_MODULE_COUNT",
                    "README.md",
                    None,
                    f"README 的 `simos-*` 模块数未与 pom 对齐（pom={len(modules)}）",
                )
            )
    return findings


def check_status(root: Path) -> list[Finding]:
    status = root / "docs/agents/STATUS.md"
    if not status.is_file():
        return [Finding("STATUS_MISSING", "docs/agents/STATUS.md", None, "唯一实时状态文件不存在")]
    text = read_text(status)
    for marker in ("更新时间", "当前目标", "最近功能门禁", "未验证", "下一步"):
        if marker not in text:
            return [Finding("STATUS_CONTENT", "docs/agents/STATUS.md", None, f"缺少字段: {marker}")]
    return []


def check_split(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    maint = root / "docs/agents/MAINTENANCE_RULINGS.md"
    design = root / "docs/superpowers/DESIGN_RULINGS.md"
    rules = root / "docs/AGENTS.md"
    for path in (maint, design, rules):
        if not path.is_file():
            findings.append(Finding("SPLIT_FILE_MISSING", path.relative_to(root).as_posix(), None, "分账文件不存在"))
            continue
        text = read_text(path)
        if "项目设计" not in text or "项目维护" not in text:
            findings.append(
                Finding("SPLIT_MARKER_MISSING", path.relative_to(root).as_posix(), None, "缺少项目设计/项目维护分账标记")
            )
    if maint.is_file() and "../superpowers/" not in read_text(maint) and "docs/superpowers/" not in read_text(maint):
        findings.append(Finding("SPLIT_DESIGN_LINK", "docs/agents/MAINTENANCE_RULINGS.md", None, "维护台账缺少设计裁定入口"))
    if design.is_file() and "MAINTENANCE_RULINGS.md" not in read_text(design):
        findings.append(Finding("SPLIT_MAINT_LINK", "docs/superpowers/DESIGN_RULINGS.md", None, "设计索引缺少维护裁定入口"))
    return findings


def check_dated_status(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    status_dir = root / "docs/superpowers/status"
    if not status_dir.is_dir():
        return findings
    for path in sorted(status_dir.glob("*.md")):
        if "当前实时状态以" not in read_text(path):
            findings.append(
                Finding("DATED_STATUS_UNMARKED", path.relative_to(root).as_posix(), None, "历史 status 缺少当前状态指针")
            )
    return findings


def check_root_index(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for rel in ("AGENTS.md", "README.md"):
        path = root / rel
        if not path.is_file():
            continue
        text = read_text(path)
        if "docs/superpowers/status/" in text:
            findings.append(Finding("ROOT_STATUS_POINTER", rel, None, "根入口不得把 dated status 当当前状态"))
        if re.search(r"(最新|唯一).*(入口|未完成清单)|下次开工的唯一入口", text):
            findings.append(Finding("ROOT_LATEST_POINTER", rel, None, "根入口不得写最新/唯一入口"))
    agents = root / "AGENTS.md"
    if agents.is_file() and agents.stat().st_size > 20000:
        findings.append(Finding("ROOT_AGENTS_TOO_LARGE", "AGENTS.md", None, "根 AGENTS 超过 20KB，索引膨胀"))
    return findings


def check_at_imports(root: Path, files: list[Path]) -> list[Finding]:
    findings: list[Finding] = []
    for path in files:
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        for no, line in enumerate(read_text(path).splitlines(), start=1):
            if re.match(r"^\s*@[^\s]", line):
                findings.append(Finding("AT_IMPORT", rel, no, "@ 导入会展开长文，改用链接"))
    return findings


def check_worktree_policy(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    gitignore = root / ".gitignore"
    if not gitignore.is_file() or "/worktree/" not in read_text(gitignore):
        findings.append(Finding("WORKTREE_POLICY", ".gitignore", None, "缺少 /worktree/ 忽略规则"))
    env = root / "docs/agents/ENVIRONMENT.md"
    if not env.is_file() or "worktree/<name>" not in read_text(env):
        findings.append(Finding("WORKTREE_POLICY", "docs/agents/ENVIRONMENT.md", None, "缺少 worktree/<name> 规范"))
    agents = root / "AGENTS.md"
    if agents.is_file() and "worktree/<name>" not in read_text(agents):
        findings.append(Finding("WORKTREE_POLICY", "AGENTS.md", None, "根索引缺少 worktree/<name> 规则指针"))
    return findings


def check_skills_catalog(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    catalog = root / "docs/agents/SKILLS.md"
    if not catalog.is_file():
        return [Finding("SKILLS_CATALOG_MISSING", "docs/agents/SKILLS.md", None, "Skill 目录不存在")]
    text = read_text(catalog)
    skills = root / ".agents/skills"
    if not skills.is_dir():
        return findings
    for entry in sorted(skills.iterdir()):
        if not entry.is_dir():
            continue
        if not (entry / "SKILL.md").is_file():
            findings.append(Finding("SKILL_FILE_MISSING", entry.relative_to(root).as_posix(), None, "active 目录缺 SKILL.md"))
            continue
        if f"`{entry.name}`" not in text:
            findings.append(Finding("SKILL_NOT_CATALOGED", entry.relative_to(root).as_posix(), None, "active Skill 未登记到 SKILLS.md"))
    return findings


def run_checks(root: Path) -> tuple[list[Finding], dict[str, int]]:
    modules, findings = load_pom(root)
    findings.extend(check_modules(root, modules))
    files = current_docs(root, modules)
    link_findings, links = check_links(root, files)
    findings.extend(link_findings)
    findings.extend(check_status(root))
    findings.extend(check_split(root))
    findings.extend(check_dated_status(root))
    findings.extend(check_root_index(root))
    findings.extend(check_at_imports(root, files))
    findings.extend(check_worktree_policy(root))
    findings.extend(check_skills_catalog(root))
    stats = {
        "modules": len(modules),
        "files": sum(1 for p in files if p.is_file()),
        "links": links,
    }
    return findings, stats


def make_fixture(root: Path) -> None:
    modules = ["simos-a", "simos-b"]
    root.mkdir(parents=True, exist_ok=True)
    module_xml = "\n".join(f"    <module>{m}</module>" for m in modules)
    (root / "pom.xml").write_text(
        f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.mosire.selftest</groupId>
  <artifactId>selftest</artifactId>
  <version>0.0.0</version>
  <packaging>pom</packaging>
  <modules>
{module_xml}
  </modules>
</project>
""",
        encoding="utf-8",
    )
    module_agents = """# {m} — 局部 AGENTS

根规则：`../AGENTS.md`；状态：`../docs/agents/STATUS.md`；设计：`../docs/superpowers/AGENTS.md`。

## 工具归属
工具实现归本模块。

## 验证
```bash
tools/gate.sh focused {m} X
```
"""
    for module in modules:
        (root / module).mkdir()
        (root / module / "AGENTS.md").write_text(module_agents.format(m=module), encoding="utf-8")
    (root / "docs/agents").mkdir(parents=True)
    (root / "docs/superpowers/status").mkdir(parents=True)
    (root / "docs/AGENTS.md").write_text(
        "# docs rules\n\n项目设计在 superpowers，项目维护在 agents。\n\n- [STATUS](agents/STATUS.md)\n",
        encoding="utf-8",
    )
    (root / "docs/agents/STATUS.md").write_text(
        """# STATUS

> 更新时间：2026-01-01
> 最近功能门禁：NOT_RUN

## 当前目标
自检

## 未验证
无

## 下一步
无
""",
        encoding="utf-8",
    )
    (root / "docs/agents/MAINTENANCE_RULINGS.md").write_text(
        "# 项目维护裁定\n\n项目设计在 `../superpowers/DESIGN_RULINGS.md`。\n", encoding="utf-8"
    )
    (root / "docs/agents/SKILLS.md").write_text("# Skills\n\n当前无 active。\n", encoding="utf-8")
    for name in ("AGENTS.md", "WORKFLOW.md", "VERIFICATION.md", "ENVIRONMENT.md", "FRONTEND.md"):
        body = "# ENVIRONMENT\n\nworktree/<name>\n" if name == "ENVIRONMENT.md" else f"# {name}\n"
        (root / "docs/agents" / name).write_text(body, encoding="utf-8")
    (root / "docs/superpowers/AGENTS.md").write_text("# 设计索引\n\n项目设计入口。\n", encoding="utf-8")
    (root / "docs/superpowers/DESIGN_RULINGS.md").write_text(
        "# 设计裁定\n\n项目设计；项目维护见 `../agents/MAINTENANCE_RULINGS.md`。\n", encoding="utf-8"
    )
    (root / "docs/superpowers/status/old.md").write_text(
        "# 历史\n\n> 当前实时状态以 `docs/agents/STATUS.md` 为准。\n", encoding="utf-8"
    )
    (root / "AGENTS.md").write_text(
        "# AGENTS\n\n2 个 `simos-*` 模块：`simos-a` `simos-b`。\n\n"
        "worktree/<name>\n\n[状态](docs/agents/STATUS.md) [a](simos-a/AGENTS.md) [b](simos-b/AGENTS.md)\n",
        encoding="utf-8",
    )
    (root / ".gitignore").write_text("/worktree/\n", encoding="utf-8")
    (root / "README.md").write_text(
        "# README\n\n2 个 `simos-*` 模块。\n\n[状态](docs/agents/STATUS.md) [AGENTS](AGENTS.md)\n",
        encoding="utf-8",
    )
    (root / ".agents/skills").mkdir(parents=True)
    (root / ".agents/AGENTS.md").write_text("# .agents\n", encoding="utf-8")
    (root / ".agents/notes").mkdir()
    (root / ".agents/notes/README.md").write_text("# notes\n", encoding="utf-8")
    (root / ".superpowers").mkdir()
    (root / ".superpowers/AGENTS.md").write_text("# local evidence\n", encoding="utf-8")


def selftest_case(name: str, mutate, expected: str | None) -> bool:
    with tempfile.TemporaryDirectory(prefix="check-docs-selftest-") as tmp:
        root = Path(tmp) / "repo"
        make_fixture(root)
        mutate(root)
        findings, _ = run_checks(root)
        codes = {f.code for f in findings}
        ok = (not findings) if expected is None else expected in codes
        if ok:
            print(f"SELFTEST PASS check-docs.py {name} (findings={len(findings)})")
        else:
            print(f"SELFTEST FAIL check-docs.py {name}: expected={expected or 'PASS'} codes={sorted(codes)}")
            for f in findings:
                print("  " + f.render())
        return ok


def run_selftest() -> int:
    def dead_link(root: Path) -> None:
        with (root / "docs/agents/STATUS.md").open("a", encoding="utf-8") as fh:
            fh.write("\n[dead](../nope.md)\n")

    def module_missing(root: Path) -> None:
        p = root / "AGENTS.md"
        p.write_text(p.read_text(encoding="utf-8").replace("simos-b", "removed"), encoding="utf-8")

    def status_missing(root: Path) -> None:
        (root / "docs/agents/STATUS.md").unlink()

    def root_pointer(root: Path) -> None:
        p = root / "AGENTS.md"
        p.write_text(
            p.read_text(encoding="utf-8") + "\n最新入口：docs/superpowers/status/old.md\n",
            encoding="utf-8",
        )

    def module_agents_missing(root: Path) -> None:
        (root / "simos-b/AGENTS.md").unlink()

    def worktree_policy_missing(root: Path) -> None:
        (root / ".gitignore").write_text("", encoding="utf-8")

    def split_missing(root: Path) -> None:
        (root / "docs/AGENTS.md").write_text("# no split\n", encoding="utf-8")

    cases = [
        selftest_case("valid-green", lambda _root: None, None),
        selftest_case("dead-link-red", dead_link, "DEAD_LINK"),
        selftest_case("module-missing-red", module_missing, "MODULE_MISSING"),
        selftest_case("status-missing-red", status_missing, "STATUS_MISSING"),
        selftest_case("root-status-pointer-red", root_pointer, "ROOT_STATUS_POINTER"),
        selftest_case("module-agents-missing-red", module_agents_missing, "MODULE_AGENTS_MISSING"),
        selftest_case("worktree-policy-red", worktree_policy_missing, "WORKTREE_POLICY"),
        selftest_case("split-missing-red", split_missing, "SPLIT_MARKER_MISSING"),
    ]
    passed = sum(cases)
    total = len(cases)
    if passed == total:
        print(f"SELFTEST PASSED check-docs.py ({passed}/{total} cases)")
        return EXIT_PASS
    print(f"SELFTEST FAILED check-docs.py ({passed}/{total} cases)")
    return EXIT_FINDINGS


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="SimulatorMosire docs consistency check")
    parser.add_argument("--root", type=Path, help="repository root (default: script parent repo)")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)
    if args.selftest:
        return run_selftest()
    root = (args.root or Path(__file__).resolve().parent.parent).resolve()
    if not root.is_dir():
        print(f"check-docs.py: root does not exist: {root}", file=sys.stderr)
        return EXIT_USAGE
    findings, stats = run_checks(root)
    if findings:
        for finding in findings:
            print(finding.render())
        print(
            f"DOCS CHECK FAILED ({len(findings)} findings; modules={stats['modules']} "
            f"files={stats['files']} links={stats['links']})"
        )
        return EXIT_FINDINGS
    print(f"DOCS CHECK PASSED (modules={stats['modules']} files={stats['files']} links={stats['links']})")
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
