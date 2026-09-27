package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>同格市场清算</b>（H4；裁定 M1-A）：<b>每周期一次</b>、<b>同一格内</b>把"留足自留之后的余量"按 <b>固定价</b>卖给"<b>有购买力</b>的缺口"。
 *
 * <p>★★ <b>形状（判据就是它；数量一律毫单位、整数）</b>：
 *
 * <pre>
 * 【供给】逐家户、逐**已定价**商品：
 *    自留 reserve = 该商品的整周期自然需求 × {@link #MARKET_SELF_RESERVE_PER_MILLE} ÷ 1000
 *    可售 supply_h = max(0, 余额_h − reserve)
 * 【需求】逐家户、逐**已定价**商品（★★ H5 ①：**本期（整周期）剩余需求**，不再是"当日缺口"）：
 *    本期缺口 unmet_h = 本周期累计未满足需求（flows.unmetNeed_h + 当日 unmetToday_h）  // 毫单位
 *    剩余 gap_h      = max(0, unmet_h − 余额_h)      // ★ "本周期还缺多少、手上又已经有多少" ⇒ 补到不缺口为止
 *    买得起 afford_h  = 钱_h(计价货币) × 1000 ÷ 价格          // ★ 没钱的缺口**不是有效需求**
 *    需求 demand_h   = min(gap_h, afford_h)
 * 【成交】固定价：供 ≥ 求 ⇒ 按需成交；求 &gt; 供 ⇒ 按需求比例**配给**（最大余数法，Σ配给 == 供给）
 *    货款 = ⌈数量 × 价格 ÷ 1000⌉                            // ★ 向上取整：向下取整会让小额成交"白送"
 * </pre>
 *
 * <p>★★ <b>"没钱的缺口不是有效需求"是本仓一贯的立场</b>（"市场有粮仍可能有人饿"）：需求那一维里 <b>购买力先于需要</b> ——
 * 饿肚子但分文没有的家户不产生一条需求（它的缺口照旧记在 {@code FlowRow.unmetNeed} 里）。 ★
 * 于是"市场开了、粮也卖光了、还是有人饿死"是一件<b>账面上说得清</b>的事。
 *
 * <p>★★ <b>H5 ①：需求窗口 = 本期剩余需求（改前是"当日缺口"）</b> —— 逐条理由：
 *
 * <pre>
 * 改前：gap_h = max(0, 当日需求_h − 余额_h)        ⇒ 集市只在关账日开一次 ⇒ 只补得上**一天**的口粮
 *       （其余 119 天照旧缺口；下一个周期又从空缸开始）—— 实测：整周期缺口几乎不收敛。
 * 改后：gap_h = max(0, 本周期累计缺口_h − 余额_h)  ⇒ 关账日一次把"这一周期缺的那些"补齐（补到不缺口为止），
 *       于是下一个周期从**满缸**开始 ⇒ 整周期缺口逐周期收敛（判据 ① 的实测见 H5 报告）。
 * ★ 恒等式（两条口径的关系，逐值可核）：
 *     本周期累计缺口_h = Σ_本周期各日 (当日需求 − 当日实得) = 本周期总需求_h − 本周期已吃_h
 *   ⇒ gap_h = max(0, 本周期总需求_h − 本周期已吃_h − 余额_h)  —— 与规格里那个算式**逐字同形**。
 *     （"本周期已吃"用 unmetNeed 表达：总需求 = 已吃 + 未满足 ⇒ 已吃 = 总需求 − unmetNeed。）
 * </pre>
 *
 * <p>★ <b>为什么读 {@code unmetNeed} 而不是另立一张"本周期已吃"的表</b>：{@code FlowRow.unmetNeed} 已经是
 * "本周期累计未满足需求"的**唯一**拼写点（逐日累加、新周期第一天归零），再算一遍必然漂开。★ 当日那一份在 {@code unmetToday} 里（还没并进流水）⇒ 两处相加 =
 * 到此刻为止的整周期缺口。
 *
 * <p>★★ <b>自留口径（{@link #MARKET_SELF_RESERVE_PER_MILLE}）</b>：家户在挂牌之前先扣下 <b>{@code 千分比 ÷ 1000}
 * 倍的整周期自然需求</b>（默认 1000‰ = <b>留足 1 个整周期</b>的自需）。★ 与放贷那条 （{@code
 * LENDER_SUBSISTENCE_RESERVE_PER_MILLE}）**同一条口径、同一个算式形状**（{@link
 * EconomyVocabulary#cumulativeRationMilli} 的整周期值），只是**各自一个旋钮**：借贷是"借出去要还的"、市场是"卖出去就没了" ——
 * 两种风险不该共用一个数。 ★ 它读的是 {@code cycleDaysByHousehold}（该家户供给的那些产业的**最长**周期；没有产业 ⇒ 0 ⇒ 自留 0）。
 *
 * <p>★★ <b>落账（唯一的 applier）</b>：一笔买卖铸<b>一对</b>转移（见 {@code Transfer} 的货币腿口径）， 两条都交给 {@link
 * EconomySettlement#applyTransferToHouseholds} 落到会话副本上 —— ★ <b>本类绝不直接改副本</b>：
 * "任何库存变动必有对应转移记录"这条不变量的落点因此仍是一处。
 *
 * <pre>
 * ① 货：goods={商品: 数量}, money={}        from=卖方家户 → to=买方家户
 * ② 钱：goods={},          money={币种: 货款} from=买方家户 → to=卖方家户
 * </pre>
 *
 * <p>★★ <b>它改的另一样：买方当日的未满足需求</b>（{@code unmetToday}）—— 买到的量**冲减当日的缺口读数** （"买到手的粮当天就能吃"）。★
 * <b>与饿死判据的次序</b>：判据在收获那一支里、**早于**本步（见 {@code EconomySettlement.settleOneDay} 的"──
 * 6."），故本步影响的是<b>当日流水读数</b>与<b>下一周期</b>的判据基数， 本周期已经跑过的那一次判据不回改（如实记；默认致死率 0‰ 时两者无差别）。
 *
 * <p>★ <b>缺格的格没有市场</b>（{@code markets} 里没有那个键）：整块跳过（不抛、不造默认价）。★ <b>没有定价的商品不交易</b> （价格表里没有它 ⇒
 * 供给与需求都不算它）。
 *
 * <p>★ <b>本批如实不做的</b>：跨格市场（同格池之外没有搬运，见 {@code Market} 的类注）、市场库存/订单簿、价格随供需浮动 （价格是数据，见 {@code
 * Market}）、运输损耗与关税。
 *
 * <p>★★ <b>H5：买卖双方仍然只有家户</b>（经营者不参与市场）—— 两个 {@code operator*} 参数进来<b>只为</b>
 * "任何库存变动必有对应转移记录"那条不变量的落点仍然只有一处（{@link EconomySettlement#applyTransfer} 要看得见
 * 经营者账）；本类**不读**它们（既不出售经营者的布/工具，也不让它买料）。★ 后果如实记：经营者因此只有"付出"没有 "收入"（它的货币周转金是一次性的，见 {@code
 * EconomySeeder.operatorWageReserveMilli} 的边界注释）。
 */
final class MarketSettlement {

  /**
   * ★★ <b>挂牌前的自留倍数</b>（千分数；相对**整周期**自然需求）：默认 <b>1000‰ = 留足 1 个整周期</b>。
   *
   * <pre>
   * reserve_h,j = 整周期自然需求(pop_h, cycleDays_h, j) × 本常量 ÷ 1000     // 毫单位
   * 可售_h,j    = max(0, 余额_h,j − reserve_h,j)
   * </pre>
   *
   * <p>★★ <b>它为什么必须是一个显式的出厂常量</b>：这是"家户肯把多少余粮拿出来卖"这个**判断**（信条十二：模型给口径、GM 给判断） ——
   * 写死成"全卖掉"会让农户把口粮也卖掉、下个周期自己变成缺口户并推高粮价（本仓最反对的"用模型替 GM 做判断"）。 ★ <b>V7 参数目录落地后</b>它迁入 {@code
   * economy} 切片的参数表、成为 GM 可调（与致死率、播种次序、放贷自留同一条路）。 ★ 取 {@code 0} = 全部可售（旧口径的"余粮即全部库存"）；取 {@code
   * 2000} = 留足两个周期（饥荒预期下的囤积）。
   *
   * <p>★★ <b>M1.2 边界：保留策略留在本层，账户层的 {@code frozen} 只表达"已明确的占用"</b> —— 本常量是<b>只读算式的中间量</b>
   * （它不写任何字段、不占任何库存），<b>不许</b>把它折进 {@code GoodsAccount.frozenBalances}（那正是"把储备政策搬进账户模型"）。 M2
   * 的可售算式在这些项之外**再减**一次 {@code frozen}，且各项互不重复扣除。
   */
  static final int MARKET_SELF_RESERVE_PER_MILLE = 1000;

  private MarketSettlement() {}

  /**
   * ★★ <b>开一次市</b>（每个周期一次；由 {@code EconomySettlement.settleOneDay} 在收获与分配之后调用）。
   *
   * @param markets 市场表（键 = 格；**缺格 = 该格没有市场**）；不得为 null
   * @param rows 家户行（**读**：人口 / 自然需求 / 周期天数）；不得为 null
   * @param householdGoods 商品账的会话工作副本（**就地更新**，且只经唯一 applier）；不得为 null
   * @param householdMoney 货币账的会话工作副本（同上）；不得为 null
   * @param unmetToday 当日的未满足需求累加器（**就地更新**：买方买到的量冲减它）；不得为 null
   * @param cycleDaysByHousehold 每条家户行的 {@code cycleDays}（自留额的输入；见 {@code
   *     EconomySettlement.cycleDaysByHousehold}）；不得为 null
   * @param householdOfActor 家户 actor → 家户身份的反查表；不得为 null
   * @param ledger 当天的发生额累加器（**铸转移的地方**；本类不自己分配 id）；不得为 null
   */
  static void clearOncePerCycle(
      Map<HexCoord, Market> markets,
      LinkedHashMap<CohortKey, ClassRow> rows,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> unmetToday,
      Map<CohortKey, FlowRow> flows,
      Map<CohortKey, Long> cycleDaysByHousehold,
      Map<ActorRef, CohortKey> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    if (markets.isEmpty()) {
      return; // ★ 世上没有市场 ⇒ 什么都不做（最常见的一支：本批之前的所有世界都是这一形态）
    }
    // ★ "这一格有哪些行"只有一处算法（EconomySettlement.rowsByHex）—— 建一次，逐格取用。
    Map<String, List<CohortKey>> rowsByHex = EconomySettlement.rowsByHex(rows.keySet());
    for (Map.Entry<HexCoord, Market> entry : markets.entrySet()) {
      HexCoord hex = entry.getKey();
      Market market = entry.getValue();
      List<CohortKey> keys =
          rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
      if (keys.isEmpty()) {
        continue; // 这一格还没有家户（世界还没播种到这里）⇒ 没有买卖双方
      }
      for (Map.Entry<CommodityId, Long> priced : market.prices().entrySet()) {
        clearOneCommodity(
            hex,
            market.numeraire(),
            priced.getKey(),
            priced.getValue(),
            keys,
            rows,
            householdGoods,
            householdMoney,
            operatorGoods,
            operatorMoney,
            unmetToday,
            flows,
            cycleDaysByHousehold,
            householdOfActor,
            ledger);
      }
    }
  }

  /** 一个格 × 一个已定价商品的撮合（见类注的形状）。 */
  private static void clearOneCommodity(
      HexCoord hex,
      CurrencyId numeraire,
      CommodityId commodity,
      long price,
      List<CohortKey> keys,
      Map<CohortKey, ClassRow> rows,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> unmetToday,
      Map<CohortKey, FlowRow> flows,
      Map<CohortKey, Long> cycleDaysByHousehold,
      Map<ActorRef, CohortKey> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    // ── 供给：留足自留之后的余量 ─────────────────────────────────────────────────────
    List<CohortKey> sellers = new ArrayList<>();
    List<Long> sellable = new ArrayList<>();
    long supply = 0L;
    for (CohortKey key : keys) {
      ClassRow row = rows.get(key);
      long stock = stockOf(householdGoods, key, commodity);
      long amount = sellableOf(row, stock, commodity, cycleDaysOf(cycleDaysByHousehold, key));
      if (amount <= 0L) {
        continue;
      }
      sellers.add(key);
      sellable.add(amount);
      supply += amount;
    }
    if (supply <= 0L) {
      return; // 没人有可售的余量 ⇒ 不开市（"市面上没有粮"）
    }
    // ── 需求：有购买力的缺口（★ 没钱的缺口不是有效需求）────────────────────────────────
    List<CohortKey> buyers = new ArrayList<>();
    List<Long> demands = new ArrayList<>();
    long totalDemand = 0L;
    for (CohortKey key : keys) {
      long demand =
          effectiveDemandOf(
              rows.get(key),
              householdGoods,
              householdMoney,
              unmetToday,
              flows,
              key,
              commodity,
              numeraire,
              price);
      if (demand <= 0L) {
        continue;
      }
      buyers.add(key);
      demands.add(demand);
      totalDemand += demand;
    }
    if (totalDemand <= 0L) {
      return; // 有货、但没有人既有缺口又买得起 ⇒ 不成交（"市场有粮仍可能有人饿"）
    }
    // ── 固定价：供 ≥ 求 ⇒ 按需成交；求 > 供 ⇒ 按需求比例配给（Σ配给 == 供给）──────────────
    long[] rationed;
    if (supply >= totalDemand) {
      rationed = new long[demands.size()];
      for (int i = 0; i < demands.size(); i++) {
        rationed[i] = demands.get(i);
      }
    } else {
      long[] weights = new long[demands.size()];
      for (int i = 0; i < demands.size(); i++) {
        weights[i] = demands.get(i);
      }
      rationed = ProportionalSplit.byDenominator(supply, weights, totalDemand);
    }
    // ── 撮合：买方按行序取货、卖方按行序出货（可复现；余额与配给额两者取小）──────────────
    int sellerIndex = 0;
    long leftWithSeller = sellable.get(0);
    for (int i = 0; i < buyers.size(); i++) {
      long need = rationed[i];
      CohortKey buyer = buyers.get(i);
      while (need > 0L && sellerIndex < sellers.size()) {
        if (leftWithSeller <= 0L) {
          sellerIndex++;
          if (sellerIndex < sellers.size()) {
            leftWithSeller = sellable.get(sellerIndex);
          }
          continue;
        }
        long quantity = Math.min(need, leftWithSeller);
        trade(
            hex,
            numeraire,
            commodity,
            price,
            quantity,
            sellers.get(sellerIndex),
            buyer,
            householdGoods,
            householdMoney,
            operatorGoods,
            operatorMoney,
            unmetToday,
            householdOfActor,
            ledger);
        need -= quantity;
        leftWithSeller -= quantity;
      }
    }
  }

  /**
   * ★★ <b>一笔成交</b>：铸<b>一对</b>同向转移（货一条、钱一条）并交给**唯一 applier** 落账。
   *
   * <pre>
   * 货款 = ⌈数量 × 价格 ÷ 1000⌉      // 毫计价货币；★ 向上取整的理由见类注（向下取整会让小额成交白送）
   * </pre>
   *
   * <p>★ 买方的余额足够付这笔钱是**结构性**保证：{@code 数量 ≤ 需求 ≤ 钱 × 1000 ÷ 价格 ⇒ 数量 × 价格 ÷ 1000 ≤ 钱} ⇒ 向上取整后仍 {@code
   * ≤ 钱}（整数）。⇒ applier 那一步不会碰到"余额不足"（真碰到 ⇒ 当场抛，见它自己的注释）。
   *
   * @param quantity 成交量（毫商品；{@code > 0}）
   */
  private static void trade(
      HexCoord hex,
      CurrencyId numeraire,
      CommodityId commodity,
      long price,
      long quantity,
      CohortKey seller,
      CohortKey buyer,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> unmetToday,
      Map<ActorRef, CohortKey> householdOfActor,
      ProductionLedger.Accumulator ledger) {
    long payment =
        (quantity * price + EconomySettlement.MILLI_PER_GRAIN - 1L)
            / EconomySettlement.MILLI_PER_GRAIN;
    // ① 货：卖方 → 买方。
    Transfer goodsLeg =
        ledger.mint(
            HouseholdActors.of(seller),
            HouseholdActors.of(buyer),
            hex,
            Map.of(commodity, quantity),
            TransferReason.MARKET_TRADE);
    EconomySettlement.applyTransfer(
        householdGoods, householdMoney, operatorGoods, operatorMoney, householdOfActor, goodsLeg);
    // ② 钱：买方 → 卖方（★ 同一套方向语义：这一张转移的 from 就是付钱的人）。
    //   ★ 只有真有钱时才铸（货款 ≥ 1 是结构性的：price ≥ 1、quantity ≥ 1 ⇒ ⌈…⌉ ≥ 1）。
    Transfer moneyLeg =
        ledger.mint(
            HouseholdActors.of(buyer),
            HouseholdActors.of(seller),
            hex,
            Map.of(),
            Map.of(numeraire, payment),
            TransferReason.MARKET_TRADE);
    EconomySettlement.applyTransfer(
        householdGoods, householdMoney, operatorGoods, operatorMoney, householdOfActor, moneyLeg);
    // ③ 缺口读数：买到的量冲减**当日**的未满足需求（封顶 = 已记的缺口；不改成负数、不凭空抵消历史缺口）。
    long recorded = unmetToday.getOrDefault(buyer, Map.of()).getOrDefault(commodity, 0L);
    long reduced = Math.min(recorded, quantity);
    if (reduced > 0L) {
      Map<CommodityId, Long> updated =
          new LinkedHashMap<>(unmetToday.getOrDefault(buyer, Map.of()));
      if (recorded - reduced <= 0L) {
        updated.remove(commodity);
      } else {
        updated.put(commodity, recorded - reduced);
      }
      unmetToday.put(buyer, updated);
    }
  }

  /**
   * ★ <b>可售余量</b>（见类注的"供给"）：{@code max(0, 余额 − 整周期自然需求 × 倍数 ÷ 1000)}。
   *
   * <p>★ <b>非自然需求商品</b>（纤维 / 工具 / 铁 / 木 —— {@link EconomyVocabulary#dailyNeedsMilli} 里没有它们）的
   * "整周期自然需求"是 <b>0</b> ⇒ 自留 0 ⇒ 全量可售。★ 今天这一类不会成交：需求那一维读的也是自然需求（同样为 0）——
   * 两处口径同源，故"中间品市场"要等"生产需求"落地（后续批次）。
   */
  private static long sellableOf(ClassRow row, long stock, CommodityId commodity, long cycleDays) {
    if (stock <= 0L) {
      return 0L;
    }
    long reserve =
        selfNeedOf(row.population(), commodity, cycleDays) * MARKET_SELF_RESERVE_PER_MILLE / 1000L;
    return Math.max(0L, stock - reserve);
  }

  /**
   * ★ <b>有效需求</b>（见类注的"需求"）：{@code min(今日缺口, 买得起的量)}。
   *
   * <p>★★ <b>购买力那一维是 {@code 钱 × 1000 ÷ 价格}（毫商品）</b>：价格的口径是"毫计价货币 / 1 商品单位"（见 {@code Market}
   * 的类注），而余额是毫单位 ⇒ 这样换算出来的量与缺口同量纲。★ 除法向下取整 ⇒ 买得起多少就买多少， 绝不会算出一个付不起的数（成交那一步的向上取整仍不超过余额 —— 见 {@link
   * #trade}）。
   */
  private static long effectiveDemandOf(
      ClassRow row,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      LinkedHashMap<CohortKey, Map<CommodityId, Long>> unmetToday,
      Map<CohortKey, FlowRow> flows,
      CohortKey key,
      CommodityId commodity,
      CurrencyId numeraire,
      long price) {
    // ★★ **H5 ①：需求窗口 = 本期（整周期）剩余需求**（见类注的算式与理由）：
    //   本周期累计缺口（流水里的 + 今天还没并进去的） − 现在手上的库存，下限 0。
    FlowRow acc = flows.get(key);
    long cycleUnmet =
        (acc == null ? 0L : acc.unmetNeed().getOrDefault(commodity, 0L))
            + unmetToday.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
    long gap = Math.max(0L, cycleUnmet - stockOf(householdGoods, key, commodity));
    if (gap <= 0L) {
      return 0L; // 本周期不缺口（或手上的存货已经够补上）⇒ 没有需求（★ 不是"想囤一点"—— 囤积是另一套制度）
    }
    long affordable =
        moneyOf(householdMoney, key, numeraire) * EconomySettlement.MILLI_PER_GRAIN / price;
    return Math.min(gap, affordable);
  }

  /** 该商品"整周期自然需求"的唯一算法（毫单位）：粮走口粮口径、布走衣着口径、其余为 0（见 {@link #sellableOf}）。 */
  private static long selfNeedOf(long population, CommodityId commodity, long cycleDays) {
    if (commodity.equals(EconomySettlement.GRAIN)) {
      return EconomyVocabulary.cumulativeRationMilli(population, cycleDays);
    }
    if (commodity.equals(EconomySettlement.CLOTH)) {
      return EconomyVocabulary.cumulativeClothMilli(population, cycleDays);
    }
    return 0L;
  }

  /** 该家户的周期天数（没有记录 ⇒ 0 ⇒ 自留 0；见 {@code EconomySettlement.cycleDaysByHousehold}）。 */
  private static long cycleDaysOf(Map<CohortKey, Long> cycleDaysByHousehold, CohortKey key) {
    return cycleDaysByHousehold.getOrDefault(key, 0L);
  }

  /** 某家户在某商品上的余额（没有这个键 ⇒ 0）—— 与 {@code EconomySettlement.stockOf} 同一条口径。 */
  private static long stockOf(
      Map<CohortKey, Map<CommodityId, Long>> householdGoods, CohortKey key, CommodityId commodity) {
    return householdGoods.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 某家户在某币种上的余额（没有这个键 ⇒ 0）—— 与 {@code EconomySettlement.moneyOf} 同一条口径。 */
  private static long moneyOf(
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney, CohortKey key, CurrencyId currency) {
    return householdMoney.getOrDefault(key, Map.of()).getOrDefault(currency, 0L);
  }
}
