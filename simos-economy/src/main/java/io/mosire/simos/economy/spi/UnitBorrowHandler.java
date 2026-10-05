package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.debt.DefaultRemedy;
import io.mosire.simos.economy.api.debt.InterestTiming;
import io.mosire.simos.economy.api.debt.MonetaryConversion;
import io.mosire.simos.economy.api.debt.RepaymentRule;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.time.DebtContractBook;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code economy.UnitBorrow}（P2-D）：单位向放贷方借入的债务腿</b>。载荷：
 *
 * <pre>{@code
 * {"unitId":"u-prov",
 *  "borrowerHousehold":"hh-gov-u-prov",   // 借款单位的国库家户（= 债务人；资金入账方）
 *  "lenderHousehold":"hh-0_0-urban-landlord",
 *  "unit":"money"|"grain",
 *  "principal":5000,
 *  "interestRatePerMille":20,
 *  "nextDueTick":400,
 *  "terms":"unit-debt",                    // 可选审计标签（缺省 unit-debt；不进合同身份）
 *  "reason":"…"}                           // 可选审计文本
 * }</pre>
 *
 * <p>★★ <b>放贷方 = 现存有账户主体（家户 / 政府家户）</b>：{@code lenderHousehold} 必须已在 {@code economy.classes} 里（=
 * 有账户的经济主体）。不再有已退役的 class-first {@code ExternalLender}； 非家户主体不得持账（P2-A §13.3），所以本命令只认 {@link
 * HouseholdId}。
 *
 * <p>★★ <b>债务仍走唯一写口</b>：{@link DebtContractBook#upsert} 负责建条/累加本金、身份（ {@code (debtor, creditor,
 * unit, terms)} 的确定性 id）与到期周期；{@code HouseholdEconomy.debts} 引用由 {@code EconomyData} 构造期的 {@code
 * DebtReferenceReconciler} 从合同表重建，本命令不手写第二份引用。
 *
 * <p>★★ <b>资金走家户账户，但不在本命令里</b>（架构硬约束，具名）：一条 {@code CommandHandler} 只能产出一个命名空间的 变更集，而"钱从放贷方家户 →
 * 借款单位国库家户"在 {@code actor} 切片。完整调用必须是<b>同一批</b> （{@code CoreSimos.submitBatch}，一批 = 一条
 * revision，原子）：
 *
 * <pre>
 * ① actor.TransferAccounts  {from:{household:lenderHousehold}, to:{household:borrowerHousehold},
 *                             goods/money: 本金}     // 钱/粮真的换手
 * ② economy.UnitBorrow      {…本载荷…}                 // 债务合同建立
 * </pre>
 *
 * <p>★ <b>先①后②</b>：{@code UnitBorrow} 不做余额校验（economy 看不见 actor 账），资金不足以 {@code
 * actor.TransferAccounts} 的具名拒为准，整批失败 ⇒ 零 revision（不会出现"有债无钱"的悬空）。 ★ <b>只提本命令</b>会造出"有债无钱"的半截状态——与旧
 * {@code economy.UnitBorrow} + {@code actor.AdjustAccounts} 的合约同款警告。<b>组合工具（{@code
 * simos.unit.issueDebt}）本批不重建</b>（MCP 工具后置，见 P2 范围）。
 *
 * <p>★★ <b>借款人解析（谁的钱）</b>：{@code unitId} 是语义/审计主体；资金与债务主体是 {@code borrowerHousehold}。GOV 单位应传
 * {@code hh-gov-<unitId>}（{@code GovernmentHouseholdResolver} 是组合根里的唯一解析落点）；非 GOV 单位必须显式点名一个自有家户。★
 * 本命令<b>不</b>按 {@code unit.households()} 的列表顺序猜"第一个家户"，也不允许省略 {@code borrowerHousehold}。
 *
 * <p>★★ <b>{@code nextDueTick} 的落点</b>：它写进 {@link DebtTerms#dueDay()}（合同身份维的一部分），
 * 记录"约定在哪个世界日到期"；本批的日结算<b>尚未执行到期催收</b>（具名缺口：due 只作身份/审计，见报告）。 {@code dueCycle} 滚动字段留空（本命令不用产业周期口径）。
 *
 * <p>★★ <b>GM-only</b>：实现 {@link GmOnlyCommand}（GM 的 {@code simos.command.submit} 可调；不进决策令白名单）。
 * {@code targetPaths} 返回空表（家户账户无格路径可声明；GM-only 命令不进资源围栏）。
 */
public final class UnitBorrowHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.UnitBorrow";

  /** {@code terms} 缺省审计标签（唯一拼写点；不进合同身份）。 */
  public static final String TERMS_UNIT_DEBT = "unit-debt";

  private static final Logger LOG = EconomyLog.debt();

  /** 粮的商品 id（{@link EconomyVocabulary} 的唯一拼写点）。 */
  private static final CommodityId GRAIN = CommodityId.parse(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /** 银的货币 id（{@link MoneyVocabulary} 的唯一拼写点）。 */
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(EconomyCommandPayloads.parseObject(TYPE, payloadJson)); // 坏载荷仍抛具名；合法载荷没有可声明的格目标。
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      long tick = state.meta().timestamp().tick();
      return new HandlerOutcome.Applied(project(base, payload, tick));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 纯函数项目：解析 → 校验 → 债务表 upsert → 变更集（只换 debtContracts；classes 的引用由构造期对账重建）。 */
  private static EconomyChangeSet project(EconomyData base, JsonNode payload, long tick) {
    Request request = parse(payload);
    if (request.principal() < 1L) {
      throw new IllegalArgumentException(TYPE + " 的 principal 必须 ≥ 1: " + request.principal());
    }
    if (request.interestRatePerMille() < 0) {
      throw new IllegalArgumentException(
          TYPE + " 的 interestRatePerMille 不得为负: " + request.interestRatePerMille());
    }
    if (request.nextDueTick() <= tick) {
      throw new IllegalArgumentException(
          TYPE
              + " 的 nextDueTick 必须大于当前 tick: nextDueTick="
              + request.nextDueTick()
              + "，当前 tick="
              + tick);
    }
    requireHousehold(base, request.borrowerHousehold(), "borrowerHousehold");
    requireHousehold(base, request.lenderHousehold(), "lenderHousehold");
    if (request.borrowerHousehold().equals(request.lenderHousehold())) {
      throw new IllegalArgumentException(
          TYPE
              + " 的 borrowerHousehold 与 lenderHousehold 不得相同: "
              + request.borrowerHousehold().value());
    }
    DebtUnit debtUnit = debtUnit(request.unit());
    DebtTerms terms =
        new DebtTerms(
            request.interestRatePerMille(),
            InterestTiming.AFTER_REPAYMENT_ON_CLOSE,
            RepaymentRule.AVAILABLE_SURPLUS_SHARE,
            MonetaryConversion.NOT_ALLOWED,
            DefaultRemedy.MARK_DEFAULTED,
            OptionalLong.empty(),
            OptionalLong.of(request.nextDueTick()));

    LinkedHashMap<DebtContractId, DebtContract> debts = new LinkedHashMap<>(base.debtContracts());
    DebtContract contract =
        DebtContractBook.upsert(
            debts,
            request.borrowerHousehold(),
            request.lenderHousehold(),
            debtUnit,
            terms,
            request.principal(),
            tick,
            OptionalLong.empty());
    EconomyData projected = base.withDebtContracts(debts);
    LOG.info(
        "event=UNIT_BORROW unit={} borrowerHousehold={} lenderHousehold={} unitKey={} principal={}"
            + " interestRatePerMille={} nextDueTick={} contract={} termsLabel={} reason={}",
        request.unitId(),
        request.borrowerHousehold().value(),
        request.lenderHousehold().value(),
        debtUnit.key(),
        request.principal(),
        request.interestRatePerMille(),
        request.nextDueTick(),
        contract.id().value(),
        request.terms(),
        request.reason());
    return EconomyChangeSet.between(base, projected);
  }

  /** 家户行必须存在（= 有账户的经济主体；引用完整性由命令边界给可读拒绝）。 */
  private static void requireHousehold(EconomyData base, HouseholdId household, String field) {
    if (!base.classes().containsKey(household)) {
      throw new IllegalArgumentException(
          TYPE
              + " 的 "
              + field
              + " 不是已知家户（先 social.CreateHousehold / economy.RegisterGovernment）: "
              + household.value());
    }
  }

  /** 计量标的词表：只认 {@code money}/{@code grain} 两个字面量（不做别名/大小写归一）。 */
  private static DebtUnit debtUnit(String unit) {
    return switch (unit) {
      case "money" -> new DebtUnit.Money(SILVER);
      case "grain" -> new DebtUnit.Commodity(GRAIN);
      default ->
          throw new IllegalArgumentException(
              TYPE + " 的 unit 只认 \"money\"/\"grain\"（不做别名/大小写归一）: " + unit);
    };
  }

  /** 载荷形状/范围校验（handler 与 targetPaths 共用；引用存在性在 {@link #project} 里判）。 */
  private static Request parse(JsonNode payload) {
    String unitId = EconomyCommandPayloads.requireText(TYPE, payload, "unitId");
    HouseholdId borrower =
        HouseholdId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "borrowerHousehold"));
    HouseholdId lender =
        HouseholdId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "lenderHousehold"));
    String unit = EconomyCommandPayloads.requireText(TYPE, payload, "unit");
    debtUnit(unit); // 形状期就拒坏词表，不让它活到状态边界。
    long principal = EconomyCommandPayloads.requireLong(TYPE, payload, "principal");
    int interestRatePerMille =
        EconomyCommandPayloads.requireInt(TYPE, payload, "interestRatePerMille");
    long nextDueTick = EconomyCommandPayloads.requireLong(TYPE, payload, "nextDueTick");
    String terms = EconomyCommandPayloads.optionalText(TYPE, payload, "terms", TERMS_UNIT_DEBT);
    String reason = EconomyCommandPayloads.optionalText(TYPE, payload, "reason", "");
    return new Request(
        unitId,
        borrower,
        lender,
        unit,
        principal,
        interestRatePerMille,
        nextDueTick,
        terms,
        reason);
  }

  /** 已校验的请求形状。 */
  private record Request(
      String unitId,
      HouseholdId borrowerHousehold,
      HouseholdId lenderHousehold,
      String unit,
      long principal,
      int interestRatePerMille,
      long nextDueTick,
      String terms,
      String reason) {}
}
