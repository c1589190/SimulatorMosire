package io.mosire.simos.app.time;

import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.api.stock.HouseholdStockDeduction;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★ <b>辖区日税的纯推导 + 账户落账（P2-D；阶段 6.3 契约在新家户/政府家户路径上的重建）</b>。
 *
 * <p>它被 {@link PopulationEconomyTimeParticipant} 的日循环调用——不另起 participant，理由是既有架构硬约束： {@code
 * TimeProposalResolver} 对两个参与者的<b>同名模块变更 = module clash ⇒ 拒整次推进</b>，而本结算写 actor 账户，population 参与者已是
 * actor 写面的唯一参与者。
 *
 * <p>★★ <b>征收口径（沿用 2026-09-30 阶段 6.3 / 阶段 11b 的合约，只换账户主体）</b>：
 *
 * <ol>
 *   <li><b>逐单位</b>：{@code units.units()} 按 {@link UnitId#value()} 升序；只征 {@code
 *       efficiencyPerMilleByUnit} 里<b>查得到</b>的单位；
 *   <li><b>无 GOV ⇒ 不征</b>：查不到效率（没有 {@code GovernmentFormation}/没有 GOV 读数）⇒ <b>整单位跳过、不征</b>； 不读已退役的 {@code
 *       Jurisdiction.administrationPerMille}、不补 0；
 *   <li><b>逐区域</b>：{@code Jurisdiction.taxRatePerMilleByRegion} 的 key 按 {@link RegionId#value()}
 *       升序， rate = 0 跳过；区域不在 {@code map.regions()} ⇒ 具名 {@link GapKind#REGION_MISSING} 并跳过；
 *   <li><b>税基</b>：区域各 hex 上的家户行（{@code rows} 的 location = {@link HouseholdEconomy#view()}.hex()）， 按 {@link
 *       HouseholdId#value()} 升序；余额 ≤ 0 跳过；<b>征着自己的国库家户排除</b> （自征自返只是账面噪声）；
 *   <li><b>国库落点</b>：GOV 单位 → {@link GovernmentHouseholdResolver} 解析出的政府家户（{@code
 *       hh-gov-&lt;unitId&gt;}， 资金先入它的账户，俸禄再从同一账户支出）；无有效位置 ⇒ 具名 {@link GapKind#NO_POSITION} 并跳过该单位
 *       （沿用旧合约：无座位 = 无行政，不征）；
 *   <li><b>算式</b>：{@code assessed = floor(balance × rate‰)}； {@code attainable = floor(assessed ×
 *       efficiency‰)}（效率 ∈ [0,1100]，&gt;1000 = 超编加成带来的超收能力）； {@code collected = min(attainable,
 *       available)}，{@code available} 走 {@link AvailableStock}（余额 − 冻结，
 *       仓储唯一算法）；粮、银两个维度<b>各按同一本税前账算足</b>；
 *   <li><b>落账</b>：{@code collected > 0} 才构造一条 {@link HouseholdStockDeduction#transfer 原子转移扣除}
 *       （reason = {@link DeductionReason#JURISDICTION_TAX}，收款方 = 政府家户）并调 {@link
 *       StockDeductionService} —— 税侧<b>不再</b>自己拼负增量、不再直接碰 {@code AccountSession.commit}（2026-10-09
 *       用户裁定：通用扣除接口）；
 *   <li><b>缺口累计（有符号）</b>：{@code adminShortfall += assessed − attainable}、 {@code stockShortfall +=
 *       attainable − collected}，恒等式 {@code collected + adminShortfall + stockShortfall == assessed}
 *       逐维成立；
 *   <li><b>重叠管辖</b>：同一天多单位命中同一区域/同一本账 ⇒ 按单位 id 升序依次征，后者见前者税后余额 （活会话顺序演进，确定性）。
 * </ol>
 *
 * <p>★★ <b>粮 / 银两个维度；不收布、不收人力</b>（具名口径）：阶段 6.3 的日税合约就是 grain + money 两维；布只在 {@code GovDaily}
 * 的俸禄物资格里出现，一次性抽取工具另有 cloth 一路。**人力不重现**——{@code Unit.manpower} 已退役， 家户劳动是按 tick
 * 的有限时间预算，不是可抽取的存量；抽人要走社会批次路径（另一个工具的裁定范围）。
 *
 * <p>★★ <b>纯推导 + 唯一账户写口的混合体</b>：它不改 {@code EconomyData}/unit/gov 状态，只把账户变动交给 {@link
 * StockDeductionService}（它内部才调 {@link AccountSession#commit}，会话的唯一落账口）；返回的 {@link Report}
 * 是不可变读数（含逐户粮税明细，供调用方 写回 {@code FlowRow.taxPaid} 与写日志）。同一输入 + 同一会话状态 ⇒ 逐字段相同的结果。
 *
 * <p>★ <b>日志</b>（AGENTS §一.9）：INFO = 每日一行"发生了什么 + 具名计数"；DEBUG = 每条跳过理由；TRACE = 逐笔征收。
 */
final class JurisdictionDailyTax {

  /** 日结算日志（settlement 分类）。 */
  private static final Logger LOG = EconomyLog.settlement();

  /** 逐笔日志（trace 分类；默认关闭）。 */
  private static final Logger TRACE = EconomyLog.trace();

  /** 粮的商品 id（{@link EconomyVocabulary} 的唯一拼写点）。 */
  private static final CommodityId GRAIN = CommodityId.parse(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /** 货币 id（{@link MoneyVocabulary} 的唯一拼写点）。 */
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private JurisdictionDailyTax() {}

  /**
   * 对 {@code tick} 这一天做一次辖区日税；账户增量直接提交进 {@code accounts}。
   *
   * @param accounts 本次推进的唯一账户会话（协调器线程；本方法用它的活表与唯一提交口）
   * @param rows 家户行（税基定位：{@code household → 当前 view/hex}；活视图，键序不参与判定）
   * @param units 只读 unit 切片（管辖、有效位置）
   * @param map 只读 map 切片（区域的 hex 集）
   * @param tick 世界日（{@code effectivePosition} 的取位时刻；≥ 1）
   * @param efficiencyPerMilleByUnit 单位 id → 当日 GOV 行政效率‰；<b>缺失键 = 没有 GOV 读数 ⇒ 整单位跳过、不征</b>
   * @return 本日 {@link Report}（不可变）
   */
  static Report collect(
      AccountSession accounts,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      UnitState units,
      GameMap map,
      long tick,
      Map<UnitId, Long> efficiencyPerMilleByUnit) {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(householdEconomies, "rows");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(efficiencyPerMilleByUnit, "efficiencyPerMilleByUnit");
    if (tick < 0L) {
      throw new IllegalArgumentException("辖区日税的 tick 不得为负: " + tick);
    }

    List<Unit> orderedUnits = new ArrayList<>(units.units().values());
    orderedUnits.sort(Comparator.comparing(unit -> unit.id().value()));
    List<HouseholdId> orderedHouseholds = new ArrayList<>(householdEconomies.keySet());
    orderedHouseholds.sort(Comparator.comparing(HouseholdId::value));
    // ★ 税基按格索引一次（旧实现逐 region 全表扫描，O(region × households)）：每格的清单保持家户 id 升序，
    //   于是同一份状态每次得到同一个征收序，与 region.hexes() 的迭代序无关。
    Map<HexCoord, List<HouseholdId>> householdsByHex = new LinkedHashMap<>();
    for (HouseholdId household : orderedHouseholds) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      if (householdEconomy == null) {
        continue;
      }
      householdsByHex
          .computeIfAbsent(householdEconomy.view().hex(), ignored -> new ArrayList<>())
          .add(household);
    }

    long grainAssessed = 0L;
    long grainCollected = 0L;
    long grainAdminShortfall = 0L;
    long grainStockShortfall = 0L;
    long moneyAssessed = 0L;
    long moneyCollected = 0L;
    long moneyAdminShortfall = 0L;
    long moneyStockShortfall = 0L;
    long unitsCharged = 0L;
    Set<HouseholdId> chargedHouseholds = new LinkedHashSet<>();
    Map<HouseholdId, Long> grainByHousehold = new LinkedHashMap<>();
    List<Gap> gaps = new ArrayList<>();

    for (Unit unit : orderedUnits) {
      Long efficiencyPerMille = efficiencyPerMilleByUnit.get(unit.id());
      if (efficiencyPerMille == null) {
        continue; // ★ 无 GOV 读数 ⇒ 整单位跳过、不征（不读退役字段、不补 0、不记缺口）。
      }
      long efficiency = efficiencyPerMille;
      if (efficiency < 0L || efficiency > 1100L) {
        throw new IllegalArgumentException(
            "GOV 效率必须 ∈ [0,1100]: unit=" + unit.id().value() + " efficiency‰=" + efficiency);
      }
      Jurisdiction jurisdiction = unit.jurisdiction().orElse(null);
      if (jurisdiction == null) {
        LOG.debug("event=TAX_UNIT_SKIPPED unit={} reason=no-jurisdiction", unit.id().value());
        continue;
      }
      List<Map.Entry<RegionId, Long>> ratedRegions = new ArrayList<>();
      for (Map.Entry<RegionId, Long> entry : jurisdiction.taxRatePerMilleByRegion().entrySet()) {
        if (entry.getValue() > 0L) {
          ratedRegions.add(entry); // rate = 0 整段跳过（不扫描、不记缺口）。
        }
      }
      if (ratedRegions.isEmpty()) {
        LOG.debug("event=TAX_UNIT_SKIPPED unit={} reason=no-rated-region", unit.id().value());
        continue;
      }
      ratedRegions.sort(
          Comparator.comparing((Map.Entry<RegionId, Long> entry) -> entry.getKey().value()));

      List<Map.Entry<RegionId, Long>> existingRegions = new ArrayList<>();
      for (Map.Entry<RegionId, Long> entry : ratedRegions) {
        if (map.regions().containsKey(entry.getKey())) {
          existingRegions.add(entry);
        } else {
          gaps.add(
              new Gap(
                  GapKind.REGION_MISSING,
                  unit.id().value(),
                  entry.getKey().value(),
                  entry.getValue(),
                  "管辖 region 不在 map.regions() 里"));
          LOG.debug(
              "event=TAX_REGION_MISSING unit={} region={} ratePerMille={}",
              unit.id().value(),
              entry.getKey().value(),
              entry.getValue());
        }
      }
      if (existingRegions.isEmpty()) {
        continue; // 全部区域缺失 ⇒ 本 tick 无一笔可征。
      }

      HouseholdId treasury;
      try {
        treasury = GovernmentHouseholdResolver.requireGovernmentHousehold(unit, unit.id().value());
      } catch (IllegalArgumentException notResolvable) {
        gaps.add(
            new Gap(
                GapKind.NO_GOVERNMENT_HOUSEHOLD,
                unit.id().value(),
                "",
                0L,
                notResolvable.getMessage()));
        LOG.warn(
            "event=TAX_NO_GOVERNMENT_HOUSEHOLD unit={} reason={}",
            unit.id().value(),
            notResolvable.getMessage());
        continue;
      }
      if (accounts.householdAccount(treasury) == null) {
        gaps.add(
            new Gap(
                GapKind.TREASURY_ACCOUNT_MISSING,
                unit.id().value(),
                "",
                0L,
                "政府家户 " + treasury.value() + " 不在账户会话里（拒绝把税落进看不见的账）"));
        LOG.warn(
            "event=TAX_TREASURY_ACCOUNT_MISSING unit={} treasury={}",
            unit.id().value(),
            treasury.value());
        continue;
      }
      Optional<HexCoord> seat = units.effectivePosition(unit.id(), SimosTimestamp.of(tick));
      if (seat.isEmpty()) {
        gaps.add(
            new Gap(
                GapKind.NO_POSITION, unit.id().value(), "", 0L, "单位当刻没有有效位置（无座位的 GOV 不征，沿用旧合约）"));
        LOG.debug("event=TAX_UNIT_SKIPPED unit={} reason=no-position", unit.id().value());
        continue;
      }

      boolean chargedThisUnit = false;
      for (Map.Entry<RegionId, Long> regionEntry : existingRegions) {
        Region region = map.regions().get(regionEntry.getKey());
        long rate = regionEntry.getValue();
        List<HexCoord> regionHexes = new ArrayList<>(region.hexes());
        regionHexes.sort(Comparator.comparing(HexCoord::toString));
        for (HexCoord hex : regionHexes) {
          for (HouseholdId household : householdsByHex.getOrDefault(hex, List.of())) {
            if (household.equals(treasury)) {
              continue; // 自征自返只是账面噪声（排除本 GOV 自己的国库家户）。
            }
            AccountSession.ActorAccount account = accounts.householdAccount(household);
            if (account == null) {
              gaps.add(
                  new Gap(
                      GapKind.HOUSEHOLD_ACCOUNT_MISSING,
                      unit.id().value(),
                      regionEntry.getKey().value(),
                      rate,
                      "家户 " + household.value() + " 在 region 内但没有账户（拒绝静默免征）"));
              LOG.warn(
                  "event=TAX_HOUSEHOLD_ACCOUNT_MISSING unit={} region={} household={}",
                  unit.id().value(),
                  regionEntry.getKey().value(),
                  household.value());
              continue;
            }

            HouseholdInventory view = goodsView(account);
            Assessment grain = assess(view, false, rate, efficiency);
            Assessment money = assess(view, true, rate, efficiency);
            grainAssessed = saturatedAdd(grainAssessed, grain.assessed());
            grainCollected = saturatedAdd(grainCollected, grain.collected());
            grainAdminShortfall = saturatedAddSigned(grainAdminShortfall, grain.adminShortfall());
            grainStockShortfall = saturatedAddSigned(grainStockShortfall, grain.stockShortfall());
            moneyAssessed = saturatedAdd(moneyAssessed, money.assessed());
            moneyCollected = saturatedAdd(moneyCollected, money.collected());
            moneyAdminShortfall = saturatedAddSigned(moneyAdminShortfall, money.adminShortfall());
            moneyStockShortfall = saturatedAddSigned(moneyStockShortfall, money.stockShortfall());
            if (grain.collected() == 0L && money.collected() == 0L) {
              continue;
            }

            // ★★ P1.2 / 2026-10-09 用户裁定：税不再自己拼负增量 + 直接 AccountSession.commit，而是"构造通用扣除
            //   + 调共享服务"。收款方 = 政府家户 ⇒ 原子转移（扣减与收款在同一次 commit 里）。
            //   ★ 可用量在 assess() 里已按 AvailableStock 算过 collected ≤ available ⇒ 服务侧的余额/冻结判据必然通过；
            //     这里仍走服务，是为了让"税 / 行政俸禄 / 军俸"只有一条落账路径、只有一份扣账语义。
            Map<CommodityId, Long> taxGoods = new LinkedHashMap<>();
            Map<CurrencyId, Long> taxMoney = new LinkedHashMap<>();
            if (grain.collected() > 0L) {
              taxGoods.put(GRAIN, grain.collected());
            }
            if (money.collected() > 0L) {
              taxMoney.put(SILVER, money.collected());
            }
            StockDeductionService.deduct(
                accounts,
                HouseholdStockDeduction.transfer(
                    household,
                    treasury,
                    taxGoods,
                    taxMoney,
                    DeductionReason.JURISDICTION_TAX,
                    "unit="
                        + unit.id().value()
                        + " region="
                        + regionEntry.getKey().value()
                        + " ratePerMille="
                        + rate
                        + " efficiencyPerMille="
                        + efficiency));
            if (grain.collected() > 0L) {
              grainByHousehold.merge(household, grain.collected(), Math::addExact);
            }
            chargedThisUnit = true;
            chargedHouseholds.add(household);
            if (TRACE.isTraceEnabled()) {
              TRACE.trace(
                  "event=TAX_COLLECTED unit={} region={} household={} treasury={} ratePerMille={}"
                      + " efficiencyPerMille={} grain={} silver={}",
                  unit.id().value(),
                  regionEntry.getKey().value(),
                  household.value(),
                  treasury.value(),
                  rate,
                  efficiency,
                  grain.collected(),
                  money.collected());
            }
          }
        }
      }
      if (chargedThisUnit) {
        unitsCharged++;
      }
    }

    Report report =
        new Report(
            new Dimension(grainAssessed, grainCollected, grainAdminShortfall, grainStockShortfall),
            new Dimension(moneyAssessed, moneyCollected, moneyAdminShortfall, moneyStockShortfall),
            unitsCharged,
            chargedHouseholds.size(),
            grainByHousehold,
            gaps);
    LOG.info(
        "event=TAX_DAILY_END tick={} unitsCharged={} householdsCharged={} grainAssessed={}"
            + " grainCollected={} grainAdminShortfall={} grainStockShortfall={} moneyAssessed={}"
            + " moneyCollected={} moneyAdminShortfall={} moneyStockShortfall={} gaps={}",
        tick,
        unitsCharged,
        chargedHouseholds.size(),
        grainAssessed,
        grainCollected,
        grainAdminShortfall,
        grainStockShortfall,
        moneyAssessed,
        moneyCollected,
        moneyAdminShortfall,
        moneyStockShortfall,
        gaps.size());
    return report;
  }

  // ── 逐维算式（不写账，只算）────────────────────────────────────────────────────────────

  /**
   * 一本家户账在一个维度上的一次征收计算：{@code assessed = floor(balance × rate‰)}； {@code attainable =
   * floor(assessed × efficiency‰)}；{@code collected = min(attainable, available)}。 余额 ≤ 0 ⇒
   * 跳过（assessed/attainable/collected 全 0，不记缺口）。
   */
  private static Assessment assess(
      HouseholdInventory inventory, boolean money, long ratePerMille, long efficiencyPerMille) {
    long balance =
        money
            ? inventory.money().getOrDefault(SILVER, 0L)
            : inventory.balances().getOrDefault(GRAIN, 0L);
    if (balance <= 0L) {
      return Assessment.skipped();
    }
    long assessed = scalePerMille(balance, ratePerMille);
    long attainable = scalePerMille(assessed, efficiencyPerMille);
    long available =
        money
            ? AvailableStock.available(inventory, SILVER)
            : AvailableStock.available(inventory, GRAIN);
    long collected = Math.min(attainable, available);
    return new Assessment(assessed, attainable, collected);
  }

  /**
   * ★ <b>溢出安全的 {@code floor(value × perMille / 1000)}</b>（{@code value ≥ 0}、{@code perMille ∈
   * [0,1100]}）， 口径与旧 {@code JurisdictionDailyTax} 逐字相同，避免溢出被静默截断。
   */
  private static long scalePerMille(long value, long perMille) {
    if (value < 0L || perMille < 0L || perMille > 1100L) {
      throw new IllegalArgumentException(
          "scalePerMille 的 value 必须 ≥ 0、perMille 必须 ∈ [0,1100]: value="
              + value
              + " perMille="
              + perMille);
    }
    long whole = value / 1000L;
    long remainder = value % 1000L;
    long bonus = perMille - 1000L;
    if (bonus <= 0L) {
      return whole * perMille + remainder * perMille / 1000L;
    }
    long base = value;
    long extra = whole * bonus + remainder * bonus / 1000L;
    if (extra > Long.MAX_VALUE - base) {
      return Long.MAX_VALUE;
    }
    return base + extra;
  }

  /** 账户会话活账 → 只读 {@link HouseholdInventory} 视图（只为复用 {@link AvailableStock} 的唯一减法）。 */
  private static HouseholdInventory goodsView(AccountSession.ActorAccount account) {
    return new HouseholdInventory(
        new HouseholdAccountKey(account.household()),
        account.goods(),
        account.money(),
        account.frozenGoods(),
        account.frozenMoney());
  }

  private static long saturatedAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static long saturatedAddSigned(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return right > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }

  // ── 报告形状 ─────────────────────────────────────────────────────────────────────────

  /** 具名缺口种类（不静默跳过）。 */
  enum GapKind {
    /** 管辖里的 region 不在 map.regions()。 */
    REGION_MISSING,
    /** 单位当刻没有有效位置（无座位不征）。 */
    NO_POSITION,
    /** 带 GovernmentFormation 却解析不出政府家户（状态损坏；正常路径由 UnitState/GovernmentHouseholdWiring 拦下）。 */
    NO_GOVERNMENT_HOUSEHOLD,
    /** 政府家户不在账户会话里（状态损坏）。 */
    TREASURY_ACCOUNT_MISSING,
    /** region 内的家户行没有账户（状态损坏）。 */
    HOUSEHOLD_ACCOUNT_MISSING
  }

  /** 一个具名缺口。{@code regionId} 无值时用空串（不发明 null）。 */
  record Gap(GapKind kind, String unitId, String regionId, long ratePerMille, String detail) {

    Gap {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(regionId, "regionId");
      Objects.requireNonNull(detail, "detail");
    }
  }

  /** 一个维度（粮或银）的四个累计量；恒等式 {@code collected + adminShortfall + stockShortfall == assessed}。 */
  record Dimension(long assessed, long collected, long adminShortfall, long stockShortfall) {

    Dimension {
      if (assessed < 0L || collected < 0L || stockShortfall < 0L) {
        throw new IllegalArgumentException(
            "Dimension 的 assessed/collected/stockShortfall 必须 ≥ 0: "
                + assessed
                + "/"
                + collected
                + "/"
                + stockShortfall);
      }
    }

    static Dimension zero() {
      return new Dimension(0L, 0L, 0L, 0L);
    }

    boolean isEmpty() {
      return assessed == 0L && collected == 0L && adminShortfall == 0L && stockShortfall == 0L;
    }
  }

  /** 本日税单（不可变读数；{@code grainByHousehold} 供调用方写 {@code FlowRow.taxPaid}）。 */
  record Report(
      Dimension grain,
      Dimension money,
      long unitsCharged,
      long householdsCharged,
      Map<HouseholdId, Long> grainByHousehold,
      List<Gap> gaps) {

    Report {
      Objects.requireNonNull(grain, "grain");
      Objects.requireNonNull(money, "money");
      Objects.requireNonNull(grainByHousehold, "grainByHousehold");
      Objects.requireNonNull(gaps, "gaps");
      if (unitsCharged < 0L || householdsCharged < 0L) {
        throw new IllegalArgumentException(
            "Report 的计数不得为负: units=" + unitsCharged + " households=" + householdsCharged);
      }
      grainByHousehold = Collections.unmodifiableMap(new LinkedHashMap<>(grainByHousehold));
      gaps = List.copyOf(gaps);
    }

    static Report empty() {
      return new Report(Dimension.zero(), Dimension.zero(), 0L, 0L, Map.of(), List.of());
    }

    boolean isEmpty() {
      return grain.isEmpty() && money.isEmpty() && gaps.isEmpty();
    }
  }

  /** 单账户单维的征收三件套（中间量只在本类内用）。 */
  private record Assessment(long assessed, long attainable, long collected) {

    Assessment {
      if (assessed < 0L || attainable < 0L || collected < 0L) {
        throw new IllegalArgumentException(
            "Assessment 不得为负: " + assessed + "/" + attainable + "/" + collected);
      }
      if (collected > attainable) {
        throw new IllegalArgumentException(
            "Assessment.collected 不得超过 attainable: " + collected + " > " + attainable);
      }
    }

    static Assessment skipped() {
      return new Assessment(0L, 0L, 0L);
    }

    long adminShortfall() {
      return assessed - attainable;
    }

    long stockShortfall() {
      return attainable - collected;
    }
  }
}
