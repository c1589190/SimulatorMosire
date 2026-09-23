package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ShellMain#parse} 的验收（M10；**T7 起决策人口开关已拔掉**、**E3 起 {@code --demo} 已拔掉**）： {@code
 * --bind-address} 开关**有缺省、可显式覆盖、缺取值即拒**；而 {@code --decision-agent-mcp-port} 与 {@code --demo}
 * **必须已不存在**（当成未知参数拒）。
 *
 * <p>★ **判别力**：缺省断言钉住 {@code 127.0.0.1}（回环）——把缺省改成 {@code 0.0.0.0} 的变异体在此红；
 * 「开关已拔掉」两条把"留着开关但不接线"这种半吊子形态也堵死（开关还在 ⇒ 有人会以为它有用）。
 */
class ShellMainParseTest {

  @TempDir Path tempDir;

  @Test
  void bindAddressDefaultsToLoopback() {
    ShellConfig config = ShellMain.parse(new String[] {"--store", tempDir.toString()});

    assertThat(config.bindAddress()).as("缺省必须回环（不裸暴露）").isEqualTo("127.0.0.1");
    assertThat(ShellConfig.DEFAULT_BIND_ADDRESS).isEqualTo("127.0.0.1");
  }

  @Test
  void explicitBindAddressIsHonored() {
    ShellConfig config =
        ShellMain.parse(new String[] {"--store", tempDir.toString(), "--bind-address", "0.0.0.0"});

    assertThat(config.bindAddress()).as("显式传 0.0.0.0 供反代场景").isEqualTo("0.0.0.0");
  }

  @Test
  void bindAddressFlagWithoutValueIsRejected() {
    assertThatThrownBy(
            () -> ShellMain.parse(new String[] {"--store", tempDir.toString(), "--bind-address"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--bind-address");
  }

  /** ★ **J2 的开关面**：{@code --decision-agent-mcp-port} **已拔掉** —— 传它必须被当成未知参数拒（不是静默忽略、也不是还在接线）。 */
  @Test
  void decisionAgentMcpPortFlagIsGone() {
    assertThatThrownBy(
            () ->
                ShellMain.parse(
                    new String[] {
                      "--store", tempDir.toString(), "--decision-agent-mcp-port", "6789"
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知参数")
        .hasMessageContaining("--decision-agent-mcp-port");
  }

  /**
   * ★ **E3 的开关面**：{@code --demo} **已拔掉** —— "有世界就是有，没有就就地初始化"不再需要一个开关；传它必须被当成未知参数拒
   * （不是静默忽略、也不是还在接线）。
   */
  @Test
  void demoFlagIsGone() {
    assertThatThrownBy(
            () -> ShellMain.parse(new String[] {"--store", tempDir.toString(), "--demo"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知参数")
        .hasMessageContaining("--demo");
  }
}
