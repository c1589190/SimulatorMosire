package io.mosire.simos.app.time;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.PortContactSurface;
import io.mosire.simos.economy.api.market.PortDirection;
import io.mosire.simos.economy.api.market.PortRule;
import io.mosire.simos.economy.api.market.PortTaxRule;
import io.mosire.simos.economy.api.market.ZonePortRegime;
import io.mosire.simos.economy.time.PortEnforcementInput;
import io.mosire.simos.economy.time.PortRegimeAggregation;
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
 * ④ 注入经济侧：逐区逐类<b>两个方向</b>的"实际抓得多严" = 1000 − E（‰），进 {@link PortEnforcementInput}
 *    —— 出口侧供"跨区两道闸"（E_源），入口侧供币种估值减项与（P-T1b 的）税
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
 * <p>★★ <b>税（P-T1a 的边界）</b>：本类<b>只校验 + 记日志</b>（{@code PORT_SURFACE_TAX}，TRACE），<b>不折算、不注入</b> ——
 * "税额怎么算、按谁的规则算、进哪个国库"是 <b>P-T1b（过境税真收款）</b>的裁定面（多政府共管一个区时"哪一家的税说了算"在 设计书里还没钉死，实现里不许替它定）。四个数字段本身由
 * {@link GovPortPolicy} 持久化 ⇒ 不会丢。
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
   * @param input 注入经济侧的逐区逐类实际管制力（逐方向 {@code 1000 − E}；空 ⇒ 没有口岸面）
   * @param regimes 逐区逐类<b>逐方向</b>的总效率读数（保序：区序 → 类序 → 入口/出口；供日志/读数/探针）
   * @param exposedEdges 全部接触面的暴露边合计（读数）
   * @param contacts 接触面条数（读数）
   * @param restrictedClasses 设过<b>非空</b>规则的类数（读数；0 ⇒ 没有口岸面）
   * @param taxedClasses 设过<b>真会收</b>的税的类数（读数；P-T1a 只进日志，真收款是 P-T1b）
   */
  public record PortRegimeDay(
      PortEnforcementInput input,
      List<ZonePortRegime> regimes,
      long exposedEdges,
      int contacts,
      int restrictedClasses,
      int taxedClasses) {

    public PortRegimeDay {
      Objects.requireNonNull(input, "input");
      regimes = Collections.unmodifiableList(new ArrayList<>(regimes)); // ★ 保序冻结
    }

    /**
     * 本轮有没有口岸面（有实际管制力才注入 ⇒ false ⇒ 逐值不变）。
     *
     * <p>★ 判据是 {@link PortEnforcementInput#isActive()}（表非空 = 至少一个区一个类真的抓得住）而不是"设过几条规则"： 一条规则设成全
     * 0、或只设了税（P-T1a 不收款）⇒ 本批一个数都不该动 ⇒ 不注入。
     */
    public boolean active() {
      return input.isActive();
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
      return new PortRegimeDay(PortEnforcementInput.none(), List.of(), 0L, 0, 0, 0);
    }
    // ── 逐区 × 逐归属：暴露边权重（w_k）────────────────────────────────────────────────
    Map<String, Map<String, Long>> weightsByZone = new LinkedHashMap<>();
    for (PortExposureEdges.Contact contact : contacts) {
      weightsByZone
          .computeIfAbsent(contact.zoneId(), ignored -> new LinkedHashMap<>())
          .put(contact.ownerKey(), contact.exposedEdgeCount());
    }
    // ── 逐区：类的定义域 = 该区各归属政府政策里显式设过**且非空**的类（一条政策都没有 ⇒ 空）──────
    Map<String, Set<String>> restrictedCommoditiesByZone = new LinkedHashMap<>();
    Map<String, Set<String>> restrictedCurrenciesByZone = new LinkedHashMap<>();
    int restrictedClasses = 0;
    int taxedClasses = 0;
    for (Map.Entry<String, Map<String, Long>> zoneEntry : weightsByZone.entrySet()) {
      Set<String> commodities = new LinkedHashSet<>();
      Set<String> currencies = new LinkedHashSet<>();
      for (String ownerKey : zoneEntry.getValue().keySet()) {
        GovPortPolicy policy = policyOf(govState, units, ownerKey);
        checkKnownClasses(policy, economy, ownerKey, day);
        for (Map.Entry<CommodityId, PortRule> rule : policy.commodityRules().entrySet()) {
          if (rule.getValue().allDefault()) {
            continue; // ★ I-P1：四个数全 0/无税 = 等于没设（显式 0 与未设同义）
          }
          commodities.add(rule.getKey().value());
          if (rule.getValue().hasEffectiveTax()) {
            taxedClasses++;
          }
        }
        for (Map.Entry<CurrencyId, PortRule> rule : policy.currencyRules().entrySet()) {
          if (rule.getValue().allDefault()) {
            continue;
          }
          currencies.add(rule.getKey().value());
          if (rule.getValue().hasEffectiveTax()) {
            taxedClasses++;
          }
        }
      }
      restrictedClasses += commodities.size() + currencies.size();
      restrictedCommoditiesByZone.put(zoneEntry.getKey(), commodities);
      restrictedCurrenciesByZone.put(zoneEntry.getKey(), currencies);
    }
    if (restrictedClasses == 0) {
      // ★ I-P8：一条有效规则都没有 ⇒ 没有口岸面（既不聚合、也不注入；与"没注入"逐值同义）。
      return new PortRegimeDay(
          PortEnforcementInput.none(),
          List.of(),
          PortExposureEdges.totalEdges(contacts),
          contacts.size(),
          0,
          0);
    }
    // ── 逐区逐类逐方向：接触面 → 总效率（唯一算式在 economy 的 PortRegimeAggregation）──────────
    List<ZonePortRegime> regimes = new ArrayList<>();
    Map<String, Map<CurrencyId, PortEnforcementInput.Directional>> currencyEnforcementByZone =
        new LinkedHashMap<>();
    Map<String, Map<CommodityId, PortEnforcementInput.Directional>> commodityEnforcementByZone =
        new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Long>> zoneEntry : weightsByZone.entrySet()) {
      String zoneId = zoneEntry.getKey();
      List<String> ownerKeys = new ArrayList<>(zoneEntry.getValue().keySet());
      ownerKeys.sort(Comparator.naturalOrder());
      for (String classKey : sorted(restrictedCurrenciesByZone.get(zoneId))) {
        ZonePortRegime entry =
            aggregateCurrency(
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
            aggregateCurrency(
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
          // ★ 零值条目不进表（0 与"缺键"读法同义；表更小、注入差异更干净）。
          currencyEnforcementByZone
              .computeIfAbsent(zoneId, ignored -> new LinkedHashMap<>())
              .put(new CurrencyId(classKey), directional);
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
        regimes,
        PortExposureEdges.totalEdges(contacts),
        contacts.size(),
        restrictedClasses,
        taxedClasses);
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
    ZonePortRegime regime =
        PortRegimeAggregation.aggregateCommodity(
            zoneId,
            new CommodityId(classKey),
            direction,
            surfaces(
                day,
                zoneId,
                classKey,
                direction,
                ownerKeys,
                weights,
                false,
                govState,
                units,
                efficiencyByUnit));
    logRegime(day, regime);
    return regime;
  }

  /** 币种类：同 {@link #aggregateCommodity}（类键换成币种）。 */
  private static ZonePortRegime aggregateCurrency(
      long day,
      String zoneId,
      String classKey,
      List<String> ownerKeys,
      Map<String, Long> weights,
      GovState govState,
      UnitState units,
      Map<UnitId, GovEfficiency.Efficiency> efficiencyByUnit,
      PortDirection direction) {
    ZonePortRegime regime =
        PortRegimeAggregation.aggregateCurrency(
            zoneId,
            new CurrencyId(classKey),
            direction,
            surfaces(
                day,
                zoneId,
                classKey,
                direction,
                ownerKeys,
                weights,
                true,
                govState,
                units,
                efficiencyByUnit));
    logRegime(day, regime);
    return regime;
  }

  /**
   * 逐接触面：{@code s} = 该归属政府的政策值<b>（本方向）</b>（三不管/无政策 ⇒ 0）；{@code e} = 该政府的口岸效率（无 ⇒ 0）。
   *
   * <p>★ 税（{@link PortTaxRule}）<b>不进接触面契约</b>（它只装限制强度与效率），本方法顺带 TRACE 记一条 {@code
   * PORT_SURFACE_TAX}——P-T1a 只落形状，真收款是 P-T1b。
   */
  private static List<PortContactSurface> surfaces(
      long day,
      String zoneId,
      String classKey,
      PortDirection direction,
      List<String> ownerKeys,
      Map<String, Long> weights,
      boolean currency,
      GovState govState,
      UnitState units,
      Map<UnitId, GovEfficiency.Efficiency> efficiencyByUnit) {
    List<PortContactSurface> surfaces = new ArrayList<>(ownerKeys.size());
    for (String ownerKey : ownerKeys) {
      UnitId owner = ownerUnitOf(units, ownerKey);
      GovPortPolicy policy = policyOf(govState, units, ownerKey);
      PortRule rule =
          owner == null
              ? PortRule.unrestricted() // 三不管：没有政府 ⇒ 无从设限（s = 0）
              : currency
                  ? policy.ruleOfCurrency(new CurrencyId(classKey))
                  : policy.ruleOfCommodity(new CommodityId(classKey));
      long restriction = rule.restriction(direction);
      long efficiency = 0L;
      if (owner != null) {
        efficiency = efficiencyOf(efficiencyByUnit, owner);
      }
      logSurfaceTax(day, zoneId, classKey, direction, ownerKey, currency, rule.tax(direction));
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

  /** DEBUG 一条（每个 zone×class×<b>方向</b>的逐段读数在 TRACE；本方法默认关，不改变任何输出）。 */
  private static void logRegime(long day, ZonePortRegime regime) {
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

  /** TRACE 一条：一个接触面上这一方向的税规则（{@code none} 不记，噪声少；P-T1a 只落形状 + 可见性，真收款是 P-T1b）。 */
  private static void logSurfaceTax(
      long day,
      String zoneId,
      String classKey,
      PortDirection direction,
      String ownerKey,
      boolean currency,
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
            currency ? "currency" : "commodity",
            "classKey",
            classKey,
            "direction",
            direction.value(),
            "owner",
            ownerKey,
            "taxMode",
            tax.mode().value(),
            "taxAmount",
            tax.amount(),
            "collected",
            false,
            "reason",
            "p-t1a-records-shape-only-collection-is-p-t1b"));
  }

  private static List<String> sorted(Set<String> values) {
    List<String> ordered = new ArrayList<>(values == null ? Set.of() : values);
    ordered.sort(Comparator.naturalOrder());
    return ordered;
  }
}
