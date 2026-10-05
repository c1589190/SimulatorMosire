/**
 * ★★ <b>通用家户库存扣除契约</b>（2026-10-09 用户裁定）：传入「家户 + 扣除的库存 + 扣除理由」即可扣除；税收、行政俸禄、军队俸禄都只给政策和
 * reason，不再各写一套扣账机制。
 *
 * <p>形状与不变量见 {@link io.mosire.simos.economy.api.stock.HouseholdStockDeduction}；原因词表见 {@link
 * io.mosire.simos.economy.api.stock.DeductionReason}。本包只放<b>契约</b> —— 没有余额、没有落账口；
 * 家户/账户存在性、余额是否够、是否侵占冻结，由持账的一方（actor 切片的 {@code actor.DeductHouseholdStock} 命令、或 {@code
 * AccountSession} 的共享扣除服务）在落账前判。
 *
 * <p>★ <b>为什么在 {@code economy-api}</b>：它是经济切片共用的稳定契约（与 ID 层 / {@code Transfer} 同待遇）；它的主体是 {@code
 * social-api} 的 {@link io.mosire.simos.social.api.id.HouseholdId}、库存是 {@code economy-api} 自己的
 * {@code CommodityId} / {@code CurrencyId}。★ 不放 {@code actor-api}：那个模块主依赖为零、只放 actor 身份契约， 放了它就必须把
 * economy-api / social-api 拖进去（见 {@code simos-actor-api} 的类注）。
 */
package io.mosire.simos.economy.api.stock;
