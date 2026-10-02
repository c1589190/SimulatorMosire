package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ D5（2026-10-02 / R5）「给 XXX 政府钱」的**金额校验**判据：三项资源至少一个 {@code > 0}、都不得为负 —— 且这一步
 * **在任何世界查询之前**（付款人身份 / 收款 GOV / 国库账都还没碰）就具名拒。
 *
 * <p>★ 判别力（本用例是"静默付 0"变异体的靶子）：把 {@link GovPayTool} 的 {@code amountViolation} 去掉或改成"允许三项全 0" ⇒ 下面的
 * {@code BAD_REQUEST} + 文案断言当场红；若把校验挪到查询之后，空的测试世界会先炸成别的错误码，也红。
 *
 * <p>★ 本用例**不需要真世界**：三条断言都落在"世界查询之前"的分支上；第三条（非零金额）只证明"越过校验之后才走到世界面"，
 * 用错误码与校验文案区分两种失败，避免"工具整体不可用"伪装成"校验有效"。
 */
class GovPayToolTest {

  @TempDir Path tempDir;

  @Test
  void allZeroAmountsAreRejectedByTheAmountGateBeforeAnyWorldLookup() {
    withTool(
        tool -> {
          ToolResult result = tool.execute(context(args("g-1", 0L, 0L, 0L)));
          assertThat(result.success()).as(result.message()).isFalse();
          assertThat(result.code()).as("三项全 0 是参数错误（BAD_REQUEST），不是世界面失败").isEqualTo("BAD_REQUEST");
          assertThat(result.message()).contains("至少一个必须 > 0");
        });
  }

  @Test
  void negativeAmountsAreRejectedByTheAmountGateBeforeAnyWorldLookup() {
    withTool(
        tool -> {
          ToolResult result = tool.execute(context(args("g-1", -1L, 0L, 0L)));
          assertThat(result.success()).as(result.message()).isFalse();
          assertThat(result.code()).isEqualTo("BAD_REQUEST");
          assertThat(result.message()).contains("都不得为负").contains("grain=-1");
        });
  }

  @Test
  void onePositiveResourcePassesTheAmountGateAndFailsOnlyOnTheWorldFace() {
    withTool(
        tool -> {
          ToolResult result = tool.execute(context(args("g-missing", 0L, 0L, 1L)));
          assertThat(result.success()).isFalse();
          // 世界面为空 ⇒ 后续可能再报别的错（空头 revision 也会落 BAD_REQUEST）——关键是**不得**再是
          // 金额校验的那两句文案；否则本用例就成了"工具整体不可用"的假红。
          assertThat(result.message())
              .as("非零金额必须越过金额校验（世界面失败另算）")
              .doesNotContain("至少一个必须 > 0")
              .doesNotContain("都不得为负");
        });
  }

  /** 起一个真壳（空 store）并把真 {@link GovPayTool} 交给回调——工具只依赖 core/query，验证分支不碰世界。 */
  private void withTool(java.util.function.Consumer<GovPayTool> body) {
    try (Shell shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0))) {
      body.accept(
          new GovPayTool(shell.coreSimos(), shell.queryService(), "decision-maker:dm-test"));
    }
  }

  private static Map<String, Object> args(String toGovId, long grain, long cloth, long money) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("toGovId", toGovId);
    args.put("grain", grain);
    args.put("cloth", cloth);
    args.put("money", money);
    return args;
  }

  private static ToolContext context(Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args);
  }
}
