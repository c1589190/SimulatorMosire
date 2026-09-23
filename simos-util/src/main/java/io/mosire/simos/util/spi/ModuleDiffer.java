package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;

/**
 * 从**两个完整状态切片**派生变更集（铁律 5）：{@code diff(base, target)}。
 *
 * <p>★ **为什么需要它**：{@link ChangeSet} 是标记接口，{@link ModuleCodec} 只有编解码与施加——**没有 diff/merge**。而 "一批命令 =
 * 一条 revision"（用户裁定：一个 tick 一条 revision，原子）要求落盘的那**一条**变更集从**基态与候选态两整份状态** 派生出来：N
 * 条命令各自的变更集是**相对各自中途状态**的，简单叠加会得到与铁律 5 相悖的"平行结构"。故 Core 需要"给定两个 {@link Snapshot}，给我它们之间的变更集"这一个新口子。
 *
 * <p>★ **本接口是新增契约，落在 {@code util.spi}**（ADR-1 C9：新增放本包、既有契约原地不动）：{@link ModuleCodec} 一个字未改。各模块的
 * codec 同时实现两者——把两个切片还原成本域状态，委托本域既有的 {@code XChangeSet.between(base, target)}。
 *
 * <p>★ **领域语义不在这里**：哪些组件参与比较、如何比较，一律仍在各模块的 {@code between} 里。本方法只是**按 namespace 归类
 * 的那一层薄壳**。因此"新增状态组件却不进变更集"那类漂移，仍由各模块的往返反射测试把守（R1 的判别力不被稀释）。
 *
 * <p>★ **实现契约**：{@code base} 与 {@code target} 的 {@code namespace()} 必须与本实现一致；不一致时**当场抛**（宁可响亮失败，
 * 也不静默给出错误的变更集）。
 */
public interface ModuleDiffer {

  /**
   * 派生 {@code base → target} 的变更集。
   *
   * <p>★ 铁律 5：变更集从完整状态派生——两整份状态进去，一份变更集出来。全组件相等 ⇒ 各组件 {@code Unchanged} （**不是**空对象）。
   *
   * @param base 基态切片
   * @param target 目标态切片
   * @return 从完整状态派生的变更集
   * @throws NullPointerException 任一参数为 null
   * @throws IllegalStateException 切片不是本模块的状态类型（装配给错了切片）
   */
  ChangeSet diff(Snapshot base, Snapshot target);
}
