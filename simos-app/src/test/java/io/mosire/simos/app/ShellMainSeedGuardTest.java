package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.util.state.BranchId;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code --demo} 空库判定的护栏（T11 / C25）：只有"给了 {@code --demo} **且** 库为空"才种入富世界；非空库绝不覆盖。
 *
 * <p>★ 判定抽成 {@link ShellMain#shouldSeedGenesis} 才可测——{@code run} 阻塞到停止信号，无法直接用例覆盖。
 */
class ShellMainSeedGuardTest {

  @Test
  void seedsOnlyWhenDemoRequestedAndStoreIsEmpty() {
    assertThat(ShellMain.shouldSeedGenesis(true, Set.of())).as("--demo + 空库 ⇒ 种").isTrue();
    assertThat(ShellMain.shouldSeedGenesis(true, Set.of(new BranchId("main"))))
        .as("--demo + 非空库 ⇒ 不种（不覆盖）")
        .isFalse();
    assertThat(ShellMain.shouldSeedGenesis(false, Set.of())).as("无 --demo ⇒ 不种").isFalse();
    assertThat(ShellMain.shouldSeedGenesis(false, Set.of(new BranchId("main"))))
        .as("无 --demo + 非空库 ⇒ 不种")
        .isFalse();
  }
}
