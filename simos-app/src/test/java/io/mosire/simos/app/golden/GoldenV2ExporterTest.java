package io.mosire.simos.app.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.tools.write.RegionClearDataTool;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.SimulationState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>D-031 P1 §5 V2（P1 侧）：黄金业务投影导出器</b>（2026-10-16 S2 段；本段唯一写者）。
 *
 * <p>★★ <b>自包含</b>：不 import 基线树任何东西、不读基线树任何文件；只把本树（P1 新 namespace）跑出来的业务投影写成 JSON。
 * 基线树里有一份**同形但独立重写**的导出器（{@code
 * d031-p1-baseline/simos-app/src/test/java/io/mosire/simos/app/golden/}）， 两者不共用代码 —— 这是控制方
 * §7「两棵树各写一个自包含导出器（不共用代码、不互相 import）」的要求。
 *
 * <p>★ <b>场景（短场景，JUnit；§7 允许）</b>：同一世界（{@code small-world} 真创世）、同一种子（{@code 0}）、同一业务操作
 * （<b>只推进天数</b>，不下任何 GM 命令）⇒ 导出创世（day 0）与随后 3 天每天一份全量业务投影。 比较用**独立脚本**（python）做递归排序 + 逐值比，见 {@code
 * test/compare-golden.py}。
 *
 * <p>★★ <b>被导出的读数一律取"状态里的权威字段"</b>（不反算、不重实现公式）：{@code economy} 片的家户行/流水/单元/份额/价表/ 铸币发行/债务 + {@code
 * production} 片的定义 + {@code actor} 片的家户账（商品库存与货币余额）。
 */
class GoldenV2ExporterTest {

  private static final ObjectMapper JSON = SimosObjectMapper.create();

  /** 场景参数（两棵树必须逐字相同）。 */
  private static final String SCENARIO = "golden-v2";

  private static final String WORLD_KIND = "small-world";

  private static final long SEED = 0L;

  private static final int TICKS = 3;

  /** 导出落点（模块目录下；两棵树各自的 target，互不覆盖）。 */
  private static final Path OUT_DIR = Path.of("target", "golden-v2").toAbsolutePath();

  @Test
  void exportTheShortScenarioBusinessProjection() throws IOException {
    Path store = OUT_DIR.resolve("store-p1");
    deleteRecursively(store);
    Files.createDirectories(OUT_DIR);

    ObjectNode root = JSON.createObjectNode();
    root.put("tree", "p1");
    root.put("scenario", SCENARIO);
    root.put("worldKind", WORLD_KIND);
    root.put("seed", SEED);
    root.put("ticks", TICKS);

    ArrayNode ticks = root.putArray("ticks");
    ObjectNode regionClear = root.putObject("regionClear");

    try (GovZ6WorldFixture.World world = GovZ6WorldFixture.smallWorldGenesis(store, SCENARIO)) {
      SimulationState genesis = world.state();
      root.set("definitions", definitionsProjection(genesis));
      ticks.add(tickProjection(0L, genesis));

      for (int day = 1; day <= TICKS; day++) {
        world.advanceDays(1L);
        ticks.add(tickProjection(day, world.state()));
      }

      // ★ V2-c：同一世界、同一区域跑同一个工具，看两棵树是否同判（P0 侧同形导出器跑同样的两段）。
      regionClear.set("small-world", regionClearProbe(world, "small-world"));
      regionClear.set("capital-province", regionClearProbe(world, "capital-province"));
    }

    root.set("unavailableReadings", unavailableReadings());

    Path out = OUT_DIR.resolve("p1.json");
    Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    assertThat(Files.size(out)).as("导出必须非空: %s", out).isGreaterThan(1_000L);
  }

  // ── 定义投影（P1：权威在 production 片）──────────────────────────────────────────────

  private static ObjectNode definitionsProjection(SimulationState state) {
    ProductionData production = productionOf(state);
    ObjectNode definitions = JSON.createObjectNode();

    ObjectNode industries = definitions.putObject("industries");
    for (Map.Entry<IndustryId, IndustryDefinitionView> entry :
        sortedEntries(production.industries(), k -> k.value())) {
      IndustryDefinitionView industry = entry.getValue();
      ObjectNode node = industries.putObject(entry.getKey().value());
      node.put("name", industry.name());
      node.put("regime", industry.regime().value());
      node.put("skill", industry.skill().map(Object::toString).orElse(""));
      node.put("cycleDays", industry.cycleDays());
      node.set("capacityPerUnit", assetMap(industry.capacityPerUnit()));
      node.set("dailyInputPerUnit", assetCommodityMap(industry.dailyInputPerUnit()));
      node.put("dailyLaborPerUnit", industry.dailyLaborPerUnit());
      node.put("laborPerUnit", industry.laborPerUnit());
      node.set("outputPerUnit", commodityMap(industry.outputPerUnit()));
      node.set("cycleInputPerUnit", assetCommodityMap(industry.cycleInputPerUnit()));
      ArrayNode slots = node.putArray("slots");
      industry
          .slots()
          .forEach(
              slot -> {
                ObjectNode slotNode = slots.addObject();
                slotNode.put("id", slot.id().value());
                slotNode.put("name", slot.name());
                slotNode.put("laborParticipationPerMille", slot.laborParticipationPerMille());
              });
      node.set("allocation", JSON.valueToTree(industry.allocation()));
      node.set("moneyOutputPerUnit", currencyMap(industry.moneyOutputPerUnit().perUnit()));
    }

    ObjectNode modes = definitions.putObject("modes");
    for (Map.Entry<ProductionModeId, ProductionModeDefinitionView> entry :
        sortedEntries(production.modes(), k -> k.value())) {
      ProductionModeDefinitionView mode = entry.getValue();
      ObjectNode node = modes.putObject(entry.getKey().value());
      node.put("name", mode.name());
      node.put("version", mode.version());
      node.put("classStructureId", mode.classStructureId().value());
    }
    return definitions;
  }

  // ── 逐 tick 业务投影 ────────────────────────────────────────────────────────────────

  private static ObjectNode tickProjection(long day, SimulationState state) {
    ObjectNode tick = JSON.createObjectNode();
    tick.put("day", day);
    tick.put("timestamp", state.meta().timestamp().tick());
    EconomyData economy = economyOf(state);
    // ★★ D-032 T1：`units` / `productionEfficiency` / `outputQuantityOverrides` 三条读数已按 B2b（裁定六）与
    //   B3/B5（效率表、产出覆盖的定义权威）迁到 production ⇒ 投影多接一份 production 切片，
    //   三个读数点逐字平移到 production 侧（判据/键名/值域一字未改）。
    tick.set("economy", economyProjection(economy, productionOf(state)));
    tick.set("actor", actorProjection(state));
    return tick;
  }

  private static ObjectNode economyProjection(EconomyData economy, ProductionData production) {
    ObjectNode node = JSON.createObjectNode();
    node.put(
        "lastClosedCycle", economy.meta().map(m -> m.lastClosedCycle().orElse(-1L)).orElse(-1L));
    node.put("rulesVersion", economy.meta().map(m -> m.rulesVersion()).orElse(""));

    ObjectNode households = node.putObject("households");
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry :
        sortedEntries(economy.classes(), k -> k.value())) {
      HouseholdEconomy row = entry.getValue();
      ObjectNode hh = households.putObject(entry.getKey().value());
      hh.put("population", row.population());
      hh.put("laborMilli", row.laborMilli());
      hh.put("participationPerMille", row.participationPerMille());
      hh.put("money", row.money());
      hh.put("cycleNaturalNeedMilli", row.cycleNaturalNeedMilli());
      hh.set("naturalNeeds", commodityMap(row.naturalNeeds()));
      hh.set("effectiveDemand", commodityMap(row.effectiveDemand()));
    }

    ObjectNode flows = node.putObject("flows");
    for (Map.Entry<HouseholdId, FlowRow> entry : sortedEntries(economy.flows(), k -> k.value())) {
      FlowRow flow = entry.getValue();
      ObjectNode row = flows.putObject(entry.getKey().value());
      row.set("income", commodityMap(flow.income()));
      row.set("consumed", commodityMap(flow.consumed()));
      row.set("unmetNeed", commodityMap(flow.unmetNeed()));
      row.put("taxPaid", flow.taxPaid());
      row.put("interestDue", flow.interestDue());
      row.put("newBorrowing", flow.newBorrowing());
      row.put("repaid", flow.repaid());
      row.put("netSurplus", flow.netSurplus());
      row.put("deaths", flow.deaths());
      row.put("births", flow.births());
      row.set("repaidMoney", currencyMap(flow.repaidMoney()));
    }

    ObjectNode units = node.putObject("units");
    for (Map.Entry<io.mosire.simos.economy.api.id.ProductionUnitId, ProductionProcess> entry :
        sortedEntries(production.instances(), k -> k.value())) {
      ProductionProcess unit = entry.getValue();
      ObjectNode row = units.putObject(entry.getKey().value());
      row.put("industry", unit.industry().value());
      row.put("operator", unit.operator().id());
      row.put("progressDays", unit.progressDays());
      row.put("cycleLaborMilli", unit.cycleLaborMilli());
      row.set("cycleInputUsedMilli", commodityMap(unit.cycleInputUsedMilli()));
    }

    ObjectNode shares = node.putObject("assetShares");
    for (Map.Entry<io.mosire.simos.economy.api.id.AssetShareId, OwnershipStake> entry :
        sortedEntries(economy.assetShares(), k -> k.value())) {
      OwnershipStake stake = entry.getValue();
      ObjectNode row = shares.putObject(entry.getKey().value());
      row.put("industry", stake.industry().value());
      row.put("asset", stake.asset().name());
      row.put("owner", stake.owner().id());
      row.put("operator", stake.operator().id());
      row.put("quantity", stake.quantity());
      row.put("rightKind", stake.kind().name());
    }

    ObjectNode markets = node.putObject("markets");
    for (Map.Entry<io.mosire.simos.economy.api.id.MarketZoneId, MarketTable> entry :
        sortedEntries(economy.marketTables(), k -> k.value())) {
      MarketTable table = entry.getValue();
      ObjectNode row = markets.putObject(entry.getKey().value());
      row.put("numeraire", table.numeraire().value());
      row.put("loanRatePerMille", table.loanRatePerMille());
      row.put("loanRateAlphaPerMille", table.loanRateAlphaPerMille());
      row.set("prices", commodityMap(table.prices()));
      List<String> hexes = new ArrayList<>();
      table.hexes().forEach(hex -> hexes.add(hex.q() + "_" + hex.r()));
      hexes.sort(Comparator.naturalOrder());
      ArrayNode hexNodes = row.putArray("hexes");
      hexes.forEach(hexNodes::add);
    }

    ObjectNode allocations = node.putObject("allocations");
    for (Map.Entry<
            io.mosire.simos.economy.api.id.LaborAllocationId,
            io.mosire.simos.economy.api.labor.HouseholdLaborCommitment>
        entry : sortedEntries(economy.allocations(), k -> k.value())) {
      var commitment = entry.getValue();
      ObjectNode row = allocations.putObject(entry.getKey().value());
      row.put("household", commitment.household().value());
      row.put("actor", commitment.actor().id());
      row.put("activity", commitment.activity());
      row.put("laborMilli", commitment.laborMilli());
      row.put("period", commitment.period());
      row.put("kind", commitment.kind().name());
    }

    ObjectNode efficiency = node.putObject("productionEfficiency");
    for (Map.Entry<
            io.mosire.simos.economy.api.id.ProductionUnitId,
            io.mosire.simos.production.contract.ProductionEfficiencyState>
        entry : sortedEntries(production.efficiency(), k -> k.value())) {
      var state = entry.getValue();
      ObjectNode row = efficiency.putObject(entry.getKey().value());
      // ★★ D-032 T1：效率书已按 D-032 迁入 production（`production.contract.ProductionEfficiencyState`
      //   = `PerCycleCarry` 三段余数）。★ 逐键对照（不是"改数字"，是**换权威 + 换分解**）：
      //   ① `laborDayRemainderMilli` / `laborScaleRemainderMilli`：同名逐值保留（读 `carry()`）；
      //   ② `scaleRemainderMilli` → `carry().efficiencyRemainderMilli()`：**同一件事改名**（新公式把
      //      "规模余数"叫效率倍率乘算余数，模仍是 1000‰；见 `PerCycleCarry.EFFICIENCY_REMAINDER_MODULUS`）；
      //   ③ `cycleModifierSumPerMille` / `modifierRemainderMilli` 两键**删除**：其被测能力 = 文化/天赋**生产
      //      修正注入链**，已按 D2「直接删」整体退役（`ProductionModifier` 注入面与 `PRODUCTION_MODIFIER_INJECTED`
      //      日志一并消失）⇒ 新状态里没有可读的真值；写 0 会把"能力已不存在"说成"本周期修正恰为 0"。
      row.put("laborDayRemainderMilli", state.carry().laborDayRemainderMilli());
      row.put("laborScaleRemainderMilli", state.carry().laborScaleRemainderMilli());
      row.put("scaleRemainderMilli", state.carry().efficiencyRemainderMilli());
    }

    ObjectNode overrides = node.putObject("outputQuantityOverrides");
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry :
        sortedEntries(production.outputQuantityOverrides(), k -> k.value())) {
      overrides.set(entry.getKey().value(), commodityMap(entry.getValue()));
    }

    ObjectNode minting = node.putObject("minting");
    minting.put("count", economy.moneyIssuances().size());
    long mintedTotal = 0L;
    ObjectNode records = minting.putObject("records");
    for (Map.Entry<io.mosire.simos.economy.api.id.MoneyIssuanceId, MoneyIssuanceRecord> entry :
        sortedEntries(economy.moneyIssuances(), k -> k.value())) {
      MoneyIssuanceRecord record = entry.getValue();
      ObjectNode row = records.putObject(entry.getKey().value());
      row.put("government", record.governmentId().value());
      row.put("day", record.day());
      row.put("currency", record.currency().value());
      row.put("amount", record.amount());
      row.put("kind", record.kind().name());
      mintedTotal += record.amount();
    }
    minting.put("amountTotal", mintedTotal);

    ObjectNode debts = node.putObject("debts");
    debts.put("count", economy.debtContracts().size());
    long principalTotal = 0L;
    ObjectNode contracts = debts.putObject("contracts");
    for (Map.Entry<io.mosire.simos.economy.api.id.DebtContractId, DebtContract> entry :
        sortedEntries(economy.debtContracts(), k -> k.value())) {
      DebtContract debt = entry.getValue();
      ObjectNode row = contracts.putObject(entry.getKey().value());
      row.put("debtor", debt.debtor().value());
      row.put("creditor", debt.creditor().value());
      row.put("unit", debt.unit().key());
      row.put("principal", debt.principal());
      row.put("status", debt.status().name());
      principalTotal += debt.principal();
    }
    debts.put("principalTotal", principalTotal);
    return node;
  }

  private static ObjectNode actorProjection(SimulationState state) {
    ObjectNode node = JSON.createObjectNode();
    ActorData actor = ((ActorSnapshot) state.module("actor").orElseThrow()).data();
    ObjectNode accounts = node.putObject("accounts");
    for (Map.Entry<HouseholdAccountKey, HouseholdInventory> entry :
        sortedEntries(actor.accounts(), k -> k.household().value())) {
      HouseholdInventory inventory = entry.getValue();
      ObjectNode row = accounts.putObject(entry.getKey().household().value());
      row.set("balances", commodityMap(inventory.balances()));
      row.set("money", currencyMap(inventory.money()));
      row.set("frozenBalances", commodityMap(inventory.frozenBalances()));
      row.set("frozenMoney", currencyMap(inventory.frozenMoney()));
    }
    return node;
  }

  // ── V2-c：同一区域清空探针（两棵树同形）──────────────────────────────────────────────

  private ObjectNode regionClearProbe(GovZ6WorldFixture.World world, String regionId) {
    ObjectNode probe = JSON.createObjectNode();
    long headBefore = world.head();
    probe.put("headBefore", headBefore);
    try {
      ToolResult preview = runRegionClear(world, regionId, true, null);
      ObjectNode previewNode = probe.putObject("preview");
      previewNode.put("ok", preview.success());
      previewNode.put("message", preview.message());
      ToolResult applied = runRegionClear(world, regionId, false, headBefore);
      ObjectNode applyNode = probe.putObject("apply");
      applyNode.put("ok", applied.success());
      applyNode.put("message", applied.message());
    } catch (RuntimeException e) {
      probe.put("exception", e.getClass().getName() + ": " + e.getMessage());
    }
    probe.put("headAfter", world.head());
    return probe;
  }

  /** 经真 GM 工具链执行；上下文由本类自建（不依赖包内可见的 {@code Shell.gmCaller()}）。 */
  private static ToolResult runRegionClear(
      GovZ6WorldFixture.World world, String regionId, boolean preview, Long expectedRevision) {
    Shell shell = world.shell();
    Map<String, Object> arguments = new java.util.LinkedHashMap<>();
    arguments.put("regionId", regionId);
    arguments.put("reason", "S2 golden V2-c probe");
    arguments.put("preview", preview);
    if (expectedRevision != null) {
      arguments.put("expectedRevision", expectedRevision);
    }
    ToolContext context =
        new ToolContext(
            AccessToken.SYSTEM,
            AgentPermissionSet.system(),
            Map.of(),
            arguments,
            AgentIdentity.external());
    return shell
        .gmToolAuthorizer()
        .execute(shell.toolRegistry(), RegionClearDataTool.NAME, context);
  }

  // ── 工具 ────────────────────────────────────────────────────────────────────────────

  /** 按稳定 key 字符串排序的条目表（键类型不实现 Comparable ⇒ 不用 TreeMap）。 */
  private static <K, V> List<Map.Entry<K, V>> sortedEntries(
      Map<K, V> map, java.util.function.Function<K, String> keyOf) {
    List<Map.Entry<K, V>> entries = new ArrayList<>(map.entrySet());
    entries.sort(Comparator.comparing(entry -> keyOf.apply(entry.getKey())));
    return entries;
  }

  private static ProductionData productionOf(SimulationState state) {
    return ((ProductionSnapshot) state.module("production").orElseThrow()).state();
  }

  private static EconomyData economyOf(SimulationState state) {
    return ((EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  private static ObjectNode commodityMap(Map<CommodityId, Long> source) {
    ObjectNode node = JSON.createObjectNode();
    for (Map.Entry<CommodityId, Long> entry : sortedEntries(source, k -> k.value())) {
      node.put(entry.getKey().value(), entry.getValue());
    }
    return node;
  }

  private static ObjectNode currencyMap(Map<CurrencyId, Long> source) {
    ObjectNode node = JSON.createObjectNode();
    for (Map.Entry<CurrencyId, Long> entry : sortedEntries(source, k -> k.value())) {
      node.put(entry.getKey().value(), entry.getValue());
    }
    return node;
  }

  private static ObjectNode assetMap(Map<io.mosire.simos.actor.api.asset.AssetKind, Long> source) {
    ObjectNode node = JSON.createObjectNode();
    for (Map.Entry<io.mosire.simos.actor.api.asset.AssetKind, Long> entry :
        sortedEntries(source, k -> k.name())) {
      node.put(entry.getKey().name(), entry.getValue());
    }
    return node;
  }

  private static ObjectNode assetCommodityMap(
      Map<io.mosire.simos.actor.api.asset.AssetKind, Map<CommodityId, Long>> source) {
    ObjectNode node = JSON.createObjectNode();
    for (Map.Entry<io.mosire.simos.actor.api.asset.AssetKind, Map<CommodityId, Long>> entry :
        sortedEntries(source, k -> k.name())) {
      node.set(entry.getKey().name(), commodityMap(entry.getValue()));
    }
    return node;
  }

  /** ★ 逐条列明 §7 要求但在**状态里没有权威读数**的字段（两棵树都必须诚实列出同一份）。 */
  private static ArrayNode unavailableReadings() {
    ArrayNode list = JSON.createArrayNode();
    list.add(
        "本 tick 投入/产出/损耗/转移/欠款明细：唯一权威是结算期瞬态的 ProductionLedger，"
            + "不落持久状态 ⇒ 本投影用【状态里能读到的等价读数】替代：unit.progressDays/cycleLaborMilli/"
            + "cycleInputUsedMilli（生产与劳动）+ 家户流水 income/consumed/unmetNeed（分配）+ actor 账余额（库存/货币）。");
    list.add("预期收益（ExpectedProfitBook.Prospect）：纯函数、不入状态 ⇒ 无权威读数（本段不冒充）。");
    list.add("本 tick 的约束拒绝条目与停产原因：结算期日志/ledger 瞬态、不入状态 ⇒ 无权威读数（V7 用独立脚本另行按 ledger 输出）。");
    list.add("skill 键：P0 的 Industry 定义里没有该字段（P0 把它放在独立的 ProductionSkillCatalog）⇒ P1 有、P0 无对照。");
    return list;
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
      for (Path path : paths) {
        Files.deleteIfExists(path);
      }
    }
  }
}
