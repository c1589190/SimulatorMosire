package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.SimulationState;
import java.util.Optional;

/**
 * ★★ <b>候选态落库前的跨切片校验器</b>（D-031 P1 spec §9.3）。
 *
 * <p>★★ <b>为什么需要它</b>：{@link MutationGuard} 在 <b>handler 之前</b>、按逐条命令看 <b>当刻候选态</b>；它验证不了"整批
 * 提交后两片最终状态"的互相引用（典型：同批先建 A 片定义、后建 B 片实例，或反向）。本接口在<b>写入口落库之前</b>拿到
 * <b>基态</b>与<b>完整候选态</b>两整份，只读地回答"这份候选态能不能落"。
 *
 * <p>★★ <b>归属与纪律</b>：Core 只按注册序调用、只把返回的具名理由折成拒绝 —— <b>不认识</b>任何领域类型（铁律 4）。领域判断 （如 economy 的实例引用
 * production 定义）由各领域实现，经 {@code production.contract} 之类契约取数；app 只负责注册。
 *
 * <p>★ <b>只读纯函数</b>：不得修改 {@code base}/{@code candidate}，不得写任何状态。返回 {@link Optional#empty()} = 通过；
 * 非空 = 可读拒绝理由（命令层折 INFO 拒绝、零 revision、零切片变化）。
 *
 * <p>★ <b>无注册者 ⇒ Core 行为不变</b>：既有调用点传空表时逐值退回改动前。
 */
public interface CandidateStateValidator {

  /** 校验器名（注册序去重/日志用；非空白）。 */
  String name();

  /**
   * 候选态是否可落。空 = 通过；非空 = 具名拒绝理由。
   *
   * @param base 落库前基态（完整 {@link SimulationState}）
   * @param candidate 落库前完整候选态（完整 {@link SimulationState}）
   */
  Optional<String> rejection(SimulationState base, SimulationState candidate);
}
