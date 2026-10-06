package io.mosire.simos.social.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialDataTestSupport;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code social.SeedGroups} 命令边界（R1 的 T3）：一条命令落 N 个批次、anchorTick 缺省/显式、同 id 覆盖、坏载荷拒绝、
 * 跨组件校验折成拒绝、目标路径。
 */
class SeedGroupsHandlerTest {

  private static final SeedGroupsHandler HANDLER = new SeedGroupsHandler();
  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);

  /** 一个已有农村序列的格（批次只能落在这种格上：设计稿 §十.7）。 */
  private static SocialData baseWithPopulation() {
    return new SocialData(Map.of(H00, SocialSpiFixture.still(1_000L)), Map.of(), Map.of());
  }

  private static SocialData apply(HandlerOutcome.Applied applied, SocialData base) {
    return SocialChangeSet.apply((SocialChangeSet) applied.changeSet(), base);
  }

  private static HandlerOutcome.Rejected rejected(SocialData base, String payloadJson) {
    return (HandlerOutcome.Rejected) HANDLER.handle(SocialSpiFixture.state(base), payloadJson);
  }

  @Test
  void typeIsSocialSeedGroups() {
    assertThat(HANDLER.type()).isEqualTo("social.SeedGroups");
  }

  /**
   * ★★ **本命令的承重判据**：一条命令落 **N 个**批次，且**只产出一份变更集**（= 一条 revision 的原料）。
   *
   * <p>判别力：若把 entries 循环写成"每条各自一个变更集/各自一次提交"，本断言（一次 handle、一个变更集）当场红。
   */
  @Test
  void oneCommandWritesEveryGroupInASingleChangeSet() {
    SocialData base = baseWithPopulation();
    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied)
            HANDLER.handle(
                SocialSpiFixture.state(base),
                "{\"entries\":[{\"id\":\"rural:0_0:MALE:1\",\"q\":0,\"r\":0,\"sex\":\"MALE\","
                    + "\"count\":600,\"ageDays\":13505},"
                    + "{\"id\":\"rural:0_0:FEMALE:1\",\"q\":0,\"r\":0,\"sex\":\"FEMALE\","
                    + "\"count\":400,\"ageDays\":12118}]}");

    ChangeSet changeSet = applied.changeSet();
    SocialData after = SocialChangeSet.apply((SocialChangeSet) changeSet, base);

    assertThat(after.groups()).hasSize(2);
    assertThat(after.groups().get(PopulationLots.rural(H00, Sex.MALE, "1")).count())
        .isEqualTo(600L);
    assertThat(after.groups().get(PopulationLots.rural(H00, Sex.FEMALE, "1")).count())
        .isEqualTo(400L);
    assertThat(after.groups().get(PopulationLots.rural(H00, Sex.MALE, "1")).ageAtAnchorDays())
        .isEqualTo(13_505L);
    assertThat(after.populationAt(H00)).as("该格人口 = 600 + 400（序列本身不动）").isEqualTo(1_000L);
    assertThat(((SocialChangeSet) changeSet).groups().changed()).as("批次组件进了变更集").isTrue();
  }

  /** anchorTick 缺省 = 世界当前 tick；显式给了就按给的记。 */
  @Test
  void anchorTickDefaultsToNowAndExplicitWins() {
    SocialData base = baseWithPopulation();
    String entries =
        "{\"entries\":[{\"id\":\"rural:0_0:MALE:1\",\"q\":0,\"r\":0,\"sex\":\"MALE\","
            + "\"count\":1,\"ageDays\":10";
    SimulationState atSeven = SocialSpiFixture.state(base, SimosTimestamp.of(7));

    SocialData defaulted =
        apply((HandlerOutcome.Applied) HANDLER.handle(atSeven, entries + "}]}"), base);
    SocialData explicit =
        apply(
            (HandlerOutcome.Applied) HANDLER.handle(atSeven, entries + ",\"anchorTick\":3}]}"),
            base);

    assertThat(defaulted.groups().get(PopulationLots.rural(H00, Sex.MALE, "1")).anchorTick())
        .as("缺省 = 世界当前日")
        .isEqualTo(7L);
    assertThat(explicit.groups().get(PopulationLots.rural(H00, Sex.MALE, "1")).anchorTick())
        .as("显式值优先")
        .isEqualTo(3L);
    assertThat(explicit.groups().get(PopulationLots.rural(H00, Sex.MALE, "1")).ageDaysAt(4L))
        .as("年龄 = 锚点年龄 + (now − anchor) = 10 + (4 − 3)")
        .isEqualTo(11L);
  }

  /** ★ 同 id：后出现者覆盖先出现者（整条替换，不是累加）。 */
  @Test
  void duplicateIdLaterWinsByWholeRecordReplacement() {
    SocialData base = baseWithPopulation();
    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied)
            HANDLER.handle(
                SocialSpiFixture.state(base),
                "{\"entries\":[{\"id\":\"rural:0_0:MALE:1\",\"q\":0,\"r\":0,\"sex\":\"MALE\","
                    + "\"count\":100,\"ageDays\":10},"
                    + "{\"id\":\"rural:0_0:MALE:1\",\"q\":0,\"r\":0,\"sex\":\"MALE\","
                    + "\"count\":200,\"ageDays\":20}]}");

    SocialData after = apply(applied, base);
    assertThat(after.groups()).hasSize(1);
    assertThat(after.groups().get(PopulationLots.rural(H00, Sex.MALE, "1")))
        .as("整条替换（count 与 ageDays 一起换）")
        .isEqualTo(
            new PopulationGroup(PopulationLots.rural(H00, Sex.MALE, "1"), Sex.MALE, 200L, 20L, 0L));
  }

  /** ★ 已有批次的其他 id 原样保留（本命令是"追加/覆盖点名的那些"，不是整表替换）。 */
  @Test
  void untouchedGroupsSurvive() {
    PeopleLotId existing = PopulationLots.rural(H00, Sex.FEMALE, "1");
    SocialData base =
        SocialDataTestSupport.withHouseholdsAt(
            Map.of(H00, SocialSpiFixture.still(1_000L)),
            Map.of(),
            Map.of(existing, new PopulationGroup(existing, Sex.FEMALE, 9L, 1L, 0L)),
            H00);

    SocialData after =
        apply(
            (HandlerOutcome.Applied)
                HANDLER.handle(
                    SocialSpiFixture.state(base),
                    "{\"entries\":[{\"id\":\"rural:0_0:MALE:1\",\"q\":0,\"r\":0,\"sex\":\"MALE\","
                        + "\"count\":1,\"ageDays\":1}]}"),
            base);

    assertThat(after.groups()).containsOnlyKeys(existing, PopulationLots.rural(H00, Sex.MALE, "1"));
    assertThat(after.groups().get(existing)).isEqualTo(base.groups().get(existing));
  }

  @Test
  void malformedPayloadIsRejected() {
    SocialData base = baseWithPopulation();
    assertThat(rejected(base, "这不是 JSON").reason()).contains("不是合法 JSON");
    assertThat(rejected(base, "{}").reason()).contains("字段 entries 必须是");
    assertThat(rejected(base, "{\"entries\":[]}").reason()).contains("entries 不得为空");
    assertThat(rejected(base, "{\"entries\":[3]}").reason()).contains("的元素必须是");
    assertThat(rejected(base, "{\"entries\":[{\"q\":0,\"r\":0}]}").reason())
        .contains("字段 id 必须是字符串");
    assertThat(
            rejected(
                    base,
                    "{\"entries\":[{\"id\":\" \",\"q\":0,\"r\":0,\"sex\":\"MALE\",\"count\":1,"
                        + "\"ageDays\":1}]}")
                .reason())
        .contains("PeopleLotId 不得为空白");
    assertThat(
            rejected(
                    base,
                    "{\"entries\":[{\"id\":\"x\",\"q\":0,\"sex\":\"MALE\",\"count\":1,"
                        + "\"ageDays\":1}]}")
                .reason())
        .contains("必须有整数 q 与 r");
    assertThat(
            rejected(
                    base,
                    "{\"entries\":[{\"id\":\"x\",\"q\":0,\"r\":0,\"sex\":\"不知\",\"count\":1,"
                        + "\"ageDays\":1}]}")
                .reason())
        .contains("字段 sex 必须是");
    assertThat(
            rejected(
                    base,
                    "{\"entries\":[{\"id\":\"x\",\"q\":0,\"r\":0,\"sex\":\"MALE\",\"count\":1}]}")
                .reason())
        .contains("字段 ageDays 必须是整数");
  }

  /** ★ 域不变量（负人数/负年龄/负锚点）由 {@code PopulationGroup} 的构造期守卫拒 ⇒ 折成 Rejected。 */
  @Test
  void negativeDomainValuesAreRejected() {
    SocialData base = baseWithPopulation();
    assertThat(entry(base, "\"count\":-1,\"ageDays\":1")).contains("count 必须 ≥ 0");
    assertThat(entry(base, "\"count\":1,\"ageDays\":-1")).contains("ageAtAnchorDays 必须 ≥ 0");
    assertThat(entry(base, "\"count\":1,\"ageDays\":1,\"anchorTick\":-1"))
        .contains("anchorTick 必须 ≥ 0");
  }

  /**
   * ★★ **S2 位置语义**（架构 §4.2）：{@code PopulationGroup.residence} 已删，位置只由家户给出 ⇒ 条目没有声明家户时， handler 按
   * {@code {q,r}} 自动并入/新建 {@code hh:hex:<q>_<r>}（旧"必须已有 populations 序列"的判据已随 residence
   * 删除；本阶段不做旧世界迁移，仍接受无序列格的命令归属）。
   */
  @Test
  void groupWithoutDeclaredHouseholdIsAutoAttachedToItsHexHousehold() {
    SocialData after =
        apply(
            (HandlerOutcome.Applied)
                HANDLER.handle(
                    SocialSpiFixture.state(baseWithPopulation()),
                    "{\"entries\":[{\"id\":\"rural:1_0:MALE:1\",\"q\":1,\"r\":0,\"sex\":\"MALE\","
                        + "\"count\":1,\"ageDays\":1}]}"),
            baseWithPopulation());

    assertThat(after.groups()).containsKey(PopulationLots.rural(H10, Sex.MALE, "1"));
    assertThat(after.households()).containsKey(HouseholdId.parse("hh:hex:1_0"));
    assertThat(after.householdsAt(H10)).hasSize(1);
    assertThat(after.populationAt(H10)).isEqualTo(1L);
  }

  /** ★★ 命令边界的位置一致性：声明的家户与条目的 {q,r} 不符 ⇒ 具名拒绝（不静默搬次）。 */
  @Test
  void declaredHouseholdLocationMustMatchTheEntryHex() {
    SocialData base = baseWithPopulation();
    String payload =
        "{\"entries\":[{\"id\":\"rural:1_0:MALE:1\",\"q\":1,\"r\":0,\"sex\":\"MALE\","
            + "\"count\":1,\"ageDays\":1,\"household\":\"hh:hex:0_0\"}],\"households\":"
            + "[{\"id\":\"hh:hex:0_0\",\"q\":0,\"r\":0,\"name\":\"0 格户\"}]}";

    assertThat(rejected(base, payload).reason()).contains("与家户").contains("不符");
  }

  /** ★ 目标资源取 social 命名空间的既有形态 {@code <q>_<r>}（与 SetPopulation 同一个资源），重复格去重。 */
  @Test
  void targetPathsAreSocialHexPathsDeduplicated() {
    List<String> paths =
        HANDLER.targetPaths(
            "Map1",
            "{\"entries\":[{\"id\":\"rural:0_0:MALE:1\",\"q\":0,\"r\":0,\"sex\":\"MALE\","
                + "\"count\":1,\"ageDays\":1},"
                + "{\"id\":\"rural:0_0:FEMALE:1\",\"q\":0,\"r\":0,\"sex\":\"FEMALE\","
                + "\"count\":1,\"ageDays\":1},"
                + "{\"id\":\"rural:1_0:MALE:1\",\"q\":1,\"r\":0,\"sex\":\"MALE\","
                + "\"count\":1,\"ageDays\":1}]}");

    assertThat(paths).containsExactly("0_0", "1_0");
  }

  /** 缺 social 切片是装配故障（{@code IllegalStateException}），不是 {@code Rejected}。 */
  @Test
  void missingSocialSliceIsAssemblyFaultNotRejection() {
    SimulationState mapOnly =
        new SimulationState(
            new StateMeta(SocialSpiFixture.REF, SocialSpiFixture.T0),
            Map.of(
                "map", new MapSnapshot(SocialSpiFixture.REF, SocialSpiFixture.T0, GameMap.empty())),
            InMemoryInfoSystem.empty());
    assertThatThrownBy(
            () ->
                HANDLER.handle(
                    mapOnly,
                    "{\"entries\":[{\"id\":\"x\",\"q\":0,\"r\":0,\"sex\":\"MALE\",\"count\":1,"
                        + "\"ageDays\":1}]}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("social");
  }

  private static String entry(SocialData base, String fields) {
    return rejected(
            base,
            "{\"entries\":[{\"id\":\"rural:0_0:MALE:1\",\"q\":0,\"r\":0,\"sex\":\"MALE\","
                + fields
                + "}]}")
        .reason();
  }
}
