package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.util.state.BranchId;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 空库判定的护栏（T11 / C25 / E3）：**只有库为空**才就地初始化世界；**非空库绝不覆盖**。
 *
 * <p>★ 判定抽成 {@link ShellMain#shouldSeedGenesis} 才可测——{@code run} 阻塞到停止信号，无法直接用例覆盖。 ★ 不再有 {@code
 * --demo} 这一维：世界开关已拔掉，"空 ⇒ 建、非空 ⇒ 不碰"就是全部语义。
 */
class ShellMainSeedGuardTest {

  @Test
  void seedsExactlyWhenTheStoreIsEmpty() {
    assertThat(ShellMain.shouldSeedGenesis(Set.of())).as("空库 ⇒ 初始化").isTrue();
    assertThat(ShellMain.shouldSeedGenesis(Set.of(new BranchId("main"))))
        .as("非空库 ⇒ 不初始化（绝不覆盖）")
        .isFalse();
    assertThat(ShellMain.shouldSeedGenesis(Set.of(new BranchId("main"), new BranchId("b2"))))
        .as("多分支的非空库同样绝不覆盖")
        .isFalse();
  }
}
