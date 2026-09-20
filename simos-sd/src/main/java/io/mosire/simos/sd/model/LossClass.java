package io.mosire.simos.sd.model;

/**
 * 损失类别（spec §三.4）：永久 vs 可回收。
 *
 * <p>★ v1 **只记类别、不设回池速率**（spec §〇.3）——回池是第二层机制，等战损先落地。
 */
public enum LossClass {
  /** 永久损失。 */
  PERMANENT,
  /** 可回收损失（v1 只记类别，不设回池）。 */
  RECOVERABLE
}
