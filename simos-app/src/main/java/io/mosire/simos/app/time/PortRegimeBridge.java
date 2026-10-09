package io.mosire.simos.app.time;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.PortContactSurface;
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
 * ★★ <b>R2：逐政府实际管制力 → 市场区总效率（唯一折算点，组合根）</b>（2026-10-09 口岸设计书 §4.2/§4.3/§4.4 + §1.4-3 用户原话）。
 *
 * <pre>
 * ① 暴露边（{@link PortExposureEdges}）⇒ 逐区逐归属的接触面权重 w_k（归"管着 Z 侧那格的政府"）
 * ② 逐接触面：s_k = 该政府对这一类的限制强度（{@link GovPortPolicy}；缺省 0 = 不限制，I-P1）
 *             e_k = 该政府的口岸效率（{@link GovEfficiency.Efficiency#portEfficiencyPerMille()} 第三维）
 *             enforcement_k = ⌊s_k × e_k ÷ 1000⌋                       ← 逐政府执行，不合并成"区的一个效率"（I-P5）
 * ③ 市场区 Z 对类 c：规则 = OR（∃k: s_k = 0）；总效率 E = 按 w_k 加权平均（{@link PortRegimeAggregation}）
 * ④ 注入经济侧：本区该类"实际抓得多严" = 1000 − E（‰），进 {@code CurrencyValuation} 的家户外币估值减项
 * </pre>
 *
 * <p>★★ <b>为什么折算只能在组合根</b>：{@code simos-gov} 的 enforcer 禁 {@code simos-economy}（gov 算不了区与暴露边），
 * {@code simos-economy} 不认识 gov（算不了政策与口岸效率）—— 只有 app 同时看得见三边（设计书 F3/§4.2）。
 *
 * <p>★★ <b>I-P8（第一判据）：无政策 / 无接触面 ⇒ 逐值不变</b>：
 *
 * <ul>
 *   <li><b>类的定义域 = 政策里显式设过的类</b>（逐区取该区各归属政府的政策键并集）⇒ 一条政策都没有的世界，本类算出空的 {@link
 *       PortEnforcementInput#none()}，注入值与"没注入"逐值同义；
 *   <li>无接触面（{@code Σw = 0}）⇒ {@code E = 1000} ⇒ 注入值 {@code 1000 − E = 0}（且零值条目<b>不入表</b>，读法等价）；
 *   <li>{@code s = 0}（未设/显式 0）⇒ 该接触面 {@code openness = 1000} ⇒ 不影响 E 的分子。
 * </ul>
 *
 * <p>★★ <b>负向（N1）：非法政策 fail-closed 具名拒，不静默忽略</b> —— "负强度"已在 {@link GovPortPolicy} 构造期拒（命令面 {@code
 * Rejected}，零 revision）；"未知商品/未知币种"的判据在这里（gov 模块编译期看不见经济词表）：具名 ERROR {@code
 * GOV_PORT_POLICY_UNKNOWN_CLASS} + {@link IllegalStateException}，<b>当场停</b>（不是"忽略这一条继续跑"）。
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
   * @param input 注入经济侧的逐区逐类实际管制力（{@code 1000 − E}；空 ⇒ 没有口岸面）
   * @param regimes 逐区逐类的总效率读数（保序；供日志/读数/探针）
   * @param exposedEdges 全部接触面的暴露边合计（读数）
   * @param contacts 接触面条数（读数）
   * @param restrictedClasses 设过限制的类数（读数；0 ⇒ 没有口岸面）
   */
  public record PortRegimeDay(
      PortEnforcementInput input,
      List<ZonePortRegime> regimes,
      long exposedEdges,
      int contacts,
      int restrictedClasses) {

    public PortRegimeDay {
      Objects.requireNonNull(input, "input");
      regimes = Collections.unmodifiableList(new ArrayList<>(regimes)); // ★ 保序冻结
    }

    /** 本轮有没有口岸面（一条限制都没有 ⇒ false ⇒ 逐值不变）。 */
    public boolean active() {
      return restrictedClasses > 0;
    }
  }

  /**
   * ★★ <b>算本轮的口岸执行规律</b>（纯函数；不写状态、不落盘、不用随机数）。
   *
   * @param govState 政府切片（口岸政策 = 逐政府 × 逐类的限制强度 s）；不得为 null
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
      return new PortRegimeDay(PortEnforcementInput.none(), List.of(), 0L, 0, 0);
    }
    // ── 逐区 × 逐归属：暴露边权重（w_k）────────────────────────────────────────────────
    Map<String, Map<String, Long>> weightsByZone = new LinkedHashMap<>();
    for (PortExposureEdges.Contact contact : contacts) {
      weightsByZone
          .computeIfAbsent(contact.zoneId(), ignored -> new LinkedHashMap<>())
          .put(contact.ownerKey(), contact.exposedEdgeCount());
    }
    // ── 逐区：类的定义域 = 该区各归属政府政策里显式设过的类（一条政策都没有 ⇒ 空）──────
    Map<String, Set<String>> restrictedCommoditiesByZone = new LinkedHashMap<>();
    Map<String, Set<String>> restrictedCurrenciesByZone = new LinkedHashMap<>();
    int restrictedClasses = 0;
    for (Map.Entry<String, Map<String, Long>> zoneEntry : weightsByZone.entrySet()) {
      Set<String> commodities = new LinkedHashSet<>();
      Set<String> currencies = new LinkedHashSet<>();
      for (String ownerKey : zoneEntry.getValue().keySet()) {
        GovPortPolicy policy = policyOf(govState, units, ownerKey);
        checkKnownClasses(policy, economy, ownerKey, day);
        for (CommodityId commodity : policy.commodityRestrictionPerMille().keySet()) {
          commodities.add(commodity.value());
        }
        for (CurrencyId currency : policy.currencyRestrictionPerMille().keySet()) {
          currencies.add(currency.value());
        }
      }
      restrictedClasses += commodities.size() + currencies.size();
      restrictedCommoditiesByZone.put(zoneEntry.getKey(), commodities);
      restrictedCurrenciesByZone.put(zoneEntry.getKey(), currencies);
    }
    if (restrictedClasses == 0) {
      // ★ I-P8：一条限制都没有 ⇒ 没有口岸面（既不聚合、也不注入；与"没注入"逐值同义）。
      return new PortRegimeDay(
          PortEnforcementInput.none(),
          List.of(),
          PortExposureEdges.totalEdges(contacts),
          contacts.size(),
          0);
    }
    // ── 逐区逐类：接触面 → 总效率（唯一算式在 economy 的 PortRegimeAggregation）──────────
    List<ZonePortRegime> regimes = new ArrayList<>();
    Map<String, Map<CurrencyId, Long>> currencyEnforcementByZone = new LinkedHashMap<>();
    Map<String, Map<CommodityId, Long>> commodityEnforcementByZone = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Long>> zoneEntry : weightsByZone.entrySet()) {
      String zoneId = zoneEntry.getKey();
      List<String> ownerKeys = new ArrayList<>(zoneEntry.getValue().keySet());
      ownerKeys.sort(Comparator.naturalOrder());
      for (String classKey : sorted(restrictedCurrenciesByZone.get(zoneId))) {
        List<PortContactSurface> surfaces =
            surfaces(
                ownerKeys, zoneEntry.getValue(), classKey, true, govState, units, efficiencyByUnit);
        ZonePortRegime regime =
            PortRegimeAggregation.aggregateCurrency(zoneId, new CurrencyId(classKey), surfaces);
        regimes.add(regime);
        long enforcement = regime.enforcementPerMille();
        if (enforcement > 0L) {
          // ★ 零值条目不进表（0 与"缺键"读法同义；表更小、注入差异更干净）。
          currencyEnforcementByZone
              .computeIfAbsent(zoneId, ignored -> new LinkedHashMap<>())
              .put(new CurrencyId(classKey), enforcement);
        }
        logRegime(day, regime);
      }
      for (String classKey : sorted(restrictedCommoditiesByZone.get(zoneId))) {
        List<PortContactSurface> surfaces =
            surfaces(
                ownerKeys,
                zoneEntry.getValue(),
                classKey,
                false,
                govState,
                units,
                efficiencyByUnit);
        ZonePortRegime regime =
            PortRegimeAggregation.aggregateCommodity(zoneId, new CommodityId(classKey), surfaces);
        regimes.add(regime);
        long enforcement = regime.enforcementPerMille();
        if (enforcement > 0L) {
          commodityEnforcementByZone
              .computeIfAbsent(zoneId, ignored -> new LinkedHashMap<>())
              .put(new CommodityId(classKey), enforcement);
        }
        logRegime(day, regime);
      }
    }
    PortEnforcementInput input =
        new PortEnforcementInput(currencyEnforcementByZone, commodityEnforcementByZone);
    return new PortRegimeDay(
        input, regimes, PortExposureEdges.totalEdges(contacts), contacts.size(), restrictedClasses);
  }

  /** 逐接触面：s = 该归属政府的政策值（三不管/无政策 ⇒ 0）；e = 该政府的口岸效率（无 ⇒ 0）。 */
  private static List<PortContactSurface> surfaces(
      List<String> ownerKeys,
      Map<String, Long> weights,
      String classKey,
      boolean currency,
      GovState govState,
      UnitState units,
      Map<UnitId, GovEfficiency.Efficiency> efficiencyByUnit) {
    List<PortContactSurface> surfaces = new ArrayList<>(ownerKeys.size());
    for (String ownerKey : ownerKeys) {
      UnitId owner = ownerUnitOf(units, ownerKey);
      GovPortPolicy policy = policyOf(govState, units, ownerKey);
      long restriction =
          owner == null
              ? 0L // 三不管：没有政府 ⇒ 无从设限（s = 0）
              : currency
                  ? policy.restrictionOfCurrency(new CurrencyId(classKey))
                  : policy.restrictionOfCommodity(new CommodityId(classKey));
      long efficiency = 0L;
      if (owner != null) {
        GovEfficiency.Efficiency reading = efficiencyByUnit.get(owner);
        efficiency = reading == null ? 0L : reading.portEfficiencyPerMille();
      }
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

  /** 该归属键的口岸政策（三不管/查无/未设 ⇒ {@link GovPortPolicy#empty()} = 不限制）。 */
  private static GovPortPolicy policyOf(GovState govState, UnitState units, String ownerKey) {
    UnitId owner = ownerUnitOf(units, ownerKey);
    return owner == null ? GovPortPolicy.empty() : govState.portPolicyOrDefault(owner);
  }

  /**
   * ★ <b>N1 负向：未知商品/未知币种 ⇒ fail-closed 具名拒</b>（不静默忽略）。
   *
   * <p>判据来源：币种 = 世界词表 {@code EconomyData.currencies()}（权威）；商品 = {@link
   * EconomyVocabulary#allCommodityIds()} （词表常量）。两者都是"世界里存在这种类吗"的唯一答案面。
   */
  private static void checkKnownClasses(
      GovPortPolicy policy, EconomyData economy, String ownerKey, long day) {
    for (CommodityId commodity : policy.commodityRestrictionPerMille().keySet()) {
      if (!EconomyVocabulary.allCommodityIds().contains(commodity.value())) {
        throw unknownClass(day, ownerKey, "commodity", commodity.value());
      }
    }
    for (CurrencyId currency : policy.currencyRestrictionPerMille().keySet()) {
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

  /** DEBUG 一条（每个 zone×class 的逐段读数在 TRACE；本方法默认关，不改变任何输出）。 */
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

  private static List<String> sorted(Set<String> values) {
    List<String> ordered = new ArrayList<>(values == null ? Set.of() : values);
    ordered.sort(Comparator.naturalOrder());
    return ordered;
  }
}
