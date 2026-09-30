package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code actor.AdjustAccounts} 命令的处理器（辖区阶段 6 / 计划 §6.2）：**按有符号净增量改 actor 账** —— 一条命令、 一条
 * revision、<b>整条原子</b>。
 *
 * <p>★★ <b>载荷形状（本原语定稿）</b>：
 *
 * <pre>{@code
 * {"entries":[
 *   {"owner":{"kind":"HOUSEHOLD","id":"hh-1"},"q":0,"r":0,
 *    "goods":{"grain":-120,"cloth":5},"money":{"silver":-30}},
 *   {"owner":{"kind":"UNIT","id":"u-1"},"q":3,"r":2,"goods":{"grain":120},"money":{"silver":30}}
 * ]}
 * }</pre>
 *
 * <ul>
 *   <li>{@code entries} 必填、非空数组；每项 {@code owner{kind,id}}、{@code q}、{@code r} 必填；
 *   <li>{@code goods} / {@code money} <b>至少一个非空</b>（缺省 / {@code null} = 空表）：键 = 商品 id / 币种 id，值 =
 *       <b>有符号净增量</b>（long，<b>0 不得出现</b>，无操作条目请删）；
 *   <li>{@code (owner, 格)} 就是 {@link GoodsAccountKey} 的 {@code (owner, location)}：增量打在**那一本账**上。
 * </ul>
 *
 * <p>★★ <b>数值语义（整条原子：任一违例 ⇒ 全拒，不做部分生效）</b>：
 *
 * <ul>
 *   <li><b>已有账</b> ⇒ 在 {@code LinkedHashMap} 拷贝上逐键 upsert：原有键序与两张<b>冻结表</b>原样保留；结果余额为 0
 *       <b>保留、不归一</b>（0 是"现在手里是 0"，与"没有这一条"不是同一件事）；
 *   <li><b>缺账 + 纯正增量</b> ⇒ **新建**该账（{@link GoodsAccount} 五参构造：其余商品 / 货币 / 冻结表空）；
 *   <li><b>缺账 + 任何负增量</b> ⇒ 拒；
 *   <li><b>负增量使余额 &lt; 0</b> ⇒ 拒；<b>负增量侵占冻结额</b> ⇒ 拒（可支配 = 余额 − 冻结，走 {@link AvailableStock}
 *       的<b>唯一算法</b>，本类不自己写减法）；
 *   <li>只动 {@code accounts} 一张表：{@code meta} / {@code actors} <b>一字不动</b>（<b>不给 UNIT owner 建 {@code
 *       Actor} 行</b>——{@link ActorData} 明确允许 {@code accounts} 的 owner 不在 {@code actors}
 *       里，存在性由装配期与调用方负责）。
 * </ul>
 *
 * <p>★★ <b>具名拒清单</b>（中文、可读、带 owner / 位置 / 维度 / 数字；解析层抛 {@link IllegalArgumentException}，本类折成 {@link
 * HandlerOutcome.Rejected}）：
 *
 * <ol>
 *   <li>{@code entries} 缺失、不是数组、或为空；
 *   <li>某条不是对象；或缺 {@code owner} / {@code q} / {@code r}；{@code kind} / {@code id} 非法；
 *   <li>{@code goods} / {@code money} 至少一个非空不成立；某张表的键不是合法 id、值不是整数；
 *   <li>{@code goods} / {@code money} 的值为 0；
 *   <li>同一 {@code (owner, q, r)} 在一条载荷里重复；
 *   <li>缺账 + 任何负增量；
 *   <li>负增量使余额 &lt; 0；
 *   <li>负增量侵占冻结额（可用 = 余额 − 冻结）。
 * </ol>
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：{@code entries[]} 里**每一个**格资源 {@link
 * ResourcePaths#actor(int, int)}，经 {@link ActorPayloads#entryHexKeys} 这个与 {@code actor.Seed}
 * <b>共用</b>的拼写点（两处各拼一份 ⇒ 权限围栏两侧会漂）。
 *
 * <p>★★ <b>GM-only（辖区阶段 6）</b>：实现 {@link GmOnlyCommand} —— 本命令是给组合工具用的<b>裸账目原语</b>， 上限 / 管辖 /
 * 人力口径都在 {@code simos.unit.levyRegion} 里；若它能被嵌进决策令，那些上限就被绕过了。 标记只禁"嵌入令 / {@code RegisterEffect} /
 * 决策人 catalog"三条路：命令仍照常注册、仍进 {@link CommandTargets}， GM 的 {@code simos.command.submit} 与 {@code
 * actor.AdjustAccounts} 窄工具照常可调。
 *
 * <p>★ <b>校验分工</b>：形状 / 类型 / 词表 / 0 增量 / 重复在 {@link ActorPayloads#adjustments} 判；余额与冻结语义在本类判；
 * <b>装配故障</b>（state 里没有 actor 切片）当场炸、不走拒绝路径（见 {@link ActorSnapshots}）。
 */
public final class AdjustAccountsHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  @Override
  public String type() {
    return "actor.AdjustAccounts";
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    return ActorPayloads.entryHexKeys(ActorPayloads.parse(payloadJson));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ActorData base = ActorSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = ActorPayloads.parse(payloadJson);
      List<ActorPayloads.AccountAdjustment> entries = ActorPayloads.adjustments(payload);
      ActorData adjusted = apply(base, entries);
      return new HandlerOutcome.Applied(ActorChangeSet.between(base, adjusted));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 全量校验 + 物化（纯函数）：任一条目违例即抛，**不返回半成品** —— 调用方把它折成整条 {@code Rejected}，于是"部分生效"在结构上 不可能发生。 */
  private static ActorData apply(ActorData base, List<ActorPayloads.AccountAdjustment> entries) {
    Map<GoodsAccountKey, GoodsAccount> next = new LinkedHashMap<>(base.accounts());
    for (ActorPayloads.AccountAdjustment entry : entries) {
      GoodsAccountKey key = new GoodsAccountKey(entry.owner(), entry.location());
      GoodsAccount account = next.get(key);
      if (account == null) {
        requireNoNegativeForMissingAccount(entry); // 缺账：任何负增量拒绝（0 已在解析期拒）
        next.put(key, new GoodsAccount(key, entry.goods(), entry.money(), Map.of(), Map.of()));
        continue;
      }
      Map<CommodityId, Long> balances = new LinkedHashMap<>(account.balances());
      for (Map.Entry<CommodityId, Long> delta : entry.goods().entrySet()) {
        applyCommodityDelta(account, balances, entry, delta.getKey(), delta.getValue());
      }
      Map<CurrencyId, Long> money = new LinkedHashMap<>(account.money());
      for (Map.Entry<CurrencyId, Long> delta : entry.money().entrySet()) {
        applyMoneyDelta(account, money, entry, delta.getKey(), delta.getValue());
      }
      // ★ 五参写回：两张冻结表**原样带过**（用三参便捷构造器会把已有冻结静默清零）。
      next.put(
          key,
          new GoodsAccount(key, balances, money, account.frozenBalances(), account.frozenMoney()));
    }
    // ★ 只换 accounts：meta / actors 原样带过（不给 owner 建 Actor 行）。
    return base.withAccounts(next);
  }

  /** 缺账 + 任何负增量 ⇒ 拒（纯正增量允许，见类注的"新建"）。 */
  private static void requireNoNegativeForMissingAccount(ActorPayloads.AccountAdjustment entry) {
    for (Map.Entry<CommodityId, Long> delta : entry.goods().entrySet()) {
      requireNotNegative(delta.getValue(), entry, "商品", delta.getKey().toString());
    }
    for (Map.Entry<CurrencyId, Long> delta : entry.money().entrySet()) {
      requireNotNegative(delta.getValue(), entry, "货币", delta.getKey().toString());
    }
  }

  private static void requireNotNegative(
      long delta, ActorPayloads.AccountAdjustment entry, String dimension, String asset) {
    if (delta < 0L) {
      throw new IllegalArgumentException(
          "缺账 + 负增量：owner="
              + entry.owner()
              + "，格 "
              + hex(entry.location())
              + "，"
              + dimension
              + " "
              + asset
              + " 增量="
              + delta
              + "（该 (owner,格) 上还没有这本账；缺账只允许纯正增量新建）");
    }
  }

  /** 记一笔商品净增量：负增量先判"结果 ≥ 0"、再判"不侵占冻结额"（两个拒因分开点名）。 */
  private static void applyCommodityDelta(
      GoodsAccount account,
      Map<CommodityId, Long> balances,
      ActorPayloads.AccountAdjustment entry,
      CommodityId commodity,
      long delta) {
    long balance = balances.getOrDefault(commodity, 0L);
    if (delta < 0L) {
      requireDeltaDoesNotBreakInvariants(
          entry,
          "商品",
          commodity.toString(),
          balance,
          account.frozenBalances().getOrDefault(commodity, 0L),
          AvailableStock.available(account, commodity),
          delta);
    }
    balances.put(commodity, balance + delta);
  }

  /** 记一笔货币净增量：口径与 {@link #applyCommodityDelta} 逐条同款（商品与货币是两个独立身份、同一套算术）。 */
  private static void applyMoneyDelta(
      GoodsAccount account,
      Map<CurrencyId, Long> money,
      ActorPayloads.AccountAdjustment entry,
      CurrencyId currency,
      long delta) {
    long balance = money.getOrDefault(currency, 0L);
    if (delta < 0L) {
      requireDeltaDoesNotBreakInvariants(
          entry,
          "货币",
          currency.toString(),
          balance,
          account.frozenMoney().getOrDefault(currency, 0L),
          AvailableStock.available(account, currency),
          delta);
    }
    money.put(currency, balance + delta);
  }

  /**
   * 一笔负增量的两条具名拒（顺序刻意：先"余额成负"、再"侵占冻结"，两条拒因指向不同的纠正动作）：
   *
   * <ul>
   *   <li>{@code 余额 + 增量 < 0} ⇒ 余额拒（把结果一起报出来）；
   *   <li>{@code 可支配 = 余额 − 冻结}（{@link AvailableStock} 的唯一算法）不够这次扣 ⇒ 冻结拒。
   * </ul>
   */
  private static void requireDeltaDoesNotBreakInvariants(
      ActorPayloads.AccountAdjustment entry,
      String dimension,
      String asset,
      long balance,
      long frozen,
      long available,
      long delta) {
    long next = balance + delta;
    if (next < 0L) {
      throw new IllegalArgumentException(
          "负增量使余额 < 0：owner="
              + entry.owner()
              + "，格 "
              + hex(entry.location())
              + "，"
              + dimension
              + " "
              + asset
              + " 余额="
              + balance
              + "，增量="
              + delta
              + "（结果 "
              + next
              + "）");
    }
    if (delta < -available) {
      throw new IllegalArgumentException(
          "负增量侵占冻结额：owner="
              + entry.owner()
              + "，格 "
              + hex(entry.location())
              + "，"
              + dimension
              + " "
              + asset
              + " 余额="
              + balance
              + "，冻结="
              + frozen
              + "，可支配="
              + available
              + "，增量="
              + delta
              + "（可支配 = 余额 − 冻结，冻结部分不可动用）");
    }
  }

  /** 格的键：{@code <q>_<r>}（**只经** {@link ResourcePaths#actor}，本类不再有第二个拼写点）。 */
  private static String hex(HexCoord coord) {
    return ResourcePaths.actor(coord.q(), coord.r());
  }
}
