package io.mosire.simos.economy.api.money;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.InstrumentId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>世界级货币词表的唯一拼写点</b>（M1.1；照 {@code EconomyVocabulary.allCommodityIds()} 的先例）：这个世界<b>有哪些币种</b>、
 * <b>有哪些货币工具</b>，只在这里写一次。
 *
 * <p>★★ <b>它为什么必须存在</b>（与商品词表同一条理由）：M1.1 新增的 {@link CurrencyDef} / {@link MoneyInstrument}
 * 若没有一个"列出这个世界有哪些钱"的读口，它们就成了<b>没人读得到的孤类型</b>（本仓禁"看起来在记"）⇒ {@code ApiViews.economyHex} 把 {@link
 * #allCurrencyDefs()} / {@link #allInstruments()} 两栏发出去（{@code currencyDefs} / {@code
 * moneyInstruments}）； "silver" 字面量在全仓 {@code src/main} 恰一处由 {@code EconomyVocabularyGuardTest}
 * 的源扫描钉住。
 *
 * <p>★★ <b>A1（2026-10-08，约束设计书 §3.1-2）：词表从"硬编码常量"改为"逐世界可配置"</b>——
 *
 * <pre>
 * 权威（世界状态）：EconomyData.currencies / EconomyData.moneyInstruments（进 Codec/ChangeSet，可持久、可回放、可分支）
 * 本类（对外的读口）：allCurrencyDefs() / allInstruments() 读"当前世界的那张表"
 * 同步点（唯一）：EconomyCodec.apply / EconomyCodec.decodeSnapshot —— 状态每变一次，门面按状态重装一次
 * </pre>
 *
 * <p>★★ <b>为什么门面是"进程内一份当前世界表"而不是"从状态读"</b>（一处如实的边界，必须写清）：
 *
 * <ol>
 *   <li>{@link #allCurrencyDefs()} 是<b>无参静态读口</b>，而 {@code economy-api} <b>看不见 {@code
 *       SimulationState}</b> （它比状态树还底层）⇒ "从状态读"在这个签名下不可能；既有读口（{@code ApiViews.economyHex}）就在用这个签名，
 *       而它是**别的责任区**的文件（A1 的文件所有权不含 {@code app/gui}）⇒ 不能把两栏改成带 state 的调用；
 *   <li>故采取与 {@code MoneyIssuance.syncAuthorities} <b>同一形态</b>：静态登记表 + 由世界状态重建。**代价如实记**：
 *       表是<b>进程级</b>的（最后物化的那个世界/那个 revision 说了算），跨世界/跨分支的并发读会互相影响 —— 这一条与 {@code MoneyIssuance}
 *       的既有限制同源，<b>不是</b>本批新引入的机制；
 *   <li>权威永远是 {@code EconomyData.currencies()} —— 需要"某个具体 revision 的词表"时必须走状态，不许读门面。
 * </ol>
 *
 * <p>★★ <b>本世界的货币（如实记：在用什么、留位什么）</b>：
 *
 * <ul>
 *   <li><b>在用</b>：{@code silver} —— 一个 {@link CurrencyDef}（{@code scale = 3}，即"毫银"）+ 一个 {@link
 *       InstrumentKind#SPECIE} 工具。它的三个读法都指向同一处：{@code HouseholdInventory.money} 的键（家户与经营者的余额）、
 *       {@code Market.numeraire}（每格的计价货币）、{@code Transfer.money} 的货币腿；
 *   <li><b>A1 新增的第二币种</b>：{@code copper}（{@code scale = 3}、{@link InstrumentKind#SPECIE}）—— 它的
 *       <b>发行人</b>是创世里的那个 GOV（{@code Government.issuable} 含 {@code copper}），见 {@code
 *       GovWorldBootstrap}。★ 这里只给"币种 id / 精度 / 工具 id"的拼写点，<b>不给</b>它出现在哪个世界：哪个世界的词表里 有 copper
 *       由那个世界的创世/命令决定（{@code economy.Seed} 默认只有 silver；{@code economy.DefineCurrency} 才是加币种的写口）；
 *   <li><b>留位</b>：<b>没有</b>国币 / 银行存款工具 —— ★ 这不是漏写：那两档<b>必须有发行人</b>（构造期守卫），本批 {@link MoneyIssuance}
 *       仍未登记任何发行人实现 ⇒ 世界上<b>说不出</b>谁是发行人， 硬造一张"没有发行人的国币"会被守卫当场拦下。 **守卫在这里的作用正是"不许为了让词表好看而编造制度"**。
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

  /** 银的显示名（A1：给人看的名字；改名只改它，不动 {@link #SILVER_CURRENCY_ID}）。 */
  public static final String SILVER_DISPLAY_NAME = "银";

  /** 银的币种定义（旧世界词表条目 = 本仓的默认词表）。 */
  public static final CurrencyDef SILVER =
      new CurrencyDef(SILVER_CURRENCY_ID, SILVER_SCALE, SILVER_DISPLAY_NAME);

  /**
   * 银的<b>运行时身份</b>（{@link CurrencyId}）—— {@code RegimeRelations.DEFAULT_CURRENCY} 与一切"哪种钱"的引用都取它，
   * 于是全仓 {@code src/main} 里<b>再没有一处</b>就地 {@code new CurrencyId(…)} 拼出这个币种名（由源扫描护栏钉住）。
   */
  public static final CurrencyId SILVER_CURRENCY = SILVER.currencyId();

  /** 银币（金属币）的工具 id。 */
  public static final String SILVER_SPECIE_INSTRUMENT_ID = "silver-specie";

  /**
   * ★★ <b>银币</b>：旧世界词表里唯一在用的货币工具（{@link InstrumentKind#SPECIE}）。
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

  /** 铜的币种 id（A1 的第二币种；字面量的唯一一处，与 {@link #SILVER_CURRENCY_ID} 同制）。 */
  public static final String COPPER_CURRENCY_ID = "copper";

  /** 铜的最小单位精度：3 ⇒ 最小单位 = 毫铜（与银同制；A 阶段两币种同精度，避免"折算"这种本批不做的事）。 */
  public static final int COPPER_SCALE = 3;

  /** 铜的显示名。 */
  public static final String COPPER_DISPLAY_NAME = "铜";

  /** 铜的币种定义（★ 只是"这个 id 怎么拼、精度几位、叫什么"的拼写点；它是否出现在某个世界由那个世界的词表决定）。 */
  public static final CurrencyDef COPPER =
      new CurrencyDef(COPPER_CURRENCY_ID, COPPER_SCALE, COPPER_DISPLAY_NAME);

  /** 铜的运行时身份。 */
  public static final CurrencyId COPPER_CURRENCY = COPPER.currencyId();

  /** 铜币的工具 id。 */
  public static final String COPPER_SPECIE_INSTRUMENT_ID = "copper-specie";

  /** ★★ <b>铜币</b>：第二币种的金属币工具（与 {@link #SILVER_SPECIE} 同形：无发行人、无兑现人）。 */
  public static final MoneyInstrument COPPER_SPECIE =
      new MoneyInstrument(
          new InstrumentId(COPPER_SPECIE_INSTRUMENT_ID),
          COPPER_CURRENCY,
          InstrumentKind.SPECIE,
          Optional.empty(),
          Optional.empty());

  /** 私有锁对象（照 {@code MoneyIssuance}：不用类固有锁，避免外部持锁者干扰内部互斥）。 */
  private static final Object LOCK = new Object();

  /** 当前世界表（保序、不可变快照）；缺省 = 旧世界默认（silver-only）。 */
  private static List<CurrencyDef> installedDefs = List.of(SILVER);

  /** 见 {@link #installedDefs}。 */
  private static List<MoneyInstrument> installedInstruments = List.of(SILVER_SPECIE);

  private MoneyVocabulary() {}

  /** 旧世界默认词表（silver-only）：世界状态没有声明词表时的取值。 */
  public static List<CurrencyDef> legacyCurrencyDefs() {
    return List.of(SILVER);
  }

  /** 旧世界默认工具表（silver-specie）：与 {@link #legacyCurrencyDefs()} 配套。 */
  public static List<MoneyInstrument> legacyInstruments() {
    return List.of(SILVER_SPECIE);
  }

  /**
   * ★★ <b>按当前世界状态重装词表</b>（A1 的唯一同步点；幂等、确定性）—— 由 {@code EconomyCodec} 在 {@code apply}/{@code
   * decodeSnapshot} 两处边界调用，于是"状态每变一次，门面就跟着变一次"。
   *
   * <p>★★ <b>空表 = 旧世界默认</b>：{@code null} / 空清单（含"旧档缺键 ⇒ 空表"）⇒ 装回 silver-only，与 A1 之前逐值相同。
   *
   * <p>★ <b>校验（fail-closed，坏数据当场抛，不装半张表）</b>：
   *
   * <ol>
   *   <li>币种 id 不得重复；
   *   <li>工具 id 不得重复；
   *   <li><b>每张工具的币种必须在币种表里有定义</b>（否则它是一张"没有币种的工具"，读口说不出它是什么钱）；
   *   <li>元素不得为 null（"没填"与"空表"是两件事）。
   * </ol>
   *
   * @param defs 币种表（{@code null}/空 ⇒ 旧世界默认）
   * @param instruments 工具表（{@code null}/空 ⇒ 旧世界默认）
   */
  public static void install(
      Collection<CurrencyDef> defs, Collection<MoneyInstrument> instruments) {
    List<CurrencyDef> nextDefs = normalizeDefs(defs);
    List<MoneyInstrument> nextInstruments = normalizeInstruments(instruments, nextDefs);
    synchronized (LOCK) {
      installedDefs = nextDefs;
      installedInstruments = nextInstruments;
    }
  }

  /** 装回旧世界默认（{@code install(null, null)} 的同义口；给"读旧档/夹具"的调用点一个自解释的拼写）。 */
  public static void installLegacyDefault() {
    install(null, null);
  }

  /**
   * ★★ <b>全部币种定义</b>（保序：声明序 = 词表序）—— 读口把它发成 {@code currencyDefs}。
   *
   * <p>★ 返回的是不可变清单；{@link CurrencyDef} 自带 {@link CurrencyDef#scale()}（最小单位精度）与 {@link
   * CurrencyDef#displayName()} ⇒ 单看这一栏就知道 "1 银是 1000 还是 100 毫"以及"它叫什么"。
   *
   * <p>★★ <b>权威是世界状态</b>（{@code EconomyData.currencies()}）：本读口是"当前世界表"的门面，见类注的代价说明。
   */
  public static List<CurrencyDef> allCurrencyDefs() {
    synchronized (LOCK) {
      return installedDefs;
    }
  }

  /**
   * ★★ <b>全部货币工具</b>（保序：声明序 = 词表序）—— 读口把它发成 {@code moneyInstruments}。
   *
   * <p>★ 逐工具的守恒（M1.6）要读的就是这一栏：币种总量恒定<b>不等于</b>逐工具恒定（同一币种下，银币与银票的增删是两件事）。
   */
  public static List<MoneyInstrument> allInstruments() {
    synchronized (LOCK) {
      return installedInstruments;
    }
  }

  /** 当前表里的币种定义；查无 ⇒ 空（只读查询，不抛）。 */
  public static Optional<CurrencyDef> currencyDefOf(CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    for (CurrencyDef def : allCurrencyDefs()) {
      if (def.currencyId().equals(currency)) {
        return Optional.of(def);
      }
    }
    return Optional.empty();
  }

  /**
   * 当前表里的币种定义；查无 ⇒ <b>当场抛</b>（说不出"这是哪种钱"时不许编一个默认精度出来）。
   *
   * @throws IllegalArgumentException 币种为 null，或当前表里没有它
   */
  public static CurrencyDef requireCurrencyDef(CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    return currencyDefOf(currency)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "当前世界的货币词表里没有币种 " + currency + "（词表=" + idsOf(allCurrencyDefs()) + "）"));
  }

  /** 当前世界表里的币种 id 集（保序、不可变；只读诊断/校验用）。 */
  public static Set<CurrencyId> currencyIds() {
    Set<CurrencyId> ids = new LinkedHashSet<>();
    for (CurrencyDef def : allCurrencyDefs()) {
      ids.add(def.currencyId());
    }
    return Collections.unmodifiableSet(ids);
  }

  /** 逐条校验 + 冻结（不装半张表）。 */
  private static List<CurrencyDef> normalizeDefs(Collection<CurrencyDef> defs) {
    if (defs == null || defs.isEmpty()) {
      return List.of(SILVER);
    }
    Map<CurrencyId, CurrencyDef> byId = new LinkedHashMap<>();
    for (CurrencyDef def : defs) {
      Objects.requireNonNull(def, "MoneyVocabulary.install 的币种表不得含 null");
      CurrencyId id = def.currencyId();
      CurrencyDef previous = byId.putIfAbsent(id, def);
      if (previous != null) {
        throw new IllegalArgumentException("货币词表里币种 id 重复（同一份身份两条例目）: " + id.value());
      }
    }
    return List.copyOf(byId.values());
  }

  /** 逐条校验（工具 id 不重复 + 币种必须在币种表里）+ 冻结。 */
  private static List<MoneyInstrument> normalizeInstruments(
      Collection<MoneyInstrument> instruments, List<CurrencyDef> defs) {
    if (instruments == null || instruments.isEmpty()) {
      // ★ 空工具表 = 旧世界默认，但**只在币种表里确实有 silver 时**才装 silver-specie：
      //   一个只有 copper 的世界若被塞进一张"银币"，就成了一张"币种不在表里的工具"（本方法下面正要拒的那种）。
      for (CurrencyDef def : defs) {
        if (SILVER_CURRENCY_ID.equals(def.id())) {
          return legacyInstruments();
        }
      }
      return List.of();
    }
    Set<CurrencyId> ids = new LinkedHashSet<>();
    for (CurrencyDef def : defs) {
      ids.add(def.currencyId());
    }
    Map<InstrumentId, MoneyInstrument> byId = new LinkedHashMap<>();
    for (MoneyInstrument instrument : instruments) {
      Objects.requireNonNull(instrument, "MoneyVocabulary.install 的工具表不得含 null");
      MoneyInstrument previous = byId.putIfAbsent(instrument.id(), instrument);
      if (previous != null) {
        throw new IllegalArgumentException("货币词表里工具 id 重复: " + instrument.id().value());
      }
      if (!ids.contains(instrument.currency())) {
        throw new IllegalArgumentException(
            "货币工具 "
                + instrument.id().value()
                + " 的币种 "
                + instrument.currency()
                + " 在币种表里没有定义（说不出这是什么钱的工具不许进词表；币种表="
                + idsOf(defs)
                + "）");
      }
    }
    return List.copyOf(byId.values());
  }

  /** 币种清单的可读摘要（只读诊断/拒因用；保序）。 */
  private static List<String> idsOf(List<CurrencyDef> defs) {
    List<String> ids = new ArrayList<>(defs.size());
    for (CurrencyDef def : defs) {
      ids.add(def.id());
    }
    return ids;
  }
}
