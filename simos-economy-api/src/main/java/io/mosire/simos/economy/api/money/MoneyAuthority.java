package io.mosire.simos.economy.api.money;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Set;

/**
 * ★★ <b>货币发行源 SPI</b>（H4；控制方冻结的形状）：<b>谁</b>有权凭空造出一种钱 ({@link #authorityOf(CurrencyId)})、
 * 它<b>能造哪些</b> ({@link #issuable()})。
 *
 * <p>★★ <b>它为什么要存在</b>：货币与商品在守恒上不是一回事 —— 商品的总量恒定是"造不出来"的物理事实， 而钱的总量恒定<b>只能靠制度</b>
 * （谁能发行、谁能回笼）。把这件事写成一个显式的接口，是为了让"钱从哪来"<b>只有一个答案</b>： 要么来自某个主体的余额（转移），要么来自这里 （发行）。★
 * 少了它，"凭空造钱"会以"某处少扣了一笔"的形式散落在结算里，而账面上完全看不出来。
 *
 * <p>★★ <b>本批（H4）不提供任何实现</b> ⇒ <b>世界上没有"发行/回笼"这条路径</b>；任何发行尝试由 {@link
 * MoneyIssuance#requireIssuerOf(CurrencyId)} 当场抛（fail-closed）。⇒ 逐币种守恒在本批的可执行形态是：
 *
 * <pre>
 * ① 除发行源外，任何账户的货币余额不得为负（唯一能透支的是发行人自己）；
 * ② 没有发行人 ⇒ Σ余额 逐币种恒定（转移两侧对冲、发行恒抛）。
 * </pre>
 *
 * <p>★★ <b>为什么它住 {@code simos-economy-api} 而不是控制方指定的 {@code simos-util/spi}</b>（一处如实的边界，理由是可编译性）：
 * 本接口的两个类型 —— {@link ActorRef} 住在 {@code simos-actor-api}、{@link CurrencyId} 住在 {@code
 * simos-economy-api} —— 而 {@code simos-util} 的模块纪律是"<b>不依赖任何 simos 模块</b>"（util 是所有人的下游）。 ⇒ 把本接口放进
 * util 会当场编不过（要么 util 反向依赖 economy-api/actor-api，要么把两个类型退化成 {@code String}）。 ★ 形制仍照 {@code Facet} /
 * {@code TimeParticipant} 那一族（纯接口、零实现、由组合根提供实现）， 只有<b>住处</b>不同：放在 <b>同时看得见这两个类型的最底层</b>（economy-api
 * 依赖 actor-api）。
 *
 * <p>★ <b>实现者（将来的批次）</b>：{@code authorityOf} 逐币种给出**唯一**的发行主体；{@code issuable} 是它能发行的币种集合 （集合外的币种 ⇒
 * 它<b>不是</b>发行人，余额照样不得为负）。★ 本批没有实现 ⇒ 任何"这个世界的货币发行人是谁"的问题都无解， 而正确的回答是<b>抛</b>，不是"随便找一个人"。
 */
public interface MoneyAuthority {

  /**
   * 该币种的**发行源**（唯一一个可以凭空收付这种钱的主体：发行让它余额变多、回笼让它变少）。
   *
   * @param currency 币种；不得为 null
   * @return 发行主体的 actor 引用；不得为 null（说不出"是谁发的"就不是一个发行人）
   * @throws IllegalArgumentException 币种不在 {@link #issuable()} 里（说不出"谁发的"）
   */
  ActorRef authorityOf(CurrencyId currency);

  /**
   * 这个发行人**能发行**的币种集合（保序、不可变）。
   *
   * <p>★ 集合之外的币种 ⇒ 它只是个普通持有者，余额照样不得为负（否则"谁能造钱"这条判据会被一个宽松的集合悄悄抹掉）。
   */
  Set<CurrencyId> issuable();
}
