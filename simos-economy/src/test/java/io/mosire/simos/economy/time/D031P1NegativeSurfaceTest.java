package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyProductionReferenceValidator;
import io.mosire.simos.economy.EconomyReferenceValidation;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionSkillKey;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-031 P1 §5 V5（economy 侧）：负向收口成独立证据</b>（本段唯一写者，2026-10-16）。
 *
 * <p>判据原文（设计书 §5 V5）里归 economy 的那些：<b>旧 economy 定义写命令/持久表确实消失；production 不 import economy 实现；
 * economy 只 import {@code production.contract}；缺定义</b>。每条都在本类里有<b>自己的可核对断言</b>（不写"别的测试已覆盖"）。
 *
 * <p>★★ <b>两条 import 边界断言是独立复算</b>（不是转引 {@code ProductionContractGuardTest}）：本类自己扫源码树、自己先证明
 * "扫描面非空"（命中 0 先怀疑自己的读取），再断违规集为空。
 */
class D031P1NegativeSurfaceTest {

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final IndustryId FARM = IndustryHexKeys.id("farm", 0, 0);
  private static final IndustryId FARM_V2 = IndustryHexKeys.id("farm_v2", 0, 0);
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.ORGANIZATION, FARM.value());
  private static final ProductionUnitId UNIT = ProductionUnitId.idOf(FARM, OPERATOR);
  private static final CohortKey VIEW =
      new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT);
  private static final HouseholdId HOUSEHOLD = HouseholdIds.ofLegacy(VIEW);
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-v5-negative");

  /** ★ 仓库根：surefire 的工作目录是模块目录（{@code simos-economy/}）。 */
  private static final Path REPO_ROOT = Path.of("..");

  // ── ① 旧 economy 定义写命令 / 持久表确实消失 ─────────────────────────────────────────

  @Test
  void economyDataNoLongerCarriesAnyProductionDefinitionTable() {
    List<String> components = new ArrayList<>();
    for (var component : EconomyData.class.getRecordComponents()) {
      components.add(component.getName());
    }
    assertThat(components)
        .as("★★ 旧定义表 industries/modes 必须从 economy 的完整状态里消失（定义权威在 production）")
        .doesNotContain("industries", "modes")
        // ★★ D-032 C2/W2a/W2b/D4-Ib1 更新：31 → 37。计数是"组件集没被悄悄塞回东西"的绊线；
        //   新增的 32–37（名册/工资默认/工资岗位/进位/占用/预留）必须与 ChangeSet 同步。
        .hasSize(37);

    List<String> methods = new ArrayList<>();
    for (Method method : EconomyData.class.getMethods()) {
      methods.add(method.getName());
    }
    assertThat(methods)
        .as("★ 旧定义**写口**（withIndustries/withModes）必须消失：economy 不再是定义写者")
        .doesNotContain("withIndustries", "withModes");

    // ★★ 读口仍在（继承自 EconomyDayView），但它必须是 **fail-closed 的缺省**：裸 EconomyData 不持有生产定义 ⇒ 抛，
    //    绝不许把"没装配定义"降级成空表（那会让"缺定义"看起来像"这个世界没有产业"）。
    assertThatThrownBy(() -> EconomyData.empty().industries())
        .as("★★ 裸 EconomyData 的定义读口必须 fail-closed（不是返回空表）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不持有生产定义");
    assertThatThrownBy(() -> EconomyData.empty().modes())
        .as("★ modes 同一读口的同一条纪律")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不持有生产定义");
  }

  @Test
  void theOldEconomyDefinitionValueTypesAreGoneFromTheClasspath() {
    List<String> gone =
        List.of(
            "io.mosire.simos.economy.model.Industry",
            "io.mosire.simos.economy.model.ProductionMode",
            "io.mosire.simos.economy.model.ProductionRecipe",
            "io.mosire.simos.economy.model.AllocationRule",
            "io.mosire.simos.economy.model.ClassSlot",
            "io.mosire.simos.economy.model.MoneyOutput",
            "io.mosire.simos.economy.model.AllocationRuleView",
            "io.mosire.simos.economy.model.MoneyOutputView",
            "io.mosire.simos.economy.model.ProductionSlotView",
            "io.mosire.simos.economy.model.RecipeDefinitionView",
            "io.mosire.simos.economy.model.EconomyProductionDefinitionLookup",
            "io.mosire.simos.economy.model.ProductionSkillCatalog");

    for (String name : gone) {
      assertThatThrownBy(() -> Class.forName(name))
          .as("★ 旧定义类型 %s 必须已从 classpath 消失（Alpha：不保留兼容位）", name)
          .isInstanceOf(ClassNotFoundException.class);
    }
  }

  @Test
  void noEconomyMainSourceStillDeclaresTheOldDefinitionWriteCommandType() {
    Path economyMain = REPO_ROOT.resolve("simos-economy/src/main/java");
    assertThat(Files.isDirectory(economyMain)).as("扫描根必须存在（否则断言恒真）").isTrue();

    List<String> hits = new ArrayList<>();
    int scanned = 0;
    for (Path file : javaFilesUnder(economyMain)) {
      scanned++;
      for (String line : readLines(file)) {
        if (line.contains("\"economy.UpsertIndustry\"")
            || line.contains("economy.GmAdjust.upsertIndustry")
            || line.contains("economy.GmAdjust.deactivateProductionMode")) {
          hits.add(file.getFileName() + " :: " + line.strip());
        }
      }
    }
    assertThat(scanned).as("★ 先证明扫描不是静默返回空").isGreaterThan(50);
    assertThat(hits).as("★★ 旧 economy 定义写命令的类型字面量必须消失（写命令已迁 production.UpsertIndustry）").isEmpty();
  }

  @Test
  void theProductionSideOwnsTheDefinitionCommandTypeLiteral() {
    // 正向对照：同一个字面量在新权威那里必须在（否则上一条"消失"可能只是我把字面量写错了）。
    assertThat(io.mosire.simos.production.spi.UpsertIndustryHandler.TYPE)
        .as("★ 对照：新权威的命令类型字面量")
        .isEqualTo("production.UpsertIndustry");
    assertThat(io.mosire.simos.production.spi.ProductionSeedHandler.TYPE)
        .isEqualTo("production.Seed");
    assertThat(io.mosire.simos.production.spi.ClearRegionDefinitionsHandler.TYPE)
        .isEqualTo("production.ClearRegionDefinitions");
  }

  // ── ② import 边界（独立复算，不转引别的用例） ─────────────────────────────────────────

  @Test
  void productionMainNeverImportsTheEconomyImplementation() {
    Path productionMain = REPO_ROOT.resolve("simos-production/src/main/java");
    assertThat(Files.isDirectory(productionMain)).isTrue();

    List<String> violations = new ArrayList<>();
    int scanned = 0;
    for (Path file : javaFilesUnder(productionMain)) {
      scanned++;
      for (String line : importLines(file)) {
        if (line.contains("io.mosire.simos.economy.")
            && !line.contains("io.mosire.simos.economy.api.")) {
          violations.add(file.getFileName() + " :: " + line);
        }
      }
    }
    assertThat(scanned).as("★ 先证明扫描不是静默返回空").isGreaterThan(20);
    assertThat(violations)
        .as(
            "★★ production 只许经 economy-api 的 id/值类型；economy 实现包（model/time/EconomyData…）一律不得 import")
        .isEmpty();
  }

  @Test
  void economyMainOnlyReachesProductionThroughTheContractPackage() {
    Path economyMain = REPO_ROOT.resolve("simos-economy/src/main/java");
    assertThat(Files.isDirectory(economyMain)).isTrue();

    List<String> violations = new ArrayList<>();
    int productionImports = 0;
    int scanned = 0;
    for (Path file : javaFilesUnder(economyMain)) {
      scanned++;
      for (String line : importLines(file)) {
        if (!line.contains("io.mosire.simos.production.")) {
          continue;
        }
        productionImports++;
        if (!line.contains("io.mosire.simos.production.contract.")) {
          violations.add(file.getFileName() + " :: " + line);
        }
      }
    }
    assertThat(scanned).as("★ 先证明扫描不是静默返回空").isGreaterThan(50);
    assertThat(productionImports)
        .as("★ 反向自证：economy 确实 import 了 production（不是'一条都没有所以恒过'）")
        .isGreaterThan(0);
    assertThat(violations)
        .as("★★ economy 只许 import io.mosire.simos.production.contract.*（实现/工具/编解码一律不得 import）")
        .isEmpty();
  }

  // ── ★★ D-032 T2b（V11 第三条腿）：economy-api 一律不引用 production ─────────────────────

  @Test
  void economyApiNeverReferencesProductionNeitherInSourcesNorInItsPom() {
    // ★ 判据（§6 V11 / I4）：`simos-economy-api` **不引用 production**（它是更底层的契约模块，
    //   production 反过来依赖它 ⇒ 一旦 economy-api 引用 production 就是依赖环 + 边界倒置）。
    //   ★ 扫描面**先自证非空**：economy-api 有 116 个 main 源文件 + 1 个 pom.xml。
    Path apiMain = REPO_ROOT.resolve("simos-economy-api/src/main/java");
    Path apiPom = REPO_ROOT.resolve("simos-economy-api/pom.xml");
    assertThat(Files.isDirectory(apiMain)).as("扫描根必须存在（否则断言恒真）").isTrue();
    assertThat(Files.isRegularFile(apiPom)).as("pom 必须存在（否则'pom 里没有'恒真）").isTrue();

    List<String> violations = new ArrayList<>();
    int scanned = 0;
    for (Path file : javaFilesUnder(apiMain)) {
      scanned++;
      for (String line : readLines(file)) {
        if (line.contains("io.mosire.simos.production")) {
          violations.add(file.getFileName() + " :: " + line.strip());
        }
      }
    }

    assertThat(scanned).as("★ 先证明扫描不是静默返回空（economy-api 的 main 源文件数）").isGreaterThan(50);
    assertThat(violations).as("★★ economy-api 的源码不得出现任何 production 类型/包名").isEmpty();

    // ★ pom 面要**分辨方向**：`<exclude>io.mosire:simos-production</exclude>` 是 enforcer 的**禁令**（正是
    //   "不许引用"的机械强制），不是引用 —— 引用只会以依赖声明的形态出现：`<artifactId>simos-production</artifactId>`。
    List<String> apiPomLines = readLines(apiPom);
    List<String> dependencyDeclarations = new ArrayList<>();
    List<String> enforcerBans = new ArrayList<>();
    for (String line : apiPomLines) {
      String stripped = line.strip();
      if (stripped.contains("<artifactId>simos-production</artifactId>")) {
        dependencyDeclarations.add("pom.xml :: " + stripped);
      }
      if (stripped.contains("simos-production") && stripped.contains("<exclude>")) {
        enforcerBans.add(stripped);
      }
    }
    assertThat(dependencyDeclarations)
        .as(
            "★★ economy-api 不得声明对 production 的依赖（依赖声明形态 = <artifactId>simos-production</artifactId>）")
        .isEmpty();
    assertThat(enforcerBans)
        .as("★ 正向对照：enforcer 的禁列里**必须**有 simos-production（禁令在，且上面 0 命中不是路径写错）")
        .isNotEmpty();
    assertThat(String.join("\n", apiPomLines))
        .as("★ 禁令原文必须写明理由是「反向引用 = 环」（可核对，不是一句「不许」）")
        .contains("反向引用 = 环");

    // ★ 正向对照：同一个扫描工具在**确实引用 production** 的模块上必须能命中 ⇒ 证明扫描有判别力
    //   （不是"路径写错 ⇒ 永远 0 命中"那种假绿）。
    int economyHits = 0;
    for (Path file : javaFilesUnder(REPO_ROOT.resolve("simos-economy/src/main/java"))) {
      for (String line : readLines(file)) {
        if (line.contains("io.mosire.simos.production")) {
          economyHits++;
        }
      }
    }
    assertThat(economyHits)
        .as("★ 正向对照：economy 实现包**确实**引用 production ⇒ 上面那条 0 命中不是工具假阴性")
        .isGreaterThan(0);
  }

  // ── ③ 缺定义：失败必须具名（不许静默当 0） ────────────────────────────────────────────

  @Test
  void aUnitPointingAtAMissingIndustryFailsClosedInsteadOfProducingZero() {
    EconomyData world = worldWithUnit();
    // ★★ D-032 T2b 更新（测试面欠账，不是放宽）：D-032 起"生产单元"的**权威形态是 production 里的一条实例**
    //   （`EconomyStateBuilder.units()` = `runtime.units()`；economy 侧的 laborCommitments/assetShares
    // 只是引用方）。
    //   故"单元指名的产业模板不存在"这份自相矛盾状态必须构造在 **production** 一侧：有一条实例、但没有它的产业定义。
    //   （旧写法把 production 留成 `ProductionData.empty()` ⇒ 实例表为空 ⇒ 那个循环一次都不进，断言恒不触发。）
    ProductionData withoutTheIndustry =
        ProductionData.empty().withInstance(unitInstance()); // 实例 plan=FARM，而 industries 空
    assertThat(withoutTheIndustry.instances()).as("先证明这条实例真的在（否则本判据空转）").hasSize(1);
    assertThat(withoutTheIndustry.industries()).as("★ 自相矛盾的另一半：这份权威里**没有**任何产业定义").isEmpty();

    AccountSession accounts = AccountSession.empty();
    for (HouseholdEconomy row : world.classes().values()) {
      accounts.registerHousehold(
          row.id(), row.view().hex(), Map.of(GRAIN, 1_000L), Map.of(), Map.of(), Map.of());
    }
    EconomyDayStepper stepper =
        new EconomyDayStepper(
            world,
            withoutTheIndustry,
            runtimeOf(withoutTheIndustry),
            accounts,
            MarketTopology.singleHex(world.marketTables(), world.marketZones()));
    try {
      // ★ 缺定义 ⇒ **具名 fail-closed**（不是"产出 0"那种静默降级）。两条具名收口都可能先触发，逐条都算收口：
      //   ① production 引擎的具名契约故障 `instance-references-unknown-plan`（悬空引用是跨片不一致）；
      //   ② economy 结算腿的 `生产单元指名的产业模板不存在（状态已被改坏）`。
      //   本读数钉住**实际先触发的那一条**（下一条用例把 ② 单独钉住）。
      assertThatThrownBy(() -> stepper.step(1L))
          .as("★★ 缺定义 ⇒ 具名 fail-closed（不许静默当 0）")
          .satisfiesAnyOf(
              thrown ->
                  assertThat(thrown)
                      .isInstanceOf(
                          io.mosire.simos.production.contract.ProductionContractFault.class)
                      .hasMessageContaining("instance-references-unknown-plan"),
              thrown ->
                  assertThat(thrown)
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining("生产单元指名的产业模板不存在"),
              thrown ->
                  assertThat(thrown)
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining("asset-occupancy-unavailable"));
    } finally {
      stepper.close();
    }
  }

  @Test
  void referenceValidationNamesMissingIndustryMissingStructureAndBadOutputOverride() {
    // ① unit → 不存在的产业：具名拒绝。
    EconomyData brokenUnit = worldWithUnit();
    assertThatThrownBy(
            () ->
                EconomyReferenceValidation.validate(
                    ProductionData.empty(), stateOf(productionWithUnit()), brokenUnit, "v5"))
        .as("★ unit→缺产业 必须具名拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(FARM.value());

    // ② mode → 不存在的 classStructure：具名拒绝。
    EconomyData modeWithoutStructure =
        EconomyData.empty().withMeta(Optional.of(meta())).withClassStructures(Map.of());

    assertThatThrownBy(
            () ->
                EconomyReferenceValidation.validate(
                    ProductionData.empty().withMode(modeWithStructure()),
                    stateOf(ProductionData.empty()),
                    modeWithoutStructure,
                    "v5"))
        .as("★ mode→缺 classStructure 必须具名拒绝（跨片结构存在性）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cs-v5-missing");

    // ③ 输出数量覆盖指向配方里没有的商品：具名拒绝。
    EconomyData override = worldWithUnit();
    assertThatThrownBy(
            () ->
                EconomyReferenceValidation.validate(
                    definitionsWithFarm(),
                    stateOf(
                        productionWithUnit()
                            .withOutputQuantityOverride(FARM, new CommodityId("ghost"), 3L)),
                    override,
                    "v5"))
        .as("★ 覆盖→配方产出键 必须具名拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ghost");
  }

  @Test
  void deletingOrReplacingAReferencedDefinitionIsCaughtByTheCrossSliceValidator() {
    EconomyData world = worldWithUnit();
    EconomyData withExtraReference = world;

    // 被引用的定义"消失"（只剩 v2 这张别的 id）⇒ 具名拒绝，不是静默放过。
    assertThatThrownBy(
            () ->
                EconomyReferenceValidation.validate(
                    ProductionData.empty().withIndustry(industry(FARM_V2, 7L, Optional.empty())),
                    stateOf(productionWithUnit()),
                    withExtraReference,
                    "v5"))
        .as("★★ 引用中的定义被删除 ⇒ 跨片校验具名拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(FARM.value());

    // 同一批里既有 v1 又有 v2 ⇒ 通过（替换 = 新版本 id，不是把旧 id 抽走）。
    EconomyReferenceValidation.validate(
        definitionsWithFarm().withIndustry(industry(FARM_V2, 7L, Optional.empty())),
        stateOf(productionWithUnit()),
        withExtraReference,
        "v5");
  }

  @Test
  void
      theCandidateValidatorThrowsOnAMissingProductionSliceButReturnsAReasonForABusinessViolation() {
    EconomyProductionReferenceValidator validator = new EconomyProductionReferenceValidator();
    assertThat(validator.name()).isEqualTo("economy.production-references");

    // ① 装配故障：有 economy 切片却缺 production 切片 ⇒ 抛（ERROR，不降级成业务拒绝）。
    SimulationState economyOnly =
        new SimulationState(
            new StateMeta(ref(), SimosTimestamp.of(0L)),
            Map.<String, Snapshot>of(
                "economy", new EconomySnapshot(ref(), SimosTimestamp.of(0L), worldWithUnit())),
            InMemoryInfoSystem.empty());
    assertThatThrownBy(() -> validator.rejection(economyOnly, economyOnly))
        .as("★★ 缺 production 切片 ⇒ fail-closed 抛（这是装配故障，不是业务拒绝）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺 production 切片");

    // ② 领域非法新引用 ⇒ 返回具名 Optional（由 Core 折成 INFO 业务拒绝），不抛。
    SimulationState broken =
        new SimulationState(
            new StateMeta(ref(), SimosTimestamp.of(0L)),
            Map.<String, Snapshot>of(
                "economy", new EconomySnapshot(ref(), SimosTimestamp.of(0L), worldWithUnit()),
                "production",
                    new ProductionSnapshot(ref(), SimosTimestamp.of(0L), ProductionData.empty())),
            InMemoryInfoSystem.empty());
    Optional<String> reason = validator.rejection(economyOnly, broken);
    assertThat(reason).as("★ 业务非法引用 ⇒ 具名理由（不抛、不静默放过）").isPresent();
    assertThat(reason.orElseThrow()).contains(FARM.value());
  }

  // ── ④ 技能：跨格互不串味、缺技能仍可见 ────────────────────────────────────────────────

  @Test
  void skillKeysArePerIndustryAndMissingSkillStillLeavesTheDefinitionVisible() {
    IndustryId otherHexFarm = IndustryHexKeys.id("farm", 1, 0);
    ProductionSkillKey arable = new ProductionSkillKey("arable-farming");

    ProductionData data =
        ProductionData.empty()
            .withIndustry(industry(FARM, 7L, Optional.of(arable)))
            .withIndustry(industry(otherHexFarm, 7L, Optional.empty()));

    assertThat(data.keyOf(FARM)).as("★ 0_0 的农业绑了技能键").contains(arable);
    assertThat(data.keyOf(otherHexFarm))
        .as("★★ 同一 kind 在另一格没绑技能 ⇒ empty（跨格不串味，绝不由 id 猜技能）")
        .isEmpty();
    assertThat(data.industry(otherHexFarm)).as("★★ 缺技能 ≠ 产业不存在：定义照常返回完整真实记录").isPresent();
    assertThat(data.industry(otherHexFarm).orElseThrow().outputPerUnit()).containsEntry(GRAIN, 7L);
    assertThat(data.keyOf(IndustryHexKeys.id("ghost", 0, 0)))
        .as("产业不存在 ⇒ empty（与'没绑技能'同一读口，不抛）")
        .isEmpty();

    // 快照与状态同一读口（消费者只经切片看定义）。
    ProductionSnapshot snapshot = new ProductionSnapshot(ref(), SimosTimestamp.of(0L), data);
    assertThat(snapshot.keyOf(FARM)).contains(arable);
    assertThat(snapshot.keyOf(otherHexFarm)).isEmpty();
    assertThat(snapshot.industries()).containsOnlyKeys(FARM, otherHexFarm);
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────

  private static StateRef ref() {
    return new StateRef(new BranchId("main"), new RevisionId(1));
  }

  private static IndustryDefinitionView industry(
      IndustryId id, long output, Optional<ProductionSkillKey> skill) {
    // ★★★ D-032 T1：14 参 → 18 参（buildDays / experience / availability / acceptedRightKinds 逐条补齐，
    //   取值与 testing.IndustryViews 的共享夹具同款）。
    return new IndustryDefinitionView(
        id,
        "农业 " + id.value(),
        new RegimeId("tenant"),
        skill,
        1L,
        0L,
        Map.of(AssetKind.CATTLE, 1L),
        Map.of(),
        0L,
        1L,
        Map.of(GRAIN, output),
        Map.of(),
        List.of(new ClassSlot(SocialClassId.POOR_PEASANT, "贫农", 1000)),
        new AllocationRule.Split(1000, 0),
        io.mosire.simos.production.contract.MoneyOutput.NONE,
        io.mosire.simos.production.contract.ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }

  /** ★★ D-032 T1：`validate` 的新形状第 2 参是 production 的**状态只读投影**（instances + overrides）。 */
  private static io.mosire.simos.production.contract.ProductionStateLookup stateOf(
      ProductionData data) {
    return new ProductionSnapshot(ref(), SimosTimestamp.of(0L), data);
  }

  /** ★★ D-032 T1：本文件通篇引用的那个实例住在 production ⇒ 校验的世界切片由它给。 */
  private static ProductionData productionWithUnit() {
    return definitionsWithFarm().withInstance(unitInstance());
  }

  /** `worldWithUnit()` 里被 economy 各表引用的那一个实例（逐值同款：FARM / OPERATOR / 中性周期状态）。 */
  private static ProductionProcess unitInstance() {
    return new ProductionProcess(
        UNIT,
        FARM,
        ProductionLocation.ofPlan(FARM).orElseThrow(),
        OPERATOR,
        FARM.value(),
        0L,
        Map.of(),
        Map.of());
  }

  private static ProductionData definitionsWithFarm() {
    return ProductionData.empty().withIndustry(industry(FARM, 7L, Optional.empty()));
  }

  private static ProductionModeDefinitionView modeWithStructure() {
    return new ProductionModeDefinitionView(
        new io.mosire.simos.economy.api.id.ProductionModeId("peasant@0_0"),
        "小农",
        1,
        new ClassStructureId("cs-v5-missing"));
  }

  private static EconomyData worldWithUnit() {
    HouseholdEconomy row =
        new HouseholdEconomy(HOUSEHOLD, VIEW, 100L, 30_000L, 1000, 0L, Map.of(), Map.of(), 0L);
    OwnershipStake cattle =
        new OwnershipStake(
            OwnershipStake.idOf(
                FARM,
                AssetKind.CATTLE,
                OPERATOR,
                OPERATOR,
                AssetRightKind.OWNED,
                new ProductionLocation("0_0"),
                0L),
            FARM,
            AssetKind.CATTLE,
            OPERATOR,
            OPERATOR,
            10L,
            AssetRightKind.OWNED,
            new ProductionLocation("0_0"));
    HouseholdLaborCommitment commitment =
        new HouseholdLaborCommitment(
            ALLOCATION,
            LOT,
            HOUSEHOLD,
            OPERATOR,
            UNIT.value(),
            30_000L,
            1L,
            LaborCommitmentKind.PRODUCTION,
            Optional.of(ProductionRoleId.parse("operator")));
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withHouseholdEconomies(Map.of(HOUSEHOLD, row))
        .withOwnershipStakes(Map.of(cattle.id(), cattle))
        .withLaborCommitments(Map.of(ALLOCATION, commitment));
  }

  private static EconomyMeta meta() {
    return new EconomyMeta(
        "d031-v5-negative",
        0L,
        OptionalLong.empty(),
        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
        Optional.empty());
  }

  // ── 源码扫描小工具 ────────────────────────────────────────────────────────────────

  private static List<Path> javaFilesUnder(Path root) {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> importLines(Path file) {
    List<String> imports = new ArrayList<>();
    for (String line : readLines(file)) {
      String trimmed = line.strip();
      if (trimmed.startsWith("import ")) {
        imports.add(trimmed);
      }
    }
    return imports;
  }

  /** ★★ D-032 B2b/B3：会话/日推进器必须显式拿到 production 运行时（{@code runtime=null} 的旧构造器已删）。 */
  private static io.mosire.simos.production.contract.ProductionRuntime runtimeOf(
      io.mosire.simos.production.ProductionData definitions) {
    return new io.mosire.simos.production.ProductionRuntimeEngine(definitions);
  }
}
