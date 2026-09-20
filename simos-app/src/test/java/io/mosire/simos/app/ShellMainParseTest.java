package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ShellMain#parse} 的验收（M10）：新增的 {@code --bind-address} 开关**有缺省、可显式覆盖、缺取值即拒**。
 *
 * <p>★ **判别力**：缺省断言钉住 {@code 127.0.0.1}（回环）——把缺省改成 {@code 0.0.0.0} 的变异体在此红。
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
}
