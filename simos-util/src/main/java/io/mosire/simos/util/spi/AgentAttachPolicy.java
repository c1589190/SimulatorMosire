package io.mosire.simos.util.spi;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.resolve.ResolveContext;

/**
 * 模块声明"本模块的哪些主体可绑决策人"（spec §6，M5 T10）。
 *
 * <p>★ **判定权归模块自己**：地图知不知道"什么算可绑"，只有 MapSimos 自己知道；app/Core 只能**问**， 不许 {@code instanceof} 领域类型、不许
 * switch 模块类型。这正是总纲"Core 只问 {@code canAttachAgent(subject)?}，不写 instanceof"的落地，也是铁律 3/4 在
 * AgentBinding 上的形态。
 *
 * <p>★ **{@code namespace()} 与地址首段一致**（{@code "map"} / {@code "unit"}）——注册表按它建键， 一个命名空间只允许一条策略。
 *
 * <p>★ **本接口只回答"可不可以绑"**：绑定的执行语义（决策人产出 → Command）由 Brain/MainMosire 消费，**不在本仓**。
 */
public interface AgentAttachPolicy {

  /** 本策略负责的命名空间，与地址首段一致（{@code "map"} / {@code "unit"}）。 */
  String namespace();

  /**
   * {@code subject} 是否可绑决策人。{@code subject} 是**canonical 地址**——调用方先解析再问（R8）， 故实现可以直接拿它去查本模块的状态。
   *
   * @param subject canonical 主体地址（namespace 与 {@link #namespace()} 相同）
   * @param ctx 解析上下文（{@code state} + {@code at}），实现按本模块的切片自取
   * @return 可绑为 {@code true}；不可绑（含本模块不服务的主体）为 {@code false}
   */
  boolean canAttach(Address subject, ResolveContext ctx);
}
