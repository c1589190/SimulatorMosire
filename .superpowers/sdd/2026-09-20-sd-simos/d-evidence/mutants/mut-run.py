#!/usr/bin/env python3
"""D 阶段变异装置（九道门禁）。

每个变异体：干净世界自证（原 md5）→ 生成变异体（字节不同）→ 原地覆盖 canonical 文件 →
`clean test`（清陈旧 .class）→ 断言 COMPILATION ERROR=0 且 Tests run>=1 → 判红落被保护断言 →
`cp` 逐字节还原并复验 md5 → 日志自指（本轮 md5 追加进日志）。
"""

import hashlib
import os
import re
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", ".."))
LOG_DIR = os.path.join(os.path.dirname(__file__), "logs")
SUMMARY = os.path.join(os.path.dirname(__file__), "mut-run.log")

# (id, module, file, old, new, test_class, expected_failing_method)
MUTANTS = [
    (
        "d1m1-r4-removed",
        "simos-sd",
        "simos-sd/src/main/java/io/mosire/simos/sd/spi/IssueDirectiveHandler.java",
        "      Optional<String> violation = firstR4Violation(base, decisionMakerId, tick);",
        "      Optional<String> violation = Optional.empty();",
        "IssueDirectiveHandlerTest",
        "rejectsSecondDirectiveForSameMakerAndTick",
    ),
    (
        "d1m2-generic-write-allowed",
        "simos-sd",
        "simos-sd/src/main/java/io/mosire/simos/sd/spi/DirectiveWhitelist.java",
        "      if (type.equals(SdCommandNames.SIMOS_COMMAND_SUBMIT)) {\n"
        "        continue; // 禁通用写（N9 同族）\n"
        "      }\n",
        "",
        "IssueDirectiveHandlerTest",
        "whitelistDropsSdSelfReferenceAndGenericSubmit",
    ),
    (
        "d1m3-self-reference-allowed",
        "simos-sd",
        "simos-sd/src/main/java/io/mosire/simos/sd/spi/DirectiveWhitelist.java",
        "      if (type.startsWith(\"sd.\")) {\n"
        "        continue; // 禁自指（spec §四）\n"
        "      }\n",
        "",
        "IssueDirectiveHandlerTest",
        "whitelistDropsSdSelfReferenceAndGenericSubmit",
    ),
    (
        "d2m1-exception-escapes",
        "simos-sd",
        "simos-sd/src/main/java/io/mosire/simos/sd/adjudication/LlmDecisionAdjudicator.java",
        "    try {\n"
        "      raw =\n"
        "          client.complete(\n"
        "              new LlmRequest(breakpoint, systemPrompt(breakpoint), request.redactedBriefJson()));\n"
        "    } catch (RuntimeException e) {\n"
        "      return new Judgement.Failed(\n"
        "          \"LLM 调用失败（降级）：\" + e.getClass().getSimpleName() + \": \" + e.getMessage());\n"
        "    }\n",
        "    raw =\n"
        "        client.complete(\n"
        "            new LlmRequest(breakpoint, systemPrompt(breakpoint), request.redactedBriefJson()));\n",
        "AdjudicationTest",
        "llmTimeoutDegradesToFailedWithoutEscaping",
    ),
    (
        "d2m2-rationale-check-removed",
        "simos-sd",
        "simos-sd/src/main/java/io/mosire/simos/sd/adjudication/AdjudicationSchemas.java",
        "    requireText(node, \"rationaleText\");\n",
        "",
        "AdjudicationTest",
        "schemaValidatesRequiredFieldsPerBreakpoint",
    ),
    (
        "d3m1-subject-surface-removed",
        "simos-sd",
        "simos-sd/src/main/java/io/mosire/simos/sd/adjudication/VerdictFreezer.java",
        "    if (!Breakpoints.acceptsVerdictSubject(breakpoint, subject)) {\n"
        "      throw new IllegalArgumentException(\n"
        "          \"subject 不在断点 \"\n"
        "              + breakpoint.value()\n"
        "              + \" 的裁决面（判决只裁决 sd:combat.*）: \"\n"
        "              + subject.canonical());\n"
        "    }\n",
        "",
        "SubmitVerdictHandlerTest",
        "rejectsSubjectOutsideTheBreakpointSurface",
    ),
    (
        "d3m2-duplicate-verdict-allowed",
        "simos-sd",
        "simos-sd/src/main/java/io/mosire/simos/sd/spi/SubmitVerdictHandler.java",
        "      if (base.verdicts().containsKey(id)) {\n"
        "        return new HandlerOutcome.Rejected(\"判决已存在: \" + id.value());\n"
        "      }\n",
        "",
        "SubmitVerdictHandlerTest",
        "rejectsDuplicateVerdictId",
    ),
    (
        "d4m1-hex-redaction-removed",
        "simos-app",
        "simos-app/src/main/java/io/mosire/simos/app/query/RedactingQueryService.java",
        "    full.put(\"hexes\", filterHexes(full.get(\"hexes\"), scope));\n",
        "",
        "RedactingQueryServiceTest",
        "twoScopesSeeDifferentHexesOnTheSameEndpoint",
    ),
    (
        "d4m2-viewscope-not-applied",
        "simos-sd",
        "simos-sd/src/main/java/io/mosire/simos/sd/spi/SetViewScopeHandler.java",
        "      next.put(id, updated);",
        "      next.put(id, existing);",
        "SetViewScopeHandlerTest",
        "writesViewScopeOntoTheDecisionMaker",
    ),
    (
        "d5m1-actor-check-removed",
        "simos-sd",
        "simos-sd/src/main/java/io/mosire/simos/sd/channel/ChannelAdmission.java",
        "    if (declared == null || !declared.contains(actor)) {\n"
        "      throw new IllegalArgumentException(\"actor 不在该渠道声明可代表的集合里（N16）: \" + actor.value());\n"
        "    }\n",
        "",
        "ChannelAdmissionTest",
        "forgedActorIsRejectedByTheModule",
    ),
    (
        "d6m1-gm-bucket-generic-write",
        "simos-app",
        "simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java",
        "      case GM -> {\n        built.add(new IssueDirectiveTool(core, initiator, mapId));",
        "      case GM -> {\n        built.add(new CommandSubmitTool(core, initiator, mapId));\n        built.add(new IssueDirectiveTool(core, initiator, mapId));",
        "SimosToolsTest",
        "roleBucketsNeverCarryGenericWrite",
    ),
    (
        "d7m1-d1d3-split",
        "simos-app",
        "simos-sd/src/main/java/io/mosire/simos/sd/adjudication/Breakpoints.java",
        "    return List.of(\n"
        "        List.of(D1, D3),\n"
        "        List.of(D2),\n"
        "        List.of(D4),\n"
        "        List.of(D5),\n"
        "        List.of(D6),\n"
        "        List.of(D7),\n"
        "        List.of(D8));",
        "    return List.of(\n"
        "        List.of(D1),\n"
        "        List.of(D3),\n"
        "        List.of(D2),\n"
        "        List.of(D4),\n"
        "        List.of(D5),\n"
        "        List.of(D6),\n"
        "        List.of(D7),\n"
        "        List.of(D8));",
        "AdjudicatorRunnerTest",
        "d1AndD3AreOneCallAndTheVerdictIsFrozen",
    ),
    (
        "d7m2-failure-interrupts-tick",
        "simos-app",
        "simos-app/src/main/java/io/mosire/simos/app/sd/AdjudicatorRunner.java",
        "      results.add(judgement);\n      if (judgement instanceof Judgement.Accepted accepted",
        "      results.add(judgement);\n      if (judgement instanceof Judgement.Failed failed) {\n        throw new IllegalStateException(failed.reason());\n      }\n      if (judgement instanceof Judgement.Accepted accepted",
        "AdjudicatorRunnerTest",
        "failedBreakpointDoesNotStopTheTick",
    ),
]


def md5(path):
    with open(path, "rb") as handle:
        return hashlib.md5(handle.read()).hexdigest()


def append(line):
    with open(SUMMARY, "a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def main():
    os.makedirs(LOG_DIR, exist_ok=True)
    append("=== D 阶段变异轮开始 " + time.strftime("%Y-%m-%dT%H:%M:%S") + " ===")
    killed = 0
    survived = []
    only = set(sys.argv[1:])
    for mid, module, rel, old, new, test_class, expected in MUTANTS:
        if only and mid not in only:
            continue
        path = os.path.join(ROOT, rel)
        orig = open(path, "rb").read()
        orig_md5 = md5(path)
        text = orig.decode("utf-8")
        if old not in text:
            append("[%s] VOID: 锚点未命中 %s" % (mid, rel))
            survived.append(mid + "(anchor-miss)")
            continue
        mutant_text = text.replace(old, new, 1)
        mutant_bytes = mutant_text.encode("utf-8")
        mutant_md5 = hashlib.md5(mutant_bytes).hexdigest()
        if mutant_md5 == orig_md5:
            append("[%s] VOID: 变异体与原字节相同" % mid)
            survived.append(mid + "(no-change)")
            continue
        # 门禁 3：按白名单推成目标类名（原地覆盖 canonical 文件）
        with open(path, "wb") as handle:
            handle.write(mutant_bytes)
        pushed_md5 = md5(path)
        assert pushed_md5 == mutant_md5, mid
        log_path = os.path.join(LOG_DIR, mid + ".log")
        start = time.time()
        try:
            proc = subprocess.run(
                [
                    "./mvnw",
                    "-pl",
                    module,
                    "-am",
                    "-Dtest=" + test_class,
                    "-Dsurefire.failIfNoSpecifiedTests=false",
                    "clean",
                    "test",
                ],
                cwd=ROOT,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=560,
            )
            output = proc.stdout
        finally:
            # 门禁 8：cp 逐字节还原（绝不 git checkout）
            with open(path, "wb") as handle:
                handle.write(orig)
        restored_md5 = md5(path)
        with open(log_path, "w", encoding="utf-8") as handle:
            handle.write(output)
            handle.write(
                "\n[device] mutant=%s orig_md5=%s pushed_md5=%s restored_md5=%s\n"
                % (mid, orig_md5, mutant_md5, restored_md5)
            )
        compilation_errors = output.count("COMPILATION ERROR")
        tests_run = [int(m) for m in re.findall(r"Tests run: (\d+), Failures", output)]
        max_tests = max(tests_run) if tests_run else 0
        failures = [
            (int(f), int(e))
            for f, e in re.findall(r"Tests run: \d+, Failures: (\d+), Errors: (\d+)", output)
        ]
        is_red = any(f > 0 or e > 0 for f, e in failures)
        red_on_expected = is_red and (expected in output)
        restored_ok = restored_md5 == orig_md5
        verdict = "KILLED" if red_on_expected and compilation_errors == 0 else "SURVIVED"
        if verdict == "KILLED":
            killed += 1
        else:
            survived.append(mid)
        append(
            "[%s] %s module=%s test=%s expected=%s red=%s compilation_errors=%d tests_run=%d "
            "orig=%s mutant=%s restored_ok=%s elapsed=%.1fs"
            % (
                mid,
                verdict,
                module,
                test_class,
                expected,
                red_on_expected,
                compilation_errors,
                max_tests,
                orig_md5,
                mutant_md5,
                restored_ok,
                time.time() - start,
            )
        )
        print("%s -> %s (comp_err=%d tests=%d)" % (mid, verdict, compilation_errors, max_tests))
    append("=== 汇总：KILLED=%d SURVIVED=%s ===" % (killed, survived))
    print("KILLED=%d SURVIVED=%s" % (killed, survived))
    if survived:
        sys.exit(2)


if __name__ == "__main__":
    main()
