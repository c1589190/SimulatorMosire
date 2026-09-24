package io.mosire.simos.util.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.state.ChangeSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link WorldTimeProposal} 的形状护栏（2026-09-24 日制裁定的多切片提案契约，POLITICAL_ECONOMY_DESIGN.md §9）：
 * 防御性拷贝、保序、构造期拒绝（空白身份 / 空模块表 / null 键值）、单切片包装。
 */
class WorldTimeProposalTest {

  /** test 侧玩具变更集（R1 的计数只算 main 源码——同 {@code TimeProposalResolverTest} 的先例）。 */
  private record ToyChangeSet(int v) implements ChangeSet {}

  @Test
  void copiesEveryComponentDefensively() {
    Map<String, ChangeSet> modules = new LinkedHashMap<>();
    modules.put("ledger", new ToyChangeSet(1));
    Set<String> reads = new LinkedHashSet<>(List.of("ledger:a1"));
    Set<String> writes = new LinkedHashSet<>(List.of("ledger:a1", "ledger:a2"));

    WorldTimeProposal proposal = new WorldTimeProposal("economy", modules, reads, writes);
    modules.put("market", new ToyChangeSet(2));
    reads.add("ledger:a9");
    writes.add("ledger:a3");

    assertThat(proposal.moduleChanges().keySet()).containsExactly("ledger");
    assertThat(proposal.reads()).containsExactly("ledger:a1");
    assertThat(proposal.writes()).containsExactly("ledger:a1", "ledger:a2");
    assertThatThrownBy(() -> proposal.moduleChanges().put("x", new ToyChangeSet(3)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> proposal.reads().add("y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * 保序：模块表的迭代序 = 传入序（不是散列槽位序）。键数用 5 个、乱序喂入——3 键在 {@code Map.copyOf} 下有 7%~40% 恰好落回插入序（M2 Task 5
   * 实测），键太少这条断言没有判别力。
   */
  @Test
  void preservesIterationOrderOfModuleChanges() {
    Map<String, ChangeSet> modules = new LinkedHashMap<>();
    for (String namespace : List.of("d:4", "b:2", "e:5", "a:1", "c:3")) {
      modules.put(namespace, new ToyChangeSet(0));
    }

    WorldTimeProposal proposal = new WorldTimeProposal("economy", modules, Set.of(), Set.of());

    assertThat(proposal.moduleChanges().keySet())
        .containsExactly("d:4", "b:2", "e:5", "a:1", "c:3");
  }

  @Test
  void blankParticipantIdIsRejected() {
    assertThatThrownBy(
            () ->
                new WorldTimeProposal(
                    "  ", Map.of("ledger", new ToyChangeSet(1)), Set.of(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("participantId");
  }

  /** ★ 空模块表 = 装配错误（"本日无事"应交不变变更集，而不是交空——否则参与者从事件里消失）。 */
  @Test
  void emptyModuleChangesIsRejected() {
    assertThatThrownBy(() -> new WorldTimeProposal("economy", Map.of(), Set.of(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("moduleChanges 不得为空");
  }

  @Test
  void nullModuleChangeKeyOrValueIsRejectedAtConstruction() {
    Map<String, ChangeSet> nullValue = new LinkedHashMap<>();
    nullValue.put("ledger", null);

    assertThatThrownBy(() -> new WorldTimeProposal("economy", nullValue, Set.of(), Set.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("moduleChanges 的值");
  }

  @Test
  void nullAddressInsideTheSetIsRejectedAtConstruction() {
    Set<String> withNull = new LinkedHashSet<>();
    withNull.add("ledger:a1");
    withNull.add(null);

    assertThatThrownBy(
            () ->
                new WorldTimeProposal(
                    "economy", Map.of("ledger", new ToyChangeSet(1)), withNull, Set.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("reads 的元素");
  }

  /** 单切片包装：模块键 = 提案自己的 namespace（{@link TimeParticipant#simulateWorld} 的默认实现走它）。 */
  @Test
  void singleWrapsATimeProposalUnderItsOwnNamespace() {
    TimeProposal single =
        new TimeProposal("unit", new ToyChangeSet(9), Set.of("unit:u-1"), Set.of());

    WorldTimeProposal wrapped = WorldTimeProposal.single(single);

    assertThat(wrapped.participantId()).isEqualTo("unit");
    assertThat(wrapped.moduleChanges().keySet()).containsExactly("unit");
    assertThat(wrapped.moduleChanges().get("unit")).isEqualTo(new ToyChangeSet(9));
    assertThat(wrapped.reads()).containsExactly("unit:u-1");
  }
}
