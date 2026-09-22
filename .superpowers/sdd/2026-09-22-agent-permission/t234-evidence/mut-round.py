#!/usr/bin/env python3
"""(T2/T3/T4) 变异轮装置：逐轮「改 → 跑 → 还原」，把**自指的 md5** 与红点追加进日志本身。

★ 本仓纪律（CLAUDE.md「护栏必须自证」）在此逐条兑现：
  1. **干净世界**：每轮开跑前从 pristine 快照 cp 还原并**核对 md5**（还原不等 ⇒ 本轮作废）；
     跑之前先验证 pristine 快照本身是"干净轮全绿"的那份字节。
  2. **编译错误不算红**：每轮断言 `COMPILATION ERROR` 计数为 0，否则记 VOID（"没跑到"≠"没红"）。
  3. **surefire 报告要认本轮**：每轮先删 `target/surefire-reports`，跑完核对报告 mtime 落在本轮内。
  4. **产物自指**：每轮把 baseline / mutant / restored 三个 md5、脚本自身 md5、红点断言名写进日志。
"""
import hashlib
import pathlib
import shutil
import subprocess
import sys
import time

ROOT = pathlib.Path("/home/cna/SimulatorMosire")
EVID = ROOT / ".superpowers/sdd/2026-09-22-agent-permission/t234-evidence"
LOG = EVID / "mutants.log"
PRISTINE = EVID / "pristine"
ROUNDS = EVID / "rounds"
TEST_CLASSES = (
    "AccessPathsTest,NationScopeTest,ArmyScopeTest,DecisionScopeFunctionsTest,ScopeFenceTest"
)
SUREFIRE = ROOT / "simos-app/target/surefire-reports"

NATION = "simos-app/src/main/java/io/mosire/simos/app/access/NationScope.java"
ARMY = "simos-app/src/main/java/io/mosire/simos/app/access/ArmyScope.java"
REGISTRY = "simos-app/src/main/java/io/mosire/simos/app/access/DecisionScopeFunctions.java"
FUNC = "simos-app/src/main/java/io/mosire/simos/app/access/DecisionScopeFunction.java"
SUPPORT = "simos-app/src/main/java/io/mosire/simos/app/tools/ToolSupport.java"

# 每个变异体：改哪个文件、动哪一段、期望的语义缺陷是什么。
MUTANTS = [
    dict(
        mid="t234m1",
        file=NATION,
        note="NationScope 的 tag 匹配退化成**前缀匹配**（把 nation:FRAX 当成 FRA）",
        anchor="      if (nationTag.equals(region.meta().tag())) {",
        replace="      if (region.meta().tag() != null && region.meta().tag().startsWith(nationTag)) {",
        expect="nationScopeCoversItsOwnRegionsAndNothingElse / theTagMustMatchExactlyNotAsAPrefix",
    ),
    dict(
        mid="t234m2",
        file=NATION,
        note="区域前缀**少拼一段**（写成 <mapId>/<rid>，丢掉 region 段）",
        anchor="        prefixes.add(ToolSupport.resourceRegion(mapId, region.id().value()).path());",
        replace='        prefixes.add(mapId + "/" + region.id().value());',
        expect="逐字断言（demo/region/701）与 allows 同时红",
    ),
    dict(
        mid="t234m3",
        file=NATION,
        note='无匹配区域时给**空图**（"本层不表态"，回落成放行）而不是显式 none()',
        anchor="""    return ResourceScopeMap.of(
        ToolSupport.MAP_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(prefixes));""",
        replace="""    return prefixes.isEmpty()
        ? ResourceScopeMap.empty()
        : ResourceScopeMap.of(
            ToolSupport.MAP_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(prefixes));""",
        expect="noMatchingRegionIsAnExplicitDenyAllNotAnEmptyMap / aNationWhoseRegionsAllDisappearedSeesNothing",
    ),
    dict(
        mid="t234m4",
        file=FUNC,
        note="scopeOfPrefixes 对空集返回 **unlimited()**（『哪里都不许』退化成『哪里都行』）",
        anchor="""    return prefixes.isEmpty()
        ? ResourceScope.none()
        : ResourceScope.of(prefixes.toArray(String[]::new));""",
        replace="""    return prefixes.isEmpty()
        ? ResourceScope.unlimited()
        : ResourceScope.of(prefixes.toArray(String[]::new));""",
        expect="两个 deny-all 用例（Nation 的 noMatchingRegion… / Army 的 anUnknownArmy…）",
    ),
    dict(
        mid="t234m5",
        file=REGISTRY,
        note="未注册的归属类型**静默回落**到某个默认实现（不响亮失败）",
        anchor="    DecisionScopeFunction function = byAffiliationType.get(affiliation.getClass());",
        replace=(
            "    DecisionScopeFunction function =\n"
            "        byAffiliationType.getOrDefault(affiliation.getClass(), NationScope.INSTANCE);"
        ),
        expect="anUnregisteredAffiliationTypeFailsLoudly / anEmptyRegistryFailsLoudlyForEveryAffiliation",
    ),
    dict(
        mid="t234m6",
        file=ARMY,
        note="视野半径 **+1**（半径语义偏一格）",
        anchor="    for (HexCoord coord : HexGrid.withinRadius(center.get(), root.visionRadius())) {",
        replace="    for (HexCoord coord : HexGrid.withinRadius(center.get(), root.visionRadius() + 1)) {",
        expect="radiusOneSeesItselfPlusTheSixNeighbours（7→19）等半径用例",
    ),
    dict(
        mid="t234m7",
        file=ARMY,
        note="位置**自己重算**（读 unit.position() 而不是 effectivePosition）",
        anchor="""    Optional<HexCoord> center =
        units.effectivePosition(affiliation.rootUnit(), state.meta().timestamp());""",
        replace="""    Optional<HexCoord> center = root.position().valueAt(state.meta().timestamp());""",
        expect="armyPositionComesFromEffectivePositionNotFromTheUnitsOwnField（编队夹具下两实现分叉）",
    ),
    dict(
        mid="t234m8",
        file=SUPPORT,
        note="hex 路径的坐标分隔符写成 `/` 而不是 `_`（围栏语法漂移）",
        anchor='    return ResourceId.of(MAP_NAMESPACE, mapId + "/hex/" + q + "_" + r);',
        replace='    return ResourceId.of(MAP_NAMESPACE, mapId + "/hex/" + q + "/" + r);',
        expect="AccessPathsTest 的逐字断言（含负坐标）",
    ),
    dict(
        mid="t234m9",
        file=SUPPORT,
        note="MAP_NAMESPACE 漂移（助手/范围函数报的命名空间与工具声明的 ResourceManifest 不是同一个）",
        anchor='  public static final String MAP_NAMESPACE = "map";',
        replace='  public static final String MAP_NAMESPACE = "maps";',
        expect="AccessPathsTest 的命名空间断言（工具声明里的 map 与它不再同源）",
    ),
]


def md5(path):
    return hashlib.md5(path.read_bytes()).hexdigest()


def now():
    return time.strftime("%H:%M:%S")


def run_tests(log_path):
    """跑本轮的四组用例，日志落盘；返回 (rc, log_text)。"""
    cmd = [
        "./mvnw", "-q", "-pl", "simos-app", "-am",
        f"-Dtest={TEST_CLASSES}", "-Dsurefire.failIfNoSpecifiedTests=false", "test",
    ]
    started = time.time()
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=900)
    text = proc.stdout + proc.stderr
    log_path.write_text(text)
    return proc.returncode, text, started


def summarize(text, started):
    """从本轮日志与 surefire 报告取：编译错误数、红点断言名、本轮是否真的跑了。"""
    compilation_errors = text.count("COMPILATION ERROR")
    fresh_reports = []
    if SUREFIRE.exists():
        for txt in sorted(SUREFIRE.glob("*.txt")):
            if txt.stat().st_mtime >= started - 1:
                counts = [line for line in txt.read_text().splitlines() if "Tests run:" in line]
                fresh_reports.append(f"{txt.name}: {counts[0] if counts else 'NO COUNTS'}")
    red_tests, red_details = [], []
    for line in text.splitlines():
        if "<<< FAILURE!" in line or "<<< ERROR!" in line:
            stripped = line.replace("[ERROR]", "").strip()
            red_tests.append(stripped.split(" -- ")[0])
        elif line.startswith("[ERROR]   ") and "Test." in line:
            red_details.append(line.replace("[ERROR]", "").strip())
    def count(report, label):
        parts = report.split(label + ":")
        return int(parts[1].split(",")[0].strip()) if len(parts) > 1 else 0

    failures = sum(count(report, "Failures") for report in fresh_reports)
    errors = sum(count(report, "Errors") for report in fresh_reports)
    return dict(
        compilation_errors=compilation_errors,
        fresh_reports=fresh_reports,
        red_tests=red_tests,
        red_details=red_details[:12],
        failures=failures,
        errors=errors,
    )


def append_block(lines):
    with LOG.open("a") as handle:
        handle.write("\n".join(lines) + "\n")


def main():
    fresh = "--fresh" in sys.argv
    EVID.mkdir(parents=True, exist_ok=True)
    ROUNDS.mkdir(parents=True, exist_ok=True)
    PRISTINE.mkdir(parents=True, exist_ok=True)
    if fresh and LOG.exists():
        archived = EVID / f"mutants-archived-{time.strftime('%H%M%S')}.log"
        LOG.rename(archived)
        print(f"[fresh] archived previous log -> {archived.name}", flush=True)
    script_md5 = md5(pathlib.Path(__file__))
    header = [
        "",
        "=" * 78,
        f"run_start={time.strftime('%Y-%m-%d %H:%M:%S')} script_md5={script_md5}",
        f"mutants={len(MUTANTS)} classes={TEST_CLASSES}",
        "=" * 78,
    ]

    files = sorted({m["file"] for m in MUTANTS})

    # ── 0a. 先无条件还原（**装置自己也会留状态**：上一轮跑到一半崩掉时，变异体还在盘上） ────────
    # 只有已存在完整 pristine 快照时才做；快照的"好"由随后的干净轮证明（全绿才算数）。
    if PRISTINE.exists() and all((PRISTINE / pathlib.Path(f).name).exists() for f in files):
        for rel in files:
            src = ROOT / rel
            pristine = PRISTINE / pathlib.Path(rel).name
            if md5(src) != md5(pristine):
                header.append(f"PRERESTORE {rel} {md5(src)} -> {md5(pristine)}")
                shutil.copyfile(pristine, src)
    else:
        header.append("PRERESTORE skipped（本机还没有完整 pristine 快照）")

    # ── 0b. 干净轮：先证明待冻结的字节是"全绿"的那份（否则 pristine 快照没有意义） ──────────────
    print("[clean] running baseline", flush=True)
    clean_rc, clean_text, clean_started = run_tests(ROUNDS / "clean.log")
    clean = summarize(clean_text, clean_started)
    header += [
        f"CLEAN round={now()} rc={clean_rc} compilation_errors={clean['compilation_errors']}",
        f"CLEAN reports={clean['fresh_reports']}",
    ]
    if clean_rc != 0 or clean["failures"] or clean["errors"]:
        header.append("CLEAN_FAILED —— 基线不是全绿，装置拒绝继续（pristine 快照会没有意义）")
        append_block(header)
        print("baseline not green; aborting")
        return 1

    # ── 快照 pristine（干净轮之后冻结；已存在则核对，不重写——它是"好字节"的定义） ──────────────
    for rel in files:
        target = PRISTINE / pathlib.Path(rel).name
        if target.exists():
            header.append(f"PRISTINE {rel} md5={md5(target)} (kept)")
            if md5(target) != md5(ROOT / rel):
                header.append(f"PRISTINE_MISMATCH {rel}（工作树与快照不同——干净轮绿的却是工作树，需人工看）")
        else:
            shutil.copyfile(ROOT / rel, target)
            header.append(f"PRISTINE {rel} md5={md5(target)} -> {target.name}")

    # ── 可续跑：日志里已有 verdict 的变异体跳过（"这一轮跑的是哪份字节"由块内 md5 自指） ──────────
    done = {}
    if LOG.exists():
        current = None
        for line in LOG.read_text().splitlines():
            if line.startswith("===== ") and line.endswith(" ====="):
                current = line.strip("= ").strip()
            elif current and line.startswith("verdict="):
                done[current] = line.split("=", 1)[1]
                current = None
    if done:
        header.append(f"RESUME: 已完成的轮次 {done}")

    verdicts = dict(done)
    for mutant in MUTANTS:
        if done.get(mutant["mid"]) in {"KILLED", "SURVIVED"}:
            print(f"[{mutant['mid']}] skipped (already {done[mutant['mid']]})", flush=True)
            continue
        rel = mutant["file"]
        src = ROOT / rel
        pristine = PRISTINE / pathlib.Path(rel).name
        log_path = ROUNDS / f"{mutant['mid']}.log"

        # 干净世界：先从 pristine 还原，再核对
        shutil.copyfile(pristine, src)
        base_md5 = md5(src)
        block = ["", "=" * 78, f"===== {mutant['mid']} =====",
                 f"file={rel}", f"note={mutant['note']}", f"expect={mutant['expect']}",
                 f"script_md5={script_md5}", f"baseline_md5={base_md5}",
                 f"pristine_md5={md5(pristine)}"]
        if base_md5 != md5(pristine):
            block.append("VOID: 还原后与 pristine 不一致（工作目录不是干净世界）")
            append_block(block)
            verdicts[mutant["mid"]] = "VOID"
            continue

        text = src.read_text()
        occurrences = text.count(mutant["anchor"])
        block.append(f"anchor_occurrence={occurrences}")
        if occurrences != 1:
            block.append("VOID: 锚点不唯一（改的不是想改的那一处）")
            append_block(block)
            verdicts[mutant["mid"]] = "VOID"
            continue

        # ★ try/finally：**装置自己也会留状态**（本机实测——第一次跑崩在 summarize 里，
        #   变异体就留在了盘上，下一轮的"干净轮"当场变红）⇒ 无论怎么退出都先把字节还原。
        try:
            src.write_text(text.replace(mutant["anchor"], mutant["replace"], 1))
            mutant_md5 = md5(src)
            block.append(f"mutant_md5={mutant_md5}")
            block.append(f"bytes_changed={mutant_md5 != base_md5}")
            if mutant_md5 == base_md5:
                block.append("VOID: 变异体与原件逐字节相同（等于没改）")
                append_block(block)
                verdicts[mutant["mid"]] = "VOID"
                continue

            shutil.rmtree(SUREFIRE, ignore_errors=True)  # 报告必须是本轮的
            rc, run_text, started = run_tests(log_path)
            result = summarize(run_text, started)
        finally:
            shutil.copyfile(pristine, src)

        block += [
            f"round_at={now()} maven_rc={rc}",
            f"compilation_errors={result['compilation_errors']}",
            f"surefire_fresh_reports={result['fresh_reports']}",
            f"totals: failures={result['failures']} errors={result['errors']}",
        ]
        for name in result["red_tests"]:
            block.append(f"RED: {name}")
        for detail in result["red_details"]:
            block.append(f"RED_DETAIL: {detail}")

        # 还原并核对（逐字节 cp，mtime 变不算"被改"——md5 才算）
        restored_md5 = md5(src)
        block.append(f"restored_md5={restored_md5}")
        block.append(f"restored_identical={restored_md5 == base_md5}")

        if restored_md5 != base_md5:
            verdict = "VOID"
            block.append("VOID: 还原失败")
        elif result["compilation_errors"] > 0:
            verdict = "VOID"
            block.append("VOID: 编译错误（'没跑到'不等于'没红'）")
        elif not result["fresh_reports"]:
            verdict = "VOID"
            block.append("VOID: 本轮没有新鲜 surefire 报告（可能根本没跑到）")
        elif result["failures"] + result["errors"] > 0:
            verdict = "KILLED"
        else:
            verdict = "SURVIVED"
        block.append(f"verdict={verdict}")
        append_block(block)
        verdicts[mutant["mid"]] = verdict
        print(f"[{mutant['mid']}] {verdict} failures={result['failures']}", flush=True)

    tail = ["", "-" * 78, "SUMMARY " + " ".join(f"{k}={v}" for k, v in verdicts.items()),
            f"killed={sum(1 for v in verdicts.values() if v == 'KILLED')} "
            f"survived={sum(1 for v in verdicts.values() if v == 'SURVIVED')} "
            f"void={sum(1 for v in verdicts.values() if v == 'VOID')}"]
    append_block(tail)
    print(tail[-2])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
