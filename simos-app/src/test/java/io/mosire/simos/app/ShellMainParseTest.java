package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ShellMain#parse} 的验收（M10；T4 增决策人口开关）：新增的 {@code --bind-address} 与 {@code
 * --decision-agent-mcp-port} 开关**有缺省、可显式覆盖、缺取值即拒**。
 *
 * <p>★ **判别力**：缺省断言钉住 {@code 127.0.0.1}（回环）与 {@code 5717}（决策人口）——把缺省改成 {@code 0.0.0.0}
 * 或别的端口的变异体在此红。
 */
class ShellMainParseTest {

  @TempDir Path tempDir;

  @Test
  void bindAddressDefaultsToLoopback() {
    ShellMain.Parsed parsed = ShellMain.parse(new String[] {"--store", tempDir.toString()});

    assertThat(parsed.config().bindAddress()).as("缺省必须回环（不裸暴露）").isEqualTo("127.0.0.1");
    assertThat(ShellConfig.DEFAULT_BIND_ADDRESS).isEqualTo("127.0.0.1");
  }

  @Test
  void explicitBindAddressIsHonored() {
    ShellMain.Parsed parsed =
        ShellMain.parse(new String[] {"--store", tempDir.toString(), "--bind-address", "0.0.0.0"});

    assertThat(parsed.config().bindAddress()).as("显式传 0.0.0.0 供反代场景").isEqualTo("0.0.0.0");
  }

  @Test
  void bindAddressFlagWithoutValueIsRejected() {
    assertThatThrownBy(
            () -> ShellMain.parse(new String[] {"--store", tempDir.toString(), "--bind-address"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--bind-address");
  }

  @Test
  void decisionAgentMcpPortDefaultsTo5717() {
    ShellMain.Parsed parsed = ShellMain.parse(new String[] {"--store", tempDir.toString()});

    assertThat(parsed.config().decisionAgentMcpPort())
        .as("T4/D3：决策人口缺省 5717（与现有口 5715 不同）")
        .isEqualTo(5717);
    assertThat(ShellConfig.DEFAULT_DECISION_AGENT_MCP_PORT).isEqualTo(5717);
  }

  @Test
  void explicitDecisionAgentMcpPortIsHonored() {
    ShellMain.Parsed parsed =
        ShellMain.parse(
            new String[] {"--store", tempDir.toString(), "--decision-agent-mcp-port", "6789"});

    assertThat(parsed.config().decisionAgentMcpPort()).as("显式开关覆盖缺省").isEqualTo(6789);
  }

  @Test
  void decisionAgentMcpPortFlagWithoutValueIsRejected() {
    assertThatThrownBy(
            () ->
                ShellMain.parse(
                    new String[] {"--store", tempDir.toString(), "--decision-agent-mcp-port"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--decision-agent-mcp-port");
  }
}
