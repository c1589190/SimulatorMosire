package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.world.BlankEditorWorld;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.culture.CultureData;
import io.mosire.simos.culture.CultureSnapshot;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R5：{@code blank-editor} 是**只组装空切片**的创世世界。
 *
 * <p>判别力：真 {@link Shell} + 真 {@code bootstrapGenesis} + 真 checkpoint
 * 重放后，断言的不是“方法没抛”，而是十个模块都在、地图为空但标准 terrain/pathway 词表在、social/economy/production/culture 都是 {@code
 * empty()}；同时产品缺省 world 仍是 v17levant。把 {@link BlankEditorWorld} 里的任一枚举/播种器改动（例如误调经济播种）都会让逐字段相等断言红。
 */
class BlankEditorGenesisTest {
  private static final BranchId MAIN = new BranchId("main");

  @TempDir Path tempDir;

  @Test
  void blankEditorBootstrapsTenEmptySlicesWithStandardTerrainAndPathwayVocabulary() {
    ShellConfig config =
        ShellConfig.defaults(tempDir).withWorldId(WorldRegistry.BLANK_EDITOR).withPorts(0, 0);

    try (Shell shell = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(shell)).as("空库 ⇒ 按 blank-editor 建世").isTrue();
      assertThat(shell.coreSimos().head(MAIN)).contains(new RevisionId(1));

      SimulationState replayed = shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(1)));
      assertThat(replayed)
          .as("重放出的世界必须与 blank-editor 生成器逐字段相同")
          .isEqualTo(BlankEditorWorld.state(config.mapId()));
      assertThat(replayed.modules())
          .as("十个空领域快照（map/social/unit/economy/actor/ga/gov/army/production/culture）")
          .hasSize(10)
          .containsKeys(
              "map",
              "social",
              "unit",
              "economy",
              "actor",
              "ga",
              "gov",
              "army",
              "production",
              "culture");

      GameMap map = ((MapSnapshot) replayed.module("map").orElseThrow()).map();
      assertThat(map.hexes()).as("创世地图为空，等待底图导入").isEmpty();
      assertThat(map.terrainTypes().keySet())
          .as("标准地形词表必须在（不是空表、也不是另立词表）")
          .containsExactlyElementsOf(TerrainCatalog.defaults().keySet());
      assertThat(map.pathwayGroups()).as("标准通路词表必须在").isEqualTo(PathwayGroup.defaults());
      assertThat(map.regions()).isEmpty();
      assertThat(map.cities()).isEmpty();
      assertThat(map.pathways()).isEmpty();
      assertThat(map.edges()).isEmpty();

      SocialData social = ((SocialSnapshot) replayed.module("social").orElseThrow()).data();
      assertThat(social.populations()).as("无人口播种").isEmpty();
      assertThat(social.cities()).as("无城市播种").isEmpty();
      assertThat(social.groups()).isEmpty();
      assertThat(social.households()).as("无家户播种").isEmpty();

      EconomyData economy = ((EconomySnapshot) replayed.module("economy").orElseThrow()).data();
      assertThat(economy).as("无经济播种").isEqualTo(EconomyData.empty());

      ProductionData production =
          ((ProductionSnapshot) replayed.module("production").orElseThrow()).state();
      assertThat(production).as("无生产播种").isEqualTo(ProductionData.empty());
      CultureData culture = ((CultureSnapshot) replayed.module("culture").orElseThrow()).state();
      assertThat(culture).as("无文化播种").isEqualTo(CultureData.empty());
    }
  }

  @Test
  void productDefaultWorldIsStillV17levant() {
    assertThat(ShellConfig.DEFAULT_WORLD_ID).isEqualTo(WorldRegistry.V17LEVANT);
    assertThat(ShellConfig.defaults(tempDir).worldId()).isEqualTo(WorldRegistry.V17LEVANT);
    assertThat(WorldRegistry.require(WorldRegistry.V17LEVANT).description())
        .as("缺省世界仍是 v17levant 富世界")
        .contains("v17levant")
        .contains("59223");
    assertThat(WorldRegistry.knownIds())
        .as("blank-editor 只是新增选择，不替换任何旧世界")
        .contains(WorldRegistry.V17LEVANT, WorldRegistry.BLANK_EDITOR);
  }
}
