#!/usr/bin/env python3
"""A3~A6 变异轮装置（九道门禁）：逐变异体备份/改源/自证 md5/跑门禁/还原/自证 md5，并把本轮 md5 追加进日志本身。"""

import hashlib
import os
import subprocess
import sys

WT = os.environ["WT"]
EVID = ".superpowers/sdd/2026-09-20-sd-simos"

SD = "simos-sd/src/main/java/io/mosire/simos/sd"
CORE = "simos-core/src"
APP = "simos-app/src"

MUTANTS = [
    {
        "name": "a3-m1-between-info-unchanged",
        "file": f"{SD}/change/SdChangeSet.java",
        "old": "        FieldDelta.diff(base.info(), target.info()));",
        "new": "        new FieldDelta.Unchanged<List<SdInfoEntry>>());",
        "module": "simos-sd",
        "test": "SdRoundTripTest",
        "cls": "SdRoundTripTest",
        "logdir": f"{EVID}/a3-evidence/mutants/logs",
    },
    {
        "name": "a3-m2-apply-combats-ignored",
        "file": f"{SD}/change/SdChangeSet.java",
        "old": "        FieldDelta.rebuild(base.combats(), cs.combats(), CombatId::parse),",
        "new": "        base.combats(),",
        "module": "simos-sd",
        "test": "SdRoundTripTest",
        "cls": "SdRoundTripTest",
        "logdir": f"{EVID}/a3-evidence/mutants/logs",
    },
    {
        "name": "a3-m3-architecture-drop-sd-path",
        "file": f"{CORE}/test/java/io/mosire/simos/core/ArchitectureGuardsTest.java",
        "old": '            "simos-sd/src/main/java/io/mosire/simos/sd/change/SdChangeSet.java",\n',
        "new": "",
        "module": "simos-core",
        "test": "ArchitectureGuardsTest",
        "cls": "ArchitectureGuardsTest",
        "logdir": f"{EVID}/a3-evidence/mutants/logs",
    },
    {
        "name": "a3-m4-codec-no-nationid-key",
        "file": f"{SD}/codec/SdCodec.java",
        "old": "    module.addKeyDeserializer(NationId.class, keyDeserializer(NationId::parse));\n",
        "new": "",
        "module": "simos-sd",
        "test": "SdCodecTest",
        "cls": "SdCodecTest",
        "logdir": f"{EVID}/a3-evidence/mutants/logs",
    },
    {
        "name": "a3-m4b-codec-raw-cast",
        "file": f"{SD}/codec/SdCodec.java",
        "old": (
            "    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {\n"
            "      throw new IllegalStateException(\n"
            "          \"sd codec 的切片不是 SdSnapshot: \"\n"
            "              + (snapshot == null ? \"null\" : snapshot.getClass().getName()));\n"
            "    }\n"
            "    return sdSnapshot;"
        ),
        "new": "    return (SdSnapshot) snapshot;",
        "module": "simos-sd",
        "test": "SdCodecTest",
        "cls": "SdCodecTest",
        "logdir": f"{EVID}/a3-evidence/mutants/logs",
    },
    {
        "name": "a4-m1-createnation-no-tag-check",
        "file": f"{SD}/spi/CreateNationHandler.java",
        "old": "      if (!NationTag.isNationTag(region.meta().tag())) {",
        "new": "      if (false) {",
        "module": "simos-sd",
        "test": "CreateNationHandlerTest",
        "cls": "CreateNationHandlerTest",
        "logdir": f"{EVID}/a4-evidence/mutants/logs",
    },
    {
        "name": "a4-m2-createdm-no-n9-check",
        "file": f"{SD}/spi/CreateDecisionMakerHandler.java",
        "old": "      if (allowedTools.contains(SdCommandNames.SIMOS_COMMAND_SUBMIT)) {",
        "new": "      if (false) {",
        "module": "simos-sd",
        "test": "CreateDecisionMakerHandlerTest",
        "cls": "CreateDecisionMakerHandlerTest",
        "logdir": f"{EVID}/a4-evidence/mutants/logs",
    },
    {
        "name": "a4-m3-resolver-fabricates-candidate",
        "file": f"{SD}/resolve/SdResolver.java",
        "old": "    if (id.isEmpty() || !exists.test(id.get())) {",
        "new": "    if (id.isEmpty()) {",
        "module": "simos-sd",
        "test": "SdResolverTest",
        "cls": "SdResolverTest",
        "logdir": f"{EVID}/a4-evidence/mutants/logs",
    },
    {
        "name": "a5-m1-putinfo-skips-address-parse",
        "file": f"{SD}/spi/PutInfoHandler.java",
        "old": '      Address address = SdPayloads.requireAddress(payload, "address");',
        "new": '      Address address = Address.parse("map:Map1");',
        "module": "simos-sd",
        "test": "PutInfoHandlerTest",
        "cls": "PutInfoHandlerTest",
        "logdir": f"{EVID}/a5-evidence/mutants/logs",
    },
    {
        "name": "a5-m2-apply-info-ignored",
        "file": f"{SD}/change/SdChangeSet.java",
        "old": "        FieldDelta.rebuild(base.info(), cs.info(), Function.identity()));",
        "new": "        base.info());",
        "module": "simos-sd",
        "test": "PutInfoHandlerTest",
        "cls": "PutInfoHandlerTest",
        "logdir": f"{EVID}/a5-evidence/mutants/logs",
    },
    {
        "name": "a6-m1-shell-no-guard-registration",
        "file": f"{APP}/main/java/io/mosire/simos/app/Shell.java",
        "old": "    coreSimos.register(new RegionDeleteGuard());\n",
        "new": "",
        "module": "simos-app",
        "test": "SdRegionDeleteGuardEndToEndTest",
        "cls": "SdRegionDeleteGuardEndToEndTest",
        "logdir": f"{EVID}/a6-evidence/mutants/logs",
    },
    {
        "name": "a6-m2-regiondeleteguard-always-reject",
        "file": f"{SD}/guard/RegionDeleteGuard.java",
        "old": "    if (regionId.isEmpty()) {\n      return Optional.empty();\n    }",
        "new": "    if (regionId.isEmpty()) {\n      return Optional.empty();\n    }\n    if (true) {\n      return Optional.of(\"恒拒\");\n    }",
        "module": "simos-sd",
        "test": "RegionDeleteGuardTest",
        "cls": "RegionDeleteGuardTest",
        "logdir": f"{EVID}/a6-evidence/mutants/logs",
    },
    {
        "name": "a6-m3-guard-runs-after-handler",
        "file": f"{CORE}/main/java/io/mosire/simos/core/command/CommandBus.java",
        "old": (
            "    for (MutationGuard guard : guards) {\n"
            "      Optional<String> rejection = guard.rejection(state, envelope.type(), envelope.payloadJson());\n"
            "      if (rejection.isPresent()) {\n"
            "        return reject(envelope, trace, rejection.get());\n"
            "      }\n"
            "    }\n"
            "\n"
            "    return switch (handler.get().handle(state, envelope.payloadJson())) {"
        ),
        "new": (
            "    HandlerOutcome outcome = handler.get().handle(state, envelope.payloadJson());\n"
            "    for (MutationGuard guard : guards) {\n"
            "      Optional<String> rejection = guard.rejection(state, envelope.type(), envelope.payloadJson());\n"
            "      if (rejection.isPresent()) {\n"
            "        return reject(envelope, trace, rejection.get());\n"
            "      }\n"
            "    }\n"
            "\n"
            "    return switch (outcome) {"
        ),
        "module": "simos-core",
        "test": "CommandBusGuardTest",
        "cls": "CommandBusGuardTest",
        "logdir": f"{EVID}/a6-evidence/mutants/logs",
    },
]


def md5(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def run_one(m):
    path = os.path.join(WT, m["file"])
    with open(path, "rb") as fh:
        orig = fh.read()
    orig_md5 = md5(orig)
    needle = m["old"].encode()
    if orig.count(needle) != 1:
        return f"SKIP {m['name']}: needle count = {orig.count(needle)}"
    mutated = orig.replace(needle, m["new"].encode(), 1)
    mutant_md5 = md5(mutated)
    if mutant_md5 == orig_md5:
        return f"SKIP {m['name']}: mutant bytes identical to orig"
    os.makedirs(os.path.join(WT, m["logdir"]), exist_ok=True)
    log_path = os.path.join(WT, m["logdir"], m["name"] + ".log")
    try:
        with open(path, "wb") as fh:
            fh.write(mutated)
        cmd = [
            "./mvnw", "-o", "-q", "-pl", m["module"], "-am",
            "-Dtest=" + m["test"], "-Dsurefire.failIfNoSpecifiedTests=false",
            "-Dcheckstyle.skip=true", "-Dspotless.check.skip=true", "-Dspotbugs.skip=true",
            "test",
        ]
        proc = subprocess.run(cmd, cwd=WT, capture_output=True, text=True)
        out = proc.stdout + proc.stderr
    finally:
        with open(path, "wb") as fh:
            fh.write(orig)
    restored_md5 = md5(open(path, "rb").read())
    compile_errors = out.count("COMPILATION ERROR")
    failing = [
        line.strip()
        for line in out.splitlines()
        if "<<< FAILURE!" in line or "<<< ERROR!" in line
    ]
    red = proc.returncode != 0 and compile_errors == 0 and len(failing) > 0
    landed = any(m["cls"] in line for line in failing)
    with open(log_path, "w") as fh:
        fh.write(out)
        fh.write("\n# --- 装置自记（本轮的字节）---\n")
        fh.write(f"# mutant={m['name']}\n")
        fh.write(f"# orig_md5={orig_md5}\n")
        fh.write(f"# mutant_md5={mutant_md5}\n")
        fh.write(f"# restored_md5={restored_md5}\n")
        fh.write(f"# rc={proc.returncode} compile_errors={compile_errors} failing_lines={len(failing)}\n")
        for line in failing:
            fh.write(f"# RED: {line}\n")
    verdict = "KILLED" if (red and landed) else ("RED-WRONG-LOCATION" if red else "SURVIVED")
    return (
        f"{verdict:12s} {m['name']:42s} rc={proc.returncode} compile_errors={compile_errors} "
        f"failing={len(failing)} restored_eq_orig={restored_md5 == orig_md5}"
    )


def main():
    wanted = sys.argv[1:]
    results = []
    for m in MUTANTS:
        if wanted and m["name"] not in wanted:
            continue
        results.append(run_one(m))
        print(results[-1], flush=True)
    killed = sum(1 for r in results if r.startswith("KILLED"))
    survived = sum(1 for r in results if r.startswith("SURVIVED"))
    wrong = sum(1 for r in results if r.startswith("RED-WRONG"))
    print(f"\nSUMMARY killed={killed} survived={survived} wrong_location={wrong} total={len(results)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
