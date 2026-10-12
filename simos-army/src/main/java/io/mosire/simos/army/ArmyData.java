package io.mosire.simos.army;

import io.mosire.simos.util.spi.DecisionMakerId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * army 切片的完整状态树（阶段 D1 / 用户设计 D-012，2026-10-02；<b>2026-10-12 批次 3d-1 起两个组件</b>）。
 *
 * <p>★★★ <b>乙收尾（本批）起三个组件</b>：{@code armyAffiliations}（<b>军队归属表</b>：决策人 → 军队，用户裁定
 * 「<b>归属肯定归模块</b>」＋「<b>同样搞工单</b>」；写口 = {@code army.AssignDecisionMakerArmy}）放<b>末位</b> ⇒
 * 既有两个键的线格式次序一字未动。
 *
 * <p>★★ <b>两个组件的分工</b>：
 *
 * <ul>
 *   <li>{@code armies} —— <b>军队身份</b>（用户口径：「<b>单个Army单位的本质不是被Army模块打上自定义数据集的Unit吗</b>」）： 一个 Unit
 *       加上军队自己的数据（名字 + 认领的 GOV）。本批（3d-1）自 {@code SdState} 搬入；
 *   <li>{@code combats} —— <b>交战记录</b>（D-012）：单 tick 单场交战的具体记录。★ <b>本批一个字未动</b>：战斗族的其余三张表 （{@code
 *       combats} 之外还有 {@code combatStates} / {@code lossRecords} 住 sd）与 5 个战斗 handler 属
 *       <b>3d-2</b>，本批只搬"军队身份"。
 * </ul>
 *
 * <p>★★ <b>为什么交战记录归 Army 而不是 sd</b>（D-012 的动机原话："目前在地图上根本看不到当前 tick 在哪里发生了交战"）：单 tick 单场交战的
 * **具体记录**由 Army 模块持有与处理；Unit 侧只留"当前回合状态 ↔ 状态描述地址"的链接（见 {@code Unit.stateDescriptions}）。
 *
 * <p>★★ <b>跨表同键不变式</b>（照 {@code ActorData}/{@code GovState} 的"键必须等于值内的身份"）：{@code armies} 的每个键必须等于其
 * {@link Army#id()}、{@code combats} 的每个键必须等于其 {@link CombatRecord#id()}。否则同一个实体就有两处可能 不一致的
 * id，而读侧拿到哪一份全看遍历路径——这两条守卫都在构造期当场抛。
 *
 * <p>★ <b>缺键 = 空</b>（旧档/旧夹具兼容，fail-closed）：任一组件为 {@code null}（Jackson 对缺失键的 record 缺参）⇒
 * 收成空表，<b>此处不抛</b>——army 切片是阶段 D1 引入的、{@code armies} 又是本批新搬入的，这一条让"还没落过该组件的世界"仍能读回。 方向是
 * fail-closed：没提该表 = 没有任何军队身份 / 没有任何交战记录。
 *
 * <p>★ <b>保序不可变</b>：三张表都走 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap}
 * 冻结，<b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立。
 */
public record ArmyData(
    Map<ArmyId, Army> armies,
    Map<CombatRecordId, CombatRecord> combats,
    Map<DecisionMakerId, ArmyId> armyAffiliations) {

  public ArmyData {
    // ★ 缺键（null）⇒ 空表，见类注释（旧档/旧夹具兼容，fail-closed 方向）。
    if (armies == null) {
      armies = Map.of();
    }
    if (combats == null) {
      combats = Map.of();
    }
    if (armyAffiliations == null) {
      armyAffiliations = Map.of();
    }
    Map<ArmyId, Army> armiesCopy = new LinkedHashMap<>();
    for (Map.Entry<ArmyId, Army> entry : armies.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("armies 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "armies 的键必须与 Army.id 一致：键=" + entry.getKey() + "，值内 id=" + entry.getValue().id());
      }
      armiesCopy.put(entry.getKey(), entry.getValue());
    }
    Map<CombatRecordId, CombatRecord> combatsCopy = new LinkedHashMap<>();
    for (Map.Entry<CombatRecordId, CombatRecord> entry : combats.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("combats 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "combats 的键必须与 CombatRecord.id 一致：键="
                + entry.getKey()
                + "，值内 id="
                + entry.getValue().id());
      }
      combatsCopy.put(entry.getKey(), entry.getValue());
    }
    Map<DecisionMakerId, ArmyId> affiliationsCopy = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, ArmyId> entry : armyAffiliations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("armyAffiliations 的键与值都不得为 null: " + entry.getKey());
      }
      affiliationsCopy.put(entry.getKey(), entry.getValue());
    }
    armies = Collections.unmodifiableMap(armiesCopy); // ★ 冻在赋值处
    combats = Collections.unmodifiableMap(combatsCopy); // ★ 冻在赋值处
    armyAffiliations = Collections.unmodifiableMap(affiliationsCopy); // ★ 冻在赋值处
  }

  /** 往返用例的起点：三张表皆空（= 本世界还没有任何军队身份、交战记录与军队归属）。 */
  public static ArmyData empty() {
    return new ArmyData(Map.of(), Map.of(), Map.of());
  }

  /** 一个组件一个 with（照 {@code ActorData} / {@code GovState} 的形制）：仅替换 {@code armies}。 */
  public ArmyData withArmies(Map<ArmyId, Army> value) {
    return new ArmyData(value, combats, armyAffiliations);
  }

  /** 一个组件一个 with：仅替换 {@code combats}。 */
  public ArmyData withCombats(Map<CombatRecordId, CombatRecord> value) {
    return new ArmyData(armies, value, armyAffiliations);
  }

  /**
   * ★★ <b>仅替换 {@code armyAffiliations}</b>（乙收尾：写口 = {@code army.AssignDecisionMakerArmy}），其余两张表原样。
   *
   * <p>★ 键 = 决策人 id、值 = {@link ArmyId}；<b>不在这里判"这个军队存在吗"</b>（存在性由命令期具名拒 + 读侧 {@link
   * ArmyAffiliations#resolve} 的裁剪兜底）。
   */
  public ArmyData withArmyAffiliations(Map<DecisionMakerId, ArmyId> value) {
    return new ArmyData(armies, combats, value);
  }

  /**
   * ★ <b>单条军队身份的写入口</b>：<b>键从值派生</b>（{@code army.id()} 就是键）。
   *
   * <p>★ 为什么必须有它：{@link io.mosire.simos.util.state.FieldDelta} 的 key 由 {@code toString()} 产出、重建时用
   * {@code parse} 还原，而"键与值内 id 一致"由构造器判。若只把表暴露成 {@code Map}，调用方就得自己拼键——同一个聚合键就有了第二个拼写点（照 {@code
   * ActorData.withActor} 的同一条理由）。{@code army.CreateArmy} / {@code army.SetArmyMasterGov} 是它今天的两个调用点
   * （新增与整条替换，语义同 {@link #withCombat}：同 id 后写覆盖前写，每次由 {@link
   * io.mosire.simos.army.change.ArmyChangeSet#between} 落成新 revision，旧 revision 的历史原样保留）。
   */
  public ArmyData withArmy(Army army) {
    Objects.requireNonNull(army, "army");
    Map<ArmyId, Army> next = new LinkedHashMap<>(armies);
    next.put(army.id(), army);
    return new ArmyData(next, combats, armyAffiliations);
  }

  /**
   * ★ <b>单条记录的写入口</b>：<b>键从值派生</b>（{@code combat.id()} 就是键）。
   *
   * <p>★ 为什么必须有它：同 {@link #withArmy} 的理由。**命令层的口径（D4 定）**：{@code army.RecordCombat} 对已存在的记录 id
   * **具名拒**（id 是一次性身份），但 {@code army.AppendCombatStage} / {@code army.ResolveCombatStage} 会用**同
   * id、新 stages** 的记录整条替换（阶段可追加/判定）——每次替换都由 {@link
   * io.mosire.simos.army.change.ArmyChangeSet#between} 落成新 revision，旧 revision 的历史原样保留。这条 {@code
   * withCombat} 只是那一层的公共写口。
   */
  public ArmyData withCombat(CombatRecord combat) {
    Objects.requireNonNull(combat, "combat");
    Map<CombatRecordId, CombatRecord> next = new LinkedHashMap<>(combats);
    next.put(combat.id(), combat);
    return new ArmyData(armies, next, armyAffiliations);
  }
}
