package io.mosire.simos.app.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 渲染缓存：命中不重算、同键单飞、LRU 淘汰、失败不被缓存。
 *
 * <p>单飞是小并发下最容易漏的一条：三个消费面（决策人 / GUI / MCP）可能同刻要同一张图，不单飞就是白烧三份 CPU。
 */
class RenderCacheTest {

  @Test
  void hitsDoNotRecomputeAndReturnTheSameBytes() {
    RenderCache cache = new RenderCache(4);
    AtomicInteger calls = new AtomicInteger();
    java.util.function.Supplier<byte[]> render = () -> new byte[] {(byte) calls.incrementAndGet()};

    byte[] first = cache.get("k1", render);
    byte[] second = cache.get("k1", render);

    assertThat(calls).hasValue(1);
    assertThat(second).isSameAs(first);
    assertThat(cache.size()).isEqualTo(1);
  }

  /**
   * 同键并发只渲一次，且两个线程拿到<b>同一份字节</b>。
   *
   * <p>★ 判别性来自"两个线程用<b>同一个计数器</b>"：第二个线程若自己算（单飞失效），它调用的供应器同样会 +1 ⇒ 必红。 （早先的版本给第二个线程传了一个不计数的新
   * lambda，变异体照样绿——夹具没把被测行为圈进来。）
   */
  @Test
  void concurrentRequestsForTheSameKeyRenderOnlyOnce() throws Exception {
    RenderCache cache = new RenderCache(4);
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    byte[][] results = new byte[2][];

    Thread first =
        new Thread(
            () ->
                results[0] =
                    cache.get(
                        "same",
                        () -> {
                          calls.incrementAndGet();
                          entered.countDown();
                          try {
                            release.await();
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                          return new byte[] {7};
                        }));
    first.start();
    assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    Thread second =
        new Thread(
            () ->
                results[1] =
                    cache.get(
                        "same",
                        () -> {
                          calls.incrementAndGet();
                          return new byte[] {99};
                        }));
    second.start();
    second.join(200); // 第二个线程此刻应该在等第一个的渲染结果，而不是自己算
    assertThat(calls).as("键在飞时第二个线程不得自己算").hasValue(1);

    release.countDown();
    first.join(5000);
    second.join(5000);
    assertThat(calls).hasValue(1);
    assertThat(results[0]).isNotNull();
    assertThat(results[1]).as("两个线程必须拿到同一份字节").isSameAs(results[0]);
  }

  @Test
  void evictsLeastRecentlyUsedBeyondCapacity() {
    RenderCache cache = new RenderCache(2);
    cache.get("a", () -> new byte[] {1});
    cache.get("b", () -> new byte[] {2});
    cache.get("a", () -> new byte[] {9}); // a 变"最近使用"
    cache.get("c", () -> new byte[] {3}); // 挤掉 b

    assertThat(cache.size()).isEqualTo(2);
    AtomicInteger kept = new AtomicInteger();
    cache.get("a", () -> new byte[] {(byte) kept.incrementAndGet()});
    assertThat(kept).as("a 最近用过 ⇒ 仍应命中").hasValue(0);
    AtomicInteger recomputed = new AtomicInteger();
    cache.get("b", () -> new byte[] {(byte) recomputed.incrementAndGet()});
    assertThat(recomputed).as("b 已被淘汰 ⇒ 必须重算（这次插入又会挤掉最旧的 a）").hasValue(1);
  }

  @Test
  void failuresAreNotCachedAndPropagate() {
    RenderCache cache = new RenderCache(2);
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                cache.get(
                    "boom",
                    () -> {
                      attempts.incrementAndGet();
                      throw new IllegalStateException("渲染失败");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("渲染失败");
    assertThat(cache.size()).isZero();

    byte[] recovered = cache.get("boom", () -> new byte[] {5});
    assertThat(recovered).containsExactly(5);
    assertThat(attempts).hasValue(1);
  }

  @Test
  void rejectsBlankKeys() {
    RenderCache cache = new RenderCache(2);
    assertThatThrownBy(() -> cache.get("  ", () -> new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RenderCache(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
