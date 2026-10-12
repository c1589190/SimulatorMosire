package io.mosire.simos.gov.nation;

/**
 * 国家 ID（调用方给的短名，spec §二.1）。**裸值 {@code toString()} + {@code static parse} 三件套**（铁律 1）：它是 id 的
 * **地址形式**，供变更集的 String key 与 gov 侧使用，**不是**调试输出。★ 与 {@code RegionId} 分离——国家区域用 tag 关联（R13）。
 * 不自增、不用随机 UUID。
 *
 * <p>★★ <b>2026-10-12 批次 3c：本类型自 {@code io.mosire.simos.sd.id} 整体迁入 {@code
 * io.mosire.simos.gov.nation}</b> （用户裁定「{@code NationId} 落点 → <b>GOV模块</b>」＋「外交是 gov
 * 的事」）；形状与拒因文案一字未改。
 *
 * <p>★ <b>它是 {@code Affiliation.Nation} 那个不透明引用的解释目标</b>：决策人的国家归属在 sd 侧只剩一个 {@code String}（sd 不得依赖
 * gov），把那个串解释成本类型、并裁定"这个国家确实存在"的地方是 gov（{@link NationAffiliations}）； 读不到 ⇒ 该决策人的 nation 面
 * deny-all（见 app 侧 {@code NationScope}）。
 */
public record NationId(String value) {

  public NationId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("NationId 不得为空白");
    }
  }

  /** 裸值。见类注释：它是**地址形式**，不是给人看的调试输出。 */
  @Override
  public String toString() {
    return value;
  }

  /** 解析 {@link #toString()} 的产物；空白与 {@code null} 抛（与构造器同口径：宁抛不静默）。 */
  public static NationId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("NationId 不得为空白: " + text);
    }
    return new NationId(text);
  }
}
