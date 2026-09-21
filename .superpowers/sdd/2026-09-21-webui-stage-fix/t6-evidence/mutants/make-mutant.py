#!/usr/bin/env python3
"""T6 变异体生成器：按 id 对 pristine 文件做定点改动，写出同规范名目标文件。

用法：make-mutant.py <id> <pristine 源> <目标路径>
每处替换断言"恰好命中 N 次"（N 缺省 1），否则非零退出（防静默不生效 ⇒ 假存活）。
"""
import sys
import pathlib

ROUND, SRC, DST = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
text = SRC.read_text(encoding="utf-8")

# 每条 = (old, new, expected_hits)
REPLACEMENTS = {
    # t6m1：adjudicationDisclosure 的 WITHHELD 早退去掉 ⇒ WITHHELD 不再隐藏判决
    #   ⇒ RedactingQueryServiceTest.withheldDisclosureHidesEveryVerdict / RedactionApiTest 红。
    "t6m1": [
        (
            "    if (policy == DisclosurePolicy.WITHHELD) {\n"
            "      return List.of();\n"
            "    }\n",
            "",
            1,
        )
    ],
    # t6m2：redactedFields 不再生效（原样返回）⇒ position 不被剔除
    #   ⇒ redactedFieldsRemovesTheNamedFieldFromUnitViews / positionIsRedactedForOneActorButPresentForAnother 红。
    "t6m2": [
        (
            "    if (scope.redactedFields().isEmpty()) {\n"
            "      return body;\n"
            "    }\n"
            "    return strip(body, scope.redactedFields());\n",
            "    return body;\n",
            1,
        )
    ],
    # t6m3：未接 redaction 的端点不再拒绝 as= ⇒ 静默返回全量（fail-closed 失效）
    #   ⇒ endpointsWithoutRedactionRejectTheAsParameter 红。
    "t6m3": [
        (
            "    if (asPresent) {\n"
            '      throw new IllegalArgumentException(\n'
            '          "读端点 " + path + " 未接入 data redaction，不支持 as= 视角参数（fail-closed）");\n'
            "    }\n",
            "",
            1,
        )
    ],
    # t6m4：PERCEPTION_ONLY 也带上模型内部量（恒 FULL 字段集）⇒ perceptionOnlyDropsTheNonObservableVerdictFields 红。
    "t6m4": [
        (
            "      if (policy == DisclosurePolicy.FULL) {\n",
            "      if (true) {\n",
            1,
        )
    ],
    # t6m5：按地址取单格的可见性 fail-closed 失效（不可见的格照回 200）⇒ invisibleHexIsNotFoundUnderAs 红。
    "t6m5": [
        (
            "    if (asPresent && !redactingQueryService.seesHex(actor, target, coord)) {\n"
            '      // fail-closed：不可见的格与"不存在"同形（都不给存在性侧信道）。\n'
            "      Map<String, Object> body = ApiViews.hexCoord(coord);\n"
            '      body.put("error", "hex not found");\n'
            "      return Reply.of(404, body);\n"
            "    }\n",
            "",
            1,
        )
    ],
    # t6r-t5m5（裁定 42 重派生）：决策人列表路由整段去掉 ⇒ 该端点落 404
    #   ⇒ SdDecisionMakerApiTest 的列表用例红。旧靶串已被 T6 重写 ⇒ 按新字节重新派生同一语义。
    "t6r-t5m5": [
        (
            "    if (path.equals(DECISION_MAKERS_PATH)) {\n"
            "      rejectAs(path, asPresent);\n"
            "      return decisionMakersReply(params);\n"
            "    }\n",
            "",
            1,
        )
    ],
    # t6r-d4m1（裁定 42 重跑）：mapOverview 的 hex 过滤去掉（靶串逐字未变）
    #   ⇒ twoScopesSeeDifferentHexesOnTheSameEndpoint 红。
    "t6r-d4m1": [
        ('    full.put("hexes", filterHexes(full.get("hexes"), scope));\n', "", 1)
    ],
}

changed = 0
for old, new, expected in REPLACEMENTS[ROUND]:
    hits = text.count(old)
    if hits != expected:
        sys.exit("replacement matched %d times (want %d): %r" % (hits, expected, old[:70]))
    text = text.replace(old, new)
    changed += 1

DST.write_text(text, encoding="utf-8")
print("mutant=%s replacements=%d" % (ROUND, changed))
