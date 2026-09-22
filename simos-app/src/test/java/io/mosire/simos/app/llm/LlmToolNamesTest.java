package io.mosire.simos.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.ToolDef;
import io.mosire.simos.app.access.DecisionCallerFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmToolNames} 的用例：**真 LLM 实测缺陷**（2026-09-22）的护栏。
 *
 * <p>★ **缺陷现场**：Simos 的工具名（{@code simos.map.hex}）含 {@code .}，OpenAI 兼容端点只收 {@code ^[a-zA-Z0-9_-]+$}
 * ⇒ {@code HTTP 400}、{@code llmCalls=0}、一轮决策**一次都没发出去**。
 *
 * <p>★ **四条各钉一件事**：① 送出去的名字合文法；② 名字**往返**（模型说的线名能映射回真实名——只转义不映射回来，模型就永远叫不动工具）； ③ 未知名字**可读拒绝**；④
 * 碰撞**响亮失败**（静默挑一个 = 可能执行了另一个工具）。
 *
 * <p>★ 样本取 {@link DecisionCallerFactory#WHITELIST}（真在用的那 11 条），不另抄一份字面量——抄一份就多一处漂移。
 */
class LlmToolNamesTest {

  @Test
  void everyWireNameMatchesTheProviderPattern() {
    Set<String> real = DecisionCallerFactory.WHITELIST;

    List<String> wire = real.stream().map(LlmToolNames::wireNameOf).toList();

    assertThat(wire)
        .as("★★ 每一条都匹配供应商的函数名文法（这就是现场那个 400 的判据）")
        .allMatch(name -> name.matches(LlmToolNames.PROVIDER_NAME_PATTERN));
    assertThat(real)
        .as("★ 对照组：真实名里有 {@code .} ⇒ 不转义就必然不合文法（本用例不是恒真）")
        .anyMatch(name -> !name.matches(LlmToolNames.PROVIDER_NAME_PATTERN));
  }

  /** ★★ **往返**：模型说线名 ⇒ 能拿回真实名。只转义、不映射回来的实现**在这里必红**（那是"模型永远叫不动工具"）。 */
  @Test
  void everyWireNameMapsBackToItsRealName() {
    LlmToolNames names = LlmToolNames.of(DecisionCallerFactory.WHITELIST);

    // 逐条往返即"一条不漏"的证明：白名单 11 条各有各的线名，且都能映射回自己。
    for (String real : DecisionCallerFactory.WHITELIST) {
      String wire = LlmToolNames.wireNameOf(real);
      assertThat(names.realNameOf(wire)).as("线名 " + wire + " 必须映射回 " + real).contains(real);
    }
  }

  /** ★ 转义规则是**换字符**（{@code .} → {@code _}），不是删字符：删掉点会压掉层级、也更容易撞名。 */
  @Test
  void escapingReplacesIllegalCharactersInsteadOfDroppingThem() {
    assertThat(LlmToolNames.wireNameOf("simos.map.hex")).isEqualTo("simos_map_hex");
    assertThat(LlmToolNames.wireNameOf("sd.IssueDirective")).isEqualTo("sd_IssueDirective");
    assertThat(LlmToolNames.wireNameOf("a b/c.d-e_f"))
        .as("空格、斜杠、点各转一个下划线；连字符与下划线原样保留")
        .isEqualTo("a_b_c_d-e_f");
    assertThat(LlmToolNames.wireNameOf("already-safe_1"))
        .as("本来就合法的名字一个字节都不动")
        .isEqualTo("already-safe_1");
  }

  /**
   * ★★ **碰撞之一：两条真实名的转义结果相同** ⇒ 装配期抛，**绝不静默挑一个**。
   *
   * <p>{@code a.b} 与 {@code a/b} 都成了 {@code a_b}——模型说 {@code a_b} 时我们无从知道它指哪一条，猜错就执行了另一个工具。
   */
  @Test
  void twoNamesEscapingToTheSameWireNameAreAnAssemblyFault() {
    assertThatThrownBy(() -> LlmToolNames.of(List.of("a.b", "a/b")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("装配故障")
        .hasMessageContaining("a_b")
        .hasMessageContaining("a.b")
        .hasMessageContaining("a/b");
  }

  /**
   * ★★ **碰撞之二：转义结果恰是另一条真实名**（{@code map.SetEdge} 撞上本来就合法的 {@code map_SetEdge}）。
   *
   * <p>这一条特别容易漏：它看着像"一条合法、一条非法"，而两条都是**真注册着的工具**。
   */
  @Test
  void anEscapedNameCollidingWithAnotherRealNameIsAnAssemblyFault() {
    assertThatThrownBy(() -> LlmToolNames.of(List.of("map.SetEdge", "map_SetEdge")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("装配故障")
        .hasMessageContaining("map_SetEdge")
        .hasMessageContaining("map.SetEdge");
  }

  /** ★ 对照组：**单独一条**不会跟自己撞（否则上面两条用例就退化成"凡有 {@code .} 就抛"）。 */
  @Test
  void aLoneNameNeverCollidesWithItself() {
    assertThat(LlmToolNames.of(List.of("map.SetEdge")).realNameOf("map_SetEdge"))
        .as("只有一条时，转义结果就是它的")
        .contains("map.SetEdge");
    assertThat(LlmToolNames.of(List.of("a.b", "a.b")).realNameOf("a_b"))
        .as("同一条真实名出现两次 = 一条（不是碰撞）")
        .contains("a.b");
  }

  /**
   * ★★ **未知名字 ⇒ 空**（可读拒绝的另一半在调用方：把原话交给权限链，由 AgentLib 报 {@code TOOL_NOT_FOUND} 并带上它给的名字）。
   *
   * <p>★ 注意**真实名也返回空**：{@code simos.map.hex} 不是线名。这一条正是"模型用了不在表里的写法"这件事本身。
   */
  @Test
  void anUnknownWireNameIsEmpty() {
    LlmToolNames names = LlmToolNames.of(DecisionCallerFactory.WHITELIST);

    assertThat(names.realNameOf("simos.made.up")).isEmpty();
    assertThat(names.realNameOf("simos.map.hex")).as("真实名不是线名").isEmpty();
  }

  /** ★ **换名字只动名字**：描述与 schema **逐字**来自真工具（顺手改点别的就是把两件事绑在一起）。 */
  @Test
  void wireDefsChangeOnlyTheName() {
    ToolDef real =
        new ToolDef(
            "simos.map.hex", "读一格", Map.of("type", "object", "properties", Map.of("q", "int")));
    LlmToolNames names = LlmToolNames.of(List.of(real.name()));

    List<ToolDef> wire = names.wireDefs(List.of(real));

    assertThat(wire).hasSize(1);
    assertThat(wire.get(0).name()).isEqualTo("simos_map_hex");
    assertThat(wire.get(0).description()).isEqualTo(real.description());
    assertThat(wire.get(0).jsonSchema()).isEqualTo(real.jsonSchema());
  }

  /** ★ 表与工具面**不同源**（拿另一组名字建的表去转义）⇒ 抛，不静默漏掉、也不补一个。 */
  @Test
  void aDefOutsideTheTableIsAnAssemblyFault() {
    LlmToolNames names = LlmToolNames.of(List.of("simos.map.hex"));

    assertThatThrownBy(
            () -> names.wireDefs(List.of(new ToolDef("sd.IssueDirective", "出令", Map.of()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("装配故障")
        .hasMessageContaining("sd.IssueDirective");
  }

  /** ★ 建表**保序可复现**：同一个坏输入两次报同一段文本（诊断要能对照，不要每次换一个顺序）。 */
  @Test
  void theFaultMessageIsReproducible() {
    String first =
        catchMessage(() -> LlmToolNames.of(new TreeSet<>(Set.of("b.c", "b/c", "a.b", "a/b"))));
    String second =
        catchMessage(() -> LlmToolNames.of(new TreeSet<>(Set.of("a/b", "a.b", "b/c", "b.c"))));

    assertThat(first).isEqualTo(second).contains("a_b");
  }

  private static String catchMessage(Runnable action) {
    try {
      action.run();
      throw new AssertionError("本该抛");
    } catch (IllegalStateException e) {
      return e.getMessage();
    }
  }
}
