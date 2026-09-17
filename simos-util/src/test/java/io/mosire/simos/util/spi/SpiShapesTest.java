package io.mosire.simos.util.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.state.ChangeSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** spec §八 / §5.2：spi 值类型与契约接口的形状护栏（sealed、防御性拷贝、保序、构造期 null 拒绝）。 */
class SpiShapesTest {

  /** 形状护栏：`HandlerOutcome` 封闭且**恰两个**变体。多一个就说明有人在 Core 里塞了新形态。 */
  @Test
  void handlerOutcomeIsSealedWithExactlyTwoVariants() {
    assertThat(HandlerOutcome.class.isSealed()).isTrue();
    assertThat(HandlerOutcome.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(HandlerOutcome.Applied.class, HandlerOutcome.Rejected.class);
  }

  /** 防御性拷贝：改传入的集合不得影响已构造的提案。 */
  @Test
  void timeProposalCopiesItsSetsDefensively() {
    Set<String> reads = new LinkedHashSet<>(List.of("unit:u-1"));
    TimeProposal proposal =
        new TimeProposal("unit", emptyChangeSet(), reads, new LinkedHashSet<>());
    reads.add("unit:u-2");
    assertThat(proposal.reads()).containsExactly("unit:u-1");
    assertThatThrownBy(() -> proposal.reads().add("unit:u-3"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * 保序：`reads` 的迭代序 = 传入序（不是散列槽位序）。
   *
   * <p>★ 键数用 **5** 个（乱序喂入）：M2 Task 5 实测 3 键在 {@code Set.copyOf} 下有 7%~40% 恰好落回插入序
   * （两键撞槽时线性探测的相对次序随插入序），4~6 键 0/30——键太少这条用例对 m2 变异没有判别力。
   */
  @Test
  void timeProposalPreservesIterationOrder() {
    TimeProposal proposal =
        new TimeProposal(
            "unit",
            emptyChangeSet(),
            new LinkedHashSet<>(List.of("d:4", "b:2", "e:5", "a:1", "c:3")),
            new LinkedHashSet<>());
    assertThat(proposal.reads()).containsExactly("d:4", "b:2", "e:5", "a:1", "c:3");
  }

  /** 集合里的 null 在**构造期**就炸，不留到 Resolve 的 `retainAll` 才炸；失败消息被 hasMessage 精确钉住。 */
  @Test
  void nullAddressInsideTheSetIsRejectedAtConstruction() {
    Set<String> withNull = new LinkedHashSet<>();
    withNull.add("unit:u-1");
    withNull.add(null);
    assertThatThrownBy(() -> new TimeProposal("unit", emptyChangeSet(), withNull, Set.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("reads 的元素");
  }

  /**
   * ★ **必须是匿名类，不能是 lambda**：M4 之后 `ChangeSet` 是标记接口（0 个抽象方法），**不再是函数式接口**—— 写 `() -> …` 编不过（这正是
   * Task 1 那个编译破坏的同源形态，计划 Task 2 Step 7 已标出）。
   */
  private static ChangeSet emptyChangeSet() {
    return new ChangeSet() {};
  }
}
