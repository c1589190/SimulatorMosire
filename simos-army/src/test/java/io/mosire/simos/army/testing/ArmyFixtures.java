package io.mosire.simos.army.testing;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.CombatOutcomeId;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.CombatStageId;
import io.mosire.simos.army.CombatUnitLoss;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T2a（2026-10-02）army 模块的共享测试夹具：一份**两张表都非空、记录内 stages/outcomes/losses 嵌套**的合法 {@link
 * ArmyData}，以及构造各层记录的小工厂。
 *
 * <p>★ 为什么要有它：D1/D4 的不变量分散在四个类型上（{@code ArmyData} 的键=值内 id、{@code CombatRecord} 的阶段 id 唯一、{@code
 * CombatStage} 的 outcomes/判定引用、{@code CombatUnitLoss} 的表内 type 唯一），各测试类需要同一份非平凡样本；
 * 夹具集中在一处，避免每个类各拼一份、拼错的那份反倒成了"事实"。
 *
 * <p>★ 样本刻意**不是**按 id 排序/按空值骨架拼的：{@code combats} 插入序是 {@code c-2 → c-1}，记录内阶段是 {@code s1 → s2}， 阶段内
 * outcomes 是 {@code o1 → o2}，参与单位是 {@code u-2 → u-1}——任何"迭代序不是内容的纯函数"的容器都会在这些顺序断言上现形。
 */
public final class ArmyFixtures {

  public static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  public static final SimosTimestamp T0 = SimosTimestamp.of(0);

  public static final SimosTimestamp T7 = SimosTimestamp.of(7);

  public static final CombatRecordId C1 = new CombatRecordId("c-1");
  public static final CombatRecordId C2 = new CombatRecordId("c-2");
  public static final CombatRecordId C3 = new CombatRecordId("c-3");
  public static final CombatRecordId C4 = new CombatRecordId("c-4");
  public static final CombatRecordId C5 = new CombatRecordId("c-5");

  public static final CombatStageId S1 = new CombatStageId("s1");
  public static final CombatStageId S2 = new CombatStageId("s2");

  public static final CombatOutcomeId O1 = new CombatOutcomeId("o1");
  public static final CombatOutcomeId O2 = new CombatOutcomeId("o2");
  public static final CombatOutcomeId O3 = new CombatOutcomeId("o3");

  public static final UnitId U1 = new UnitId("u-1");
  public static final UnitId U2 = new UnitId("u-2");

  public static final HexCoord HEX = new HexCoord(3, 4);
  public static final HexCoord OTHER_HEX = new HexCoord(-2, 1);

  private ArmyFixtures() {}

  /** 按参数顺序建表，键**从值内的 id 派生**（不手拼第二份）。 */
  public static ArmyData data(CombatRecord... records) {
    Map<CombatRecordId, CombatRecord> combats = new LinkedHashMap<>();
    for (CombatRecord record : records) {
      combats.put(record.id(), record);
    }
    return new ArmyData(combats);
  }

  /** 无损失的结局（概率表里的一个选项）。 */
  public static CombatOutcome outcome(CombatOutcomeId id, String label, long weight) {
    return new CombatOutcome(id, label, weight, List.of());
  }

  /** 带逐单位变动的结局；{@code manpower/equipment} 两条表各一条。 */
  public static CombatOutcome outcomeWithLoss(
      CombatOutcomeId id, String label, long weight, CombatUnitLoss... losses) {
    return new CombatOutcome(id, label, weight, List.of(losses));
  }

  /** 一个单位的损失/变动：人力一条 + 装备一条（有符号增量，与 {@code unit.AdjustComposition} 同形）。 */
  public static CombatUnitLoss loss(
      UnitId unit, String manpowerType, long manpower, String equipmentType, long equipment) {
    return new CombatUnitLoss(
        unit,
        List.of(new CompositionDelta(manpowerType, manpower)),
        List.of(new CompositionDelta(equipmentType, equipment)));
  }

  /** 未判定的阶段（判定留给 {@code ResolveCombatStage} 的两条路径）。 */
  public static CombatStage stage(
      CombatStageId id,
      String name,
      List<UnitId> participants,
      String text,
      List<CombatOutcome> outcomes) {
    return new CombatStage(
        id, name, participants, text, outcomes, Optional.empty(), Optional.empty());
  }

  /** 一条最小记录：阶段 {@code s1}、两个等权结局 {@code o1/o2}（1:1——顺序与落点的判别力最强）。 */
  public static CombatRecord duelRecord(CombatRecordId id, long tick) {
    return new CombatRecord(
        id,
        "野战",
        tick,
        HEX,
        List.of(U1, U2),
        "记录级过程 " + id.value(),
        List.of(
            stage(
                S1,
                "接触",
                List.of(U1, U2),
                "阶段过程 " + id.value(),
                List.of(outcome(O1, "胜", 1L), outcome(O2, "负", 1L)))));
  }

  /** 一条最小记录：只有一个结局的阶段（边界落点用）。 */
  public static CombatRecord singleOutcomeRecord(CombatRecordId id, long tick) {
    return new CombatRecord(
        id,
        "野战",
        tick,
        HEX,
        List.of(U1),
        "记录级过程 " + id.value(),
        List.of(stage(S1, "接触", List.of(U1), "阶段过程 " + id.value(), List.of(outcome(O1, "胜", 1L)))));
  }

  /** 一份非平凡切片：两条记录、三条阶段、四个结局（含 losses），插入序见类注。 */
  public static ArmyData sampleData() {
    CombatRecord c1 =
        new CombatRecord(
            C1,
            "野战",
            12L,
            HEX,
            List.of(U2, U1),
            "记录级过程：接触后转入续战",
            List.of(
                stage(
                    S1,
                    "接触",
                    List.of(U2, U1),
                    "阶段一：前锋接敌",
                    List.of(
                        outcomeWithLoss(O1, "胜", 60L, loss(U1, "士兵", -30L, "步枪", -5L)),
                        outcomeWithLoss(O2, "惨胜", 40L, loss(U2, "士兵", -10L, "步枪", -1L)))),
                stage(
                    S2,
                    "续战",
                    List.of(U1),
                    "阶段二：追歼",
                    List.of(outcomeWithLoss(O3, "城破", 100L, loss(U1, "士兵", -5L, "火炮", -1L))))));
    CombatRecord c2 =
        new CombatRecord(
            C2,
            "轰城",
            9L,
            OTHER_HEX,
            List.of(U1),
            "记录级过程：炮击城池",
            List.of(stage(S1, "炮击", List.of(U1), "阶段一：轰城", List.of(outcome(O1, "城破", 100L)))));
    // ★ 插入序 c-2 → c-1（不是 id 升序）：JSON 保序断言据此有判别力。
    return data(c2, c1);
  }

  /** 快照（ref/timestamp 可另给，用于 C28 的"新坐标"用例）。 */
  public static ArmySnapshot snapshot(ArmyData data, SimosTimestamp at) {
    return new ArmySnapshot(REF, at, data);
  }

  /** 只有 army 切片的真 {@link SimulationState}（handler 测试用，世界 tick 显式给定）。 */
  public static SimulationState world(ArmyData data, long tick) {
    SimosTimestamp at = SimosTimestamp.of(tick);
    return new SimulationState(
        new StateMeta(REF, at),
        Map.of("army", new ArmySnapshot(REF, at, data)),
        InMemoryInfoSystem.empty());
  }
}
