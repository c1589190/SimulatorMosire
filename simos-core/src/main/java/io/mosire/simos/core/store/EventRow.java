package io.mosire.simos.core.store;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code events} 表的一行（spec §6.1）：{@code ts / type / agent / payload / correlation_id}。
 *
 * <p>★★ **{@code seq} 不在本记录里**——它是数据库的 {@code INTEGER PRIMARY KEY AUTOINCREMENT}，写的时候**还不存在**。
 * 读回来时顺序由 {@code ORDER BY seq} 保证（{@link EventStore} 的读方法都带这个排序）， 而判据二要的是**类型序列**，不是序号本身。硬塞一个
 * {@code long seq} 进写侧会逼出一个"写时填 0"的假字段。
 *
 * <p>★ **{@code ts} 是墙上时钟（{@code Instant}），不是模拟时刻**。理由：本列与 agentlib 的 {@code events} 一字不差，而
 * agentlib 的 {@code Event.ts()} 就是 {@code Instant}；且总纲 §8.1 列它的用途是"**耗时** | 性能排查"——那是墙上时钟的活。**模拟时刻在
 * {@code revisions} 表里**（{@code tick} + {@code calendar_label} 两列）， 两者不是一个东西，别混。★ 这条是**执行期裁定**：spec
 * 只钉了列名与类型，没钉 `ts` 的口径。
 *
 * <p>★ **{@code agent} 存 initiator 原文**（C21）：不解析、不改写——Core 不认识"玩家/Agent/MCP/脚本"的区别， 那是发起方自己的身份字符串。
 */
public record EventRow(
    Instant ts, String type, String agent, String payload, String correlationId) {

  public EventRow {
    Objects.requireNonNull(ts, "ts");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(correlationId, "correlationId");
  }

  /**
   * 便捷构造：{@code ts} 取当前墙上时钟。
   *
   * <p>★ 生产路径用它；**用例若需要可复现的 `ts` 就直接调规范构造器**（本记录允许显式给值）。
   */
  public static EventRow of(String type, String agent, String payload, String correlationId) {
    return new EventRow(Instant.now(), type, agent, payload, correlationId);
  }
}
