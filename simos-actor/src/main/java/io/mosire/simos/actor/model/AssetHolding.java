package io.mosire.simos.actor.model;

/**
 * ★★ <b>产权：某人持有某格上某一类资产的余额</b>（spec §2.3）。<b>独立概念，不是 {@link Actor} 的字段</b> —— "资产是 Actor
 * <b>拥有的关系</b>，不是 Actor <b>本体的一部分</b>"：卖掉 30% 土地只改这里，不用打开整个 Actor aggregate。故本类型与 {@link Actor}
 * 之间只有 {@code owner} 这一条引用，没有嵌套。
 *
 * <p>★★ <b>默认 fungible aggregate，例外才 Entity</b>（spec §2.3）：格已经提供了空间边界 ⇒ 同格内同质的生产资料
 * 作<b>可分割余额</b>（{@code quantity}），而不是一条条资产实体。只有不可分割、需独立历史的（大矿井 / 铁路枢纽 / 港口 / 特殊工厂）才实体化 ——
 * 本阶段不做，也不假装做了。
 *
 * <p>★ <b>为什么没有 {@code id}</b>（计划 §Task 5 的裁定）：spec 的 {@code AssetHolding { id, owner, location,
 * assetKey, quantity }} 里那个 {@code id} <b>不要</b> —— 身份就是聚合键 {@link #key()}（I2.2 前半句）， 另造一个 id
 * 等于把"同一事实"记两处（铁律 1：id 是身份，不在切片里另造同义 ID）。
 *
 * <p>★★ <b>{@code quantity} 是余额，不是增量</b>：故<b>负数当场抛</b>（持有量不可能为负），而 <b>0 合法</b>—— "有这份产权、只是数量为
 * 0"与"没有这份产权"是<b>两件事</b>（同 {@code GoodsAccount} 的"存量不是空表"口径）。 ★ 写入口 {@code ActorData.withHolding}
 * 的语义因此是"<b>该余额是多少</b>"（后写覆盖前写），不是"加多少"。
 *
 * <p>★ <b>佃制的形状</b>（spec §2.3）：同一格同一类地可以是两条 —— {@code ESTATE} 持 10000、 {@code HOUSEHOLD} 持
 * 1000，两条各是各的余额，互不牵连。
 *
 * @param key 聚合键（{@code (owner, location, assetKey)}）
 * @param quantity 余额（≥ 0；单位由 {@link #key()} 的 {@code assetKey} 决定）
 */
public record AssetHolding(AssetHoldingKey key, long quantity) {

  public AssetHolding {
    if (key == null) {
      throw new IllegalArgumentException("AssetHolding.key 不得为 null");
    }
    if (quantity < 0) {
      throw new IllegalArgumentException("AssetHolding.quantity 是余额、不得为负: " + quantity);
    }
  }
}
