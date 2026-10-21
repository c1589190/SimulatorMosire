package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>① 试算（roster 形态）：请求</b>（D-032 P3 约束设计书 §8；审计 A2/A3 的契约面）。
 *
 * <p>★★ <b>为什么需要它而不是把整份名册塞进单户 {@link ProductionProbeRequest}</b>：
 *
 * <ul>
 *   <li><b>A2</b>：单户 probe 只按<b>一个</b>家户求效率；而协作生产 execute 按<b>每个实际出工家户</b>分别求效率再累计。
 *       用组织者的熟练度冒充全员会系统性高估/低估预计产出。本请求把"参与者 × 角色 × 计划劳动"与"逐户条件"一起带进来， 由 production 用与 execute
 *       <b>同一个</b> {@code ProductionEfficiency} 公式逐户求效率。
 *   <li><b>A3</b>：零劳动 organizer + 工资 posting 招工是合法形态（用户 U15/U16）。本请求允许 organizer 计划劳动为 0，
 *       并允许用<b>工资招工目标</b>作为劳动需求边界（见 {@link #wageTargetLaborMilliPerDay()}），不得静默判 labor-short。
 * </ul>
 *
 * <p>★★ <b>聚合口径</b>：{@link #conditions()} 按<b>户</b>去重（同一户多角色只读一次资产/原料/熟练度）； {@link #participants()}
 * 只拆"角色与劳动"。{@link #assetOccupancy()} 是本次统一分配后该实例的实占读数（按种类， 与单户 probe 同义），<b>不</b>从各户 conditions
 * 里再拼一份"可用资产"当占用。
 *
 * <p>★★ <b>无副作用是硬约束</b>（I5）：本请求的求值不得改任何切片、不写 revision、不推进时间、不涨经验； 同一份入参 + 同一份候选态 ⇒ 同一答案（V1/V2）。
 *
 * <p>★★ <b>价格与需求不进这里</b>（§4.2）：production 只说"能产多少"；估值、排序、分配是 economy 的事。
 * 工资招工目标是<b>劳动需求边界</b>（物理量），不是价格。
 *
 * @param plan 方案稳定 ID；不得为 null
 * @param organizer 组织者家户；不得为 null，且必须出现在 {@link #participants()} 里（整份 roster 的组织者身份是 economy
 *     的事实，production 只按它判计划级资格主体）
 * @param participants 参与项（家户 × 角色 × 本周期计划劳动）；不得为 null；空表由求值给具名结果 {@code roster-empty}；逐项非
 *     null、{@code (household, role)} 不得重复
 * @param conditions 逐户生产条件；不得为 null、键值不得为 null、键必须等于条件内的家户。参与户缺条件 ⇒ 求值 fail-closed（ {@code
 *     condition-missing:<household>}），不按 0 静默。★ 本入口的劳动以 {@link #participants()} 的 {@code
 *     plannedLaborMilliPerCycle} 为准；{@link HouseholdProductionConditions#laborMilli()}
 *     不被读取（避免"同一份劳动两处口径"）
 * @param assetOccupancy 该实例本次实占的生产资料（按种类）；不得为 null（无占用用空表）、逐值必须为正（0 不进表）
 * @param durableCommodityOccupancy ★★ <b>D-032 C5a</b>：该实例本次实占的<b>耐久商品资本</b>（商品 id → 数量，如 {@code
 *     transport_horse}）；不得为 null（无此通道用空表）、逐值必须为正（0 不进表）。★ 它只作规模锚、<b>不</b>被扣减； <b>不得从各参与户 conditions
 *     聚合</b>（必须由 economy 按 operator/owner 户同一份库存填）。
 * @param carry 该实例当前周期已结转的精度余数；不得为 null（没有实例/没有余数给 {@link Optional#empty()}）
 * @param participantAttributes 逐参与户外部属性事实；不得为 null（无条件/无事实用空表）。角色级资格缺事实 ⇒ fail-closed
 * @param wageTargetLaborMilliPerDay ★ <b>工资招工目标</b>（按角色，毫小时/日）——A3 的"最小事实"。
 *     <p>★ <b>它是什么 / 不是什么</b>：它只是<b>劳动需求边界</b>（economy 侧工资招工目标的 {@code targetLaborMilliPerDay}
 *     只读投影），<b>不是</b>"已经招到人"的承诺，也<b>不</b>为它伪造 worker 参与项。求值时：
 *     <ul>
 *       <li>只对方案里 {@code payment = MONEY_WAGE} 的角色生效；键指向非工资角色 ⇒ 具名拒绝 {@code
 *           wage-target-role-not-money-wage:<role>}；
 *       <li>某角色已有正计划劳动的参与项时，目标只补足差额（{@code max(0, target - 已计划)}），不重复计劳动；
 *       <li>该目标劳动没有具名参与户 ⇒ 无工人经验事实可读，按<b>中性效率 1000‰</b>计入效率加权（见 {@link ProductionRosterProbeResult}
 *           的类注）；一旦招到实际工人，调用方应把工人作为参与项传入以获得逐户精确值。
 *     </ul>
 *     ★ 未给某工资角色的目标时，退回方案角色的 {@link ProductionRoleDefinition#standardLaborMilliPerCycle()}（既有
 *     production 契约里的最小标准劳动事实）；两者都缺/为 0 ⇒ 不凭空制造劳动，整份 roster 劳动为 0 时仍按 {@code labor-short} 具名拒绝。 不得为
 *     null；空表 = 没有额外目标
 */
public record ProductionRosterProbeRequest(
    IndustryId plan,
    HouseholdId organizer,
    List<ProductionRosterProbeParticipant> participants,
    Map<HouseholdId, HouseholdProductionConditions> conditions,
    Map<AssetKind, Long> assetOccupancy,
    Map<CommodityId, Long> durableCommodityOccupancy,
    Optional<ProductionEfficiencyState> carry,
    Map<HouseholdId, ExternalAttributeFacts> participantAttributes,
    Map<ProductionRoleId, Long> wageTargetLaborMilliPerDay) {

  /**
   * ★ <b>冻结的七入参形态</b>（不带显式工资招工目标）：等价于 {@code wageTargetLaborMilliPerDay = Map.of()}； 此时 A3 退回方案角色的
   * {@code standardLaborMilliPerCycle} 事实。★ 无耐久商品占用（空表）。
   */
  public ProductionRosterProbeRequest(
      IndustryId plan,
      HouseholdId organizer,
      List<ProductionRosterProbeParticipant> participants,
      Map<HouseholdId, HouseholdProductionConditions> conditions,
      Map<AssetKind, Long> assetOccupancy,
      Optional<ProductionEfficiencyState> carry,
      Map<HouseholdId, ExternalAttributeFacts> participantAttributes) {
    this(
        plan,
        organizer,
        participants,
        conditions,
        assetOccupancy,
        Map.of(),
        carry,
        participantAttributes,
        Map.of());
  }

  /**
   * ★ <b>C5a 之前的 8 参形状</b>（含工资招工目标、无耐久商品占用）：等价于 {@code durableCommodityOccupancy =
   * Map.of()}；旧调用点逐值不变。
   */
  public ProductionRosterProbeRequest(
      IndustryId plan,
      HouseholdId organizer,
      List<ProductionRosterProbeParticipant> participants,
      Map<HouseholdId, HouseholdProductionConditions> conditions,
      Map<AssetKind, Long> assetOccupancy,
      Optional<ProductionEfficiencyState> carry,
      Map<HouseholdId, ExternalAttributeFacts> participantAttributes,
      Map<ProductionRoleId, Long> wageTargetLaborMilliPerDay) {
    this(
        plan,
        organizer,
        participants,
        conditions,
        assetOccupancy,
        Map.of(),
        carry,
        participantAttributes,
        wageTargetLaborMilliPerDay);
  }

  public ProductionRosterProbeRequest {
    Objects.requireNonNull(plan, "ProductionRosterProbeRequest.plan 不得为 null");
    Objects.requireNonNull(organizer, "ProductionRosterProbeRequest.organizer 不得为 null");
    if (participants == null) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeRequest.participants 不得为 null（空 roster 由求值给具名结果）");
    }
    List<ProductionRosterProbeParticipant> participantCopy = new ArrayList<>();
    Set<ProductionParticipantKey> participantKeys = new LinkedHashSet<>();
    for (ProductionRosterProbeParticipant participant : participants) {
      if (participant == null) {
        throw new IllegalArgumentException("ProductionRosterProbeRequest.participants 不得含 null");
      }
      if (!participantKeys.add(participant.participantKey())) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeRequest 的 (household, role) 不得重复: "
                + participant.participantKey());
      }
      participantCopy.add(participant);
    }
    participants = Collections.unmodifiableList(participantCopy); // ★ 冻在赋值处

    if (conditions == null) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeRequest.conditions 不得为 null（无条件下用空表，但参与户会在求值时被具名拒绝）");
    }
    Map<HouseholdId, HouseholdProductionConditions> conditionCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdProductionConditions> entry : conditions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionRosterProbeRequest.conditions 的键与值都不得为 null");
      }
      if (!entry.getKey().equals(entry.getValue().household())) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeRequest.conditions 的键必须等于条件内的家户: 键="
                + entry.getKey().value()
                + " 值="
                + entry.getValue().household().value());
      }
      conditionCopy.put(entry.getKey(), entry.getValue());
    }
    conditions = Collections.unmodifiableMap(conditionCopy); // ★ 冻在赋值处

    if (assetOccupancy == null) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeRequest.assetOccupancy 不得为 null（无占用用空表）");
    }
    Map<AssetKind, Long> occupancyCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : assetOccupancy.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeRequest.assetOccupancy 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeRequest.assetOccupancy 必须为正（0 不进表）: "
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      occupancyCopy.put(entry.getKey(), entry.getValue());
    }
    assetOccupancy = Collections.unmodifiableMap(occupancyCopy); // ★ 冻在赋值处

    if (durableCommodityOccupancy == null) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeRequest.durableCommodityOccupancy 不得为 null（无占用用空表）");
    }
    Map<CommodityId, Long> durableCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : durableCommodityOccupancy.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeRequest.durableCommodityOccupancy 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeRequest.durableCommodityOccupancy 必须为正（0 不进表）: "
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      durableCopy.put(entry.getKey(), entry.getValue());
    }
    durableCommodityOccupancy = Collections.unmodifiableMap(durableCopy); // ★ 冻在赋值处

    if (carry == null) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeRequest.carry 不得为 null（无余数请给 Optional.empty()）");
    }

    if (participantAttributes == null) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeRequest.participantAttributes 不得为 null（无事实用空表）");
    }
    Map<HouseholdId, ExternalAttributeFacts> attributeCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ExternalAttributeFacts> entry : participantAttributes.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeRequest.participantAttributes 的键与值都不得为 null");
      }
      attributeCopy.put(entry.getKey(), entry.getValue());
    }
    participantAttributes = Collections.unmodifiableMap(attributeCopy); // ★ 冻在赋值处

    if (wageTargetLaborMilliPerDay == null) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeRequest.wageTargetLaborMilliPerDay 不得为 null（无目标用空表）");
    }
    Map<ProductionRoleId, Long> targetCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionRoleId, Long> entry : wageTargetLaborMilliPerDay.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeRequest.wageTargetLaborMilliPerDay 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeRequest.wageTargetLaborMilliPerDay 不得为负: "
                + entry.getKey().value());
      }
      targetCopy.put(entry.getKey(), entry.getValue());
    }
    wageTargetLaborMilliPerDay = Collections.unmodifiableMap(targetCopy); // ★ 冻在赋值处
  }

  /** 该户在 roster 里的计划劳动合计（毫小时/周期；同户多角色累加，缺户 ⇒ 0）。 */
  public long plannedLaborMilliPerCycleOf(HouseholdId household) {
    long total = 0L;
    for (ProductionRosterProbeParticipant participant : participants) {
      if (participant.household().equals(household)) {
        total = Math.addExact(total, participant.plannedLaborMilliPerCycle());
      }
    }
    return total;
  }
}
