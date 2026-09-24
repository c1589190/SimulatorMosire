package io.mosire.simos.app.render;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * 渲染缓存：键 → PNG 字节，<b>LRU + 单飞</b>。
 *
 * <p>★ <b>为什么键要显式传</b>：同一份参数在不同 revision 上是两张不同的图（世界变了），所以键 = revision + 参数指纹， 由调用方拼（{@link
 * RenderRequest#fingerprint()} 给参数字段）。缓存<b>不猜</b>数据是否变过。
 *
 * <p>★ <b>单飞</b>：同一键并发请求只渲一次，其余等结果。渲染是纯 CPU 的（几毫秒～几十毫秒），但一次决策可能同时有 多个消费面（决策人 + GUI +
 * MCP）在同刻要同一张图——不单飞就是白烧几倍 CPU，且白白把延迟叠在别人身上。
 *
 * <p>线程安全：LRU 用 synchronized 保护（操作很短），在飞表用 {@link java.util.concurrent.ConcurrentHashMap}。
 */
public final class RenderCache {

  private final int maxEntries;
  private final LinkedHashMap<String, byte[]> entries;
  private final Map<String, CompletableFuture<byte[]>> inFlight =
      new java.util.concurrent.ConcurrentHashMap<>();

  public RenderCache(int maxEntries) {
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries 必须 ≥ 1: " + maxEntries);
    }
    this.maxEntries = maxEntries;
    this.entries = new LinkedHashMap<>(16, 0.75f, true);
  }

  /**
   * 取缓存；未命中则调用 {@code render} 现算并缓存。
   *
   * @param key 渲染键（revision + 参数指纹；不得为空白）
   * @param render 现算器（只在未命中时被调用，且同一键同时只有一个线程在算）
   * @return PNG 字节（同一实例；调用方<b>不得</b>改它的内容）
   */
  // ★ 转发语义：渲染失败必须**原样**上抛（同一实例、同一类型）——换一个新实例会改掉调用方按类型/身份
  //   分类处理的依据（例如统一的 BadRequest 折叠靠类型判），故此处刻意 rethrow，不用 SpotBugs 的替代写法。
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "转发语义：同一实例原样上抛，包装会改变调用方的类型判据")
  public byte[] get(String key, Supplier<byte[]> render) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("缓存键不得为空白");
    }
    Objects.requireNonNull(render, "render");

    synchronized (entries) {
      byte[] hit = entries.get(key);
      if (hit != null) {
        return hit;
      }
    }

    CompletableFuture<byte[]> mine = new CompletableFuture<>();
    CompletableFuture<byte[]> running = inFlight.putIfAbsent(key, mine);
    if (running != null) {
      return await(running);
    }
    try {
      byte[] bytes = Objects.requireNonNull(render.get(), "渲染器不得返回 null");
      synchronized (entries) {
        entries.put(key, bytes);
        while (entries.size() > maxEntries) {
          var oldest = entries.entrySet().iterator();
          oldest.next();
          oldest.remove();
        }
      }
      mine.complete(bytes);
      return bytes;
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      inFlight.remove(key, mine);
    }
  }

  /** 当前缓存条目数（测试/审计用）。 */
  public int size() {
    synchronized (entries) {
      return entries.size();
    }
  }

  /** 等已在飞的渲染结果：失败按**原类型**上抛（{@link CompletionException} 的 cause 若是运行时异常，剥出来原样抛）。 */
  // 同上：转发语义，见 get 上的说明。
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "转发语义：把在飞渲染的失败按原类型上抛，包装会改变调用方的类型判据")
  private static byte[] await(CompletableFuture<byte[]> running) {
    try {
      return running.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw e;
    }
  }
}
