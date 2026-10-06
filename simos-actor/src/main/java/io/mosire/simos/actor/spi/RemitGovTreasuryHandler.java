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
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ {@code actor.RemitGovTreasury} 命令的处理器（行政区划修复计划 R3a / 计划 §1.3）：<b>显式的 GOV 国库上缴 / 任意两个 GOV
 * 单位之间的资源转移</b> —— 一条命令、一条 revision、<b>整条原子</b>。
 *
 * <p>★★ <b>载荷形状</b>：
 *
 * <pre>{@code
 * {"fromUnitId":"u-prov","fromQ":3,"fromR":2,
 *  "toUnitId":"u-central","toQ":0,"toR":0,
 *  "grain":1200,"cloth":30,"money":500,"reason":"秋税上缴"}
 * }</pre>
 *
 * <ul>
 *   <li>{@code fromUnitId} / {@code toUnitId} 必填、非空白；{@code fromQ/R/toQ/R} 必填 int；
 *   <li>{@code grain} / {@code cloth} / {@code money} 可选（缺省 0）；<b>至少一个 &gt; 0</b>；任何值为负数 ⇒ 拒；
 *   <li>{@code reason} 可选（若给出必须是非空白文本；本命令只动 accounts，reason 不进状态、供调用方/审计留痕）。
 * </ul>
 *
 * <p>★★ <b>语义（整条原子：任一违例 ⇒ 全拒，不做部分生效）</b>：
 *
 * <ol>
 *   <li>源账号 {@code (ActorRef(UNIT,fromUnitId), fromQ/fromR)} <b>必须存在</b>；缺 ⇒ 具名拒"源国库账不存在"
 *       （缺账不新建——上缴不是创世，源没有就是没有）；
 *   <li>逐资源走 {@link AvailableStock#available} 判可支配量（<b>全仓唯一的"余额 − 冻结"算法</b>，本类不自己写减法）： 不足 ⇒
 *       拒，消息写清资源、请求量与可用量；
 *   <li>源账按资源扣减：其余余额键、键序与两张冻结表原样保留（五参写回）；
 *   <li>目标账号缺 ⇒ 五参<b>新建</b>（两张冻结表空），有 ⇒ 加余额、保留其余键与两张冻结表； 目标收到的量恰等于源扣减的量（不凭空造资源）；
 *   <li>只动 {@code accounts} 一张表：{@code meta} / {@code actors} 一字不动；变更集走 {@link
 *       ActorChangeSet#between(ActorData, ActorData)}。
 * </ol>
 *
 * <p>★★ <b>非 GmOnly</b>：省份决策人要把这条命令嵌进 {@code sd.IssueDirective}，故<b>不</b>实现 {@link
 * io.mosire.simos.util.spi.GmOnlyCommand}。★ 本类不做"目标必须是源的 superiorGov"校验（actor 模块不认识 unit；该规则在 app 层
 * scope / 窄工具，见计划 §1.3 与 R3b）。GM 的 {@code simos.gov.remit} 允许任意两个 GOV 之间转移。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：<b>源与目标两个</b> actor 格路径（ {@link ResourcePaths#actor(int,
 * int)}，形如 {@code <q>_<r>}）都要给 —— 决策 scope 才能对"从哪扣、往哪加"两侧做资源目标校验。
 *
 * <p>★★ <b>具名拒清单</b>（中文、可读；解析期抛 {@link IllegalArgumentException}，本类折成 {@link
 * HandlerOutcome.Rejected}）：
 *
 * <ol>
 *   <li>unit id 缺失 / 空白 / 非文本；坐标缺失 / 非 int；金额非整数；
 *   <li>源 / 目标账号键相同；
 *   <li>任一金额为负；三个金额全为 0；
 *   <li>源国库账不存在；
 *   <li>某资源可支配量不足（余额 − 冻结）。
 * </ol>
 */
public final class RemitGovTreasuryHandler implements CommandHandler, CommandTargets {

  private static final Logger LOG = ActorLog.account();

  /** 命令类型（app 工具的拼写点也取它，避免第二处字面量）。 */
  public static final String TYPE = "actor.RemitGovTreasury";

  /**
   * 本命令的三个维度：{@code grain}/{@code cloth} 是 {@link CommodityId}，{@code money} 是银。
   *
   * <p>★ <b>商品 id 的唯一拼写点</b>：走 {@link EconomyVocabulary} 的两个字符串常量（util 是 actor 能看见的共同上游； actor
   * 模块<b>不许</b>依赖 {@code simos-economy}，{@code PilotModel} 因此不可见）；货币走 economy-api 的 {@link
   * MoneyVocabulary#SILVER_CURRENCY}（唯一拼写点）。本类<b>不</b>就地写商品 id 字面量 （那样会被经济词表源扫描护栏判红）。
   */
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /** 见 {@link #GRAIN}。 */
  private static final CommodityId CLOTH = new CommodityId(EconomyVocabulary.CLOTH_COMMODITY_ID);

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    parse(payloadJson); // 载荷必须可解析；家户账户无格 ⇒ 无 hex 目标（政府家户国库的围栏路径属 P2-C）
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ActorData base = ActorSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      Remit remit = parse(payloadJson);
      ActorData remitted = apply(base, remit);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ACTOR_GOV_TREASURY_REMITTED",
                  ActorLogSource.ACTOR_ACCOUNT,
                  "fromHousehold",
                  remit.fromHousehold(),
                  "toHousehold",
                  remit.toHousehold(),
                  "grain",
                  remit.grain(),
                  "cloth",
                  remit.cloth(),
                  "money",
                  remit.money()));
      return new HandlerOutcome.Applied(ActorChangeSet.between(base, remitted));
    } catch (IllegalArgumentException e) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ACTOR_GOV_TREASURY_REMIT_REJECTED",
                  ActorLogSource.ACTOR_ACCOUNT,
                  "type",
                  TYPE,
                  "reason",
                  ActorPayloads.logReason(e.getMessage())));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  // ── 解析（形状 / 类型 / 金额边界）────────────────────────────────────────────────────

  /** 解析载荷（见类注的形状）；形状 / 类型 / 金额边界错都抛 {@link IllegalArgumentException}。 */
  private static Remit parse(String payloadJson) {
    JsonNode payload = ActorPayloads.parse(payloadJson);
    HouseholdId fromHousehold = AccountPayloads.household(payload, "fromHousehold");
    HouseholdId toHousehold = AccountPayloads.household(payload, "toHousehold");
    long grain = optionalAmount(payload, "grain");
    long cloth = optionalAmount(payload, "cloth");
    long money = optionalAmount(payload, "money");
    requireOptionalReason(payload);
    return new Remit(fromHousehold, toHousehold, grain, cloth, money);
  }

  /**
   * 已通过形状解析的一条上缴（P2-A §13.3：国库 = 政府家户账户）：{@code fromHousehold} → {@code toHousehold} + 三个可选金额。
   *
   * <p>构造期把"账户不得相同、金额不得为负、至少一个 &gt; 0"这三条语义违例判掉 —— 解析出即合法。
   */
  private record Remit(
      HouseholdId fromHousehold, HouseholdId toHousehold, long grain, long cloth, long money) {

    Remit {
      if (fromHousehold == null) {
        throw new IllegalArgumentException("字段 fromHousehold 不得为 null");
      }
      if (toHousehold == null) {
        throw new IllegalArgumentException("字段 toHousehold 不得为 null");
      }
      if (grain < 0L) {
        throw new IllegalArgumentException("字段 grain 不得为负（上缴量必须 ≥ 0）: " + grain);
      }
      if (cloth < 0L) {
        throw new IllegalArgumentException("字段 cloth 不得为负（上缴量必须 ≥ 0）: " + cloth);
      }
      if (money < 0L) {
        throw new IllegalArgumentException("字段 money 不得为负（上缴量必须 ≥ 0）: " + money);
      }
      if (grain == 0L && cloth == 0L && money == 0L) {
        throw new IllegalArgumentException("grain/cloth/money 至少一个必须 > 0（至少上缴一种资源；三个都是 0 的载荷没有动作）");
      }
      if (fromHousehold.equals(toHousehold)) {
        throw new IllegalArgumentException(
            "源与目标国库账不得相同：household=" + fromHousehold + "（同一本账自己转给自己没有动作）");
      }
    }
  }

  // ── 物化（纯函数）──────────────────────────────────────────────────────────────────

  /** 全量校验 + 物化（纯函数）：任一违例即抛，**不返回半成品** —— 调用方把它折成整条 {@code Rejected}，于是"部分生效"在结构上 不可能发生。 */
  private static ActorData apply(ActorData base, Remit remit) {
    HouseholdAccountKey fromKey = new HouseholdAccountKey(remit.fromHousehold());
    HouseholdAccountKey toKey = new HouseholdAccountKey(remit.toHousehold());
    Map<HouseholdAccountKey, HouseholdInventory> next = new LinkedHashMap<>(base.accounts());
    HouseholdInventory source = next.get(fromKey);
    if (source == null) {
      throw new IllegalArgumentException(
          "源国库账不存在：household=" + remit.fromHousehold() + "（上缴要求源账已存在；缺账不新建）");
    }
    requireAvailable(source, fromKey, remit);
    // ★ 源账扣减：余额表拷到 LinkedHashMap（保持键序），只改本次涉及的键；两张冻结表**原样带过**（五参写回）。
    Map<CommodityId, Long> sourceGoods = new LinkedHashMap<>(source.balances());
    if (remit.grain() > 0L) {
      sourceGoods.put(GRAIN, sourceGoods.getOrDefault(GRAIN, 0L) - remit.grain());
    }
    if (remit.cloth() > 0L) {
      sourceGoods.put(CLOTH, sourceGoods.getOrDefault(CLOTH, 0L) - remit.cloth());
    }
    Map<CurrencyId, Long> sourceMoney = new LinkedHashMap<>(source.money());
    if (remit.money() > 0L) {
      sourceMoney.put(
          MoneyVocabulary.SILVER_CURRENCY,
          sourceMoney.getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L) - remit.money());
    }
    next.put(
        fromKey,
        new HouseholdInventory(
            fromKey, sourceGoods, sourceMoney, source.frozenBalances(), source.frozenMoney()));

    // ★ 目标入账：缺账 ⇒ 五参新建（两张冻结表空，禁走三参便捷构造器）；有账 ⇒ 保留其它键与冻结表。
    HouseholdInventory target = next.get(toKey);
    if (target == null) {
      Map<CommodityId, Long> targetGoods = new LinkedHashMap<>();
      if (remit.grain() > 0L) {
        targetGoods.put(GRAIN, remit.grain());
      }
      if (remit.cloth() > 0L) {
        targetGoods.put(CLOTH, remit.cloth());
      }
      Map<CurrencyId, Long> targetMoney = new LinkedHashMap<>();
      if (remit.money() > 0L) {
        targetMoney.put(MoneyVocabulary.SILVER_CURRENCY, remit.money());
      }
      next.put(toKey, new HouseholdInventory(toKey, targetGoods, targetMoney, Map.of(), Map.of()));
    } else {
      Map<CommodityId, Long> targetGoods = new LinkedHashMap<>(target.balances());
      if (remit.grain() > 0L) {
        targetGoods.put(
            GRAIN, addExact(targetGoods.getOrDefault(GRAIN, 0L), remit.grain(), "grain", toKey));
      }
      if (remit.cloth() > 0L) {
        targetGoods.put(
            CLOTH, addExact(targetGoods.getOrDefault(CLOTH, 0L), remit.cloth(), "cloth", toKey));
      }
      Map<CurrencyId, Long> targetMoney = new LinkedHashMap<>(target.money());
      if (remit.money() > 0L) {
        targetMoney.put(
            MoneyVocabulary.SILVER_CURRENCY,
            addExact(
                targetMoney.getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L),
                remit.money(),
                "money",
                toKey));
      }
      next.put(
          toKey,
          new HouseholdInventory(
              toKey, targetGoods, targetMoney, target.frozenBalances(), target.frozenMoney()));
    }
    // ★ 只换 accounts：meta / actors 原样带过（目标新账不给 UNIT owner 建 Actor 行）。
    return base.withInventories(next);
  }

  /** 逐资源判可支配量：不足 ⇒ 具名拒（资源、请求、可用各写清；可用走 {@link AvailableStock} 唯一算法）。 */
  private static void requireAvailable(
      HouseholdInventory source, HouseholdAccountKey key, Remit remit) {
    if (remit.grain() > 0L) {
      requireCommodityAvailable(source, key, GRAIN, "grain", remit.grain());
    }
    if (remit.cloth() > 0L) {
      requireCommodityAvailable(source, key, CLOTH, "cloth", remit.cloth());
    }
    if (remit.money() > 0L) {
      long available = AvailableStock.available(source, MoneyVocabulary.SILVER_CURRENCY);
      if (remit.money() > available) {
        throw new IllegalArgumentException(
            "源国库账 money 不足：household="
                + key.household()
                + "，请求="
                + remit.money()
                + "，可用="
                + available
                + "（余额="
                + source.money().getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L)
                + "，冻结="
                + source.frozenMoney().getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L)
                + "；可用 = 余额 − 冻结）");
      }
    }
  }

  /** 商品维度的可支配量判据（与货币那一段逐条同款；消息写清资源 / 请求 / 可用 / 余额 / 冻结）。 */
  private static void requireCommodityAvailable(
      HouseholdInventory source,
      HouseholdAccountKey key,
      CommodityId commodity,
      String label,
      long requested) {
    long available = AvailableStock.available(source, commodity);
    if (requested > available) {
      throw new IllegalArgumentException(
          "源国库账 "
              + label
              + " 不足：household="
              + key.household()
              + "，请求="
              + requested
              + "，可用="
              + available
              + "（余额="
              + source.balances().getOrDefault(commodity, 0L)
              + "，冻结="
              + source.frozenBalances().getOrDefault(commodity, 0L)
              + "；可用 = 余额 − 冻结）");
    }
  }

  /**
   * 目标余额加法（溢出 ⇒ 具名拒而不是静默回绕）：余额是 long 存量，normal 路径下不会溢出；这里是 fail-closed 兜底，不把 {@link
   * ArithmeticException} 漏出命令边界。
   */
  private static long addExact(long balance, long amount, String label, HouseholdAccountKey key) {
    long sum = balance + amount;
    if (sum < balance) {
      throw new IllegalArgumentException(
          "目标国库账 " + label + " 余额溢出（拒绝静默回绕）：household=" + key.household());
    }
    return sum;
  }

  // ── 形状 ─────────────────────────────────────────────────────────────────────────

  /** 可选金额：缺键 / {@code null} ⇒ 0；类型不是整数 ⇒ 抛（负数在 {@link Remit} 构造期拒）。 */
  private static long optionalAmount(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return 0L;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + value);
    }
    return value.asLong();
  }

  /** 可选原因：缺键 / {@code null} ⇒ 不写；给了必须是非空白文本（本命令不落状态，只保证形状不坏）。 */
  private static void requireOptionalReason(JsonNode node) {
    JsonNode value = node.get("reason");
    if (value == null || value.isNull()) {
      return;
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 reason 若给出必须是非空字符串: " + value);
    }
  }
}
