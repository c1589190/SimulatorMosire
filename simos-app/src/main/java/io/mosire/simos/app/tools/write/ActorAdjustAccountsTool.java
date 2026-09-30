package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code actor.AdjustAccounts} 窄工具（辖区阶段 6 / 计划 §6.2）：**按有符号净增量改 actor 账**的唯一窄写面。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 经 MCP
 * 调用时走审批门链。
 *
 * <p>★ <b>载荷形状</b>：{@code {entries:[{owner:{kind,id}, q, r, goods:{商品:净增量}?,
 * money:{币种:净增量}?}...]}}—— {@code entries} 必填非空；每项 {@code owner/q/r} 必填；{@code goods}/{@code money}
 * 至少一个非空；值是<b>有符号净增量</b> （0 不得出现，无操作条目请删）。
 *
 * <p>★ <b>语义三条</b>：整条原子（任一违例 ⇒ 全拒，不做部分生效）；缺账 + 纯正增量 ⇒ 新建该账；负增量不得使余额 &lt; 0、 <b>不得侵占冻结额</b>（可支配 = 余额
 * − 冻结）。
 *
 * <p>★ 工具层不做前置校验（那份校验能被 {@code simos.command.submit} 绕过 ⇒ 是装饰），拒绝理由由域层具名给、经 {@code
 * ToolSupport.fold} 变成可读的 {@code REJECTED}。
 */
public final class ActorAdjustAccountsTool extends AbstractNarrowWriteTool {

  public static final String NAME = "actor.AdjustAccounts";

  public ActorAdjustAccountsTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "调整 actor 账目 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "调整 actor 账目：固定 actor.AdjustAccounts，载荷"
        + " {entries:[{owner:{kind,id}, q, r, goods:{商品:有符号净增量}?, money:{币种:有符号净增量}?}]}"
        + "（★ entries 必填非空；每项 owner/q/r 必填；goods/money 至少一个非空；"
        + "值 = 有符号净增量、不得为 0；同一 (owner,q,r) 不得重复；"
        + "缺账只允许纯正增量并新建；负增量不得使余额 < 0、不得侵占冻结额（可支配 = 余额 − 冻结）；整条原子，任一违例全拒）";
  }
}
