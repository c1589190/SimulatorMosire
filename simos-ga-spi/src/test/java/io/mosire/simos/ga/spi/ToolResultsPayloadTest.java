package io.mosire.simos.ga.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>判据 A + C：{@code ToolResults} 的对外载荷<b>逐字节</b>不变（I-G1）与三结局折叠的拒绝码</b>。
 *
 * <h2>它守的是哪种坏法</h2>
 *
 * <p>工具结果的 JSON 是<b>对外载荷</b>（模型与 MCP 面读它）。本类的类注写着：「字段名与顺序<b>逐字照抄</b> app 侧原实现 （{@code
 * result}/{@code ref}/{@code current}/{@code reason}/{@code commandId}/{@code correlationId}）……
 * 本次搬迁不允许改一个字节（I-G1）」。<b>改一个字节不会有任何报错</b>：字段改名 ⇒ 下游读不到（静默 null）； 顺序变了 ⇒ 逐字节比对与 e2e 基线漂移；拒绝码变了 ⇒
 * 调用方的分支永远走不到。
 *
 * <p>★ 所以本类<b>不用</b> {@code containsKey} 之类的弱断言，而是把整条 JSON 串<b>逐字</b>钉住（含字段顺序）。 ★ 第二条判据是
 * fail-closed：{@code fold(null)} 必须抛（不许折成一条"成功"），{@code json()} 序列化失败必须抛 （不许发出半个载荷）。
 */
class ToolResultsPayloadTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(7));
  private static final StateRef HEAD_9 = new StateRef(new BranchId("main"), new RevisionId(9));

  /**
   * ★★ <b>提交成功的载荷：字段名、字段顺序、坐标的序列化逐字节钉死</b>。
   *
   * <p>★ 为什么连<b>顺序</b>都钉：{@code LinkedHashMap} 的插入序就是输出序，而"顺序"是 e2e 基线/逐字节比对的输入
   * ——它是**规格**的一部分（子类会把这份视图当构造块嵌进自己的结果）。
   */
  @Test
  void theCommittedPayloadKeepsTheExactFieldNamesAndOrder() {
    Map<String, Object> view = ToolResults.committedView(REF, "cmd-1", "corr-1");

    assertThat(view.keySet())
        .as("★★ 判据 A：提交视图的键序 = result, ref, commandId, correlationId（照抄 app 侧原实现）")
        .containsExactly("result", "ref", "commandId", "correlationId");
    assertThat(ToolResults.json(view))
        .as("★★ 判据 A：对外载荷逐字节不变（I-G1）")
        .isEqualTo(
            "{\"result\":\"committed\",\"ref\":{\"branch\":\"main\",\"revision\":7},"
                + "\"commandId\":\"cmd-1\",\"correlationId\":\"corr-1\"}");
  }

  /**
   * ★★ <b>冲突 / 被拒的载荷：字段名与拒绝码逐字节钉死</b>（{@code Conflict → CONFLICT}、{@code Rejected → REJECTED}， 与
   * app 侧 {@code ToolSupport.fold} 逐字相同）。
   */
  @Test
  void theConflictAndRejectedPayloadsKeepTheirExactBytesAndCodes() {
    ToolResult conflict =
        ToolResults.fold(new ToolWritePort.Outcome.Conflict(HEAD_9), "cmd-2", "corr-2");
    assertThat(conflict.success()).as("冲突不是成功").isFalse();
    assertThat(conflict.code()).as("★★ 判据 A：拒绝码逐字 = CONFLICT").isEqualTo("CONFLICT");
    assertThat(conflict.message())
        .isEqualTo(
            "{\"result\":\"conflict\",\"current\":{\"branch\":\"main\",\"revision\":9},"
                + "\"commandId\":\"cmd-2\",\"correlationId\":\"corr-2\"}");

    ToolResult rejected =
        ToolResults.fold(new ToolWritePort.Outcome.Rejected("世界拒绝：区域不存在"), "cmd-3", "corr-3");
    assertThat(rejected.success()).isFalse();
    assertThat(rejected.code()).as("★★ 判据 A：拒绝码逐字 = REJECTED").isEqualTo("REJECTED");
    assertThat(rejected.message())
        .as("★ 拒因原样进载荷（中文不转义、不加引号处理）")
        .isEqualTo(
            "{\"result\":\"rejected\",\"reason\":\"世界拒绝：区域不存在\","
                + "\"commandId\":\"cmd-3\",\"correlationId\":\"corr-3\"}");
  }

  /** ★ <b>{@code Committed} 走 {@code ok}：成功结果的 code 为 null（{@code ToolResult} 的成功口径）</b>。 */
  @Test
  void theCommittedOutcomeFoldsToASuccessResultWithTheNewCoordinates() {
    ToolResult ok = ToolResults.fold(new ToolWritePort.Outcome.Committed(REF), "cmd-1", "corr-1");

    assertThat(ok.success()).as("★ Committed ⇒ 成功").isTrue();
    assertThat(ok.code()).as("★ 成功的口径是 code == null").isNull();
    assertThat(ok.message())
        .as("★ 成功载荷 = committedView 的 JSON（与上面那条逐字节同源）")
        .isEqualTo(ToolResults.json(ToolResults.committedView(REF, "cmd-1", "corr-1")));
  }

  /**
   * ★★ <b>判据 B：{@code fold(null)} 当场抛，不许折成一条"成功"</b>。
   *
   * <p>守的坏法：{@code switch} 之前少一次空判 ⇒ NPE 在 switch 里炸（还好），但若哪天被"兜底成 ok"⇒ <b>一次没有结局的
   * 提交会报成功</b>：调用方以为世界改了，实际什么都没发生。
   */
  @Test
  void foldRefusesANullOutcomeInsteadOfReturningAnEmptySuccess() {
    assertThatThrownBy(() -> ToolResults.fold(null, "cmd-x", "corr-x"))
        .as("★★ 判据 B：没有结局 ⇒ 抛（不许折成一条成功）")
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("outcome");
  }

  /**
   * ★★ <b>判据 B：序列化失败 ⇒ 抛 {@link IllegalStateException}，不许发出半个载荷</b>。
   *
   * <p>守的坏法：把 {@code JsonProcessingException} 吞掉（返回 null / 返回空串 / 返回 {@code "{}"}）⇒ 调用方拿到一条
   * <b>语法上合法、语义上是空的</b>结果，而没有任何一层报错。
   */
  @Test
  void jsonRefusesToEmitAHalfPayloadWhenSerializationFails() {
    assertThatThrownBy(() -> ToolResults.json(new UnserializableView()))
        .as("★★ 判据 B：视图序列化不了 ⇒ 抛（不许静默发半个载荷）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JSON 序列化失败");
  }

  /** 取不出值的视图（Jackson 内省它的 getter 时抛）——"这条视图序列化不了"的最小造法。 */
  private static final class UnserializableView {

    @SuppressWarnings("unused")
    public String getBroken() {
      throw new IllegalStateException("视图坏了");
    }
  }
}
