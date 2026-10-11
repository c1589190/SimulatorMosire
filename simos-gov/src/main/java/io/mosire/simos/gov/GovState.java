package io.mosire.simos.gov;

import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * gov 切片的完整状态树（阶段 10a；Z2 扩为“派生读数 + 两条源状态”，Z7c 再扩 remittance 周期账，<b>R2 再扩口岸政策</b>）： ★★ <b>追加裁定
 * 12（2026-10-12 §21.9 D-1）后只剩两个组件</b> —— {@code offices}（每 tick 行政读数，派生）与 {@code
 * remittanceStates}（Z7c：每 GOV 的周期实收税累计 + 最近一次关账执行读数）。
 *
 * <p>★★★ <b>三类政策已搬出本切片</b>（用户原话「口岸本身肯定是交给经济模块负责维护的……GOV 只调用相关修改，不负责管理机制」、 「B，GOV 不应该负责存」）：{@code
 * administrationPlans} / {@code budgetPolicies} / {@code portPolicies} 三条 <b>整表删除</b>，政策值改挂 {@code
 * EconomyData.governments} 的 {@code Government} 记录（写口 = {@code
 * economy.SetGovernmentPolicy}，GM-only）。本切片从此<b>不存任何政策</b>，只留"每 tick 读数 + 上缴周期账"。
 *
 * <ul>
 *   <li>★ <b>结构红利</b>：政府记录没了 ⇒ 它的政策跟着没了 ⇒ "GOV 删除后政策悬空"在结构上不可能出现（无需级联清理、无需悬空检测）；
 *   <li>★ <b>旧档不兼容</b>（AGENTS §一.11）：形状变 ⇒ 旧档读不了就读不了，<b>不写</b>归一/兼容位/别名/双读。
 * </ul>
 *
 * <p>★★ <b>为什么读数不写在 unit 片</b>（计划 §1 的时序约束）：{@code gov} 每 tick 变，而 {@code UnitTimeParticipant} 与
 * gov 的日结算分属不同写者；同一模块只能有一个写者，否则 {@code TimeProposalResolver} 按同名模块**拒整次推进**。 虽然三类政策已搬走，{@code
 * offices}/ 上缴账仍每 tick 变，故本节结论不变。
 *
 * <p>★★ <b>跨表同键不变式</b>（照 {@code ActorData} 的“actors 的每个键必须等于其 {@code Actor.ref()}”）：{@code offices}
 * 的每个键必须等于其 {@link GovOfficeState#unitId()}。<b>两条源状态不内嵌 unitId</b>：身份只在键上（内容 record 见 {@link
 * GovRemittanceState}），故这里只判键/值非 null。
 *
 * <p>★ <b>缺键 = 空</b>（照 {@code ActorData} 的旧档兼容口径）：两个组件为 {@code null}（Jackson 对缺失键的 record 缺参）⇒
 * 各自收成空表，<b>此处不抛</b>——{@link #remittanceStateOrDefault(UnitId)} 返回中性默认（remittance 全 0）。
 *
 * <p>★★ <b>写派生值必须原样带过源状态</b>（照 economy {@code periodicAdjustments} 的拷贝纪律）：{@link GovDaily} 与 app
 * 参与者只改 {@code offices} 时，必须用 {@link #withOffices(Map)}；漏带 = 静默清空上缴周期账。
 *
 * <p>★ <b>保序不可变</b>：两张表都 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结，
 * <b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立。
 *
 * @param offices GOV 单位 → 每 tick 读数（键 == 值内 {@code unitId}；保序不可变；缺键读成 {@link Map#of()}）
 * @param remittanceStates GOV 单位 → remittance 周期账 / 最近关账读数（键非 null、值非 null；缺键读成空表 = 全 0）
 */
public record GovState(
    Map<UnitId, GovOfficeState> offices, Map<UnitId, GovRemittanceState> remittanceStates) {

  public GovState {
    // ★ 缺键（null）⇒ 空表，见类注释（fail-closed 方向）。
    offices = Collections.unmodifiableMap(copyOffices(offices));
    remittanceStates = Collections.unmodifiableMap(copyRemittances(remittanceStates));
  }

  /**
   * ★ <b>只给 offices 的形状</b>（多数调用点/夹具的入口）：上缴周期账取空表（读作全 0）。
   *
   * <p>★★ 2026-10-12（追加裁定 12 §21.9 D-1）：本构造器<b>不再有</b>"三条政策源状态"参数 —— 它们已整表搬进 {@code
   * EconomyData.governments}。旧的三参/四参/五参形状<b>直接删除</b>（AGENTS §一.11：旧设计不留兼容位）。
   */
  public GovState(Map<UnitId, GovOfficeState> offices) {
    this(offices, Map.of());
  }

  /** 往返用例的起点：两个组件全空（= 本世界还没有任何 GOV 读数/上缴周期账）。 */
  public static GovState empty() {
    return new GovState(Map.of(), Map.of());
  }

  /**
   * 一个组件一个 with（照 {@code ActorData} / {@code EconomyData} 的形制）。
   *
   * <p>★★ <b>本方法保留上缴周期账</b>（Z7c 拷贝纪律）：它是 {@link GovDaily} / app 重写 {@code offices} 时的推荐入口。
   */
  public GovState withOffices(Map<UnitId, GovOfficeState> value) {
    return new GovState(value, remittanceStates);
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
    return new GovState(next, remittanceStates);
  }

  /** 写一条 remittance 周期账（键 = GOV 单位；内容不内嵌身份）：整条替换，保留 offices。 */
  public GovState withRemittanceState(UnitId unitId, GovRemittanceState state) {
    Objects.requireNonNull(unitId, "unitId");
    Objects.requireNonNull(state, "state");
    Map<UnitId, GovRemittanceState> next = new LinkedHashMap<>(remittanceStates);
    next.put(unitId, state);
    return new GovState(offices, next);
  }

  /** 该 GOV 的 remittance 周期账（缺席 = 还没累计，由 {@link #remittanceStateOrDefault} 给全 0）。 */
  public Optional<GovRemittanceState> remittanceState(UnitId unitId) {
    Objects.requireNonNull(unitId, "unitId");
    return Optional.ofNullable(remittanceStates.get(unitId));
  }

  /** 该 GOV 的 remittance 周期账；缺省 ⇒ {@link GovRemittanceState#empty()}（全 0）。 */
  public GovRemittanceState remittanceStateOrDefault(UnitId unitId) {
    return remittanceState(unitId).orElseGet(GovRemittanceState::empty);
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

  /** remittance 周期账表拷贝（键/值非 null；内容不内嵌身份）；null ⇒ 空表（旧档缺键）。 */
  private static Map<UnitId, GovRemittanceState> copyRemittances(
      Map<UnitId, GovRemittanceState> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<UnitId, GovRemittanceState> copy = new LinkedHashMap<>();
    for (Map.Entry<UnitId, GovRemittanceState> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("remittanceStates 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }
}
