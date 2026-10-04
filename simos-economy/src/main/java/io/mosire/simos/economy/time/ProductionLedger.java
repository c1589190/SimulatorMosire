package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★ <b>一天结算里"离开 {@code ClassRow} 的那些发生额"</b>（S1 阶段 4+5 Task 4；spec §四 ①→⑤ 的账）。
 *
 * <p>★★ <b>它为什么必须存在</b>（本阶段最要紧的一件事）：产出<b>不再写进阶层行</b>（R5 ②）—— 它变成产权条目/转移（{@code actor}
 * 账上的增减）。这一样<b>不是 economy 切片自己的数据</b>： 产权住在 {@code simos-actor}，而"谁把条目/转移落到账上"必须由<b>同时看得见两片</b>的
 * app 协调器来做（R4/E7）。 于是 economy 侧交出的就是它 —— <b>一天一本账</b>，一个字段不少，且<b>绝不静默丢弃</b>。
 *
 * <p>★★ <b>它是"一天"的，不是"一个周期"的</b>（旧日推进器的 step(long)（R3a 已删除） 的返回值）：协调器逐步 把它落到账上 ⇒
 * 若它跨日累计，调用方就会<b>重复落账</b>。需要"整周期"的读数由调用方自己按天攒。
 *
 * <p>★★ <b>七件</b>（逐件与判据对应；★ H1.3 删掉了原来的第六件 {@code cohortIntake}，★ H2 把 {@code actorEntries} 拆成"产出计提
 * + 转移"并补上"逐规则读数"）：
 *
 * <ul>
 *   <li>{@link #gross()} 本期毛产（逐产业 × 逐商品）—— I4.2 的 {@code ΣOutput}；
 *   <li>{@link #losses()} 本期生产损耗（饲料 0‰ + 折旧 30‰）—— I4.2 的 {@code ΣLoss}； ★ 它<b>不再进 {@code
 *       FlowRow.consumed}</b>：损耗不是"谁消费了"，是"蒸发了"，在守恒式里自成一项（R1）；
 *   <li>{@link #inputs()} 本期现扣周期投入 —— I4.2 的 {@code ΣProductionInputs}。★★ <b>H1
 *       起投入从家户账（会话工作副本）扣</b> ⇒ 它<b>不是一条转移</b>：扣减已经写在副本上，而"扣了多少料"在这里读得出来；
 *   <li>★★ {@link #transfers()} <b>当天的转移</b>（{@link Transfer}；H2 起这是全系统唯一的"东西从 A 到 B"的事实）——
 *       含五族：<b>关系实付</b>（实物与<b>货币</b>两族；{@code operator → 受方}）、<b>投入征调</b>（{@code 供方 → 经营者}）、
 *       <b>同格借粮</b>（家户 → 家户）、<b>同格市场成交</b>（H4：一笔买卖<b>一对</b>转移 —— 货一条、钱一条）。 ★ <b>货币腿逐币种直接可读</b>（H4
 *       的逐币种守恒判据就读它，不另设一张"货币发生额"表：同一件事两处拼写必然漂开）。 ★ 协调器把它们折成 {@code (actor, location, commodity,
 *       delta)} 落到 {@code ActorData.accounts}（见 {@link #transfers()} 的注释）；
 *   <li>{@link #outputAccruals()} <b>产出计提</b>（{@code +净产 → operator}；{@link ActorEntry}）。 ★
 *       <b>它不是一条转移</b>：产出是<b>造出来</b>的、没有对端，而转移的两端恒为 actor 且不许相等 —— 理由详见 {@link ActorEntry}；
 *   <li>{@link #ruleSettlements()} <b>逐规则的实得读数</b>（应付 / 实付 / 欠；裁定 S4）。★ <b>只读</b>：不影响守恒、不落债权；
 *   <li>{@link #deferredMoney()} ★ <b>留档字段</b>（H4 起<b>恒为空表</b>）：H2/H3 装的是"只定义、不结算"的货币规则； H4
 *       起货币档真的结算（进 {@link #transfers()} 与 {@link #ruleSettlements()}）⇒ 它再没有内容 —— 保留的理由见 {@code
 *       Outcome} 的 {@code deferredMoney} 注释；
 *   <li>★★ {@link #debtCapitalizations()} <b>E4c：本日"欠租/欠薪 → 合同债权"的资本化明细</b>（逐事件；只记本金增量，
 *       <b>不搬粮/钱</b>）。它是 {@link #ruleSettlements()} 的 owed 那一侧真的落成债权之后的具名审计；
 *   <li>★★ {@link #unresolvedDebtCapitalizations()} <b>E4c：资本化跳过的具名原因</b>——付款人/受款人 actor 解析不到 家户
 *       {@code HouseholdId} 时，<b>不伪造端点</b>，把 owed 与原因留在这里（欠款读数本身仍留在 {@code ruleSettlements}）；
 *   <li>★★ {@link #debtRepaymentSkips()} <b>P11.1 / D-023：偿还跳过的具名原因</b>——债务人手头持有资产、但没有稳定价格/比价
 *       （如非本格 numeraire 的币种、没有市场价的商品）⇒ <b>不折算、不静默付 0</b>；把剩余本金与
 *       {@code unpriced-repayment-assets:…} 的资产清单记在这里。
 * </ul>
 *
 * <p>★ <b>三张表都保序不可变</b>：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，<b>绝不用 {@code
 * Map.copyOf}</b> —— 它的迭代序不是内容的纯函数。
 */
public record ProductionLedger(
    Map<IndustryId, Map<CommodityId, Long>> gross,
    Map<IndustryId, Map<CommodityId, Long>> losses,
    Map<IndustryId, Map<CommodityId, Long>> inputs,
    List<ActorEntry> outputAccruals,
    List<Transfer> transfers,
    List<RuleSettlement> ruleSettlements,
    List<CompensationRule> deferredMoney,
    List<DebtCapitalization> debtCapitalizations,
    List<UnresolvedDebtCapitalization> unresolvedDebtCapitalizations,
    List<DebtRepaymentSkip> debtRepaymentSkips,
    List<LiquidationAudit> liquidationAudits) {

  public ProductionLedger {
    // ★ 缺键按空处理（同 EconomyData 的旧档兼容口径：这里只服务"当天什么都没发生"这一形态）。
    // ★★ 不可变写在**赋值处**（照 Industry.outputPerUnit / Facts 的先例）：SpotBugs 的
    //   EI_EXPOSE_REP 不做跨过程分析，看不出"校验助手返回的是一份不可变副本"。
    gross = Collections.unmodifiableMap(freezeQuantities(gross, "gross"));
    losses = Collections.unmodifiableMap(freezeQuantities(losses, "losses"));
    inputs = Collections.unmodifiableMap(freezeQuantities(inputs, "inputs"));
    outputAccruals = outputAccruals == null ? List.of() : List.copyOf(outputAccruals);
    transfers = transfers == null ? List.of() : List.copyOf(transfers);
    ruleSettlements = ruleSettlements == null ? List.of() : List.copyOf(ruleSettlements);
    deferredMoney = deferredMoney == null ? List.of() : List.copyOf(deferredMoney);
    debtCapitalizations =
        debtCapitalizations == null ? List.of() : List.copyOf(debtCapitalizations);
    unresolvedDebtCapitalizations =
        unresolvedDebtCapitalizations == null
            ? List.of()
            : List.copyOf(unresolvedDebtCapitalizations);
    debtRepaymentSkips = debtRepaymentSkips == null ? List.of() : List.copyOf(debtRepaymentSkips);
    liquidationAudits = liquidationAudits == null ? List.of() : List.copyOf(liquidationAudits);
  }

  /**
   * ★★ <b>转移凭据的铸造口</b>（H2）：结算只回答"这条转移<b>是什么</b>"，而 <b>id（{@code tr-<day>-<seq>}）与日号由当天的 ledger
   * 累加器盖</b>（{@code ProductionLedger.Accumulator#mint}）—— ★ 序号是"当天该账本内第几条"，只有那个累加器 知道 ⇒
   * 分配点<b>恰一处</b>，重放/分支可比。
   *
   * <p>★ 纯函数调用方（夹具、公式读法）走 （旧结算引擎在 R3a 删除；本接口只服务 {@link ProductionLedger.Accumulator} 的铸造口。）
   */
  @FunctionalInterface
  public interface TransferMint {

    /**
     * 铸一条转移（{@code settles} 恒空：清偿留待 H5）。
     *
     * <p>★★ <b>H4 起货币腿真的有钱</b>：货币工资/地租铸的是<b>只带货币腿</b>的转移（{@code goods} 给空表）。 ★ 两条腿都沿 {@code from →
     * to}（见 {@code Transfer} 的货币腿口径）⇒ 一笔买卖铸<b>一对</b>转移。
     */
    Transfer mint(
        ActorRef from,
        ActorRef to,
        HexCoord location,
        Map<CommodityId, Long> goods,
        Map<CurrencyId, Long> money,
        TransferReason reason);

    /**
     * <b>纯商品转移</b>的便捷重载（没有货币腿 ⇒ 给一枚空 map）。
     *
     * <p>★ 它<b>不是</b>第二套语义：默认实现转调上面那一个，{@code money = Map.of()}。
     */
    default Transfer mint(
        ActorRef from,
        ActorRef to,
        HexCoord location,
        Map<CommodityId, Long> goods,
        TransferReason reason) {
      return mint(from, to, location, goods, Map.of(), reason);
    }
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
   * <p>★★ <b>它本身只是读数，不是债权</b>：本类不写任何索取权。★★ E4c 起 旧结算层的资本化写口（R3a 已删） 在这些读数（{@code owed > 0}）之后显式落成
   * {@code DebtContract}；资本化不改本读数，也不清零它。
   *
   * @param rule 这一条规则（付款次序里的那一条）；不得为 null
   * @param commodity 这条规则的**实物**商品（货币档为空）；与 {@code currency} <b>恰其一</b>
   * @param currency 这条规则的**货币**币种（实物档为空；H4 起货币档也有读数）；与 {@code commodity} 互为反相
   * @param dueAmount 应付（毫单位；{@code ≥ 0}）
   * @param paidNow 实付（毫单位；{@code ∈ [0, 应付]} —— 上限是 R6 的"本周期可用"：实物 = 本周期产出、货币 = 付方可见的余额）
   * @param payer ★★ <b>E4c：付款人 actor</b>（恒为 {@code relation.operator()}；不得为 null）。欠款资本化要解析到家户
   *     {@code HouseholdId}，<b>不能靠猜</b> —— 解析不到（聚合主体/外部主体）就具名跳过，不伪造端点。
   * @param payee ★★ <b>E4c：受款人 actor</b>（规则受方；{@code ToHousehold}/{@code ToCohort} 经 {@code
   *     HouseholdActors.of} 归一，{@code ToActor} 原样）；不得为 null。语义同上。
   * @param activity ★★ <b>E4c：这条欠款所属的生产单元/活动</b>（{@code ProductionRelation.activity()}）；不得为 null。
   *     它是同一天内"同一付款人 × 同一受款人 × 同一 unit × 同一规则"的两笔事件<b>不被误合成一笔</b>的事件维； {@code DebtContractId}
   *     只含四元组，事件维不参与合同身份（同四元组仍是同一条连续欠账）。
   */
  public record RuleSettlement(
      CompensationRule rule,
      Optional<CommodityId> commodity,
      Optional<CurrencyId> currency,
      long dueAmount,
      long paidNow,
      ActorRef payer,
      ActorRef payee,
      ProductionUnitId activity) {

    public RuleSettlement {
      if (rule == null) {
        throw new IllegalArgumentException("RuleSettlement.rule 不得为 null");
      }
      if (commodity == null || currency == null) {
        throw new IllegalArgumentException(
            "RuleSettlement 的 commodity / currency 都不得为 null（空要用 Optional.empty()）");
      }
      // ★★ 恰其一（H4）：与 CompensationRule 的那条反相守卫同源 —— "两处都能填"会让读口的判别力当场消失
      //   （分不清"这条付的是粮"还是"付的是银"）。
      if (commodity.isPresent() == currency.isPresent()) {
        throw new IllegalArgumentException(
            "RuleSettlement 必须恰给 commodity 或 currency 之一（同时给/都不给都是坏数据）: "
                + commodity
                + " / "
                + currency);
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
      if (payer == null || payee == null || activity == null) {
        throw new IllegalArgumentException(
            "RuleSettlement 的 payer/payee/activity 都不得为 null（E4c 的身份维，不许靠猜补）");
      }
    }

    /** <b>欠 = 应付 − 实付</b>（毫单位；{@code ≥ 0}）。★ 派生量不落成字段：两个数各自只有一处拼写点。 */
    public long owed() {
      return dueAmount - paidNow;
    }
  }

  /**
   * ★★ <b>S3：具名欠款（WageArrears / RentArrears / SubsistenceArrears）</b>—— 由 {@link RuleSettlement} 里
   * {@code owed > 0} 的那些派生；<b>不是新状态、本身也不是债权</b>（E4c 起由 旧结算层的资本化写口（R3a 已删） 显式落成合同债权），
   * 但它是"制度规定要付、实际付不出"的<b>具名可读聚合</b>：不把欠款静默当 0。
   *
   * <p>★ 它的名字按规则类型分档：{@link RuleType#FIXED_MONEY_WAGE} ⇒ {@link Kind#WAGE}（WageArrears）； 两类固定租 ⇒
   * {@link Kind#RENT}（RentArrears）；给养/实物劳动报酬 ⇒ {@link Kind#SUBSISTENCE}。
   *
   * <p>★★ <b>E4c：身份维随读数一起发出</b>（{@code payer}/{@code payee}/{@code activity}）—— 资本化必须解析出 {@code
   * HouseholdId} 两端；解析不到就具名跳过。<b>不许</b>在这里按图层/规则反推付款人（那会伪造端点）。
   */
  public record Arrear(
      CompensationRule rule,
      Kind kind,
      Optional<CommodityId> commodity,
      Optional<CurrencyId> currency,
      long dueAmount,
      long paidNow,
      long owed,
      ActorRef payer,
      ActorRef payee,
      ProductionUnitId activity) {

    /** 欠款名目（具名，不合成一个"总欠款"）。 */
    public enum Kind {
      /** 货币工资欠款（WageArrears）。 */
      WAGE,
      /** 地租欠款（RentArrears）。 */
      RENT,
      /** 给养/实物劳动报酬欠款（SubsistenceArrears）。 */
      SUBSISTENCE,
      /** 其它未偿规则（不静默丢；本批枚举里没有别的档会走到这里）。 */
      OTHER
    }

    public Arrear {
      if (rule == null || kind == null || commodity == null || currency == null) {
        throw new IllegalArgumentException("Arrear 的字段不得为 null");
      }
      if (dueAmount < 0L || paidNow < 0L || paidNow > dueAmount) {
        throw new IllegalArgumentException("Arrear 的实付必须在 [0, 应付] 里: " + dueAmount + "/" + paidNow);
      }
      if (owed != dueAmount - paidNow) {
        throw new IllegalArgumentException(
            "Arrear.owed 必须逐值等于 应付 − 实付: " + owed + " != " + (dueAmount - paidNow));
      }
      if (payer == null || payee == null || activity == null) {
        throw new IllegalArgumentException(
            "Arrear 的 payer/payee/activity 都不得为 null（E4c 的身份维，不许靠猜补）");
      }
    }

    /** 从一条逐规则读数派生（{@code owed == 0} ⇒ 调用方应过滤）；身份维逐值带过。 */
    public static Arrear of(RuleSettlement reading) {
      Objects.requireNonNull(reading, "reading");
      return new Arrear(
          reading.rule(),
          kindOf(reading.rule().type()),
          reading.commodity(),
          reading.currency(),
          reading.dueAmount(),
          reading.paidNow(),
          reading.owed(),
          reading.payer(),
          reading.payee(),
          reading.activity());
    }

    /** 规则的欠款名目（唯一分档点）。 */
    public static Kind kindOf(RuleType type) {
      if (type == RuleType.FIXED_MONEY_WAGE) {
        return Kind.WAGE;
      }
      if (type == RuleType.FIXED_IN_KIND_RENT || type == RuleType.FIXED_MONEY_RENT) {
        return Kind.RENT;
      }
      if (type == RuleType.FIXED_IN_KIND_PER_LABOR) {
        return Kind.SUBSISTENCE;
      }
      return Kind.OTHER;
    }
  }

  /** 一天什么都没有发生（既没关账、也没有任何转移）。 */
  public static ProductionLedger empty() {
    return new ProductionLedger(
        Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of());
  }

  /**
   * ★★ <b>E4c：一条欠租/欠薪资本化的审计条目</b>（逐事件合并后的一次合同写）。
   *
   * <p>★★ {@code eventCount} 是本次写入合并的 {@code Arrear} 读数条数：同一天内 {@code (payer, payee, unit,
   * activity, rule)} 相同的读数按 {@link DebtContractId} 的四元组本就是同一条连续欠账， 故按该键<b>稳定去重并累加</b>；{@code
   * principalAfter} 是写后的合同本金（读口据此与合同表对账）。
   *
   * <p>★ {@code termsSource} = {@code "E4c_LEGACY_DEFAULT"}：E4c 的资本化条款明确取 {@link
   * DebtTerms#legacyDefault()}；这不是"未知条款静默并入默认档"——Arrear 读数本身不携带条款， E4c
   * 也没有第二条带条款的资本化路径。将来若要按规则/合同给不同条款，必须先让 {@code Arrear} 携带条款再新开分支。
   */
  public record DebtCapitalization(
      ActorRef payer,
      ActorRef payee,
      ProductionUnitId activity,
      CompensationRule rule,
      HouseholdId debtor,
      HouseholdId creditor,
      DebtContractId contractId,
      DebtUnit unit,
      long amount,
      long day,
      long dueCycle,
      DebtTerms terms,
      String termsSource,
      long principalAfter,
      int eventCount) {

    public DebtCapitalization {
      if (payer == null || payee == null || activity == null || rule == null) {
        throw new IllegalArgumentException(
            "DebtCapitalization 的 payer/payee/activity/rule 不得为 null");
      }
      if (debtor == null
          || creditor == null
          || contractId == null
          || unit == null
          || terms == null) {
        throw new IllegalArgumentException(
            "DebtCapitalization 的 debtor/creditor/contractId/unit/terms 不得为 null");
      }
      if (amount <= 0L || eventCount <= 0) {
        throw new IllegalArgumentException(
            "DebtCapitalization 的 amount/eventCount 必须 > 0: " + amount + "/" + eventCount);
      }
      if (day < 0L || dueCycle < 0L || principalAfter < amount) {
        throw new IllegalArgumentException(
            "DebtCapitalization 的 day/dueCycle/principalAfter 非法: "
                + day
                + "/"
                + dueCycle
                + "/"
                + principalAfter
                + " amount="
                + amount);
      }
      if (termsSource == null || termsSource.isBlank()) {
        throw new IllegalArgumentException("DebtCapitalization.termsSource 不得空白");
      }
    }
  }

  /**
   * ★★ <b>E4c：资本化被跳过的具名条目</b>——欠款读数仍然有效（制度规定未付），只是无法解析出合同的债务人/债权人端。 {@code reason} 是唯一可读的原因串（如
   * {@code payer-not-household} / {@code payee-not-household}）；不许伪造端点。
   */
  public record UnresolvedDebtCapitalization(
      ActorRef payer,
      ActorRef payee,
      ProductionUnitId activity,
      CompensationRule rule,
      Optional<CommodityId> commodity,
      Optional<CurrencyId> currency,
      long owed,
      String reason) {

    public UnresolvedDebtCapitalization {
      if (payer == null || payee == null || activity == null || rule == null) {
        throw new IllegalArgumentException(
            "UnresolvedDebtCapitalization 的 payer/payee/activity/rule 不得为 null");
      }
      if (commodity == null || currency == null) {
        throw new IllegalArgumentException(
            "UnresolvedDebtCapitalization 的 commodity/currency 不得为 null（空用 Optional.empty()）");
      }
      if (commodity.isPresent() == currency.isPresent()) {
        throw new IllegalArgumentException(
            "UnresolvedDebtCapitalization 必须恰给 commodity 或 currency 之一: "
                + commodity
                + " / "
                + currency);
      }
      if (owed <= 0L) {
        throw new IllegalArgumentException("UnresolvedDebtCapitalization.owed 必须 > 0: " + owed);
      }
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("UnresolvedDebtCapitalization.reason 不得空白");
      }
    }
  }

  /**
   * ★★ <b>P11.1 / D-023：一条偿还被具名跳过的条目</b>——债务人持有资产但该资产没有稳定价格/比价（或合同计价口径的价目表里
   * 没有对应价格）：按 D-023 的<b>不折算、不静默付 0</b>，合同本金保持不动；此处把剩余本金与原因发出来。
   *
   * <p>★ {@code reason} 的 P11.1 取值：{@code "unpriced-repayment-assets:commodity:<id>,money:<id>…"}
   * （持有但缺价的资产清单；不拿别的价硬折、也不做 FX）。
   */
  public record DebtRepaymentSkip(
      HouseholdId debtor,
      DebtContractId contractId,
      DebtUnit unit,
      long principalOutstanding,
      String reason) {

    public DebtRepaymentSkip {
      if (debtor == null || contractId == null || unit == null) {
        throw new IllegalArgumentException("DebtRepaymentSkip 的 debtor/contractId/unit 不得为 null");
      }
      if (principalOutstanding <= 0L) {
        throw new IllegalArgumentException(
            "DebtRepaymentSkip.principalOutstanding 必须 > 0: " + principalOutstanding);
      }
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("DebtRepaymentSkip.reason 不得空白");
      }
    }
  }

  /**
   * ★★ <b>E5b：清算阶段的瞬态审计条目</b>（只活在当天的 {@link ProductionLedger} 里，不落盘；持久真值仍是 {@code EconomyData} 的
   * assetShares/debtContracts/pledges/classStandings/crisisSignals）。
   *
   * <p>★★ <b>为什么需要它</b>：清算选路里有一批"<b>处置不了</b>"的具名原因（无价格源、无政策、租佃份额、保护量吃满、无市场 acteur
   * …）——它们不改任何状态，如果不在这里留名，读口只能看到"什么都没发生"，分不清"没触发"与"触发了但处置不了"。{@code action} 是稳定的动作词：
   *
   * <ul>
   *   <li>{@code disposed}：真的执行了一条资产转移（{@code sourceAssetShareId} → {@code createdAssetShareId}）；
   *   <li>{@code skipped}：处置被具名跳过（{@code reason} 是唯一可读原因）；
   *   <li>{@code degraded-rule-selection}：政策按 assetKind 退化为 id 最小的一条（仍可能继续处置）；
   *   <li>{@code class-decline} / {@code debt-explosion}：阶层下滑与债务爆炸信号同样落一条审计（信号本体进 {@code
   *       crisisSignals}）；
   *   <li>{@code class-projection-fallback}：5c 发现 {@code ClassStanding.currentPositionId} 投影不回旧
   *       {@code ClassRow.view.stratum}，保留旧 view 并具名报告。
   * </ul>
   *
   * <p>★ <b>窗口/单位</b>：{@code day} = 本次结算日；{@code quantity} 与 AssetShare 同单位；{@code *Milli}
   * 均为"毫值"（粮债口径 = 毫粮）；F 读不到的条目在 evidence 里用 {@code FUnavailable=1} 标注，<b>不填 0</b> 冒充。
   *
   * <p>★ 可选引用一律用 {@link Optional} 表达"这一条没有这个对象"，不用 null 冒充。
   */
  public record LiquidationAudit(
      long day,
      String action,
      Optional<HouseholdId> household,
      Optional<DebtContractId> contractId,
      Optional<PledgeId> pledgeId,
      Optional<AssetShareId> sourceAssetShareId,
      Optional<AssetShareId> createdAssetShareId,
      long quantity,
      long pricePerUnitMilli,
      long debtReductionMilli,
      long debtPrincipalAfter,
      long pledgeQuantityAfter,
      String reason,
      Map<String, Long> evidence) {

    public LiquidationAudit {
      if (day < 0L) {
        throw new IllegalArgumentException("LiquidationAudit.day 不得为负: " + day);
      }
      if (action == null || action.isBlank()) {
        throw new IllegalArgumentException("LiquidationAudit.action 不得空白");
      }
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("LiquidationAudit.reason 不得空白");
      }
      if (quantity < 0L
          || pricePerUnitMilli < 0L
          || debtReductionMilli < 0L
          || debtPrincipalAfter < 0L
          || pledgeQuantityAfter < 0L) {
        throw new IllegalArgumentException(
            "LiquidationAudit 的数量/价格/减额不得为负: "
                + quantity
                + "/"
                + pricePerUnitMilli
                + "/"
                + debtReductionMilli
                + "/"
                + debtPrincipalAfter
                + "/"
                + pledgeQuantityAfter);
      }
      if (household == null
          || contractId == null
          || pledgeId == null
          || sourceAssetShareId == null
          || createdAssetShareId == null) {
        throw new IllegalArgumentException("LiquidationAudit 的可选引用不得为 null（没有给 Optional.empty()）");
      }
      if (evidence == null) {
        throw new IllegalArgumentException("LiquidationAudit.evidence 不得为 null（没有给空表）");
      }
      Map<String, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<String, Long> entry : evidence.entrySet()) {
        if (entry.getKey() == null || entry.getKey().isBlank()) {
          throw new IllegalArgumentException("LiquidationAudit.evidence 的键不得空白");
        }
        if (entry.getValue() == null) {
          throw new IllegalArgumentException(
              "LiquidationAudit.evidence 的值不得为 null（未知用具名键标注）: " + entry.getKey());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      evidence = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
    }
  }

  /**
   * ★★ <b>这一天有没有"产出"</b>（E7/R4 的 fail-closed 判据）：有毛产、或有产出计提。
   *
   * <p>★ 为什么这两样：它们正是<b>离开 {@code ClassRow} 的部分</b> —— 没有产权落账口的入口拿它们<b>无处可放</b>。 ★ 为什么不含 {@link
   * #inputs()}：投入扣在家户账（会话副本）里、记在流水的 {@code consumed} 里，账是完整的。 ★ 为什么不含 {@link #deferredMoney()}：H4
   * 起它<b>恒为空</b>（货币档真的结算了），对判据没有影响。 ★ 为什么不含 {@link
   * #transfers()}：借粮与取材<b>不是产出</b>（它们是既有库存的换手）；而本判据服务的入口（多日静态 {@code settle}）
   * 在全零人口之外<b>根本进不来</b>（第一天之前就抛），那个状态里三者恒空。
   */
  public boolean hasOutput() {
    return !gross.isEmpty() || !outputAccruals.isEmpty();
  }

  /** 逐产业 × 逐商品的毛产（毫单位）。 */
  public long grossOf(IndustryId industry, CommodityId commodity) {
    return gross.getOrDefault(industry, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 逐产业 × 逐商品的生产损耗（毫单位）。 */
  public long lossOf(IndustryId industry, CommodityId commodity) {
    return losses.getOrDefault(industry, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 逐产业 × 逐商品的现扣投入（毫单位）。 */
  public long inputOf(IndustryId industry, CommodityId commodity) {
    return inputs.getOrDefault(industry, Map.of()).getOrDefault(commodity, 0L);
  }

  /** ★★ <b>S3：本日全部具名欠款</b>（由 {@code ruleSettlements} 的 {@code owed>0} 派生；不新增状态组件）。 */
  public List<Arrear> arrears() {
    return arrearsOf(ruleSettlements);
  }

  /** 欠款派生的唯一实现（record 与 Accumulator 共用；见 {@link #arrears()}）。 */
  private static List<Arrear> arrearsOf(List<RuleSettlement> readings) {
    List<Arrear> result = new ArrayList<>();
    for (RuleSettlement reading : readings) {
      if (reading.owed() > 0L) {
        result.add(Arrear.of(reading));
      }
    }
    return List.copyOf(result);
  }

  /** ★ S3：工资欠款（WageArrears）具名读数。 */
  public List<Arrear> wageArrears() {
    return arrearsOfKind(Arrear.Kind.WAGE);
  }

  /** ★ S3：地租欠款（RentArrears）具名读数。 */
  public List<Arrear> rentArrears() {
    return arrearsOfKind(Arrear.Kind.RENT);
  }

  private List<Arrear> arrearsOfKind(Arrear.Kind kind) {
    List<Arrear> result = new ArrayList<>();
    for (Arrear arrear : arrears()) {
      if (arrear.kind() == kind) {
        result.add(arrear);
      }
    }
    return List.copyOf(result);
  }

  /**
   * ★★ <b>一天的可变累加器</b>（包内可见）—— 日结算边跑边记，跑完 {@link #toLedger()} 冻成上面那个 record。
   *
   * <p>★★ <b>它还管一件事：转移凭据的铸造</b>（H2）。{@link #mint} 是<b>全系统唯一分配 {@link TransferId} 的地方</b>： 协调器铸造
   * {@code "tr-<day>-<seq>"}，R2 的分区 worker 铸造 {@code "tr-<day>-p<partition>-<seq>"} （{@code seq} =
   * <b>本铸造器</b>当天第几条，从 1 起）⇒ 同代码态、同分区计划下 1/4/8 线程给出同一批 id。 ★ <b>M7：跨代码态的 {@code seq}
   * 与列表序不可逐条比较</b>（V 阶段按业务键排序后比较集合，见 {@link #mint} 的 javadoc）。 日号由构造器收（调用方知道它推进到了第几天），序号在累加器里自增 ——
   * 两段都只有一处拼写点。
   *
   * <p>★ 形制与 {@code settleOneDay} 里那几张"逐日累加器"同款（{@code consumedGoods} / {@code income}）：<b>可变的那一份
   * 不出包</b>，外部拿到的永远是冻好的值。
   */
  static final class Accumulator implements TransferMint {

    /** 逐笔原始事件日志（trace 分类；并行分区下只保证逐行原子，不保证跨分区顺序）。 */
    private static final Logger RAW = EconomyLog.trace();

    private final long day;
    private final Map<IndustryId, Map<CommodityId, Long>> gross = new LinkedHashMap<>();
    private final Map<IndustryId, Map<CommodityId, Long>> losses = new LinkedHashMap<>();
    private final Map<IndustryId, Map<CommodityId, Long>> inputs = new LinkedHashMap<>();
    private final List<ActorEntry> outputAccruals = new ArrayList<>();
    private final List<Transfer> transfers = new ArrayList<>();
    private final List<RuleSettlement> ruleSettlements = new ArrayList<>();
    private final List<CompensationRule> deferredMoney = new ArrayList<>();
    private final List<DebtCapitalization> debtCapitalizations = new ArrayList<>();
    private final List<UnresolvedDebtCapitalization> unresolvedDebtCapitalizations =
        new ArrayList<>();
    private final List<DebtRepaymentSkip> debtRepaymentSkips = new ArrayList<>();
    private final List<LiquidationAudit> liquidationAudits = new ArrayList<>();

    /** ★ M2.3/M2.4：当天区域市场的只读报告（瞬态；不进 {@link ProductionLedger}，由 stepper 交给 L3 读数）。 */
    private MarketReport marketReport;

    /** 当天已铸的转移条数（{@link TransferId} 的 {@code seq} 段）。 */
    private long transferSequence;

    /**
     * ★★ <b>分区序号</b>（R2）：{@code < 0} = 协调器累加器（id 形如 {@code tr-<day>-<seq>}）； {@code ≥ 0} = worker
     * 分区累加器（id 形如 {@code tr-<day>-p<partition>-<seq>}）。
     *
     * <p>★ 为什么必须分段：并行 worker 各自从 1 起算序号，若仍拼 {@code tr-<day>-<seq>}，两个分区会铸出<b>同一条 id</b>。
     * 带上固定分区号后，{@code (partition, seq)} 在当天唯一；分区号来自与提交序同一处 {@code
     * AccountPartitionKey.partitionIndexOf}，1/4/8 线程（结构分区数固定）给出同一批 id。
     */
    private final int partitionIndex;

    /**
     * @param day 这一天是第几个世界日（进 {@link Transfer#day()} 与 id 的第二段）
     */
    Accumulator(long day) {
      this(day, -1);
    }

    /** ★ R2：worker 的分区累加器（id 带 {@code p<partitionIndex>} 段；见 {@link #partitionIndex}）。 */
    Accumulator(long day, int partitionIndex) {
      this.day = day;
      this.partitionIndex = partitionIndex;
    }

    void addGross(IndustryId industry, CommodityId commodity, long amount) {
      addQuantities(gross, industry, commodity, amount);
    }

    void addLoss(IndustryId industry, CommodityId commodity, long amount) {
      addQuantities(losses, industry, commodity, amount);
    }

    void addInput(IndustryId industry, CommodityId commodity, long amount) {
      addQuantities(inputs, industry, commodity, amount);
    }

    /** 追加一条产出计提（{@code delta} 为 0 的条目在此挡掉：0 不是一条发生额）。 */
    void addOutputAccrual(ActorEntry accrual) {
      if (accrual.delta() == 0L) {
        return;
      }
      outputAccruals.add(accrual);
    }

    /**
     * ★★ <b>铸一条转移并记进当天的账</b>（唯一分配点；见类注的 id 兼容边界）。
     *
     * <p>★★ <b>M7：id 的兼容边界（V 阶段的比较口径）</b>：
     *
     * <pre>
     * 协调器铸造：id = "tr-<day>-<seq>"
     * 分区 worker：id = "tr-<day>-p<partitionIndex>-<seq>"（R2 起；分区号来自固定 STRUCTURAL_PARTITIONS）
     * </pre>
     *
     * <p>★ {@code seq} 是"<b>本铸造器</b>当天第几条"：同代码态、同分区计划下 1/4/8 线程给出同一批 id； 但<b>跨代码态</b>（旧串行 {@code
     * tr-<day>-<seq>}、R2 后的 {@code tr-<day>-p…}）的 id 与列表序<b>不可逐条比较</b> —— 分区顺序改变了 {@code seq} 的分配。⇒
     * V 阶段对 {@code transfers}/{@code outputAccruals}/{@code ruleSettlements}
     * 的跨代码态/跨线程数比较<b>一律"按业务键排序后比较集合"</b>（业务键 = day + from + to + location + reason + goods/money 内容
     * + 产出归属等），不得按 id 或列表下标逐条比，也不得为了旧 id 兼容破坏 1/4/8 的确定性。
     *
     * <p>★ {@code settles} 恒空（清偿属 H5）；★ <b>H4 起货币腿真的有钱</b>（货币工资/地租走只带货币腿的转移、 市场成交走一对转移 —— 见 {@code
     * Transfer} 的货币腿口径）。
     */
    @Override
    public Transfer mint(
        ActorRef from,
        ActorRef to,
        HexCoord location,
        Map<CommodityId, Long> goods,
        Map<CurrencyId, Long> money,
        TransferReason reason) {
      long sequence = ++transferSequence;
      String id =
          partitionIndex < 0
              ? "tr-" + day + "-" + sequence
              : "tr-" + day + "-p" + partitionIndex + "-" + sequence;
      Transfer transfer =
          new Transfer(
              new TransferId(id), day, from, to, location, goods, money, reason, Optional.empty());
      transfers.add(transfer);
      if (RAW.isTraceEnabled()) {
        RAW.trace(
            "event=TRANSFER id={} day={} from={} to={} hex={},{} reason={} goods={} money={}",
            id,
            day,
            from.id(),
            to.id(),
            location.q(),
            location.r(),
            reason.value(),
            goods,
            money);
      }
      return transfer;
    }

    /**
     * ★★ <b>把一个分区 worker 的账本吸收进协调器账本</b>（R2）：调用方按 {@link PartitionPlan} 的分区序遍历结果逐个吸收 ⇒
     * 列表序与读数序是内容的纯函数。
     *
     * <p>★ 逐商品表按量合并（整数加法可交换）；四个列表<b>追加</b>，保持"先阶段、再分区、再分区内生成序"的稳定序。 分区号不参与拼接判定（同一天的不同阶段都会有分区
     * 0..N），序由调用方的遍历序承担。
     */
    void absorb(Accumulator partitionLedger) {
      Objects.requireNonNull(partitionLedger, "partitionLedger");
      mergeQuantities(gross, partitionLedger.gross);
      mergeQuantities(losses, partitionLedger.losses);
      mergeQuantities(inputs, partitionLedger.inputs);
      outputAccruals.addAll(partitionLedger.outputAccruals);
      transfers.addAll(partitionLedger.transfers);
      ruleSettlements.addAll(partitionLedger.ruleSettlements);
      deferredMoney.addAll(partitionLedger.deferredMoney);
      debtCapitalizations.addAll(partitionLedger.debtCapitalizations);
      unresolvedDebtCapitalizations.addAll(partitionLedger.unresolvedDebtCapitalizations);
      debtRepaymentSkips.addAll(partitionLedger.debtRepaymentSkips);
      liquidationAudits.addAll(partitionLedger.liquidationAudits);
      if (marketReport == null && partitionLedger.marketReport != null) {
        marketReport = partitionLedger.marketReport;
      }
    }

    /** 逐产业 × 逐商品的两层表按量合并（键序 = 首次出现序；不引入第二份累加器）。 */
    private static void mergeQuantities(
        Map<IndustryId, Map<CommodityId, Long>> target,
        Map<IndustryId, Map<CommodityId, Long>> source) {
      for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : source.entrySet()) {
        Map<CommodityId, Long> inner =
            target.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>());
        for (Map.Entry<CommodityId, Long> quantity : entry.getValue().entrySet()) {
          inner.merge(quantity.getKey(), quantity.getValue(), Long::sum);
        }
      }
    }

    /** 一条逐规则的实得读数（应付 / 实付 / 欠；★ 只读）。 */
    void addRuleSettlement(RuleSettlement reading) {
      ruleSettlements.add(reading);
    }

    /**
     * 收下一条**被推迟的货币规则**（★ H4 起<b>没有生产调用方</b>：货币档真的结算了 ⇒ 这张表恒空；它只服务旧口径的留痕， 见 {@code
     * Outcome.deferredMoney}）。
     */
    void addDeferred(CompensationRule rule) {
      deferredMoney.add(rule);
    }

    /** ★★ E4c：记一条"欠租/欠薪 → 债权"的资本化审计（逐事件合并后的一次合同写）。 */
    void addDebtCapitalization(DebtCapitalization capitalization) {
      debtCapitalizations.add(Objects.requireNonNull(capitalization, "capitalization"));
    }

    /** ★★ E4c：记一条资本化跳过的具名原因（端点解析不到；不伪造）。 */
    void addUnresolvedDebtCapitalization(UnresolvedDebtCapitalization unresolved) {
      unresolvedDebtCapitalizations.add(Objects.requireNonNull(unresolved, "unresolved"));
    }

    /** ★★ E4c：记一条偿还跳过的具名原因（目前唯一来源 = 无价格源的货币折偿）。 */
    void addDebtRepaymentSkip(DebtRepaymentSkip skip) {
      debtRepaymentSkips.add(Objects.requireNonNull(skip, "skip"));
    }

    /** ★★ E5b：记一条清算阶段的瞬态审计（处置/跳过/退化选择/阶层下滑/债务爆炸/投影回退）。 */
    void addLiquidationAudit(LiquidationAudit audit) {
      liquidationAudits.add(Objects.requireNonNull(audit, "audit"));
    }

    /** ★★ E4c：本日全部具名欠款（与 {@link ProductionLedger#arrears()} 同一派生实现）。 */
    List<Arrear> arrears() {
      return arrearsOf(ruleSettlements);
    }

    /**
     * ★ 记下当天的区域市场报告（M2.3/M2.4；瞬态，不进落盘的 {@link ProductionLedger}）—— {@code 旧日推进器（R3a
     * 已删除）.lastMarketReport()} 读它。
     */
    void recordMarketReport(MarketReport report) {
      this.marketReport = report;
    }

    /** 当天的市场报告（没开市 ⇒ {@code null}）。 */
    MarketReport marketReport() {
      return marketReport;
    }

    ProductionLedger toLedger() {
      return new ProductionLedger(
          gross,
          losses,
          inputs,
          outputAccruals,
          transfers,
          ruleSettlements,
          deferredMoney,
          debtCapitalizations,
          unresolvedDebtCapitalizations,
          debtRepaymentSkips,
          liquidationAudits);
    }

    private static void addQuantities(
        Map<IndustryId, Map<CommodityId, Long>> acc,
        IndustryId industry,
        CommodityId commodity,
        long amount) {
      if (amount == 0L) {
        return;
      }
      acc.computeIfAbsent(industry, key -> new LinkedHashMap<>())
          .merge(commodity, amount, Long::sum);
    }
  }

  // ── 冻结 ───────────────────────────────────────────────────────────────────────────

  /** 逐产业 → 逐商品的表：**两层都保序不可变**（★ 内层也要冻：它会经 {@code grossOf} / {@code gross().get(k)} 逸出）。 */
  private static Map<IndustryId, Map<CommodityId, Long>> freezeQuantities(
      Map<IndustryId, Map<CommodityId, Long>> quantities, String field) {
    if (quantities == null) {
      return Map.of();
    }
    Map<IndustryId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : quantities.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(field + " 的键与值都不得为 null: " + entry.getKey());
      }
      Map<CommodityId, Long> inner = new LinkedHashMap<>(entry.getValue());
      for (Map.Entry<CommodityId, Long> row : inner.entrySet()) {
        if (row.getKey() == null || row.getValue() == null) {
          throw new IllegalArgumentException(field + " 的键与值都不得为 null: " + row.getKey());
        }
      }
      copy.put(entry.getKey(), Collections.unmodifiableMap(inner));
    }
    return copy; // 外层的不可变由**赋值处**加（见紧凑构造器）
  }
}
