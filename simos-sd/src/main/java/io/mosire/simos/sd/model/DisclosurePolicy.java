package io.mosire.simos.sd.model;

/**
 * 判决结果的披露口径（spec §七.2 的 {@code ViewScope.adjudicationDisclosure}）。
 *
 * <p>★ **spec 未定义其取值**（§三.2 只把它列为字段类型）——实现期定为三档，见台账取代说明。它是 redaction 层的**入参**： {@code
 * PERCEPTION_ONLY} 表示只披露观察者可感知的部分；{@code WITHHELD} 表示本判决对该角色不可见。
 */
public enum DisclosurePolicy {
  /** 全量披露（GM / 裁决者）。 */
  FULL,
  /** 只披露可观察项（N6：不报"未探测到 X"的否定式）。 */
  PERCEPTION_ONLY,
  /** 对持有者完全不披露。 */
  WITHHELD
}
