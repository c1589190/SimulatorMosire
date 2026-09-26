/**
 * 经济主体引用的种类与引用本体（设计稿 §2）—— S1 阶段 2 随 {@code ActorRef} / {@code ActorKind} 从 {@code
 * io.mosire.simos.economy.api.actor} 整体上移到这里。
 *
 * <p>四类出处：人口批次（§2 {@code PeopleLot}）、军事/生产单位（§4）、政府（§7）、组织者（§5 的"组织者"）。
 *
 * <p>本包只放"谁"的引用，不放任何切片自己的状态类型。
 */
package io.mosire.simos.actor.api.actor;
