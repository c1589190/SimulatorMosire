package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ShellConfig#bindAddress()} 从配置到**真实监听面**的验收（M10）。
 *
 * <p>★ **两层判别力**：
 *
 * <ul>
 *   <li><b>确定性层</b>：{@code 0.0.0.0} 时 {@link InetAddress#isAnyLocalAddress()} 为真、缺省时为假——不需要第二块网卡，
 *       且直接杀掉"Shell 忽略 {@code bindAddress}、硬编码 127.0.0.1"的变异体。
 *   <li><b>跨接口层</b>：经**非回环** IPv4 真连一次：绑 {@code 0.0.0.0} 的 GUI / MCP 可达、缺省回环不可达；审批端口因 AgentLib
 *       恒绑回环而**始终不可达**。本机无非回环 IPv4 时用 {@link Assumptions} 跳过（不伪装成绿）。
 * </ul>
 */
class BindAddressTest {

  @TempDir Path tempDir;

  @Test
  void guiBindsLoopbackByDefault() throws Exception {
    try (Shell shell = Shell.start(ShellConfig.defaults(storeDir("loop")).withPorts(0, 0, 0, 0))) {
      assertThat(shell.boundGuiHost()).as("缺省绑定地址").isEqualTo("127.0.0.1");
      assertThat(InetAddress.getByName(shell.boundGuiHost()).isAnyLocalAddress())
          .as("缺省不是通配地址")
          .isFalse();
    }
  }

  @Test
  void guiHonorsWildcardBindAddress() throws Exception {
    try (Shell shell =
        Shell.start(
            ShellConfig.defaults(storeDir("wild"))
                .withPorts(0, 0, 0, 0)
                .withBindAddress("0.0.0.0"))) {
      assertThat(InetAddress.getByName(shell.boundGuiHost()).isAnyLocalAddress())
          .as("0.0.0.0 ⇒ 通配（JDK 读回 0:0:0:0:0:0:0:0）；硬编码回环的变异体在此红")
          .isTrue();
    }
  }

  @Test
  void wildcardExposesGuiAndMcpOnANonLoopbackInterfaceButNotApproval() throws Exception {
    Optional<String> lanIp = nonLoopbackIpv4();
    Assumptions.assumeTrue(lanIp.isPresent(), "本机无非回环 IPv4，跳过跨接口可达性断言");
    String ip = lanIp.get();

    try (Shell wild =
        Shell.start(
            ShellConfig.defaults(storeDir("wild-iface"))
                .withPorts(0, 0, 0, 0)
                .withBindAddress("0.0.0.0"))) {
      assertThat(canConnect(ip, wild.boundGuiPort())).as("GUI 绑 0.0.0.0 ⇒ 经 %s 可达", ip).isTrue();
      assertThat(canConnect(ip, wild.boundMcpPort())).as("MCP 绑 0.0.0.0 ⇒ 经 %s 可达", ip).isTrue();
      assertThat(canConnect(ip, wild.boundDecisionAgentMcpPort()))
          .as("决策人 MCP 同绑 0.0.0.0 ⇒ 经 %s 可达（T4）", ip)
          .isTrue();
      assertThat(canConnect(ip, wild.boundApprovalPort()))
          .as("审批恒回环（AgentLib 安全基线）⇒ 经 %s 不可达", ip)
          .isFalse();
    }

    try (Shell loop =
        Shell.start(ShellConfig.defaults(storeDir("loop-iface")).withPorts(0, 0, 0, 0))) {
      assertThat(canConnect(ip, loop.boundGuiPort()))
          .as("缺省回环 ⇒ 经 %s 不可达（绑定地址真的生效的判别力）", ip)
          .isFalse();
      assertThat(canConnect(ip, loop.boundMcpPort())).as("缺省回环 ⇒ MCP 经 %s 不可达", ip).isFalse();
      assertThat(canConnect(ip, loop.boundDecisionAgentMcpPort()))
          .as("缺省回环 ⇒ 决策人 MCP 经 %s 不可达", ip)
          .isFalse();
    }
  }

  private Path storeDir(String name) throws IOException {
    Path dir = tempDir.resolve(name);
    Files.createDirectories(dir);
    return dir;
  }

  /** 对一个 {@code host:port} 发一次裸 TCP 连接（能建连即"在监听"）。 */
  private static boolean canConnect(String host, int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 700);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** 本机第一个非回环、非链路本地的 IPv4 地址（用于跨接口可达性断言）。 */
  private static Optional<String> nonLoopbackIpv4() {
    try {
      for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!ni.isUp() || ni.isLoopback()) {
          continue;
        }
        for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
          if (addr instanceof Inet4Address
              && !addr.isLoopbackAddress()
              && !addr.isLinkLocalAddress()
              && !addr.isAnyLocalAddress()) {
            return Optional.of(addr.getHostAddress());
          }
        }
      }
    } catch (SocketException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }
}
