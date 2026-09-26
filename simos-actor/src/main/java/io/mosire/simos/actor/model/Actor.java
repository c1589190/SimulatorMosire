package io.mosire.simos.actor.model;

import io.mosire.simos.actor.api.actor.ActorRef;

/**
 * ★★ <b>Actor 的身份本体</b>（S1 spec §三 L277）：<b>只有身份</b>。
 *
 * <p>★★ <b>为什么资产与库存都不在这里</b>（spec §2.3 L109、§三 L283/L290）："资产是 Actor <b>拥有的关系</b>，不是 Actor
 * <b>本体的一部分</b>" —— 卖掉 30% 土地只改 {@code AssetHolding}，不用打开整个 Actor aggregate。库存同理（{@code
 * GoodsAccount}）。
 *
 * <p>★★ <b>禁令</b>（spec §三 L283 原文）：<b>不许</b>出现 {@code ActorRow { Money money; List<Debt> debts; }}
 * 这种形状 —— 它会把 S1 刚拆开的产权/消费混合当场复活。★ 这条禁令<b>结构上可判</b>，故由 {@code ActorRoundTripTest} 的反射断言把守：本 record
 * 的组件必须<b>恰是</b> {@code ref} 与 {@code label}（多一个红，少一个也红）。
 *
 * @param ref 身份引用（种类 + 稳定 id；铁律 1：id 是身份，不在切片里另造同义 ID）
 * @param label 人类可读的名字（<b>不是</b>身份：改名不影响任何键与引用）
 */
public record Actor(ActorRef ref, String label) {

  public Actor {
    if (ref == null) {
      throw new IllegalArgumentException("Actor.ref 不得为 null");
    }
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("Actor.label 不得为空白: " + label);
    }
  }
}
