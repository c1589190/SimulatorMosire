package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;

/**
 * ★★ <b>实物资产份额</b>（R3B.1：产权/资产线的独立账本）：某种实物资产（{@code asset}）的一份数量记录，同时说清 <b>谁拥有</b> （{@code owner}）与
 * <b>谁实际经营/使用</b>（{@code operator}）。
 *
 * <p>★★ <b>与 {@code Industry.capacity} 的关系（B.1 过渡口径）</b>：本表是唯一的实物资产总账 —— 对每个 {@code (industry,
 * asset)}，{@code Σ OwnershipStake.quantity} 就是实物总量。{@code Industry.capacity} 在 B.1 里只是过渡字段 （B.2
 * 会把它从生产模型中移走），旧档/播种器允许用它<b>一次性</b>生成初始份额，但它<b>不是</b>持续存在的实物账本上界： 本类型与 {@code EconomyData}
 * 都<b>不</b>保留"Σ quantity ≤ capacity"或"Σ quantity == capacity"这类把技术模板与实物账本绑死的不变量。
 *
 * <p>★ <b>{@code owner} 与 {@code operator} 的语义</b>：{@code owner == operator} = 自有自营；{@code owner !=
 * operator} = 租佃/委托/占用 —— 终止租佃时只改该行的 {@code operator}（或改回 owner），{@code owner} 与 {@code quantity}
 * 不变。 地租、分成、工资由 {@code ProductionRules} 结算，不在这里存。
 *
 * <p>★ <b>{@code kind} 三档语义</b>：{@code OWNED}（自有；旧档迁移的默认档）、{@code TENANCY}（租佃）、 {@code
 * COMMUNAL}（公地/共同使用）；{@code kind} 只表达权利性质，不表达数量约束。
 *
 * <p>★ <b>量纲</b>：{@code LAND} 千分亩、其余件（同 {@link AssetKind}）；{@code quantity ≥ 0}（0 是合法状态）。
 *
 * @param id 稳定身份；见 {@link #idOf(IndustryId, AssetKind, ActorRef, ActorRef, RightKind, long)}
 * @param industry 这份份额登记在哪个产业（旧 {@code UseRight.activity} 的对应字段，一对一迁移时原样保留）
 * @param asset 实物资产种类；不得为 null
 * @param owner 所有权主体；不得为 null
 * @param operator 实际经营/使用主体；不得为 null
 * @param quantity 数量（非负；单位见 {@link AssetKind}）
 * @param kind 权利性质；不得为 null
 */
public record OwnershipStake(
    AssetShareId id,
    IndustryId industry,
    AssetKind asset,
    ActorRef owner,
    ActorRef operator,
    long quantity,
    RightKind kind) {

  /** 资产份额的权利性质（R3B.1 词表；沿用旧 {@code UseRight.RightKind}）。 */
  public enum RightKind {
    /** 自有（旧档一对一迁移/default 物化的默认档）。 */
    OWNED,
    /** 租佃（{@code owner != operator} 时的常见档；地租仍在 ProductionRules 结算）。 */
    TENANCY,
    /** 公地/共同使用。 */
    COMMUNAL
  }

  public OwnershipStake {
    if (id == null) {
      throw new IllegalArgumentException("OwnershipStake.id 不得为 null");
    }
    if (industry == null) {
      throw new IllegalArgumentException("OwnershipStake.industry 不得为 null");
    }
    if (asset == null) {
      throw new IllegalArgumentException("OwnershipStake.asset 不得为 null");
    }
    if (owner == null) {
      throw new IllegalArgumentException("OwnershipStake.owner 不得为 null");
    }
    if (operator == null) {
      throw new IllegalArgumentException("OwnershipStake.operator 不得为 null");
    }
    if (quantity < 0L) {
      throw new IllegalArgumentException("OwnershipStake.quantity 不得为负: " + quantity);
    }
    if (kind == null) {
      throw new IllegalArgumentException("OwnershipStake.kind 不得为 null");
    }
  }

  /**
   * ★★ <b>资产份额 id 的唯一拼写点</b>： {@code
   * share-<industry>-<asset>-<owner.kind>-<owner.id>-<operator.kind>-<operator.id>-<kind>-<sequence>}。
   *
   * <p>★ {@code sequence} 由状态内确定性计数给出（同一状态的重放/分支得到同一批 id；禁止随机数/时间戳/UUID）。 ★ <b>不含 {@code
   * "."}</b>：若任一段带点会与 {@code AddressParser} 的第一个点截断约定冲突 ⇒ 当场抛， 不静默造一个解析不到的地址。
   *
   * <p>★ <b>旧档 id 不重算</b>：{@code LegacyHouseholdMigration} 一对一搬运旧 {@code UseRight} 时直接沿用原 id 字符串
   * （{@link AssetShareId#parse(String)} 是 opaque 的），本工厂只服务新生成的份额。
   */
  public static AssetShareId idOf(
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      RightKind kind,
      long sequence) {
    if (industry == null) {
      throw new IllegalArgumentException("OwnershipStake.idOf 的 industry 不得为 null");
    }
    if (asset == null) {
      throw new IllegalArgumentException("OwnershipStake.idOf 的 asset 不得为 null");
    }
    if (owner == null) {
      throw new IllegalArgumentException("OwnershipStake.idOf 的 owner 不得为 null");
    }
    if (operator == null) {
      throw new IllegalArgumentException("OwnershipStake.idOf 的 operator 不得为 null");
    }
    if (kind == null) {
      throw new IllegalArgumentException("OwnershipStake.idOf 的 kind 不得为 null");
    }
    if (sequence < 0L) {
      throw new IllegalArgumentException("OwnershipStake.idOf 的 sequence 不得为负: " + sequence);
    }
    String value =
        "share-"
            + industry.value()
            + "-"
            + asset.name()
            + "-"
            + owner.kind()
            + "-"
            + owner.id()
            + "-"
            + operator.kind()
            + "-"
            + operator.id()
            + "-"
            + kind.name()
            + "-"
            + sequence;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException(
          "OwnershipStake id 不得含 '.'（地址 economy:<mapId>:assetShare.<id> 会被第一个点截断）: " + value);
    }
    return new AssetShareId(value);
  }
}
