package io.mosire.simos.economy.api.classes;

/**
 * ★★★ <b>D-032 C1：家户阶级的二元公开词表</b>（2026-10-21 冻结）。
 *
 * <p>★★ <b>它不是持久身份</b>：{@code ownershipClass} 只由家户当刻持有的生产资料派生， 不写进 {@code
 * EconomyData}/ChangeSet/Codec/快照，也不由 productionMode/currentPosition 派生。
 *
 * <p>判定输入（跨切片只读事实）：
 *
 * <ul>
 *   <li>LAND：Map transient ledger / owner 视图里该户 Σ quantityMilliMu &gt; 0；
 *   <li>运输马匹等耐久商品资本：actor 家户商品库存里已登记的资本商品 &gt; 0（C5 接入 {@code transport_horse}）；
 *   <li>economy {@code assetShares} 中该户作为 owner 的非 LAND 正数量份额。
 * </ul>
 *
 * <p>普通消费/投入商品库存、现金、operator 但非 owner 的份额都不计入。生产方式切换不改本值。
 *
 * <p>★★ <b>唯一拼写点</b>：production / economy / app 不得再拼 {@code "landed"} / {@code "landless"} 这类第二套
 * 家户阶级字符串；对外规范名就是 {@link #name()}。
 */
public enum OwnershipClass {
  /** 有产者：任一正数量生产资料（LAND / 运输马匹等耐久商品资本 / 非 LAND 耐久资产份额）。 */
  OWNER,
  /** 无产者：上述全部为 0；只能进入工资位置。 */
  PROLETARIAN
}
