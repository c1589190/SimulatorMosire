package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@link DecisionToolDefs} 的用例（spec §2.3 要点 1：**工具面 = 决策人权限组下的工具**）。
 *
 * <p>★ 这一条的要害是**同源**：给模型看的工具清单与权限层放行的白名单若是两份数据，就会有"模型看得见、调了却被拒"或"能调却看不见"的错位，
 * 而**两种错位都不报错**。故用例的两条分别是"只有白名单里的工具会被交出去"与"白名单漏一条要响亮失败"。
 *
 * <p>★ 期望值取自 {@link DecisionCallerFactory#WHITELIST}（它自己有派生式用例钉着），不另抄一份字面量——抄一份就多一处漂移。
 */
class DecisionToolDefsTest {

  /** 白名单之外的 GM 工具（真的注册着，用来证明"注册了就一定露出来"是不成立的）。 */
  private static final List<String> GM_ONLY_TOOLS =
      List.of("simos.command.submit", "simos.advance", "simos.fork", "map.SetTerrain");

  @Test
  void theOfferedToolFaceIsExactlyTheWhitelistNotTheWholeRegistry() {
    ToolRegistry registry = new ToolRegistry();
    for (String name : DecisionCallerFactory.WHITELIST) {
      registry.register(new StubTool(name));
    }
    for (String name : GM_ONLY_TOOLS) {
      registry.register(new StubTool(name));
    }

    List<String> offered = DecisionToolDefs.of(registry).stream().map(ToolDef::name).toList();

    assertThat(offered)
        .as("★ 恰好是白名单（有序），注册表里多出来的 GM 写工具**一条都不露**")
        .containsExactlyElementsOf(new TreeSet<>(DecisionCallerFactory.WHITELIST));
    assertThat(offered).doesNotContainAnyElementsOf(GM_ONLY_TOOLS);
  }

  /** ToolDef 的字段来自**工具自己**（描述与 schema 原样转交，不是另填一份）。 */
  @Test
  void theToolDefCarriesTheToolsOwnDescriptionAndSchema() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new StubTool("simos.map.hex"));

    List<ToolDef> defs = DecisionToolDefs.of(registry, java.util.Set.of("simos.map.hex"));

    assertThat(defs).hasSize(1);
    assertThat(defs.get(0).name()).isEqualTo("simos.map.hex");
    assertThat(defs.get(0).description()).isEqualTo("stub 描述 simos.map.hex");
    assertThat(defs.get(0).jsonSchema()).containsEntry("type", "object");
  }

  /** ★★ **白名单里有一条不在注册表 ⇒ 响亮失败**（装配故障），不是静默少给一条工具——后者会让那个能力**无声消失**：模型再也不会请求它， 也不会有任何报错。 */
  @Test
  void aMissingWhitelistedToolIsAnAssemblyFaultNotASilentGap() {
    ToolRegistry partial = new ToolRegistry();
    partial.register(new StubTool("simos.map.hex"));

    assertThatThrownBy(() -> DecisionToolDefs.requireAll(partial, DecisionCallerFactory.WHITELIST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("装配故障")
        .hasMessageContaining("simos.map.overview"); // 消息里逐条列出缺了哪些

    assertThat(DecisionToolDefs.of(partial, DecisionCallerFactory.WHITELIST))
        .as("对照组：取交形态**不抛**（它有意允许部分工具面）⇒ 两条路的差别就是这条用例在钉的东西")
        .hasSize(1);
  }

  /** 最小工具替身：只提供 {@link DecisionToolDefs} 用到的三个分量。 */
  private record StubTool(String name) implements AgentTool {

    @Override
    public String description() {
      return "stub 描述 " + name;
    }

    @Override
    public java.util.Map<String, Object> jsonSchema() {
      return java.util.Map.of("type", "object", "properties", java.util.Map.of());
    }

    @Override
    public ToolResult execute(ToolContext context) {
      return ToolResult.ok("{}");
    }
  }
}
