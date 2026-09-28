package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.UseRightId;

/**
 * ★★ <b>使用权</b>（S1）：某主体（{@code holder}）对某产业（{@code activity}）的某种生产资料（{@code asset}） 持有 {@code
 * quantity} 单位的使用权，以及这份权利的性质（{@code kind}）。
 *
 * <p>★★ <b>为什么产能与使用权分离</b>：{@code Industry.capacity} 是该格该产业的<b>技术产能</b>（K3：S1 起只有一份总量），
 * 而"谁能用、用多少"是<b>权利</b>。旧口径把两者压成一个 {@code ClassRow.meansOfProduction} ⇒ 迁移/分家/租佃时无法表达
 * "地还是那块地、换人种了"。S1 起：{@code Industry.capacity} 不动，使用权按主体拆分/转移。
 *
 * <p>★★ <b>不变量</b>（跨表守卫在 {@code EconomyData}）：
 *
 * <pre>
 * 对每个 (activity, asset)：Σ UseRight.quantity ≤ Industry.capacity(activity, asset)   // 使用权不得超过技术产能
 * </pre>
 *
 * <p>★ <b>{@code kind} 三档语义</b>：{@code OWNED}（自有：迁移时按旧 {@code Industry.capacity + operator}
 * 生成的整额档）、 {@code TENANCY}（租佃：S1 保留，S3 的关系改挂）、{@code COMMUNAL}（公地/共同使用）。三档都不改技术 capacity。
 *
 * <p>★ <b>量纲</b>：{@code LAND} 千分亩、其余件（同 {@code Industry.capacity}/{@code AssetKind}）； {@code
 * quantity ≥ 0}（0 是合法状态：权利占位，尚未启用）。
 *
 * @param id 稳定身份；见 {@link #idOf(IndustryId, AssetKind, ActorRef, RightKind, long)}
 * @param activity 这份使用权作用的生产活动（产业）；不得为 null
 * @param holder 使用权持有者（actor 身份）；不得为 null
 * @param asset 生产资料种类；不得为 null
 * @param quantity 数量（非负；单位见 {@link AssetKind}）
 * @param kind 权利性质；不得为 null
 */
public record UseRight(
    UseRightId id,
    IndustryId activity,
    ActorRef holder,
    AssetKind asset,
    long quantity,
    RightKind kind) {

  /** 使用权的性质（S1 词表；S3 的租佃/公地规则读它）。 */
  public enum RightKind {
    /** 自有（迁移器的默认档：旧 {@code Industry.capacity + operator} ⇒ 整额 OWNED）。 */
    OWNED,
    /** 租佃（S1 保留关系；S3 的 ProductionRelation 改挂到实际经营者）。 */
    TENANCY,
    /** 公地/共同使用。 */
    COMMUNAL
  }

  public UseRight {
    if (id == null) {
      throw new IllegalArgumentException("UseRight.id 不得为 null");
    }
    if (activity == null) {
      throw new IllegalArgumentException("UseRight.activity 不得为 null");
    }
    if (holder == null) {
      throw new IllegalArgumentException("UseRight.holder 不得为 null");
    }
    if (asset == null) {
      throw new IllegalArgumentException("UseRight.asset 不得为 null");
    }
    if (quantity < 0L) {
      throw new IllegalArgumentException("UseRight.quantity 不得为负: " + quantity);
    }
    if (kind == null) {
      throw new IllegalArgumentException("UseRight.kind 不得为 null");
    }
  }

  /**
   * ★★ <b>使用权 id 的唯一拼写点</b>： {@code use-<activity>-<asset>-<holder>-<kind>-<sequence>}。
   *
   * <p>★ {@code sequence} 由状态内确定性计数给出（同一状态的重放/分支得到同一批 id；禁止随机数/时间戳/UUID）。 ★ <b>不含 {@code
   * "."}</b>：若任一段带点会与 {@code AddressParser} 的第一个点截断约定冲突 ⇒ 当场抛， 不静默造一个解析不到的地址。
   */
  public static UseRightId idOf(
      IndustryId activity, AssetKind asset, ActorRef holder, RightKind kind, long sequence) {
    if (activity == null) {
      throw new IllegalArgumentException("UseRight.idOf 的 activity 不得为 null");
    }
    if (asset == null) {
      throw new IllegalArgumentException("UseRight.idOf 的 asset 不得为 null");
    }
    if (holder == null) {
      throw new IllegalArgumentException("UseRight.idOf 的 holder 不得为 null");
    }
    if (kind == null) {
      throw new IllegalArgumentException("UseRight.idOf 的 kind 不得为 null");
    }
    if (sequence < 0L) {
      throw new IllegalArgumentException("UseRight.idOf 的 sequence 不得为负: " + sequence);
    }
    String value =
        "use-"
            + activity.value()
            + "-"
            + asset.name()
            + "-"
            + holder.kind()
            + "-"
            + holder.id()
            + "-"
            + kind.name()
            + "-"
            + sequence;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException(
          "UseRight id 不得含 '.'（地址 economy:<mapId>:useRight.<id> 会被第一个点截断）: " + value);
    }
    return new UseRightId(value);
  }
}
