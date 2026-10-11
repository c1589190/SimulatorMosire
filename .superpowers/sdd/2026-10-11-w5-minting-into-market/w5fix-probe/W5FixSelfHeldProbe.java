package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.MoneyOutput;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.testing.TestLogCapture;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.event.Level;

/**
 * W5-fix 运行时探针（**只读**，落 /tmp，不进 src、不进 Maven 门禁）：BLOCKED-2 的"真世界形状"复现/验收。
 *
 * <p>夹具与 W5b 的 {@code MintProductionSettlementTest} 逐值同形（一格 / 一铸币产业 / 一 unit / 一户 = 政府家户
 * = 国库 = 铸币组织者），**去掉它唯一的一处装置**：国库货币不再冻结 ⇒ 还债轮看到"有库存"（W2 v3.6 口径：有库存就还）
 * —— 这正是播种世界的形状。
 *
 * <p>修复前：还债轮为 `debtor == creditor` 的铸币债铸自转移 ⇒ `Transfer` 抛（BLOCKED-2）。
 * 修复后：还债轮排除自持合同（具名 DEBUG）⇒ 这一天跑得完、`finish()` 也过，且**合同与国库余额逐值不变**。
 */
public final class W5FixSelfHeldProbe {

  private static final HexCoord HEX = new HexCoord(0, 0);

  private static final IndustryId MINT = IndustryHexKeys.id("mint", 0, 0);

  private static final GovernmentId GOV = new GovernmentId("gov-mint");

  private static final HouseholdId GOV_HOUSEHOLD = HouseholdId.parse("hh-gov-mint");

  private static final ActorRef TREASURY = HouseholdActors.of(GOV_HOUSEHOLD);

  private static final ProductionUnitId UNIT = ProductionUnitId.idOf(MINT, TREASURY);

  private static final LaborAllocationId ALLOCATION = new LaborAllocationId("alloc-mint-probe");

  private static final PeopleLotId LOT = new PeopleLotId("urban:0_0:MALE:1");

  private static final CohortKey VIEW =
      new CohortKey(HEX, ResidenceKind.URBAN, SocialClassId.OFFICIAL);

  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private static final CommodityId TOOL = new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);

  private static final long MINT_PER_UNIT = 500L;

  private static final long TOOL_PER_UNIT = 2_000L;

  private static final long LABOR_PER_UNIT = 8_000L;

  private static final long SEED_TOOL_MILLI = 8_000L;

  private static final long SEED_MONEY_MILLI = 100L;

  public static void main(String[] args) {
    System.out.println("=== BLOCKED-2 运行时探针：播种形状（国库钱是活的 = 不冻结）+ 铸币债自持");
    EconomyData world = mintWorld(1L, 2_000L, OptionalLong.empty(), Map.of(), SILVER);
    AccountSession accounts = accountsFor();

    EconomyDayStepper stepper = new EconomyDayStepper(world, accounts);
    ProductionLedger ledger = null;
    EconomyData data = null;
    String failure = null;
    try (TestLogCapture log = TestLogCapture.attach(EconomyLog.SETTLEMENT_LOGGER_NAME, Level.DEBUG)) {
      try {
        ledger = stepper.step(1L);
      } catch (Throwable t) {
        failure = t.getClass().getName() + ": " + t.getMessage();
      }
      if (failure == null) {
        try {
          data = stepper.finish();
        } catch (Throwable t) {
          failure = "finish(): " + t.getClass().getName() + ": " + t.getMessage();
        }
      }
      System.out.println("  step+finish 结果 = " + (failure == null ? "跑完一整天（无异常）" : "抛: " + failure));
      System.out.println("  --- 自持排除的具名日志行（settlement logger, DEBUG 及以上）：");
      boolean sawSkip = false;
      for (String line : log.lines()) {
        if (line.contains("DEBT_REPAY_SELF_HELD_SKIPPED")) {
          sawSkip = true;
          System.out.println("    " + line);
        }
      }
      if (!sawSkip) {
        System.out.println("    （没有 DEBT_REPAY_SELF_HELD_SKIPPED 行）");
      }
      for (String line : log.lines()) {
        if (line.contains("event=DEBT_REPAY_ROUND ")) {
          System.out.println("  --- 还债轮汇总行：");
          System.out.println("    " + line);
        }
      }
      System.out.println("  见到 DEBT_REPAY_SELF_HELD_SKIPPED = " + sawSkip);
    } finally {
      stepper.close();
    }

    if (ledger != null) {
      System.out.println("  铸币投入（工具，毫） = " + ledger.inputOf(MINT, TOOL));
      Map<HouseholdId, Map<CommodityId, Long>> goods = new LinkedHashMap<>();
      Map<HouseholdId, Map<CurrencyId, Long>> money = new LinkedHashMap<>();
      long total = 0L;
      for (AccountSession.ActorAccount account : accounts.accounts().values()) {
        goods.put(account.household(), Map.copyOf(account.goods()));
        money.put(account.household(), Map.copyOf(account.money()));
        for (Map.Entry<CurrencyId, Long> entry : account.money().entrySet()) {
          total += entry.getValue();
        }
      }
      System.out.println("  国库工具 = " + goods.get(GOV_HOUSEHOLD));
      System.out.println("  国库货币 = " + money.get(GOV_HOUSEHOLD) + "（期望 {silver=600}：100 初始 + 500 铸出，未被自持还债动过）");
      System.out.println("  货币总量增量 = " + (total - SEED_MONEY_MILLI) + "（期望 500 = 铸币发行之和）");
      if (data != null) {
        System.out.println("  发行记录条数 = " + data.moneyIssuances().size());
        System.out.println("  债务合同条数 = " + data.debtContracts().size());
        for (DebtContract debt : data.debtContracts().values()) {
          System.out.println(
              "    合同 "
                  + debt.id().value()
                  + " debtor="
                  + debt.debtor().value()
                  + " creditor="
                  + debt.creditor().value()
                  + " principal="
                  + debt.principal()
                  + " status="
                  + debt.status()
                  + "（期望：自持、principal 仍 500、NORMAL）");
          System.out.println("    自持（debtor==creditor） = " + debt.debtor().equals(debt.creditor()));
          System.out.println("    未被还债动过（principal==500 且 NORMAL） = " + (debt.principal() == 500L && debt.status() == DebtStatus.NORMAL));
        }
        FlowRow flow = data.flows().get(GOV_HOUSEHOLD);
        System.out.println("  国库户当日流水行 = " + (flow == null ? "null" : "repaidMoney=" + flow.repaidMoney() + "（期望空：自持债不被清偿）"));
      }
    }
    System.out.println("=== 结论：" + (failure == null ? "自持债在同一日的还债轮被排除 ⇒ 播种形状能跑完一天" : "★ 仍失败：" + failure));
  }

  // ── 夹具（与 MintProductionSettlementTest.mintWorld 逐值同形；唯一差别 = accountsFor 不冻结货币） ──

  private static EconomyData mintWorld(
      long furnaces,
      long mintScalePerCycle,
      OptionalLong lastClosedCycle,
      Map<MoneyIssuanceId, MoneyIssuanceRecord> preIssuances,
      CurrencyId currency) {
    Government government =
        new Government(GOV, "world", TREASURY, Set.of(SILVER), mintScalePerCycle);
    Industry mint =
        new Industry(
            MINT,
            "铸币",
            new RegimeId("government_mint"),
            1L,
            Map.of(AssetKind.WORKSHOP, 1L),
            Map.of(),
            0L,
            LABOR_PER_UNIT,
            Map.of(),
            Map.of(AssetKind.WORKSHOP, Map.of(TOOL, TOOL_PER_UNIT)),
            List.of(new ClassSlot(SocialClassId.OFFICIAL, "官府", 1000)),
            new AllocationRule.Split(1000, 0),
            null,
            0L,
            Map.of(),
            0L,
            Map.of(),
            new MoneyOutput(Map.of(currency, MINT_PER_UNIT)));
    ProductionProcess unit =
        new ProductionProcess(UNIT, MINT, TREASURY, MINT.value(), 0L, 0L, Map.of());
    HouseholdEconomy row =
        new HouseholdEconomy(GOV_HOUSEHOLD, VIEW, 0L, 100_000L, 1000, 0L, Map.of(), Map.of(), 0L);
    OwnershipStake workshops =
        new OwnershipStake(
            OwnershipStake.idOf(
                MINT, AssetKind.WORKSHOP, TREASURY, TREASURY, OwnershipStake.RightKind.OWNED, 0L),
            MINT,
            AssetKind.WORKSHOP,
            TREASURY,
            TREASURY,
            furnaces,
            OwnershipStake.RightKind.OWNED);
    HouseholdLaborCommitment commitment =
        new HouseholdLaborCommitment(
            ALLOCATION, LOT, GOV_HOUSEHOLD, TREASURY, UNIT.value(), LABOR_PER_UNIT * furnaces, 1L);
    return EconomyData.empty()
        .withMeta(Optional.of(meta(lastClosedCycle)))
        .withGovernments(Map.of(government.id(), government))
        .withIndustries(Map.of(MINT, mint))
        .withProcesses(Map.of(UNIT, unit))
        .withHouseholdEconomies(Map.of(GOV_HOUSEHOLD, row))
        .withOwnershipStakes(Map.of(workshops.id(), workshops))
        .withLaborCommitments(Map.of(ALLOCATION, commitment))
        .withMoneyIssuances(preIssuances);
  }

  /** ★ 与 W5b 夹具的唯一差别：货币**不冻结**（还债轮看得到"有库存"⇒ 真世界形状）。 */
  private static AccountSession accountsFor() {
    AccountSession accounts = AccountSession.empty();
    accounts.registerHousehold(
        GOV_HOUSEHOLD,
        HEX,
        Map.of(TOOL, SEED_TOOL_MILLI),
        Map.of(SILVER, SEED_MONEY_MILLI),
        Map.of(),
        Map.of());
    return accounts;
  }

  private static EconomyMeta meta(OptionalLong lastClosedCycle) {
    return new EconomyMeta(
        "mint-self-held-probe",
        0L,
        lastClosedCycle,
        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
        Optional.empty());
  }
}
