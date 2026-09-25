package io.mosire.simos.util.economy;

/**
 * 跨模块共用的**经济词表**：全仓恰一份，由源扫描护栏钉住（{@code EconomyVocabularyGuardTest}）。
 *
 * <p>★★ **为什么在 {@code simos-util} 而不是 {@code simos-economy-api}**：军队（{@code simos-unit}）将来也要按同一
 * 口径吃粮，而 {@code simos-unit} 只依赖 util + map ⇒ **只有 util 是 economy 与 unit 都能看见的共同上游**。
 *
 * <p>★ **本类只放 {@code String} 与原生类型**：util 不能依赖 economy-api（方向相反），故 {@code CommodityId} 那一层留在
 * economy 侧，由 {@link #GRAIN_COMMODITY_ID} 构造。
 *
 * <p>★ {@link #DAILY_GRAIN_MILLI_PER_PERSON} 是**临时居所**：v2 spec §四 的参数目录（增量 V7）落地后，它迁入 {@code
 * economy} 切片的 {@code worldParams} 并成为 GM 可调参数；届时本类只留商品 id。
 */
public final class EconomyVocabulary {

  /** 粮的商品 id（v2 spec §3.2：**粮与种子是同一个商品**，不设 {@code seed}）。 */
  public static final String GRAIN_COMMODITY_ID = "grain";

  /** 每人每日口粮（毫粮）：10 粮/周期 ÷ 120 天 = 83.33 ⇒ 取 83（残差分派见 v2 spec §八.6）。 */
  public static final long DAILY_GRAIN_MILLI_PER_PERSON = 83L;

  /** 1 粮 = 1000 毫粮（库存按最小计量单位，{@code outputPerUnit} 是「粮/亩」⇒ 入账前要换算）。 */
  public static final long MILLI_PER_GRAIN = 1000L;

  private EconomyVocabulary() {}
}
