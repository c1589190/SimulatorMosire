package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
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
 * ★★★ <b>一个生产实例的真实参与名册</b>（D-032 D2 §5.2；{@code EconomyData} 第 32 个组件）。
 *
 * <p>★★ <b>它是唯一权威</b>：哪个 unit、哪些家户、在哪个 {@link ProductionRoleId}、谁是组织者、每户计划劳动、 哪些份额被引用、配置权重多少 ——
 * 全部只在这里。production 只从执行请求接收，<b>不持久持有名册</b>。
 *
 * <p>★ <b>名册不是第二份方案定义</b>：角色份额 / 经验系数 / 标准劳动只来自 production 的角色定义；本类型只保存 "谁在哪个角色上参与"这一事实，不复制任何角色配置。
 *
 * <p>★ <b>构造期只做不依赖 production 定义的校验</b>（null、非空、键、organizer 唯一、非负、份额去重、冻结）。角色
 * 存在性、角色规则、份额合计、资格、owner 等跨切片校验在 {@code EconomyReferenceValidation#validateProductionRosters}。
 *
 * @param unit 生产实例稳定 ID；{@code EconomyData.productionRosters} 的键必须等于它
 * @param plan 实例引用的方案 ID；必须与 production 实例的 plan 一致（跨切片校验）
 * @param participants 参与项；非空、保序不可变、逐项非 null；{@code (household, role)} 唯一
 */
public record ProductionRoster(
    ProductionUnitId unit, IndustryId plan, List<ProductionParticipant> participants) {

  public ProductionRoster {
    Objects.requireNonNull(unit, "ProductionRoster.unit 不得为 null");
    Objects.requireNonNull(plan, "ProductionRoster.plan 不得为 null");
    if (participants == null) {
      throw new IllegalArgumentException("ProductionRoster.participants 不得为 null");
    }
    if (participants.isEmpty()) {
      throw new IllegalArgumentException(
          "ProductionRoster.participants 不得为空；清除名册请用 withoutProductionRoster: " + unit.value());
    }
    List<ProductionParticipant> participantCopy = new ArrayList<>(participants.size());
    Set<ParticipantSlot> slots = new LinkedHashSet<>();
    int organizers = 0;
    for (ProductionParticipant participant : participants) {
      if (participant == null) {
        throw new IllegalArgumentException("ProductionRoster.participants 不得含 null");
      }
      ParticipantSlot slot = new ParticipantSlot(participant.household(), participant.role());
      if (!slots.add(slot)) {
        throw new IllegalArgumentException(
            "ProductionRoster 的 (household, role) 不得重复: unit="
                + unit.value()
                + " household="
                + participant.household().value()
                + " role="
                + participant.role().value());
      }
      if (participant.organizer()) {
        organizers++;
      }
      participantCopy.add(participant);
    }
    if (organizers != 1) {
      throw new IllegalArgumentException(
          "ProductionRoster 必须恰好一个 organizer（当前 " + organizers + " 个）: unit=" + unit.value());
    }
    participants = Collections.unmodifiableList(participantCopy); // ★ 冻在赋值处
  }

  /** 整份名册的组织者（构造期已判恰好一个）。 */
  public ProductionParticipant organizer() {
    for (ProductionParticipant participant : participants) {
      if (participant.organizer()) {
        return participant;
      }
    }
    // 构造期不变量 ⇒ 不可达；保留 fail-closed 形态而不是返回 null。
    throw new IllegalStateException("ProductionRoster 没有 organizer（状态已被改坏）: " + unit.value());
  }

  /** 组织者家户。 */
  public HouseholdId organizerHousehold() {
    return organizer().household();
  }

  /** 该 unit 名册的全部参与户（按 {@code household.value()} 稳定升序、去重）—— {@code laborSources} 的派生口径。 */
  public List<HouseholdId> households() {
    Set<HouseholdId> households = new LinkedHashSet<>();
    for (ProductionParticipant participant : participants) {
      households.add(participant.household());
    }
    List<HouseholdId> ordered = new ArrayList<>(households);
    ordered.sort((left, right) -> left.value().compareTo(right.value()));
    return Collections.unmodifiableList(ordered);
  }

  /** 该户在名册里的全部参与项（同户可多角色）；保序、不可变。 */
  public List<ProductionParticipant> participantsOf(HouseholdId household) {
    Objects.requireNonNull(household, "household");
    List<ProductionParticipant> matched = new ArrayList<>();
    for (ProductionParticipant participant : participants) {
      if (participant.household().equals(household)) {
        matched.add(participant);
      }
    }
    return Collections.unmodifiableList(matched);
  }

  /** 某户在某角色上的参与项；没有 ⇒ empty。 */
  public Optional<ProductionParticipant> participant(HouseholdId household, ProductionRoleId role) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(role, "role");
    for (ProductionParticipant participant : participants) {
      if (participant.household().equals(household) && participant.role().equals(role)) {
        return Optional.of(participant);
      }
    }
    return Optional.empty();
  }

  /** 逐参与户的计划劳动之和（毫小时/日）—— 全局预算校验的输入之一。 */
  public Map<HouseholdId, Long> plannedLaborByHousehold() {
    Map<HouseholdId, Long> planned = new LinkedHashMap<>();
    for (ProductionParticipant participant : participants) {
      planned.merge(participant.household(), participant.plannedLaborMilliPerDay(), Math::addExact);
    }
    return Collections.unmodifiableMap(planned);
  }

  /** {@code (家户, 角色)} 复合键（只在构造期做重复判定，不引入旁路字符串）。 */
  private record ParticipantSlot(HouseholdId household, ProductionRoleId role) {}
}
