package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>实物资产份额的稳定身份</b>（R3B.1）：一条 {@code OwnershipStake} = "某主体拥有、某主体实际经营/使用的某种实物资产的多少份额"。
 *
 * <p>★ 拼写规则（唯一拼写点在 {@code OwnershipStake.idOf(industry, asset, owner, operator, kind, location,
 * sequence)}）： {@code
 * share-<industry>-<asset>-<owner.kind>-<owner.id>-<operator.kind>-<operator.id>-<kind>-<location>-<sequence>}；
 * <b>不含 {@code "."}</b>（同 {@link DebtId} 的地址截断理由）。 {@code location} 是规范 {@code <q>_<r>}； {@code
 * sequence} 由状态内确定性计数给出（同一状态重放得到同一批 id；不得用随机数/时间戳/UUID）。
 *
 * <p>★ {@link #parse(String)} 只校验非空白（Alpha 不兼容旧档；新世界所有 id 由 {@code OwnershipStake.idOf} 生成）。
 */
public record AssetShareId(String value) {

  public AssetShareId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("AssetShareId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白（格式的权威在 {@code OwnershipStake.idOf}；Alpha 不兼容旧档）。 */
  public static AssetShareId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetShareId 不得为空白: " + text);
    }
    return new AssetShareId(text);
  }
}
