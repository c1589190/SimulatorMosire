package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.world.RichWorld;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E3 的真行为测试：**有世界就是有，没有就就地初始化一个新的**（走真 {@link Shell} + 真 {@code bootstrapGenesis} + 真 {@link
 * RichWorld}）。
 *
 * <p>★ 为什么这些断言有判别力：
 *
 * <ul>
 *   <li><b>空库 ⇒ 建起来</b>：断言分支/head 与**重放出的世界内容**（hex 数、与 {@link RichWorld} 逐字段相等）——不是 "方法没抛异常就算过"。
 *   <li><b>非空库绝不覆盖</b>：第二次起同一库后，{@code seedGenesisIfEmpty} 必须返回 {@code false}，且 head/revision
 *       行数/重放内容与第一次结束时**逐项相同**。把 {@code shouldSeedGenesis} 的守卫去掉 ⇒ 第二次会去调 {@code bootstrapGenesis}
 *       ⇒ 撞核心的"非空库拒绝"当场抛（用例红）；若连核心守卫也去掉 ⇒ 重复 {@code (main,1)} 的 INSERT 违反主键/或追加出 head 2 ⇒ 断言红。
 * </ul>
 *
 * <p>★ 端口一律 {@code 0}（OS 随机口），不占固定端口、不起常驻服务。
 */
class ShellMainGenesisTest {

  private static final BranchId MAIN = new BranchId("main");

  @TempDir Path tempDir;

  @Test
  void emptyStoreIsInitializedInPlaceWithoutAnyWorldFlag() {
    ShellConfig config = ShellConfig.defaults(tempDir).withPorts(0, 0, 0);

    try (Shell shell = Shell.start(config)) {
      assertThat(shell.coreSimos().branches()).as("开局是空库").isEmpty();

      assertThat(ShellMain.seedGenesisIfEmpty(shell)).as("空库 ⇒ 就地初始化").isTrue();

      assertThat(shell.coreSimos().branches()).as("世界已建起来").containsExactly(MAIN);
      assertThat(shell.coreSimos().head(MAIN)).as("创世落在 (main, 1)").contains(new RevisionId(1));
      assertThat(shell.coreSimos().revisions(MAIN)).as("只有创世那一行").hasSize(1);

      SimulationState replayed = shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(1)));
      assertThat(replayed).as("真引擎往返：种进去的 == 资源解出的富世界").isEqualTo(RichWorld.state(config.mapId()));
      GameMap map = ((MapSnapshot) replayed.module("map").orElseThrow()).map();
      assertThat(map.hexes()).as("世界确非空（v17levant 的 hex 数）").hasSize(59223);
    }
  }

  @Test
  void nonEmptyStoreIsNeverReinitializedAndKeepsItsData() {
    ShellConfig config = ShellConfig.defaults(tempDir).withPorts(0, 0, 0);

    long headAfterFirst;
    int revisionRowsAfterFirst;
    SimulationState worldAfterFirst;
    try (Shell first = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(first)).as("第一次：空库 ⇒ 种").isTrue();
      headAfterFirst = first.coreSimos().head(MAIN).orElseThrow().value();
      revisionRowsAfterFirst = first.coreSimos().revisions(MAIN).size();
      worldAfterFirst = first.coreSimos().replay(new StateRef(MAIN, new RevisionId(1)));
    }

    try (Shell second = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(second)).as("第二次：非空库 ⇒ 不重新初始化（绝不覆盖）").isFalse();

      assertThat(second.coreSimos().branches()).as("分支集合不被改写").containsExactly(MAIN);
      assertThat(second.coreSimos().head(MAIN).orElseThrow().value())
          .as("head 与第一次结束时一致")
          .isEqualTo(headAfterFirst);
      assertThat(second.coreSimos().revisions(MAIN))
          .as("revision 行数与第一次结束时一致（没有多写一行）")
          .hasSize(revisionRowsAfterFirst);
      assertThat(second.coreSimos().replay(new StateRef(MAIN, new RevisionId(1))))
          .as("既有世界内容与第一次结束时逐字段一致（数据没丢）")
          .isEqualTo(worldAfterFirst);
    }
  }
}
