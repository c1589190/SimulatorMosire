package io.mosire.simos.app;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>测试侧 GM 面调用桥</b>（2026-10-14 GM⇄DM 测试批新增；<b>只在测试树里</b>）。
 *
 * <p>★ <b>为什么需要它</b>：GM 工具（{@code simos.gm.*}）的调用入口是 {@code
 * shell.gmToolAuthorizer().execute(shell.toolRegistry(), name, ctx)}，而 {@code ctx} 必须由 GM 身份 + GM
 * 权限集构成 —— 那两样在 {@link Shell#gmCaller()} 里现算。{@code Shell.gmCaller()} 是<b>包内可见</b>的
 * （类注明写「包内可见：这是装配自检与用例的读回口径，**不是**对外 API」）⇒ 住在 {@code io.mosire.simos.app.decision}
 * 等子包里的用例**够不着**它。
 *
 * <p>★ <b>本类做与不做的事</b>：<b>只转发</b>——身份、权限、上下文形状全部照抄生产装配（与 {@code DmMessagingEndToEndTest}
 * 内联的那几行逐字同形），<b>不替调用者放宽任何东西</b>（不放行审批、 不跳过资源断言、不伪造身份）。它存在的唯一理由是 Java 的包可见性，不是为了让用例变绿。
 *
 * <p>★ <b>为什么不是"把 {@code Shell.gmCaller()} 改成 public"</b>：那是改生产代码（本批红线：只动 {@code
 * src/test/**}），而且会把一条明确的"读回口径不是对外 API"的边界抹掉。测试侧桥的代价小得多。
 */
public final class GmToolCallBridge {

  private GmToolCallBridge() {}

  /**
   * 以**真 GM 身份**经真 {@code gmToolAuthorizer} 调一条 GM 面工具。
   *
   * @param shell 真壳（装配完成）
   * @param toolName 工具名（{@code simos.gm.*} 一族）
   * @param args 工具载荷（本方法自动补 {@code branch=main} 与 {@code expectedRevision=head}，与既有用例同形）
   * @return 工具结果原文（{@code code()} / {@code message()} 都是真的）
   */
  public static ToolResult call(Shell shell, String toolName, Map<String, ?> args) {
    ToolContext gm = Shell.gmCaller();
    Map<String, Object> full = new LinkedHashMap<>(args);
    full.put("branch", "main");
    // ★ expectedRevision 取**当下 head**（与 DmMessagingEndToEndTest.gmCall 逐字同形）：
    //   GM 读口与写口都要它，且"过期 ⇒ 冲突"这条语义不能被本桥悄悄绕掉。
    full.put(
        "expectedRevision",
        shell
            .coreSimos()
            .head(new io.mosire.simos.util.state.BranchId("main"))
            .orElseThrow()
            .value());
    ToolContext call =
        new ToolContext(
            gm.caller(), gm.permissions(), gm.config(), full, gm.identity(), gm.resources());
    return shell.gmToolAuthorizer().execute(shell.toolRegistry(), toolName, call);
  }
}
