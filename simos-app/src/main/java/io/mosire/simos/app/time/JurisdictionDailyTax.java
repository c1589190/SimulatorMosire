package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>阶段 6.3：长期税的纯推导</b>（辖区阶段，计划 §6.3 的口径逐条落在这里；阶段 11b 起行政效率改读 GOV 读数）。
 *
 * <p>它被 {@link ClassFirstPopulationEconomyTimeParticipant} 的日循环调用——<b>不</b>另起 participant，理由是架构硬约束：
 * {@code TimeProposalResolver} 对两个参与者的<b>同名模块变更 = module clash ⇒ 拒整次推进</b>（Core 手里的 ChangeSet
 * 不透明、无法合并两份）。长期税要写 actor 家户账与单位国库账，而 population 参与者已经是 actor 写面的唯一参与者 ⇒ 只能并进它的日循环。
 *
 * <p>★★ <b>本类是纯函数</b>：不碰 {@code ToolContext} / {@code CoreSimos} / 日志（日志由调用方在推进结束写一条）； 同一份 {@code
 * (actor, units, map, tick, efficiencyPerMilleByUnit)} ⇒ 逐字段相同的结果。它不修改入参，返回的账本要么是同一个 {@link
 * ActorData} 实例（没有任何征收），要么是只换了 {@code accounts} 组件的新实例（{@code meta}/{@code actors} 一字不动）。
 *
 * <p>★★ <b>征收口径（逐条对应任务书 §6.3 + 阶段 11b 裁定 7/8）</b>：
 *
 * <ol>
 *   <li><b>逐单位</b>：{@code units.units()} 全表按 {@link UnitId#value()} 升序；只收 {@code jurisdiction}
 *       present 且 <b>{@code efficiencyPerMilleByUnit} 里查得到该单位</b> 的单位；
 *   <li><b>无 GOV ⇒ 不征（具名口径）</b>：某单位查不到效率（没有 {@code GovFormation}/没有 GOV 读数）⇒ <b>整单位跳过、不征</b>；不读
 *       {@code Jurisdiction.administrationPerMille} 的旧值、不补 0、不发明来源。 为什么用 {@code Map<UnitId, Long>}
 *       而不是 {@code ToLongFunction}：{@code ToLongFunction} 无法表达“查不到” （只能回 0，而 0
 *       与“没有读数”在口径上必须分开），{@code Map.get} 的 {@code null} 正好是有齿的存在性检查；
 *   <li><b>逐区域</b>：该单位 {@code taxRatePerMilleByRegion} 的 key 按 {@link RegionId#value()} 升序，rate = 0
 *       跳过；区域不在 {@code map.regions()} ⇒ 具名 {@link GapKind#REGION_MISSING} 缺口并跳过；
 *   <li><b>国库落点</b>：{@code units.effectivePosition(unitId, SimosTimestamp.of(tick))}；空 ⇒ 具名 {@link
 *       GapKind#NO_POSITION} 缺口并跳过该单位（该 tick 不征）；
 *   <li><b>税基</b>：区域各 hex 上的 {@link ActorKind#HOUSEHOLD} 账，按账键 {@link GoodsAccountKey#toString()}
 *       升序；<b>余额 ≤ 0 跳过</b>（粮看 {@link #GRAIN}、钱看 {@link #SILVER}，两个维度各判各的）；
 *   <li><b>算式</b>：{@code assessed = floor(balance × rate / 1000)}（溢出安全拆法见 {@link #scalePerMille}）；
 *       {@code attainable = floor(assessed × efficiency / 1000)}（效率可直接取 GOV 读数的 {@code [0,1100]}；
 *       {@code >1000} 时 {@code attainable > assessed} = 超编加成带来的行政超收能力）； {@code collected =
 *       min(attainable, available)}， 其中 {@code available} 只经 {@link AvailableStock} （全仓唯一的“余额 −
 *       冻结”算法，本类不写减法）；
 *   <li><b>落账</b>：{@code collected > 0} 才写——家户账负增量（粮 {@link #GRAIN}、钱 {@link #SILVER}），国库账 {@code
 *       GoodsAccountKey(ActorRef(UNIT, unitId), 有效位置)} 正增量；缺国库账 ⇒ 五参新建（冻结表空），已有 ⇒
 *       保留其两张冻结表。家户同一天两个维度先各自按<b>同一本税前的账</b>算足，再<b>合并成一次改写</b> （不会拿旧值覆盖丢改动）；冻结表、其它键序、0 余额一律原样保留；
 *   <li><b>缺口累计（有符号）</b>：{@code adminShortfall += assessed − attainable}、{@code stockShortfall +=
 *       attainable − collected}，逐维累计。★ 效率 {@code ≤1000} 时 {@code adminShortfall ≥ 0}（行政能力不足）； 效率
 *       {@code >1000} 时它为负 = 行政超收，恒等式 {@code collected + adminShortfall + stockShortfall ==
 *       assessed} 仍成立（无溢出时）；
 *   <li><b>重叠管辖</b>：同一天多单位命中同一区域/同一本账 ⇒ 按单位 id 升序依次征，后者见前者税后余额（本类用一张 保序工作账演进，确定性）；
 *   <li><b>空结果</b>：没有任何征收 ⇒ 返回<b>同一个</b> {@link ActorData} 实例（让上层 {@code ActorChangeSet.between}
 *       自然落 Unchanged）。★ 只有缺口、没有征收时同样返回同一个实例，但 {@link Report} 带着缺口（缺口必须可见，不能静默）。
 * </ol>
 *
 * <p>★ <b>边界（本批具名）</b>：只动 actor 家户账 + 单位国库账；classfirst 阶层池库存不动（见计划 §6.0-3）。
 *
 * <p>★ <b>已知耦合（计划 §6.3 具名）</b>：税抽走家户账余额后会减少 {@code ClassFirstActorWriteback} 负增量分摊的可用池； 若某户不足，次日写回
 * fail-closed 当场抛 = "政策超出账本承受力"的响亮失败（测试阶段须专门构造高压税用例）。
 */
final class JurisdictionDailyTax {

  /** 粮的商品 id（{@link PilotModel#GRAIN} 的<b>唯一</b>字面量来源；本类不另写 {@code "grain"}）。 */
  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);

  /** 钱的币种（{@link MoneyVocabulary#SILVER_CURRENCY} 的<b>唯一</b>字面量来源；本类不另写 {@code "silver"}）。 */
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private JurisdictionDailyTax() {}

  /**
   * 纯推导入口：对 {@code tick} 这一天做一次长期税。
   *
   * @param actor 税前的 actor 切片（只读，不改）
   * @param units 只读的 unit 切片（管辖、有效位置）
   * @param map 只读的地图切片（区域的 hex 集）
   * @param tick 世界日（{@code units.effectivePosition} 的取位时刻）
   * @param efficiencyPerMilleByUnit 单位 id → 当日 GOV 行政效率‰；<b>缺失键 = 没有 GOV 读数 ⇒ 整单位跳过、不征</b> （不读
   *     {@code Jurisdiction.administrationPerMille} 的旧值，不补 0）
   * @return 新的 actor（无征收时是入参同一实例）+ 本日 {@link Report}
   */
  static Collected collect(
      ActorData actor,
      UnitState units,
      GameMap map,
      long tick,
      Map<UnitId, Long> efficiencyPerMilleByUnit) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(
        efficiencyPerMilleByUnit, "efficiencyPerMilleByUnit（没有 GOV 读数用 Map.of()）");
    if (tick < 0L) {
      throw new IllegalArgumentException("tick 不得为负: " + tick);
    }

    List<Unit> orderedUnits = new ArrayList<>(units.units().values());
    orderedUnits.sort(Comparator.comparing((Unit unit) -> unit.id().value()));

    // ★ 保序工作账：原键序原样带过，新国库账按"首次创建"顺序追加在末尾（LinkedHashMap，禁 Map.copyOf）。
    Map<GoodsAccountKey, GoodsAccount> working = new LinkedHashMap<>(actor.accounts());
    List<Gap> gaps = new ArrayList<>();
    LinkedHashSet<GoodsAccountKey> chargedHouseholds = new LinkedHashSet<>();
    long unitsCharged = 0L;
    long grainAssessed = 0L;
    long grainCollected = 0L;
    long grainAdminShortfall = 0L;
    long grainStockShortfall = 0L;
    long moneyAssessed = 0L;
    long moneyCollected = 0L;
    long moneyAdminShortfall = 0L;
    long moneyStockShortfall = 0L;

    for (Unit unit : orderedUnits) {
      Jurisdiction jurisdiction = unit.jurisdiction().orElse(null);
      Long efficiencyPerMille = efficiencyPerMilleByUnit.get(unit.id());
      // ★ 无 GOV 读数（查不到效率）⇒ 整单位跳过、不征：不读旧字段、不补 0、不记缺口（无数据就没有）。
      if (jurisdiction == null || efficiencyPerMille == null) {
        continue;
      }
      if (efficiencyPerMille < 0L || efficiencyPerMille > 1100L) {
        throw new IllegalArgumentException(
            "GOV 效率必须 ∈ [0,1100]: unit="
                + unit.id().value()
                + " efficiency‰="
                + efficiencyPerMille);
      }
      List<Map.Entry<RegionId, Long>> ratedRegions = new ArrayList<>();
      for (Map.Entry<RegionId, Long> entry : jurisdiction.taxRatePerMilleByRegion().entrySet()) {
        if (entry.getValue() > 0L) {
          ratedRegions.add(entry); // rate = 0 整段跳过（不扫描、不记缺口、不参与任何累计）。
        }
      }
      if (ratedRegions.isEmpty()) {
        continue;
      }
      ratedRegions.sort(
          Comparator.comparing((Map.Entry<RegionId, Long> entry) -> entry.getKey().value()));

      // ★ 先登记区域缺口（region 不在图上 ⇒ 具名跳过），再决定是否需要国库落点。
      List<Map.Entry<RegionId, Long>> existingRegions = new ArrayList<>();
      for (Map.Entry<RegionId, Long> entry : ratedRegions) {
        if (map.regions().containsKey(entry.getKey())) {
          existingRegions.add(entry);
        } else {
          gaps.add(Gap.regionMissing(unit.id(), entry.getKey(), entry.getValue()));
        }
      }
      if (existingRegions.isEmpty()) {
        continue; // 全部区域缺失 ⇒ 本 tick 无一笔可征，不因"没有国库落点"再记第二条缺口。
      }
      Optional<HexCoord> treasuryAt = units.effectivePosition(unit.id(), SimosTimestamp.of(tick));
      if (treasuryAt.isEmpty()) {
        gaps.add(Gap.noPosition(unit.id(), existingRegions.size()));
        continue;
      }
      GoodsAccountKey treasuryKey =
          new GoodsAccountKey(new ActorRef(ActorKind.UNIT, unit.id().value()), treasuryAt.get());
      long efficiency = efficiencyPerMille;
      boolean chargedThisUnit = false;

      for (Map.Entry<RegionId, Long> regionEntry : existingRegions) {
        Region region = map.regions().get(regionEntry.getKey());
        long rate = regionEntry.getValue();
        for (GoodsAccountKey householdKey : householdAccounts(working, region)) {
          GoodsAccount book = working.get(householdKey);
          // ★ 两个维度都按这本"税前账"算足（互不依赖），再合并成一次改写 ⇒ 不丢改动。
          Assessment grain = assess(book, false, rate, efficiency);
          Assessment money = assess(book, true, rate, efficiency);
          grainAssessed = saturatedAdd(grainAssessed, grain.assessed());
          grainCollected = saturatedAdd(grainCollected, grain.collected());
          grainAdminShortfall = saturatedAddSigned(grainAdminShortfall, grain.adminShortfall());
          grainStockShortfall = saturatedAdd(grainStockShortfall, grain.stockShortfall());
          moneyAssessed = saturatedAdd(moneyAssessed, money.assessed());
          moneyCollected = saturatedAdd(moneyCollected, money.collected());
          moneyAdminShortfall = saturatedAddSigned(moneyAdminShortfall, money.adminShortfall());
          moneyStockShortfall = saturatedAdd(moneyStockShortfall, money.stockShortfall());
          if (grain.collected() == 0L && money.collected() == 0L) {
            continue;
          }

          // ★ 家户账：两个维度合并成一次改写（本账上其它键/冻结表/0 余额原样保留）。
          GoodsAccount updated = book;
          if (grain.collected() > 0L) {
            updated = subtract(updated, false, grain.collected());
          }
          if (money.collected() > 0L) {
            updated = subtract(updated, true, money.collected());
          }
          working.put(householdKey, updated);
          // ★ 国库账：缺账 ⇒ 五参新建（冻结表空）；已有 ⇒ 保留两张冻结表加增量。
          if (grain.collected() > 0L) {
            working.put(
                treasuryKey,
                addTo(working.get(treasuryKey), treasuryKey, false, grain.collected()));
          }
          if (money.collected() > 0L) {
            working.put(
                treasuryKey, addTo(working.get(treasuryKey), treasuryKey, true, money.collected()));
          }
          chargedHouseholds.add(householdKey);
          chargedThisUnit = true;
        }
      }
      if (chargedThisUnit) {
        unitsCharged++;
      }
    }

    Dimension grain =
        new Dimension(grainAssessed, grainCollected, grainAdminShortfall, grainStockShortfall);
    Dimension money =
        new Dimension(moneyAssessed, moneyCollected, moneyAdminShortfall, moneyStockShortfall);
    Report report = new Report(grain, money, unitsCharged, chargedHouseholds.size(), gaps);
    // ★ 没有任何征收 ⇒ 同一个 ActorData 实例（缺口只进 Report；上层的 between 自然落 Unchanged）。
    ActorData result = chargedHouseholds.isEmpty() ? actor : actor.withAccounts(working);
    return new Collected(result, report);
  }

  // ── 逐户/逐维的算式（不写账，只算）────────────────────────────────────────────────────

  /**
   * 一本家户账在一个维度上的一次征收计算。
   *
   * <p>{@code balance} 是该维度的<b>票面余额</b>（冻结也计入税基）；{@code available} 走 {@link AvailableStock} （余额 −
   * 冻结，唯一算法）；{@code assessed = floor(balance × rate / 1000)}；{@code attainable = floor(assessed ×
   * efficiency / 1000)}（efficiency ∈ [0,1100]）；{@code collected = min(attainable, available)}。 效率
   * &gt; 1000‰ 时 {@code attainable &gt; assessed}，是超编加成带来的行政超收能力（计划 §3 的公式原样）。
   */
  private static Assessment assess(
      GoodsAccount account, boolean money, long ratePerMille, long efficiencyPerMille) {
    long balance =
        money
            ? account.money().getOrDefault(SILVER, 0L)
            : account.balances().getOrDefault(GRAIN, 0L);
    if (balance <= 0L) {
      return Assessment.skipped(); // 余额 ≤ 0 跳过：assessed/attainable/collected 全 0，不记缺口。
    }
    long assessed = scalePerMille(balance, ratePerMille);
    long attainable = scalePerMille(assessed, efficiencyPerMille);
    long available =
        money
            ? AvailableStock.available(account, SILVER)
            : AvailableStock.available(account, GRAIN);
    long collected = Math.min(attainable, available);
    return new Assessment(assessed, attainable, collected);
  }

  /**
   * ★ <b>溢出安全的 {@code floor(value × perMille / 1000)}</b>（{@code value ≥ 0}、{@code perMille ∈
   * [0,1100]}：税率 ≤1000‰、GOV 效率 ≤1100‰，两者都是本路径的入口不变量）。
   *
   * <p>拆法：{@code value = (value/1000)×1000 + value%1000}，并把 {@code perMille = 1000 + bonus}（bonus ∈
   * [0,100]）：
   *
   * <pre>
   * floor(value×perMille/1000) = value + (value/1000)×bonus + floor((value%1000)×bonus/1000)
   * </pre>
   *
   * 第一项 = value；第二项 ≤ value/10；第三项 ≤ 99。逐项都不越过 long，且只有当数学结果本身 &gt; {@link Long#MAX_VALUE}（即 value
   * 已接近 long 上限且 bonus 把结果推出界）时饱和到 {@code Long.MAX_VALUE}——
   * 余额量级下不可达，只为不把溢出静默成错误的小数。后一项是整数除法，对被除数非负即 floor。
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
    long base = value; // 公式里的第一项；不溢出
    long extra = whole * bonus + remainder * bonus / 1000L; // whole×100 ≤ value/10；两项都不溢出
    if (extra > Long.MAX_VALUE - base) {
      return Long.MAX_VALUE; // 数学结果本身超过 long 上限：饱和（余额量级不可达）
    }
    return base + extra;
  }

  // ── 账本改写小件（整本覆盖 → 新 GoodsAccount，不改运行对象）──────────────────────────────

  /**
   * 家户账<b>负增量</b>：只改该维度，其它键序/冻结表/0 余额原样带过。
   *
   * <p>{@code amount ≤ available} 由 {@link #assess} 保证；这里再 fail-closed 判一次"不得越过余额"——真越了说明
   * 本类内部不自洽，宁抛不静默。
   */
  private static GoodsAccount subtract(GoodsAccount account, boolean money, long amount) {
    if (amount <= 0L) {
      throw new IllegalArgumentException("amount 必须 > 0: " + amount);
    }
    if (money) {
      Map<CurrencyId, Long> nextMoney = new LinkedHashMap<>(account.money());
      long current = nextMoney.getOrDefault(SILVER, 0L);
      if (amount > current) {
        throw new IllegalStateException(
            "长期税货币扣减超出余额（内部不自洽）：key="
                + account.key()
                + " amount="
                + amount
                + " balance="
                + current);
      }
      nextMoney.put(SILVER, current - amount); // ★ 扣成 0 也保留这条键（0 余额是事实，不做归并）。
      return new GoodsAccount(
          account.key(),
          account.balances(),
          nextMoney,
          account.frozenBalances(),
          account.frozenMoney());
    }
    Map<CommodityId, Long> nextBalances = new LinkedHashMap<>(account.balances());
    long current = nextBalances.getOrDefault(GRAIN, 0L);
    if (amount > current) {
      throw new IllegalStateException(
          "长期税粮食扣减超出余额（内部不自洽）：key=" + account.key() + " amount=" + amount + " balance=" + current);
    }
    nextBalances.put(GRAIN, current - amount);
    return new GoodsAccount(
        account.key(),
        nextBalances,
        account.money(),
        account.frozenBalances(),
        account.frozenMoney());
  }

  /**
   * 国库账<b>正增量</b>：{@code accountOrNull == null} ⇒ 五参新建（两张冻结表空）；已有 ⇒ 保留其两张冻结表加增量。
   *
   * <p>金额用 {@link Math#addExact}（真溢出当场抛，不静默截断）。
   */
  private static GoodsAccount addTo(
      GoodsAccount accountOrNull, GoodsAccountKey key, boolean money, long amount) {
    if (amount <= 0L) {
      throw new IllegalArgumentException("amount 必须 > 0: " + amount);
    }
    if (money) {
      Map<CurrencyId, Long> nextMoney =
          accountOrNull == null
              ? new LinkedHashMap<>()
              : new LinkedHashMap<>(accountOrNull.money());
      nextMoney.put(SILVER, Math.addExact(nextMoney.getOrDefault(SILVER, 0L), amount));
      Map<CommodityId, Long> balances =
          accountOrNull == null
              ? new LinkedHashMap<>()
              : new LinkedHashMap<>(accountOrNull.balances());
      Map<CommodityId, Long> frozenBalances =
          accountOrNull == null ? Map.of() : accountOrNull.frozenBalances();
      Map<CurrencyId, Long> frozenMoney =
          accountOrNull == null ? Map.of() : accountOrNull.frozenMoney();
      return new GoodsAccount(key, balances, nextMoney, frozenBalances, frozenMoney);
    }
    Map<CommodityId, Long> nextBalances =
        accountOrNull == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(accountOrNull.balances());
    nextBalances.put(GRAIN, Math.addExact(nextBalances.getOrDefault(GRAIN, 0L), amount));
    Map<CurrencyId, Long> moneyBalances =
        accountOrNull == null ? new LinkedHashMap<>() : new LinkedHashMap<>(accountOrNull.money());
    Map<CommodityId, Long> frozenBalances =
        accountOrNull == null ? Map.of() : accountOrNull.frozenBalances();
    Map<CurrencyId, Long> frozenMoney =
        accountOrNull == null ? Map.of() : accountOrNull.frozenMoney();
    return new GoodsAccount(key, nextBalances, moneyBalances, frozenBalances, frozenMoney);
  }

  // ── 税基枚举 ─────────────────────────────────────────────────────────────────────────

  /** 区域各 hex 上的 {@link ActorKind#HOUSEHOLD} 账键，按 {@link GoodsAccountKey#toString()} 升序（全序）。 */
  private static List<GoodsAccountKey> householdAccounts(
      Map<GoodsAccountKey, GoodsAccount> working, Region region) {
    List<GoodsAccountKey> keys = new ArrayList<>();
    for (GoodsAccountKey key : working.keySet()) {
      if (key.owner().kind() == ActorKind.HOUSEHOLD && region.hexes().contains(key.location())) {
        keys.add(key);
      }
    }
    keys.sort(Comparator.comparing(GoodsAccountKey::toString));
    return keys;
  }

  /** 饱和加法（非负 long；溢出取 {@link Long#MAX_VALUE}）——只用于合计，不参与逐值扣减。 */
  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  /**
   * 有符号饱和加法（{@code adminShortfall} 可为负：效率 &gt;1000‰ 时它是行政超收，不是缺口）； 溢出方向决定取 {@link Long#MAX_VALUE} /
   * {@link Long#MIN_VALUE}。同样只用于合计。
   */
  private static long saturatedAddSigned(long left, long right) {
    long sum = left + right;
    if (((left ^ sum) & (right ^ sum)) < 0L) {
      return left > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
    return sum;
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0L) {
      throw new IllegalArgumentException(name + " 不得为负: " + value);
    }
  }

  // ── 结果类型（保序不可变）────────────────────────────────────────────────────────────

  /** 缺口种类（具名，不静默）。 */
  enum GapKind {
    /** 单位管辖的区域不在 {@code map.regions()} 里。 */
    REGION_MISSING,
    /** 单位当刻没有有效位置，国库落点无法确定 ⇒ 该 tick 不征。 */
    NO_POSITION
  }

  /**
   * 一条具名缺口。
   *
   * @param kind 缺口种类
   * @param unitId 相关单位 id（裸值）
   * @param regionId 相关区域 id（{@link GapKind#NO_POSITION} 是单位级缺口 ⇒ 为 {@code null}）
   * @param number {@link GapKind#REGION_MISSING} 时 = 该区域配置的税率‰；{@link GapKind#NO_POSITION} 时 =
   *     因无位置而跳过的"有税率的既有区域"条数
   */
  record Gap(GapKind kind, String unitId, String regionId, long number) {

    Gap {
      Objects.requireNonNull(kind, "kind");
      if (unitId == null || unitId.isBlank()) {
        throw new IllegalArgumentException("unitId 不得为空白");
      }
      requireNonNegative(number, "number");
      if (kind == GapKind.REGION_MISSING) {
        if (regionId == null || regionId.isBlank()) {
          throw new IllegalArgumentException("REGION_MISSING 必须带 regionId: unit=" + unitId);
        }
      } else if (regionId != null) {
        throw new IllegalArgumentException(
            "NO_POSITION 是单位级缺口、不带单一 regionId: unit=" + unitId + " region=" + regionId);
      }
    }

    static Gap regionMissing(UnitId unit, RegionId region, long ratePerMille) {
      return new Gap(GapKind.REGION_MISSING, unit.value(), region.value(), ratePerMille);
    }

    static Gap noPosition(UnitId unit, long ratedRegionCount) {
      return new Gap(GapKind.NO_POSITION, unit.value(), null, ratedRegionCount);
    }

    /** 日志摘要用的一行文本（确定性；不含时间/墙钟）。 */
    String summary() {
      return kind == GapKind.REGION_MISSING
          ? "REGION_MISSING(unit=" + unitId + ",region=" + regionId + ",rate‰=" + number + ")"
          : "NO_POSITION(unit=" + unitId + ",ratedRegions=" + number + ")";
    }
  }

  /**
   * 一个维度（粮/钱）的汇总：{@code collected + adminShortfall + stockShortfall == assessed}（无溢出时）。
   *
   * <p>★ <b>{@code adminShortfall} 是有符号量</b>：GOV 效率 ≤1000‰ 时它 = 行政能力吃掉的缺口（{@code assessed −
   * attainable}，≥0）；效率 &gt;1000‰（超编加成）时它为负 = 票面评估之外的行政超收能力。恒等式在两种情况下都成立。
   *
   * @param assessed 全部尝试过计税的家户余额 × 税率之和（≥0）
   * @param collected 实际入库量（≥0；效率 &gt;1000‰ 时可能 &gt; assessed）
   * @param adminShortfall 行政能力差额（{@code assessed − attainable}；可为负，见上）
   * @param stockShortfall 库存/冻结吃掉的缺口（{@code attainable − collected}，≥0）
   */
  record Dimension(long assessed, long collected, long adminShortfall, long stockShortfall) {

    Dimension {
      requireNonNegative(assessed, "assessed");
      requireNonNegative(collected, "collected");
      // ★ adminShortfall 有意允许负数：效率 >1000‰ 时「行政能力」不是缺口而是超收能力。
      requireNonNegative(stockShortfall, "stockShortfall");
    }

    static Dimension zero() {
      return new Dimension(0L, 0L, 0L, 0L);
    }

    boolean isEmpty() {
      return assessed == 0L && collected == 0L && adminShortfall == 0L && stockShortfall == 0L;
    }

    Dimension plus(Dimension other) {
      Objects.requireNonNull(other, "other");
      return new Dimension(
          saturatedAdd(assessed, other.assessed),
          saturatedAdd(collected, other.collected),
          saturatedAddSigned(adminShortfall, other.adminShortfall),
          saturatedAdd(stockShortfall, other.stockShortfall));
    }
  }

  /**
   * 一次 {@link #collect} 的汇总（保序不可变）。
   *
   * <p>★ <b>"空"的判据</b>：逐维四项全 0、{@code unitsCharged}/{@code householdsCharged} 为 0、且<b>无缺口</b> ⇒
   * {@link #isEmpty()}。只有缺口没有征收时 Report <b>非空</b>（缺口必须能被上层看见），但 actor 仍是同一个实例。
   */
  record Report(
      Dimension grain, Dimension money, long unitsCharged, long householdsCharged, List<Gap> gaps) {

    Report {
      Objects.requireNonNull(grain, "grain");
      Objects.requireNonNull(money, "money");
      requireNonNegative(unitsCharged, "unitsCharged");
      requireNonNegative(householdsCharged, "householdsCharged");
      gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps"));
    }

    static Report empty() {
      return new Report(Dimension.zero(), Dimension.zero(), 0L, 0L, List.of());
    }

    /** 逐维四项全 0 且无计数、无缺口 ⇒ 真正什么都没发生（调用方据此完全不写日志）。 */
    boolean isEmpty() {
      return grain.isEmpty()
          && money.isEmpty()
          && unitsCharged == 0L
          && householdsCharged == 0L
          && gaps.isEmpty();
    }

    /** 跨日累计（参与者的日循环把每天的报告并起来；缺口表按日序拼接）。 */
    Report plus(Report other) {
      Objects.requireNonNull(other, "other");
      List<Gap> mergedGaps = new ArrayList<>(gaps);
      mergedGaps.addAll(other.gaps);
      return new Report(
          grain.plus(other.grain),
          money.plus(other.money),
          saturatedAdd(unitsCharged, other.unitsCharged),
          saturatedAdd(householdsCharged, other.householdsCharged),
          mergedGaps);
    }
  }

  /** {@link #collect} 的结果：新 actor（无征收时是入参同一实例）+ 本日报告。 */
  record Collected(ActorData actor, Report report) {

    Collected {
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(report, "report");
    }
  }

  /** 一本账一个维度的"算出来的三个数"（不改账）；{@code attainable} 允许 &gt; {@code assessed}（效率 &gt;1000‰）。 */
  private record Assessment(long assessed, long attainable, long collected) {

    Assessment {
      requireNonNegative(assessed, "assessed");
      requireNonNegative(attainable, "attainable");
      requireNonNegative(collected, "collected");
      if (collected > attainable) {
        throw new IllegalArgumentException(
            "评估量不自洽：assessed="
                + assessed
                + " attainable="
                + attainable
                + " collected="
                + collected);
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
