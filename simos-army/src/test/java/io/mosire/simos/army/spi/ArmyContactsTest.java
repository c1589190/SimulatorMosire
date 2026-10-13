package io.mosire.simos.army.spi;

import static io.mosire.simos.army.spi.EmergencyStopFixture.armies;
import static io.mosire.simos.army.spi.EmergencyStopFixture.army;
import static io.mosire.simos.army.spi.EmergencyStopFixture.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.spi.Incident;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>"两军相遇"判据</b>（2026-10-13 急停协议 §6.2 P2/P4）：{@link ArmyContacts#scan} 是纯函数，直接单测。
 *
 * <p>判据（设计书 §4.4）：两军位置<b>同格或相邻</b>（六角距离 ≤ 1）<b>且至少一方被 GM 要求监控</b>（{@code Army.contactWatch()}）⇒ 报一条
 * {@link Incident}（{@code kind = army.contact}，{@code tick = 当天}）。
 *
 * <p>★ P4 的两半都在这里：都不启用 ⇒ 相遇也不报；只启用一方 ⇒ 报（"这两军相遇时告诉我"是 GM 的语义）。
 */
class ArmyContactsTest {

  private static final long DAY = 5L;

  private static Incident only(List<Incident> incidents) {
    assertThat(incidents).as("恰一条相遇事件").hasSize(1);
    return incidents.get(0);
  }

  /** ★ P2（判据层）：同格 ⇒ 报一条，且事件带着模块自报的 kind / namespace / 当天 / 证据 / 日志锚。 */
  @Test
  void sameHexReportsOneContactWithTheFullIncidentShape() {
    ArmyId a = new ArmyId("a-army");
    ArmyId b = new ArmyId("b-army");
    Map<ArmyId, Army> table = armies(army("a-army", "u-a", true), army("b-army", "u-b", true));

    Incident incident = only(ArmyContacts.scan(DAY, table, at(new HexCoord(3, 0), a, b)));

    assertThat(incident.kind()).as("★ I5：这个字面量只住 Army").isEqualTo("army.contact");
    assertThat(incident.namespace()).isEqualTo("army");
    assertThat(incident.tick()).as("★ 判据命中的就是这一天（core 侧机械核对）").isEqualTo(DAY);
    assertThat(incident.payload())
        .as("证据形状逐字照 §4.4：{armies:[a,b], hex:\"q_r\"}")
        .containsExactly(entry("armies", List.of("a-army", "b-army")), entry("hex", "3_0"));
    assertThat(incident.logRefs())
        .as("★ 日志锚由模块自报，且与真日志行可逐字对上")
        .containsExactly("ARMY_CONTACT day=5 a=a-army b=b-army");
  }

  /** 相邻（六角距离 1）也算相遇 —— 界的下侧；距离 2 不算 —— 界的上侧。只差一格，判别力最强。 */
  @Test
  void adjacentHexesReportButTwoHexesApartDoNot() {
    ArmyId a = new ArmyId("a-army");
    ArmyId b = new ArmyId("b-army");
    Map<ArmyId, Army> table = armies(army("a-army", "u-a", true), army("b-army", "u-b", false));

    assertThat(new HexCoord(3, 0).distanceTo(new HexCoord(4, 0))).as("前提：这是相邻而非同格").isEqualTo(1);
    Incident adjacent =
        only(ArmyContacts.scan(DAY, table, Map.of(a, new HexCoord(3, 0), b, new HexCoord(4, 0))));
    assertThat(adjacent.payload().get("hex"))
        .as("相邻 ⇒ 取**被监控那一军**的格（GM 关心的是他让盯的那支旁边出现了谁）")
        .isEqualTo("3_0");

    assertThat(new HexCoord(3, 0).distanceTo(new HexCoord(5, 0))).as("前提：这是隔一格").isEqualTo(2);
    assertThat(ArmyContacts.scan(DAY, table, Map.of(a, new HexCoord(3, 0), b, new HexCoord(5, 0))))
        .as("相邻是界的**内侧**：距离 2 不算相遇 ⇒ 不报")
        .isEmpty();
  }

  /** ★★ P4：**未启用相遇报警的两军相遇 ⇒ 不报**（"没要求过" = 不报，不是"默认全报"）。 */
  @Test
  void unwatchedPairNeverReportsEvenWhenTheyShareTheHex() {
    ArmyId a = new ArmyId("a-army");
    ArmyId b = new ArmyId("b-army");
    Map<ArmyId, Army> unwatched =
        armies(army("a-army", "u-a", false), army("b-army", "u-b", false));

    assertThat(ArmyContacts.scan(DAY, unwatched, at(new HexCoord(3, 0), a, b)))
        .as("★ P4：两位都没被要求监控 ⇒ 同格也不报（否则 P4 红）")
        .isEmpty();
  }

  /** ★ P4 的另一半：**只启用一方即报** —— 同格时 hex 就是那一格；只启用 b 时不因遍历序而漏报。 */
  @Test
  void oneWatchedSideIsEnoughToReport() {
    ArmyId a = new ArmyId("a-army");
    ArmyId b = new ArmyId("b-army");

    Map<ArmyId, Army> onlyB = armies(army("a-army", "u-a", false), army("b-army", "u-b", true));
    Incident pair = only(ArmyContacts.scan(DAY, onlyB, at(new HexCoord(3, 0), a, b)));
    assertThat(pair.payload().get("armies"))
        .as("★ 只启用乙军也报（且军对仍是字典序 [a,b]，不是「谁启用了谁在前」）")
        .isEqualTo(List.of("a-army", "b-army"));

    Map<ArmyId, Army> onlyA = armies(army("a-army", "u-a", true), army("b-army", "u-b", false));
    assertThat(ArmyContacts.scan(DAY, onlyA, at(new HexCoord(3, 0), a, b)))
        .as("只启用甲军同样报")
        .hasSize(1);
  }

  /**
   * 军对次序是<b>内容的纯函数</b>（军对按 id 字典序）：位置表插入序**故意反过来**，结果的 {@code armies} 次序不变 ⇒ {@code
   * StopReport.reasons()} 的次序可复现。
   */
  @Test
  void pairOrderIsDeterministicRegardlessOfMapInsertionOrder() {
    ArmyId a = new ArmyId("a-army");
    ArmyId b = new ArmyId("b-army");
    Map<ArmyId, Army> table = armies(army("b-army", "u-b", true), army("a-army", "u-a", true));

    Map<ArmyId, HexCoord> reversed = new java.util.LinkedHashMap<>();
    reversed.put(b, new HexCoord(3, 0));
    reversed.put(a, new HexCoord(3, 0));

    assertThat(only(ArmyContacts.scan(DAY, table, reversed)).payload().get("armies"))
        .as("★ 位置表插入序 [b,a] ⇒ 事件里的军对仍是 [a,b]（决定论）")
        .isEqualTo(List.of("a-army", "b-army"));
  }

  /** 位置表里出现军队表里没有的 id ⇒ 不造事件（也不抛）：判据只对"两军都在册"的军对负责。 */
  @Test
  void unknownIdsInThePositionTableProduceNothing() {
    ArmyId a = new ArmyId("a-army");
    ArmyId ghost = new ArmyId("ghost-army");
    Map<ArmyId, Army> table = armies(army("a-army", "u-a", true));

    assertThat(ArmyContacts.scan(DAY, table, at(new HexCoord(3, 0), a, ghost)))
        .as("军队表里没有 ghost-army ⇒ 不造事件（装配异常由调用方/读侧负责）")
        .isEmpty();
  }

  /** 三军两两同格 ⇒ **每个军对各一条**（聚合不丢：判据层不做"取第一条"）。 */
  @Test
  void threeArmiesOnTheSameHexProduceThreePairwiseContacts() {
    Map<ArmyId, Army> table =
        armies(
            army("a-army", "u-a", true), army("b-army", "u-b", true), army("c-army", "u-c", true));
    Map<ArmyId, HexCoord> all =
        at(new HexCoord(3, 0), new ArmyId("a-army"), new ArmyId("b-army"), new ArmyId("c-army"));

    assertThat(ArmyContacts.scan(DAY, table, all))
        .as("三对：a-b、a-c、b-c")
        .hasSize(3)
        .allSatisfy(incident -> assertThat(incident.tick()).isEqualTo(DAY));
  }

  /** 锚的形状是**单一拼写点**：{@link ArmyContacts#anchor} 与事件里的 logRef 逐字相同（日志里 grep 得到）。 */
  @Test
  void anchorMatchesTheReportedLogReference() {
    ArmyId a = new ArmyId("a-army");
    ArmyId b = new ArmyId("b-army");
    Incident incident =
        only(
            ArmyContacts.scan(
                DAY,
                armies(army("a-army", "u-a", true), army("b-army", "u-b", false)),
                at(new HexCoord(2, 0), a, b)));

    assertThat(incident.logRefs())
        .containsExactly(ArmyContacts.anchor(DAY, a, b))
        .allSatisfy(ref -> assertThat(ref).contains("day=5", "a=a-army", "b=b-army"));
  }
}
