#!/usr/bin/env python3
"""T11 + T11B 变异轮驱动（自指留痕）。

每条变异体：备份原件（md5）→ 落变异体（md5 + 断言与原件**字节不同**）→ 删本轮 surefire 报告并记本轮起点
→ 跑 `./mvnw -pl simos-app -am test`（**整条上游链**，避免"只跑某类"把别的漏掉而误判存活）
→ 判 VOID（编译错 / 本轮报告数 0）→ 提取红点用例名 → 逐字节还原并核 md5 → 把上面每一项**追加进本轮日志本身**。

★ report_files=0 ⇒ VOID（"没跑到"不许读成"存活"，本仓纪律形态 1 与 6）。
★ 判据全部**自指**：每一轮的 baseline_md5 / mutant_md5 / restored_md5 / restored_identical /
  compilation_errors / report_files / red_assertions 都写进 `logs/<id>.log` 自己身上——
  装置自己的产物也要能自证（M4 Task 12 的教训）。
★ 条目形态：(id, [(相对路径, 原件片段, 变异片段), …], 破坏的护栏)。
"""
import hashlib
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path("/home/cna/SimulatorMosire")
EVID = ROOT / ".superpowers/sdd/2026-09-22-agent-permission/t11b-evidence/mutants"
LOGS = EVID / "logs"
BACKUPS = EVID / "orig"

APP_MAIN = "simos-app/src/main/java/io/mosire/simos/app"
APP_TEST = "simos-app/src/test/java/io/mosire/simos/app"
SD_MAIN = "simos-sd/src/main/java/io/mosire/simos/sd"

REPORTS = [
    ROOT / "simos-app/target/surefire-reports",
    ROOT / "simos-sd/target/surefire-reports",
]

# ── 本轮新护栏的变异体 ────────────────────────────────────────────────────────────────
MUTANTS = [
    (
        "m1-neighbours-include-own-nation",
        [
            (
                f"{APP_MAIN}/access/NeighborNations.java",
                """          for (String nation : HexOwner.nationsOf(map, index, adjacent)) {
            if (!nation.equals(nationId.value())) {
              neighbors.add(nation); // ★ 本国自己贴着自己，必须排除
            }
          }""",
                """          for (String nation : HexOwner.nationsOf(map, index, adjacent)) {
            neighbors.add(nation);
          }""",
            )
        ],
        "邻国把本国算进去（'不含本国'那条判据）",
    ),
    (
        "m2-hex-owner-ignores-tag-prefix",
        [
            (
                f"{APP_MAIN}/access/HexOwner.java",
                """      if (NationTag.isNationTag(tag)) {
        nations.add(nationIdOf(tag));
      }""",
                """      if (tag != null) {
        nations.add(tag);
      }""",
            )
        ],
        "归属国不看 nation: 前缀（supply / null 也算成国家）",
    ),
    (
        "m3-hex-owner-assumes-unique",
        [
            (
                f"{APP_MAIN}/access/HexOwner.java",
                """    Set<String> nations = new TreeSet<>();
    for (RegionId owner : index.regionOf(coord)) {
      Region region = map.regions().get(owner);
      if (region == null) {
        continue;
      }
      String tag = region.meta().tag();
      if (NationTag.isNationTag(tag)) {
        nations.add(nationIdOf(tag));
      }
    }""",
                """    Set<String> nations = new TreeSet<>();
    for (RegionId owner : index.regionOf(coord)) {
      Region region = map.regions().get(owner);
      if (region == null) {
        continue;
      }
      String tag = region.meta().tag();
      if (NationTag.isNationTag(tag)) {
        nations.add(nationIdOf(tag));
        break; // 只回第一个
      }
    }""",
            )
        ],
        "归属国假定唯一（只回第一个区域的国家 ⇒ M8-U1 多对多被抹掉）",
    ),
    (
        "m4-runner-does-not-persist",
        [
            (
                f"{APP_MAIN}/decision/DecisionAgentRunner.java",
                """      conversations.append(conversationId, assistant);
      history.add(assistant);""",
                """      history.add(assistant);""",
            )
        ],
        "运行流不落会话（assistant 不进 ConversationStore）",
    ),
    (
        "m5-runner-fakes-tool-execution",
        [
            (
                f"{APP_MAIN}/decision/DecisionAgentRunner.java",
                """    ToolResult result =
        callerFactory.execute(registry, call.name(), dm, state, mapId, call.arguments());""",
                """    ToolResult result =
        ToolResult.ok("{\\"result\\":\\"committed\\",\\"terrain\\":\\"desert\\"}");""",
            )
        ],
        "工具不真执行、只回一段编好的结果（「看起来在调工具」）",
    ),
    (
        "m6-runner-ignores-history",
        [
            (
                f"{APP_MAIN}/decision/DecisionAgentRunner.java",
                """    List<LlmMessage> history = new ArrayList<>(conversations.load(conversationId));""",
                """    List<LlmMessage> history = new ArrayList<>();""",
            )
        ],
        "运行流不喂历史（load(cid) 读了但不用 ⇒ 跨 tick 断掉）",
    ),
    (
        "m7-no-signature-check",
        [
            (
                f"{APP_MAIN}/tools/write/AbstractNarrowWriteTool.java",
                """    Optional<String> violation = signatureViolation(context);
    if (violation.isPresent()) {
      return ToolResult.error("REJECTED", violation.get());
    }""",
                """    Optional<String> violation = Optional.empty();""",
            )
        ],
        "不校验署名（决策人 A 可以落一条署名 B 的 directive）",
    ),
    (
        "m8-signature-always-violation",
        [
            (
                f"{SD_MAIN}/spi/DecisionSignature.java",
                """    return signatureOf(payloadJson)
        .filter(signature -> !signature.equals(caller))""",
                """    return signatureOf(payloadJson)
        .filter(signature -> true)""",
            )
        ],
        "署名判据写反（本人也拒 ⇒ 决策人出不了令）",
    ),
    (
        "m9-tool-defs-silent-gap",
        [
            (
                f"{APP_MAIN}/decision/DecisionToolDefs.java",
                """    List<String> missing =
        allowed.stream().filter(name -> registry.find(name).isEmpty()).sorted().toList();""",
                """    List<String> missing = List.of();""",
            )
        ],
        "白名单缺项不再响亮失败（静默少给工具）",
    ),
    (
        "m10-tool-defs-ignore-whitelist",
        [
            (
                f"{APP_MAIN}/decision/DecisionToolDefs.java",
                """    for (String name : new TreeSet<>(allowed)) {
      AgentTool tool = registry.find(name).orElse(null);
      if (tool != null) {
        defs.add(ToolDefs.of(tool));
      }
    }""",
                """    for (String name : new TreeSet<>(registry.list().stream().map(AgentTool::name).toList())) {
      AgentTool tool = registry.find(name).orElse(null);
      if (tool != null) {
        defs.add(ToolDefs.of(tool));
      }
    }""",
            )
        ],
        "工具面不按白名单取交（把**整个注册表**交给模型）",
    ),
    (
        "m11-hex-view-loses-nation",
        [
            (
                f"{APP_MAIN}/access/HexOwner.java",
                """    return Collections.unmodifiableSet(nations);
  }

  /**
   * {@code nation:<id>} → {@code <id>}。""",
                """    nations.clear();
    return Collections.unmodifiableSet(nations);
  }

  /**
   * {@code nation:<id>} → {@code <id>}。""",
            )
        ],
        "归属国家恒为空（判据 J6 消失：hex 视图与纯函数都不再给出归属）",
    ),
    (
        "m12-viewer-nation-undispatched",
        [
            (
                f"{APP_MAIN}/access/DecisionCallerFactory.java",
                """    return decisionMakerOf(context, state)
        .map(DecisionMaker::affiliation)
        .filter(Affiliation.Nation.class::isInstance)
        .map(nation -> ((Affiliation.Nation) nation).nationId());""",
                """    return decisionMakerOf(context, state)
        .map(
            maker ->
                maker.affiliation() instanceof Affiliation.Nation nation
                    ? nation.nationId()
                    : new NationId(maker.id().value()));""",
            )
        ],
        "邻国不过身份分派（军队决策人也拿到这一项）",
    ),
    (
        "m13-no-turn-budget",
        [
            (
                f"{APP_MAIN}/decision/DecisionAgentRunner.java",
                """      if (llmCalls >= maxLlmCalls) {""",
                """      if (false) {""",
            )
        ],
        "回合预算被拿掉（跑飞的模型不再被中止）",
    ),
    (
        "m14-runner-executes-without-permission-chain",
        [
            (
                f"{APP_MAIN}/access/DecisionCallerFactory.java",
                """    return authorizer.execute(Objects.requireNonNull(registry, "registry"), toolName, ctx);""",
                """    return Objects.requireNonNull(registry, "registry")
        .find(toolName)
        .orElseThrow()
        .execute(ctx);""",
            )
        ],
        "绕过权限链直接执行工具（判定链五段全不走 ⇒ 白名单形同虚设）",
    ),
]


def md5(path: Path) -> str:
    return hashlib.md5(path.read_bytes()).hexdigest()


def clear_reports() -> None:
    for reports in REPORTS:
        if reports.exists():
            shutil.rmtree(reports)


def report_files_since(start: float, reports: Path) -> list:
    """本轮内被写过的 surefire 报告（mtime ≥ 本轮起点）——陈旧报告不算数（纪律形态 6）。"""
    fresh = []
    if not reports.exists():
        return fresh
    for txt in sorted(reports.glob("*.txt")):
        if txt.stat().st_mtime >= start:
            fresh.append(txt)
    return fresh


def red_assertions(reports: list) -> list:
    """从本轮报告里提取红点（失败/错误的用例名 + 首行消息）。"""
    reds = []
    for txt in reports:
        body = txt.read_text(errors="replace")
        test_set = txt.name
        for line in body.splitlines():
            if "<<< FAILURE!" in line or "<<< ERROR!" in line:
                reds.append(f"{test_set}::{line.split(' -- ')[0].strip()}")
        for line in body.splitlines():
            if line.startswith("Tests run:") and ("Failures: 0" not in line or "Errors: 0" not in line):
                if "Failures: 0, Errors: 0" not in line:
                    reds.append(f"{test_set}::{line.strip()}")
    return reds


def run_round(mutant_id: str, edits: list, guard: str) -> dict:
    LOGS.mkdir(parents=True, exist_ok=True)
    BACKUPS.mkdir(parents=True, exist_ok=True)
    log_path = LOGS / f"{mutant_id}.log"
    record = {
        "mutant": mutant_id,
        "guard": guard,
        "baseline_md5": {},
        "mutant_md5": {},
        "restored_md5": {},
        "restored_identical": None,
        "compilation_errors": None,
        "target_module": None,
        "report_files": None,
        "app_report_files": None,
        "sd_report_files": None,
        "maven_rc": None,
        "red_assertions": [],
        "verdict": None,
    }

    # ① 备份 + 落变异体
    for rel, old, new in edits:
        path = ROOT / rel
        original = path.read_bytes()
        if original.count(old.encode()) != 1:
            record["verdict"] = "VOID(snippet-not-unique)"
            _append(log_path, record)
            return record
        backup = BACKUPS / f"{mutant_id}__{Path(rel).name}"
        backup.write_bytes(original)
        record["baseline_md5"][rel] = hashlib.md5(original).hexdigest()
        mutated = original.replace(old.encode(), new.encode())
        path.write_bytes(mutated)
        record["mutant_md5"][rel] = md5(path)
        if record["mutant_md5"][rel] == record["baseline_md5"][rel]:
            record["verdict"] = "VOID(mutant-identical-to-original)"
            _append(log_path, record)
            return record

    # ② 清干净世界：删旧报告（陈旧的假红/假绿都不许用）
    clear_reports()
    start = time.time()

    # ③ 跑整条上游链
    proc = subprocess.run(
        ["./mvnw", "-pl", "simos-app", "-am", "test"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    log = proc.stdout + proc.stderr
    record["maven_rc"] = proc.returncode
    record["compilation_errors"] = len(re.findall(r"COMPILATION ERROR", log))
    (LOGS / f"{mutant_id}.mvn.log").write_text(log)

    app_reports = report_files_since(start, ROOT / "simos-app/target/surefire-reports")
    sd_reports = report_files_since(start, ROOT / "simos-sd/target/surefire-reports")
    record["app_report_files"] = len(app_reports)
    record["sd_report_files"] = len(sd_reports)
    record["report_files"] = len(app_reports) + len(sd_reports)
    # ★★ **靶模块必须有本轮报告**：只看"总报告数 ≠ 0"会被**另一个模块**的报告蒙过去——
    #   m10 首轮实测：变异体让 `TreeSet` 成了未用 import ⇒ Checkstyle 在 surefire **之前**把
    #   simos-app 拦下（`SimosApp FAILURE [0.135 s]`）⇒ 0 个 app 报告，而 simos-sd 的 21 个报告
    #   照常新鲜 ⇒ 旧判据把"**根本没跑到**"读成了 **SURVIVED**（本仓纪律形态 1："没跑到"≠"没红"）。
    target = "sd" if all(rel.startswith("simos-sd/") for rel, _o, _n in edits) else "app"
    target_reports = sd_reports if target == "sd" else app_reports
    record["target_module"] = target
    if record["report_files"] == 0 or not target_reports:
        record["verdict"] = (
            f"VOID(no-fresh-report-for-target-module:{target}; app={len(app_reports)}"
            f" sd={len(sd_reports)})"
        )
    elif record["compilation_errors"] > 0:
        record["verdict"] = "VOID(compilation-error)"
    else:
        record["red_assertions"] = red_assertions(app_reports + sd_reports)
        record["verdict"] = "KILLED" if record["red_assertions"] else "SURVIVED"

    # ④ 逐字节还原 + 自证
    identical = True
    for rel, _old, _new in edits:
        path = ROOT / rel
        backup = BACKUPS / f"{mutant_id}__{Path(rel).name}"
        shutil.copyfile(backup, path)
        record["restored_md5"][rel] = md5(path)
        if record["restored_md5"][rel] != record["baseline_md5"][rel]:
            identical = False
    record["restored_identical"] = identical

    _append(log_path, record)
    return record


def _append(log_path: Path, record: dict) -> None:
    lines = [
        "─" * 72,
        f"round_utc      : {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}",
        f"mutant         : {record['mutant']}",
        f"guard          : {record['guard']}",
        f"baseline_md5   : {record['baseline_md5']}",
        f"mutant_md5     : {record['mutant_md5']}",
        f"restored_md5   : {record['restored_md5']}",
        f"restored_identical: {record['restored_identical']}",
        f"compilation_errors: {record['compilation_errors']}",
        f"target_module  : {record.get('target_module')}",
        f"report_files   : {record['report_files']} (app={record.get('app_report_files')} "
        f"sd={record.get('sd_report_files')})",
        f"maven_rc       : {record['maven_rc']}",
        f"red_assertions : {record['red_assertions']}",
        f"verdict        : {record['verdict']}",
    ]
    with log_path.open("a") as handle:
        handle.write("\n".join(lines) + "\n")


def main() -> int:
    only = sys.argv[1:] if len(sys.argv) > 1 else None
    results = []
    for mutant_id, edits, guard in MUTANTS:
        if only and mutant_id not in only:
            continue
        print(f"[round] {mutant_id} …", flush=True)
        record = run_round(mutant_id, edits, guard)
        print(f"        -> {record['verdict']} {record['red_assertions'][:2]}", flush=True)
        results.append(record)
    killed = sum(1 for r in results if r["verdict"] == "KILLED")
    survived = [r["mutant"] for r in results if r["verdict"] == "SURVIVED"]
    voids = [r["mutant"] for r in results if str(r["verdict"]).startswith("VOID")]
    print(f"\nTOTAL={len(results)} KILLED={killed} SURVIVED={survived} VOID={voids}")
    return 0 if not survived and not voids else 1


if __name__ == "__main__":
    sys.exit(main())
