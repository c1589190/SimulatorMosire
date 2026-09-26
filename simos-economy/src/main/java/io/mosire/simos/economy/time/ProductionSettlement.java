package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ <b>一次生产关账的结算「计算」</b>（S1 阶段 4+5 Task 3；spec §四 ①→⑤ 里"怎么分"那一步）。
 *
 * <p>★★ <b>纯函数、无 IO、无历史余额</b>：输入 = 一条关系（{@link ProductionRelation}）+ 本周期的事实（{@link Facts}）， 输出 =
 * <b>转移</b>（{@link Transfer}，每条实付一条）+ <b>逐规则的应付/实付/欠读数</b>（{@link RuleSettlement}）+ 待 S2/S4
 * 的货币规则（{@link Outcome#deferredMoney()}）。它<b>不碰状态</b>：账户怎么落盘是 app 协调器的事 （{@code
 * OwnershipBooks}，T5）、家户的账怎么落是 economy 的 {@code harvest}（H1 起它落进会话工作副本）。
 *
 * <p>★★ <b>公式表（判据就是它；数量一律毫单位、整数、向下取整）</b>——★ <b>H2（裁定 D5-B）起按 {@code pool × weight} 两维读</b>（改前是单个
 * {@code basis}；旧五档的逐档映射见 {@code Basis} 的类注）：
 *
 * <table border="1">
 *   <caption>规则 × pool × weight → 数量</caption>
 *   <tr><th>规则</th><th>{@code pool}</th><th>{@code weight}</th><th>数量</th></tr>
 *   <tr><td>{@link RuleType#SELF_RETENTION}</td><td>（不读）</td><td>（不读）</td><td>{@code 0}（<b>不动</b>；余额归 {@code residualOwner}）</td></tr>
 *   <tr><td>{@link RuleType#OUTPUT_SHARE}</td><td>{@link Pool#GROSS_OUTPUT}</td><td>{@link Weight#NONE}</td><td>{@code gross_j × rate ÷ 1000}</td></tr>
 *   <tr><td>{@link RuleType#OUTPUT_SHARE}</td><td>{@link Pool#NET_AFTER_INPUTS}</td><td>{@link Weight#NONE}</td><td>{@code net_j × rate ÷ 1000}</td></tr>
 *   <tr><td>{@link RuleType#OUTPUT_SHARE}</td><td>{@link Pool#OPERATOR_SURPLUS}</td><td>{@link Weight#NONE}</td><td>{@code (net_j − 已付_j) × rate ÷ 1000}（★ 已付按 priority 序累计 ⇒ 次序是数据）</td></tr>
 *   <tr><td>{@link RuleType#OUTPUT_SHARE}</td><td>{@link Pool#NET_AFTER_INPUTS}</td><td>{@link Weight#LABOR_AMOUNT}</td><td>{@code net_j × rate ÷ 1000 × 本受方劳动 ÷ Σ劳动}（Σ 取 {@code laborOfCohort} 全体）</td></tr>
 *   <tr><td>{@link RuleType#FIXED_IN_KIND_PER_LABOR}</td><td>{@link Pool#NET_AFTER_INPUTS}</td><td>{@link Weight#LABOR_AMOUNT}</td><td>{@code ⌊本受方劳动 ÷ 1000⌋ × fixedAmount}</td></tr>
 *   <tr><td>{@link RuleType#FIXED_IN_KIND_RENT}</td><td>{@link Pool#FIXED_AMOUNT}</td><td>{@link Weight#NONE}</td><td>{@code fixedAmount}（每周期一笔）</td></tr>
 *   <tr><td>{@code FIXED_MONEY_*}</td><td>（不读）</td><td>（不读）</td><td><b>不产生任何转移</b>，进 {@code deferredMoney}（I5.3）</td></tr>
 * </table>
 *
 * <p>★ 表里的除法**按写法从左到右**逐步向下取整（先 {@code × rate ÷ 1000}，再乘本受方量、除以总量）； {@code Σ量 = 0} 那一路
 * <b>归零、不除零</b>（同 {@code EconomySettlement} 的"人口为 0 ⇒ 劳动 0"口径）。 ★ <b>受方不在对应的账里</b>（ cohort 查不到劳动量 /
 * actor 查不到资产量）⇒ 该条<b>归零</b>、不产生转移（R7）；本阶段<b>没有 actor 劳动账</b> ⇒ {@code OUTPUT_SHARE ×
 * NET_AFTER_INPUTS + LABOR_AMOUNT} 的 actor 受方只能拿到 0（如实记：这是 S1 的边界，不是静默兜底）。
 *
 * <p>★★ <b>次序 = 数据</b>：按 {@code priority} <b>升序</b>、<b>同值按规则在 {@code rules} 里的先后</b>（稳定排序 ⇒ 可复现）。
 * 唯一<b>读"已付"</b>的一档是 {@link Pool#OPERATOR_SURPLUS} ⇒ 两条规则的次序一换、实得数就不同（I5.4 的载体）。
 *
 * <p>★★ <b>付款上限（R6）= 本周期收到的产出</b>：每条实付 = {@code min(应付, 可用)}，可用 = {@code net_j − 已付_j}。 要得比产出多 ⇒
 * <b>实付 = 产出</b>（<b>不抛、不造账、不留索取权</b>）；实付为 0 的那条<b>不产生转移</b>。 ★ 正因为上限只看本周期，本方法<b>不需要任何历史余额</b>（→
 * {@link Facts} 里没有"期初库存"这种东西）。
 *
 * <p>★★ <b>H2：每条实付 = 一条 {@link Transfer}（{@code from=operator, to=受方}，原因 {@link
 * TransferReason#RELATION_PAYMENT}）</b>——改前是两条"一 actor × 一商品"的条目（转出 + 收入）。 一条转移自带两端与方向 ⇒
 * "同一份产出在账上多一份/少一份"这类错<b>在类型上</b>就更难发生（受方恒 actor：裁定 D1-A）。
 *
 * <p>★★ <b>H2：应付/实付/欠三数分别报出来</b>（裁定 S4）——每条<b>非货币</b>规则一条 {@link RuleSettlement} 读数 （{@code
 * 应付}、{@code 实付}、{@code 欠 = 应付 − 实付}），<b>连实付 0 的那些也报</b> （否则"制度规定 30%、实付 0"这件最要紧的事 恰好看不见）。★
 * 这些读数<b>只读</b>：本批<b>不落债权</b>（"记欠"开关未定，见 {@code target-economic-cycle-design} §七-4）， 也不影响任何守恒式。
 *
 * <p>★★ <b>两条落点（H1 起；裁定 D1-A 的红利：所有受方都是 actor）</b>：
 *
 * <ul>
 *   <li><b>付方</b>恒为 {@code relation.operator()}、恒落 {@code facts.location()} 这一格；
 *   <li><b>受方</b>（{@code ToActor} 与 {@code ToCohort} <b>合流成同一条</b>）：{@code ToActor} 用规则里的 {@code
 *       actor}， {@code ToCohort} 用 {@code HouseholdActors.of(cohort)} —— <b>家户就是那批人的 actor</b>。
 * </ul>
 *
 * <p>★★ <b>E14 的守卫（fail-closed，本类存在的理由之一）</b>：<b>规则指名的商品必须在该产业的产出表里</b> （{@link
 * Facts#outputPerUnit()}）。★ 依据：GM 把 {@code household}（布规则）套在<b>农业</b>产业上时，若没有这条守卫， 那条规则会<b>静默付
 * 0</b> —— 无人察觉，正是本仓最反对的形态。故它在<b>任何数量计算之前</b>判死（与"本期产了多少"无关： 产出表里有、本期产 0 仍然合法）。
 *
 * <p>★ <b>未登记的 (type × pool × weight) 组合</b>也 fail-closed（裁定 E11：组合的落点就是这张公式表）。★ 两类组合<b>不受</b>此判 ——
 * {@code FIXED_MONEY_*}（不结算）与 {@code SELF_RETENTION}（数量恒 0）：它们<b>不读池与权重</b>，而"没读的字段不判"。
 *
 * <p>★ <b>"受方的家户行不存在"不在这里判</b>：本类是纯计算，看不见行、也看不见家户账。该判据在 {@code
 * EconomySettlement.harvest}（那里同时看得见关系、行与会话工作副本），**fail-closed**。
 *
 * <p>★ <b>本阶段如实不做的</b>（未达成项，落点见台账）：§2.5 的超额上限（{@code receipt = min(应得, 需求)}，阶段 6）、 cohort
 * 侧索取权、跨周期结转与欠租（S2 的 ledger）、以及"欠款按规则开关落成债权"（开关的默认值未定）。 ★ {@link Facts#inputs()}
 * <b>本阶段没有任何公式读它</b>（表里没有用到它的档）—— 留着是因为它是本周期的事实的一部分。
 */
public final class ProductionSettlement {

  /** 千分率的分母（{@code ratePerMille} 是千分数）。 */
  private static final long PER_MILLE = 1000L;

  private ProductionSettlement() {}

  /**
   * ★★ <b>转移凭据的铸造口</b>（H2）：结算只回答"这条转移<b>是什么</b>"，而 <b>id（{@code tr-<day>-<seq>}）与日号由当天的 ledger
   * 累加器盖</b>（{@code ProductionLedger.Accumulator#mint}）—— ★ 序号是"当天该账本内第几条"，只有那个累加器 知道 ⇒
   * 分配点<b>恰一处</b>，重放/分支可比。
   *
   * <p>★ 纯函数调用方（夹具、公式读法）走 {@link #settle(ProductionRelation, Facts)}：它自持一个日号为 0（创世）、序号自 1 起的 铸造口 ——
   * <b>落账的路径一律传当天的累加器</b>。
   */
  @FunctionalInterface
  public interface TransferMint {

    /** 铸一条转移（{@code money} 腿与 {@code settles} 本批恒空：H4 才有货币、清偿留待 H5）。 */
    Transfer mint(
        ActorRef from,
        ActorRef to,
        HexCoord location,
        Map<CommodityId, Long> goods,
        TransferReason reason);
  }

  /**
   * 一条产权条目：{@code +} 收 / {@code −} 付（毫单位）。<b>账户 = (actor, location)</b>。
   *
   * <p>★★ <b>H2 起它只剩一种用途：产出计提</b>（{@code +净产 → operator}）—— 三条搬运路径的腿<b>全部改走 {@link Transfer}</b>。 ★
   * <b>为什么产出不是一条转移</b>：产出是<b>造出来</b>的，没有对端；而 {@code Transfer} 的两端恒为 actor、且<b>不许相等</b>（自转移是坏数据）⇒
   * "净产入 operator"没有合法的 {@code from}。 故它留在账上作<b>产出计提读数</b> （守恒式里与毛产/损耗同族），而"东西从 A 到 B"这件事只有 {@code
   * Transfer} 一个拼写点。
   *
   * @param actor 账户主体；不得为 null
   * @param location 账户所在格；不得为 null
   * @param commodity 商品；不得为 null
   * @param delta 增减（毫单位；符号有意义：{@code > 0} 收、{@code < 0} 付）
   */
  public record ActorEntry(ActorRef actor, HexCoord location, CommodityId commodity, long delta) {

    public ActorEntry {
      if (actor == null) {
        throw new IllegalArgumentException("ActorEntry.actor 不得为 null");
      }
      if (location == null) {
        throw new IllegalArgumentException("ActorEntry.location 不得为 null（账户 = (actor, location)）");
      }
      if (commodity == null) {
        throw new IllegalArgumentException("ActorEntry.commodity 不得为 null");
      }
    }
  }

  /**
   * ★★ <b>一条规则的"实得读数"</b>（H2；裁定 S4）：这条规则<b>要了多少</b>、<b>真的付了多少</b>、<b>还欠多少</b>。
   *
   * <p>★★ <b>为什么三个数都要报</b>：只报实付，"制度规定 30%、实付 17%"与"制度规定 17%"在账上<b>完全一样</b> ——
   * 而这两件事的含义相反（前者是付款上限/产出不够咬合，后者是制度本身如此）。★ <b>实付 0 的那些也报</b>：那正是最该被看见的一条。
   *
   * <p>★★ <b>它只是读数，不是债权</b>（本批）：{@link #owed()} <b>不落</b>任何索取权（"欠款按规则开关落成债权"的开关 默认值未定，见 {@code
   * target-economic-cycle-design} §七-4）—— 本类既不产生 {@code Debt}、也不产生 {@code Claim}， 不改任何守恒式。
   *
   * @param rule 这一条规则（付款次序里的那一条）；不得为 null
   * @param commodity 这条规则的实物商品（★ 货币档进 {@code deferredMoney}、<b>没有</b>读数：它今天压根不结算）；不得为 null
   * @param dueAmount 应付（毫单位；{@code ≥ 0}）
   * @param paidNow 实付（毫单位；{@code ∈ [0, 应付]} —— 上限是 R6 的"本周期可用"）
   */
  public record RuleSettlement(
      CompensationRule rule, CommodityId commodity, long dueAmount, long paidNow) {

    public RuleSettlement {
      if (rule == null) {
        throw new IllegalArgumentException("RuleSettlement.rule 不得为 null");
      }
      if (commodity == null) {
        throw new IllegalArgumentException("RuleSettlement.commodity 不得为 null（货币档没有读数）");
      }
      if (dueAmount < 0L) {
        throw new IllegalArgumentException("RuleSettlement.dueAmount 不得为负: " + dueAmount);
      }
      if (paidNow < 0L || paidNow > dueAmount) {
        throw new IllegalArgumentException(
            "RuleSettlement.paidNow 必须在 [0, 应付] 里（R6 的上限就是应付与可用取小）: 应付="
                + dueAmount
                + " 实付="
                + paidNow);
      }
    }

    /** <b>欠 = 应付 − 实付</b>（毫单位；{@code ≥ 0}）。★ 派生量不落成字段：两个数各自只有一处拼写点。 */
    public long owed() {
      return dueAmount - paidNow;
    }
  }

  /**
   * 一次关账的结算结果。
   *
   * @param transfers 实付转移（<b>按付款次序</b>；每条实付一条 {@code from=operator, to=受方}）
   * @param ruleSettlements <b>逐规则的实得读数</b>（付款次序；含实付为 0 的那些；★ 货币档不在里面）
   * @param deferredMoney 待 S2/S4 的货币规则（<b>按付款次序</b>；它们<b>不</b>产生 {@link #transfers()} 里的任何东西）
   */
  public record Outcome(
      List<Transfer> transfers,
      List<RuleSettlement> ruleSettlements,
      List<CompensationRule> deferredMoney) {

    public Outcome {
      if (transfers == null) {
        throw new IllegalArgumentException("Outcome.transfers 不得为 null（没有转移请给空表）");
      }
      if (ruleSettlements == null) {
        throw new IllegalArgumentException("Outcome.ruleSettlements 不得为 null（没有读数请给空表）");
      }
      if (deferredMoney == null) {
        throw new IllegalArgumentException("Outcome.deferredMoney 不得为 null（没有待办请给空表）");
      }
      transfers = List.copyOf(requireNoNulls(transfers, "Outcome.transfers"));
      ruleSettlements = List.copyOf(requireNoNulls(ruleSettlements, "Outcome.ruleSettlements"));
      deferredMoney = List.copyOf(requireNoNulls(deferredMoney, "Outcome.deferredMoney"));
    }
  }

  /**
   * 结算的事实输入：<b>全部来自本周期</b>（付款上限见 R6 ⇒ 不需要任何历史余额）。
   *
   * <p>★★ <b>{@code outputPerUnit} 是 E14 的守卫输入</b>（= {@code Industry.outputPerUnit}）：本类
   * <b>只读它的键</b>（"这个产业产不产这种商品"）。★ <b>绝不用它算数量</b> —— 数量一律取自 {@code gross} / {@code net} （同一件事两处拼写 =
   * 两份会漂开的真相）。
   *
   * <p>★ 六张表各自判 null 即抛、键值非 null、<b>逐值 ≥ 0</b>（负数不是一种数量，同 {@code CompensationRule.fixedAmount}
   * 的口径）：否则"实付"会变成反向的收。
   *
   * @param location 关账那一格（付方账户落在它上面）；不得为 null
   * @param gross 本期毛产（逐商品，毫单位）
   * @param net 本期净产（扣损耗后；毫单位）—— 它同时是<b>付款上限</b>（R6）
   * @param inputs 本期现扣投入（★ <b>本阶段没有公式读它</b>，如实记；阶段 6/7 投入改从 operator 扣时才进场）
   * @param laborOfCohort 本期各 cohort 的劳动量（{@code LABOR_AMOUNT} 那一族的分子/分母）
   * @param outputPerUnit 该产业的产出表（E14 的守卫只读键）
   */
  public record Facts(
      HexCoord location,
      Map<CommodityId, Long> gross,
      Map<CommodityId, Long> net,
      Map<CommodityId, Long> inputs,
      Map<CohortKey, Long> laborOfCohort,
      Map<CommodityId, Long> outputPerUnit) {

    public Facts {
      if (location == null) {
        throw new IllegalArgumentException("Facts.location 不得为 null");
      }
      // ★ 不可变写在**赋值处**（照 Industry.outputPerUnit 的先例）：门禁的 EI_EXPOSE_REP 只看得到
      //   赋值处字面上的 Collections.unmodifiableMap，看不出"校验助手返回的是一份不可变副本"。
      gross = Collections.unmodifiableMap(requireQuantities(gross, "Facts.gross"));
      net = Collections.unmodifiableMap(requireQuantities(net, "Facts.net"));
      inputs = Collections.unmodifiableMap(requireQuantities(inputs, "Facts.inputs"));
      laborOfCohort =
          Collections.unmodifiableMap(requireQuantities(laborOfCohort, "Facts.laborOfCohort"));
      outputPerUnit =
          Collections.unmodifiableMap(requireQuantities(outputPerUnit, "Facts.outputPerUnit"));
    }
  }

  /**
   * ★★ <b>把一条关系按公式表结清</b>（纯函数；见类注的公式表、次序、上限与两条落点）。
   *
   * <p>★ 本重载<b>自持铸造口</b>（日号 0 = 创世、序号自 1 起）：它服务"不落账的读法"（夹具、纯函数调用）。 <b>落账的路径一律走 {@link
   * #settle(ProductionRelation, Facts, TransferMint)}</b>，把当天的 ledger 累加器传进来 —— 那样 id
   * 的序号才是"当天该账本内第几条"。
   *
   * @param relation 生产关系（身份 = 它结算的那个 activity；付方恒为它的 {@code operator}）；不得为 null
   * @param facts 本周期的事实；不得为 null
   * @throws IllegalArgumentException 规则指名的商品不在 {@link Facts#outputPerUnit()} 里（E14）、 或者规则的 (type ×
   *     pool × weight) 组合在公式表里没有登记（E11）—— 两者都<b>当场抛</b>，不静默兜底
   */
  public static Outcome settle(ProductionRelation relation, Facts facts) {
    return settle(relation, facts, selfMint());
  }

  /**
   * ★★ <b>把一条关系按公式表结清，并把每条实付铸成一条转移</b>（H2：转移凭据的唯一来源）。
   *
   * @param mint 转移凭据的铸造口（**落账路径 = 当天的 ledger 累加器**，见 {@link TransferMint}）；不得为 null
   */
  public static Outcome settle(ProductionRelation relation, Facts facts, TransferMint mint) {
    if (relation == null) {
      throw new IllegalArgumentException("relation 不得为 null");
    }
    if (facts == null) {
      throw new IllegalArgumentException("facts 不得为 null");
    }
    if (mint == null) {
      throw new IllegalArgumentException("mint 不得为 null（没有铸造口就没有转移凭据）");
    }
    List<CompensationRule> ordered = inPaymentOrder(relation.rules());
    requireProducibleCommodities(ordered, relation, facts); // ★ E14：在任何数量计算之前
    Map<CommodityId, Long> paid = new LinkedHashMap<>(); // 已付（逐商品；R6 的"可用"就靠它）
    List<Transfer> transfers = new ArrayList<>();
    List<RuleSettlement> readings = new ArrayList<>();
    List<CompensationRule> deferred = new ArrayList<>();
    for (CompensationRule rule : ordered) {
      OptionalLong due = dueAmount(rule, facts, paid);
      if (due.isEmpty()) {
        deferred.add(rule); // ★ I5.3：货币档**只定义、不结算**（不产生任何转移）
        continue;
      }
      CommodityId commodity = commodityOf(rule);
      long available =
          Math.max(0L, facts.net().getOrDefault(commodity, 0L) - paidOf(paid, commodity));
      long paidNow = Math.min(due.getAsLong(), available); // ★ R6：付款上限 = 本周期收到的产出
      // ★★ S4：读数**先记**（连实付 0 的那些）—— 只报实付会让"制度要得多、实际付不出"这件事在账上消失。
      readings.add(new RuleSettlement(rule, commodity, due.getAsLong(), paidNow));
      if (paidNow <= 0L) {
        continue; // 归零 ⇒ 不产生转移（自留的 0、付不出的 0、受方不在账里的 0 都走这一支）
      }
      paid.merge(commodity, paidNow, Long::sum);
      // ★★ H2：实付 = 一条 `from=operator → to=受方` 的转移（受方恒为 actor —— 裁定 D1-A）。
      //   {@code ToCohort} 的家户 actor 由 {@link HouseholdActors#of(CohortKey)} 给出（家户身份的唯一拼写点，K9）。
      ActorRef recipient =
          switch (rule.recipient()) {
            case Recipient.ToActor toActor -> toActor.actor();
            case Recipient.ToCohort toCohort -> HouseholdActors.of(toCohort.cohort());
          };
      transfers.add(
          mint.mint(
              relation.operator(),
              recipient,
              facts.location(),
              Map.of(commodity, paidNow),
              TransferReason.RELATION_PAYMENT));
    }
    return new Outcome(transfers, readings, deferred);
  }

  /**
   * ★ <b>I5.3 的读口</b>：把一条被推迟的货币规则渲染成一句可读的话（读口 / MCP / 日志用）。
   *
   * <p>★★ <b>「待 S2」这句口径的唯一拼写点在本方法里</b>：别处再写一遍就是同一个格式的第二处拼写点。 ★
   * 货币规则<b>只定义、不结算</b>是<b>必须报出来的事实</b>（静默的"什么都没发生"和"没有这条规则"无法区分）。 ★ H2 起这句话里带上<b>币种</b> （钱的种类必须说清
   * —— "1000 毫钱"没有意义）。
   *
   * @param rule 一条货币规则（{@code type.money()} 为 true）；非货币档 ⇒ 抛（那句话对它不成立）
   */
  public static String deferredMoneyReason(CompensationRule rule) {
    if (rule == null) {
      throw new IllegalArgumentException("rule 不得为 null");
    }
    if (!rule.type().money()) {
      throw new IllegalArgumentException("只有货币档才「待 S2」，这一条是: " + rule.type());
    }
    return "货币规则只定义、不结算（待 S2）："
        + rule.type()
        + " → "
        + rule.recipient()
        + " 每周期 "
        + rule.fixedAmount()
        + " 毫"
        + rule.currency().map(CurrencyId::value).orElse("钱（★ 币种缺失是坏数据，构造期守卫应已判死）")
        + "（S1 没有 ledger ⇒ 不假装货币结算成功）";
  }

  /** 纯函数自持的铸造口：日号 0（创世）、序号自 1 起（★ 只给不落账的调用方，见 {@link TransferMint}）。 */
  private static TransferMint selfMint() {
    long[] sequence = {0L};
    return (from, to, location, goods, reason) ->
        new Transfer(
            new TransferId("tr-0-" + (++sequence[0])),
            0L,
            from,
            to,
            location,
            goods,
            Map.of(),
            reason,
            Optional.empty());
  }

  // ── 次序 ─────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>付款次序 = 数据</b>：按 {@code priority} 升序、<b>同值按规则在 {@code rules} 里的先后</b>。
   *
   * <p>★ {@code List.sort} 是<b>稳定</b>排序 ⇒ 同值天然保表序（不必再带"表内序号"当第二关键字）；这一点是判据的一部分 （可复现），不是实现细节。
   */
  private static List<CompensationRule> inPaymentOrder(List<CompensationRule> rules) {
    List<CompensationRule> ordered = new ArrayList<>(rules);
    ordered.sort(Comparator.comparingInt(CompensationRule::priority));
    return ordered;
  }

  // ── 配置守卫（fail-closed）────────────────────────────────────────────────────────

  /**
   * ★★ <b>E14 的守卫</b>：<b>规则指名的商品必须在该产业的产出表里</b>。
   *
   * <p>★ 判的是<b>产出表</b>（"这个产业能产什么"），不是"本期产了多少" —— 表里有、本期产 0（规模 0）是合法状态。 ★ 只判<b>实物</b>规则（有 {@code
   * commodity} 的那些）：货币档没有商品可判。
   */
  private static void requireProducibleCommodities(
      List<CompensationRule> ordered, ProductionRelation relation, Facts facts) {
    for (CompensationRule rule : ordered) {
      if (rule.commodity().isEmpty()) {
        continue;
      }
      CommodityId commodity = rule.commodity().get();
      if (!facts.outputPerUnit().containsKey(commodity)) {
        throw new IllegalArgumentException(
            "规则指名的商品不在该产业的产出表里（E14）：activity="
                + relation.activity()
                + " commodity="
                + commodity
                + " 该产业的 outputPerUnit="
                + facts.outputPerUnit().keySet()
                + " —— 静默付 0 是本仓最反对的形态，故当场抛（规则的商品要么改、要么改 relation）"
                + "；规则="
                + rule);
      }
    }
  }

  /**
   * 公式表里<b>没有登记的 (type × pool × weight) 组合</b> ⇒ 抛（裁定 E11：组合守卫的落点就是这张表）。
   *
   * <p>★ 消息里列出已登记的档与这一条实际填的两个字段（同 {@code RuleType.parse} 列词表的形制）—— 写错的人当场看得见该写哪一档。
   */
  private static IllegalArgumentException unregisteredCombination(CompensationRule rule) {
    return new IllegalArgumentException(
        "未登记的规则组合（公式表里没有这一档）：type="
            + rule.type()
            + " pool="
            + rule.pool()
            + " weight="
            + rule.weight()
            + "；已登记的档: OUTPUT_SHARE × {GROSS_OUTPUT+NONE, NET_AFTER_INPUTS+NONE,"
            + " OPERATOR_SURPLUS+NONE, NET_AFTER_INPUTS+LABOR_AMOUNT} ·"
            + " FIXED_IN_KIND_PER_LABOR × NET_AFTER_INPUTS+LABOR_AMOUNT ·"
            + " FIXED_IN_KIND_RENT × FIXED_AMOUNT+NONE（SELF_RETENTION 与 FIXED_MONEY_* 不读池与权重）");
  }

  // ── 公式表 ───────────────────────────────────────────────────────────────────────

  /**
   * 一条规则的<b>应付量</b>；货币档返回 {@link OptionalLong#empty()}（⇒ 进 {@code deferredMoney}，不产生转移）。
   *
   * <p>★ {@code SELF_RETENTION} 的数量恒为 {@code 0}（不读池与权重）—— 它经 {@link #dueAmount} 走到"实付 0 ⇒ 不产生转移"
   * 那一支，于是余额落在 {@code residualOwner} 手上（"不动"）。
   */
  private static OptionalLong dueAmount(
      CompensationRule rule, Facts facts, Map<CommodityId, Long> paid) {
    return switch (rule.type()) {
      case FIXED_MONEY_WAGE, FIXED_MONEY_RENT -> OptionalLong.empty(); // ★ I5.3
      case SELF_RETENTION -> OptionalLong.of(0L); // ★ 公式表：0（不动；余额归 residualOwner）
      case OUTPUT_SHARE -> OptionalLong.of(shareOf(rule, facts, paid));
      case FIXED_IN_KIND_PER_LABOR -> OptionalLong.of(perLabor(rule, facts));
      case FIXED_IN_KIND_RENT -> OptionalLong.of(fixedRent(rule));
    };
  }

  /**
   * {@code OUTPUT_SHARE}：{@code pool} 说数量取自哪一层、{@code weight} 说池在受方之间怎么分 （★ H2
   * 起是两个正交字段；表里没登记的组合一律抛）。
   */
  private static long shareOf(CompensationRule rule, Facts facts, Map<CommodityId, Long> paid) {
    CommodityId commodity = commodityOf(rule);
    long net = facts.net().getOrDefault(commodity, 0L);
    int rate = rule.ratePerMille();
    return switch (rule.pool()) {
      case GROSS_OUTPUT ->
          requireNoWeight(rule, perMille(facts.gross().getOrDefault(commodity, 0L), rate));
      case NET_AFTER_INPUTS ->
          switch (rule.weight()) {
            case NONE -> perMille(net, rate);
            // ★★ 次序是数据：OPERATOR_SURPLUS 那一档读"已付"，而"已付"按 priority 序累计 ⇒ 换个次序，实得数就变（I5.4）。
            //   ★ 而 LABOR_AMOUNT 这一档：池仍是**净产**（× rate），劳动只是**权重**（× 本受方劳动 ÷ Σ劳动）。
            case LABOR_AMOUNT ->
                shareWithTotal(
                    perMille(net, rate),
                    laborOf(facts, rule.recipient()),
                    totalOf(facts.laborOfCohort()));
            case EQUAL -> throw unregisteredCombination(rule); // ★ 留位：今天没有公式
          };
      case OPERATOR_SURPLUS -> requireNoWeight(rule, perMille(net - paidOf(paid, commodity), rate));
      case FIXED_AMOUNT -> throw unregisteredCombination(rule);
    };
  }

  /**
   * {@code FIXED_IN_KIND_PER_LABOR}：{@code ⌊本受方劳动 ÷ 1000⌋ × fixedAmount}（★ 只按**本受方**的劳动量，不除以 Σ）。
   *
   * <p>★ 它读的是<b>权重</b>那一维（"按劳动量"）—— 池只是旧档映射带过来的（{@link Pool#NET_AFTER_INPUTS}），本档不用它算数。
   */
  private static long perLabor(CompensationRule rule, Facts facts) {
    if (rule.pool() != Pool.NET_AFTER_INPUTS || rule.weight() != Weight.LABOR_AMOUNT) {
      throw unregisteredCombination(rule);
    }
    return laborOf(facts, rule.recipient()) / PER_MILLE * rule.fixedAmount();
  }

  /** {@code FIXED_IN_KIND_RENT}：{@code fixedAmount}（每周期一笔；★ 与产出、劳动都无关 ⇒ 池是固定额、权重不适用）。 */
  private static long fixedRent(CompensationRule rule) {
    if (rule.pool() != Pool.FIXED_AMOUNT || rule.weight() != Weight.NONE) {
      throw unregisteredCombination(rule);
    }
    return rule.fixedAmount();
  }

  /** 这一档的池是"不分权重"的那几层（毛产 / 经营剩余）⇒ 权重不是 {@code NONE} 就是没登记的组（E11）。 */
  private static long requireNoWeight(CompensationRule rule, long amount) {
    if (rule.weight() != Weight.NONE) {
      throw unregisteredCombination(rule);
    }
    return amount;
  }

  /** 分成率：{@code amount × rate ÷ 1000}（整数、向下取整）。 */
  private static long perMille(long amount, int rate) {
    return amount * rate / PER_MILLE;
  }

  /**
   * 按份额分：{@code 分成后的量 × 本受方量 ÷ 总量}；<b>总量为 0 ⇒ 0（不除零）</b>。
   *
   * <p>★ 分母是<b>全体</b>（{@code laborOfCohort} 的逐值之和），<b>不是</b>"全体受方" ——
   * 制度是"这一格的产出在这些人之间怎么分"，分母当然得是这一格的全部出工/全部资产。
   */
  private static long shareWithTotal(long share, long own, long total) {
    return total == 0L ? 0L : share * own / total;
  }

  /** 本受方的<b>劳动量</b>：cohort 查 {@code laborOfCohort}；★ actor 在本阶段<b>没有劳动账</b> ⇒ 0（归零，见类注）。 */
  private static long laborOf(Facts facts, Recipient recipient) {
    return switch (recipient) {
      case Recipient.ToCohort toCohort -> facts.laborOfCohort().getOrDefault(toCohort.cohort(), 0L);
      case Recipient.ToActor ignored -> 0L;
    };
  }

  /** 一张数量表的逐值之和（{@code Σ劳动} / {@code Σ资产量}）。 */
  private static long totalOf(Map<?, Long> quantities) {
    long total = 0L;
    for (long value : quantities.values()) {
      total += value;
    }
    return total;
  }

  /** 实物规则的商品（★ {@link CompensationRule} 的"二选一"守卫已判死"非货币 ⇒ 必须有商品"，此处是兜底，不猜）。 */
  private static CommodityId commodityOf(CompensationRule rule) {
    return rule.commodity()
        .orElseThrow(
            () -> new IllegalStateException("实物规则没有商品（CompensationRule 的守卫应已判死）: " + rule));
  }

  // ── 累加器与冻结 ─────────────────────────────────────────────────────────────────

  private static long paidOf(Map<CommodityId, Long> paid, CommodityId commodity) {
    return paid.getOrDefault(commodity, 0L);
  }

  /** 逐项非空的副本（与 {@link Outcome} 的"没有就请给空表"口径同源：null 项是坏数据，不是"没有"）。 */
  private static <T> List<T> requireNoNulls(List<T> values, String field) {
    List<T> copy = new ArrayList<>(values.size());
    for (T value : values) {
      if (value == null) {
        throw new IllegalArgumentException(field + " 不得含 null 项");
      }
      copy.add(value);
    }
    return copy;
  }

  /**
   * 数量表：<b>键值非 null、逐值 ≥ 0</b>，返回<b>保序的副本</b>（不可变由调用方的赋值处加 —— 见 {@link Facts} 的紧凑构造器）。
   *
   * @throws IllegalArgumentException 表为 null、含 null 键值、或含负数
   */
  private static <K> Map<K, Long> requireQuantities(Map<K, Long> quantities, String field) {
    if (quantities == null) {
      throw new IllegalArgumentException(field + " 不得为 null（没有就用空 map）");
    }
    Map<K, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<K, Long> entry : quantities.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(field + " 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            field + " 的数量不得为负（负数不是一种数量）：" + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }
}
