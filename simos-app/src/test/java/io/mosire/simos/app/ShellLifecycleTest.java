package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **R9（spec §11 末行）的生命周期验收（M5 T11；T4 曾扩到四口，**2026-09-22 随决策人 MCP 口撤销回到三口**）**： {@link
 * Shell#close()} 之后**三个端口**（GUI / MCP / 审批）全部释放， 且**无停驻的非守护线程**。
 *
 * <p>★ **判别力来源**：端口释放用"再绑一次"证——{@code close()} 漏掉任何一次 {@code stop}/{@code closeGracefully}，
 * 对应端口仍被监听，{@code ServerSocket.bind} 当场 {@code EADDRINUSE}（{@code SO_REUSEADDR} 不允两个监听者同占一个地址）。 这正是
 * 变异体 {@code m1}（去掉 {@code Shell.close()} 里的一次关闭）的咬点。
 *
 * <p>★ 线程检查取 {@code close()} 前后**非守护线程集合的差**（以 {@code threadId:name} 标识，避免同名线程相互遮蔽）——
 * 只要求"本壳新建的线程都收干净"，不误伤 JVM/JUnit 既有的常驻线程。
 *
 * <p>夹具最简：空 shop 起壳即可（{@code Shell.start} 不要求创世），端口全 0。
 */
class ShellLifecycleTest {

  private static final Duration PORT_RELEASE_WAIT = Duration.ofSeconds(5);
  private static final Duration THREAD_DRAIN_WAIT = Duration.ofSeconds(10);

  @TempDir Path tempDir;

  @Test
  void closeReleasesAllThreePortsAndLeavesNoLingeringNonDaemonThreads() {
    Set<String> threadsBefore = nonDaemonThreads();

    Shell shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
    int guiPort = shell.boundGuiPort();
    int mcpPort = shell.boundMcpPort();
    int approvalPort = shell.boundApprovalPort();
    assertThat(guiPort).as("GUI 端口已绑定").isPositive();
    assertThat(mcpPort).as("MCP 端口已绑定").isPositive();
    assertThat(approvalPort).as("审批端口已绑定").isPositive();

    shell.close();

    assertEventuallyRebindable("GUI", guiPort, shell);
    assertEventuallyRebindable("MCP", mcpPort, shell);
    assertEventuallyRebindable("审批", approvalPort, shell);

    Set<String> leaked = awaitDrainedNonDaemonThreads(threadsBefore, THREAD_DRAIN_WAIT);
    assertThat(leaked).as("close() 后不得有本壳新建且仍停驻的非守护线程: %s", leaked).isEmpty();
  }

  /** 端口可重新绑定（{@code close()} 漏关一次即在此红）。轮询以吸收 OS 回收的极短延迟。 */
  private static void assertEventuallyRebindable(String label, int port, Shell shell) {
    long deadline = System.nanoTime() + PORT_RELEASE_WAIT.toNanos();
    boolean bound = false;
    while (System.nanoTime() < deadline) {
      if (canBind(port)) {
        bound = true;
        break;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(bound)
        .as("%s 端口 %d 在 close() 后必须可重新绑定（仍被监听 ⇒ Shell.close() 漏了一次关闭）", label, port)
        .isTrue();
  }

  private static boolean canBind(int port) {
    try (ServerSocket probe = new ServerSocket()) {
      probe.setReuseAddress(true);
      probe.bind(new InetSocketAddress("127.0.0.1", port));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** 轮询直到"本壳新建的非守护线程"全部消失；返回仍停驻的差集（超时也返回，供断言报出名字）。 */
  private static Set<String> awaitDrainedNonDaemonThreads(Set<String> before, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    Set<String> leaked;
    while (true) {
      leaked = new HashSet<>(nonDaemonThreads());
      leaked.removeAll(before);
      if (leaked.isEmpty() || System.nanoTime() >= deadline) {
        return leaked;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return leaked;
      }
    }
  }

  /** 当前存活非守护线程的 {@code threadId:name} 集合（id 唯一，避免同名线程互相遮蔽）。 */
  private static Set<String> nonDaemonThreads() {
    Set<String> ids = new HashSet<>();
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.isAlive() && !thread.isDaemon()) {
        ids.add(thread.threadId() + ":" + thread.getName());
      }
    }
    return ids;
  }
}
