#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""T13-调优的变异轮装置（决策人运行流：预算 + 身份消息规则）。

★ 设计要点（照本仓的形态清单）：
  1. **只改一个文件**（`DecisionAgentRunner.java`），一次只改一处；替换**必须恰好命中一次**，否则当场作废；
  2. **变异体按规范名就地推**（不做"按变异体文件名拷入"那种会变成编译错误的形态）；
  3. **编译错误必须为 0**（`COMPILATION ERROR` 计数），不为 0 判 VOID；
  4. **report_files=0 ⇒ VOID**：跑之前先删本轮要看的报告，跑完必须存在且 mtime 落在本轮内
     （防"拿上次留下的绿/红当本轮结论"）；
  5. **留痕自指**：每轮的 baseline/mutant/restored 三个 md5、restored_identical、compilation_errors、
     report_files、红点断言名**全部写进这一轮自己的日志**；
  6. 轮间把工作目录**恢复成干净世界**（逐字节 `cp` 还原 + 双侧 md5 相等才算数）。
"""
import hashlib
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path("/home/cna/SimulatorMosire")
TARGET = ROOT / "simos-app/src/main/java/io/mosire/simos/app/decision/DecisionAgentRunner.java"
EVID = ROOT / ".superpowers/sdd/2026-09-22-agent-permission/ts-m1-tuning-evidence"
PRISTINE = EVID / "pristine-DecisionAgentRunner.java"
REPORTS = ROOT / "simos-app/target/surefire-reports"
SUREFIRE = ["DecisionAgentRunnerTest", "RunDecisionEndToEndTest"]
REPORT_FILES = [REPORTS / f"io.mosire.simos.app.decision.{n}.txt" for n in SUREFIRE]
TEST_FILTER = ",".join(SUREFIRE)

MUTANTS = [
    (
        "m1-budget-back-to-8",
        "预算改回 8（现场那个不够用的数）",
        "public static final int DEFAULT_MAX_LLM_CALLS = 20;",
        "public static final int DEFAULT_MAX_LLM_CALLS = 8;",
    ),
    (
        "m2-identity-drops-the-rule",
        "身份消息删掉【出令的 commands 放什么】整段",
        '            + "。\\n"\n'
        '            + "\\n"\n'
        '            + "【出令的 commands 放什么】只放**领域命令**（改地图、动单位这一类），可用类型与载荷字段见 "\n'
        '            + catalog\n'
        '            + "。★ **以 sd. 开头的命令类型一律会被拒**——那是防无限自指（令不得再生成令，否则会一直递归下去），"\n'
        '            + "所以别让令去注册效果、下指令或改动推演自身的状态；第一次出令就撞上这条，纯属白烧一轮。");',
        '            + "。");',
    ),
    (
        "m3-rule-uses-real-tool-name",
        "新规则里的 catalog 写成**真名**（不是线名）",
        '            + "【出令的 commands 放什么】只放**领域命令**（改地图、动单位这一类），可用类型与载荷字段见 "\n'
        "            + catalog\n",
        '            + "【出令的 commands 放什么】只放**领域命令**（改地图、动单位这一类），可用类型与载荷字段见 "\n'
        '            + "simos.command.catalog"\n',
    ),
    (
        "m4-rule-prefix-is-wire-style",
        "规则里把命令类型前缀 `sd.` 写成工具线名式的 `sd_`（混层）",
        "+ \"。★ **以 sd. 开头的命令类型一律会被拒**",
        "+ \"。★ **以 sd_ 开头的命令类型一律会被拒**",
    ),
]


def md5(path: Path) -> str:
    return hashlib.md5(path.read_bytes()).hexdigest()


def red_points(log_text: str, started: float) -> list:
    """从 surefire 报告里取本轮的红点（报告必须落在本轮内，否则算作没跑到）。"""
    found = []
    for report in REPORT_FILES:
        if not report.exists():
            found.append(f"!! 报告不存在: {report.name}")
            continue
        if report.stat().st_mtime < started:
            found.append(f"!! 报告是陈旧的（mtime 早于本轮）: {report.name}")
            continue
        text = report.read_text(encoding="utf-8", errors="replace")
        body = text.splitlines()
        for i, line in enumerate(body):
            if "<<< FAILURE!" in line or "<<< ERROR!" in line:
                found.append(line.split(" -- ")[0])
            elif "to contain:" in line or "not to contain:" in line:
                # ★ 断言正文要**逐字**进日志（红的理由必须是被保护的那一行本身，不能只留一句"Expecting"）
                found.append("    " + line.strip()[:260])
                nxt = body[i + 1].strip() if i + 1 < len(body) else ""
                if nxt:
                    found.append("      ^ 断言的目标串: " + nxt[:260])
            elif (
                "but was:" in line
                or line.startswith("expected:")
                or line.startswith("Expecting")
                or re.match(r"^[a-z][\w.$]*Exception: ", line)
            ):
                found.append("    " + line.strip()[:260])
            elif line.strip().startswith("at io.mosire."):
                # ★ 失败断言**落在哪个文件的哪一行**——最直接的"为什么红"
                found.append("      @" + line.strip()[3:])
    return found


def run_round(name: str, note: str, old: str, new: str) -> bool:
    log = EVID / f"{name}.log"
    lines = []
    started = time.time()

    # ── 干净世界：把目标文件恢复成基线，并记下基线 md5
    shutil.copy(PRISTINE, TARGET)
    baseline_md5 = md5(TARGET)
    lines.append(f"### round={name}")
    lines.append(f"note={note}")
    lines.append(f"target={TARGET.relative_to(ROOT)}")
    lines.append(f"baseline_md5={baseline_md5}")

    text = TARGET.read_text(encoding="utf-8")
    hits = text.count(old)
    lines.append(f"replacement_hits={hits}")
    if hits != 1:
        lines.append("verdict=VOID（替换未恰好命中一次——装置故障，不是被测物结论）")
        log.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return False

    TARGET.write_text(text.replace(old, new), encoding="utf-8")
    mutant_md5 = md5(TARGET)
    lines.append(f"mutant_md5={mutant_md5}")
    lines.append(f"mutant_differs_from_baseline={mutant_md5 != baseline_md5}")
    if mutant_md5 == baseline_md5:
        lines.append("verdict=VOID（落盘的字节与基线相同——变异体没生效）")
        log.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return False

    # ── 清掉本轮要读的报告（防陈旧数字）
    for report in REPORT_FILES:
        report.unlink(missing_ok=True)
    lines.append(f"reports_deleted_before_run={[r.name for r in REPORT_FILES]}")

    proc = subprocess.run(
        [
            "./mvnw", "-pl", "simos-app", "-am",
            f"-Dtest={TEST_FILTER}",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test",
        ],
        cwd=ROOT, capture_output=True, text=True,
    )
    out = proc.stdout + proc.stderr
    (EVID / f"{name}.mvn.log").write_text(out, encoding="utf-8")

    compilation_errors = out.count("COMPILATION ERROR")
    report_files = [r.name for r in REPORT_FILES if r.exists()]
    points = red_points(out, started)

    lines.append(f"mvn_rc={proc.returncode}")
    lines.append(f"compilation_errors={compilation_errors}")
    lines.append(f"report_files={len(report_files)} {report_files}")
    lines.append("red_points:")
    for point in points:
        lines.append("  - " + point)

    if compilation_errors:
        verdict = "VOID（编译失败——变异体没跑到用例，不算杀）"
    elif not report_files:
        verdict = "VOID（report_files=0——没跑到，不算杀）"
    elif points:
        verdict = "KILLED"
    else:
        verdict = "SURVIVED"
    lines.append(f"verdict={verdict}")

    # ── 恢复干净世界 + 双侧 md5
    shutil.copy(PRISTINE, TARGET)
    restored_md5 = md5(TARGET)
    lines.append(f"restored_md5={restored_md5}")
    lines.append(f"restored_identical={restored_md5 == baseline_md5}")
    lines.append(f"elapsed_s={time.time() - started:.1f}")

    log.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[{name}] {verdict} red={len(points)} compile_err={compilation_errors} reports={len(report_files)}")
    return verdict == "KILLED"


def main() -> int:
    EVID.mkdir(parents=True, exist_ok=True)
    shutil.copy(TARGET, PRISTINE)  # 基线 = 当前（spotless 之后的最终字节）
    print(f"baseline written to {PRISTINE.name}: md5={md5(PRISTINE)}")
    results = []
    for name, note, old, new in MUTANTS:
        results.append((name, run_round(name, note, old, new)))
    # 收尾：确保工作目录回到基线
    shutil.copy(PRISTINE, TARGET)
    print("--- summary ---")
    for name, killed in results:
        print(f"{name}: {'KILLED' if killed else 'NOT-KILLED'}")
    print(f"final_target_md5={md5(TARGET)} baseline_md5={md5(PRISTINE)}")
    return 0 if all(k for _, k in results) else 1


if __name__ == "__main__":
    sys.exit(main())
