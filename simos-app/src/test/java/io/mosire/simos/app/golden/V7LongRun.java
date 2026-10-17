package io.mosire.simos.app.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.SimulationState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ★★ <b>D-031 P1 §5 V7（P1 侧）：独立长模拟合理性</b>（2026-10-16 S2 段）。
 *
 * <p>★★ <b>它不是 JUnit 用例</b>：本类只有 {@code main}，由 {@code test-compile} 编进 test-classes，再用 {@code java
 * -cp} 直接跑（控制方 §3 S2：「长多 tick 走独立脚本……不塞进 JUnit」）。
 *
 * <pre>
 * 用法: java -cp &lt;test-classes:classes:deps&gt; io.mosire.simos.app.golden.V7LongRun &lt;days&gt; &lt;out.json&gt; &lt;storeRoot&gt;
 * </pre>
 *
 * <p>★ <b>固定种子</b>：世界来自确定性创世（{@code SmallWorld}/{@code EconomySeeder}，无随机源）；<b>自己的 store 根</b>
 * （调用方给的隔离目录，绝不用共享 {@code test-world/}）。长度 = 真实周期：调用方传的 {@code days} 必须 ≥ 本世界最长 {@code
 * cycleDays}（本世界 = 120 ⇒ 125 天，保证每个产业至少关一次账、30 天的那两个关 4 次）。
 *
 * <p>★★ <b>逐次输出</b>（每个结算日一行）：全账户逐商品库存合计、逐币种货币合计、本 tick 铸币增量、单元进度/劳动/投入合计、 <b>本 tick
 * 关账的单元清单</b>（含关账前该周期累计的劳动与投入）、<b>停产/约束拒绝清单</b>（关账但本周期零劳动或缺必需投入 ⇒ 具名）。
 *
 * <p>★★ <b>自动不变量</b>（每 tick 检查，违反即记进 {@code violations}，绝不静默）：① 一切库存/货币/进度/劳动非负； ② 货币守恒：{@code
 * ΔΣ(货币+冻结货币) == 本 tick 铸币增量}（铸币是唯一的外部注入）；③ 无劳动或缺原料<b>不得凭空关账</b>： 关账单元若其配方要求劳动（{@code laborPerUnit
 * > 0}）或某种周期投入（{@code cycleInputPerUnit} 逐值 > 0），则本周期该路 累计必须 > 0。
 */
public final class V7LongRun {

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  private V7LongRun() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.err.println("用法: V7LongRun <days> <out.json> <storeRoot>");
      System.exit(2);
    }
    int days = Integer.parseInt(args[0]);
    Path out = Path.of(args[1]).toAbsolutePath();
    Path store = Path.of(args[2]).toAbsolutePath();
    deleteRecursively(store);
    Files.createDirectories(out.getParent());

    ObjectNode root = JSON.createObjectNode();
    root.put("tree", "p1");
    root.put("scenario", "v7-long-run");
    root.put("worldKind", "small-world");
    root.put("seed", 0L);
    root.put("days", days);

    ArrayNode ticks = root.putArray("ticks");
    List<String> allViolations = new ArrayList<>();

    try (GovZ6WorldFixture.World world =
        GovZ6WorldFixture.smallWorldGenesis(store, "v7-long-run")) {
      Map<IndustryId, IndustryDefinitionView> definitions =
          productionOf(world.state()).industries();
      root.put(
          "maxCycleDays",
          definitions.values().stream().mapToLong(d -> d.cycleDays()).max().orElse(0L));
      root.put("industryCount", definitions.size());

      Reading prev = Reading.of(world.state());
      ticks.add(project(0L, prev, prev, List.of(), List.of()));

      // 关账前该周期的累计上界（逐 unit 取 running max；关账后清零重来）。
      Map<String, Long> cycleLaborMax = new LinkedHashMap<>();
      Map<String, Map<String, Long>> cycleInputMax = new LinkedHashMap<>();

      for (int day = 1; day <= days; day++) {
        world.advanceDays(1L);
        Reading cur = Reading.of(world.state());

        List<ObjectNode> harvests = new ArrayList<>();
        List<String> stops = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        for (Map.Entry<ProductionUnitId, ProductionProcess> entry :
            sortedUnits(world.state()).entrySet()) {
          String unitId = entry.getKey().value();
          ProductionProcess unit = entry.getValue();
          ProductionProcess before = prev.units.get(unitId);
          if (before == null) {
            continue;
          }
          cycleLaborMax.merge(unitId, unit.cycleLaborMilli(), Math::max);
          Map<String, Long> inputs =
              cycleInputMax.computeIfAbsent(unitId, key -> new LinkedHashMap<>());
          unit.cycleInputUsedMilli()
              .forEach((commodity, amount) -> inputs.merge(commodity.value(), amount, Math::max));

          boolean cycleClosed = before.progressDays() > 0L && unit.progressDays() == 0L;
          if (!cycleClosed) {
            continue;
          }
          IndustryDefinitionView definition = definitions.get(unit.industry());
          long laborInCycle = cycleLaborMax.getOrDefault(unitId, 0L);
          ObjectNode harvest = JSON.createObjectNode();
          harvest.put("unit", unitId);
          harvest.put("industry", unit.industry().value());
          harvest.put("cycleDays", definition == null ? -1L : definition.cycleDays());
          harvest.put("laborInCycle", laborInCycle);
          ObjectNode usedInputs = harvest.putObject("inputsInCycle");
          Map<String, Long> inputSnapshot = cycleInputMax.getOrDefault(unitId, Map.of());
          inputSnapshot.forEach(usedInputs::put);
          harvests.add(harvest);

          if (definition != null) {
            if (definition.laborPerUnit() > 0L && laborInCycle <= 0L) {
              String reason =
                  "STOP_NO_LABOR: unit="
                      + unitId
                      + " industry="
                      + unit.industry().value()
                      + " laborPerUnit="
                      + definition.laborPerUnit()
                      + " 但本周期累计劳动=0";
              stops.add(reason);
              violations.add(reason);
            }
            for (Map.Entry<io.mosire.simos.actor.api.asset.AssetKind, Map<CommodityId, Long>> line :
                definition.cycleInputPerUnit().entrySet()) {
              for (Map.Entry<CommodityId, Long> need : line.getValue().entrySet()) {
                if (need.getValue() == null || need.getValue() <= 0L) {
                  continue;
                }
                long used = inputSnapshot.getOrDefault(need.getKey().value(), 0L);
                if (used <= 0L) {
                  String reason =
                      "STOP_NO_INPUT: unit="
                          + unitId
                          + " industry="
                          + unit.industry().value()
                          + " commodity="
                          + need.getKey().value()
                          + " 每周期需 "
                          + need.getValue()
                          + " 但本周期实扣=0";
                  stops.add(reason);
                  violations.add(reason);
                }
              }
            }
          }
          cycleLaborMax.put(unitId, 0L);
          cycleInputMax.put(unitId, new LinkedHashMap<>());
        }

        // ① 非负
        violations.addAll(prev.negativeCheck(cur));
        // ② 货币守恒：ΔΣ(货币+冻结) == 本 tick 铸币增量
        violations.addAll(prev.moneyConservationCheck(cur));

        allViolations.addAll(violations);
        ticks.add(project(day, prev, cur, harvests, merge(stops, violations)));
        prev = cur;
      }
    }

    ObjectNode summary = root.putObject("summary");
    summary.put("days", days);
    summary.put("violations", allViolations.size());
    ArrayNode violationList = summary.putArray("violationList");
    allViolations.stream().distinct().limit(50).forEach(violationList::add);

    Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    System.out.println("V7 长模拟完成: days=" + days + " 违例=" + allViolations.size() + " → " + out);
    if (!allViolations.isEmpty()) {
      System.exit(1);
    }
  }

  // ── 逐 tick 投影 ────────────────────────────────────────────────────────────────────

  private static ObjectNode project(
      long day, Reading prev, Reading cur, List<ObjectNode> harvests, List<String> violations) {
    ObjectNode tick = JSON.createObjectNode();
    tick.put("day", day);
    ObjectNode goods = tick.putObject("totalGoods");
    cur.goods.forEach(goods::put);
    ObjectNode money = tick.putObject("totalMoney");
    cur.money.forEach(money::put);
    ObjectNode moneyDelta = tick.putObject("moneyDelta");
    for (String currency : new TreeSet<>(union(cur.money.keySet(), prev.money.keySet()))) {
      moneyDelta.put(
          currency, cur.money.getOrDefault(currency, 0L) - prev.money.getOrDefault(currency, 0L));
    }
    ObjectNode goodsDeltaNode = tick.putObject("goodsDelta");
    if (day > 0L) {
      for (String commodity : new TreeSet<>(union(cur.goods.keySet(), prev.goods.keySet()))) {
        goodsDeltaNode.put(
            commodity,
            cur.goods.getOrDefault(commodity, 0L) - prev.goods.getOrDefault(commodity, 0L));
      }
    }
    ObjectNode minted = tick.putObject("mintedDelta");
    for (String currency : new TreeSet<>(union(cur.minted.keySet(), prev.minted.keySet()))) {
      minted.put(
          currency, cur.minted.getOrDefault(currency, 0L) - prev.minted.getOrDefault(currency, 0L));
    }
    tick.put("unitCount", cur.units.size());
    tick.put("progressSum", cur.progressSum);
    tick.put("cycleLaborSum", cur.cycleLaborSum);
    tick.put("inputUsedSum", cur.inputUsedSum);
    tick.put("householdCount", cur.householdCount);
    tick.put("populationSum", cur.populationSum);
    tick.put("householdMoneySum", cur.householdMoneySum);
    ArrayNode harvestNodes = tick.putArray("cycleCloses");
    harvests.forEach(harvestNodes::add);
    ArrayNode violationNodes = tick.putArray("violations");
    violations.forEach(violationNodes::add);
    return tick;
  }

  private static List<String> merge(List<String> a, List<String> b) {
    List<String> merged = new ArrayList<>(a);
    merged.addAll(b);
    return merged;
  }

  private static List<String> union(Iterable<String> a, Iterable<String> b) {
    TreeSet<String> set = new TreeSet<>();
    a.forEach(set::add);
    b.forEach(set::add);
    return new ArrayList<>(set);
  }

  /** 一个 tick 的全量紧凑读数（含两条自动不变量的检查）。 */
  private static final class Reading {

    private final Map<String, Long> goods;
    private final Map<String, Long> money;
    private final Map<String, Long> minted;
    private final Map<String, ProductionProcess> units;
    private final long progressSum;
    private final long cycleLaborSum;
    private final long inputUsedSum;
    private final long householdCount;
    private final long populationSum;
    private final long householdMoneySum;

    private Reading(
        Map<String, Long> goods,
        Map<String, Long> money,
        Map<String, Long> minted,
        Map<String, ProductionProcess> units,
        long progressSum,
        long cycleLaborSum,
        long inputUsedSum,
        long householdCount,
        long populationSum,
        long householdMoneySum) {
      this.goods = goods;
      this.money = money;
      this.minted = minted;
      this.units = units;
      this.progressSum = progressSum;
      this.cycleLaborSum = cycleLaborSum;
      this.inputUsedSum = inputUsedSum;
      this.householdCount = householdCount;
      this.populationSum = populationSum;
      this.householdMoneySum = householdMoneySum;
    }

    static Reading of(SimulationState state) {
      ActorData actor = ((ActorSnapshot) state.module("actor").orElseThrow()).data();
      EconomyData economy = economyOf(state);
      Map<String, Long> goods = new TreeMap<>();
      Map<String, Long> money = new TreeMap<>();
      for (HouseholdInventory inventory : actor.accounts().values()) {
        inventory.balances().forEach((c, v) -> goods.merge(c.value(), v, Long::sum));
        inventory.frozenBalances().forEach((c, v) -> goods.merge(c.value(), v, Long::sum));
        inventory.money().forEach((c, v) -> money.merge(c.value(), v, Long::sum));
        inventory.frozenMoney().forEach((c, v) -> money.merge(c.value(), v, Long::sum));
      }
      Map<String, Long> minted = new TreeMap<>();
      for (MoneyIssuanceRecord record : economy.moneyIssuances().values()) {
        minted.merge(record.currency().value(), record.amount(), Long::sum);
      }
      Map<String, ProductionProcess> units = new LinkedHashMap<>();
      sortedUnits(state).forEach((id, unit) -> units.put(id.value(), unit));
      long progressSum = 0L;
      long cycleLaborSum = 0L;
      long inputUsedSum = 0L;
      for (ProductionProcess unit : units.values()) {
        progressSum += unit.progressDays();
        cycleLaborSum += unit.cycleLaborMilli();
        for (Long amount : unit.cycleInputUsedMilli().values()) {
          inputUsedSum += amount;
        }
      }
      long populationSum = 0L;
      long householdMoneySum = 0L;
      for (HouseholdEconomy row : economy.classes().values()) {
        populationSum += row.population();
        householdMoneySum += row.money();
      }
      return new Reading(
          goods,
          money,
          minted,
          units,
          progressSum,
          cycleLaborSum,
          inputUsedSum,
          economy.classes().size(),
          populationSum,
          householdMoneySum);
    }

    /** ① 非负。 */
    List<String> negativeCheck(Reading cur) {
      List<String> violations = new ArrayList<>();
      cur.goods.forEach(
          (commodity, amount) -> {
            if (amount < 0L) {
              violations.add("NEGATIVE_GOODS: " + commodity + "=" + amount);
            }
          });
      cur.money.forEach(
          (currency, amount) -> {
            if (amount < 0L) {
              violations.add("NEGATIVE_MONEY: " + currency + "=" + amount);
            }
          });
      cur.units.forEach(
          (unitId, unit) -> {
            if (unit.progressDays() < 0L) {
              violations.add("NEGATIVE_PROGRESS: " + unitId + "=" + unit.progressDays());
            }
            if (unit.cycleLaborMilli() < 0L) {
              violations.add("NEGATIVE_LABOR: " + unitId + "=" + unit.cycleLaborMilli());
            }
            unit.cycleInputUsedMilli()
                .forEach(
                    (commodity, amount) -> {
                      if (amount < 0L) {
                        violations.add(
                            "NEGATIVE_INPUT: " + unitId + "/" + commodity.value() + "=" + amount);
                      }
                    });
          });
      return violations;
    }

    /** ② 货币守恒：ΔΣ(货币+冻结) == 本 tick 铸币增量。 */
    List<String> moneyConservationCheck(Reading cur) {
      List<String> violations = new ArrayList<>();
      for (String currency : union(cur.money.keySet(), money.keySet())) {
        long delta = cur.money.getOrDefault(currency, 0L) - money.getOrDefault(currency, 0L);
        long mintedDelta =
            cur.minted.getOrDefault(currency, 0L) - minted.getOrDefault(currency, 0L);
        if (delta != mintedDelta) {
          violations.add(
              "MONEY_NOT_CONSERVED: "
                  + currency
                  + " ΔΣ="
                  + delta
                  + " 铸币增量="
                  + mintedDelta
                  + "（差 "
                  + (delta - mintedDelta)
                  + "）");
        }
      }
      return violations;
    }
  }

  // ── 读口 ────────────────────────────────────────────────────────────────────────────

  private static Map<ProductionUnitId, ProductionProcess> sortedUnits(SimulationState state) {
    Map<ProductionUnitId, ProductionProcess> sorted = new LinkedHashMap<>();
    // ★★ D-032 T1：实例读数改读 production 切片。
    productionOf(state).instances().entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey().value()))
        .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
    return sorted;
  }

  private static ProductionData productionOf(SimulationState state) {
    return ((ProductionSnapshot) state.module("production").orElseThrow()).state();
  }

  private static EconomyData economyOf(SimulationState state) {
    return ((EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
