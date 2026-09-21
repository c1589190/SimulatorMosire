#!/usr/bin/env python3
"""m4（brief §7 的可选项，两半都要报）：给 `SdSetDecisionMakerProviderTool` 加**工具层 provider 存在性校验**。

期望（**修正后的预测**，见派单 §4）：
  ① `SdProviderBindingEndToEndTest` **保持绿** —— 它走 `core.submit(envelope(...))`，**绕过工具层**；
  ② 用**通用写** `simos.command.submit` 传一个从未登记的 `providerId` ⇒ **仍然 Committed**。

★ 两件事一起推：生产类（工具层校验）+ `SimosToolsTest` 里一个**临时**的旁路探针方法。
  `mut-round.sh` 事后从 pristine 逐字节还原**两个**文件（含探针），故探针不会被提交。
★ 之所以要探针：② 的命题是「通用写绕过工具层校验」，这需要一个**真调通用写**的用例才测得到。
"""
import os
import sys

WT = sys.argv[1]
TOOL_REL = (
    "simos-app/src/main/java/io/mosire/simos/app/tools/write/SdSetDecisionMakerProviderTool.java"
)
TEST_REL = "simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java"

TOOL_OLD_IMPORTS = "import io.mosire.simos.core.CoreSimos;\nimport java.util.Map;\n"
TOOL_NEW_IMPORTS = (
    "import io.mosire.agentlib.tool.ToolContext;\n"
    "import io.mosire.agentlib.tool.ToolResult;\n"
    "import io.mosire.simos.core.CoreSimos;\n"
    "import java.util.Map;\n"
)

TOOL_OVERRIDE = '''
  /** 变异体 m4：工具层「provider 存在性」校验 —— §4 明令禁止的形态（对通用写毫无约束 ⇒ 装饰）。 */
  @Override
  public ToolResult execute(ToolContext context) {
    Object raw = context.arguments().get("payloadJson");
    String payload = raw instanceof String text ? text : "";
    if (!payload.contains("\\"providerId\\":\\"p-registered\\"")) {
      return ToolResult.error("REJECTED", "provider 未注册（工具层校验）");
    }
    return super.execute(context);
  }
}
'''

PROBE = '''  /**
   * ★★ m4 变异轮的**旁路探针**（临时方法，跑完即随基线字节还原）：给 {@code SdSetDecisionMakerProviderTool}
   * 加上工具层 provider 存在性校验之后——
   *
   * <p>① 经**窄工具**提交 ⇒ 被工具层拦下（REJECTED，且理由来自工具层）；
   * ② 同一条命令、同一个从未登记的 {@code providerId}，经**通用写** {@code simos.command.submit} 提交 ⇒
   * 工具层的校验**碰都碰不到**，仍然 Committed、head 真的前进。
   *
   * <p>⇒ 工具层校验 = 装饰（§4 的原文理由：那份校验能被通用写绕过）。
   */
  @Test
  void m4BypassProbeGenericWriteStillCommitsAnUnregisteredProvider() throws Exception {
    ToolResult region =
        callNarrowWrite(
            MapCreateRegionTool.NAME,
            "{\\"regionId\\":\\"t3-region\\",\\"name\\":\\"甲区\\",\\"hexes\\":[{\\"q\\":1,\\"r\\":1}],"
                + "\\"meta\\":{\\"tag\\":\\"nation:n1\\"}}",
            1L);
    assertThat(region.success()).as(region.message()).isTrue();
    ToolResult nation =
        callNarrowWrite(
            SdCreateNationTool.NAME,
            "{\\"nationId\\":\\"n1\\",\\"name\\":\\"甲国\\",\\"homeRegionId\\":\\"t3-region\\","
                + "\\"adminBudgetPerTick\\":10}",
            2L);
    assertThat(nation.success()).as(nation.message()).isTrue();
    ToolResult maker =
        callNarrowWrite(
            SdCreateDecisionMakerTool.NAME,
            "{\\"id\\":\\"dm-1\\",\\"affiliation\\":{\\"kind\\":\\"nation\\",\\"id\\":\\"n1\\"},"
                + "\\"allowedTools\\":[\\"sd.SubmitVerdict\\"],\\"cadence\\":5}",
            3L);
    assertThat(maker.success()).as(maker.message()).isTrue();

    String payload = "{\\"decisionMakerId\\":\\"dm-1\\",\\"providerId\\":\\"p-never-registered\\"}";
    long headBefore = shell.coreSimos().head(main()).orElseThrow().value();

    ToolResult viaNarrow =
        callNarrowWrite(SdSetDecisionMakerProviderTool.NAME, payload, headBefore);
    assertThat(viaNarrow.code())
        .as("m4 ①：变异体的工具层校验确实拦住了窄工具面")
        .isEqualTo("REJECTED");
    assertThat(viaNarrow.message()).as("m4 ①：拦下的理由出自工具层").contains("provider 未注册");

    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "sd.SetDecisionMakerProvider");
    args.put("payloadJson", payload);
    args.put("branch", "main");
    args.put("expectedRevision", headBefore);
    ToolResult viaGenericWrite = call("simos.command.submit", args);
    assertThat(viaGenericWrite.success())
        .as(
            "m4 ②：通用写绕过工具层校验 ⇒ 从未登记的 providerId 照样提交成功 —— %s",
            viaGenericWrite.message())
        .isTrue();
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("m4 ②：head 真的前进了（不是「没报错」）")
        .isEqualTo(headBefore + 1);
  }

'''


def patch(rel, pairs, tail=None, count=1):
    path = os.path.join(WT, rel)
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    for old, new in pairs:
        hits = text.count(old)
        if hits != count:
            raise SystemExit("VOID: %s 里期望 %d 处锚点，实得 %d 处" % (rel, count, hits))
        text = text.replace(old, new, count)
    if tail is not None:
        old, new = tail
        hits = text.count(old)
        if hits != 1:
            raise SystemExit("VOID: %s 里期望 1 处尾部锚点，实得 %d 处" % (rel, hits))
        text = text.replace(old, new, 1)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)
    print("  edited %s" % rel)


patch(TOOL_REL, [(TOOL_OLD_IMPORTS, TOOL_NEW_IMPORTS), ("\n}\n", TOOL_OVERRIDE)])
MARKER = "  // ────────────────────────────── 夹具 ──────────────────────────────"
patch(
    TEST_REL,
    [
        (
            MARKER,
            PROBE + MARKER,
        )
    ],
)
