package io.mosire.simos.economy.api.money;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.InstrumentId;
import java.util.List;
import java.util.Optional;

/**
 * ★★ <b>世界级货币词表的唯一拼写点</b>（M1.1；照 {@code EconomyVocabulary.allCommodityIds()} 的先例）：这个世界<b>有哪些币种</b>、
 * <b>有哪些货币工具</b>，只在这里写一次。
 *
 * <p>★★ <b>它为什么必须存在</b>（与商品词表同一条理由）：M1.1 新增的 {@link CurrencyDef} / {@link MoneyInstrument}
 * 若没有一个"列出这个世界有哪些钱"的读口，它们就成了<b>没人读得到的孤类型</b>（本仓禁"看起来在记"）⇒ {@code ApiViews.economyHex} 把 {@link
 * #allCurrencyDefs()} / {@link #allInstruments()} 两栏发出去（{@code currencyDefs} / {@code
 * moneyInstruments}）； 唯一性由 {@code EconomyVocabularyGuardTest} 的源扫描钉住（"silver" 字面量全仓 {@code
 * src/main} 恰一处）。
 *
 * <p>★★ <b>为什么它在 {@code economy-api} 而不在 {@code simos-util} 的 {@code
 * EconomyVocabulary}</b>（一处如实的边界）： 商品词表住 util，是因为 {@code simos-unit} 只依赖 util + map
 * 也要吃粮；而货币词表<b>带类型</b>（{@link CurrencyDef} / {@link MoneyInstrument} 要用 {@code ActorRef} 与 {@code
 * CurrencyId}），util 看不见这两个类型 ⇒ 只能住<b>同时看得见它们的最底层</b> （本模块）。★ 将来军队真要按币种吃饷，需要的是更上一层的接口（M4+
 * 的裁定），<b>不是</b>把这两个类型降级成 {@code String}。
 *
 * <p>★★ <b>本世界的货币（如实记：在用什么、留位什么）</b>：
 *
 * <ul>
 *   <li><b>在用</b>：{@code silver} —— 一个 {@link CurrencyDef}（{@code scale = 3}，即"毫银"）+ 一个 {@link
 *       InstrumentKind#SPECIE} 工具。它的三个读法都指向同一处：{@code HouseholdInventory.money} 的键（家户与经营者的余额）、
 *       {@code Market.numeraire}（每格的计价货币）、{@code Transfer.money} 的货币腿；
 *   <li><b>留位</b>：<b>没有</b>国币 / 银行存款工具 —— ★ 这不是漏写：那两档<b>必须有发行人</b>（构造期守卫），而本批 {@code MoneyIssuance}
 *       <b>零注册</b>（没有任何 {@code MoneyAuthority} 实现）⇒ 世界上<b>说不出</b>谁是发行人，
 *       硬造一张"没有发行人的国币"会被守卫当场拦下。**守卫在这里的作用正是"不许为了让词表好看而编造制度"**。
 * </ul>
 *
 * <p>★ <b>没有 {@code allCurrencyIds()}</b>：那会是同一份清单的第二个拼写（{@link CurrencyDef#id()} 已经带着 id）——
 * 本仓只留一处。★ 本批也没有汇率：币种之间不许求和、不许折算（裁定 M1-A）。
 */
public final class MoneyVocabulary {

  /** 银的币种 id（★ 全仓 {@code src/main} 里 {@code "silver"} 字面量的唯一一处）。 */
  public static final String SILVER_CURRENCY_ID = "silver";

  /**
   * 银的<b>最小单位精度</b>：3 ⇒ 最小单位 = 毫银（{@code 1 银 = 1000 毫银}）。
   *
   * <p>★ <b>这个 3 是"写下来的既有事实"，不是新裁定的数</b>：全仓的货币余额一直是"毫银"（{@code
   * EconomySeeder.genesisMoneyMilliPerCapita} 的口径、各读口的 {@code milli} 后缀、{@code
   * HouseholdInventory.money} 的"最小币值"） —— M1.1 之前它<b>没有一处写下来</b>，只活在每个人的脑子里。
   */
  public static final int SILVER_SCALE = 3;

  /** 银的币种定义（词表条目）。 */
  public static final CurrencyDef SILVER = new CurrencyDef(SILVER_CURRENCY_ID, SILVER_SCALE);

  /**
   * 银的<b>运行时身份</b>（{@link CurrencyId}）—— {@code RegimeRelations.DEFAULT_CURRENCY} 与一切"哪种钱"的引用都取它，
   * 于是全仓 {@code src/main} 里<b>再没有一处</b>就地 {@code new CurrencyId(…)} 拼出这个币种名（由源扫描护栏钉住）。
   */
  public static final CurrencyId SILVER_CURRENCY = SILVER.currencyId();

  /** 银币（金属币）的工具 id。 */
  public static final String SILVER_SPECIE_INSTRUMENT_ID = "silver-specie";

  /**
   * ★★ <b>银币</b>：本世界唯一在用的货币工具（{@link InstrumentKind#SPECIE}）。
   *
   * <p>★ <b>两个 {@code Optional.empty()} 都不是"忘了填"</b>：金属币<b>没有</b>发行人（价值来自金属本身 ⇒ 构造期守卫要求 issuer
   * 必须为空），也<b>没有</b>兑现人（兑现属 M4+；{@code redeemer} 是具名留位，见 {@link MoneyInstrument} 的类注）。 ⇒
   * 它同时是"SPECIE 的空 issuer 是合法态"的**生产侧样例**。
   */
  public static final MoneyInstrument SILVER_SPECIE =
      new MoneyInstrument(
          new InstrumentId(SILVER_SPECIE_INSTRUMENT_ID),
          SILVER_CURRENCY,
          InstrumentKind.SPECIE,
          Optional.empty(),
          Optional.empty());

  private MoneyVocabulary() {}

  /**
   * ★★ <b>全部币种定义</b>（保序：声明序 = 词表序）—— 读口把它发成 {@code currencyDefs}。
   *
   * <p>★ 返回的是不可变清单；{@link CurrencyDef} 自带 {@link CurrencyDef#scale()}（最小单位精度）⇒ 单看这一栏就知道 "1 银是 1000
   * 还是 100 毫"。
   */
  public static List<CurrencyDef> allCurrencyDefs() {
    return List.of(SILVER);
  }

  /**
   * ★★ <b>全部货币工具</b>（保序：声明序 = 词表序）—— 读口把它发成 {@code moneyInstruments}。
   *
   * <p>★ 逐工具的守恒（M1.6）要读的就是这一栏：币种总量恒定<b>不等于</b>逐工具恒定（同一币种下，银币与银票的增删是两件事）。
   */
  public static List<MoneyInstrument> allInstruments() {
    return List.of(SILVER_SPECIE);
  }
}
