#!/usr/bin/env python3
"""九道门禁的变异驱动器（C 阶段）。

每轮：自证原/变异 md5 不同 -> 白名单推成目标类名（就地覆盖 canonical 文件）-> `clean test`
（清陈旧 .class）-> 断言 COMPILATION ERROR=0 且 Tests run>=1 -> 判红 -> cp 逐字节还原 -> 日志自指。
"""
import hashlib
import re
import shutil
import subprocess
import sys

ROOT = "."
EVID = ".superpowers/sdd/2026-09-20-sd-simos/mutants"
LOG = EVID + "/mut-run.log"


def md5(path):
    with open(path, "rb") as handle:
        return hashlib.md5(handle.read()).hexdigest()


MUTANTS = [
    # (id, module, test_selector, file, old, new, expect_marker)
    ("c1m1", "simos-sd", "SdCombatHandlersTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/model/CombatStages.java",
     "if (!last.exit().equals(next.entry())) {",
     "if (!last.exit().equals(last.exit())) {",
     "addStageRejectsBrokenChain"),
    ("c1m3", "simos-sd", "SdCombatHandlersTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/spi/CreateCombatHandler.java",
     "if (!SdSnapshots.unitExists(state, unit)) {",
     "if (unit.value().isEmpty()) {",
     "createCombatRejectsDanglingParticipant"),
    ("c1m4b", "simos-sd", "SdCombatHandlersTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/spi/SetOutcomeTableHandler.java",
     "        stage.maxDurationTicks(),\n        outcomes);",
     "        stage.maxDurationTicks(),\n        stage.outcomes());",
     "setOutcomeTableReplacesWeights"),
    ("c2m1", "simos-sd", "SdCombatHandlersTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/spi/CommitOutcomeHandler.java",
     "if (!inTable(stage, outcomeId)) {",
     "if (!combat.stages().contains(stage)) {",
     "commitOutcomeRejectsOutcomeFromAnotherStage"),
    ("c2m2", "simos-sd", "SdCombatHandlersTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/spi/CommitOutcomeHandler.java",
     "if (combatState.selectedOutcome().isPresent()) {",
     "if (combatState.selectedOutcome().isEmpty()) {",
     "commitOutcomeRejectsSecondSelection"),
    ("c3m1", "simos-sd", "SdCombatHandlersTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/spi/RecordCasualtiesHandler.java",
     "        requireWithinBound(delta, unit);",
     "        delta.personnel();",
     "recordCasualtiesRejectsPersonnelOverBound"),
    ("c3m2", "simos-sd", "SdCombatHandlersTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/spi/RecordCasualtiesHandler.java",
     "if (delta.personnel() < -unit.member()) {",
     "if (delta.personnel() <= -unit.member()) {",
     "recordCasualtiesAcceptsExactPersonnelBound"),
    ("c3m3", "simos-sd", "SdCombatHandlersTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/spi/RecordCasualtiesHandler.java",
     "new LossRecord(recordId, combatId, stageId, atRevision, deltas);",
     "new LossRecord(recordId, combatId, stageId, null, deltas);",
     "recordCasualtiesStoresDeltaWithRevision"),
    ("c4m1", "simos-sd", "SdTimeParticipantTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/time/SdTimeParticipant.java",
     "if (!TriggerEvaluator.evaluate(effect.trigger(), at, base, units, effect.createdTick())) {",
     "if (!effect.trigger().equals(effect.trigger())) {",
     "effectDoesNotFireBeforeTrigger"),
    ("c4m2", "simos-sd", "SdTimeParticipantTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/time/SdTimeParticipant.java",
     "if (effect.status() != EffectStatus.PLANNED && effect.status() != EffectStatus.COMMITTED) {",
     "if (effect.status() == EffectStatus.CANCELLED && effect.status() == EffectStatus.EXPIRED) {",
     "effectDoesNotRepeatOnLaterTick"),
    ("c4m3", "simos-sd", "SdTimeParticipantTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/time/SdTimeParticipant.java",
     "if (!TriggerEvaluator.evaluate(condition, at, base, units, 0L)) {",
     "if (!(at.tick() >= 0)) {",
     "stageAdvancesWhenAtOrAfterTickExitIsSatisfied"),
    ("c4m4", "simos-app", "SimosToolsTest",
     "simos-app/src/main/java/io/mosire/simos/app/Shell.java",
     "new SdTimeParticipant(config.mapId()));",
     "new SdTimeParticipant(config.mapId()), new SdTimeParticipant(config.mapId()));",
     "SimosToolsTest"),
    ("c5m1", "simos-app", "SdCommandDrainTest",
     "simos-app/src/main/java/io/mosire/simos/app/sd/SdCommandDrain.java",
     "      if (drained.contains(commandId)) {\n        continue;\n      }\n",
     "",
     "crossModuleEffectBecomesARealRevisionAndIsIdempotent"),
    ("c5m3", "simos-app", "SdCommandDrainTest",
     "simos-app/src/main/java/io/mosire/simos/app/sd/SdCommandDrain.java",
     "if (effect.status() == EffectStatus.FIRED",
     "if (effect.status() != EffectStatus.CANCELLED",
     "crossModuleEffectBecomesARealRevisionAndIsIdempotent"),
    ("c6m2", "simos-app", "SdCombatEndToEndTest",
     "simos-sd/src/main/java/io/mosire/simos/sd/codec/SdCodec.java",
     "return new SdSnapshot(newMeta.ref(), newMeta.timestamp(), next);",
     "return new SdSnapshot(sdBase.ref(), sdBase.timestamp(), next);",
     "stageAdvanceIsConditionDrivenFrozenAndSelectsExactlyOne"),
]


def main():
    with open(LOG, "w") as log:
        log.write("== mut-run start ==\n")
    for (mid, module, selector, path, old, new, marker) in MUTANTS:
        p = ROOT + "/" + path
        original = open(p).read()
        if old not in original:
            print(f"{mid}: OLD NOT FOUND (VOID) in {path}")
            with open(LOG, "a") as log:
                log.write(f"{mid} VOID old-not-found\n")
            continue
        mutant = original.replace(old, new, 1)
        orig_md5 = hashlib.md5(original.encode()).hexdigest()
        mut_md5 = hashlib.md5(mutant.encode()).hexdigest()
        if orig_md5 == mut_md5:
            print(f"{mid}: mutant identical (VOID)")
            continue
        backup = p + ".orig.bak"
        shutil.copy2(p, backup)
        with open(p, "w") as handle:
            handle.write(mutant)
        pushed_md5 = md5(p)
        assert pushed_md5 == mut_md5, f"{mid}: push md5 mismatch"
        cmd = [
            "./mvnw", "-q", "-pl", module, "-am", "clean", "test",
            f"-Dtest={selector}", "-Dsurefire.failIfNoSpecifiedTests=false",
        ]
        proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
        out = proc.stdout + proc.stderr
        logfile = f"{EVID}/logs/{mid}.log"
        with open(logfile, "w") as handle:
            handle.write(out)
        comp_errors = len(re.findall(r"COMPILATION ERROR", out))
        tests = [int(x) for x in re.findall(r"Tests run: (\d+)", out)]
        tests_run = max(tests) if tests else 0
        red = marker in out and ("<<< FAILURE!" in out or "<<< ERROR!" in out or proc.returncode != 0)
        killed = comp_errors == 0 and tests_run >= 1 and red
        shutil.copy2(backup, p)
        restored_md5 = md5(p)
        assert restored_md5 == orig_md5, f"{mid}: restore md5 mismatch"
        if comp_errors != 0:
            verdict = "VOID(compilation-error)"
        elif tests_run < 1:
            verdict = "VOID(no-tests-ran)"
        elif killed:
            verdict = "KILLED"
        else:
            verdict = "SURVIVED"
        line = (
            f"{mid} verdict={verdict} module={module} selector={selector} "
            f"orig_md5={orig_md5} mutant_md5={mut_md5} pushed_md5={pushed_md5} "
            f"restored_md5={restored_md5} comp_errors={comp_errors} tests_run={tests_run} "
            f"rc={proc.returncode} marker={marker} red={red}\n"
        )
        print(line.strip())
        with open(LOG, "a") as log:
            log.write(line)


if __name__ == "__main__":
    main()
