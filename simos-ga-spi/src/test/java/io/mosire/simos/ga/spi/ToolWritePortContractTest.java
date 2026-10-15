package io.mosire.simos.ga.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.Command;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>判据 B + C：{@code ToolWritePort} 的<b>记录型 fail-closed</b> 与<b>投影的唯一拼写点</b></b>。
 *
 * <h2>它守的是哪种坏法</h2>
 *
 * <ol>
 *   <li><b>记录型把坏数据放进来</b>：{@code Request} / {@code Outcome} / {@code BatchOutcome} / {@code
 *       BatchReceipt} 是 <b>core 三种结局的镜像</b>（本接口类注：语义逐条同形，字段一个不差）。若 {@code type} 允许空串、 {@code
 *       outcome} 允许 null，则坏数据会一路漂到工具结果里（对外载荷），而判定点已经过去了——没有第二处会拦；
 *   <li><b>批结局的「逐位对应」不变式</b>：{@code BatchOutcome.outcomes()} 必须与入参批次<b>逐位对应、条数相同</b>。
 *       若构造器不复制列表（或复制了但不冻），调用方随后改自己那份 ⇒ 工具报出的第 N 条结局<b>不是</b>第 N 条命令的；
 *   <li><b>投影走岔</b>：{@code submit} / {@code submitBatch} 是 {@code …WithReceipt} 的 <b>default
 *       投影</b>（类注： 「只要结局、不要 id 的形态」）。若哪天有人把它们<b>各自实现一遍</b>，两条路会给出不同的结局/id，而<b>没有任何一层报错</b> ——{@code
 *       submitBatch} 的 3 参重载还必须为每次批提交生成<b>一个新的</b>调用链 id（工具拿它当幂等键 {@code orderId} 的一段，复用同一个 id
 *       会让两次不同的批撞成同一条链）。
 * </ol>
 */
class ToolWritePortContractTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(7));

  // ────────────────────────── ① 记录型 fail-closed ──────────────────────────

  /** ★★ <b>批中的一条命令：{@code type} 不得为 null / 空串（空白也不行），{@code payloadJson} 不得为 null</b>。 */
  @Test
  void requestRefusesNullsAndABlankType() {
    assertThatThrownBy(() -> new ToolWritePort.Request(null, "{}"))
        .as("★★ 判据 B：type 为 null ⇒ 抛")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ToolWritePort.Request("", "{}"))
        .as("★★ 判据 B：type 为空串 ⇒ 抛（空类型名会落成一条没有 handler 的信封）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("type");
    assertThatThrownBy(() -> new ToolWritePort.Request("unit.RenameUnit", null))
        .as("★★ 判据 B：payloadJson 为 null ⇒ 抛（载荷是不透明文本，null 不是「空载荷」）")
        .isInstanceOf(NullPointerException.class);

    assertThat(new ToolWritePort.Request("unit.RenameUnit", "").payloadJson())
        .as("对照：空串载荷是合法取值（C26：逐字节转交，不 parse、不 trim）")
        .isEmpty();
  }

  /**
   * ★★ <b>三种结局的载荷都不得为 null</b>（{@code Committed.ref} / {@code Conflict.current} / {@code
   * Rejected.reason}）。
   */
  @Test
  void outcomesRefuseNullPayloads() {
    assertThatThrownBy(() -> new ToolWritePort.Outcome.Committed(null))
        .as("★★ 判据 B：Committed 没有坐标 ⇒ 抛（不许报一条「成功但不知道落在哪」的结局）")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ToolWritePort.Outcome.Conflict(null))
        .as("★★ 判据 B：Conflict 没有真实 head ⇒ 抛（调用方靠它重试/分岔）")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ToolWritePort.Outcome.Rejected(null))
        .as("★★ 判据 B：Rejected 没有理由 ⇒ 抛（拒因是给人与事件看的文本）")
        .isInstanceOf(NullPointerException.class);

    assertThat(new ToolWritePort.Outcome.Committed(REF).ref()).isSameAs(REF);
  }

  /** ★★ <b>批结局与批回执不得为 null 分量</b>。 */
  @Test
  void batchOutcomesAndReceiptsRefuseNullComponents() {
    assertThatThrownBy(() -> new ToolWritePort.BatchOutcome.Rejected(null))
        .as("判据 B：批结局的逐条清单不得为 null")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ToolWritePort.BatchOutcome.Committed(null, List.of()))
        .as("判据 B：整批生效却没有新 revision 坐标 ⇒ 抛")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ToolWritePort.BatchOutcome.Committed(REF, null))
        .as("判据 B：整批生效却没有逐条结局 ⇒ 抛")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ToolWritePort.BatchOutcome.Conflict(REF, null))
        .as("判据 B：批冲突的逐条清单不得为 null")
        .isInstanceOf(NullPointerException.class);

    ToolWritePort.BatchOutcome noEntries = new ToolWritePort.BatchOutcome.Rejected(List.of());
    assertThatThrownBy(() -> new ToolWritePort.BatchReceipt(null, List.of("c1"), noEntries))
        .as("判据 B：批回执的 correlationId 不得为 null（批内所有信封共用它）")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ToolWritePort.BatchReceipt("corr", null, noEntries))
        .as("判据 B：批回执的逐条 commandId 不得为 null")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ToolWritePort.BatchReceipt("corr", List.of("c1"), null))
        .as("判据 B：批回执的结局不得为 null")
        .isInstanceOf(NullPointerException.class);
  }

  // ────────────────────────── ② 保序 + 不可变（「逐位对应」不变式） ──────────────────────────

  /**
   * ★★ <b>逐条结局清单：构造期<b>复制</b>（调用方事后改自己那份不影响结局）+ 只读 + <b>保住顺序</b></b>。
   *
   * <p>守的坏法：不复制 ⇒ 工具报出的第 N 条结局可能不是第 N 条命令的（批的语义就是"逐位对应"）；不冻 ⇒ 下游谁都能往"已经发生的结局"里塞一条。★
   * 顺序判据用三条<b>可区分</b>的拒因（r1/r2/r3）钉住，不用等值对象。
   */
  @Test
  void batchOutcomeCopiesFreezesAndKeepsTheOrderOfItsEntries() {
    List<ToolWritePort.Outcome> mutable = new ArrayList<>();
    mutable.add(new ToolWritePort.Outcome.Rejected("r1"));
    mutable.add(new ToolWritePort.Outcome.Conflict(REF));
    mutable.add(new ToolWritePort.Outcome.Rejected("r3"));

    ToolWritePort.BatchOutcome.Rejected batch = new ToolWritePort.BatchOutcome.Rejected(mutable);
    mutable.clear();

    assertThat(batch.outcomes())
        .as("★★ 判据 B：结局清单必须与入参批次逐位对应（构造期复制——调用方 clear 之后仍有 3 条）")
        .hasSize(3);
    assertThat(batch.outcomes())
        .extracting(ToolWritePortContractTest::tagOf)
        .as("★★ 顺序即语义：第 N 条结局就是第 N 条命令的结局")
        .containsExactly("rejected:r1", "conflict", "rejected:r3");
    assertThatThrownBy(() -> batch.outcomes().add(new ToolWritePort.Outcome.Rejected("r4")))
        .as("★★ 判据 B：已经发生的结局清单必须真不可变（不是「约定不可变」）")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> batch.outcomes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★★ <b>批回执：{@code commandIds} 保序（逐位对应）+ 复制 + 只读</b>。 */
  @Test
  void batchReceiptCopiesFreezesAndKeepsTheOrderOfItsCommandIds() {
    List<String> mutableIds = new ArrayList<>(List.of("c-1", "c-2", "c-3"));
    ToolWritePort.BatchReceipt receipt =
        new ToolWritePort.BatchReceipt(
            "corr-1", mutableIds, new ToolWritePort.BatchOutcome.Rejected(List.of()));
    mutableIds.clear();

    assertThat(receipt.correlationId()).isEqualTo("corr-1");
    assertThat(receipt.commandIds())
        .as("★ 判据 B：commandIds 与入参批次逐位对应、长度相同（构造期复制）")
        .containsExactly("c-1", "c-2", "c-3");
    assertThatThrownBy(() -> receipt.commandIds().add("c-4"))
        .as("★ 判据 B：报出去的 id 清单必须真不可变")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ────────────────────────── ③ 投影的唯一拼写点 ──────────────────────────

  /**
   * ★★ <b>{@code submit(...)} 必须<b>走</b> {@code submitWithReceipt(...)}——投影不许各写一遍</b>。
   *
   * <p>守的坏法：两条路各自实现 ⇒ 报出去的命令 id 与落盘信封里的 id 不是同一个（工具结果的 {@code commandId} / {@code correlationId}
   * 是对外载荷，{@code ToolResults} 只把它们当构造块嵌进去），而<b>没有任何一层报错</b>。
   */
  @Test
  void submitIsAProjectionOfTheReceiptReturningForm() {
    RecordingPort port = new RecordingPort();

    ToolWritePort.Outcome outcome =
        port.submit(new BranchId("main"), () -> new RevisionId(3), "unit.RenameUnit", "{}");

    assertThat(port.submitWithReceiptCalls)
        .as("★★ 判据 C：投影必须**真的**走 submitWithReceipt（各写一遍 = 两处 id 会对不上）")
        .isEqualTo(1);
    assertThat(outcome)
        .as("★★ 投影返回的就是回执里的那一个结局（同实例——不是复制品、不是另算一遍）")
        .isSameAs(port.receipt.outcome());
    assertThat(port.lastType).isEqualTo("unit.RenameUnit");
    assertThat(port.lastPayloadJson).isEqualTo("{}");
  }

  /**
   * ★★ <b>{@code submitBatch(...)} 走 3 参重载 → 4 参重载，且每次批提交都拿到<b>新的、非空的</b>调用链 id</b>。
   *
   * <p>守的坏法：3 参重载若把 {@code correlationId} 生成成 null（或复用同一个常量），工具写进载荷的幂等键 {@code orderId =
   * "<kind>:<batchId>:<unitId>"} 就会缺失或让两次不同的批撞成同一条链——<b>重放/重试时静默地对上，第二次批被吞掉</b>。
   */
  @Test
  void submitBatchIsAProjectionThatAlwaysCarriesAFreshChainId() {
    RecordingPort port = new RecordingPort();
    List<ToolWritePort.Request> batch = List.of(new ToolWritePort.Request("gov.Recruit", "{}"));

    ToolWritePort.BatchOutcome first =
        port.submitBatch(new BranchId("main"), new RevisionId(4), batch);
    ToolWritePort.BatchOutcome second =
        port.submitBatch(new BranchId("main"), new RevisionId(4), batch);

    assertThat(port.batchCalls).as("★★ 判据 C：投影必须真的走 4 参的 submitBatchWithReceipt").isEqualTo(2);
    assertThat(port.correlationIds)
        .as("★★ 每次批提交都必须有非空的调用链 id（它是工具载荷里幂等键的一段）")
        .hasSize(2)
        .allSatisfy(id -> assertThat(id).isNotBlank());
    assertThat(port.correlationIds.get(0))
        .as("★★ 两次批提交的链 id 必须不同（复用同一个 = 两次不同的批撞成同一条链）")
        .isNotEqualTo(port.correlationIds.get(1));
    assertThat(first).isSameAs(port.lastBatchOutcome);
    assertThat(second).isSameAs(port.lastBatchOutcome);
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 每条结局的可区分标签（三条各不相同 ⇒ 顺序断言才有判别力）。 */
  private static String tagOf(ToolWritePort.Outcome outcome) {
    return switch (outcome) {
      case ToolWritePort.Outcome.Rejected rejected -> "rejected:" + rejected.reason();
      case ToolWritePort.Outcome.Conflict conflict -> "conflict";
      case ToolWritePort.Outcome.Committed committed -> "committed";
    };
  }

  /**
   * 一个只实现<b>抽象</b>方法的窄写口：两个 {@code …WithReceipt} 记调用，两个投影<b>一个都不覆写</b> ——这样上面那两条用例证明的才是"接口的 default
   * 投影"，不是夹具的转发。
   */
  private static final class RecordingPort implements ToolWritePort {

    private final ToolWritePort.Receipt receipt =
        new ToolWritePort.Receipt("cmd-1", new ToolWritePort.Outcome.Committed(REF));
    private final ToolWritePort.BatchOutcome lastBatchOutcome =
        new ToolWritePort.BatchOutcome.Rejected(List.of(new ToolWritePort.Outcome.Rejected("夹具")));
    private final List<String> correlationIds = new ArrayList<>();
    private int submitWithReceiptCalls;
    private int batchCalls;
    private String lastType;
    private String lastPayloadJson;

    @Override
    public ToolWritePort.Receipt submitWithReceipt(
        BranchId branch, Command command, String type, String payloadJson) {
      submitWithReceiptCalls++;
      lastType = type;
      lastPayloadJson = payloadJson;
      return receipt;
    }

    @Override
    public ToolWritePort.BatchReceipt submitBatchWithReceipt(
        BranchId branch, RevisionId expectedRevision, List<Request> batch, String correlationId) {
      batchCalls++;
      correlationIds.add(correlationId);
      return new ToolWritePort.BatchReceipt(correlationId, List.of("cmd-1"), lastBatchOutcome);
    }

    @Override
    public Optional<RevisionId> head(BranchId branch) {
      return Optional.empty();
    }

    @Override
    public SimulationState replay(StateRef ref) {
      throw new UnsupportedOperationException("夹具：不重放");
    }
  }
}
