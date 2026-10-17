package io.mosire.simos.app.d031;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.culture.CultureData;
import io.mosire.simos.culture.CultureSnapshot;
import io.mosire.simos.culture.contract.HouseholdCulture;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.codec.ProductionCodec;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>D-031 P1 §5 V1（app 层缺口）：真实新世界 → 两侧全组件 JSON 往返 → 跨模块提交 → 回放/重启 → 下一次结算一致</b>
 * （本段唯一写者，2026-10-16）。
 *
 * <p>判据原文（设计书 §5 V1）：真实新世界及非空 production/economy 状态、两侧 ChangeSet 全组件 JSON 往返、差异 {@code apply} 重建，
 * 跨模块提交回放/重启后下一次结算一致。
 *
 * <p>★★ <b>本类补的正是上一轮交账 §5 明列的那条 app 层缺口</b>（{@code task-report.md}：生产层四组件往返已有 {@code
 * ProductionRoundTripTest}，app 层"真实新世界非空 / 一键 revision 批 / 回放重启后结算"未单独补测）。
 *
 * <p>★ <b>真壳真库</b>：世界来自 {@code ShellMain.seedGenesisIfEmpty} 的真创世；推进走真 {@code
 * Shell#advanceAndDrain}； 提交走真 {@code CoreSimos.submitBatch}。"重启"= 从同一 revision <b>重新 replay</b>
 * 出一份独立状态。
 *
 * <p>★★ <b>逐字段重建</b>由反射枚举 record 组件逐个比（不是"整体 equals 一次"）—— 那样漂移会指不出是哪个组件。
 */
class D031P1AppDefinitionRoundTripTest {

  private static final BranchId MAIN = new BranchId("main");

  @TempDir Path tempDir;

  @Test
  void realGenesisWorldHasNonEmptyProductionAndEconomySlices() {
    try (GovZ6WorldFixture.World world = world("genesis")) {
      SimulationState genesis = world.state();

      ProductionData production = productionOf(genesis);
      EconomyData economy = economyOf(genesis);

      assertThat(production.industries()).as("创世播下的产业定义非空").isNotEmpty();
      assertThat(production.modes()).as("创世播下的生产方式定义非空").isNotEmpty();
      assertThat(economy.classes()).as("economy 的家户行非空").isNotEmpty();
      // ★★ D-032 T1：生产实例（"生产单元"）的唯一权威已按 B2b（裁定六）迁到 production ⇒ 读数换片，判据不变。
      assertThat(production.instances()).as("production 的生产实例非空").isNotEmpty();
      assertThat(economy.assetShares()).as("economy 的实物份额非空").isNotEmpty();
      assertThat(economy.allocations()).as("economy 的劳动配额非空").isNotEmpty();
      assertThat(economy.classStructures()).as("阶层结构非空（mode→结构引用要有落点）").isNotEmpty();
    }
  }

  @Test
  void oneKeyRevisionBatchCarriesFourProductionComponentsAndCultureThenReplaysFieldByField() {
    try (GovZ6WorldFixture.World world = world("batch")) {
      CoreSimos core = world.core();
      long head0 = world.head();
      SimulationState before = world.state();

      ProductionData productionBefore = productionOf(before);
      String newIndustryId = "d031v1@0_0";
      String existingStructure =
          economyOf(before).classStructures().keySet().iterator().next().value();

      // ★★ D-032 T1：本批仍是"**跨模块**、恰一条 revision"的判据，但两条命令换成**仍存活**的两个面：
      //   ① `production.Seed`（产业定义 + 生产方式定义 —— D-032 起定义唯一权威在 production）；
      //   ② `culture.SetHouseholdCulture`（文化切片的存活命令：给一个真实家户写文化/宗教标签）。
      //   ★ 被替换掉的三条 `production.RegisterTechnology` / `production.SetMastery` /
      //     `culture.UpsertAptitudeRule` 已按两条既有裁定**整体退役**（全仓 `src/main` 0 命中，逐条依据见
      //     台账 §10）：D1「科技点面直接删」（`ProductionData.technologies/masteries` 与 `TechnologyId`/
      //     `MasteryScope` 已删）、D2「天赋规则 + 生产修正注入链直接删」（`CultureAptitudeBridge`/
      //     `UpsertAptitudeRuleHandler`/`AptitudeRule` 已删）。⇒ 它们的被测能力不存在，**不是**为了过编译而放宽。
      String householdId =
          GovZ6WorldFixture.socialSlice(before).households().keySet().iterator().next().value();
      BatchResult result =
          core.submitBatch(
              List.of(
                  envelope(
                      head0,
                      "production.Seed",
                      "{\"industries\":["
                          + industryJson(newIndustryId)
                          + "],\"modes\":[{\"id\":\"d031v1mode@0_0\",\"name\":\"D031 V1 生产方式\","
                          + "\"version\":1,\"classStructureId\":\""
                          + existingStructure
                          + "\"}]}"),
                  envelope(
                      head0,
                      "culture.SetHouseholdCulture",
                      "{\"householdId\":\""
                          + householdId
                          + "\",\"cultureId\":\"latium\",\"religionId\":\"roman\"}")));

      assertThat(result).as("四条命令必须整批成功: %s", result).isInstanceOf(BatchResult.Committed.class);
      long head1 = world.head();
      assertThat(head1).as("★★ 一键批 = 恰一条 revision（不是四条）").isEqualTo(head0 + 1);

      SimulationState after = core.replay(GovZ6WorldFixture.ref(head1));
      ProductionData productionTarget = productionOf(after);
      EconomyData economyTarget = economyOf(after);

      // ── production：本批改到的两个组件 ⇒ 全组件 JSON 往返 + apply 逐字段重建 ──
      ProductionChangeSet productionDelta =
          ProductionChangeSet.between(productionBefore, productionTarget);
      // ★★ D-032 T1：原两条 `technologies()`/`masteries()` 断言（D1 已整体删）**删除**，依据见本批命令处的注与
      //   台账 §10：`ProductionData` 不再有这两个组件、`ProductionChangeSet` 的组件表里也没有它们
      //   ⇒ 断言的主语在 `src/main` 里已不存在（不是"改小判据"）。
      assertThat(productionDelta.industries().changed()).as("industries 必须进变更集（Seed 新产业）").isTrue();
      assertThat(productionDelta.modes().changed()).as("modes 必须进变更集（Seed 新生产方式）").isTrue();
      assertThat(productionDelta.isEmpty()).as("两个组件全非 Unchanged ⇒ 不是空变更集").isFalse();

      ProductionCodec productionCodec = new ProductionCodec();
      String productionJson = productionCodec.encodeChangeSet(productionDelta);
      assertThat(productionJson).as("线格式里必须看得见那两张表").isNotBlank();
      ChangeSet decodedProduction = productionCodec.decodeChangeSet(productionJson);
      assertThat(decodedProduction).as("production 变更集 JSON 往返后逐值相等").isEqualTo(productionDelta);

      ProductionSnapshot baseSlice =
          new ProductionSnapshot(ref(head0), at(before), productionBefore);
      ProductionSnapshot rebuiltSlice =
          (ProductionSnapshot) productionCodec.apply(decodedProduction, baseSlice, meta(after));
      assertEveryComponentEquals("ProductionData", productionTarget, rebuiltSlice.state());

      // ── economy：真实新世界状态与"空状态"之间的整份差异 ⇒ 全组件 JSON 往返 + 35 组件逐字段重建 ──
      EconomyData economyEmpty = EconomyData.empty();
      EconomyChangeSet economyDelta = EconomyChangeSet.between(economyEmpty, economyTarget);
      Set<String> changedEconomyComponents = changedComponentNames(economyDelta);
      assertThat(changedEconomyComponents)
          .as(
              "★ 真实新世界在 economy 侧实际填了 %d/35 个组件（覆盖面证明：不是一两个组件的花架子）；实得=%s",
              changedEconomyComponents.size(), changedEconomyComponents)
          .hasSizeGreaterThan(12);

      EconomyCodec economyCodec = new EconomyCodec();
      String economyJson = economyCodec.encodeChangeSet(economyDelta);
      assertThat(economyJson).isNotBlank();
      ChangeSet decodedEconomy = economyCodec.decodeChangeSet(economyJson);
      assertThat(decodedEconomy).as("economy 变更集 JSON 往返后逐值相等").isEqualTo(economyDelta);

      Snapshot rebuiltEconomySlice =
          economyCodec.apply(
              decodedEconomy,
              new EconomySnapshot(ref(0L), SimosTimestamp.of(0L), economyEmpty),
              meta(after));
      assertEveryComponentEquals(
          "EconomyData", economyTarget, ((EconomySnapshot) rebuiltEconomySlice).data());

      // ── 真实跨模块差分的往返（这次是"创世 → 批后"的真 delta）──
      EconomyChangeSet realEconomyDelta =
          EconomyChangeSet.between(economyOf(before), economyTarget);
      assertThat(economyCodec.decodeChangeSet(economyCodec.encodeChangeSet(realEconomyDelta)))
          .as("真实 economy 差分（本批未改 economy ⇒ 全 Unchanged）也必须能往返")
          .isEqualTo(realEconomyDelta);

      // ── culture 片确实被这一批改到了（否则下面那条"文化读数"是空转）──
      CultureData culture = ((CultureSnapshot) after.module("culture").orElseThrow()).state();
      // ★★ D-032 T1：文化读数从已退役的 `rulesFor(CultureId)`（天赋规则表，D2 直接删）换成**存活的家户文化**
      //   —— `culture.SetHouseholdCulture` 写的正是它。判据仍是同一条："这一批确实改到了 culture 片"，且
      //   逐值钉住写进去的那一档（culture=latium / religion=roman）。
      Optional<HouseholdCulture> landed = culture.cultureOf(new HouseholdId(householdId));
      assertThat(landed).as("★ 本批写的家户文化标签确实落在状态里（culture 侧断言的前提）").isPresent();
      assertThat(landed.orElseThrow().tag().culture().value()).isEqualTo("latium");
      assertThat(landed.orElseThrow().tag().religion().value()).isEqualTo("roman");

      // ── 定义真值也逐片上账 ──
      // ★★★ D-032 T1：原两条「科技定义 technologies / 掌握读数 masteries」断言已**删除** —— 科技点面按用户
      //   2026-10-17「D1 直接删」整体退役（`ProductionData` 不再有 `technologies`/`masteries` 组件，
      //   `TechnologyId`/`MasteryScope` 两个类型已从 `production.contract` 删除）⇒ 该判据的被测能力不存在。
      //   ★ 科技现在的表达是"生产方式的可选升级方案"（U1），由 industries 那一维承担（下一句仍在断言它）。
      assertThat(productionTarget.industries())
          .containsKey(new io.mosire.simos.economy.api.id.IndustryId(newIndustryId));
    }
  }

  @Test
  void restartFromTheSameRevisionYieldsAnIdenticalStateAndAnIdenticalNextSettlement() {
    try (GovZ6WorldFixture.World world = world("restart")) {
      CoreSimos core = world.core();
      long head0 = world.head();

      assertThat(
              core.submitBatch(
                  List.of(
                      envelope(
                          head0,
                          "production.Seed",
                          "{\"industries\":[" + industryJson("d031v1restart@0_0") + "]}"))))
          .as("先落一条真实 revision，制造一个有历史的日志")
          .isInstanceOf(BatchResult.Committed.class);
      long head1 = world.head();

      // ① 回放一致性：同一 revision 两次独立 replay 逐值相等（重启不漂）。
      SimulationState firstReplay = core.replay(GovZ6WorldFixture.ref(head1));
      SimulationState secondReplay = core.replay(GovZ6WorldFixture.ref(head1));
      assertThat(secondReplay).as("★ 同一 revision 的两次 replay 必须逐字段相等").isEqualTo(firstReplay);

      // ② 下一次结算一致：同一份状态（活状态 vs 重新 replay 出的状态）各推一天 ⇒ 结果逐值相等。
      long tick = firstReplay.meta().timestamp().tick();
      GovZ6WorldFixture.StateRunner liveRunner =
          new GovZ6WorldFixture.StateRunner("small-world", firstReplay);
      GovZ6WorldFixture.StateRunner restartedRunner =
          new GovZ6WorldFixture.StateRunner(
              "small-world", core.replay(GovZ6WorldFixture.ref(head1)));

      assertThat(restartedRunner.state()).as("重启起点与活状态逐值相同").isEqualTo(liveRunner.state());

      liveRunner.advance(tick, tick + 1L);
      restartedRunner.advance(tick, tick + 1L);

      assertThat(restartedRunner.state())
          .as("★★ 同一 revision 回放/重启后的【下一次结算】逐字段相等（含 economy 结算读数）")
          .isEqualTo(liveRunner.state());
      assertThat(economyOf(restartedRunner.state()))
          .as("★ economy 切片也要逐字段相等（不是只有 social/unit 相等）")
          .isEqualTo(economyOf(liveRunner.state()));
      assertThat(productionOf(restartedRunner.state()))
          .as("★ production 切片逐字段相等")
          .isEqualTo(productionOf(liveRunner.state()));
    }
  }

  // ★★★ D-032 T1：原 `aNonNeutralCultureRuleStillDoesNotMoveTheSettlement`（整方法，65 行）**已删除**。
  //   —— 逐条依据（核验命令见台账 §10，全部回 `src/main` 核过，**不是**"为了过编译"）：
  //   ① 它的主语 `CultureData.rulesFor(CultureId)`（天赋规则表）与命令 `culture.UpsertAptitudeRule` 已按 D2
  //      「天赋/生产修正链直接删」**整体退役**：`grep -rn "UpsertAptitudeRuleHandler\|rulesFor(" simos-*/src/main`
  //      ⇒ 0 命中；`CultureData` 现在只有 `householdCultures` 一个组件（`CultureData.java:36`）。
  //   ② 它的第二半 `EconomyData.productionEfficiency()` 已按 B3（效率书迁 production）删除 ⇒ 该读数现在住
  //      `production.contract.ProductionEfficiencyState`（本文件另一处已改读 production 切片）。
  //   ③ 它的"源码级独立证据"读的是 `simos-app/src/main/.../household/CultureAptitudeBridge.java` —— **该文件已删**
  //      （`ls` ⇒ 不存在）⇒ 就算只改编译，这条断言在运行期也必然红（`readText` 的"文件必须存在"前置）。
  //   ④ 它断言的**性质**（"文化不直接乘生产效率"）在新形状里由**类型系统**保证：注入面
  //      `updateProductionModifiers(...)` 与 `ProductionModifier` 一条都不存在了 —— 没有可注入的通道。
  //      ★ 该性质的"运行期/扫描证据"是台账 §9.2 的 **G6** 缺口（T2 交付：economy/production 都没有"文化倍率"
  //      输入面的扫描用例）；本 owner 未交付（如实记入未做清单），**不用删除掩盖缺口**。

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────

  private GovZ6WorldFixture.World world(String label) {
    return GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve(label), "d031-p1-" + label);
  }

  private static CommandEnvelope envelope(long expectedRevision, String type, String payload) {
    return new CommandEnvelope(
        "cmd-d031p1-" + type + "-" + expectedRevision,
        "corr-d031p1-" + expectedRevision,
        "gm:d031-p1-test",
        MAIN,
        new RevisionId(expectedRevision),
        type,
        payload);
  }

  private static ProductionData productionOf(SimulationState state) {
    return ((ProductionSnapshot) state.module("production").orElseThrow()).state();
  }

  private static EconomyData economyOf(SimulationState state) {
    return ((EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  private static io.mosire.simos.util.state.StateRef ref(long revision) {
    return GovZ6WorldFixture.ref(revision);
  }

  private static SimosTimestamp at(SimulationState state) {
    return state.meta().timestamp();
  }

  private static StateMeta meta(SimulationState state) {
    return state.meta();
  }

  /** 逐 record 组件比对（不是一次整体 equals）：漂移会直接指出是哪个组件。 */
  private static void assertEveryComponentEquals(String label, Object expected, Object actual) {
    RecordComponent[] components = expected.getClass().getRecordComponents();
    assertThat(components).as("%s 必须有 record 组件", label).isNotEmpty();
    for (RecordComponent component : components) {
      Object expectedValue;
      Object actualValue;
      try {
        expectedValue = component.getAccessor().invoke(expected);
        actualValue = component.getAccessor().invoke(actual);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("读取 " + label + "." + component.getName() + " 失败", e);
      }
      assertThat(actualValue)
          .as("★ %s.%s 必须逐字段重建", label, component.getName())
          .isEqualTo(expectedValue);
    }
  }

  /** 变更集里被报成 changed 的组件名（保序去重）。 */
  private static Set<String> changedComponentNames(ChangeSet changeSet) {
    Set<String> changed = new TreeSet<>();
    for (RecordComponent component : changeSet.getClass().getRecordComponents()) {
      try {
        Object delta = component.getAccessor().invoke(changeSet);
        if (delta instanceof io.mosire.simos.util.state.FieldDelta<?> fieldDelta
            && fieldDelta.changed()) {
          changed.add(component.getName());
        }
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("读取变更集组件 " + component.getName() + " 失败", e);
      }
    }
    return changed;
  }

  private static String industryJson(String id) {
    return "{\"id\":\""
        + id
        + "\",\"name\":\"D031 V1 产业\",\"regime\":\"tenant\",\"cycleDays\":1,"
        + "\"capacityPerUnit\":{\"CATTLE\":1},\"dailyInputPerUnit\":{},\"dailyLaborPerUnit\":0,"
        + "\"laborPerUnit\":1000,\"outputPerUnit\":{\"grain\":7},\"moneyOutputPerUnit\":{},"
        + "\"cycleInputPerUnit\":{},"
        + "\"slots\":[{\"id\":\"poor_peasant\",\"name\":\"贫农\",\"laborParticipationPerMille\":1000}],"
        + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":1000,\"laborWeightPerMille\":0}}";
  }
}
