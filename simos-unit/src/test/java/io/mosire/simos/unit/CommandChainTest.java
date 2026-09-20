package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link CommandChain} 的构造期不变量（spec §一.2 / §一.6 不变量 2）与「多属是正常态」。 */
class CommandChainTest {

  private static final UnitId A = new UnitId("u-a");
  private static final UnitId B = new UnitId("u-b");

  @Test
  void commanderMustBeAMember() {
    assertThatThrownBy(() -> new CommandChain(new CommandChainId("c-1"), "链", A, Set.of(B)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commander");
  }

  @Test
  void membersMustNotBeEmpty() {
    assertThatThrownBy(() -> new CommandChain(new CommandChainId("c-1"), "链", A, Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("members");
  }

  @Test
  void aUnitMayBelongToSeveralChains() {
    CommandChain first = new CommandChain(new CommandChainId("c-1"), "第一链", A, Set.of(A, B));
    CommandChain second = new CommandChain(new CommandChainId("c-2"), "第二链", B, Set.of(A, B));

    assertThat(first.members()).containsExactlyInAnyOrder(A, B);
    assertThat(second.members()).containsExactlyInAnyOrder(A, B);
  }

  @Test
  void membersKeepInsertionOrderAndAreFrozen() {
    Set<UnitId> mutable = new LinkedHashSet<>();
    mutable.add(B);
    mutable.add(A);
    CommandChain chain = new CommandChain(new CommandChainId("c-1"), "链", B, mutable);
    mutable.add(new UnitId("u-c"));

    assertThat(chain.members()).containsExactly(B, A);
    assertThatThrownBy(() -> chain.members().add(new UnitId("u-d")))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
