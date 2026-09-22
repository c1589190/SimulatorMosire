#!/usr/bin/env python3
"""T10 变异轮驱动（自指留痕）。

每条变异体：备份原件（md5）→ 落变异体（md5 + 断言与原件**字节不同**）→ 删本轮 surefire 报告并记本轮起点
→ 跑 `./mvnw -pl simos-app -am test`（整模块，避免"只跑某类"把别的漏掉而误判存活）
→ 判 VOID（编译错 / 报告数 0）→ 提取红点用例名 → 逐字节还原并核 md5 → 把上面每一项**追加进本轮日志本身**。

★ report_files=0 ⇒ VOID（"没跑到"不许读成"存活"，本仓纪律形态 1 与 6）。
★ 条目形态：(id, [(相对路径, 原件片段, 变异片段), …], 破坏的护栏, 备注)。多条 = 一个变异体跨两处。
★ 两组：`MUTANTS` = T10 本轮新护栏；`OLD_MUTANTS` = **裁定 42 的连带重跑**（T2~T4 / T5~T8 的旧轮，
  其靶文件被 T10 改过 ⇒ 必须从**最终字节**重新派生后重跑）。
"""
import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path("/home/cna/SimulatorMosire")
EVID = ROOT / ".superpowers/sdd/2026-09-22-agent-permission/t10-evidence/mutants"
REPORTS = ROOT / "simos-app/target/surefire-reports"

TOOL = "simos-app/src/main/java/io/mosire/simos/app/tools"
ACCESS = "simos-app/src/main/java/io/mosire/simos/app/access"

MUTANTS = [
    (
        "m1-overview-unfiltered",
        [
            (
                f"{TOOL}/read/MapOverviewTool.java",
                """      return ToolSupport.ok(
          ToolSupport.mapOverview(
              mapId,
              map,
              coord -> ToolSupport.hexVisible(context, mapId, map, coord),
              region -> ToolSupport.regionVisible(context, mapId, region)));""",
                """      return ToolSupport.ok(ToolSupport.mapOverview(mapId, map));""",
            )
        ],
        "总览不过滤直接回全量",
    ),
    (
        "m2-hex-returns-200",
        [
            (
                f"{TOOL}/read/MapHexTool.java",
                """      if (cell == null || !ToolSupport.hexVisible(context, mapId, map, coord)) {""",
                """      if (cell == null) {""",
            )
        ],
        "越界格返回 200 而非 NOT_FOUND",
    ),
    (
        "m3-write-hook-open",
        [
            (
                f"{TOOL}/write/AbstractNarrowWriteTool.java",
                """  protected List<ResourceId> writeResources(ToolContext context) {
    return ToolSupport.allWriteResources(mapId);
  }""",
                """  protected List<ResourceId> writeResources(ToolContext context) {
    return List.of();
  }""",
            )
        ],
        "写工具缺省资源声明被改成放行（窄写那一支）",
    ),
    (
        "m4-swallow-resource-denied",
        [
            (
                f"{TOOL}/write/AbstractNarrowWriteTool.java",
                """    } catch (ResourceDeniedException e) {""",
                """    } catch (ResourceDeniedException e) {
      if (true) {
        return ToolResult.error("TOOL_ERROR", "命令提交失败: " + e.getMessage());
      }""",
            )
        ],
        "ResourceDeniedException 又被吞回 TOOL_ERROR",
    ),
    (
        "m5-hex-loses-region-channel",
        [
            (
                f"{TOOL}/ToolSupport.java",
                """    for (RegionId owner : map.regionIndex().regionOf(coord)) {
      if (regionVisible(context, mapId, owner)) {
        return true;
      }
    }
    return false;""",
                """    return false;""",
            )
        ],
        "hexVisible 丢掉区域通道（国家决策人读不到本国的格）",
    ),
    (
        "m6-nation-omits-unit-namespace",
        [
            (
                f"{ACCESS}/NationScope.java",
                """        .withNamespace(
            ToolSupport.UNIT_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(unitPrefixes))
""",
                "",
            )
        ],
        "范围函数不表态 unit ⇒ 回落工具缺省 ⇒ 静默全放行",
    ),
    (
        "m7-factory-omits-sd-selfscope",
        [
            (
                f"{ACCESS}/DecisionCallerFactory.java",
                """    ResourceScopeMap computed =
        scopeFunctions
            .scopesFor(dm, state, mapId)
            .withNamespace(ToolSupport.SD_NAMESPACE, selfDecisionScope(dm));""",
                """    ResourceScopeMap computed = scopeFunctions.scopesFor(dm, state, mapId);""",
            )
        ],
        "决策人没有 sd 自身决策域 ⇒ 出不了令",
    ),
    (
        "m8-directive-coarse-declaration",
        [
            (
                f"{TOOL}/write/IssueDirectiveTool.java",
                """  protected List<ResourceId> writeResources(ToolContext context) {
    return decisionWriteResources(context);
  }""",
                """  protected List<ResourceId> writeResources(ToolContext context) {
    return ToolSupport.allWriteResources("Map1");
  }""",
            )
        ],
        "决策窄工具退回粗断言 ⇒ 整调被拒",
    ),
    (
        "m9-directive-default-policy-open",
        [
            (
                f"{TOOL}/write/IssueDirectiveTool.java",
                """      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);""",
                """      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);""",
            )
        ],
        "sd 缺省策略由 read-only 改回 unrestricted ⇒ 未表态者反而拿到全量写权",
    ),
    (
        "m10-verdict-default-policy-open",
        [
            (
                f"{TOOL}/write/SubmitVerdictTool.java",
                """      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);""",
                """      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);""",
            )
        ],
        "同 m9，落在 SubmitVerdictTool（两条工具各被自己的变异体红）",
    ),
]

# ── 裁定 42：T2~T4 / T5~T8 的旧轮里，靶文件被 T10 动过的那几条（从**最终字节**重新派生后重跑）────────
OLD_MUTANTS = [
    (
        "t234m1-nation-tag-prefix-match",
        [
            (
                f"{ACCESS}/NationScope.java",
                """      if (!nationTag.equals(region.meta().tag())) {""",
                """      if (!(region.meta().tag() != null && region.meta().tag().startsWith(nationTag))) {""",
            )
        ],
        "NationScope 的 tag 匹配退化成前缀匹配（把 nation:FRAX 当成 FRA）",
        "重派生：原锚点 `if (nationTag.equals(...))` 在 T10 改成早退形态后不复存在",
    ),
    (
        "t234m2-region-prefix-missing-segment",
        [
            (
                f"{ACCESS}/NationScope.java",
                """      regionPrefixes.add(ToolSupport.resourceRegion(mapId, region.id().value()).path());""",
                """      regionPrefixes.add(mapId + "/" + region.id().value());""",
            )
        ],
        "区域前缀少拼一段（写成 <mapId>/<rid>，丢掉 region 段）",
    ),
    (
        "t234m3-empty-scope-map-instead-of-none",
        [
            (
                f"{ACCESS}/NationScope.java",
                """    return ResourceScopeMap.of(
            ToolSupport.MAP_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(regionPrefixes))""",
                """    if (regionPrefixes.isEmpty() && unitPrefixes.isEmpty() && socialPrefixes.isEmpty()) {
      return ResourceScopeMap.empty();
    }
    return ResourceScopeMap.of(
            ToolSupport.MAP_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(regionPrefixes))""",
            )
        ],
        "无匹配区域时给空图（本层不表态⇒回落放行）而不是显式 none()",
        "重派生：T10 把返回值改成三命名空间链，原锚点（单命名空间）不复存在",
    ),
    (
        "t234m4-scope-of-prefixes-unlimited",
        [
            (
                f"{ACCESS}/DecisionScopeFunction.java",
                """    return prefixes.isEmpty()
        ? ResourceScope.none()
        : ResourceScope.of(prefixes.toArray(String[]::new));""",
                """    return prefixes.isEmpty()
        ? ResourceScope.unlimited()
        : ResourceScope.of(prefixes.toArray(String[]::new));""",
            )
        ],
        "scopeOfPrefixes 对空集返回 unlimited()（哪里都不许退化成哪里都行）",
        "文件未被 T10 改动，原样重放",
    ),
    (
        "t234m5-registry-silent-fallback",
        [
            (
                f"{ACCESS}/DecisionScopeFunctions.java",
                """    DecisionScopeFunction function = byAffiliationType.get(affiliation.getClass());""",
                """    DecisionScopeFunction function =
        byAffiliationType.getOrDefault(affiliation.getClass(), NationScope.INSTANCE);""",
            )
        ],
        "未注册的归属类型静默回落到某个默认实现（不响亮失败）",
        "文件未被 T10 改动，原样重放",
    ),
    (
        "t234m6-vision-radius-plus-one",
        [
            (
                f"{ACCESS}/ArmyScope.java",
                """HexGrid.withinRadius(center.get(), root.visionRadius())""",
                """HexGrid.withinRadius(center.get(), root.visionRadius() + 1)""",
            )
        ],
        "视野半径 +1（半径语义偏一格）",
        "锚点仍在（T10 在它外面套了 TreeSet），原样重放",
    ),
    (
        "t234m7-position-recomputed",
        [
            (
                f"{ACCESS}/ArmyScope.java",
                """    Optional<HexCoord> center =
        units.effectivePosition(affiliation.rootUnit(), state.meta().timestamp());""",
                """    Optional<HexCoord> center = root.position().valueAt(state.meta().timestamp());""",
            )
        ],
        "位置自己重算（读 unit.position() 而不是 effectivePosition）",
        "锚点仍在，原样重放",
    ),
    (
        "t234m8-hex-path-separator-drift",
        [
            (
                f"{TOOL}/ToolSupport.java",
                """    return ResourceId.of(MAP_NAMESPACE, mapId + "/hex/" + q + "_" + r);""",
                """    return ResourceId.of(MAP_NAMESPACE, mapId + "/hex/" + q + "/" + r);""",
            )
        ],
        "hex 路径的坐标分隔符写成 / 而不是 _（围栏语法漂移）",
        "锚点未被 T10 改动，原样重放",
    ),
    (
        "t234m9-map-namespace-drift",
        [
            (
                f"{TOOL}/ToolSupport.java",
                """  public static final String MAP_NAMESPACE = "map";""",
                """  public static final String MAP_NAMESPACE = "maps";""",
            )
        ],
        "MAP_NAMESPACE 漂移（助手与工具声明的命名空间不再同源）",
        "锚点未被 T10 改动，原样重放",
    ),
    (
        "t5t8m3-decision-scopes-unlimited",
        [
            (
                f"{ACCESS}/DecisionCallerFactory.java",
                """            .resourceScopes(gmAccessLimit == null ? computed : computed.narrowTo(gmAccessLimit))""",
                """            .resourceScopes(ResourceScopeMap.of(ToolSupport.MAP_NAMESPACE, ResourceScope.unlimited()))""",
            )
        ],
        "决策人的 resourceScopes 被 unlimited() 覆盖 —— 范围函数白算",
        "锚点未被 T10 改动，原样重放",
    ),
    (
        "t5t8m4-whitelist-regains-a-write",
        [
            (
                f"{ACCESS}/DecisionCallerFactory.java",
                """          IssueDirectiveTool.NAME,
          SubmitVerdictTool.NAME);""",
                """          IssueDirectiveTool.NAME,
          SubmitVerdictTool.NAME,
          io.mosire.simos.app.tools.write.UnitRenameTool.NAME);""",
            )
        ],
        "决策人白名单放回一条写工具（unit.RenameUnit）",
        "锚点未被 T10 改动，原样重放",
    ),
    (
        "t5t8m8-scope-cached",
        [
            (
                f"{ACCESS}/DecisionCallerFactory.java",
                """  private final DecisionScopeFunctions scopeFunctions;""",
                """  private static final java.util.concurrent.ConcurrentHashMap<String, ResourceScopeMap> CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final DecisionScopeFunctions scopeFunctions;""",
            ),
            (
                f"{ACCESS}/DecisionCallerFactory.java",
                """    ResourceScopeMap computed =
        scopeFunctions
            .scopesFor(dm, state, mapId)
            .withNamespace(ToolSupport.SD_NAMESPACE, selfDecisionScope(dm));""",
                """    ResourceScopeMap computed =
        CACHE
            .computeIfAbsent(dm.id().value(), key -> scopeFunctions.scopesFor(dm, state, mapId))
            .withNamespace(ToolSupport.SD_NAMESPACE, selfDecisionScope(dm));""",
            ),
        ],
        "范围不再每次现算（按决策人 id 缓存）—— 世界变了范围不变",
        "重派生：T10 的 computed 变成三命名空间链，原锚点不复存在",
    ),
]


def md5_of(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def tree_digest(sub: str) -> str:
    """整棵子树的聚合指纹（路径+内容，排序后）——**这一轮跑的是哪一版树**的自指锚。"""
    lines = []
    for p in sorted((ROOT / sub).rglob("*.java")):
        lines.append(f"{p.relative_to(ROOT)}:{md5_of(p.read_bytes())}")
    return md5_of("\n".join(lines).encode("utf-8"))


def red_points(log_text: str) -> list:
    """surefire 的失败汇总行：`[ERROR]   <Class>.<method>:<line> [消息]`。"""
    out = []
    for line in log_text.splitlines():
        m = re.match(r"^\[ERROR\]\s{2,}([\w.$]+\.\w+):\d+", line)
        if m:
            out.append(line.strip()[:200])
    seen = []
    for item in out:
        if item not in seen:
            seen.append(item)
    return seen


def run_round(ident: str, edits: list, guard: str, note: str = "") -> dict:
    """edits = [(相对路径, 原件片段, 变异片段), …]（多条 = 同一个变异体跨两处）。"""
    originals = {}
    backup_md5 = {}
    for rel, _old, _new in edits:
        originals[rel] = (ROOT / rel).read_bytes()
        backup_md5[rel] = md5_of(originals[rel])
    # ★ 同一文件的多处改动必须**串起来**（后一处改前一处已改过的文本）——第一版装置让每处各从
    #   原件快照出发，于是"先加字段、再用字段"里的**字段被后一处覆盖掉**，落盘的是半个变异体
    #   ⇒ 编译失败 ⇒ VOID 轮（假"没跑到"）。本仓纪律形态 1/6/8 的同族：装置自己带状态。
    mutant_texts = {rel: originals[rel].decode("utf-8") for rel, _, _ in edits}
    for rel, old, new in edits:
        hits = mutant_texts[rel].count(old)
        if hits != 1:
            return {
                "id": ident,
                "verdict": "VOID",
                "why": f"{rel} 锚点命中 {hits} 次（须恰 1 次）",
            }
        mutant_texts[rel] = mutant_texts[rel].replace(old, new, 1)

    for rel, text in mutant_texts.items():
        (ROOT / rel).write_bytes(text.encode("utf-8"))
    changed = {rel: md5_of((ROOT / rel).read_bytes()) for rel, _, _ in edits}
    # ★ 变异体自证：落盘的必须与原件**字节不同**（否则等于没改）
    if all(changed[rel] == backup_md5[rel] for rel in changed):
        return {"id": ident, "verdict": "VOID", "why": "变异体与原件逐字节相同（等于没改）"}

    log_path = EVID / f"{ident}.log"
    round_start = time.time()
    # ★ 自指：这一轮的测试树 / 生产树分别指纹化——只在靶文件上记 md5 会漏掉"测试也变了"这件事。
    tests_digest = tree_digest("simos-app/src/test")
    if REPORTS.exists():
        for stale in REPORTS.glob("*.txt"):
            stale.unlink()

    prod_digest_mutated = tree_digest("simos-app/src/main")
    try:
        proc = subprocess.run(
            ["./mvnw", "-pl", "simos-app", "-am", "test"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=1800,
        )
        log_text = proc.stdout + "\n" + proc.stderr
        rc = proc.returncode
    finally:
        for rel, _old, _new in edits:
            (ROOT / rel).write_bytes(originals[rel])
    prod_digest_restored = tree_digest("simos-app/src/main")
    restored = all(
        md5_of((ROOT / rel).read_bytes()) == backup_md5[rel] for rel, _, _ in edits
    )

    compilation_errors = log_text.count("COMPILATION ERROR")
    reports = (
        sorted(p.name for p in REPORTS.glob("*.txt") if p.stat().st_mtime >= round_start)
        if REPORTS.exists()
        else []
    )
    reds = red_points(log_text)
    verdict = "KILLED" if (rc != 0 and reds) else "SURVIVED"
    if compilation_errors > 0:
        verdict = "VOID"
    elif len(reports) == 0:
        verdict = "VOID"

    record = {
        "id": ident,
        "guard": guard,
        "note": note,
        "target": edits[0][0] if len(edits) == 1 else [e[0] for e in edits],
        "baseline_md5": backup_md5[edits[0][0]],
        "mutant_md5": changed[edits[0][0]],
        "restored_md5": md5_of((ROOT / edits[0][0]).read_bytes()),
        "restored_identical": restored,
        "tests_digest": tests_digest,
        "prod_digest_mutated": prod_digest_mutated,
        "prod_digest_restored": prod_digest_restored,
        "prod_only_target_changed": prod_digest_mutated != prod_digest_restored,
        "compilation_errors": compilation_errors,
        "report_files": len(reports),
        "reports_sample": reports[:6],
        "maven_rc": rc,
        "verdict": verdict,
        "red_points": reds[:8],
    }
    # ★ 自指留痕：这一轮跑的是哪份字节（md5）与证据本身，追加进**本轮日志**；
    #   并把 Maven 原文一并落盘——只有 md5 没有原文，VOID 轮就没法诊断（本装置第一版犯过）。
    with log_path.open("a", encoding="utf-8") as log:
        log.write(log_text)
        log.write("\n\n===== 装置补记（自指）=====\n")
        log.write(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    with (EVID / "summary.jsonl").open("a", encoding="utf-8") as summary:
        summary.write(json.dumps(record, ensure_ascii=False) + "\n")
    return record


def main() -> int:
    EVID.mkdir(parents=True, exist_ok=True)
    args = sys.argv[1:]
    group = "t10"
    if args and args[0] in ("--old", "--t10"):
        group = args.pop(0).lstrip("-")
    only = args
    entries = MUTANTS if group == "t10" else OLD_MUTANTS
    for entry in entries:
        ident, edits, guard = entry[0], entry[1], entry[2]
        note = entry[3] if len(entry) > 3 else ""
        if only and not any(o in ident for o in only):
            continue
        rec = run_round(ident, edits, guard, note)
        print(json.dumps(rec, ensure_ascii=False))
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
