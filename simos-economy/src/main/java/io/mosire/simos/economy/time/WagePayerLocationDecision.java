package io.mosire.simos.economy.time;

/**
 * ★★ <b>付款人地点预检结论</b>（D-032 D3 §6.9.1）。
 *
 * <p>{@link #OK} = 去重后的已有 ∪ 候选地点为空或恰好一个；{@link #MULTIPLE_LOCATIONS} = 多于一个。后者在 W1/W2b 候选不可行/skip、W3
 * 整组业务拒绝 {@code wage-payer-location-unsupported}，均零 lookup/零部分支付。
 */
public enum WagePayerLocationDecision {
  OK,
  MULTIPLE_LOCATIONS
}
