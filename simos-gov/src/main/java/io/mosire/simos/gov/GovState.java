package io.mosire.simos.gov;

import io.mosire.simos.gov.nation.DiplomaticEvent;
import io.mosire.simos.gov.nation.DiplomaticEventId;
import io.mosire.simos.gov.nation.DiplomaticRelation;
import io.mosire.simos.gov.nation.DiplomaticRelationKey;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.DecisionMakerId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * gov 切片的完整状态树（阶段 10a；Z2 扩为“派生读数 + 两条源状态”，Z7c 再扩 remittance 周期账，<b>R2 再扩口岸政策</b>）： ★★ <b>追加裁定
 * 12（2026-10-12 §21.9 D-1）后只剩两个组件</b> —— {@code offices}（每 tick 行政读数，派生）与 {@code
 * remittanceStates}（Z7c：每 GOV 的周期实收税累计 + 最近一次关账执行读数）。<b>★★★ 本批（3c）再扩到五个组件</b>：外交三表整表自 {@code
 * simos-sd} 迁入（用户裁定「<b>外交是 gov 的事</b>」＋「sd 整个弃用！」）—— {@code nations} / {@code diplomaticRelations}
 * / {@code diplomaticEvents}（见下）。
 *
 * <p>★★★ <b>三类政策已搬出本切片</b>（用户原话「口岸本身肯定是交给经济模块负责维护的……GOV 只调用相关修改，不负责管理机制」、 「B，GOV 不应该负责存」）：{@code
 * administrationPlans} / {@code budgetPolicies} / {@code portPolicies} 三条 <b>整表删除</b>，政策值改挂 {@code
 * EconomyData.governments} 的 {@code Government} 记录（写口 = {@code
 * economy.SetGovernmentPolicy}，GM-only）。本切片从此<b>不存任何政策</b>，只留"每 tick 读数 + 上缴周期账"。 ⇒ <b>形状是"读数 2 +
 * 外交 3"</b>，不再是"读数 + 政策"。
 *
 * <p>★★ <b>七个组件与 {@link io.mosire.simos.gov.change.GovChangeSet} 的七个组件一一对应</b>（铁律
 * 5）：任何新增组件都要同时进变更集， 由 {@code GovRoundTripTest} 的反射枚举把守（0a 建时 2 → 3c 扩到 5 → <b>乙收尾扩到 7</b>）。
 *
 * <p>★★★ <b>乙收尾（本批）新增的两个组件 = 归属表</b>（用户裁定「<b>归属肯定归模块</b>」＋「单个决策人的具体归属，交给调用 ga
 * 的具体模块不行吗？<b>同样搞工单</b>」）：{@code nationAffiliations}（决策人 → 国家）与 {@code govAffiliations}（决策人 → GOV
 * 单位）。它们放在<b>末位</b> ⇒ 既有五个键的线格式次序一字未动。★ 写口是两条新命令 （{@code gov.AssignDecisionMakerNation} / {@code
 * gov.AssignDecisionMakerGov}），读口是本模块的两个裁定视图 （{@link io.mosire.simos.gov.nation.NationAffiliations}
 * / {@link io.mosire.simos.gov.GovAffiliations}）。
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
 * 的每个键必须等于其 {@link GovOfficeState#unitId()}。<b>外交三表照 sd 侧原样</b>：{@code nations} 的键与 {@code
 * Nation.id()} 由写入方（{@code gov.CreateNation}）保证一致，但本构造器**只判键/值非 null**（不新增 sd 时代没有的构造期拒绝——那会把 gov
 * 变成比 sd 更严的 守门人，属另一项决定）；两条源状态与外交事件不内嵌 unitId：身份只在键上。
 *
 * <p>★ <b>缺键 = 空</b>（照 {@code ActorData} 的旧档兼容口径）：五个组件为 {@code null}（Jackson 对缺失键的 record 缺参）⇒
 * 各自收成空表，<b>此处不抛</b>——{@link #remittanceStateOrDefault(UnitId)} 返回中性默认（remittance 全 0），{@code
 * nations} 空 ⇒ 没有国家（依赖它的读侧一律 fail-closed）。★ 这是**本模块既有的缺省口径**，不是为旧档写的迁移代码。
 *
 * <p>★★ <b>写派生值必须原样带过源状态</b>（照 economy {@code periodicAdjustments} 的拷贝纪律）：{@link GovDaily} 与 app
 * 参与者只改 {@code offices} 时，必须用 {@link #withOffices(Map)}；漏带 = 静默清空上缴周期账<b>与整个外交面</b>（本批扩到五组件后这条纪律的
 * 代价更大 ⇒ 五个 {@code withXxx} 一律显式带过其余四个）。
 *
 * <p>★ <b>保序不可变</b>：五张表都 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结，
 * <b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立。
 *
 * @param offices GOV 单位 → 每 tick 读数（键 == 值内 {@code unitId}；保序不可变；缺键读成 {@link Map#of()}）
 * @param remittanceStates GOV 单位 → remittance 周期账 / 最近关账读数（键非 null、值非 null；缺键读成空表 = 全 0）
 * @param nations 国家表（键 = {@link NationId}；2026-10-12 批次 3c 自 {@code SdState} 迁入）
 * @param diplomaticRelations 有向外交关系边（D-003；键 = (from,to) 复合键；本批自 {@code SdState} 迁入）
 * @param diplomaticEvents 外交事件记录（D-005；键 = {@link DiplomaticEventId}；本批自 {@code SdState} 迁入）
 * @param nationAffiliations <b>nation 归属表</b>（乙收尾：{@code 决策人 → 国家}；写口 = {@code
 *     gov.AssignDecisionMakerNation}）
 * @param govAffiliations <b>gov 归属表</b>（乙收尾：{@code 决策人 → GOV 单位}；写口 = {@code
 *     gov.AssignDecisionMakerGov}）
 */
public record GovState(
    Map<UnitId, GovOfficeState> offices,
    Map<UnitId, GovRemittanceState> remittanceStates,
    Map<NationId, Nation> nations,
    Map<DiplomaticRelationKey, DiplomaticRelation> diplomaticRelations,
    Map<DiplomaticEventId, DiplomaticEvent> diplomaticEvents,
    Map<DecisionMakerId, NationId> nationAffiliations,
    Map<DecisionMakerId, UnitId> govAffiliations) {

  public GovState {
    // ★ 缺键（null）⇒ 空表，见类注释（fail-closed 方向）。
    offices = Collections.unmodifiableMap(copyOffices(offices));
    remittanceStates = Collections.unmodifiableMap(copyRemittances(remittanceStates));
    nations = Collections.unmodifiableMap(copyNations(nations));
    diplomaticRelations = Collections.unmodifiableMap(copyRelations(diplomaticRelations));
    diplomaticEvents = Collections.unmodifiableMap(copyEvents(diplomaticEvents));
    nationAffiliations = Collections.unmodifiableMap(copyNationAffiliations(nationAffiliations));
    govAffiliations = Collections.unmodifiableMap(copyGovAffiliations(govAffiliations));
  }

  /**
   * ★ <b>只给 offices 的形状</b>（多数调用点/夹具的入口）：其余四张表取空表（上缴账读作全 0、没有国家、没有外交边/事件）。
   *
   * <p>★★ <b>不要用它改写既有状态</b>：它会把 {@code remittanceStates}/ 外交三表<b>静默清空</b>——改写一律走 {@link
   * #withOffices(Map)} 这类逐组件入口（见类注释的拷贝纪律）。
   *
   * <p>★★ 2026-10-12（追加裁定 12 §21.9 D-1）：本构造器<b>不再有</b>"三条政策源状态"参数 —— 它们已整表搬进 {@code
   * EconomyData.governments}。旧的三参/四参/五参形状<b>直接删除</b>（AGENTS §一.11：旧设计不留兼容位）。
   */
  public GovState(Map<UnitId, GovOfficeState> offices) {
    this(offices, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  /** 往返用例的起点：七个组件全空（= 本世界还没有任何 GOV 读数/上缴周期账/国家/外交边/外交事件/归属）。 */
  public static GovState empty() {
    return new GovState(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  /**
   * 一个组件一个 with（照 {@code ActorData} / {@code EconomyData} 的形制）。
   *
   * <p>★★ <b>本方法保留上缴周期账与外交三表</b>（Z7c / 3c 的拷贝纪律）：它是 {@link GovDaily} / app 重写 {@code offices}
   * 时的推荐入口。
   */
  public GovState withOffices(Map<UnitId, GovOfficeState> value) {
    return new GovState(
        value,
        remittanceStates,
        nations,
        diplomaticRelations,
        diplomaticEvents,
        nationAffiliations,
        govAffiliations);
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
    return new GovState(
        next,
        remittanceStates,
        nations,
        diplomaticRelations,
        diplomaticEvents,
        nationAffiliations,
        govAffiliations);
  }

  /** 写一条 remittance 周期账（键 = GOV 单位；内容不内嵌身份）：整条替换，保留其余四表。 */
  public GovState withRemittanceState(UnitId unitId, GovRemittanceState state) {
    Objects.requireNonNull(unitId, "unitId");
    Objects.requireNonNull(state, "state");
    Map<UnitId, GovRemittanceState> next = new LinkedHashMap<>(remittanceStates);
    next.put(unitId, state);
    return new GovState(
        offices,
        next,
        nations,
        diplomaticRelations,
        diplomaticEvents,
        nationAffiliations,
        govAffiliations);
  }

  /** 仅替换 {@code nations}（外交三表之 3/3：国家表），其余四个组件原样。 */
  public GovState withNations(Map<NationId, Nation> value) {
    return new GovState(
        offices,
        remittanceStates,
        value,
        diplomaticRelations,
        diplomaticEvents,
        nationAffiliations,
        govAffiliations);
  }

  /** 仅替换 {@code diplomaticRelations}（D-003 有向边表；键 = (from,to)），其余四个组件原样。 */
  public GovState withDiplomaticRelations(Map<DiplomaticRelationKey, DiplomaticRelation> value) {
    return new GovState(
        offices,
        remittanceStates,
        nations,
        value,
        diplomaticEvents,
        nationAffiliations,
        govAffiliations);
  }

  /** 仅替换 {@code diplomaticEvents}（D-005 多国谈判/外交事件记录），其余四个组件原样。 */
  public GovState withDiplomaticEvents(Map<DiplomaticEventId, DiplomaticEvent> value) {
    return new GovState(
        offices,
        remittanceStates,
        nations,
        diplomaticRelations,
        value,
        nationAffiliations,
        govAffiliations);
  }

  /**
   * ★★ <b>仅替换 {@code nationAffiliations}</b>（写口 = {@code gov.AssignDecisionMakerNation}），其余六个组件原样。
   *
   * <p>★ 键 = 决策人 id、值 = {@link NationId}；<b>不在这里判"这个国家存在吗"</b>（存在性由命令期具名拒 + 读侧裁剪兜底， 见 {@link
   * io.mosire.simos.gov.nation.NationAffiliations}）。
   */
  public GovState withNationAffiliations(Map<DecisionMakerId, NationId> value) {
    return new GovState(
        offices,
        remittanceStates,
        nations,
        diplomaticRelations,
        diplomaticEvents,
        value,
        govAffiliations);
  }

  /**
   * ★★ <b>仅替换 {@code govAffiliations}</b>（写口 = {@code gov.AssignDecisionMakerGov}），其余六个组件原样。
   *
   * <p>★ 键 = 决策人 id、值 = 那个 GOV 单位的 {@link UnitId}（"必须是带 {@code GovernmentFormation} 的单位"由命令期 具名拒 +
   * {@link io.mosire.simos.gov.GovAffiliations} 的读侧裁剪兜底）。
   */
  public GovState withGovAffiliations(Map<DecisionMakerId, UnitId> value) {
    return new GovState(
        offices,
        remittanceStates,
        nations,
        diplomaticRelations,
        diplomaticEvents,
        nationAffiliations,
        value);
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

  /** 国家表拷贝（键/值非 null）；null ⇒ 空表（见类注释的缺省口径）。 */
  private static Map<NationId, Nation> copyNations(Map<NationId, Nation> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<NationId, Nation> copy = new LinkedHashMap<>();
    for (Map.Entry<NationId, Nation> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("nations 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** 外交关系边拷贝（复合键/值非 null）；null ⇒ 空表。 */
  private static Map<DiplomaticRelationKey, DiplomaticRelation> copyRelations(
      Map<DiplomaticRelationKey, DiplomaticRelation> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<DiplomaticRelationKey, DiplomaticRelation> copy = new LinkedHashMap<>();
    for (Map.Entry<DiplomaticRelationKey, DiplomaticRelation> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("diplomaticRelations 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** 外交事件表拷贝（键/值非 null）；null ⇒ 空表。 */
  private static Map<DiplomaticEventId, DiplomaticEvent> copyEvents(
      Map<DiplomaticEventId, DiplomaticEvent> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<DiplomaticEventId, DiplomaticEvent> copy = new LinkedHashMap<>();
    for (Map.Entry<DiplomaticEventId, DiplomaticEvent> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("diplomaticEvents 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /**
   * nation 归属表拷贝（键/值非 null）；null ⇒ 空表。
   *
   * <p>★ 只有"键与值都不得为 null"这一条构造期判据（与 {@code nations} 同口径）：<b>不</b>在这里判"国家存在吗" ——
   * 那需要另一张表（同一棵树里可以判，但会让"删国家"与"改归属"互相纠缠），故存在性一律走命令期具名拒 + 读侧裁剪 （{@link
   * io.mosire.simos.gov.nation.NationAffiliations#resolve}）。
   */
  private static Map<DecisionMakerId, NationId> copyNationAffiliations(
      Map<DecisionMakerId, NationId> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<DecisionMakerId, NationId> copy = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, NationId> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("nationAffiliations 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** gov 归属表拷贝（键/值非 null）；null ⇒ 空表（判据同 {@link #copyNationAffiliations}）。 */
  private static Map<DecisionMakerId, UnitId> copyGovAffiliations(
      Map<DecisionMakerId, UnitId> map) {
    if (map == null) {
      return new LinkedHashMap<>();
    }
    Map<DecisionMakerId, UnitId> copy = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, UnitId> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("govAffiliations 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }
}
