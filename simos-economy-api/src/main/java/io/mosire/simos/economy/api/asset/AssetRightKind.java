package io.mosire.simos.economy.api.asset;

import java.util.Arrays;

/**
 * ★★ <b>资产权利性质词表</b>（OWNED / TENANCY / COMMUNAL）—— <b>全仓唯一拼写点</b>。
 *
 * <p>★★ <b>为什么它上移到 economy-api</b>（D-032 B2c，裁定八）：这个三值词表原先嵌在 {@code
 * io.mosire.simos.economy.model.OwnershipStake.RightKind} 里，而 B2c 把「方案声明的资产接受面」 （{@code
 * acceptedRightKinds}）搬进了 production 的 {@code IndustryDefinitionView} —— production 只允许 依赖 {@code
 * simos-economy-api}，看不见 economy 的实现包。**上移而不是复制**：同一份词表两处各写一份 必然漂开（本仓「同一个东西两处各写一份」那一族），故 {@code
 * OwnershipStake} 也改为引用本类型，旧的嵌套枚举 <b>整体删除</b>（不留别名、不留兼容位）。
 *
 * <p>★ <b>各档语义</b>（R3B.1 起未变）：{@code OWNED} = 自有（旧档一对一迁移/default 物化的默认档）； {@code TENANCY} =
 * 租佃（{@code owner != operator} 时的常见档；地租仍在 {@code ProductionRules} 结算）； {@code COMMUNAL} = 公地/共同使用。★
 * 本类型只表达权利性质，<b>不</b>表达数量约束，也不含任何分配公式。
 */
public enum AssetRightKind {
  /** 自有（旧档一对一迁移/default 物化的默认档）。 */
  OWNED,
  /** 租佃（{@code owner != operator} 时的常见档；地租仍在 {@code ProductionRules} 结算）。 */
  TENANCY,
  /** 公地/共同使用。 */
  COMMUNAL;

  /** 按词表解析；词表外即抛并列出合法值（不默认、不归一 —— 同 {@code LaborSource} 的口径）。 */
  public static AssetRightKind parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetRightKind 不得为空白: " + text);
    }
    try {
      return valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 AssetRightKind: " + text + "；合法值: " + Arrays.toString(values()));
    }
  }
}
