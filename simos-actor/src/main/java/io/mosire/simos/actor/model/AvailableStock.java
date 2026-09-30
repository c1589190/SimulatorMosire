package io.mosire.simos.actor.model;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>可支配库存查询 —— 全仓唯一的那个算法</b>（M1.2）：
 *
 * <pre>
 * available(主体, 格, 资产) = 该本账上该资产的<b>余额 − 冻结额</b>        // 缺键 = 0；没有这本账 = 0
 * </pre>
 *
 * <p>★★ <b>它为什么必须只有一个拼写点</b>：M1.2 之前，读"某主体有多少可支配库存"有<b>六类路径</b>各自遍历 {@code
 * ActorData.accounts()}（生产侧会话副本的四条载入、GUI/MCP 读口、地址解析、播种、测试夹具）—— 谁想读就自己拆余额表、自己减。
 * 于是同一个算式散在调用方，改口径时没人能找全。⇒ 家户与经营者<b>走同一入口</b>：本类的四个重载最后都落到那一行减法上。
 *
 * <p>★★ <b>边界（用户 2026-09-27 裁定；与 {@link GoodsAccount} 的类注同一条，这里再点一次是因为它最容易被做歪）</b>：
 * 本算法<b>只减"已冻结"</b>这一项。那条例式里的另外两项 —— <b>必要生产投入</b>与<b>生活保留</b> —— 是<b>决策层的策略</b>， 按裁定落在 M2
 * 的订单/保留算式里，照
 *
 * <pre>
 * 可售库存 = max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留)   // 各项互不重复扣除
 * </pre>
 *
 * 逐项算。<b>不许</b>把 {@code MarketSettlement.MARKET_SELF_RESERVE_PER_MILLE} / {@code 旧结算引擎（R3a
 * 已删除）.LENDER_SUBSISTENCE_RESERVE_PER_MILLE} 塞进本类，也<b>不许</b>在这里按阶层/人口推一个保留额。
 *
 * <p>★ <b>这里没有 {@code max(0, …)}，是刻意的</b>：{@code GoodsAccount} 的构造期守卫已经把"{@code 0 ≤ 冻结 ≤ 余额}"钉成
 * 类型不变量 ⇒ 本减法的结果<b>必然 ≥ 0</b>。而那条例式里的 {@code max(0, …)} 属于 M2（那里还要再减两项，减成负数才需要截断）—— 在这里抄一个 {@code
 * max(0, …)} 只会把"守卫被绕过"这种真事故<b>掩盖成 0</b>（本仓最反对的"静默付 0"）。
 *
 * <p>★ <b>为什么是本类（一个静态助手），而不是 {@link GoodsAccount} 的实例方法</b>（形态由实现裁，理由记在这里）： 判据要的入口形状是 <b>(主体, 格,
 * 资产)</b> —— 它必须<b>先解析出那本账</b>；把"解析"与"减法"分住两个文件，就等于把这个概念劈成两半，
 * 读的人要跳两处才敢说"没有第二个算法"。故本类同时收<b>账本级</b>与<b>主体级</b>两个入口，减法只写一次。★ 反过来， {@code GoodsAccount}
 * 保持纯值类型（只有"是多少"、没有算式），与它类注里"写入口是整本覆盖、'转入 500'是命令不是状态类型的方法"同一条口径。
 *
 * <p>★ <b>没有这本账 ⇒ 0，不是抛</b>：这是<b>查询</b>口径 —— 读不到库存就是没有可支配库存（经营者账缺席是合法状态，见 {@code
 * OwnershipBooks.loadOperatorGoods} 的三条理由）。★ 而<b>落账</b>那一路的"该有账却没有 ⇒ 抛"（家户 side 的 fail-closed）
 * 是另一件事，仍在 {@code OwnershipBooks} 里，不因本类而放松。
 *
 * <p>★ <b>家户怎么走这个入口</b>：家户的账键同样是 {@code (owner, location)}，只是 owner 由 {@code
 * HouseholdActors.of(cohort)} 给出（家户 id 拼法的唯一拼写点）⇒ 调用方给 {@code (HouseholdActors.of(cohort),
 * cohort.hex())} 即可。★ 本类<b>不</b>提供 {@code CohortKey} 重载：那会在本切片里立起"家户 → 账键"的第二个拼法， 而它今天只该有一个（{@code
 * HouseholdActors} 与 app 侧的 {@code OwnershipBooks.accountKeyOf}）。
 */
public final class AvailableStock {

  private AvailableStock() {}

  /**
   * ★★ <b>全仓唯一的一处减法</b>（商品与货币共用它）：{@code 余额 − 冻结}，缺键按 0 算。
   *
   * <p>★ 泛型化成 {@code <A>} 是为了让<b>两张表共用同一行</b>：{@code CommodityId} 与 {@code CurrencyId} 是两个独立身份（裁定
   * M2），而"可支配 = 余额 − 冻结"这条算术对二者<b>逐字同款</b> ⇒ 写两遍就是同一个算式的第二处拼写点。
   */
  private static <A> long availableOf(Map<A, Long> balances, Map<A, Long> frozen, A asset) {
    return balances.getOrDefault(asset, 0L) - frozen.getOrDefault(asset, 0L);
  }

  /** 一本账上某商品的<b>可支配</b>数量（毫单位；余额 − 冻结）。 */
  public static long available(GoodsAccount account, CommodityId commodity) {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(commodity, "commodity");
    return availableOf(account.balances(), account.frozenBalances(), commodity);
  }

  /** 一本账上某币种的<b>可支配</b>金额（最小币值；余额 − 冻结）。 */
  public static long available(GoodsAccount account, CurrencyId currency) {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(currency, "currency");
    return availableOf(account.money(), account.frozenMoney(), currency);
  }

  /** ★ 主体级入口（商品）：{@code (owner, location)} 上那本账的可支配数量；没有这本账 ⇒ 0（见类注）。 */
  public static long available(
      ActorData books, ActorRef owner, HexCoord location, CommodityId commodity) {
    GoodsAccount account = accountOf(books, owner, location);
    return account == null ? 0L : available(account, commodity);
  }

  /** ★ 主体级入口（货币）：与上面那条**同一个入口**（家户与经营者都走它，见类注）。 */
  public static long available(
      ActorData books, ActorRef owner, HexCoord location, CurrencyId currency) {
    GoodsAccount account = accountOf(books, owner, location);
    return account == null ? 0L : available(account, currency);
  }

  /** 取账：键是 {@code (owner, location)}（本类里唯一的拼写点；解析不出 ⇒ null = 没有这本账）。 */
  private static GoodsAccount accountOf(ActorData books, ActorRef owner, HexCoord location) {
    Objects.requireNonNull(books, "books");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(location, "location");
    return books.accounts().get(new GoodsAccountKey(owner, location));
  }
}
