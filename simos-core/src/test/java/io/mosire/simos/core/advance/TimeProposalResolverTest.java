package io.mosire.simos.core.advance;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.ChangeSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ③ Resolve 的护栏（C14/C15，spec §5.3）——**纯函数**，不必搭 store/Timeline。
 *
 * <p>★★ **本用例有一条"专门为变异体 m1 而写"的场景**（见 {@link #onlyTheReverseReadWriteDirectionIsReported}）： m1 把
 * Resolve 改成"只查 {@code reads(A) ∩ writes(B)} 一个方向"。若用例里 A、B 互相读对方写的，那么**两个方向都命中**， 单方向实现照样报得出来 ⇒
 * **m1 恒绿、本用例零判别力**。故必须构造出"**只有反方向**命中"的那一幕。
 *
 * <p>★ 地址与 namespace 都用**乱序输入**。理由不是好看：{@code Set} 的迭代序不是键集的纯函数（M2 Task 5 实测 30 次），
 * 若喂进去的恰好是排好的，那么"忘了排序"这个错**可能**碰巧不显形。≥4 个键 + 打乱顺序，是为了让那条断言真的有判别力。
 */
class TimeProposalResolverTest {

  /** 玩具变更集（test 侧，R1 的计数只算 main 源码）。 */
  private record ToyChangeSet(int v) implements ChangeSet {}

  /** 保序的地址集：**故意乱序**，好让"排序"这一步有可观测的差别。 */
  private static Set<String> addresses(String... values) {
    return new LinkedHashSet<>(List.of(values));
  }

  /** 单切片参与者的同形包装（模块键 = 它自己的身份名）——旧用例的构造点全部走这里。 */
  private static WorldTimeProposal proposal(
      String namespace, Set<String> reads, Set<String> writes) {
    return new WorldTimeProposal(namespace, Map.of(namespace, new ToyChangeSet(1)), reads, writes);
  }

  /** 多切片参与者：一次带多个模块的变更集（1b-1 的新契约）。 */
  private static WorldTimeProposal multi(
      String participantId,
      Map<String, ChangeSet> moduleChanges,
      Set<String> reads,
      Set<String> writes) {
    return new WorldTimeProposal(participantId, moduleChanges, reads, writes);
  }

  // ── 写-写：拒绝（R9 的判定来源）─────────────────────────────────────────────────────

  @Test
  void writeWriteIntersectionIsBlocked() {
    WorldTimeProposal left = proposal("alpha", Set.of(), addresses("alpha:x1", "shared:x9"));
    WorldTimeProposal right = proposal("beta", Set.of(), addresses("beta:y1", "shared:x9"));

    TimeProposalResolver.Outcome outcome = TimeProposalResolver.resolve(List.of(left, right));

    assertThat(outcome).isInstanceOf(TimeProposalResolver.Outcome.Blocked.class);
    AdvanceConflict conflict = ((TimeProposalResolver.Outcome.Blocked) outcome).conflict();
    assertThat(conflict.kind()).isEqualTo(AdvanceConflict.WRITE_WRITE);
    assertThat(conflict.namespaces()).containsExactly("alpha", "beta");
    assertThat(conflict.addresses()).containsExactly("shared:x9");
  }

  @Test
  void disjointWritesAreNotBlocked() {
    TimeProposalResolver.Outcome outcome =
        TimeProposalResolver.resolve(
            List.of(
                proposal("alpha", Set.of(), addresses("alpha:x1")),
                proposal("beta", Set.of(), addresses("beta:y1"))));

    assertThat(outcome).isInstanceOf(TimeProposalResolver.Outcome.Resolved.class);
    assertThat(((TimeProposalResolver.Outcome.Resolved) outcome).warnings()).isEmpty();
  }

  // ── 读-写：只留痕，不拒绝（R10）─────────────────────────────────────────────────────

  /**
   * ★★ **m1 的专属场景**：**只有反方向命中**。
   *
   * <p>构造：{@code beta} 写 {@code beta:x1}，{@code alpha} 读 {@code beta:x1}。于是
   *
   * <ul>
   *   <li>{@code reads(alpha) ∩ writes(beta)} = <b>{@code {beta:x1}} ← 命中的是这个</b>
   *   <li>{@code reads(beta) ∩ writes(alpha)} = ∅（beta 什么都不读）
   * </ul>
   *
   * <p>若实现只查 {@code reads(A) ∩ writes(B)}（A=alpha 在前,B=beta 在后）则**恰好命中**——为彻底堵死这个巧合，
   * 本例把**读方放在列表后面**（{@code [writer, reader]}）：那个"只查前一个读后一个写的"天真实现会在 i=0,j=1 上 查 {@code reads(beta)
   * ∩ writes(alpha)} = ∅ ⇒ 报不出任何东西 ⇒ 本用例红。**这正是 m1 要打的位置。**
   */
  @Test
  void onlyTheReverseReadWriteDirectionIsReported() {
    WorldTimeProposal writer = proposal("beta", Set.of(), addresses("beta:x1"));
    WorldTimeProposal reader = proposal("alpha", addresses("beta:x1"), Set.of());

    TimeProposalResolver.Outcome outcome = TimeProposalResolver.resolve(List.of(writer, reader));

    assertThat(outcome).isInstanceOf(TimeProposalResolver.Outcome.Resolved.class);
    List<AdvanceConflict> warnings = ((TimeProposalResolver.Outcome.Resolved) outcome).warnings();
    assertThat(warnings).as("读方在前、写方在后的**有向**对：reads(alpha) ∩ writes(beta) 非空 ⇒ 必须留痕").hasSize(1);
    assertThat(warnings.get(0).kind()).isEqualTo(AdvanceConflict.READ_WRITE);
    assertThat(warnings.get(0).namespaces())
        .as("有向：namespaces = [读方, 写方]，即 [alpha, beta]（此处恰好也是字典序）")
        .containsExactly("alpha", "beta");
    assertThat(warnings.get(0).addresses()).containsExactly("beta:x1");
  }

  /**
   * 两个方向是**两条独立的风险**：互相读对方写的 ⇒ 留下**两条**（不是一条，也不是四条）。
   *
   * <p>★★ **本用例是 Task 12 抓到真缺陷的那一条**：{@code AdvanceConflict} 的构造器一度对 {@code namespaces}
   * 也做字典序（"顺手"），于是 {@code [alpha,beta]} 与 {@code [beta,alpha]} 落成**同一个值**——两条风险退化成
   * 两条逐字节相同的事件，读事件的人分不出方向。本断言当场红（1 failure / 8），缺陷随即被修掉。 ⇒ **它现在同时守着两件事**：两个方向都要查（m1），且方向必须活着。
   */
  @Test
  void bothReadWriteDirectionsAreReportedAsTwoWarnings() {
    WorldTimeProposal alpha = proposal("alpha", addresses("beta:y"), addresses("alpha:x"));
    WorldTimeProposal beta = proposal("beta", addresses("alpha:x"), addresses("beta:y"));

    List<AdvanceConflict> warnings =
        ((TimeProposalResolver.Outcome.Resolved) TimeProposalResolver.resolve(List.of(alpha, beta)))
            .warnings();

    assertThat(warnings).hasSize(2);
    assertThat(warnings.stream().map(AdvanceConflict::namespaces).toList())
        .as("★ 有向对，两条都要在（只查一个方向的实现只会报一条）")
        .containsExactlyInAnyOrder(List.of("alpha", "beta"), List.of("beta", "alpha"));
  }

  /** ★ **自交不算冲突**：同一参与者先读后写自己那块，是它自己的事，不该留下任何警告。 */
  @Test
  void aParticipantsOwnReadsAndWritesDoNotConflictWithItself() {
    WorldTimeProposal solo = proposal("alpha", addresses("alpha:x"), addresses("alpha:x"));

    TimeProposalResolver.Outcome outcome = TimeProposalResolver.resolve(List.of(solo));

    assertThat(outcome).isInstanceOf(TimeProposalResolver.Outcome.Resolved.class);
    assertThat(((TimeProposalResolver.Outcome.Resolved) outcome).warnings())
        .as("单参与者：它自己读自己写不构成跨模块风险")
        .isEmpty();
  }

  // ── 地址的字典序（C15 / R10 的序断言；m4 打的就是这里）───────────────────────────────

  /**
   * ★ **地址列表按字典序**（C15）。喂进去 4 个**乱序**的地址，落出来的必须是排好的。
   *
   * <p>★ 为什么是 4 个而不是 2~3 个：M2 实测 3 键的散列序有 7%~40% 恰好落回插入序——键太少， "忘了排序"这个错可能碰巧不显形。
   *
   * <p>★★ **同时钉住"namespaces 不排序"**：读方是 {@code zulu}、写方是 {@code alpha}，落出来必须是 {@code [zulu,
   * alpha]}——**读方在前，尽管字典序是反的**。若哪天有人"顺手"把 namespaces 也排了 （Task 12 真发生过，见 {@link AdvanceConflict}
   * 类注释），这条断言当场红。
   */
  @Test
  void conflictAddressesAreSortedLexicographicallyEvenWhenGivenOutOfOrder() {
    WorldTimeProposal reader =
        proposal("zulu", addresses("shared:x3", "shared:x1", "shared:x4", "shared:x2"), Set.of());
    WorldTimeProposal writer =
        proposal("alpha", Set.of(), addresses("shared:x4", "shared:x2", "shared:x3", "shared:x1"));

    AdvanceConflict warning =
        ((TimeProposalResolver.Outcome.Resolved)
                TimeProposalResolver.resolve(List.of(reader, writer)))
            .warnings()
            .get(0);

    assertThat(warning.addresses())
        .as("★ 输入序是 x3,x1,x4,x2 ⇒ 输出必须是 x1,x2,x3,x4（差一位就红）")
        .containsExactly("shared:x1", "shared:x2", "shared:x3", "shared:x4");
    assertThat(warning.namespaces())
        .as("★ 有向：读方 zulu 在前。**不是**字典序——排了就把方向抹掉了")
        .containsExactly("zulu", "alpha");
  }

  // ── 汇总与决定论 ───────────────────────────────────────────────────────────────────

  @Test
  void mergedChangeSetIsSortedByNamespaceRegardlessOfInputOrder() {
    WorldTimeProposal alpha = proposal("alpha", Set.of(), Set.of());
    WorldTimeProposal beta = proposal("beta", Set.of(), Set.of());

    WorldChangeSet forward =
        ((TimeProposalResolver.Outcome.Resolved) TimeProposalResolver.resolve(List.of(alpha, beta)))
            .changeSet();
    WorldChangeSet reversed =
        ((TimeProposalResolver.Outcome.Resolved) TimeProposalResolver.resolve(List.of(beta, alpha)))
            .changeSet();

    assertThat(forward.modules().keySet())
        .as("汇总按 namespace 字典序（输出只是**内容**的函数）")
        .containsExactly("alpha", "beta");
    assertThat(reversed.modules().keySet())
        .as("★ 入参逆序 ⇒ 落盘序相同（TreeMap 的兑现；旧实现'保入参序'会让两个字节不同的 changeset_json）")
        .containsExactly("alpha", "beta");
    assertThat(forward.modules().get("alpha")).isEqualTo(new ToyChangeSet(1));
  }

  /** ★ **多切片参与者**（1b-1 的新契约）：一个提案带多个模块的变更集 ⇒ 逐模块进汇总（按字典序）。 */
  @Test
  void multiModuleProposalContributesEveryModuleToTheMergedChangeSet() {
    WorldTimeProposal economy =
        multi(
            "economy",
            Map.of("ledger", new ToyChangeSet(7), "production", new ToyChangeSet(8)),
            Set.of(),
            Set.of());

    WorldChangeSet changeSet =
        ((TimeProposalResolver.Outcome.Resolved) TimeProposalResolver.resolve(List.of(economy)))
            .changeSet();

    assertThat(changeSet.modules().keySet()).containsExactly("ledger", "production");
    assertThat(changeSet.modules().get("ledger")).isEqualTo(new ToyChangeSet(7));
    assertThat(changeSet.modules().get("production")).isEqualTo(new ToyChangeSet(8));
  }

  /**
   * ★★ **两个参与者改同一模块 ⇒ 拒绝整次推进，哪怕地址不相交**：Core 手里的 {@code ChangeSet} 是**不透明**的
   * （ADR-1/C26），没有能力把两份变更集合并成一个（见 {@link TimeProposalResolver} 类注释第 1 条）。 报告里用 {@value
   * TimeProposalResolver#MODULE_CLASH_PREFIX} 前缀的**合成地址**标记这类冲突。
   */
  @Test
  void twoParticipantsTouchingTheSameModuleAreBlockedEvenWithDisjointAddresses() {
    WorldTimeProposal left =
        multi("economy", Map.of("ledger", new ToyChangeSet(1)), Set.of(), addresses("ledger:a1"));
    WorldTimeProposal right =
        multi("banking", Map.of("ledger", new ToyChangeSet(2)), Set.of(), addresses("ledger:b1"));

    TimeProposalResolver.Outcome outcome = TimeProposalResolver.resolve(List.of(left, right));

    assertThat(outcome).isInstanceOf(TimeProposalResolver.Outcome.Blocked.class);
    AdvanceConflict conflict = ((TimeProposalResolver.Outcome.Blocked) outcome).conflict();
    assertThat(conflict.kind()).isEqualTo(AdvanceConflict.WRITE_WRITE);
    assertThat(conflict.namespaces()).containsExactly("economy", "banking");
    assertThat(conflict.addresses())
        .as("写地址不相交，但模块名相同 ⇒ 用 module: 前缀的合成地址标记")
        .containsExactly(TimeProposalResolver.MODULE_CLASH_PREFIX + "ledger");
  }

  /** ★ 多切片参与者的读-写风险按**参与者**整体比对（读方/写方身份 = participantId）。 */
  @Test
  void multiModuleProposalParticipatesInReadWriteWarningsWithItsParticipantId() {
    WorldTimeProposal owner =
        multi("owner", Map.of("property", new ToyChangeSet(1)), Set.of(), addresses("property:p1"));
    WorldTimeProposal observer =
        multi(
            "observer", Map.of("market", new ToyChangeSet(1)), addresses("property:p1"), Set.of());

    List<AdvanceConflict> warnings =
        ((TimeProposalResolver.Outcome.Resolved)
                TimeProposalResolver.resolve(List.of(owner, observer)))
            .warnings();

    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0).namespaces())
        .as("有向：[读方 observer, 写方 owner]")
        .containsExactly("observer", "owner");
    assertThat(warnings.get(0).addresses()).containsExactly("property:p1");
  }

  /**
   * ★ **输出是输入的纯函数**（决定论）：同一批提案换个顺序喂进来，{@code warnings} 必须**逐条相同**。
   *
   * <p>为什么这条重要：{@code warnings} 的顺序会原样变成事件行的顺序（{@code seq} 自增）⇒ 若它随入参顺序变，
   * 同样的世界状态、同一批参与者，两次推进会落出**不同的事件序列**——那让"重放/对拍"这类手段全部失效。
   */
  @Test
  void warningsOrderIsAFunctionOfContentNotOfInputOrder() {
    WorldTimeProposal alpha = proposal("alpha", addresses("beta:y"), addresses("alpha:x"));
    WorldTimeProposal beta = proposal("beta", addresses("alpha:x"), addresses("beta:y"));
    WorldTimeProposal gamma = proposal("gamma", addresses("alpha:x"), Set.of());

    List<AdvanceConflict> forward =
        ((TimeProposalResolver.Outcome.Resolved)
                TimeProposalResolver.resolve(List.of(alpha, beta, gamma)))
            .warnings();
    List<AdvanceConflict> reversed =
        ((TimeProposalResolver.Outcome.Resolved)
                TimeProposalResolver.resolve(List.of(gamma, beta, alpha)))
            .warnings();

    assertThat(forward).as("同一批内容换个顺序喂 ⇒ 逐条相同").isEqualTo(reversed);
    assertThat(forward).isNotEmpty();
  }
}
