package io.mosire.simos.ga.effect;

/**
 * 效果种类（spec §三.6）：ECA 规则的子类（条令名，research §B.8）。
 *
 * <p>★ **{@code BE_PREPARED} 是"仅计划、可能不发生"**（= "be prepared to"）；**{@code ON_CALL} 是"已交给你、等触发"** （=
 * "on order"）——两者语义不同，勿混。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本类型自 {@code io.mosire.simos.sd.model} 整族迁入本包</b>（用户裁定「sd 整个弃用！」）
 * ——<b>五档的枚举名与线格式取值（{@code SCHEDULED}/{@code ON_CALL}/{@code BE_PREPARED}/{@code BRANCH}/{@code
 * SEQUEL}） 一字未改</b>。
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
