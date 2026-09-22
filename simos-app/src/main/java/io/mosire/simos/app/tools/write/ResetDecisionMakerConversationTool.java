package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code sd.ResetDecisionMakerConversation} 窄工具：**GM 专用**——把某个决策人的 LLM 会话**重置为新会话**（标
 * sensitive，走审批）。
 *
 * <p>★ **与 {@code sd.RunDecision} 同形**（窄工具、命令类型固定、载荷由模型写）：两者都只在 GM 面，都要走审批链。
 *
 * <p>★★ **它治的是现场真出现过的病**：改版前落盘的老会话在回放时会 400（历史里没有 {@code reasoning_content}，补不上）⇒
 * 处置只能是"换个新会话重开"。在那之前唯一的做法是**去动库/删文件**——那既绕过铁律 2，也把审计凭据销毁了。本工具把那件事变成一条**真命令**： 世代
 * +1、旧会话**一个字节都不动**、下一次它自己从空上下文重新开始。
 *
 * <p>★ **载荷只有 {@code decisionMakerId}**（没有"指定一个新会话 id"这种字段）：让 GM 能指定 id，就等于让一个决策人能被指到**别人的**
 * 会话上（冒名/串话），而这正是"计数器"天然没有的面。
 *
 * <p>★ **{@code NAME} 必须是字面量**（与 sd 侧 handler 的 {@code type()} 一样）：两侧各有一条**派生式同源判据**按源码字面量扫描
 * ——窄写工具集合 == GM 桶的窄写工具集合（{@code SimosToolsTest}）、catalog 的 type 集合 == 全部 handler 的 {@code
 * type()}。它们才是把三处名字钉在一起的东西（写成常量引用会让扫描器当场判"抽不到"）。
 */
public final class ResetDecisionMakerConversationTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.ResetDecisionMakerConversation";

  public ResetDecisionMakerConversationTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "GM 重置决策人会话 payload="
        + args.get("payloadJson")
        + " branch="
        + args.get("branch")
        + " expected="
        + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 重置：固定 sd.ResetDecisionMakerConversation，载荷 {decisionMakerId}——"
        + "把该决策人的 LLM 会话换一段新的（世界事实里的会话世代 +1）。"
        + "它下一轮从**空上下文**重新开始；旧会话**一条字节都不删**（可审计）。"
        + "用途：旧会话回放坏掉（如缺少 reasoning_content）时重开一段。";
  }
}
