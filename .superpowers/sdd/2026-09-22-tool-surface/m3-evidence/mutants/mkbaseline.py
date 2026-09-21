#!/usr/bin/env python3
"""M3 变异轮的地基：把「基线字节」存档成 pristine/，产出 baseline.md5 与 write/ 的白名单。

★ 为什么要 pristine 而不是 git：本轮改动**尚未提交**，`git checkout --` 会退到 BASE、
  把我全部的测试改动一起抹掉。⇒ 基线 = 本脚本运行时刻的工作树字节，逐字节 cp 存档。
★ write/ 白名单：变异轮的纪律是「先把规范名之外的 .java 清掉」，白名单就是**基线时刻**
  该目录下的文件名全集（m3 会往里塞一个孤立文件，它必须能被清掉）。
"""
import hashlib
import os
import shutil

WT = "/home/dev/SimulatorMosire/.claude/worktrees/ts+m1"
EV = os.path.join(WT, ".superpowers/sdd/2026-09-22-tool-surface/m3-evidence")
MUT = os.path.join(EV, "mutants")
PRISTINE = os.path.join(MUT, "pristine")

WRITE_DIR = "simos-app/src/main/java/io/mosire/simos/app/tools/write"

# —— 本任务产出的全部文件（12 新工具类 + 接线点 + §6(f) 提示 + 三个测试文件）——
SD_TOOLS = [
    "SdCreateNationTool.java",
    "SdCreateArmyTool.java",
    "SdCreateDecisionMakerTool.java",
    "SdPutInfoTool.java",
    "SdCreateCombatTool.java",
    "SdAddCombatStageTool.java",
    "SdSetStageOutcomeTableTool.java",
    "SdCommitCombatOutcomeTool.java",
    "SdRecordCasualtiesTool.java",
    "SdRegisterEffectTool.java",
    "SdCancelEffectTool.java",
    "SdSetDecisionMakerProviderTool.java",
]

FILES = [
    "simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java",
    "simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java",
    "simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java",
    "simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java",
    "simos-app/src/test/java/io/mosire/simos/app/McpPortTopologyTest.java",
] + [WRITE_DIR + "/" + name for name in SD_TOOLS]


def md5(path):
    with open(path, "rb") as handle:
        return hashlib.md5(handle.read()).hexdigest()


def main():
    os.makedirs(PRISTINE, exist_ok=True)
    os.makedirs(os.path.join(MUT, "logs"), exist_ok=True)
    lines = []
    for rel in FILES:
        src = os.path.join(WT, rel)
        if not os.path.isfile(src):
            raise SystemExit("缺文件（本任务的产物清单与磁盘不一致）: " + rel)
        dst = os.path.join(PRISTINE, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
        lines.append("%s  %s" % (md5(src), rel))
    with open(os.path.join(MUT, "baseline.md5"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")

    # write/ 白名单 = 基线时刻该目录下 .java 的文件名全集（按名排序）
    names = sorted(
        name for name in os.listdir(os.path.join(WT, WRITE_DIR)) if name.endswith(".java")
    )
    with open(os.path.join(MUT, "write-whitelist.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(names) + "\n")

    print("baseline.md5 => %d 个文件；write-whitelist.txt => %d 个类" % (len(lines), len(names)))
    print("baseline.md5 的 md5 = %s" % md5(os.path.join(MUT, "baseline.md5")))
    for line in lines:
        print("  " + line)


if __name__ == "__main__":
    main()
