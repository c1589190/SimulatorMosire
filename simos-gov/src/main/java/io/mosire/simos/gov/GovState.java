package io.mosire.simos.gov;

import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * gov 切片的完整状态树（阶段 10a；Z2 扩为“派生读数 + 两条源状态”，设计书 §4.1）：<b>三个组件</b>—— {@code offices}（每 tick
 * 行政读数，派生）、{@code administrationPlans} / {@code budgetPolicies}（两条慢变源状态）。
 *
 * <p>★★ <b>为什么读数不写在 unit 片</b>（计划 §1 的时序约束）：{@code gov} 每 tick 变，而 {@code UnitTimeParticipant} 与
 * gov 的日结算分属不同写者；同一模块只能有一个写者，否则 {@code TimeProposalResolver} 按同名模块**拒整次推进**。
 * 编制字段（staff/policy/superiorGov/level）是慢变事实，住在 {@code Unit.module}；这里放每 tick 读数与 gov 自己的配置源状态。
 *
 * <p>★★ <b>跨表同键不变式</b>（照 {@code ActorData} 的“actors 的每个键必须等于其 {@code Actor.ref()}”）：{@code offices}
 * 的每个键必须等于其 {@link GovOfficeState#unitId()}。<b>两条源状态不内嵌 unitId</b>：身份只在键上（内容 record 见 {@link
 * GovAdministrationPlan} / {@link GovBudgetPolicy}），故这里只判键/值非 null。
 *
 * <p>★ <b>缺键 = 空</b>（照 {@code ActorData} 的旧档兼容口径）：三个组件为 {@code null}（Jackson 对缺失键的 record 缺参）⇒
 * 各自收成空表，<b>此处不抛</b>——旧档没有这两条源状态时，{@link #administrationPlanOrDefault(UnitId)} / {@link
 * #budgetPolicyOrDefault(UnitId)} 返回中性默认（计划 0、修正 1000‰、{@code k=1}、预算空=不自动付）。
 *
 * <p>★★ <b>写派生值必须原样带过源状态</b>（照 economy {@code periodicAdjustments} 的拷贝纪律）：{@link GovDaily} 与 app
 * 参与者只改 {@code offices} 时，必须用 {@link #withOffices(Map)} 或把两条源状态原样传给 canonical 构造器；漏带 = 静默清空配置。
 *
 * <p>★ <b>保序不可变</b>：三张表都 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结，
 * <b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立。
 *
 * @param offices GOV 单位 → 每 tick 读数（键 == 值内 {@code unitId}；保序不可变；缺键读成 {@link Map#of()}）
 * @param administrationPlans GOV 单位 → 行政编制计划（键非 null、值非 null；缺键读成空表 = 中性默认）
 * @param budgetPolicies GOV 单位 → 国库预算政策（键非 null、值非 null；缺键读成空表 = 不自动付）
 */
public record GovState(
    Map<UnitId, GovOfficeState> offices,
    Map<UnitId, GovAdministrationPlan> administrationPlans,
    Map<UnitId, GovBudgetPolicy> budgetPolicies) {

  public GovState {
    // ★ 缺键（null）⇒ 空表，见类注释（旧档/夹具兼容，fail-closed 方向）。
    offices = Collections.unmodifiableMap(copyOffices(offices));
    administrationPlans = Collections.unmodifiableMap(copyPlans(administrationPlans));
    budgetPolicies = Collections.unmodifiableMap(copyPolicies(budgetPolicies));
  }

  /**
   * 旧构造器兼容（Z2 加字段前的调用点/旧档夹具）：两条源状态取空表。新代码请用 canonical 3 参构造器， 或在改 {@code offices} 时用 {@link
   * #withOffices(Map)} 带过既有源状态。
   */
  public GovState(Map<UnitId, GovOfficeState> offices) {
    this(offices, Map.of(), Map.of());
  }

  /** 往返用例的起点：三个组件全空（= 本世界还没有任何 GOV 读数/配置）。 */
  public static GovState empty() {
    return new GovState(Map.of(), Map.of(), Map.of());
  }

  /**
   * 一个组件一个 with（照 {@code ActorData} / {@code EconomyData} 的形制）。
   *
   * <p>★★ <b>本方法保留两条源状态</b>（Z2 拷贝纪律）：它是 {@link GovDaily} / app 重写 {@code offices} 时的推荐入口。
   */
  public GovState withOffices(Map<UnitId, GovOfficeState> value) {
    return new GovState(value, administrationPlans, budgetPolicies);
  }

  /**
   * ★ <b>单个 GOV 读数的写入口</b>：<b>键从值派生</b>（{@code office.unitId()} 就是键）。
   *
   * <p>★ 为什么必须有它：{@link io.mosire.simos.util.state.FieldDelta} 的 key 由 {@code toString()} 产出、 重建时用
   * {@code parse} 还原，而“键与值内 unitId 一致”由构造器判。若只把表暴露成 {@code Map}，调用方就得自己拼键—— 同一个聚合键就有了第二个拼写点（照
   * {@code ActorData.withActor} 的同一条理由）。
   *
   * <p>★ 同一个键写两次 = 后写覆盖前写（{@code LinkedHashMap} 的 {@code put} 保留首次插入位置、只换值）。
   */
  public GovState withOffice(GovOfficeState office) {
    Objects.requireNonNull(office, "office");
    Map<UnitId, GovOfficeState> next = new LinkedHashMap<>(offices);
    next.put(office.unitId(), office);
    return new GovState(next, administrationPlans, budgetPolicies);
  }

  /** 写一条编制计划（键 = GOV 单位；内容不内嵌身份）：整条替换，保留其余组件。 */
  public GovState withAdministrationPlan(UnitId unitId, GovAdministrationPlan plan) {
    Objects.requireNonNull(unitId, "unitId");
    Objects.requireNonNull(plan, "plan");
    Map<UnitId, GovAdministrationPlan> next = new LinkedHashMap<>(administrationPlans);
    next.put(unitId, plan);
    return new GovState(offices, next, budgetPolicies);
  }

  /** 写一条预算政策（键 = GOV 单位；内容不内嵌身份）：整条替换，保留其余组件。 */
  public GovState withBudgetPolicy(UnitId unitId, GovBudgetPolicy policy) {
    Objects.requireNonNull(unitId, "unitId");
    Objects.requireNonNull(policy, "policy");
    Map<UnitId, GovBudgetPolicy> next = new LinkedHashMap<>(budgetPolicies);
    next.put(unitId, policy);
    return new GovState(offices, administrationPlans, next);
  }

  /** 该 GOV 的显式编制计划（缺席 = 旧档/未配置，由 {@link #administrationPlanOrDefault} 给中性默认）。 */
  public Optional<GovAdministrationPlan> administrationPlan(UnitId unitId) {
    Objects.requireNonNull(unitId, "unitId");
    return Optional.ofNullable(administrationPlans.get(unitId));
  }

  /** 该 GOV 的编制计划；缺省 ⇒ {@link GovAdministrationPlan#neutral()}（旧档中性默认）。 */
  public GovAdministrationPlan administrationPlanOrDefault(UnitId unitId) {
    return administrationPlan(unitId).orElseGet(GovAdministrationPlan::neutral);
  }

  /** 该 GOV 的显式预算政策（缺席 = 旧档/未配置，由 {@link #budgetPolicyOrDefault} 给“不自动付”）。 */
  public Optional<GovBudgetPolicy> budgetPolicy(UnitId unitId) {
    Objects.requireNonNull(unitId, "unitId");
    return Optional.ofNullable(budgetPolicies.get(unitId));
  }

  /** 该 GOV 的预算政策；缺省 ⇒ {@link GovBudgetPolicy#neutral()}（空表 + 零工资 = 不自动付）。 */
  public GovBudgetPolicy budgetPolicyOrDefault(UnitId unitId) {
    return budgetPolicy(unitId).orElseGet(GovBudgetPolicy::neutral);
  }

  /** offices 拷贝 + 同键不变式；null ⇒ 空表（旧档缺键），返回可继续冻结的普通表。 */
  private static Map<UnitId, GovOfficeState> copyOffices(Map<UnitId, GovOfficeState> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<UnitId, GovOfficeState> copy = new LinkedHashMap<>();
    for (Map.Entry<UnitId, GovOfficeState> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("offices 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().unitId())) {
        throw new IllegalArgumentException(
            "offices 的键必须与 GovOfficeState.unitId 一致：键="
                + entry.getKey()
                + "，值内 unitId="
                + entry.getValue().unitId());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** 编制计划表拷贝（键/值非 null；内容不内嵌身份）；null ⇒ 空表（旧档缺键）。 */
  private static Map<UnitId, GovAdministrationPlan> copyPlans(
      Map<UnitId, GovAdministrationPlan> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<UnitId, GovAdministrationPlan> copy = new LinkedHashMap<>();
    for (Map.Entry<UnitId, GovAdministrationPlan> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("administrationPlans 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** 预算政策表拷贝（键/值非 null；内容不内嵌身份）；null ⇒ 空表（旧档缺键）。 */
  private static Map<UnitId, GovBudgetPolicy> copyPolicies(Map<UnitId, GovBudgetPolicy> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<UnitId, GovBudgetPolicy> copy = new LinkedHashMap<>();
    for (Map.Entry<UnitId, GovBudgetPolicy> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("budgetPolicies 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }
}
