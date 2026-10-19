package io.mosire.simos.production.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-031 P1 §5 V5（production 侧）：负向收口成独立证据</b>（本段唯一写者，2026-10-16）。
 *
 * <p>判据原文（设计书 §5 V5）里归 production 命令面的那些：<b>缺定义、无效 mode↔阶层结构、重复/非法载荷、引用中删除替换、组批中途拒绝、 planOnly
 * 不写、region clear/seed 边界</b>。每条都有<b>本类自己的可核对断言</b>（不写"别的测试已覆盖"）。
 *
 * <p>★★ <b>本类不依赖 economy 实现</b>：跨片端口用本地 {@link FakePort}（真 economy 侧的端口语义与 {@code
 * EconomyReferenceValidation} 的断言见 economy 的 {@code D031P1NegativeSurfaceTest}）。
 *
 * <p>★ <b>拒绝的判别力来自"状态一字不动"</b>：每条拒绝都同时断言 {@link HandlerOutcome.Rejected} 与 <b>零状态变化</b>（不只看返回类型）。
 */
class D031P1DefinitionCommandNegativeTest {

  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final IndustryId MILL = IndustryHexKeys.id("mill", 0, 0);
  private static final IndustryId FARM_V2 = IndustryHexKeys.id("farm_v2", 0, 0);
  private static final IndustryId FARM_OTHER_HEX = IndustryHexKeys.id("farm", 1, 0);
  private static final ProductionModeId MODE = new ProductionModeId("peasant@0_0");
  private static final ProductionModeId MODE_FREE = new ProductionModeId("free@0_0");
  private static final ClassStructureId STRUCTURE = new ClassStructureId("cs-peasant");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final RegimeId TENANT = new RegimeId("tenant");

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);

  // ── ① 重复 / 非法载荷：整批拒绝且零状态变化 ────────────────────────────────────────────

  @Test
  void seedRejectsAConflictingDuplicateInsteadOfSilentlyOverwriting() {
    ProductionData base = ProductionData.empty().withIndustry(industry(FARM, 7L));
    FakePort port = new FakePort();
    port.activate("0_0");

    HandlerOutcome outcome =
        new ProductionSeedHandler()
            .handle(state(base, port), "{\"industries\":[" + industryJson(FARM, 9L) + "]}");

    assertThat(outcome).as("同 id 不同值 ⇒ 冲突拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .contains("与既有定义冲突")
        .contains("farm@0_0");
    // ★ 裁定二十七清扫（T2a）：base 是不可变 record ⇒ "既有定义仍是 7" 是结构性事实，不是运行期读数。
    //   真判别力在"同值重放必须幂等（Applied + 空变更集）"这一侧 ⇒ 补上它作为正值读数。
    HandlerOutcome replay =
        new ProductionSeedHandler()
            .handle(state(base, port), "{\"industries\":[" + industryJson(FARM, 7L) + "]}");
    assertThat(replay).as("同值重放 ⇒ 幂等（不是拒绝）").isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(changeSetOf(replay).isEmpty()).as("同值重放 = 空变更集（既有定义没有被改成 9，也没有改成别的值）").isTrue();
  }

  @Test
  void seedIsAtomicWhenALaterEntryIsIllegalTheWholeBatchIsRejected() {
    ProductionData base = ProductionData.empty().withIndustry(industry(FARM, 7L));
    FakePort port = new FakePort();
    port.activate("0_0");

    // 第 1 条合法（新 id）、第 2 条非法（缺 name）⇒ 整批拒绝，第 1 条也不许落。
    String payload =
        "{\"industries\":["
            + industryJson(MILL, 5L)
            + ",{\"id\":\"bad@0_0\",\"regime\":\"tenant\",\"cycleDays\":1,"
            + "\"capacityPerUnit\":{\"WORKSHOP\":1},\"outputPerUnit\":{\"grain\":1},"
            + "\"slots\":[{\"id\":\"poor_peasant\",\"name\":\"贫农\",\"laborParticipationPerMille\":1000}],"
            + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":1000,\"laborWeightPerMille\":0}}]}";

    HandlerOutcome outcome = new ProductionSeedHandler().handle(state(base, port), payload);

    assertThat(outcome).as("★ 组批中途拒绝 ⇒ 整批 Rejected").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("name");
    // ★★ 裁定二十七清扫（T2a）：原来这里断言 `applyOrSame(base, outcome) == base` —— Rejected 分支恒返回 base，
    //   而 base 又是不可变 record（处理器拿不到写句柄）⇒ 恒真断言。判别力改由**正值对照**承担：
    //   同一形状、两条都合法的载荷必须真的落两行（证明"整批拒绝"不是靠夹具惰性蒙对的）。
    HandlerOutcome allLegal =
        new ProductionSeedHandler()
            .handle(
                state(base, port),
                "{\"industries\":["
                    + industryJson(MILL, 5L)
                    + ","
                    + industryJson(FARM_V2, 3L)
                    + "]}");
    ProductionData applied = apply(base, allLegal);
    assertThat(applied.industries())
        .as("★★ 正向对照：两条都合法 ⇒ 都落（与上面'整批拒绝'形成对照）")
        .containsOnlyKeys(FARM, MILL, FARM_V2);
    assertThat(changeSetOf(allLegal).industries().changed()).isTrue();
  }

  @Test
  void seedSameValueReplayIsAnEmptyChangeSetAndVersionBelowOneIsRejected() {
    ProductionData base =
        ProductionData.empty().withIndustry(industry(FARM, 7L)).withMode(mode(MODE, 1, STRUCTURE));
    FakePort port = new FakePort();
    port.activate("0_0");

    HandlerOutcome replay =
        new ProductionSeedHandler()
            .handle(
                state(base, port),
                "{\"industries\":["
                    + industryJson(FARM, 7L)
                    + "],\"modes\":[{\"id\":\"peasant@0_0\",\"name\":\"生产方式 peasant@0_0\","
                    + "\"version\":1,\"classStructureId\":\"cs-peasant\"}]}");

    assertThat(replay).as("逐值相同 ⇒ 幂等").isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(changeSetOf(replay).isEmpty()).as("同值重放 = 空变更集（不落 revision）").isTrue();

    HandlerOutcome badVersion =
        new ProductionSeedHandler()
            .handle(
                state(base, port),
                "{\"modes\":[{\"id\":\"fresh@0_0\",\"name\":\"新\",\"version\":0,"
                    + "\"classStructureId\":\"cs-peasant\"}]}");
    assertThat(badVersion).as("★ version < 1 ⇒ 具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) badVersion).reason()).contains("version 必须 ≥ 1");
  }

  @Test
  void upsertIndustryRejectsEveryIllegalPayloadShapeWithZeroStateChange() {
    ProductionData base = ProductionData.empty().withIndustry(industry(FARM, 7L));
    FakePort port = new FakePort();
    port.activate("0_0");

    List<String> illegal =
        List.of(
            "{\"regime\":\"tenant\"}", // 缺 id
            "{\"id\":\"mill@0_0\",\"regime\":\"tenant\"}", // 缺 name
            "{\"id\":\"mill@0_0\",\"name\":\"x\",\"regime\":\"tenant\",\"cycleDays\":0}", // cycleDays < 1
            "{\"id\":\"mill@0_0\",\"name\":\"x\",\"regime\":\"tenant\",\"cycleDays\":1,"
                + "\"capacityPerUnit\":{}}", // 空产能锚
            "{\"id\":\"mill@0_0\",\"name\":\"x\",\"regime\":\"tenant\",\"cycleDays\":1,"
                + "\"capacityPerUnit\":{\"WORKSHOP\":1},\"allocation\":{\"@class\":\"wage_first\","
                + "\"wagePerLaborMilli\":1,\"ownerResidual\":{}}}", // v1 只支持 split
            "{\"id\":\"mill@0_0\",\"name\":\"x\",\"regime\":\"tenant\",\"cycleDays\":1,"
                + "\"capacityPerUnit\":{\"WORKSHOP\":1},\"laborPerUnit\":-1}" // 负劳动
            );

    for (String payload : illegal) {
      HandlerOutcome outcome = new UpsertIndustryHandler(port).handle(state(base, port), payload);
      assertThat(outcome)
          .as("非法载荷必须具名拒绝（不是静默接受或 500）: %s", payload)
          .isInstanceOf(HandlerOutcome.Rejected.class);
      assertThat(((HandlerOutcome.Rejected) outcome).reason()).as("拒绝理由非空").isNotBlank();
    }
    // ★ 裁定二十七清扫（T2a）：原来收尾断言 `base.industries()` 仍只有 FARM —— base 不可变 ⇒ 恒真。
    //   改成**正值对照**：同一入口的合法载荷必须真的改到状态（这才证明上面六条 Rejected 有判别力）。
    HandlerOutcome legal =
        new UpsertIndustryHandler(port).handle(state(base, port), industryJson(FARM_V2, 11L));
    ProductionData applied = apply(base, legal);
    assertThat(applied.industries()).as("正值对照：合法载荷真的落了一条新定义").containsOnlyKeys(FARM, FARM_V2);
    assertThat(applied.industry(FARM_V2).orElseThrow().outputPerUnit()).containsEntry(GRAIN, 11L);
  }

  @Test
  void upsertIndustryRejectsAnIdThatIsNotInTheCanonicalHexForm() {
    ProductionData base = ProductionData.empty();
    FakePort port = new FakePort();
    port.activate("0_0");

    List<String> badIds =
        List.of("no-hex", "mill@x_y", "mill@01_0", "mill@0_0.extra", "@0_0", "mill@0_");

    for (String id : badIds) {
      String payload = industryJson(new IndustryId(id), 7L);
      HandlerOutcome outcome = new UpsertIndustryHandler(port).handle(state(base, port), payload);
      assertThat(outcome)
          .as("★ 非规范 id（%s）必须被拒：kind 缺格/前导 0/含点/空 kind/空 r", id)
          .isInstanceOf(HandlerOutcome.Rejected.class);
    }
    // ★ 裁定二十七清扫（T2a）：base 是**显式空世界**（ProductionData.empty()）⇒ "仍然为空"恒真。
    //   改成正值对照：同一入口的规范 id 必须落得下去。
    HandlerOutcome legal =
        new UpsertIndustryHandler(port)
            .handle(state(base, port), industryJson(IndustryHexKeys.id("mill", 0, 0), 7L));
    assertThat(apply(base, legal).industries())
        .as("正值对照：规范 id 落得下去")
        .containsOnlyKeys(IndustryHexKeys.id("mill", 0, 0));
  }

  // ── ② 引用中删除 / 替换 ─────────────────────────────────────────────────────────────

  @Test
  void regionClearKeepsReferencedDefinitionsAndRemovesOnlyTheUnreferencedOnesInTheTargetHex() {
    ProductionData base =
        ProductionData.empty()
            .withIndustry(industry(FARM, 7L)) // 同格、被引用 ⇒ 必须保留（不许删悬空）
            .withIndustry(industry(MILL, 5L)) // 同格、无引用 ⇒ 删除
            .withIndustry(industry(FARM_OTHER_HEX, 6L)) // 别的格 ⇒ 不动
            .withMode(mode(MODE, 1, STRUCTURE)); // 全局 mode ⇒ 绝不随区删
    FakePort port = new FakePort();
    port.activate("0_0");
    port.activate("1_0");
    port.references(FARM, new ProductionConsumerPort.IndustryReferenceCounts(1, 0, 0));

    HandlerOutcome outcome =
        new ClearRegionDefinitionsHandler(port).handle(state(base, port), "{\"hexes\":[\"0_0\"]}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    ProductionData after = apply(base, outcome);
    assertThat(after.industries())
        .as("★ 只删本区无引用定义：farm@0_0 被引用 ⇒ 保留；farm@1_0 不在区 ⇒ 保留")
        .containsOnlyKeys(FARM, FARM_OTHER_HEX);
    assertThat(after.modes()).as("★★ 全局 mode 不随区删").containsOnlyKeys(MODE);
    assertThat(changeSetOf(outcome).industries().changed()).as("删除必须进变更集").isTrue();
    assertThat(changeSetOf(outcome).modes().changed()).as("mode 组件不许被这次清区动到").isFalse();
  }

  @Test
  void regionClearOnAHexWithNothingToRemoveIsAnEmptyChangeSetAndBadPayloadIsRejected() {
    ProductionData base =
        ProductionData.empty().withIndustry(industry(FARM, 7L)).withMode(mode(MODE, 1, STRUCTURE));
    FakePort port = new FakePort();
    port.activate("0_0");

    HandlerOutcome noop =
        new ClearRegionDefinitionsHandler(port).handle(state(base, port), "{\"hexes\":[\"9_9\"]}");
    assertThat(noop).as("没有任何命中 ⇒ 幂等成功（不是拒绝）").isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(changeSetOf(noop).isEmpty()).as("★ 无变动 ⇒ 空 ChangeSet（不落 revision）").isTrue();
    assertThat(apply(base, noop)).isEqualTo(base);

    for (String payload :
        List.of("{}", "{\"hexes\":\"0_0\"}", "{\"hexes\":[1]}", "{\"hexes\":[\" \"]}")) {
      HandlerOutcome rejected =
          new ClearRegionDefinitionsHandler(port).handle(state(base, port), payload);
      assertThat(rejected)
          .as("非法 hexes 载荷必须具名拒绝: %s", payload)
          .isInstanceOf(HandlerOutcome.Rejected.class);
    }
    // ★ 裁定二十七清扫（T2a）：原收尾断言 `base.industries()` 仍只有 FARM —— base 不可变 ⇒ 恒真，
    //   已删；本方法的真读数就是上面那两条（空变更集 + apply 恒等 + 四类坏载荷全拒）。
  }

  @Test
  void replacingAReferencedDefinitionIsAllowedOnlyUnderANewVersionedId() {
    ProductionData base = ProductionData.empty().withIndustry(industry(FARM, 7L));
    FakePort port = new FakePort();
    port.activate("0_0");
    port.references(FARM, new ProductionConsumerPort.IndustryReferenceCounts(2, 1, 1));

    HandlerOutcome inPlace =
        new UpsertIndustryHandler(port).handle(state(base, port), industryJson(FARM, 11L));
    assertThat(inPlace).as("被引用 ⇒ 原地改值拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) inPlace).reason())
        .contains("拒绝原地修改被引用的产业模板")
        .contains("units=2")
        .contains("assetShares=1")
        .contains("relations=1")
        .contains("farm_v2@0_0");

    HandlerOutcome newVersion =
        new UpsertIndustryHandler(port).handle(state(base, port), industryJson(FARM_V2, 11L));
    assertThat(newVersion).as("★ 替换的唯一合法形态 = 新版本 id").isInstanceOf(HandlerOutcome.Applied.class);
    ProductionData after = apply(base, newVersion);
    assertThat(after.industries()).containsOnlyKeys(FARM, FARM_V2);
    assertThat(after.industry(FARM).orElseThrow().outputPerUnit())
        .as("旧 id 的定义一字不动（旧实例继续读它）")
        .containsEntry(GRAIN, 7L);
  }

  // ── ③ mode：无效 mode↔阶层结构、版本、被引用停用 ──────────────────────────────────────

  @Test
  void deactivateModeRejectsAnUnknownModeAndAModeWithAnyOfTheFiveReferenceClasses() {
    ProductionData base =
        ProductionData.empty()
            .withMode(mode(MODE, 1, STRUCTURE))
            .withMode(mode(MODE_FREE, 1, STRUCTURE));
    FakePort port = new FakePort();

    HandlerOutcome unknown =
        new DeactivateProductionModeHandler(port)
            .handle(state(base, port), "{\"id\":\"ghost@0_0\"}");
    assertThat(unknown).as("★ 不存在的 mode ⇒ 具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) unknown).reason()).contains("不存在");

    port.modeReferences(
        MODE,
        new ProductionConsumerPort.ModeReferenceReport(
            List.of("cs-1"), List.of("cp-1"), List.of("org-1"), List.of("ar-1"), List.of("mt-1")));
    HandlerOutcome referenced =
        new DeactivateProductionModeHandler(port)
            .handle(state(base, port), "{\"id\":\"" + MODE.value() + "\"}");
    assertThat(referenced).as("★ 被引用 mode ⇒ 具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    String reason = ((HandlerOutcome.Rejected) referenced).reason();
    assertThat(reason)
        .as("五类引用者按类具名列出（可核对）")
        .contains("仍被引用")
        .contains("classStructures=[cs-1]")
        .contains("classPositions=[cp-1]")
        .contains("productionOrganizations=[org-1]")
        .contains("assetRules=[ar-1]")
        .contains("modeTransitions=[mt-1]")
        .doesNotContain("pledges");
    // ★ 裁定二十七清扫（T2a）：原断言"两个 mode 都还在"对不可变 base 恒真，已删；
    //   本方法的真读数是上面两条 Rejected 的具名理由 + 下面"无引用 mode 允许停用"的 Applied 逐值。

    HandlerOutcome ok =
        new DeactivateProductionModeHandler(port)
            .handle(state(base, port), "{\"id\":\"" + MODE_FREE.value() + "\"}");
    assertThat(ok).as("无引用 mode ⇒ 允许停用").isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(apply(base, ok).modes()).containsOnlyKeys(MODE);
  }

  @Test
  void modeUpsertRejectsVersionRegressionAndSilentModificationButAcceptsAnExplicitAdvance() {
    ProductionData base = ProductionData.empty().withMode(mode(MODE, 2, STRUCTURE));
    FakePort port = new FakePort();

    HandlerOutcome regression =
        new UpsertProductionModeHandler(port)
            .handle(
                state(base, port),
                "{\"id\":\"peasant@0_0\",\"name\":\"小农\",\"version\":1,\"classStructureId\":\"cs-peasant\"}");
    assertThat(regression).as("★ version 倒退 ⇒ 具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) regression).reason()).contains("不得倒退");

    HandlerOutcome silent =
        new UpsertProductionModeHandler(port)
            .handle(
                state(base, port),
                "{\"id\":\"peasant@0_0\",\"name\":\"小农（改）\",\"classStructureId\":\"cs-peasant\"}");
    assertThat(silent)
        .as("★ 改既有 mode 却不显式给 version ⇒ 具名拒绝")
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) silent).reason()).contains("必须显式给 version");

    HandlerOutcome sameVersionDifferentValue =
        new UpsertProductionModeHandler(port)
            .handle(
                state(base, port),
                "{\"id\":\"peasant@0_0\",\"name\":\"小农（改）\",\"version\":2,\"classStructureId\":\"cs-peasant\"}");
    assertThat(sameVersionDifferentValue)
        .as("★ 同 version 改值 ⇒ 具名拒绝（同 version 只允许逐值幂等重放）")
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) sameVersionDifferentValue).reason())
        .contains("修订必须推进 version");

    HandlerOutcome advanced =
        new UpsertProductionModeHandler(port)
            .handle(
                state(base, port),
                "{\"id\":\"peasant@0_0\",\"name\":\"小农（改）\",\"version\":3,\"classStructureId\":\"cs-peasant\"}");
    assertThat(advanced)
        .as("显式推进 version ⇒ 允许（不自行禁止被引用 mode 升级）")
        .isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(apply(base, advanced).modes().get(MODE).version()).isEqualTo(3);

    HandlerOutcome replay =
        new UpsertProductionModeHandler(port)
            .handle(
                state(base, port),
                "{\"id\":\"peasant@0_0\",\"name\":\"生产方式 peasant@0_0\",\"version\":2,"
                    + "\"classStructureId\":\"cs-peasant\"}");
    assertThat(replay).as("逐值相同的重放可省略推进 ⇒ 幂等").isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(changeSetOf(replay).isEmpty()).as("同值重放 = 空变更集").isTrue();
  }

  // ── ④ 装配边界：缺切片 / 切片类型不符 ⇒ 具名装配故障（不折成业务拒绝） ──────────────────────

  @Test
  void aStateWithoutTheProductionSliceIsAnAssemblyFaultNotABusinessRejection() {
    SimulationState withoutProduction =
        new SimulationState(
            new StateMeta(REF, T0),
            Map.of("economy", fakeEconomy(new FakePort())),
            InMemoryInfoSystem.empty());

    for (String payload : List.of(industryJson(FARM, 7L), "{}")) {
      assertThatThrownBy(
              () -> new UpsertIndustryHandler(new FakePort()).handle(withoutProduction, payload))
          .as("★ 缺 production 切片是装配故障 ⇒ 必须抛，不许折成 Rejected 或静默成功")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("没有 production 切片");
    }

    SimulationState withoutEconomy =
        new SimulationState(
            new StateMeta(REF, T0),
            Map.of("production", new ProductionSnapshot(REF, T0, ProductionData.empty())),
            InMemoryInfoSystem.empty());
    // ★★ D-032 B4 起跨片端口是**构造期注入**的（实现住组合根，方法带 state 入参）；「缺 economy 切片」这一故障
    //   因此归**端口实现**，不再由 handler 从 state 里现取快照时抛出。生产侧 handler 只保证"缺 production 切片 ⇒
    //   抛"（上面那一段）。本段用**真会查 economy 切片的端口实现**（{@link SliceReadingPort}）证明：
    //   缺 economy 切片 ⇒ 端口实现抛 IllegalStateException（装配故障，不许折成业务拒绝）。
    SimulationState withProductionOnly = withoutEconomy;
    assertThatThrownBy(
            () ->
                new UpsertIndustryHandler(new SliceReadingPort(new FakePort()))
                    .handle(withProductionOnly, industryJson(FARM, 7L)))
        .as("缺 economy 切片（定义命令的跨片端口）⇒ 端口实现必须抛")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有 economy 切片");
  }

  /**
   * ★ 会**现取 economy 切片**再委托的端口实现（组合根真实实现的形状：无状态、每次按 {@code state} 现取 ⇒ 结构上不可能读陈旧值）。用来把"缺切片 ⇒
   * 装配故障"这条判据钉在**端口实现**这一侧。
   */
  private record SliceReadingPort(FakePort delegate) implements ProductionConsumerPort {

    private FakePort economySlice(SimulationState state) {
      Snapshot snapshot =
          state
              .module("economy")
              .orElseThrow(() -> new IllegalStateException("state 里没有 economy 切片（装配故障）"));
      if (!(snapshot instanceof FakeEconomySnapshot)) {
        throw new IllegalStateException("economy 切片类型不符（装配故障）: " + snapshot.getClass());
      }
      return delegate;
    }

    @Override
    public boolean isHexEconomicallyActive(SimulationState state, String hexKey) {
      return economySlice(state).isHexEconomicallyActive(state, hexKey);
    }

    @Override
    public IndustryReferenceCounts industryReferences(SimulationState state, IndustryId id) {
      return economySlice(state).industryReferences(state, id);
    }

    @Override
    public ModeReferenceReport modeReferences(SimulationState state, ProductionModeId id) {
      return economySlice(state).modeReferences(state, id);
    }
  }

  /** region clear 也走同一个端口 ⇒ 缺切片同样当场炸。 */
  @Test
  void regionClearAlsoTreatsAMissingEconomySliceAsAnAssemblyFault() {
    // ★ 夹具必须让"这一格真有一条待删定义"—— 端口只在 `industryReferences` 那一行被读到；空产业表 ⇒
    //   handler 走的是"没有命中"的幂等分支、根本不问端口，本判据会变成假阴性（实测：空表时该用例红）。
    ProductionData base = ProductionData.empty().withIndustry(industry(FARM, 7L));
    SimulationState withoutEconomy =
        new SimulationState(
            new StateMeta(REF, T0),
            Map.of("production", new ProductionSnapshot(REF, T0, base)),
            InMemoryInfoSystem.empty());
    assertThatThrownBy(
            () ->
                new ClearRegionDefinitionsHandler(new SliceReadingPort(new FakePort()))
                    .handle(withoutEconomy, "{\"hexes\":[\"0_0\"]}"))
        .as("缺 economy 切片 ⇒ 必须抛")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有 economy 切片");
  }

  // ── 夹具：跨片端口的本地替身（不 import economy 实现） ────────────────────────────────

  /** 可编程的 {@link ProductionConsumerPort} 替身：哪个格已激活、哪张定义被引用、哪个结构存在。 */
  private static final class FakePort implements ProductionConsumerPort {

    private final Set<String> activeHexes = new LinkedHashSet<>();
    private final Map<IndustryId, ProductionConsumerPort.IndustryReferenceCounts> references =
        new LinkedHashMap<>();
    private final Map<ProductionModeId, ProductionConsumerPort.ModeReferenceReport> modeReports =
        new LinkedHashMap<>();

    void activate(String hexKey) {
      activeHexes.add(hexKey);
    }

    void references(IndustryId id, ProductionConsumerPort.IndustryReferenceCounts counts) {
      references.put(id, counts);
    }

    void modeReferences(ProductionModeId id, ProductionConsumerPort.ModeReferenceReport report) {
      modeReports.put(id, report);
    }

    /**
     * ★ B4 起 {@link ProductionConsumerPort} 的四个方法都带 {@code SimulationState} 入参（实现住组合根，每次现取当刻切片 ⇒
     * 结构上不可能读陈旧值）。本替身把自己的表当权威，故 {@code state} 只收不用。
     */
    @Override
    public boolean isHexEconomicallyActive(SimulationState state, String hexKey) {
      return activeHexes.contains(hexKey);
    }

    @Override
    public ProductionConsumerPort.IndustryReferenceCounts industryReferences(
        SimulationState state, IndustryId id) {
      return references.getOrDefault(
          id, new ProductionConsumerPort.IndustryReferenceCounts(0, 0, 0));
    }

    @Override
    public ProductionConsumerPort.ModeReferenceReport modeReferences(
        SimulationState state, ProductionModeId id) {
      return modeReports.getOrDefault(
          id,
          new ProductionConsumerPort.ModeReferenceReport(
              List.of(), List.of(), List.of(), List.of(), List.of()));
    }
  }

  /**
   * economy 切片的替身：只实现 {@link Snapshot} + {@link ProductionConsumerPort}（production 侧不 import
   * economy 实现）。
   *
   * <p>★ B5 起 {@code classStructureExists} 已随 {@code EconomyData.modes} 退役一并从契约面删除（ {@code
   * ProductionConsumerPort} 类注末段）⇒ 本替身不再实现它。
   */
  private record FakeEconomySnapshot(StateRef ref, SimosTimestamp timestamp, FakePort port)
      implements Snapshot, ProductionConsumerPort {

    @Override
    public String namespace() {
      return "economy";
    }

    @Override
    public boolean isHexEconomicallyActive(SimulationState state, String hexKey) {
      return port.isHexEconomicallyActive(state, hexKey);
    }

    @Override
    public ProductionConsumerPort.IndustryReferenceCounts industryReferences(
        SimulationState state, IndustryId id) {
      return port.industryReferences(state, id);
    }

    @Override
    public ProductionConsumerPort.ModeReferenceReport modeReferences(
        SimulationState state, ProductionModeId id) {
      return port.modeReferences(state, id);
    }
  }

  private static FakeEconomySnapshot fakeEconomy(FakePort port) {
    return new FakeEconomySnapshot(REF, T0, port);
  }

  private static SimulationState state(ProductionData data, FakePort port) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("production", new ProductionSnapshot(REF, T0, data));
    modules.put("economy", fakeEconomy(port));
    return new SimulationState(new StateMeta(REF, T0), modules, InMemoryInfoSystem.empty());
  }

  private static ProductionChangeSet changeSetOf(HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return (ProductionChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
  }

  private static ProductionData apply(ProductionData base, HandlerOutcome outcome) {
    return ProductionChangeSet.apply(changeSetOf(outcome), base);
  }

  /**
   * ★ <b>D-032 迁移后的 18 参形状</b>：新增 {@code buildDays}（建设期，B2c 从候选搬进定义）与 {@code
   * acceptedRightKinds}（可接受的权利性质，B2c 同上）两个组件；{@code experience} 给 {@link
   * ProductionExperienceUse#NONE}（不挂经验面）、{@code availability} 给空 list（无条件）。
   */
  private static IndustryDefinitionView industry(IndustryId id, long output) {
    return new IndustryDefinitionView(
        id,
        "产业 " + id.value(),
        TENANT,
        Optional.empty(),
        1L,
        0L,
        Map.of(AssetKind.WORKSHOP, 1L),
        Map.of(),
        0L,
        1L,
        Map.of(GRAIN, output),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1000)),
        new AllocationRule.Split(1000, 0),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }

  private static ProductionModeDefinitionView mode(
      ProductionModeId id, int version, ClassStructureId structure) {
    return new ProductionModeDefinitionView(id, "生产方式 " + id.value(), version, structure);
  }

  /**
   * ★ 载荷形状与 {@link IndustryDefinitionView} 的 18 组件一一对应（缺键取缺省，见上方 {@code industry(...)}）。 {@code
   * acceptedRightKinds} 是 B2c 新增组件：给空集 ⇒ 定义能看见但**必被采用算法具名拒绝** {@code NO_RIGHT_KIND}。
   */
  private static String industryJson(IndustryId id, long output) {
    return "{\"id\":\""
        + id.value()
        + "\",\"name\":\"产业 "
        + id.value()
        + "\",\"regime\":\"tenant\",\"cycleDays\":1,\"buildDays\":0,"
        + "\"capacityPerUnit\":{\"WORKSHOP\":1},"
        + "\"dailyInputPerUnit\":{},\"dailyLaborPerUnit\":0,\"laborPerUnit\":1,\"outputPerUnit\":{\"grain\":"
        + output
        + "},\"moneyOutputPerUnit\":{},\"cycleInputPerUnit\":{},"
        + "\"slots\":[{\"id\":\"poor_peasant\",\"name\":\"贫农\",\"laborParticipationPerMille\":1000}],"
        + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":1000,\"laborWeightPerMille\":0},"
        + "\"experience\":{\"effectScope\":\"total\",\"unlockAtPrimaryExperience\":0},"
        + "\"availability\":[],\"acceptedRightKinds\":[\"OWNED\"]}";
  }
}
