package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>实物资产份额的稳定身份</b>（R3B.1）：一条 {@code AssetShare} = "某主体拥有、某主体实际经营/使用的某种实物资产的多少份额"。
 *
 * <p>★ 拼写规则（唯一拼写点在 {@code AssetShare.idOf(industry, asset, owner, operator, kind, sequence)}）：
 * {@code
 * share-<industry>-<asset>-<owner.kind>-<owner.id>-<operator.kind>-<operator.id>-<kind>-<sequence>}；
 * <b>不含 {@code "."}</b>（同 {@link DebtId} 的地址截断理由）。 {@code sequence} 由状态内确定性计数给出（同一状态重放得到同一批
 * id；不得用随机数/时间戳/UUID）。
 *
 * <p>★ <b>旧档 opaque 兼容</b>：{@link #parse(String)} 只校验非空白、不做格式约束 —— 旧档里 {@code use-…} 形状的 使用权 id
 * 原样可读（不重算、不改写），格式的权威只在 {@code AssetShare.idOf}。
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

  /** 只校验非空白，不做格式约束（格式的权威在 {@code AssetShare.idOf}；旧 {@code use-…} id 原样读入）。 */
  public static AssetShareId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetShareId 不得为空白: " + text);
    }
    return new AssetShareId(text);
  }
}
