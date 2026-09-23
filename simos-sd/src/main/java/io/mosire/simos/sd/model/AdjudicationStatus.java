package io.mosire.simos.sd.model;

/**
 * ★★ **一次裁决的两种状态**（2026-09-23，用户原话：「只有生效裁决和作废裁决」）。
 *
 * <p>★★ **为什么不是"可重复裁决"**：一个 tick 可以留下**多条**裁决记录（改判、晚到的令、驳回后重写），但**至多一条生效**
 * ——新的那个生效，旧的那个**作废**。这与令的 `DirectiveStatus.SUPERSEDED`（末位生效）同构，区别在层级：
 * 令的 SUPERSEDED 是"同 tick 有人重写了令"，本状态是"这一 tick 的**裁决**不算数了"。
 *
 * <p>★★ **作废会把世界改回去**（2026-09-23 用户裁定 (b)）：一次裁决 = 一条 revision，作废 = 追加一条**逆变更**
 * revision，把 map/unit/social 恢复成裁决前的样子、把被它翻过的令退回 {@link DirectiveStatus#ISSUED}。
 * 见 {@code CommandBus#submitRestore}。★ 那条记录**不删**，只是从 {@link #EFFECTIVE} 变成 {@link #VOIDED} ——
 * 留痕（"第 1 版被作废"这件事本身要看得到），正如令的 SUPERSEDED 也不删旧令。
 *
 * <p>★ **缺省是 {@link #EFFECTIVE}**（老档兼容的方向）：在引入本字段之前落盘的裁决条目**没有**这个键，而它们当时都是
 * 生效的 ⇒ 读回来判成生效才是**语义为真**的缺省。普通 INFO 条目（非 {@code sd:adjudication.*}）压根不带本字段。
 */
public enum AdjudicationStatus {

  /** 生效：这一 tick 的裁决以它为准。**至多一条**。 */
  EFFECTIVE,

  /** 作废：被后来的裁决（或 GM 的作废操作）顶掉了。世界已被改回，令已退回待裁决。 */
  VOIDED
}
