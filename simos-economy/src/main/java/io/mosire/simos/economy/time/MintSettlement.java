package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.MoneyOutput;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.time.AccountSession.ActorAccount;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★★ <b>W5（2026-10-11）：铸币生产方式（用户裁定 C-4 的落地）；W5-fix5：实铸上限 = 政策旋钮本身（"不夹"）</b>。
 *
 * <p>★★ <b>它取代了什么（逐条点名，旧设计按 §一.11 直接删）</b>：
 *
 * <ul>
 *   <li>{@code GovernmentSeigniorage}（周期开始把 {@code seignioragePerCycle} <b>直接加进国库余额</b> + 写一条
 *       {@code FISCAL_ISSUE}）—— <b>整类删除</b>。那正是用户说的"印钱绕过市场"的旁路；
 *   <li>{@code GovernmentDebtIssuance}（周期开始按 {@code debtIssuePerCycle} 向家户<b>借</b>钱）—— <b>整类删除</b>。
 *       它是与铸币并列的第二条财政轨道（双轨），本批<b>合并成一条</b>；
 *   <li>{@code Government.seignioragePerCycle} / {@code Government.debtIssuePerCycle} ——
 *       <b>两个字段删除</b>， 只留 {@code mintScalePerCycle}（政策旋钮）。
 * </ul>
 *
 * <p>★★ <b>钱是怎么来的（本类唯一的产出算式）</b>：货币<b>与粮/布/工具同一条生产路径</b> —— 它挂在 {@link
 * Industry#moneyOutputPerUnit()}（{@link MoneyOutput}）上，由 {@code EconomySettlement} 在<b>产业周期关账</b>时
 * 按<b>同一个规模</b>收获：
 *
 * <pre>
 * 毛产出(币种 c) = scale × moneyOutputPerUnit.perUnit[c]        // 毫；scale = 关账处求好的规模
 * 规模 0（或产出率 0、或政策旋钮 0） ⇒ 毛产出恰好 0 毫        // 判据 ②：货币总量守恒
 * </pre>
 *
 * <p>投入侧（劳动 + 工具/金属等）<b>不走本类</b>：它是既有的周期投入管线（{@code drawCycleInputs} + {@code
 * ProductionEfficiencyBook.evaluateHarvest}）—— 本类只消费<b>已经求好的 scale</b>，因此"投入真实消耗、产出 = 货币"
 * 这两半在结构上不可能漂开（同一份 {@code ProductionProcess.cycleInputUsedMilli()} 与同一个 scale）。
 *
 * <p>★★★ <b>实铸上限 = 政策旋钮本身（W5-fix5，2026-10-11）</b>：用户原话「<b>没事没事，铸币能进政府的家户，按照决策人预期被发行就行</b>」 ⇒
 * **决策人预期即权威**，不再因"超过上一轮印量"拒发（设计书 v3.18 §7）。
 *
 * <pre>
 * 上一铸币轮日(政府) = max{ day | MoneyIssuanceRecord{ kind == MINT_PRODUCTION, 同政府, day &lt; 本轮 day } }
 * 上一轮印量(政府)   = Σ 落在"上一铸币轮日"的全部 MINT_PRODUCTION 金额（同政府，跨币种求和）—— ★ **只作读数**
 * 本轮可铸上限       = 政策旋钮 mintScalePerCycle（**唯一闸门**；旋钮 0 ⇒ 0 毫）
 * 实际铸币           = min(毛产出, 上限)
 * 超限 ⇒ 具名拒（INFO，写清 limit / previousCycleMinted / requested / rejected）
 * </pre>
 *
 * <p>★★ <b>"上一轮印了多少"是给人看的读数，不是闸门</b>（用户原话同上）。它现在落三处，逐条具名：
 *
 * <ol>
 *   <li>DEBUG {@code MINT_LIMIT_WINDOW} 的 {@code previousCycleMinted}（每政府每关账轮一条，读数取的是哪一轮也写清）；
 *   <li>INFO {@code MINT_LIMIT_REJECTED} 的同名字段（真的拒了才有 —— 旋钮 &lt; 请求时仍会拒，{@code limit} = 旋钮）；
 *   <li>★★ <b>{@code Government.mintedLastCyclePerCycle}</b>（逐币种；本类每轮关账**覆盖写** —— 它是那份"可持久读数"的
 *       唯一写点，GUI {@code GET /api/economy/gov} 读它）。★ 写进**状态**的东西必须走 {@code Command → ChangeSet →
 *       Revision} 同一条路径：这里写的是 {@code EconomySession} 的工作表 （{@code
 *       EconomyStateBuilder.governments()}），与发行审计/债务同一条通道，{@code EconomyChangeSet} 的 {@code
 *       governments} 字段增量自然带出去。
 * </ol>
 *
 * <p>★ <b>它不参与任何 clamps</b>：本轮能铸多少<b>只由旋钮回答</b>（旧口径 {@code min(旋钮, 上一轮印量)} 已删）。
 *
 * <p>★★★ <b>为什么窗口是"绝对日"而不是 {@code period == 当前周期 − 1}（W5-fix4，2026-10-11 实测裁定）</b>： {@code
 * MoneyIssuanceRecord.period} 的权威含义是<b>经济周期序号</b>（{@code EconomyMeta.currentCycleNumber()} =
 * "全局最后关账周期 + 1"，见 {@code EconomyMeta.java:99-102} 与写入处 {@code EconomySettlement.java:1164}），
 * 它<b>不是</b>"这个铸币 unit 的第几轮"：小世界里铸币产业 {@code cycleDays=120}（{@code EconomySeeder.CYCLE_DAYS}）、 官署产业
 * {@code OFFICE_CYCLE_DAYS=30}（{@code GovWorldBootstrap.java:156}）⇒ 每 120 天里官署关账 4 次 ⇒ 铸币记录的 {@code
 * period} 步进实测是 <b>4 / 8 / 12 / 16</b>，而"当前周期 − 1"是 3 / 7 / 11 ⇒ <b>永远匹配不到</b>，
 * 于是那条窗口<b>读不到任何一轮</b>（W5 的"≤ 上一轮印量"因此形同虚设；W5-fix5 起这个读数只用于显示/审计，闸门只看旋钮）。 ★
 * 权威的"轮"身份在本仓是<b>绝对日</b>——统计簿 {@code MarketRoundStats.lastRoundDay} 用的就是它；铸币记录只在关账日写 ⇒
 * "最近一个铸币日"就是"上一轮"， 不需要第二份轮次定义、也不新开状态组件。
 *
 * <p>★ <b>为什么读数仍按 {@code MoneyIssuanceRecord} 现算</b>（约束设计书 §5.3-4）："上轮印量"不是第二份真相 —— 发行审计表里已经有
 * {@code (governmentId, day, period, currency, amount, kind)}，按窗口求和即可，<b>不新开状态组件</b>
 * （性能红线：本方法只在关账轮跑一次，求和规模 = 本政府的发行条数；{@code mintedLastCyclePerCycle} 是**定长**读数，不随天数增长）。
 *
 * <p>★ <b>读数的量纲（如实记）</b>：日志里的 {@code previousCycleMinted} 是<b>每个政府一个数</b>、跨币种求和 （{@code
 * Government.mintScalePerCycle} 本身也不按币种细分，两者同一量纲）；写进状态的 {@code mintedLastCyclePerCycle}
 * 则是<b>逐币种</b>的那一份（契约要求的形状）。
 *
 * <p>★★ <b>首轮特判已删（W5-fix5，§一.11）</b>：旧口径那句"没有上一轮 ⇒ 上限 = 政策旋钮（首轮启动额）"**其语义已随"不夹"消失** —— 上限恒 =
 * 旋钮，有没有上一轮都一样。⇒ 该分支整个删除，不留兼容位；"这个政府从未铸过币"现在只是一个**读数**事实 （{@code mintedLastCyclePerCycle} =
 * 空表；DEBUG {@code MINT_LIMIT_WINDOW} 的 {@code previousRoundDay=-1} / {@code previousCycleMinted=-1}
 * 具名）。
 *
 * <p>★★ <b>货币 = 发行政府的债务（判据 ④ 的反面：钱是欠出来的）</b>：每笔铸币都写进<b>同一本</b> {@code debtContracts}（{@code
 * DebtContractBook.upsert}），债务人是发行政府的国库家户、债权人是**铸币这一路的 组织者**（= 该 unit 的 {@code operator} 解析出的家户）。★
 * 在本批的常态世界里两者是同一户（政府家户既是国库又是组织者） ⇒ 合同是"自持"的记账事实（发行额 = 政府负债），这是**货币即债务**在最小模型下的形状，不是重复计债。
 *
 * <p>★ <b>幂等/重放</b>：{@link MoneyIssuanceId#forMintProduction} 由 {@code (政府, 日, 币种)} 确定性派生；
 * 同一天同一政府同一币种第二次写入 ⇒ 当场抛（不静默覆盖）。
 */
final class MintSettlement {

  /** 铸币日志（settlement 分类）：发行/上限/拒绝三类事实。 */
  private static final org.slf4j.Logger LOG = EconomyLog.settlement();

  private MintSettlement() {}

  /**
   * ★★★ <b>在周期关账处收获这一轮的全部铸币产出</b>（返回逐币种的铸币总量，只作日志/调用方读数）。
   *
   * @param base 本次 revision 的不可变基态（读政府表；{@code units} 走会话工作表）
   * @param session 本次推进会话（发行审计 + 债务写进它的工作表）
   * @param accounts 账户会话（国库余额的唯一工作副本）
   * @param day 当前世界日
   * @param currentCycle 当前产业周期序号（≥ 1；进发行审计与"上一轮"窗口判据）
   * @param closedUnits 本日关账的 unit → 关账处求好的规模（{@code scale}）；非关账 unit 不在表里
   */
  static Map<CurrencyId, Long> settleCycleClose(
      EconomyDayView base,
      EconomySession session,
      AccountSession accounts,
      long day,
      long currentCycle,
      Map<ProductionUnitId, Long> closedUnits) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(closedUnits, "closedUnits");
    if (closedUnits.isEmpty() || base.governments().isEmpty()) {
      return Map.of();
    }
    Map<MoneyIssuanceId, MoneyIssuanceRecord> issuances = session.sheet().moneyIssuances();
    Map<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    Map<CurrencyId, Long> mintedTotal = new LinkedHashMap<>();
    // ★ 与基态同序（LinkedHashMap）⇒ 多政府/多币种世界的处理序是内容的纯函数（I7）。
    for (Government government : base.governments().values()) {
      // ★★ W5-fix5：闸门 = **政策旋钮本身**（决策人预期即权威；用户原话见类注）。上一轮印量只算一次、只作读数。
      //   ★ W5-fix4 的窗口（= **绝对日**：最近一个严格早于本轮的铸币日）保留 —— 它现在服务读数字段与日志，
      //     不再是闸门（period == 当前周期 − 1 那条恒空转，见类注的实测）。
      Optional<PreviousRound> previousRound = previousRoundMinted(session, government, day);
      long previousMinted =
          previousRound.map(PreviousRound::minted).orElse(-1L); // -1 = 还没有过铸币轮（读数缺席）
      long limit = government.mintScalePerCycle();
      // ★★ 读数写口（本批新增/接通）：把"上一铸币轮的逐币种印量"覆盖进 Government.mintedLastCyclePerCycle。
      //   位置 = 闸门算完之后、铸币之前 —— 本轮（今天）写下的发行记录不在窗口内（day < 本轮 day 严格小于）
      //   ⇒ 写进去的就是"上一轮"那个数，与本轮 limit 的读数字段逐值同源。
      boolean readoutChanged =
          writeLastCycleReadout(session, government, day, currentCycle, previousRound);
      // ★ §一.9：判据路径的具名 DEBUG —— 读了哪个窗口、读到多少、上限怎么来的（都是真值；
      //   没有上一轮时 previousRoundDay=-1、previousCycleMinted=-1，reason 具名说清"还没有过铸币轮"）。
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "MINT_LIMIT_WINDOW",
                  EconomyLogSource.ECONOMY_MONEY,
                  "day",
                  day,
                  "period",
                  currentCycle,
                  "government",
                  government.id().value(),
                  "previousRoundDay",
                  previousRound.map(PreviousRound::day).orElse(-1L),
                  "previousCycleMinted",
                  previousMinted,
                  "mintScalePerCycle",
                  government.mintScalePerCycle(),
                  "limit",
                  limit,
                  "limitSource",
                  "mint-scale-policy",
                  "mintedLastCycleReadout",
                  previousRound.map(round -> round.byCurrency().toString()).orElse("{}"),
                  "mintedLastCycleReadoutChanged",
                  readoutChanged,
                  "reason",
                  previousRound.isPresent()
                      ? "previous-mint-round-day-window-readout-only"
                      : "no-previous-mint-round-readout-only"));
      for (Map.Entry<ProductionUnitId, Long> entry : closedUnits.entrySet()) {
        ProductionProcess unit = session.sheet().units().get(entry.getKey());
        if (unit == null) {
          continue; // 关账表里有、工作表里没有：不是本方法的状态（别在这里猜）
        }
        Industry industry = session.sheet().industries().get(unit.industry());
        if (industry == null || industry.moneyOutputPerUnit().isEmpty()) {
          continue; // 这一路不铸币（绝大多数产业）⇒ 一个字节都不动
        }
        HouseholdId organizer = organizerOf(unit);
        HouseholdId treasuryHousehold = treasuryHouseholdOf(government);
        if (organizer == null
            || treasuryHousehold == null
            || !organizer.equals(treasuryHousehold)) {
          // ★ 不是这个政府的铸币 unit：别的政府/别的组织者的铸币由它自己那一轮处理。
          continue;
        }
        if (government.mintScalePerCycle() <= 0L) {
          // ★ 判据 ②：规模 0 ⇒ 新钱 0 毫。★ 政策明写 0 是**具名状态**（GM 关掉了铸币），发 DEBUG 说清；
          //   不发 INFO —— "关掉的政策"不是一次拒绝，发 INFO 会在每个关账日刷屏。
          EventLog.channel(LOG)
              .debug(
                  LogEvent.of(
                      "MINT_POLICY_ZERO",
                      EconomyLogSource.ECONOMY_MONEY,
                      "day",
                      day,
                      "period",
                      currentCycle,
                      "government",
                      government.id().value(),
                      "unit",
                      unit.id().value(),
                      "industry",
                      industry.id().value(),
                      "reason",
                      "mint-scale-zero",
                      "minted",
                      0L));
          continue;
        }
        long scale = entry.getValue();
        // ★★★ W10：铸币产出率的**运行期**权威 = 政策键 MINT_MILLI_PER_FURNACE_CYCLE（GM 写入即下一关账日生效）。
        //   ★ "未配置"⇒ 用产业载荷里声明的值（`Industry.moneyOutputPerUnit`）—— 那正是这条改动前的唯一来源
        //     （创世由 EconomySeeder 从同一个常量播种）⇒ **从没被 GM 写过的世界逐值不变**。
        boolean policyOverride =
            base.economyPolicyConfigured(EconomyPolicyKey.MINT_MILLI_PER_FURNACE_CYCLE);
        long policyMilliPerFurnaceCycle =
            base.economyPolicyOf(EconomyPolicyKey.MINT_MILLI_PER_FURNACE_CYCLE);
        for (Map.Entry<CurrencyId, Long> line :
            industry.moneyOutputPerUnit().perUnit().entrySet()) {
          CurrencyId currency = line.getKey();
          long requested =
              Math.multiplyExact(
                  scale, policyOverride ? policyMilliPerFurnaceCycle : line.getValue());
          if (requested <= 0L) {
            continue; // 规模 0 或产出率 0 ⇒ 没有产出（不做"0 毫的发行记录"：金额必须 > 0）
          }
          if (!government.issuable().contains(currency)) {
            // ★ 越权发行（M6）：说不出"这是我发的钱"就不许铸。契约故障 ⇒ ERROR 且不降级（§一.9）。
            EventLog.channel(LOG)
                .error(
                    LogEvent.of(
                        "MINT_CURRENCY_NOT_ISSUABLE",
                        EconomyLogSource.ECONOMY_MONEY,
                        "day",
                        day,
                        "period",
                        currentCycle,
                        "government",
                        government.id().value(),
                        "currency",
                        currency.value(),
                        "issuable",
                        government.issuable().toString(),
                        "unit",
                        unit.id().value(),
                        "industry",
                        industry.id().value(),
                        "requested",
                        requested));
            throw new IllegalStateException(
                "铸币产出的币种不在该政府的 issuable 集合内（拒绝按产业配方静默铸一种它无权发行的币）：政府="
                    + government.id().value()
                    + " 币种="
                    + currency.value()
                    + " issuable="
                    + government.issuable());
          }
          if (requested > limit) {
            // ★★ 超额**具名拒**（INFO；写清上限与实投）。★ 这不是故障 —— 政策旋钮是正常状态 ⇒ INFO（业务拒绝 = INFO）。
            //   ★★ W5-fix5：`limit` **就等于**政策旋钮（不再被上一轮印量夹）；`previousCycleMinted` 只是读数，
            //     它与 `limit` 并排出现，正是为了让"上限从哪来"一眼可读（读数是给人看的，不是闸门）。
            EventLog.channel(LOG)
                .info(
                    LogEvent.of(
                        "MINT_LIMIT_REJECTED",
                        EconomyLogSource.ECONOMY_MONEY,
                        "day",
                        day,
                        "period",
                        currentCycle,
                        "government",
                        government.id().value(),
                        "currency",
                        currency.value(),
                        "unit",
                        unit.id().value(),
                        "industry",
                        industry.id().value(),
                        "scale",
                        scale,
                        "perUnit",
                        line.getValue(),
                        "requested",
                        requested,
                        "limit",
                        limit,
                        "previousCycleMinted",
                        previousMinted,
                        "mintScalePerCycle",
                        government.mintScalePerCycle(),
                        "rejected",
                        requested - limit,
                        "reason",
                        "above-mint-scale-policy"));
          }
          long minted = Math.min(requested, limit);
          if (minted <= 0L) {
            continue;
          }
          credit(
              government,
              organizer,
              currency,
              minted,
              day,
              currentCycle,
              scale,
              line.getValue(),
              requested,
              limit,
              accounts,
              issuances,
              debts);
          mintedTotal.merge(currency, minted, Long::sum);
        }
      }
    }
    if (mintedTotal.isEmpty()) {
      return Map.of();
    }
    long grand = 0L;
    for (long amount : mintedTotal.values()) {
      grand = Math.addExact(grand, amount);
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MINT_SETTLED",
                EconomyLogSource.ECONOMY_MONEY,
                "day",
                day,
                "period",
                currentCycle,
                "closedUnits",
                closedUnits.size(),
                "currencies",
                mintedTotal.size(),
                "mintedTotal",
                grand,
                "byCurrency",
                mintedTotal.toString(),
                "kind",
                MoneyIssuanceKind.MINT_PRODUCTION.name()));
    return mintedTotal;
  }

  /**
   * ★★ <b>上一铸币轮（绝对日 + 该日这个政府<b>逐币种</b>铸出的额，毫）</b>：从 {@code moneyIssuances} 现算。
   *
   * <p>★★★ <b>为什么按 {@code day} 而不是按 {@code period == 当前周期 − 1}（W5-fix4 换掉的那条）</b>： {@code period}
   * 是<b>经济周期序号</b>（全局关账计数 + 1，{@code EconomyMeta.currentCycleNumber()}）， 而"轮"在这里是<b>铸币 unit
   * 的关账轮</b>；两者步长不同（小世界：铸币 120 天、官署 30 天 ⇒ period 步进 4）， 故 {@code period − 1}
   * 永远匹配不到上一条记录（实测证据见类注）。★ 绝对日正是统计簿认"轮"的方式 （{@code MarketRoundStats.lastRoundDay}）⇒
   * 取"最近一个小于本轮的铸币日"就是上一轮，且不需要新状态。
   *
   * <p>★★ <b>它现在是<b>读数</b>（W5-fix5 起不再参与 clamps）</b>，两份形态同源：
   *
   * <ul>
   *   <li>{@link PreviousRound#minted()} = 跨币种求和 ⇒ 日志字段 {@code previousCycleMinted}；
   *   <li>{@link PreviousRound#byCurrency()} = 逐币种表 ⇒ 写进 {@code Government.mintedLastCyclePerCycle}
   *       （契约要求的形状，也是 GUI 那一栏）。
   * </ul>
   *
   * <p>★ 严格 {@code day < 本轮 day}：本轮（今天）刚写下的记录不算"上一轮"；{@code day} 更大的记录也不参与。 同一天的多条（多个铸币 unit
   * 同日关账、或多币种）<b>合并</b> —— 它们是同一轮（同币种相加，不同币种各成一键）。
   *
   * @return 上一轮的绝对日与该日逐币种铸币额；这个政府<b>从未铸过币</b> ⇒ 空（调用方按空表读，见类注"首轮特判已删"）
   */
  private static Optional<PreviousRound> previousRoundMinted(
      EconomySession session, Government government, long day) {
    long roundDay = -1L;
    LinkedHashMap<CurrencyId, Long> byCurrency = new LinkedHashMap<>();
    for (MoneyIssuanceRecord record : session.sheet().moneyIssuances().values()) {
      if (record.kind() != MoneyIssuanceKind.MINT_PRODUCTION
          || !record.governmentId().equals(government.id())
          || record.day() >= day) {
        continue; // 别的类别/别的政府不是"这个政府印了多少"；本轮与未来的日不构成"上一轮"
      }
      if (record.day() > roundDay) {
        roundDay = record.day();
        byCurrency.clear(); // 更近的一轮 ⇒ 丢弃上一轮的小计（窗口只留最近一轮）
        byCurrency.put(record.currency(), record.amount());
      } else if (record.day() == roundDay) {
        byCurrency.merge(record.currency(), record.amount(), Math::addExact);
      }
    }
    return roundDay < 0L ? Optional.empty() : Optional.of(new PreviousRound(roundDay, byCurrency));
  }

  /**
   * ★★ <b>把"上一铸币轮的逐币种印量"覆盖写进 {@code Government.mintedLastCyclePerCycle}</b>（W5-fix5 接通的写点）。
   *
   * <p>★★ <b>契约原文</b>（{@code Government.java} 的 {@code @param
   * mintedLastCyclePerCycle}）："上一市场轮逐币种实际印量（毫）…… 由 {@code MintSettlement} 在每轮关账时<b>覆盖写</b>……★
   * <b>空表</b> = 还没有过任何铸币轮（合法状态：这一轮没有上一轮印量可比）。键不得为 null、 逐值必须 {@code > 0}（0 不是一条记录，删键即可）"。⇒
   * 本方法逐条照此：<b>没有上一轮 ⇒ 写空表</b>（不是 0 毫 —— 0 进不了那个类型的构造期守卫）。
   *
   * <p>★★ <b>唯一写路径</b>（铁律 2）：写的是本次推进会话的<b>工作表</b>（{@code EconomyStateBuilder.governments()}）——
   * 与发行审计（{@code moneyIssuances}）、债务（{@code debtContracts}）同一条通道；{@code EconomySession.build()}
   * 把它挂成完整的 {@code EconomyData}，{@code EconomyChangeSet} 的 {@code governments} 字段增量由 {@code
   * FieldDelta.diff} 派生 ⇒ 落在 {@code Command → ChangeSet → Revision} 上。<b>没有绕过</b>：本方法拿不到
   * base，只拿得到会话工作表。
   *
   * <p>★ <b>值没变就不写</b>（工作表一字不动 ⇒ 变更集保持 {@code Unchanged}）：关账日之外重复写同一个数没有语义，
   * 让"每轮"都产生一条形态相同但内容相同的状态增量只会污染 changeset/dump 的差异面。读数真变了才落盘。
   *
   * @return 是否真的覆盖了（{@code true} = 状态里这个字段的值变了 ⇒ 已发 INFO 具名日志）
   */
  private static boolean writeLastCycleReadout(
      EconomySession session,
      Government government,
      long day,
      long currentCycle,
      Optional<PreviousRound> previousRound) {
    Map<CurrencyId, Long> next = previousRound.map(PreviousRound::byCurrency).orElseGet(Map::of);
    Government current = session.sheet().governments().get(government.id());
    if (current == null) {
      // 工作表里没有这个政府（别的写口在同一次会话里刚删了它）⇒ 不在这里把它造回来。
      return false;
    }
    Map<CurrencyId, Long> before = current.mintedLastCyclePerCycle();
    if (before.equals(next)) {
      return false; // 读数没变：不写、不发（DEBUG MINT_LIMIT_WINDOW 仍会具名记下这次读数）
    }
    session.sheet().governments().put(government.id(), current.withMintedLastCyclePerCycle(next));
    // ★ §一.9：新增状态写口的 INFO（"发生了什么 + 具名计数"）—— 只在真的覆盖时发（值没变的那一轮不刷屏）。
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MINT_LAST_CYCLE_READOUT",
                EconomyLogSource.ECONOMY_MONEY,
                "day",
                day,
                "period",
                currentCycle,
                "government",
                government.id().value(),
                "previousRoundDay",
                previousRound.map(PreviousRound::day).orElse(-1L),
                "previousCycleMinted",
                previousRound.map(PreviousRound::minted).orElse(-1L),
                "before",
                before.toString(),
                "after",
                next.toString(),
                "currencies",
                next.size()));
    return true;
  }

  /**
   * 上一铸币轮读数：{@code day} = 该轮的绝对日，{@code byCurrency} = 该轮这个政府<b>逐币种</b>的铸币额（毫）。
   *
   * <p>★ 它只是一个<b>读写窗口的中间读数</b>（进日志字段 + 覆盖写 {@code Government.mintedLastCyclePerCycle}）， 自身不是状态、不进
   * {@code EconomyData}。★ 保序不可变（键序 = 该轮发行记录的首次出现序）。
   */
  private record PreviousRound(long day, Map<CurrencyId, Long> byCurrency) {

    PreviousRound {
      // ★ 冻在赋值处（保序）：调用方拿不到可变引用，迭代序 = 内容的纯函数。
      byCurrency = Collections.unmodifiableMap(new LinkedHashMap<>(byCurrency));
    }

    /** 该轮这个政府的铸币总额（毫，**跨币种求和**）—— 日志字段 {@code previousCycleMinted} 的真值。 */
    long minted() {
      long total = 0L;
      for (long amount : byCurrency.values()) {
        total = Math.addExact(total, amount);
      }
      return total;
    }
  }

  /** 铸币这一路的组织者家户（解析不到 ⇒ {@code null}；调用方按"不是这个政府的 unit"跳过）。 */
  private static HouseholdId organizerOf(ProductionProcess unit) {
    try {
      return HouseholdRouting.requireHouseholdOf(unit.operator());
    } catch (RuntimeException unresolved) {
      return null;
    }
  }

  /** 政府的国库家户（非家户国库 ⇒ {@code null}：那种政府在最小模型下没有可入账的家户账户）。 */
  private static HouseholdId treasuryHouseholdOf(Government government) {
    try {
      return HouseholdRouting.requireHouseholdOf(government.treasury());
    } catch (RuntimeException unresolved) {
      return null;
    }
  }

  /** 把一笔铸币落成三件事实：国库余额 + 发行审计 + 政府债务（三者同一次调用里做完）。 */
  private static void credit(
      Government government,
      HouseholdId organizer,
      CurrencyId currency,
      long minted,
      long day,
      long currentCycle,
      long scale,
      long perUnit,
      long requested,
      long limit,
      AccountSession accounts,
      Map<MoneyIssuanceId, MoneyIssuanceRecord> issuances,
      Map<DebtContractId, DebtContract> debts) {
    ActorAccount treasury = accounts.householdAccount(treasuryHouseholdOf(government));
    if (treasury == null) {
      throw new IllegalStateException(
          "铸币找不到国库账户（seed/账户会话不完整，拒绝静默跳过）：政府="
              + government.id().value()
              + " treasury="
              + government.treasury());
    }
    // ① 国库余额 +minted（货币产出**走账户余额**，与商品产出走货物账是同一条"产出计入组织者"的口径）
    Map<CurrencyId, Long> money = new LinkedHashMap<>(treasury.money());
    long before = money.getOrDefault(currency, 0L);
    long after = Math.addExact(before, minted);
    money.put(currency, after);
    treasury.replaceMoney(money);
    // ② 发行审计（kind 具名 = MINT_PRODUCTION；id 由 (政府, 日, 币种) 确定性派生 ⇒ 重复写入当场抛）
    MoneyIssuanceId id = MoneyIssuanceId.forMintProduction(government.id().value(), day, currency);
    MoneyIssuanceRecord record =
        new MoneyIssuanceRecord(
            id,
            government.id(),
            day,
            currentCycle,
            currency,
            minted,
            MoneyIssuanceKind.MINT_PRODUCTION,
            "铸币生产方式：scale="
                + scale
                + " × perUnit="
                + perUnit
                + " 毫/规模，上限="
                + limit
                + "（政策旋钮="
                + government.mintScalePerCycle()
                + "，产出请求="
                + requested
                + "）");
    if (issuances.putIfAbsent(id, record) != null) {
      throw new IllegalStateException("铸币审计 id 重复（同一天同一政府同一币种只允许一条）：" + id.value());
    }
    // ③ 货币 = 发行政府的债务：同一本 debtContracts（债务人是**发行政府的国库家户**，
    //   债权人是**铸币这一路的组织者**）。
    //   ★★ 两个参数为什么是这两个（这是"货币本身是债务"在最小模型下的形状，别照抄市场上那条）：
    //     市场上那条的债务人是"没付清货款的买方"；铸币这条的债务人是**发行主体**（它印出来的每 1 毫都是它的负债），
    //     债权人 = 这一路产出的组织者（新钱以"它产出的东西"的名分交到它手上，故它持有对该政府的索取权）。
    //   ★ 在常态世界里两者是同一户（政府家户既是国库又是组织者）⇒ 合同是"自持"的记账事实（发行额 = 政府负债）。
    HouseholdId governmentTreasury = treasuryHouseholdOf(government);
    if (governmentTreasury == null) {
      throw new IllegalStateException(
          "铸币记债找不到国库家户（非家户国库在最小模型下没有债务主体）：政府=" + government.id().value());
    }
    DebtContractBook.upsert(
        debts,
        governmentTreasury,
        organizer,
        new DebtUnit.Money(currency),
        DebtTerms.legacyDefault(),
        minted,
        day,
        OptionalLong.of(currentCycle + 1L));
    EventLog.channel(LOG)
        .trace(
            LogEvent.of(
                "MINT_CREDIT",
                EconomyLogSource.ECONOMY_MONEY,
                "day",
                day,
                "period",
                currentCycle,
                "government",
                government.id().value(),
                "currency",
                currency.value(),
                "minted",
                minted,
                "requested",
                requested,
                "limit",
                limit,
                "scale",
                scale,
                "perUnit",
                perUnit,
                "treasuryBefore",
                before,
                "treasuryAfter",
                after,
                "issuance",
                id.value(),
                "debtor",
                organizer.value()));
  }
}
