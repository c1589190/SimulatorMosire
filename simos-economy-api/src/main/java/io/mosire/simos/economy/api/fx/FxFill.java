package io.mosire.simos.economy.api.fx;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★ <b>一笔外汇成交（读数量，不落盘）</b>（阶段 2-A2a；约束设计书 §3.2/§3.3/I20）。
 *
 * <p>★★ <b>每一笔 = 两条转移腿</b>：{@code baseMilli} 个 base 从 {@link #seller()} 到 {@link #buyer()}， {@code
 * quoteMilli} 个 quote 从买方到卖方 —— 两条腿都走唯一落账口 {@code EconomySettlement.applyTransfer}，
 * 因此<b>逐币种守恒</b>（I20）不是靠本类型保证的，而是靠"只有一条写路径"。
 *
 * <p>★ <b>为什么 {@code pricePerMille} 要单独记</b>（它能由两条腿算出来）：读口与日志要的是"这一笔按什么价成交" 这个<b>事实</b> —— 由 {@code
 * quoteMilli × 1000 / baseMilli} 现算会带上取整（与撮合时用的价可能差一毫），把成交价记下来才不会有第二份真相。
 *
 * @param day 成交的世界日
 * @param hex 成交落点格（家户单 = 该户所在格；窗口单 = 该户所在格，窗口的报价没有格）
 * @param regionId 成交所属市场区（"最近 N 笔<b>本区</b>成交"的过滤维）
 * @param base 标的币
 * @param quote 计价币
 * @param baseMilli base 腿的量（最小单位；&gt; 0）
 * @param quoteMilli quote 腿的量（最小单位；&gt; 0）
 * @param pricePerMille 成交价（per-mille：每 1000 个 base 最小单位付的 quote 最小单位；&gt; 0）
 * @param buyer 买入 base、付出 quote 的一方
 * @param seller 卖出 base、收进 quote 的一方
 * @param venue 场所（家户 ↔ 家户 / 政府窗口）
 */
public record FxFill(
    long day,
    HexCoord hex,
    String regionId,
    CurrencyId base,
    CurrencyId quote,
    long baseMilli,
    long quoteMilli,
    long pricePerMille,
    ActorRef buyer,
    ActorRef seller,
    FxVenue venue) {

  public FxFill {
    Objects.requireNonNull(hex, "FxFill.hex 不得为 null");
    Objects.requireNonNull(base, "FxFill.base 不得为 null");
    Objects.requireNonNull(quote, "FxFill.quote 不得为 null");
    Objects.requireNonNull(buyer, "FxFill.buyer 不得为 null");
    Objects.requireNonNull(seller, "FxFill.seller 不得为 null");
    Objects.requireNonNull(venue, "FxFill.venue 不得为 null（官方拉动 vs 市场自定必须分得清）");
    if (day < 0L) {
      throw new IllegalArgumentException("FxFill.day 不得为负: " + day);
    }
    if (base.equals(quote)) {
      throw new IllegalArgumentException("FxFill 的 base 与 quote 不得是同一种钱: " + base.value());
    }
    if (baseMilli <= 0L || quoteMilli <= 0L) {
      throw new IllegalArgumentException(
          "两条腿都必须为正（一次兑换必产生两条腿，I2）: base=" + baseMilli + " quote=" + quoteMilli);
    }
    if (pricePerMille <= 0L) {
      throw new IllegalArgumentException("FxFill.pricePerMille 必须 > 0: " + pricePerMille);
    }
    if (buyer.equals(seller)) {
      throw new IllegalArgumentException("FxFill 的两端不得是同一个主体（自成交不是成交）: " + buyer);
    }
    regionId = regionId == null ? "" : regionId;
  }

  /** 是不是政府窗口那一侧的成交（读口判"官方是否拉动市场价"用）。 */
  public boolean windowInvolved() {
    return venue == FxVenue.GOV_WINDOW;
  }
}
