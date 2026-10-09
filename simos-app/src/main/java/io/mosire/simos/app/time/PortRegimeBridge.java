package io.mosire.simos.app.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.PortContactSurface;
import io.mosire.simos.economy.api.market.PortDirection;
import io.mosire.simos.economy.api.market.PortRule;
import io.mosire.simos.economy.api.market.PortTaxMode;
import io.mosire.simos.economy.api.market.PortTaxRule;
import io.mosire.simos.economy.api.market.ZonePortRegime;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.time.PortEnforcementInput;
import io.mosire.simos.economy.time.PortRegimeAggregation;
import io.mosire.simos.economy.time.PortTaxInput;
import io.mosire.simos.gov.GovEfficiency;
import io.mosire.simos.gov.GovPortPolicy;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>P-T1a：逐政府实际管制力 → 市场区<b>逐方向</b>开放度（唯一折算点，组合根）</b> （2026-10-09 口岸设计书 §4.2/§4.3/§4.4 +
 * 2026-10-10 追加裁定 3 §12）。
 *
 * <pre>
 * ① 暴露边（{@link PortExposureEdges}）⇒ 逐区逐归属的接触面权重 w_k（归"管着 Z 侧那格的政府"）
 * ② 逐接触面：s_k = 该政府对这一类的<b>该方向</b>限制强度（{@link PortRule}；缺省 0 = 不限制，I-P1）
 *             e_k = 该政府的口岸效率（{@link GovEfficiency.Efficiency#portEfficiencyPerMille()} 第三维）
 *             enforcement_k = ⌊s_k × e_k ÷ 1000⌋                       ← 逐政府执行，不合并成"区的一个效率"（I-P5）
 * ③ 市场区 Z 对类 c 的<b>每个方向</b>：规则 = OR（∃k: s_k = 0）；总效率 E = 按 w_k 加权平均（{@link PortRegimeAggregation}）
 *    ★ P-T1e：币种维的"类" = <b>（币种 × 挂单类型）</b>（{@link MarketOrderKind}）—— 同一个币上"兑换/货↔钱/借贷"各自一套 E
 * ④ 注入经济侧：逐区逐类<b>两个方向</b>的"实际抓得多严" = 1000 − E（‰），进 {@link PortEnforcementInput}
 *    —— 商品维供"跨区两道闸"（E_源）、币种维供"币种挂单闸"（入口/出口各一，按挂单类型分列）与（既有）估值减项
 * </pre>
 *
 * <p>★★ <b>为什么折算只能在组合根</b>：{@code simos-gov} 的 enforcer 禁 {@code simos-economy}（gov 算不了区与暴露边），
 * {@code simos-economy} 不认识 gov（算不了政策与口岸效率）—— 只有 app 同时看得见三边（设计书 F3/§4.2）。
 *
 * <p>★★ <b>方向怎么落到接触面上</b>（§12.2）：接触面归"Z 侧那格的政府"⇒ 这个政府设的<b>入口</b>规则管的是"进 Z"， 它的<b>出口</b>规则管的是"出
 * Z"。于是"Z 这一侧的入口开放度"用各接触面的 {@code s_in} 加权平均、"出口开放度"用 {@code s_out}（同一个权重表，两次聚合）。跨区时：{@code E_源 =
 * 卖方区.EXIT}、{@code E_目的 = 买方区.ENTRY} ⇒ 唯一算式在 {@code PortThrottle}。
 *
 * <p>★★ <b>I-P8（第一判据）：无政策 / 无接触面 ⇒ 逐值不变</b>：
 *
 * <ul>
 *   <li><b>类的定义域 = 政策里显式设过<b>且非空</b>的规则</b>（逐区取该区各归属政府政策键并集；四个数全 0/无税 ⇒ "等于没设"，不进定义域）⇒
 *       一条政策都没有的世界，本类算出空的 {@link PortEnforcementInput#none()}， 注入值与"没注入"逐值同义；
 *   <li>无接触面（{@code Σw = 0}）⇒ {@code E = 1000} ⇒ 注入值 {@code 1000 − E = 0}（且零值条目<b>不入表</b>，读法等价）；
 *   <li>{@code s = 0}（未设/显式 0）⇒ 该接触面 {@code openness = 1000} ⇒ 不影响 E 的分子。
 * </ul>
 *
 * <p>★★ <b>负向（N1）：非法政策 fail-closed 具名拒，不静默忽略</b> —— "负强度/负税/非法计量方式"已在 {@link PortRule} / {@code
 * PortTaxRule} 构造期拒（命令面 {@code Rejected}，零 revision）；"未知商品/未知币种"的判据在这里（gov 模块编译期看不见经济词表）：具名 ERROR
 * {@code GOV_PORT_POLICY_UNKNOWN_CLASS} + {@link IllegalStateException}， <b>当场停</b>（不是"忽略这一条继续跑"）。
 *
 * <p>★★ <b>税（P-T1b 起真收）</b>：本类把四个数字段里的<b>税</b>折成"区级税率 + 收税政府"，经 {@link PortTaxInput} 注入经济侧（真收款 =
 * 买方多付、差额进对应政府国库户，落在市场结算的成交处）：
 *
 * <pre>
 * 区级税率（两个分量各自按暴露边权重加权平均，分母 = 该区全部暴露边含三不管那一份）：
 *   perUnitMilli      = ⌊Σ(w_k × 该政府从量额) ÷ Σw⌋        （只有设了从量规则的政府进分子）
 *   adValoremPerMille = ⌊Σ(w_k × 该政府从价额) ÷ Σw⌋        （只有设了从价规则的政府进分子）
 * 收税政府 = 该区暴露边归属里"国库是家户"的那些政府（权威 = Government.treasury()；按权重分摊税额）
 * </pre>
 *
 * ★ <b>无政府的一侧 ⇒ 该侧税 = 0</b>（三不管那一份交 0、也不进收税政府表；与"无政府 ⇒ 开放度 1000"同源）。 ★
 * <b>只设税不设限</b>的世界：闸不动、税照收（两个注入表各管各的）。★ 四个数字段本身由 {@link GovPortPolicy} 持久化 ⇒ 不会丢。
 *
 * <p>★ <b>时序（本批冻结）</b>：本折算读当日结算后的口岸效率（{@code GovEfficiency} 在同一日循环的 gov 阶段算）， 注入给 {@code stepper}
 * 后被<b>下一次</b>市场轮消费（一 tick 滞后）；理由与影响记账本 §关键判断。
 */
public final class PortRegimeBridge {

  /** 组合根日志通道（与日循环同一 logger/来源，§一.9）。 */
  private static final LogChannel LOG = EventLog.channel(AppLog.time());

  private PortRegimeBridge() {}

  /**
   * 一次折算的完整产物。
   *
   * @param input 注入经济侧的逐区逐类实际管制力（逐方向 {@code 1000 − E}；币种表再按挂单类型分列；空 ⇒ 没有口岸面）
   * @param taxInput ★ P-T1b：注入经济侧的<b>三层税税率与收税政府</b>（空 ⇒ 一分不收，逐值退回改前）
   * @param regimes 逐区逐类<b>逐方向</b>的总效率读数（保序：区序 → 类序 → 入口/出口；币种维按"币种#挂单类型"分条； 供日志/读数/探针）
   * @param exposedEdges 全部接触面的暴露边合计（读数）
   * @param contacts 接触面条数（读数）
   * @param restrictedClasses 设过<b>非空</b>规则的条数（读数；商品类 + （币种 × 挂单类型）；0 ⇒ 没有口岸面）
   * @param taxedClasses 设过<b>真会收</b>的税/手续费的条数（读数；商品维 + 币种维）
   * @param currencyFeeClasses ★ P-T1e：设了币种手续费（真会收）的（币种 × 挂单类型）条数（读数；本批<b>只落形状 + 读数 + 具名
   *     INFO</b>，没有收款面 ⇒ 一个数都不搬）
   */
  public record PortRegimeDay(
      PortEnforcementInput input,
      PortTaxInput taxInput,
      List<ZonePortRegime> regimes,
      long exposedEdges,
      int contacts,
      int restrictedClasses,
      int taxedClasses,
      int currencyFeeClasses) {

    public PortRegimeDay {
      Objects.requireNonNull(input, "input");
      Objects.requireNonNull(taxInput, "taxInput（没有税就给 PortTaxInput.none()）");
      regimes = Collections.unmodifiableList(new ArrayList<>(regimes)); // ★ 保序冻结
    }

    /**
     * 本轮有没有口岸面（有实际管制力才注入 ⇒ false ⇒ 逐值不变）。
     *
     * <p>★ 判据是 {@link PortEnforcementInput#isActive()}（表非空 = 至少一个区一个类真的抓得住）而不是"设过几条规则"： 一条规则设成全
     * 0、或只设了税 ⇒ 闸一个数都不动 ⇒ 不注入管制力（税走 {@link #taxActive()} 那条路，两者各管各的）。
     */
    public boolean active() {
      return input.isActive();
    }

    /**
     * ★★ <b>P-T1b：本轮有没有税制可注入</b>（至少一个区有"能收钱的政府"）—— 真收不收得看税率与成交，这里只回答 "要不要把表交给经济会话"。
     *
     * <p>★ <b>为什么与 {@link #active()} 分开</b>：只设税率而两侧全开的世界"能过但要多付钱"；只设限制而不设税的世界
     * "过不去但不加价"。合成一个判据会让其中一半静默失效。
     */
    public boolean taxActive() {
      return taxInput.isActive();
    }

    /** 本轮真的有非 0 税率的（区 × 商品 × 方向）条数（读数/日志用；0 ⇒ 收了也是 0）。 */
    public int taxedSides() {
      return taxInput.taxedSides();
    }
  }

  /**
   * ★★ <b>算本轮的口岸执行规律</b>（纯函数；不写状态、不落盘、不用随机数）。
   *
   * @param govState 政府切片（口岸政策 = 逐政府 × 逐类的四元组规则）；不得为 null
   * @param units 单位切片（辖区 ⇒ 暴露边归属）；不得为 null
   * @param map 地图（Region→hexes、hex 存在性）；不得为 null
   * @param economy 经济切片（区表 = 成员格唯一权威；币种词表 = {@code currencies()}）；不得为 null
   * @param efficiencyByUnit 当日逐 GOV 效率表（口岸效率 = 第三维）；缺失的 GOV 按"没有口岸编制"（e = 0）读
   * @param day 世界日（只进日志）
   * @throws IllegalStateException 非法政策（未知商品/未知币种）⇒ 具名 ERROR + fail-closed
   */
  public static PortRegimeDay compute(
      GovState govState,
      UnitState units,
      GameMap map,
      EconomyData economy,
      Map<UnitId, GovEfficiency.Efficiency> efficiencyByUnit,
      long day) {
    Objects.requireNonNull(govState, "govState");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(efficiencyByUnit, "efficiencyByUnit");

    List<PortExposureEdges.Contact> contacts = PortExposureEdges.contacts(economy, map, units);
    if (contacts.isEmpty()) {
      return new PortRegimeDay(
          PortEnforcementInput.none(), PortTaxInput.none(), List.of(), 0L, 0, 0, 0, 0);
    }
    // ── 逐区 × 逐归属：暴露边权重（w_k）────────────────────────────────────────────────
    Map<String, Map<String, Long>> weightsByZone = new LinkedHashMap<>();
    for (PortExposureEdges.Contact contact : contacts) {
      weightsByZone
          .computeIfAbsent(contact.zoneId(), ignored -> new LinkedHashMap<>())
          .put(contact.ownerKey(), contact.exposedEdgeCount());
    }
    // ── 逐区：① 类的定义域（显式设过且非空的类）② 区级税率（两个分量各自按 w_k 加权平均）
    //          ③ 收税政府（国库是家户的那些；权威 = Government.treasury()）────────────────────
    Map<String, Set<String>> restrictedCommoditiesByZone = new LinkedHashMap<>();
    // ★★ P-T1e：币种维的定义域 = （币种 × 挂单类型）—— 同一个币上"兑换/货↔钱/借贷"是三条独立规则。
    Map<String, Map<String, Set<MarketOrderKind>>> restrictedCurrenciesByZone =
        new LinkedHashMap<>();
    Map<String, Map<CommodityId, ZoneTaxAccumulator>> taxAccumulatorsByZone = new LinkedHashMap<>();
    Map<String, List<PortTaxInput.GovernmentShare>> governmentsByZone = new LinkedHashMap<>();
    Map<String, CurrencyId> legalTenderByZone = new LinkedHashMap<>();
    int restrictedClasses = 0;
    int taxedClasses = 0;
    int currencyFeeClasses = 0;
    for (Map.Entry<String, Map<String, Long>> zoneEntry : weightsByZone.entrySet()) {
      String zoneId = zoneEntry.getKey();
      Set<String> commodities = new LinkedHashSet<>();
      Map<String, Set<MarketOrderKind>> currencies = new LinkedHashMap<>();
      Map<CommodityId, ZoneTaxAccumulator> taxAccumulators = new LinkedHashMap<>();
      List<PortTaxInput.GovernmentShare> governments = new ArrayList<>();
      // ★ 加权平均的分母 = 该区**全部**暴露边（含三不管那一份）：没有政府的那一侧交 0，
      //   于是"整区都没政府 ⇒ 税率 0"是这条算式的自然结果（§13.2-3），不是另一条特判。
      long weightSum = 0L;
      for (long weight : zoneEntry.getValue().values()) {
        weightSum = Math.addExact(weightSum, weight);
      }
      List<String> ownerKeys = new ArrayList<>(zoneEntry.getValue().keySet());
      ownerKeys.sort(Comparator.naturalOrder());
      for (String ownerKey : ownerKeys) {
        GovPortPolicy policy = policyOf(govState, units, ownerKey);
        checkKnownClasses(policy, economy, ownerKey, day);
        long weight = zoneEntry.getValue().get(ownerKey);
        for (Map.Entry<CommodityId, PortRule> rule : policy.commodityRules().entrySet()) {
          if (rule.getValue().allDefault()) {
            continue; // ★ I-P1：四个数全 0/无税 = 等于没设（显式 0 与未设同义）
          }
          commodities.add(rule.getKey().value());
          if (rule.getValue().hasEffectiveTax()) {
            taxedClasses++;
            accumulateTax(taxAccumulators, rule.getKey(), rule.getValue(), weight);
          }
        }
        for (Map.Entry<CurrencyId, Map<MarketOrderKind, PortRule>> currencyRule :
            policy.currencyRules().entrySet()) {
          for (Map.Entry<MarketOrderKind, PortRule> byKind : currencyRule.getValue().entrySet()) {
            if (byKind.getValue().allDefault()) {
              continue; // ★ 同上：显式 0 与未设同义（"这条类型我没设规则"）
            }
            currencies
                .computeIfAbsent(currencyRule.getKey().value(), ignored -> new LinkedHashSet<>())
                .add(byKind.getKey());
            if (byKind.getValue().hasEffectiveTax()) {
              // ★ 币种维的手续费**不进**商品税表：它的税基不是"货值" ⇒ 本表只统计可读性，
              //   不参与任何金额计算（没有收款面 ⇒ 一个数都不搬；具名 INFO 见 logCurrencyFeeShapedOnly）。
              taxedClasses++;
              currencyFeeClasses++;
            }
          }
        }
        PortTaxInput.GovernmentShare share = governmentShareOf(economy, ownerKey, weight, day);
        if (share != null) {
          governments.add(share);
        }
      }
      restrictedClasses += commodities.size() + currencyKindCount(currencies);
      restrictedCommoditiesByZone.put(zoneId, commodities);
      restrictedCurrenciesByZone.put(zoneId, currencies);
      if (!governments.isEmpty()) {
        CurrencyId legalTender = legalTenderOf(economy, zoneId, day);
        if (legalTender != null) {
          governmentsByZone.put(zoneId, governments);
          legalTenderByZone.put(zoneId, legalTender);
          taxAccumulatorsByZone.put(zoneId, taxAccumulators);
        }
      }
    }
    // ★★ P-T1b：三层税的注入表（区级税率 + 收税政府）。它**不**依赖"有没有口岸限制"：
    //   区内市场税（MarketRegulation.tariffPerUnit）的税率在经济侧，收税政府必须照样注入，否则那一层永远收不到钱。
    PortTaxInput taxInput =
        buildPortTaxInput(
            weightsByZone, taxAccumulatorsByZone, governmentsByZone, legalTenderByZone);
    if (restrictedClasses == 0) {
      // ★ I-P8：一条有效规则都没有 ⇒ 没有口岸面（既不聚合、也不注入管制力；与"没注入"逐值同义）。
      return new PortRegimeDay(
          PortEnforcementInput.none(),
          taxInput,
          List.of(),
          PortExposureEdges.totalEdges(contacts),
          contacts.size(),
          0,
          0,
          0);
    }
    // ── 逐区逐类逐方向：接触面 → 总效率（唯一算式在 economy 的 PortRegimeAggregation）──────────
    List<ZonePortRegime> regimes = new ArrayList<>();
    Map<String, Map<CurrencyId, PortEnforcementInput.CurrencyEnforcement>>
        currencyEnforcementByZone = new LinkedHashMap<>();
    Map<String, Map<CommodityId, PortEnforcementInput.Directional>> commodityEnforcementByZone =
        new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Long>> zoneEntry : weightsByZone.entrySet()) {
      String zoneId = zoneEntry.getKey();
      List<String> ownerKeys = new ArrayList<>(zoneEntry.getValue().keySet());
      ownerKeys.sort(Comparator.naturalOrder());
      for (Map.Entry<String, Set<MarketOrderKind>> currencyEntry :
          restrictedCurrenciesByZone.get(zoneId).entrySet()) {
        String classKey = currencyEntry.getKey();
        CurrencyId currency = new CurrencyId(classKey);
        Map<MarketOrderKind, PortEnforcementInput.Directional> byKind = new LinkedHashMap<>();
        for (MarketOrderKind orderKind : sortedKinds(currencyEntry.getValue())) {
          // ★ 读数键带上挂单类型（{@code 币种#类型}）：同一个币的"兑换/货↔钱/借贷"三条规则各有各的 E，
          //   只写币种会让日志里的两条读数无法分辨（ZonePortRegime.classKey 是**读数键**，不是稳定 id）。
          String readingKey = currencyReadingKey(classKey, orderKind);
          ZonePortRegime entry =
              aggregateCurrency(
                  day,
                  zoneId,
                  readingKey,
                  currency,
                  orderKind,
                  ownerKeys,
                  zoneEntry.getValue(),
                  govState,
                  units,
                  efficiencyByUnit,
                  PortDirection.ENTRY);
          ZonePortRegime exit =
              aggregateCurrency(
                  day,
                  zoneId,
                  readingKey,
                  currency,
                  orderKind,
                  ownerKeys,
                  zoneEntry.getValue(),
                  govState,
                  units,
                  efficiencyByUnit,
                  PortDirection.EXIT);
          regimes.add(entry);
          regimes.add(exit);
          PortEnforcementInput.Directional directional =
              new PortEnforcementInput.Directional(
                  entry.enforcementPerMille(), exit.enforcementPerMille());
          if (!directional.zero()) {
            // ★ 零值条目不进表（0 与"缺键"读法同义；表更小、注入差异更干净）。
            byKind.put(orderKind, directional);
          }
          logCurrencyFeeShapedOnly(day, zoneId, classKey, orderKind, govState, units, ownerKeys);
        }
        if (!byKind.isEmpty()) {
          currencyEnforcementByZone
              .computeIfAbsent(zoneId, ignored -> new LinkedHashMap<>())
              .put(currency, new PortEnforcementInput.CurrencyEnforcement(byKind));
        }
      }
      for (String classKey : sorted(restrictedCommoditiesByZone.get(zoneId))) {
        ZonePortRegime entry =
            aggregateCommodity(
                day,
                zoneId,
                classKey,
                ownerKeys,
                zoneEntry.getValue(),
                govState,
                units,
                efficiencyByUnit,
                PortDirection.ENTRY);
        ZonePortRegime exit =
            aggregateCommodity(
                day,
                zoneId,
                classKey,
                ownerKeys,
                zoneEntry.getValue(),
                govState,
                units,
                efficiencyByUnit,
                PortDirection.EXIT);
        regimes.add(entry);
        regimes.add(exit);
        PortEnforcementInput.Directional directional =
            new PortEnforcementInput.Directional(
                entry.enforcementPerMille(), exit.enforcementPerMille());
        if (!directional.zero()) {
          commodityEnforcementByZone
              .computeIfAbsent(zoneId, ignored -> new LinkedHashMap<>())
              .put(new CommodityId(classKey), directional);
        }
      }
    }
    PortEnforcementInput input =
        new PortEnforcementInput(currencyEnforcementByZone, commodityEnforcementByZone);
    return new PortRegimeDay(
        input,
        taxInput,
        regimes,
        PortExposureEdges.totalEdges(contacts),
        contacts.size(),
        restrictedClasses,
        taxedClasses,
        currencyFeeClasses);
  }

  /** 币种维的（币种 × 挂单类型）条数（读数的分母；`Map<币种, Set<类型>>` 的总项数）。 */
  private static int currencyKindCount(Map<String, Set<MarketOrderKind>> currencies) {
    int count = 0;
    for (Set<MarketOrderKind> kinds : currencies.values()) {
      count += kinds.size();
    }
    return count;
  }

  /** 挂单类型的规范遍历序（声明序；I7 —— 不用 {@code Set} 迭代序当序）。 */
  private static List<MarketOrderKind> sortedKinds(Set<MarketOrderKind> kinds) {
    List<MarketOrderKind> ordered = new ArrayList<>();
    for (MarketOrderKind kind : MarketOrderKind.all()) {
      if (kinds.contains(kind)) {
        ordered.add(kind);
      }
    }
    return ordered;
  }

  /**
   * ★ 读数键：{@code 币种#挂单类型}（{@link ZonePortRegime#classKey()} 是<b>读数键</b>，不是稳定 id）。
   *
   * <p>★ <b>为什么不用 {@code Map} 或新类型</b>：读数列表是扁平的一条条 {@link ZonePortRegime}（经济侧的契约类型不改），
   * 而唯一的区分解就是那个字符串键 —— 拼在一个地方（本方法），日志与读数同源。
   */
  private static String currencyReadingKey(String classKey, MarketOrderKind orderKind) {
    return classKey + "#" + orderKind.value();
  }

  // ── P-T1b：区级税率（从量/从价两个分量各自按暴露边权重加权平均）+ 收税政府 ────────────────────

  /** 逐区逐商品的税率分子累加器（权重 × 数额；四个分量各一格）。 */
  private static final class ZoneTaxAccumulator {
    long exitPerUnitWeighted;
    long exitAdValoremWeighted;
    long entryPerUnitWeighted;
    long entryAdValoremWeighted;
  }

  /** 把一个政府的这条商品规则按它的权重累加进分子（从量/从价各归各的格子；溢出 fail-closed）。 */
  private static void accumulateTax(
      Map<CommodityId, ZoneTaxAccumulator> accumulators,
      CommodityId commodity,
      PortRule rule,
      long weight) {
    if (weight <= 0L) {
      return;
    }
    ZoneTaxAccumulator accumulator =
        accumulators.computeIfAbsent(commodity, ignored -> new ZoneTaxAccumulator());
    PortTaxRule exit = rule.exitTax();
    if (exit.leviesTax()) {
      long weighted = Math.multiplyExact(weight, exit.amount());
      if (exit.mode() == PortTaxMode.PER_UNIT_MILLI) {
        accumulator.exitPerUnitWeighted = Math.addExact(accumulator.exitPerUnitWeighted, weighted);
      } else {
        accumulator.exitAdValoremWeighted =
            Math.addExact(accumulator.exitAdValoremWeighted, weighted);
      }
    }
    PortTaxRule entry = rule.entryTax();
    if (entry.leviesTax()) {
      long weighted = Math.multiplyExact(weight, entry.amount());
      if (entry.mode() == PortTaxMode.PER_UNIT_MILLI) {
        accumulator.entryPerUnitWeighted =
            Math.addExact(accumulator.entryPerUnitWeighted, weighted);
      } else {
        accumulator.entryAdValoremWeighted =
            Math.addExact(accumulator.entryAdValoremWeighted, weighted);
      }
    }
  }

  /** 分子 ÷ 分母（加权平均，向下取整）；没有非 0 分子 ⇒ 该类不进表（= 不收）。 */
  private static Map<CommodityId, PortTaxInput.CommodityTaxRates> zoneRates(
      Map<CommodityId, ZoneTaxAccumulator> accumulators, long weightSum) {
    Map<CommodityId, PortTaxInput.CommodityTaxRates> rates = new LinkedHashMap<>();
    if (weightSum <= 0L) {
      return rates;
    }
    for (Map.Entry<CommodityId, ZoneTaxAccumulator> entry : accumulators.entrySet()) {
      ZoneTaxAccumulator accumulator = entry.getValue();
      PortTaxInput.CommodityTaxRates rate =
          new PortTaxInput.CommodityTaxRates(
              accumulator.exitPerUnitWeighted / weightSum,
              accumulator.exitAdValoremWeighted / weightSum,
              accumulator.entryPerUnitWeighted / weightSum,
              accumulator.entryAdValoremWeighted / weightSum);
      if (!rate.zero()) {
        rates.put(entry.getKey(), rate);
      }
    }
    return rates;
  }

  /** 组装注入表（区序 = 接触面表序；类序 = 政策的规范序 ⇒ 逐值可复现）。 */
  private static PortTaxInput buildPortTaxInput(
      Map<String, Map<String, Long>> weightsByZone,
      Map<String, Map<CommodityId, ZoneTaxAccumulator>> taxAccumulatorsByZone,
      Map<String, List<PortTaxInput.GovernmentShare>> governmentsByZone,
      Map<String, CurrencyId> legalTenderByZone) {
    Map<String, PortTaxInput.ZoneTaxTable> tables = new LinkedHashMap<>();
    for (String zoneId : weightsByZone.keySet()) {
      List<PortTaxInput.GovernmentShare> governments = governmentsByZone.get(zoneId);
      CurrencyId legalTender = legalTenderByZone.get(zoneId);
      if (governments == null || legalTender == null) {
        continue;
      }
      long weightSum = 0L;
      for (long weight : weightsByZone.get(zoneId).values()) {
        weightSum = Math.addExact(weightSum, weight);
      }
      tables.put(
          zoneId,
          new PortTaxInput.ZoneTaxTable(
              legalTender,
              governments,
              zoneRates(taxAccumulatorsByZone.getOrDefault(zoneId, Map.of()), weightSum)));
    }
    return new PortTaxInput(tables);
  }

  /**
   * ★★ <b>一个归属键 → 收税政府份额</b>（{@code null} = 这一份收不了钱，不进税表）。
   *
   * <p>★★ <b>权威来自 {@code Government.treasury()}，不按 {@code hh-gov-} 前缀猜</b>：政府身份由 GOV 单位 id 派生
   * （{@code GovernmentIds.ofUnit}），国库 actor 从政府记录上取。★ 只有 {@code ActorKind.HOUSEHOLD} 的国库进得来 ——
   * 账户主体只有家户（{@code applyTransfer} 的 fail-closed 契约），{@code GOVERNMENT} actor 国库收不了税： 那种情况<b>具名记一条
   * INFO</b> 并把它排除（不静默按前缀造一个家户）。
   */
  private static PortTaxInput.GovernmentShare governmentShareOf(
      EconomyData economy, String ownerKey, long weight, long day) {
    if (PortExposureEdges.UNGOVERNED_OWNER_KEY.equals(ownerKey)) {
      return null; // 三不管：没有政府可收（那一份交 0）
    }
    GovernmentId governmentId;
    try {
      governmentId = GovernmentIds.ofUnit(ownerKey);
    } catch (IllegalArgumentException bad) {
      LOG.info(
          LogEvent.of(
              "GOV_PORT_TAX_OWNER_KEY_ILLEGAL",
              AppLogSource.DAILY_LOOP,
              "day",
              day,
              "owner",
              ownerKey,
              "reason",
              "government-unit-id-cannot-be-derived-into-a-government-id"));
      return null;
    }
    Government government = economy.governments().get(governmentId);
    if (government == null) {
      LOG.info(
          LogEvent.of(
              "GOV_PORT_TAX_NO_GOVERNMENT_RECORD",
              AppLogSource.DAILY_LOOP,
              "day",
              day,
              "owner",
              ownerKey,
              "government",
              governmentId.value(),
              "reason",
              "exposure-edge-owner-has-no-government-record-so-it-cannot-collect"));
      return null;
    }
    if (government.treasury().kind() != ActorKind.HOUSEHOLD) {
      LOG.info(
          LogEvent.of(
              "GOV_PORT_TAX_TREASURY_NOT_HOUSEHOLD",
              AppLogSource.DAILY_LOOP,
              "day",
              day,
              "owner",
              ownerKey,
              "government",
              governmentId.value(),
              "treasuryKind",
              government.treasury().kind().name(),
              "reason",
              "only-household-treasuries-can-receive-transfers"));
      return null;
    }
    return new PortTaxInput.GovernmentShare(governmentId.value(), government.treasury(), weight);
  }

  /**
   * ★★ <b>区的法定币</b>（{@code MarketZone.legalTender} —— 区的<b>唯一货币事实</b>）：税率的量纲与折算的起点都是它。
   *
   * <p>★ 查无这个区（不可能：接触面就是从区表建出来的）⇒ {@code null} 并具名记一条 DEBUG，本区不进税表（收 0）。
   */
  private static CurrencyId legalTenderOf(EconomyData economy, String zoneId, long day) {
    MarketZone zone = MarketZoneBook.zone(economy, new MarketZoneId(zoneId)).orElse(null);
    if (zone == null) {
      LOG.debug(
          LogEvent.of(
              "GOV_PORT_TAX_ZONE_NOT_FOUND",
              AppLogSource.DAILY_LOOP,
              "day",
              day,
              "zone",
              zoneId,
              "reason",
              "market-zone-not-found-so-no-legal-tender-so-no-tax"));
      return null;
    }
    return zone.legalTender();
  }

  /** 商品类：建该方向的接触面 → 聚合 → DEBUG/TRACE 一条（两个方向的算式逐字相同，只有 s 的来源不同）。 */
  private static ZonePortRegime aggregateCommodity(
      long day,
      String zoneId,
      String classKey,
      List<String> ownerKeys,
      Map<String, Long> weights,
      GovState govState,
      UnitState units,
      Map<UnitId, GovEfficiency.Efficiency> efficiencyByUnit,
      PortDirection direction) {
    CommodityId commodity = new CommodityId(classKey);
    ZonePortRegime regime =
        PortRegimeAggregation.aggregateCommodity(
            zoneId,
            commodity,
            direction,
            surfaces(
                day,
                zoneId,
                classKey,
                "commodity",
                null,
                direction,
                ownerKeys,
                weights,
                policy -> policy.ruleOfCommodity(commodity),
                govState,
                units,
                efficiencyByUnit));
    logRegime(day, regime, null);
    return regime;
  }

  /**
   * 币种类：同 {@link #aggregateCommodity}，类键换成<b>（币种 × 挂单类型）</b>（P-T1e）。
   *
   * @param classKey <b>读数键</b>（{@code 币种#类型}，见 {@link #currencyReadingKey}）；稳定 id 在 {@code
   *     currency}
   * @param currency 币种稳定 id（接触面的规则按它取）
   * @param orderKind 挂单类型（同一个币上"兑换/货↔钱/借贷"是三条独立规则）
   */
  private static ZonePortRegime aggregateCurrency(
      long day,
      String zoneId,
      String classKey,
      CurrencyId currency,
      MarketOrderKind orderKind,
      List<String> ownerKeys,
      Map<String, Long> weights,
      GovState govState,
      UnitState units,
      Map<UnitId, GovEfficiency.Efficiency> efficiencyByUnit,
      PortDirection direction) {
    ZonePortRegime regime =
        PortRegimeAggregation.aggregateCurrency(
            zoneId,
            currency,
            direction,
            surfaces(
                day,
                zoneId,
                classKey,
                "currency",
                orderKind,
                direction,
                ownerKeys,
                weights,
                policy -> policy.ruleOfCurrency(currency, orderKind),
                govState,
                units,
                efficiencyByUnit));
    logRegime(day, regime, orderKind);
    return regime;
  }

  /**
   * 某个政府在"该方向该（币种 × 挂单类型）"上的规则取法（商品/币种两族共用 {@link #surfaces} 的唯一差别）。
   *
   * <p>★ 用函数式入参而不是再写一遍循环：接触面的组装（权重、效率、三不管、日志）逐字只有一份。
   */
  @FunctionalInterface
  private interface RuleLookup {

    /** 该政策对这一类这一方向的规则（缺键由 {@code GovPortPolicy} 给 {@link PortRule#unrestricted()}）。 */
    PortRule ruleOf(GovPortPolicy policy);
  }

  /**
   * 逐接触面：{@code s} = 该归属政府的政策值<b>（本方向、本挂单类型）</b>（三不管/无政策 ⇒ 0）；{@code e} = 该政府的口岸效率（无 ⇒ 0）。
   *
   * <p>★ 税（{@link PortTaxRule}）<b>不进接触面契约</b>（它只装限制强度与效率），本方法顺带 TRACE 记一条 {@code
   * PORT_SURFACE_TAX}——商品那份自 P-T1b 起**真的收**；币种那份（手续费）本批<b>只落形状</b>（见 {@link
   * #logCurrencyFeeShapedOnly}）。
   */
  private static List<PortContactSurface> surfaces(
      long day,
      String zoneId,
      String classKey,
      String classKind,
      MarketOrderKind orderKind,
      PortDirection direction,
      List<String> ownerKeys,
      Map<String, Long> weights,
      RuleLookup ruleLookup,
      GovState govState,
      UnitState units,
      Map<UnitId, GovEfficiency.Efficiency> efficiencyByUnit) {
    List<PortContactSurface> surfaces = new ArrayList<>(ownerKeys.size());
    for (String ownerKey : ownerKeys) {
      UnitId owner = ownerUnitOf(units, ownerKey);
      GovPortPolicy policy = policyOf(govState, units, ownerKey);
      // 三不管：没有政府 ⇒ 无从设限（s = 0；policy 也是 empty ⇒ 查表结果同样是 unrestricted）。
      PortRule rule = ruleLookup.ruleOf(policy);
      long restriction = rule.restriction(direction);
      long efficiency = 0L;
      if (owner != null) {
        efficiency = efficiencyOf(efficiencyByUnit, owner);
      }
      logSurfaceTax(
          day, zoneId, classKey, classKind, orderKind, direction, ownerKey, rule.tax(direction));
      surfaces.add(
          new PortContactSurface(ownerKey, weights.get(ownerKey), restriction, efficiency));
    }
    return surfaces;
  }

  /** 该归属键对应的 GOV 单位（{@link PortExposureEdges#UNGOVERNED_OWNER_KEY} / 查无 ⇒ {@code null}）。 */
  private static UnitId ownerUnitOf(UnitState units, String ownerKey) {
    if (PortExposureEdges.UNGOVERNED_OWNER_KEY.equals(ownerKey)) {
      return null;
    }
    for (Unit unit : units.units().values()) {
      if (unit.id().value().equals(ownerKey)) {
        return unit.id();
      }
    }
    return null;
  }

  /** 该归属键的口岸政策（三不管/查无/未设 ⇒ {@link GovPortPolicy#empty()} = 不限制、不收税）。 */
  private static GovPortPolicy policyOf(GovState govState, UnitState units, String ownerKey) {
    UnitId owner = ownerUnitOf(units, ownerKey);
    return owner == null ? GovPortPolicy.empty() : govState.portPolicyOrDefault(owner);
  }

  /** 该政府的口岸效率（‰）；缺失 ⇒ 0（"没有口岸编制" = 管不住 ⇒ 该接触面全开）。 */
  private static long efficiencyOf(
      Map<UnitId, GovEfficiency.Efficiency> efficiencyByUnit, UnitId owner) {
    GovEfficiency.Efficiency reading = efficiencyByUnit.get(owner);
    return reading == null ? 0L : reading.portEfficiencyPerMille();
  }

  /**
   * ★ <b>N1 负向：未知商品/未知币种 ⇒ fail-closed 具名拒</b>（不静默忽略）。
   *
   * <p>判据来源：币种 = 世界词表 {@code EconomyData.currencies()}（权威）；商品 = {@link
   * EconomyVocabulary#allCommodityIds()} （词表常量）。两者都是"世界里存在这种类吗"的唯一答案面。
   */
  private static void checkKnownClasses(
      GovPortPolicy policy, EconomyData economy, String ownerKey, long day) {
    for (CommodityId commodity : policy.commodityRules().keySet()) {
      if (!EconomyVocabulary.allCommodityIds().contains(commodity.value())) {
        throw unknownClass(day, ownerKey, "commodity", commodity.value());
      }
    }
    for (CurrencyId currency : policy.currencyRules().keySet()) {
      if (!economy.currencies().containsKey(currency)) {
        throw unknownClass(day, ownerKey, "currency", currency.value());
      }
    }
  }

  /** 非法政策（未知类）的 fail-closed 出口：具名 ERROR（不降级）+ {@link IllegalStateException}。 */
  private static IllegalStateException unknownClass(
      long day, String ownerKey, String kind, String classKey) {
    LOG.error(
        LogEvent.of(
            "GOV_PORT_POLICY_UNKNOWN_CLASS",
            AppLogSource.DAILY_LOOP,
            "day",
            day,
            "owner",
            ownerKey,
            "kind",
            kind,
            "classKey",
            classKey,
            "reason",
            "unknown-class-in-port-policy"));
    return new IllegalStateException(
        "口岸政策非法：未知"
            + ("currency".equals(kind) ? "币种" : "商品")
            + " "
            + classKey
            + "（政府 "
            + ownerKey
            + "，day "
            + day
            + "）——fail-closed，不静默忽略");
  }

  /**
   * DEBUG 一条（每个 zone×class×<b>方向</b>的逐段读数在 TRACE；本方法默认关，不改变任何输出）。
   *
   * @param orderKind 币种维的挂单类型（商品维传 {@code null} ⇒ 日志里写 {@code -}）
   */
  private static void logRegime(long day, ZonePortRegime regime, MarketOrderKind orderKind) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    LOG.debug(
        LogEvent.of(
            "PORT_REGIME_COMPUTED",
            AppLogSource.DAILY_LOOP,
            "day",
            day,
            "zone",
            regime.zoneId(),
            "classKey",
            regime.classKey(),
            "orderKind",
            orderKind == null ? "-" : orderKind.value(),
            "direction",
            regime.direction().value(),
            "allowedByRule",
            regime.allowedByRule(),
            "efficiencyPerMille",
            regime.efficiencyPerMille(),
            "enforcementPerMille",
            regime.enforcementPerMille(),
            "totalExposedEdges",
            regime.totalExposedEdges(),
            "noContactSurface",
            regime.noContactSurface(),
            "surfaces",
            regime.surfaces().size()));
    if (!LOG.isTraceEnabled()) {
      return;
    }
    for (ZonePortRegime.SurfaceReading reading : regime.surfaces()) {
      LOG.trace(
          LogEvent.of(
              "PORT_SURFACE_READING",
              AppLogSource.DAILY_LOOP,
              "day",
              day,
              "zone",
              regime.zoneId(),
              "classKey",
              regime.classKey(),
              "direction",
              regime.direction().value(),
              "owner",
              reading.contactKey(),
              "w",
              reading.exposedEdgeCount(),
              "s",
              reading.restrictionPerMille(),
              "e",
              reading.portEfficiencyPerMille(),
              "enforcement",
              reading.enforcementPerMille(),
              "openness",
              reading.opennessPerMille()));
    }
  }

  /** TRACE 一条：一个接触面上这一方向的税/手续费规则（{@code none} 不记，噪声少）。 */
  private static void logSurfaceTax(
      long day,
      String zoneId,
      String classKey,
      String classKind,
      MarketOrderKind orderKind,
      PortDirection direction,
      String ownerKey,
      PortTaxRule tax) {
    if (tax.taxFree() || !LOG.isTraceEnabled()) {
      return;
    }
    LOG.trace(
        LogEvent.of(
            "PORT_SURFACE_TAX",
            AppLogSource.DAILY_LOOP,
            "day",
            day,
            "zone",
            zoneId,
            "kind",
            classKind,
            "classKey",
            classKey,
            "orderKind",
            orderKind == null ? "-" : orderKind.value(),
            "direction",
            direction.value(),
            "owner",
            ownerKey,
            "taxMode",
            tax.mode().value(),
            "taxAmount",
            tax.amount(),
            "collected",
            "commodity".equals(classKind) && tax.leviesTax(),
            "reason",
            "commodity-port-tax-collected-at-settlement-p-t1b-currency-handling-fee-shaped-only-p-t1e"));
  }

  /**
   * ★★ <b>P-T1e：币种手续费"只落形状、不搬钱"的具名 INFO</b>（每个（区 × 币种 × 挂单类型）一条，任一侧设了真会收的费才发）。
   *
   * <p>★★ <b>为什么要有它</b>（派单冻结口径"不做：手续费的真收钱；若规则里含费率而当前无收款面，只落形状 + 读数 + 具名 INFO"）：
   * 用户「异种货币自然按手续费/规则来算」已经把"收手续费"写进了规则形状（{@link PortRule} 的入口/出口税，从量从价都行），
   * 但今天的钱腿恒铸<b>买方支付币</b>、货↔钱成交<b>没有</b>"兑换手续费"的收款面 ⇒ 若只静默存下这条规则，设规则的人会以为在收钱。 本条 INFO
   * 就是那句"配置被记下了，但本批不收"（§一.9：INFO = 规则设置/这一轮发生了什么）。
   *
   * <p>★ 只读：翻一遍该区各归属政府的政策取两个方向的费规则（纯读、不写状态、不改任何金额）。
   */
  private static void logCurrencyFeeShapedOnly(
      long day,
      String zoneId,
      String classKey,
      MarketOrderKind orderKind,
      GovState govState,
      UnitState units,
      List<String> ownerKeys) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    String entryFee = null;
    String exitFee = null;
    CurrencyId currency = new CurrencyId(classKey);
    for (String ownerKey : ownerKeys) {
      PortRule rule = policyOf(govState, units, ownerKey).ruleOfCurrency(currency, orderKind);
      if (rule.entryTax().leviesTax()) {
        entryFee = rule.entryTax().toString();
      }
      if (rule.exitTax().leviesTax()) {
        exitFee = rule.exitTax().toString();
      }
    }
    if (entryFee == null && exitFee == null) {
      return;
    }
    LOG.info(
        LogEvent.of(
            "GOV_PORT_CURRENCY_FEE_SHAPED_ONLY",
            AppLogSource.DAILY_LOOP,
            "day",
            day,
            "zone",
            zoneId,
            "currency",
            classKey,
            "orderKind",
            orderKind.value(),
            "entryFee",
            entryFee == null ? "none" : entryFee,
            "exitFee",
            exitFee == null ? "none" : exitFee,
            "collected",
            false,
            "reason",
            "currency-handling-fee-has-no-collection-surface-in-this-batch"));
  }

  private static List<String> sorted(Set<String> values) {
    List<String> ordered = new ArrayList<>(values == null ? Set.of() : values);
    ordered.sort(Comparator.naturalOrder());
    return ordered;
  }
}
