package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>市场区 ID</b>（阶段 2-B2，2026-10-08；约束设计书 §4.2 / 不变量 I22）：一个<b>市场区</b>的稳定身份。
 *
 * <p>★★ <b>为什么市场区需要身份</b>：B2 之前"市场区"是**纯派生件**（城市 + tier 半径现算，见 {@code
 * MarketTopologyBook}）——"这个 hex 属于哪个区"每次现算，因而不可能被治理（划界/退让/覆盖/合并都没有可写的对象）。落成持久状态之后，
 * 区必须先有身份，才能被命令面点名（{@code economy.DefineMarketZone} / {@code ReassignZoneHexes} / {@code
 * MergeMarketZones}）。
 *
 * <p>★ <b>形制与 {@link CurrencyId} / {@link MarketId} 逐字相同</b>（同族的稳定 ID：单参构造 + {@code toString()} 裸值 +
 * {@code parse} 逆 + 空白即抛）—— 同一族的东西不许有两套写法。★ 它<b>不</b>带格式约束（例如 {@code zone-} 前缀）：id 由命令层给，
 * 派生规则属命令层，不是身份本身的约束（{@code MarketId} 的既有口径）。
 *
 * <p>★ <b>id 里不得含 {@code '|'}</b>（编码/账户分段符；与 {@code GovernmentIds} 同族）。区 id 会出现在日志与读数里，
 * 含分隔符会让"一行一条读数"这种消费方式产生歧义。
 */
public record MarketZoneId(String value) {

  public MarketZoneId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MarketZoneId 不得为空白");
    }
    if (value.indexOf('|') >= 0) {
      throw new IllegalArgumentException("MarketZoneId 不得含 '|'（分段符，读数的行格式会因此产生歧义）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白（与非空分隔符），不做格式约束（分配器属命令层）。 */
  public static MarketZoneId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("MarketZoneId 不得为空白: " + text);
    }
    return new MarketZoneId(text);
  }
}
