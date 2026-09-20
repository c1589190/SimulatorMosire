package io.mosire.simos.sd.model;

/**
 * 效果种类（spec §三.6）：ECA 规则的子类（条令名，research §B.8）。
 *
 * <p>★ **{@code BE_PREPARED} 是"仅计划、可能不发生"**（= "be prepared to"）；**{@code ON_CALL} 是"已交给你、等触发"** （=
 * "on order"）——两者语义不同，勿混。
 */
public enum EffectKind {
  /** 计划在特定时点发生。 */
  SCHEDULED,
  /** 已下达、等触发即执行（on order）。 */
  ON_CALL,
  /** 仅计划、可能不发生（be prepared to）。 */
  BE_PREPARED,
  /** 分支（条件不满足则改走另一条）。 */
  BRANCH,
  /** 后续（前序效果之后的延续）。 */
  SEQUEL
}
