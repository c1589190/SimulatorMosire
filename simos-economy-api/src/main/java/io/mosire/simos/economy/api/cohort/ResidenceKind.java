package io.mosire.simos.economy.api.cohort;

import io.mosire.simos.economy.api.id.PeopleLotId;
import java.util.List;

/**
 * ★★ <b>居住类型：农村 / 城镇</b>（2026-09-27 裁定 R-N1-A 的落点；H0.2）。
 *
 * <p>★★ <b>它为什么必须存在</b>（一个会「假绿」的坑，当场读码核到的）：播种器对
 * {@code farm} / {@code weave} / {@code craft} <b>三个产业都用同一套四阶层</b>
 * （{@code EconomySeeder.CLASS_IDS}）与同一组份额，而同一格的<b>城镇人口是独立批次</b>
 * （{@code urban:} 前缀）⇒ 在"家户 = {@code (格, 阶层)}"的键下，<b>农村贫农与城镇贫农会并成同一本账</b>，
 * 于是"农村的余粮 + 城市的缺口"并到一起 ⇒ <b>城市不再饿死，但不是因为修好了通道，而是因为账合并了</b>。
 * 那正是 {@code AGENT.md} §9.1 的"假绿"，且恰好会长在"城市缺口收敛"这条新判据上。
 *
 * <p>★★ <b>批次前缀的唯一拼写点在本类</b>：{@code rural:} / {@code urban:} 这两个前缀原本只在
 * {@code simos-social} 的 {@code PopulationLots} 里拼，而 <b>{@code simos-economy} 看不见
 * {@code simos-social}</b>（铁律 3），却又必须从劳动配额表的批次 id 推出"这批人住哪种居住类型"
 * ⇒ 若两处各写一份前缀，就是同一个格式的<b>两处拼写点</b>（本仓明令禁止）。
 * 故把前缀与"批次 → 居住类型"的判定放在 {@link PeopleLotId} 的<b>同模块邻居</b>这里，
 * {@code PopulationLots} 改为引用本类（**它不再自己拼前缀**）。
 *
 * <p>★ <b>规范字面量 = {@code "rural"} / {@code "urban"}</b>（与批次 id 的前缀同字面，不是枚举名）——
 * 它进 {@link CohortKey} 的规范串，故大小写敏感、不做归一（归一是"猜"）。
 */
public enum ResidenceKind {
  /** 农村（村镇）。 */
  RURAL("rural"),

  /** 城镇（城市）。 */
  URBAN("urban");

  private final String value;

  ResidenceKind(String value) {
    this.value = value;
  }

  /** 规范字面量（{@code "rural"} / {@code "urban"}）：既是规范串的那一段，也是批次 id 的前缀词。 */
  public String value() {
    return value;
  }

  /** 批次 id 的前缀（含冒号）：{@code "rural:"} / {@code "urban:"}。 */
  public String lotPrefix() {
    return value + ":";
  }

  /** 词表（保序：农村 → 城镇）；由枚举常量派生，供遍历与断言用。 */
  public static List<ResidenceKind> all() {
    return List.of(values());
  }

  /**
   * 按规范字面量解析（<b>大小写敏感</b>，同 {@code ActorKind.parse} 的口径：不归一，归一即猜）。
   *
   * @throws IllegalArgumentException 空白、或不在词表里（消息列出合法值）
   */
  public static ResidenceKind parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ResidenceKind 不得为空白: " + text);
    }
    for (ResidenceKind kind : values()) {
      if (kind.value.equals(text)) {
        return kind;
      }
    }
    throw new IllegalArgumentException(
        "未知 ResidenceKind: " + text + "；合法值: " + List.of("rural", "urban"));
  }

  /**
   * ★ <b>批次 → 居住类型</b>（按前缀判定，唯一拼写点）：{@code rural:…} ⇒ {@link #RURAL}，
   * {@code urban:…} ⇒ {@link #URBAN}。
   *
   * <p>★ <b>两个前缀互不为前缀</b>（{@code "rural:"} 与 {@code "urban:"} 在首个字符就分岔）⇒ 判定无歧义，
   * 不需要按长度排序。
   *
   * <p>★★ <b>认不出来 ⇒ 抛</b>（fail-closed）：静默给一个默认居住类型，会让"农村的钱发给城镇家户"
   * 这种错<b>无人察觉</b>；而这条判定的下游是"哪个家户收到实物报酬"。
   *
   * @throws IllegalArgumentException 批次 id 不以任一前缀开头
   */
  public static ResidenceKind ofLot(PeopleLotId lot) {
    if (lot == null) {
      throw new IllegalArgumentException("PeopleLotId 不得为 null（居住类型由它决定）");
    }
    String text = lot.value();
    for (ResidenceKind kind : values()) {
      if (text.startsWith(kind.lotPrefix())) {
        return kind;
      }
    }
    throw new IllegalArgumentException(
        "批次 id 不以居住前缀开头，无法判定居住类型: "
            + text
            + "（合法前缀: "
            + List.of(RURAL.lotPrefix(), URBAN.lotPrefix())
            + "）");
  }
}
