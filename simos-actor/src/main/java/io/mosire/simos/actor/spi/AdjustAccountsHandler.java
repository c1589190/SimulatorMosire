package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorLog;
import io.mosire.simos.actor.ActorLogSource;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
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
import org.slf4j.Logger;

/**
 * ★★ {@code actor.AdjustAccounts} 命令的处理器（辖区阶段 6 / 计划 §6.2）：**按有符号净增量改家户账** —— 一条命令、 一条
 * revision、<b>整条原子</b>。
 *
 * <p>★★ <b>载荷形状（本原语定稿；逐字段对应 {@link ActorPayloads#adjustments} 的解析）</b>：
 *
 * <pre>{@code
 * {"entries":[
 *   {"household":"hh-1","goods":{"grain":-120,"cloth":5},"money":{"silver":-30}},
 *   {"household":"hh-2","goods":{"grain":120},"money":{"silver":30}}
 * ]}
 * }</pre>
 *
 * <ul>
 *   <li>{@code entries} 必填、非空数组：每项 = 一本家户账的净增量（一条命令可含多户，仍只落一条 revision）；
 *   <li>每项是 {@code {household, goods?, money?}} 对象 —— P2-A §13.3 起账户主体<b>只有家户</b>，故<b>没有</b> {@code
 *       owner{kind,id}}，也<b>没有</b> {@code q} / {@code r}；
 *   <li>{@code household} 必填、字符串、合法家户 id（走 {@code AccountPayloads.household}，词表在 {@code
 *       HouseholdId.parse}）；同一 {@code household} 在一份载荷里<b>只能出现一次</b>；
 *   <li>{@code goods} / {@code money} 是两张 {@code {"商品/币种 id": 有符号整数}} 表，<b>至少一个非空</b>（缺键 / {@code
 *       null} = 空表；两个都空 = 这条没有任何动作，拒）：键走 {@code CommodityId.parse} / {@code CurrencyId.parse} （词表在
 *       ID 类型里），值 = <b>有符号净增量</b>（long，<b>0 不得出现</b>，无操作条目请删）；
 *   <li>{@code household} 就是 {@link HouseholdAccountKey} 的<b>全部内容</b>：增量打在**该家户那一本账**上；格不写进载荷
 *       （从家户的位置派生 ⇒ 家户搬家账自动跟走）。
 * </ul>
 *
 * <p>★★ <b>数值语义（整条原子：任一违例 ⇒ 全拒，不做部分生效）</b>：
 *
 * <ul>
 *   <li><b>已有账</b> ⇒ 在 {@code LinkedHashMap} 拷贝上逐键 upsert：原有键序与两张<b>冻结表</b>原样保留；结果余额为 0
 *       <b>保留、不归一</b>（0 是"现在手里是 0"，与"没有这一条"不是同一件事）；
 *   <li><b>缺账 + 纯正增量</b> ⇒ **新建**该账（{@link HouseholdInventory} 五参构造：其余商品 / 货币 / 冻结表空）；
 *   <li><b>缺账 + 任何负增量</b> ⇒ 拒；
 *   <li><b>负增量使余额 &lt; 0</b> ⇒ 拒；<b>负增量侵占冻结额</b> ⇒ 拒（可支配 = 余额 − 冻结，走 {@link AvailableStock}
 *       的<b>唯一算法</b>，本类不自己写减法）；
 *   <li>只动 {@code accounts} 一张表：{@code meta} / {@code actors} <b>一字不动</b>（<b>不给 owner（家户）建 {@code
 *       Actor} 行</b>——{@link ActorData} 明确允许 {@code accounts} 的 owner 不在 {@code actors}
 *       里，存在性由装配期与调用方负责）。
 * </ul>
 *
 * <p>★★ <b>具名拒清单</b>（中文、可读、带家户 / 维度 / 数字；解析层抛 {@link IllegalArgumentException}，本类折成 {@link
 * HandlerOutcome.Rejected}）：
 *
 * <ol>
 *   <li>{@code entries} 缺失、不是数组、或为空；
 *   <li>某条不是对象；或缺 {@code household}（不是字符串 / 家户 id 词表不合法）；
 *   <li>{@code goods} / {@code money} 至少一个非空不成立；某张表不是对象、键不是合法 id、值不是整数；
 *   <li>{@code goods} / {@code money} 的值为 0；
 *   <li>同一 {@code household} 在一条载荷里重复；
 *   <li>缺账 + 任何负增量；
 *   <li>负增量使余额 &lt; 0；
 *   <li>负增量侵占冻结额（可用 = 余额 − 冻结）。
 * </ol>
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：<b>家户账户无格 ⇒ {@code targetPaths} 恒为空列表</b>（本命令不动任何 hex
 * 资源；账户键只有家户，格由家户的位置派生），{@code targetResources} 走默认实现 ⇒ 目标清单同样为空（空 = 本命令没有可寻址目标）。 {@link
 * ActorPayloads#entryHexKeys}（{@link ResourcePaths#actor(int, int)} 那条逐格拼写）是 {@code actor.Seed}
 * 载荷的路径， <b>本命令不经过它</b>——本命令的载荷里没有 {@code q} / {@code r}。
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

  private static final Logger LOG = ActorLog.account();

  @Override
  public String type() {
    return "actor.AdjustAccounts";
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    ActorPayloads.parse(payloadJson); // 载荷必须可解析；家户账户无格 ⇒ 无 hex 目标
    return List.of();
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
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ACTOR_ACCOUNTS_ADJUSTED",
                  ActorLogSource.ACTOR_ACCOUNT,
                  "entries",
                  entries.size(),
                  "accountsBefore",
                  base.accounts().size(),
                  "accountsAfter",
                  adjusted.accounts().size()));
      return new HandlerOutcome.Applied(ActorChangeSet.between(base, adjusted));
    } catch (IllegalArgumentException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ACTOR_ACCOUNTS_ADJUST_REJECTED",
                  ActorLogSource.ACTOR_ACCOUNT,
                  "type",
                  type(),
                  "reason",
                  ActorPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 全量校验 + 物化（纯函数）：任一条目违例即抛，**不返回半成品** —— 调用方把它折成整条 {@code Rejected}，于是"部分生效"在结构上 不可能发生。 */
  private static ActorData apply(ActorData base, List<ActorPayloads.AccountAdjustment> entries) {
    Map<HouseholdAccountKey, HouseholdInventory> next = new LinkedHashMap<>(base.accounts());
    for (ActorPayloads.AccountAdjustment entry : entries) {
      HouseholdAccountKey key = new HouseholdAccountKey(entry.household());
      HouseholdInventory inventory = next.get(key);
      if (inventory == null) {
        requireNoNegativeForMissingInventory(entry); // 缺账：任何负增量拒绝（0 已在解析期拒）
        if (LOG.isDebugEnabled()) {
          EventLog.channel(LOG)
              .debug(
                  LogEvent.of(
                      "ACTOR_ACCOUNTS_ADJUST_NEW_ACCOUNT",
                      ActorLogSource.ACTOR_ACCOUNT,
                      "household",
                      entry.household(),
                      "goods",
                      entry.goods().size(),
                      "money",
                      entry.money().size()));
        }
        next.put(
            key, new HouseholdInventory(key, entry.goods(), entry.money(), Map.of(), Map.of()));
        continue;
      }
      Map<CommodityId, Long> balances = new LinkedHashMap<>(inventory.balances());
      for (Map.Entry<CommodityId, Long> delta : entry.goods().entrySet()) {
        applyCommodityDelta(inventory, balances, entry, delta.getKey(), delta.getValue());
      }
      Map<CurrencyId, Long> money = new LinkedHashMap<>(inventory.money());
      for (Map.Entry<CurrencyId, Long> delta : entry.money().entrySet()) {
        applyMoneyDelta(inventory, money, entry, delta.getKey(), delta.getValue());
      }
      // ★ 五参写回：两张冻结表**原样带过**（用三参便捷构造器会把已有冻结静默清零）。
      next.put(
          key,
          new HouseholdInventory(
              key, balances, money, inventory.frozenBalances(), inventory.frozenMoney()));
    }
    // ★ 只换 accounts：meta / actors 原样带过（不给 owner 建 Actor 行）。
    return base.withInventories(next);
  }

  /** 缺账 + 任何负增量 ⇒ 拒（纯正增量允许，见类注的"新建"）。 */
  private static void requireNoNegativeForMissingInventory(ActorPayloads.AccountAdjustment entry) {
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
          "缺账 + 负增量：household="
              + entry.household()
              + "，"
              + dimension
              + " "
              + asset
              + " 增量="
              + delta
              + "（该家户还没有这本账；缺账只允许纯正增量新建）");
    }
  }

  /** 记一笔商品净增量：负增量先判"结果 ≥ 0"、再判"不侵占冻结额"（两个拒因分开点名）。 */
  private static void applyCommodityDelta(
      HouseholdInventory inventory,
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
          inventory.frozenBalances().getOrDefault(commodity, 0L),
          AvailableStock.available(inventory, commodity),
          delta);
    }
    balances.put(commodity, balance + delta);
  }

  /** 记一笔货币净增量：口径与 {@link #applyCommodityDelta} 逐条同款（商品与货币是两个独立身份、同一套算术）。 */
  private static void applyMoneyDelta(
      HouseholdInventory inventory,
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
          inventory.frozenMoney().getOrDefault(currency, 0L),
          AvailableStock.available(inventory, currency),
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
          "负增量使余额 < 0：household="
              + entry.household()
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
          "负增量侵占冻结额：household="
              + entry.household()
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
}
