package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.classfirst.ClassFirstPilotEngine;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.MobilityPolicy;
import io.mosire.simos.economy.classfirst.PilotConfig;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.spi.EconomyGmAdjustments.Projection;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>P7 GM 生产方式编辑</b>：mode-first 首建闭环 + 版本推进/倒退 + 引用完整性 + 被引用不可停用 + 同值幂等。
 *
 * <p>★ 首建夹具是一个<b>最小 class-first 世界</b>（{@code classFirst} 非空、{@code classStructures} 空）——这正是 P7
 * 注释里的迁移入口：允许先建引用“尚未创建的结构 id”的 mode，随后补结构，再挂位置。这样断的是真实首建路径， 不是拿目录里已存在的结构做“假首建”。
 */
class EconomyGmAdjustP7Test {

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  private static final ProductionModeId MODE = new ProductionModeId("gm-bootstrap-mode");
  private static final ClassStructureId STRUCTURE = new ClassStructureId("gm-bootstrap-structure");
  private static final ClassPositionId OWNER = new ClassPositionId("gm-bootstrap-owner");
  private static final ClassPositionId LABORER = new ClassPositionId("gm-bootstrap-laborer");
  private static final ClassPositionId MANAGER = new ClassPositionId("gm-bootstrap-manager");

  @Test
  void modeFirstBootstrapCreatesModeThenStructureThenPosition() throws Exception {
    EconomyData base = classFirstBase();
    assertThat(base.classFirst().isEmpty()).as("首建夹具必须是非空 class-first 世界").isFalse();
    assertThat(base.classStructures()).as("首建入口：结构表为空").isEmpty();
    assertThat(base.modes()).as("首建入口：mode 表为空").isEmpty();

    Projection modeProjection =
        project(
            base,
            EconomyGmAdjustments.UPSERT_PRODUCTION_MODE,
            """
            {"id":"gm-bootstrap-mode","name":"GM 首建生产方式","version":1,
             "classStructureId":"gm-bootstrap-structure"}
            """);
    assertThat(modeProjection.changes()).as("首建 mode：changeSet 必须非空且点名 modes 键").isNotEmpty();
    assertThat(modeProjection.changeSet().isEmpty()).as("changeSet 非空").isFalse();
    assertThat(modeProjection.projected().modes()).containsKey(MODE);
    assertThat(modeProjection.projected().modes().get(MODE).classStructureId())
        .as("mode 先引用尚未创建的结构 id（P7 首建入口）")
        .isEqualTo(STRUCTURE);
    assertThat(modeProjection.projected().classStructures()).as("第一步只建 mode，结构还没落地").isEmpty();

    Projection structureProjection =
        project(
            modeProjection.projected(),
            EconomyGmAdjustments.UPSERT_CLASS_STRUCTURE,
            """
            {"id":"gm-bootstrap-structure","modeId":"gm-bootstrap-mode",
             "positions":[
               {"id":"gm-bootstrap-owner","name":"GM 所有者","relationToMeans":"OWNER",
                "laborRole":"ORGANIZER","surplusRole":"SURPLUS_RECEIVER"},
               {"id":"gm-bootstrap-laborer","name":"GM 劳动者","relationToMeans":"DIRECT_LABORER",
                "laborRole":"PROVIDER","surplusRole":"WAGE_EARNER"}],
             "defaultSharesPerMille":{"gm-bootstrap-owner":600,"gm-bootstrap-laborer":400}}
            """);
    assertThat(structureProjection.changes()).as("第二步建结构 + 两个位置").isNotEmpty();
    assertThat(structureProjection.projected().classStructures()).containsKey(STRUCTURE);
    assertThat(
            structureProjection.projected().classStructures().get(STRUCTURE).positions().keySet())
        .as("结构内位置")
        .containsExactlyInAnyOrder(OWNER, LABORER);
    assertThat(structureProjection.projected().classPositions())
        .as("全局位置表与结构内副本同批落地")
        .containsKeys(OWNER, LABORER);
    assertThat(
            structureProjection
                .projected()
                .classStructures()
                .get(STRUCTURE)
                .defaultSharesPerMille())
        .as("默认份额逐值保留")
        .containsEntry(OWNER, 600L)
        .containsEntry(LABORER, 400L);

    Projection positionProjection =
        project(
            structureProjection.projected(),
            EconomyGmAdjustments.UPSERT_CLASS_POSITION,
            """
            {"id":"gm-bootstrap-manager","modeId":"gm-bootstrap-mode",
             "classStructureId":"gm-bootstrap-structure","name":"GM 经理",
             "relationToMeans":"OPERATOR","laborRole":"ORGANIZER",
             "surplusRole":"SURPLUS_RECEIVER","ruleExtensions":{"note":"p9"}}
            """);
    EconomyData afterPosition = positionProjection.projected();
    assertThat(positionProjection.changes()).as("第三步挂新位置").isNotEmpty();
    assertThat(afterPosition.classPositions()).containsKey(MANAGER);
    assertThat(afterPosition.classStructures().get(STRUCTURE).positions().keySet())
        .as("新位置已挂进结构")
        .containsExactlyInAnyOrder(OWNER, LABORER, MANAGER);
    assertThat(afterPosition.modes()).containsKey(MODE);
    assertThat(afterPosition.classFirst())
        .as("GM 编辑不碰 class-first 状态")
        .isEqualTo(base.classFirst());
  }

  @Test
  void versionMustAdvanceAndRegressionIsRejectedByName() throws Exception {
    EconomyData base = bootstrappedWorld();

    Projection v2 =
        project(
            base,
            EconomyGmAdjustments.UPSERT_PRODUCTION_MODE,
            """
            {"id":"gm-bootstrap-mode","name":"GM 首建生产方式 v2","version":2,
             "classStructureId":"gm-bootstrap-structure"}
            """);
    assertThat(v2.projected().modes().get(MODE).version()).as("显式推进到 version=2").isEqualTo(2);

    assertThatThrownBy(
            () ->
                project(
                    v2.projected(),
                    EconomyGmAdjustments.UPSERT_PRODUCTION_MODE,
                    """
                    {"id":"gm-bootstrap-mode","name":"GM 首建生产方式 v1","version":1,
                     "classStructureId":"gm-bootstrap-structure"}
                    """))
        .as("version 从 2 倒退到 1 必须具名拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得倒退");

    assertThatThrownBy(
            () ->
                project(
                    v2.projected(),
                    EconomyGmAdjustments.UPSERT_PRODUCTION_MODE,
                    """
                    {"id":"gm-bootstrap-mode","name":"同版本改值","version":2,
                     "classStructureId":"gm-bootstrap-structure"}
                    """))
        .as("同 version 改值（非幂等重放）必须拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
  }

  @Test
  void sameValueReplayIsAnEmptyChangeSet() throws Exception {
    EconomyData base = bootstrappedWorld();

    Projection modeReplay =
        project(
            base,
            EconomyGmAdjustments.UPSERT_PRODUCTION_MODE,
            """
            {"id":"gm-bootstrap-mode","name":"GM 首建生产方式","version":1,
             "classStructureId":"gm-bootstrap-structure"}
            """);
    assertThat(modeReplay.changes()).as("同值重放：changes 为空").isEmpty();
    assertThat(modeReplay.changeSet().isEmpty()).as("同值重放：changeSet 为空").isTrue();
    assertThat(modeReplay.projected()).as("同值重放不制造新状态").isEqualTo(base);

    Projection structureReplay =
        project(base, EconomyGmAdjustments.UPSERT_CLASS_STRUCTURE, structureJson());
    assertThat(structureReplay.changes()).as("结构同值重放：changes 为空").isEmpty();
    assertThat(structureReplay.changeSet().isEmpty()).as("结构同值重放：changeSet 为空").isTrue();
  }

  @Test
  void missingReferencesAreRejectedByName() throws Exception {
    EconomyData base = bootstrappedWorld();

    assertThatThrownBy(
            () ->
                project(
                    base,
                    EconomyGmAdjustments.UPSERT_PRODUCTION_MODE,
                    """
                    {"id":"gm-missing-structure-mode","name":"缺结构","version":1,
                     "classStructureId":"does-not-exist"}
                    """))
        .as("mode 引用不存在的 classStructure ⇒ 拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("classStructureId")
        .hasMessageContaining("does-not-exist");

    assertThatThrownBy(
            () ->
                project(
                    base,
                    EconomyGmAdjustments.UPSERT_CLASS_STRUCTURE,
                    """
                    {"id":"gm-bad-structure","modeId":"does-not-exist",
                     "positions":[{"id":"gm-bad-position","name":"孤儿位置",
                       "relationToMeans":"DIRECT_LABORER","laborRole":"PROVIDER",
                       "surplusRole":"WAGE_EARNER"}]}
                    """))
        .as("结构引用不存在的 mode ⇒ 拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("modeId")
        .hasMessageContaining("does-not-exist");

    assertThatThrownBy(
            () ->
                project(
                    base,
                    EconomyGmAdjustments.UPSERT_CLASS_POSITION,
                    """
                    {"id":"gm-orphan-position","modeId":"gm-bootstrap-mode","name":"无结构位置",
                     "relationToMeans":"DIRECT_LABORER","laborRole":"PROVIDER",
                     "surplusRole":"WAGE_EARNER"}
                    """))
        .as("位置不属于任何结构且未给 classStructureId ⇒ 拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("classStructureId");
  }

  @Test
  void deactivatingAReferencedModeIsRejectedByName() throws Exception {
    EconomyData base = bootstrappedWorld();

    assertThatThrownBy(
            () ->
                project(
                    base,
                    EconomyGmAdjustments.DEACTIVATE_PRODUCTION_MODE,
                    "{\"id\":\"gm-bootstrap-mode\"}"))
        .as("mode 仍被结构/位置引用 ⇒ 不可停用，且必须点名引用者")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("仍被引用")
        .hasMessageContaining("gm-bootstrap-structure");

    assertThatThrownBy(
            () ->
                project(
                    base,
                    EconomyGmAdjustments.DEACTIVATE_PRODUCTION_MODE,
                    "{\"id\":\"no-such-mode\"}"))
        .as("停用不存在的 mode ⇒ 具名拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不存在");
  }

  @Test
  void deactivatingAModeReferencedByOrganizationIsRejectedByName() throws Exception {
    EconomyData base = bootstrappedWorld();
    Projection organization =
        project(
            base,
            EconomyGmAdjustments.UPSERT_PRODUCTION_ORGANIZATION,
            """
            {"id":"gm-bootstrap-org","modeId":"gm-bootstrap-mode",
             "classPositionId":"gm-bootstrap-owner",
             "organizer":{"kind":"HOUSEHOLD","id":"hh-0_0-laborer"},
             "laborSources":[],"assetSources":[],"inputSources":[],
             "outputOwnership":{"household":"hh-0_0-laborer"},
             "status":"SUSPENDED","statusReason":"P9 引用完整性测试"}
            """);
    assertThat(organization.projected().productionOrganizations())
        .as("组织必须真的落进生产组织表")
        .containsKey(new ProductionOrganizationId("gm-bootstrap-org"));

    assertThatThrownBy(
            () ->
                project(
                    organization.projected(),
                    EconomyGmAdjustments.DEACTIVATE_PRODUCTION_MODE,
                    "{\"id\":\"gm-bootstrap-mode\"}"))
        .as("mode 被 productionOrganization 引用 ⇒ 不可停用，且必须点名引用者")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("仍被引用")
        .hasMessageContaining("productionOrganizations")
        .hasMessageContaining("gm-bootstrap-org");
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────────

  /** mode-first 首建后的世界：一个 mode + 一个 struct + 两个 position。 */
  private static EconomyData bootstrappedWorld() throws Exception {
    EconomyData base = classFirstBase();
    EconomyData afterMode =
        project(
                base,
                EconomyGmAdjustments.UPSERT_PRODUCTION_MODE,
                """
                {"id":"gm-bootstrap-mode","name":"GM 首建生产方式","version":1,
                 "classStructureId":"gm-bootstrap-structure"}
                """)
            .projected();
    return project(afterMode, EconomyGmAdjustments.UPSERT_CLASS_STRUCTURE, structureJson())
        .projected();
  }

  private static String structureJson() {
    return """
        {"id":"gm-bootstrap-structure","modeId":"gm-bootstrap-mode",
         "positions":[
           {"id":"gm-bootstrap-owner","name":"GM 所有者","relationToMeans":"OWNER",
            "laborRole":"ORGANIZER","surplusRole":"SURPLUS_RECEIVER"},
           {"id":"gm-bootstrap-laborer","name":"GM 劳动者","relationToMeans":"DIRECT_LABORER",
            "laborRole":"PROVIDER","surplusRole":"WAGE_EARNER"}],
         "defaultSharesPerMille":{"gm-bootstrap-owner":600,"gm-bootstrap-laborer":400}}
        """;
  }

  private static Projection project(EconomyData base, String adjustment, String parametersJson)
      throws Exception {
    JsonNode parameters = JSON.readTree(parametersJson);
    return EconomyGmAdjustments.project(base, adjustment, parameters, "P9 GM 编辑测试", 0L);
  }

  /**
   * 最小 class-first 世界：一个雇农家户 + 一个放贷主体——只为了让 {@code classFirst} 非空、从而进入 P7 的 首建分支（{@code classFirst}
   * 非空 + {@code classStructures} 空）。
   */
  private static EconomyData classFirstBase() {
    PilotModel.Lender lender =
        new PilotModel.Lender("gm-lender", 1_000_000L, Map.of(), 20L, 60L, 1000L);
    PilotModel.CollectionPolicy policy =
        new PilotModel.CollectionPolicy(
            PilotModel.LANDLORD_ID,
            1_000_000_000L,
            6000L,
            250L,
            20L,
            PilotModel.SeizurePriority.LIQUID_THEN_LAND);
    PilotConfig config =
        PilotConfig.tenancyAgriculture(lender, policy, 90L, MobilityPolicy.tenancyDefaults());
    PilotModel.Household household =
        new PilotModel.Household(
            "hh-0_0-laborer",
            "测试雇农",
            PilotModel.LABORER_ID,
            1000L,
            500L,
            Map.of(),
            0L,
            0L,
            0L,
            1000L);
    ClassFirstState state =
        new ClassFirstPilotEngine(config, List.of(household), List.of(lender)).snapshot();
    return EconomyData.empty().withClassFirst(state);
  }
}
