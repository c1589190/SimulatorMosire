package io.mosire.simos.economy.api.money;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ★★ <b>「钱从哪来」的唯一闸门</b>（H4）：全系统<b>唯一</b>可以回答"这个币种归谁发行"的地方；回答不出来 ⇒ <b>当场抛</b> （fail-closed）。
 *
 * <p>★★ <b>本批的注册表是空的</b>（{@link #registered()} 恒为空表）：{@link MoneyAuthority} 在 H4 <b>没有任何实现</b> ⇒
 * <b>世界上没有"发行/回笼"这条路径</b>。这不是"还没接线"，而是本批<b>刻意</b>的状态： 只要有发行人，"Σ货币余额恒定"就不再成立，而本批要的正是那条恒等式 （判据 M-J3）。
 *
 * <p>★★ <b>它落在哪条链上</b>：货币腿的落账（{@code EconomySettlement.applyTransferToHouseholds}）在
 * <b>付方余额不足以支付</b>时调 {@link #requireIssuerOf(CurrencyId)} —— 因为"钱不够还照付"只有一种合法解释：付方是发行源
 * （它在发行）。本批没有发行源 ⇒ 这一支<b>必然抛</b>。⇒ 不变量可执行：
 *
 * <pre>
 * 除发行源外，任何账户的货币余额不得为负        （透支 = 发行，而发行这条路在本批恒抛）
 * ⇒ 没有发行人时，逐币种 Σ余额 恒定             （转移两端对冲：一方 −x、另一方 +x）
 * </pre>
 *
 * <p>★★ <b>M1.6 的限量（写在这里，因为它最容易被误读成总不变量）</b>：<b>没有</b>"全世界货币总量永远不变"这条 总不变量。可成立的守恒是<b>逐工具</b>的：
 *
 * <pre>
 * Σ该工具的持有账户 = 创世 + 累计发行 − 累计注销
 * </pre>
 *
 * 发行/注销都会让总量变；本批 {@code REGISTERED} 为空 ⇒ 发行 = 注销 = 0，上面那条等式才退化为 "逐币种 Σ持有恒定"。★
 * <b>将来真要做发行/注销时，累计量的登记点在本类（{@code REGISTERED} 旁，一处拼写点）</b> —— 不在读口、不在调用方另存一份（那会是"同一事实的第二处拼写"）。
 *
 * <p>★ <b>为什么注册表是一个常量而不是一张可变的全局表</b>：世界级的可变单例既不可重放、也不可分支（同一份存档在两个进程里会有两个 "谁是发行人"的答案）。本批零注册 ⇒ 表就是
 * {@code List.of()}；将来真有实现时，注册点是<b>这里</b>（一处拼写点）， 而不是散在各调用方的构造器里。
 */
public final class MoneyIssuance {

  /**
   * ★★ <b>本批**零注册**</b>：没有任何 {@link MoneyAuthority} 实现 ⇒ 没有任何币种可被发行。
   *
   * <p>★ 它的存在本身就是判据：把这一行改成非空，M-J1（"未注册发行人 ⇒ 发行尝试当场抛"）与 M-J3（逐币种守恒）都要重新论证。
   */
  private static final List<MoneyAuthority> REGISTERED = List.of();

  private MoneyIssuance() {}

  /** 已登记的发行人（**本批恒为空表**；保序、不可变）。 */
  public static List<MoneyAuthority> registered() {
    return REGISTERED;
  }

  /**
   * 全部<b>可被发行</b>的币种（= 已登记发行人各自 {@link MoneyAuthority#issuable()} 的并集）。
   *
   * <p>★ 本批恒为空集：一个字面量也没有。⇒ "世界上没有一条路径能造出钱"这句话在代码里可读、可断言。
   */
  public static Set<CurrencyId> issuable() {
    Set<CurrencyId> currencies = new LinkedHashSet<>();
    for (MoneyAuthority authority : REGISTERED) {
      currencies.addAll(authority.issuable());
    }
    return currencies;
  }

  /**
   * ★★ <b>唯一的发行查询</b>：这个币种的发行源是谁。<b>查不到 ⇒ 当场抛</b>（不是"返回 null 让调用方自己看着办"）。
   *
   * <p>★★ <b>谁会调它</b>：货币腿的落账在"付方余额不足"时 —— 那一刻唯一的合法解释是"付方在发行"， 而"谁是发行人"只有这里能回答。本批零注册 ⇒
   * 这个调用<b>必然抛</b>，于是"余额不得为负"这条守卫不依赖任何调用方的自觉。
   *
   * @param currency 币种；不得为 null
   * @return 发行主体（唯一一个可以透支该币种的主体）
   * @throws IllegalArgumentException 币种为 null
   * @throws IllegalStateException 没有任何已登记的发行人发行该币种（**本批的常态**：发行/回笼这条路不存在）
   */
  public static ActorRef requireIssuerOf(CurrencyId currency) {
    if (currency == null) {
      throw new IllegalArgumentException("CurrencyId 不得为 null（说不出币种就答不出发行人）");
    }
    for (MoneyAuthority authority : REGISTERED) {
      if (authority.issuable().contains(currency)) {
        return authority.authorityOf(currency);
      }
    }
    throw new IllegalStateException(
        "没有任何已登记的货币发行人发行 "
            + currency
            + "（H4：MoneyAuthority 在本批**没有实现** ⇒ 世界上没有'发行/回笼'这条路径）。"
            + "⇒ 付方余额不足以支付时不许透支（透支 = 发行），逐币种 Σ余额 因此恒定。"
            + "若确实需要发行，先实现 MoneyAuthority 并在 MoneyIssuance 登记它（唯一注册点）");
  }
}
