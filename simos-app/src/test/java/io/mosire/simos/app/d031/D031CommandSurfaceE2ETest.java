package io.mosire.simos.app.d031;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.culture.CultureData;
import io.mosire.simos.culture.CultureSnapshot;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>D-031 命令面端到端（spec §11 必测 2）</b>：真 {@link GovZ6WorldFixture}（真 Shell + 真创世）里
 *
 * <ul>
 *   <li>标准新世界具备 {@code production} / {@code culture} 两片；
 *   <li>五条新命令经真 {@code CoreSimos.submit}（真 CommandBus）<b>写入 revision</b>，重放后逐值仍在；
 *   <li>拒绝语义：{@code SetMastery} 引用未登记科技 ⇒ 具名拒；{@code RemoveAptitudeRule} 不存在 ⇒ 具名拒且 head 不动；
 *   <li>catalog 五条载荷提示可发现，且 catalog 仍 GM-only。
 * </ul>
 */
class D031CommandSurfaceE2ETest {

  private static final BranchId MAIN = new BranchId("main");

  @TempDir Path tempDir;

  @Test
  void standardNewWorldCarriesTheProductionAndCultureSlices() {
    try (GovZ6WorldFixture.World world = world("slices")) {
      SimulationState state = world.state();
      assertThat(state.module("production"))
          .as("新世界必须携带 production 空片（否则命令在 CommandBus 找不到命名空间）")
          .containsInstanceOf(ProductionSnapshot.class);
      assertThat(state.module("culture")).containsInstanceOf(CultureSnapshot.class);
      // ★ P1：真创世在同一次落账里经 production.Seed 播下定义 ⇒ production 片非空（不再是空片）。
      ProductionData production =
          ((ProductionSnapshot) state.module("production").orElseThrow()).state();
      assertThat(production.industries()).as("创世播下的产业定义非空").isNotEmpty();
      assertThat(production.modes()).as("创世播下的生产方式定义非空").isNotEmpty();
      assertThat(((CultureSnapshot) state.module("culture").orElseThrow()).state())
          .isEqualTo(CultureData.empty());
    }
  }

  // ★★★ D-032 T1（测试 owner）：本类原为「D-031 五条新命令的命令面端到端」。本批两处裁定把其中四条命令
  //   **整体退役**：D1（用户 2026-10-17「D1 直接删」）删掉 `production.RegisterTechnology` /
  //   `production.SetMastery`（科技点面整条退役，`ProductionData` 不再有 technologies/masteries 组件）；
  //   D2 删掉 `culture.UpsertAptitudeRule` / `culture.RemoveAptitudeRule`（文化→生产效率倍率链整条退役，
  //   handler 与 `CultureData` 的规则表一并删除）。⇒ 以下两条用例的被测命令在 `src/main` 里**已不存在**，
  //   整方法删除（不是放宽断言）：
  //     · `fiveCommandsCommitThroughTheRealCommandBusAndSurviveReplay`（四条命令的提交/重放读数）
  //     · `setMasteryRejectsAnUnregisteredTechnologyWithoutRevision`（SetMastery 的具名拒）
  //   ★ 仍然成立的判据（唯一存活的那条命令 + 两片装配）保留在下面两条用例里。

  @Test
  void householdCultureCommandCommitsThroughTheRealCommandBusAndSurvivesReplay() {
    try (GovZ6WorldFixture.World world = world("commands")) {
      CoreSimos core = world.core();

      assertCommitted(
          submit(
              world,
              "culture.SetHouseholdCulture",
              "{\"householdId\":\"hh-d031\",\"cultureId\":\"latium\",\"religionId\":\"roman\","
                  + "\"customs\":[\"patrilineal\"]}"));

      long headAfterSet = world.head();
      SimulationState replayed = core.replay(GovZ6WorldFixture.ref(headAfterSet));

      ProductionSnapshot production =
          (ProductionSnapshot) replayed.module("production").orElseThrow();
      // ★ D-032 T1：产业/生产方式定义由真创世的 `production.Seed` 落下（定义权威在 production）——
      //   原断言读的是 technologies/masteries（D1 已整条退役）。
      assertThat(production.state().industries()).as("创世播下的产业定义在重放后仍在").isNotEmpty();

      CultureSnapshot culture = (CultureSnapshot) replayed.module("culture").orElseThrow();
      assertThat(culture.state().householdCultures())
          .as("家户文化写入 revision，重放后逐值仍在")
          .containsKey(io.mosire.simos.social.api.id.HouseholdId.parse("hh-d031"));
    }
  }

  @Test
  void catalogExposesTheHouseholdCulturePayloadHintAndStaysGmOnly() {
    // ★★ D-032 T1：五条命令里四条已按 D1/D2 退役 ⇒ 载荷提示面收缩到唯一存活的那一条。
    Set<String> types = Set.of("culture.SetHouseholdCulture");

    CatalogTool catalog = new CatalogTool(types);
    assertThat(catalog.roles())
        .as("catalog 是共享只读工具（DM+GM 都能看），命令的 GM-only 由 handler 契约面钉住")
        .containsExactlyInAnyOrder(
            io.mosire.simos.ga.spi.ToolRole.DM, io.mosire.simos.ga.spi.ToolRole.GM);
    // ★ 构造期强制：该 type 缺载荷提示 ⇒ 构造器当场抛（本行不抛即证明它在 PAYLOAD_HINTS 里）。
    assertThat(catalog.name()).isEqualTo(CatalogTool.NAME);

    // ★ 结构化补充：该 handler 源码不得实现 DmUsableCommand / DecisionProposable（仍 GM-only 的命令契约面）。
    for (Path handlerSource : commandHandlerSources()) {
      String text = readText(handlerSource);
      assertThat(text)
          .as("%s 不得声明 DM 可用/可嵌入（该命令仍 GM-only）", handlerSource.getFileName())
          .doesNotContain("DmUsableCommand")
          .doesNotContain("DecisionProposable");
    }
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────

  private GovZ6WorldFixture.World world(String label) {
    return GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve(label), "d031-" + label);
  }

  private static CommandResult submit(GovZ6WorldFixture.World world, String type, String payload) {
    long head = world.head();
    return world
        .core()
        .submit(
            new CommandEnvelope(
                "cmd-d031-" + (head + 1) + "-" + type,
                "corr-d031-" + (head + 1),
                "gm:d031-test",
                MAIN,
                new RevisionId(head),
                type,
                payload));
  }

  private static void assertCommitted(CommandResult result) {
    assertThat(result)
        .as("命令必须经真 CommandBus 提交成功: %s", result)
        .isInstanceOf(CommandResult.Committed.class);
  }

  /**
   * ★★ D-032 T1：本清单原为 D-031 五条命令的 handler 源文件。D1/D2 把四条命令整体退役 ⇒ 只剩 {@code
   * culture.SetHouseholdCulture}（其余四个 handler 源文件在 {@code src/main} 里已不存在， 保留清单会让 {@link #readText}
   * 的"源文件必须存在"断言当场红）。
   */
  private static List<Path> commandHandlerSources() {
    return List.of(
        Path.of(
            "..",
            "simos-culture",
            "src",
            "main",
            "java",
            "io",
            "mosire",
            "simos",
            "culture",
            "spi",
            "SetHouseholdCultureHandler.java"));
  }

  private static String readText(Path file) {
    assertThat(Files.isRegularFile(file)).as("源文件必须存在: %s", file).isTrue();
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
