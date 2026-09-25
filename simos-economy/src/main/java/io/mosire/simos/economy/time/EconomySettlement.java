package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ **R3a 日结算 + R4a 周期收获与制度分配**（聚合式经济重设计 §四 的日/周期步骤，v1 口径）——纯函数：拿 {@link EconomyData} 交**新**的
 * {@link EconomyData}，**不写状态、不碰核心**。
 *
 * <p>★★ **每天跑一次**（每次 {@code AdvanceTime}）：
 *
 * <ol>
 *   <li>**消费**：每行扣粮 {@code population × 83 毫粮}（{@link #DAILY_GRAIN_MILLI_PER_PERSON}，§十"消费"行）。
 *   <li>**缺口**：库存不够 ⇒ 先在同格内借粮（地主 → 富农 → 中农 的顺序，从有粮的行的**当日盈余**划转），借到的记一条 {@link Debt}（本金 = 借到量、利率
 *       {@code 20‰}、{@code dueCycle = 当前周期 + 1}、标的 = 粮）；**没粮可借 ⇒ 只留未满足的自然需求（{@code naturalNeeds}
 *       与实得的差），不造粮也不造债**。
 *   <li>**进度**：每个产业 {@code progressDays + 1}。
 *   <li>**劳动投入**：本产业当日实际劳动 = Σ(行 {@code laborMilli × participationPerMille / 1000}) —— 累加进 {@link
 *       Industry#cycleLaborMilli()}（供收获时算劳动瓶颈）。
 * </ol>
 *
 * <p>★★ **周期末追加**（{@code progressDays + 1 == cycleDays} 那一天，同一次日结算里）：
 *
 * <ol>
 *   <li>**产出**：{@code 实际投入亩 × 7 粮/亩 × 1000 毫粮/粮}。实际投入亩 = {@code min(可用亩, 平均每日实际劳动 × 7
 *       亩/劳动)}，**取小后向下取整到亩**（{@link #LAND_MU_PER_LABOR} / {@link #MILLI_PER_GRAIN}）。
 *   <li>**生产消耗**：扣 {@code 15%}（种子/牲畜/工具）——**明文记入本期流水**（{@link FlowRow#consumed()}），不静默丢弃。
 *   <li>**分配**：按 {@link AllocationRule.Split}：{@code 行得 = 剩余产出 × (生产资料权重 × 该行土地占比 + 劳动权重 × 该行劳动占比)
 *       / 1000}（**定点整数、残差按槽位 id 序分派、Σ行得 = 剩余产出**）。
 *   <li>产出进各行粮库存；{@code progressDays} 归零、周期劳动清零、{@link EconomyMeta#lastClosedCycle()} +1。
 * </ol>
 *
 * <p>★ **税（v1 明确不做）**：{@code government} 切片还不存在 ⇒ **不造假账**（{@code FlowRow.taxPaid} 恒 0；R7
 * 与政府切片一起做）。 市场定价、阶层流动、产业转换、矿业、日原料消耗同样不做（§四 的后续增量）。
 *
 * <p>★★ **量纲（§7 + §十）**：人口「人」；劳动「千分劳动」；土地「千分亩」；粮库存「**毫粮**」（1 粮 = 1000 毫粮， {@link
 * #MILLI_PER_GRAIN}）；利率/权重/投入率「千分」。**一切整数运算，禁 double**。
 *
 * <p>★★ **守恒（§6.1，账要平）**：本函数不凭空造粮、不凭空销粮。把每日/每期的发生额记进 {@link FlowRow} 后，恒有 {@code Σ(推进前库存) −
 * Σ(推进后库存) == Σ(流水消费) − Σ(流水所得)}：日耗与生产消耗在 {@code consumed} 里、收获的**毛产出**在 {@code income} 里（净产出进库存，差额
 * = 生产消耗）。买/借/税等跨主体转移不改变总和（同格借贷是内部划转）。
 *
 * <p>★ **未激活**（{@code meta} 空）：原样返回（不做任何公式，§6.6）。
 */
public final class EconomySettlement {

  /** 每人每日口粮（毫粮）：§十"消费"行（每人每农业周期 10 粮 ÷ 120 天 ⇒ 83 毫粮/人·日）。 */
  public static final long DAILY_GRAIN_MILLI_PER_PERSON = 83L;

  /** 1 粮 = 1000 毫粮（§7：库存按最小计量单位；{@code outputPerUnit} 是「粮/亩」⇒ 入账前要换算）。 */
  public static final long MILLI_PER_GRAIN = 1000L;

  /** 1 标准劳动（1000 千分劳动）能经营的亩数：§十 / 资料 §十 的「1 标准劳动支持 7 亩」。 */
  public static final long LAND_MU_PER_LABOR = 7L;

  /** 生产消耗（种子/牲畜/工具）千分数：15%。 */
  public static final int PRODUCTION_CONSUMPTION_PER_MILLE = 150;

  /** 同格借粮的每周期利率（千分数）：20‰。 */
  public static final int BORROW_RATE_PER_MILLE_PER_CYCLE = 20;

  /** 粮食商品 id（§十"单位"行：粮 = 1 公斤；本轮只结算这一种商品）。 */
  public static final CommodityId GRAIN = new CommodityId("grain");

  /** **借粮优先序**（§四 第 8 步 / 用户口径）：地主 → 富农 → 中农。★ **贫农不在放贷序列**里（v1 明文：它没有余粮可贷）； 只有这三个槽位的行才可能是债权人。 */
  private static final List<String> LENDER_SLOT_PRIORITY = List.of("landlord", "rich", "middle");

  private EconomySettlement() {}

  /**
   * 结算**一天**（含"这一天若是周期末则追加周期结算"）。
   *
   * @param base 结算前的经济状态
   * @param day 推进到的世界日（1 tick = 1 天）；**只用于债务 id 的去重**（{@code debt-<day>-<seq>}），不参与任何公式
   * @return 结算后的新状态；{@code meta} 空 ⇒ 原样返回
   */
  public static EconomyData settle(EconomyData base, long day) {
    Objects.requireNonNull(base, "base");
    if (base.meta().isEmpty()) {
      return base; // 未激活：不做任何公式（§6.6）
    }
    EconomyMeta meta = base.meta().orElseThrow();
    long currentCycle = meta.lastClosedCycle().orElse(0L) + 1L; // 正在进行的周期序号
    long dueCycle = currentCycle + 1L; // §四：借粮的到期周期 = 当前周期 + 1

    // 工作副本：一律保序（绝不用 Map.copyOf——迭代序不是内容的纯函数）。
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>(base.industries());
    LinkedHashMap<ClassKey, ClassRow> rows = new LinkedHashMap<>(base.classes());
    LinkedHashMap<DebtId, Debt> debts = new LinkedHashMap<>(base.debts());
    LinkedHashMap<ClassKey, FlowRow> flows = new LinkedHashMap<>(); // 每日重建（流水结算后清零 = 本期发生额）

    // 逐行当日发生额（流水的事后组装）。
    LinkedHashMap<ClassKey, Long> consumedGrain = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> borrowing = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> income = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, Long> productionLoss = new LinkedHashMap<>();

    // ── 1~2. 消费 + 同格缺口（借粮 / 记缺口）────────────────────────────────────────────
    settleHexes(rows, debts, consumedGrain, borrowing, day, dueCycle);

    // ── 3~4. 进度 + 劳动投入；周期末追加收获与分配 ─────────────────────────────────────
    boolean anyCycleClosed = false;
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      Industry industry = industries.get(id);
      List<ClassKey> keys = classKeysOf(rows, id);
      long laborToday = 0L;
      for (ClassKey key : keys) {
        ClassRow row = rows.get(key);
        laborToday += row.laborMilli() * row.participationPerMille() / 1000L;
      }
      long cycledLabor = industry.cycleLaborMilli() + laborToday;
      long progressed = industry.progressDays() + 1L;
      long nextProgress = progressed;
      long nextCycleLabor = cycledLabor;
      if (progressed == industry.cycleDays()) {
        // ── 周期末：产出 / 生产消耗 / 制度分配 ──
        harvest(industry, rows, keys, cycledLabor, income, productionLoss);
        nextProgress = 0L;
        nextCycleLabor = 0L;
        anyCycleClosed = true;
      }
      industries.put(id, withProgressAndLabor(industry, nextProgress, nextCycleLabor));
    }

    // ── 流水：每行一条（本期发生额；税/利息 v1 恒 0）──────────────────────────────────
    for (ClassKey key : rows.keySet()) {
      long grainConsumed =
          consumedGrain.getOrDefault(key, 0L) + productionLoss.getOrDefault(key, 0L);
      Map<CommodityId, Long> consumed =
          grainConsumed > 0L ? Map.of(GRAIN, grainConsumed) : Map.of();
      long earned = income.getOrDefault(key, 0L);
      long borrowed = borrowing.getOrDefault(key, 0L);
      long netSurplus = earned - grainConsumed; // income − 消费 − 税(0) − 利息(0)
      flows.put(key, new FlowRow(key, earned, consumed, 0L, 0L, borrowed, 0L, netSurplus));
    }

    OptionalLong lastClosed =
        anyCycleClosed ? OptionalLong.of(currentCycle) : meta.lastClosedCycle();
    EconomyMeta nextMeta =
        new EconomyMeta(
            meta.mapId(),
            meta.activatedDay(),
            lastClosed,
            meta.rulesVersion(),
            meta.migrationSource());
    return new EconomyData(Optional.of(nextMeta), industries, rows, debts, flows);
  }

  // ── 消费 + 同格借粮 ─────────────────────────────────────────────────────────────────

  /**
   * 每个格一次：先各自吃自己的库存，库存不够的**在同格内借**（地主 → 富农 → 中农 的当日盈余），借到的记债。
   *
   * <p>★ **借到的粮当日即被吃掉** ⇒ 缺口行 {@code consumed} 记足额（借入量并入当日消费），行库存归零；放贷行的库存相应减少（债权体现在债务表）。
   */
  private static void settleHexes(
      LinkedHashMap<ClassKey, ClassRow> rows,
      LinkedHashMap<DebtId, Debt> debts,
      LinkedHashMap<ClassKey, Long> consumedGrain,
      LinkedHashMap<ClassKey, Long> borrowing,
      long day,
      long dueCycle) {
    Map<String, List<ClassKey>> hexToRows = rowsByHex(rows.keySet());
    int[] debtSeq = {0}; // 结算内的债务序号（与 day 一起保证 DebtId 唯一且可复现）
    for (Map.Entry<String, List<ClassKey>> hex : hexToRows.entrySet()) {
      List<ClassKey> keys = hex.getValue();
      LinkedHashMap<ClassKey, Long> deficit = new LinkedHashMap<>();
      // ① 各自消费：扣 min(库存, 需求)；差额入 deficit。
      //   ★ 需求的口径 = 人口 × 83（整数乘法，无除法残差）；自然需求字段（naturalNeeds）在 v1 人口不变时恒等于它，
      //     故"缺口 = naturalNeeds − 实得"直接由字段的差可读，不必另存一份。
      for (ClassKey key : keys) {
        ClassRow row = rows.get(key);
        long need = row.population() * DAILY_GRAIN_MILLI_PER_PERSON;
        long stock = grainOf(row);
        long eaten = Math.min(stock, need);
        rows.put(key, withGoodsGrain(row, stock - eaten));
        consumedGrain.put(key, eaten);
        if (need - eaten > 0L) {
          deficit.put(key, need - eaten);
        }
      }
      if (deficit.isEmpty()) {
        continue;
      }
      // ② 放贷序列：地主 → 富农 → 中农，可取"当日盈余"（消费后的余粮）；同档按产业 id 定序。
      List<ClassKey> lenders = new ArrayList<>();
      for (String slot : LENDER_SLOT_PRIORITY) {
        for (ClassKey key : keys) {
          if (key.slot().value().equals(slot) && grainOf(rows.get(key)) > 0L) {
            lenders.add(key);
          }
        }
      }
      // ③ 逐缺口行（槽位 id 序）借：借到多少记多少债；没人有粮 ⇒ 剩下的只留作未满足的自然需求。
      List<ClassKey> debtors = new ArrayList<>(deficit.keySet());
      debtors.sort(
          Comparator.comparing((ClassKey k) -> k.slot().value())
              .thenComparing(k -> k.industry().value()));
      for (ClassKey debtor : debtors) {
        long remaining = deficit.get(debtor);
        for (ClassKey lender : lenders) {
          if (remaining <= 0L) {
            break;
          }
          long available = grainOf(rows.get(lender));
          if (available <= 0L) {
            continue;
          }
          long lent = Math.min(remaining, available);
          rows.put(lender, withGoodsGrain(rows.get(lender), available - lent));
          DebtId debtId = new DebtId("debt-" + day + "-" + debtSeq[0]++);
          debts.put(
              debtId,
              new Debt(
                  debtId,
                  debtor,
                  lender,
                  Optional.of(GRAIN),
                  lent,
                  BORROW_RATE_PER_MILLE_PER_CYCLE,
                  dueCycle,
                  false));
          rows.put(debtor, withExtraDebt(rows.get(debtor), debtId));
          // 借到的粮当日吃掉 ⇒ 计入当日消费。
          consumedGrain.merge(debtor, lent, Long::sum);
          borrowing.merge(debtor, lent, Long::sum);
          remaining -= lent;
        }
        // remaining > 0 ⇒ 没人有粮：不造粮、不造债（缺口 = naturalNeeds − 实得，留在两处字段的差里）。
      }
    }
  }

  // ── 周期收获与制度分配 ─────────────────────────────────────────────────────────────

  /**
   * 周期末的产出、生产消耗与制度分配（§四 周期结算 1~5；税明确不做）。
   *
   * @param cycledLabor 本周期累计实际劳动（千分劳动·日）；`/cycleDays` 得**平均每日实际劳动**
   */
  private static void harvest(
      Industry industry,
      LinkedHashMap<ClassKey, ClassRow> rows,
      List<ClassKey> keys,
      long cycledLabor,
      LinkedHashMap<ClassKey, Long> income,
      LinkedHashMap<ClassKey, Long> productionLoss) {
    long totalLandMilliMu = 0L;
    long[] rowLand = new long[keys.size()];
    long[] rowLabor = new long[keys.size()];
    long totalLabor = 0L;
    for (int i = 0; i < keys.size(); i++) {
      ClassRow row = rows.get(keys.get(i));
      rowLand[i] = row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L);
      totalLandMilliMu += rowLand[i];
      rowLabor[i] = row.laborMilli() * row.participationPerMille() / 1000L;
      totalLabor += rowLabor[i];
    }
    long avgLaborMilli = cycledLabor / industry.cycleDays(); // 平均每日实际劳动（千分劳动）
    long availableMu = totalLandMilliMu / 1000L; // 千分亩 ⇒ 亩（向下取整）
    long ableMu = avgLaborMilli * LAND_MU_PER_LABOR / 1000L; // 劳动可经营亩数（向下取整）
    long actualMu = Math.min(availableMu, ableMu); // 劳动瓶颈：取小后向下取整到亩
    long perMu = industry.outputPerUnit().getOrDefault(GRAIN, 0L); // 粮/亩
    long gross = actualMu * perMu * MILLI_PER_GRAIN; // 毫粮（毛产出）
    long loss = gross * PRODUCTION_CONSUMPTION_PER_MILLE / 1000L; // 种子/牲畜/工具
    long net = gross - loss; // 剩余产出（待分配）

    AllocationRule rule = industry.allocation();
    if (!(rule instanceof AllocationRule.Split split)) {
      // v1 只结算 Split（小农/封建租佃/手工业）；资本主义 WageFirst 是 §五 的后续增量。
      throw new UnsupportedOperationException(
          "v1 的周期分配只支持 AllocationRule.Split，产业 " + industry.id() + " 是 " + rule);
    }
    long[] weights = new long[keys.size()];
    for (int i = 0; i < keys.size(); i++) {
      long meansPerMille = totalLandMilliMu == 0L ? 0L : rowLand[i] * 1000L / totalLandMilliMu;
      long laborPerMille = totalLabor == 0L ? 0L : rowLabor[i] * 1000L / totalLabor;
      weights[i] =
          (split.meansWeightPerMille() * meansPerMille
                  + split.laborWeightPerMille() * laborPerMille)
              / 1000L;
    }
    long[] netParts = allocate(net, weights);
    long[] lossParts = allocate(loss, weights);
    for (int i = 0; i < keys.size(); i++) {
      ClassKey key = keys.get(i);
      rows.put(key, withGoodsGrain(rows.get(key), grainOf(rows.get(key)) + netParts[i]));
      // 所得记**毛产出**（净得 + 其份额的生产消耗）⇒ 与 consumed 里的生产消耗配平（§6.1 的账要平）。
      income.merge(key, netParts[i] + lossParts[i], Long::sum);
      productionLoss.merge(key, lossParts[i], Long::sum);
    }
  }

  /**
   * **定点整数分配 + 残差按槽位 id 序分派**（§4 周期结算第 4 步）：{@code parts[i] = floor(total × weight[i] / 1000)}，余下的
   * {@code total − Σparts} 个单位按索引序（= 槽位 id 序，调用方已排序）轮转补齐 ⇒ **Σparts == total**。
   *
   * <p>★ 这条路是"Σ行得 = 剩余产出"的唯一保证：去掉残差补齐，Σ 会小于 total（权重之和因逐项取整可 &lt; 1000）。
   */
  static long[] allocate(long total, long[] weights) {
    long[] parts = new long[weights.length];
    long assigned = 0L;
    for (int i = 0; i < weights.length; i++) {
      parts[i] = total * weights[i] / 1000L;
      assigned += parts[i];
    }
    distributeResidue(parts, total - assigned);
    return parts;
  }

  /**
   * 把 {@code remainder} 个单位按**索引序轮转**补到 parts 上（每项先摊 {@code remainder/n}，前 {@code remainder%n} 项各多
   * 1）。
   */
  private static void distributeResidue(long[] parts, long remainder) {
    if (remainder < 0L) {
      throw new IllegalStateException("分配残差不得为负: " + remainder);
    }
    int n = parts.length;
    if (n == 0) {
      if (remainder != 0L) {
        throw new IllegalStateException("没有可承载的槽位，但残差 = " + remainder);
      }
      return;
    }
    long share = remainder / n;
    long extra = remainder % n;
    for (int i = 0; i < n; i++) {
      parts[i] += share + (i < extra ? 1L : 0L);
    }
  }

  // ── 分组与排序 ─────────────────────────────────────────────────────────────────────

  /** 该产业的阶层行键，**按槽位 id 字典序**（可复现；也是分配残差的"槽位 id 序"）。 */
  private static List<ClassKey> classKeysOf(Map<ClassKey, ClassRow> rows, IndustryId id) {
    List<ClassKey> keys = new ArrayList<>();
    for (ClassKey key : rows.keySet()) {
      if (key.industry().equals(id)) {
        keys.add(key);
      }
    }
    keys.sort(Comparator.comparing(key -> key.slot().value()));
    return keys;
  }

  /** 按格（{@link IndustryHexKeys} 的 {@code <q>_<r>}）分组，格的顺序与行序都显式排序（可复现）。 */
  private static Map<String, List<ClassKey>> rowsByHex(Iterable<ClassKey> keys) {
    Map<String, List<ClassKey>> byHex = new LinkedHashMap<>();
    for (ClassKey key : keys) {
      byHex
          .computeIfAbsent(
              IndustryHexKeys.hexKeyOf(key.industry()).orElse(key.industry().value()),
              ignored -> new ArrayList<>())
          .add(key);
    }
    LinkedHashMap<String, List<ClassKey>> sorted = new LinkedHashMap<>();
    List<String> hexKeys = new ArrayList<>(byHex.keySet());
    hexKeys.sort(Comparator.naturalOrder());
    for (String hexKey : hexKeys) {
      List<ClassKey> rows = byHex.get(hexKey);
      rows.sort(
          Comparator.comparing((ClassKey k) -> k.slot().value())
              .thenComparing(k -> k.industry().value()));
      sorted.put(hexKey, rows);
    }
    return sorted;
  }

  // ── 行与产业的不可变替换 ───────────────────────────────────────────────────────────

  private static long grainOf(ClassRow row) {
    return row.goods().getOrDefault(GRAIN, 0L);
  }

  /** 换粮库存（0 ⇒ 去掉该键，保持"空商品表"的纯形态）。 */
  private static ClassRow withGoodsGrain(ClassRow row, long grain) {
    Map<CommodityId, Long> goods = new LinkedHashMap<>(row.goods());
    if (grain <= 0L) {
      goods.remove(GRAIN);
    } else {
      goods.put(GRAIN, grain);
    }
    return new ClassRow(
        row.key(),
        row.population(),
        row.laborMilli(),
        row.participationPerMille(),
        row.meansOfProduction(),
        goods,
        row.money(),
        row.debts(),
        row.naturalNeeds(),
        row.effectiveDemand());
  }

  /** 追加一条债务引用（其余字段原样带过）。 */
  private static ClassRow withExtraDebt(ClassRow row, DebtId debtId) {
    List<DebtId> debts = new ArrayList<>(row.debts());
    debts.add(debtId);
    return new ClassRow(
        row.key(),
        row.population(),
        row.laborMilli(),
        row.participationPerMille(),
        row.meansOfProduction(),
        row.goods(),
        row.money(),
        debts,
        row.naturalNeeds(),
        row.effectiveDemand());
  }

  /** 换进度与周期劳动累计（其余字段原样带过）。 */
  private static Industry withProgressAndLabor(Industry industry, long progress, long cycleLabor) {
    return new Industry(
        industry.id(),
        industry.name(),
        industry.regime(),
        industry.cycleDays(),
        progress,
        industry.dailyInputPerUnit(),
        industry.dailyLaborPerUnit(),
        industry.outputPerUnit(),
        industry.slots(),
        industry.allocation(),
        cycleLabor);
  }
}
