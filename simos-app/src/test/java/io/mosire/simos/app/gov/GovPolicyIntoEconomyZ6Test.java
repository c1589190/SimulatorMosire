package io.mosire.simos.app.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.time.PortRegimeBridge;
import io.mosire.simos.app.time.PortRegimeBridge.PortRegimeDay;
import io.mosire.simos.app.time.ProcurementPriorityBridge;
import io.mosire.simos.app.time.ProcurementPriorityBridge.ProcurementPriorityDay;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.PortRule;
import io.mosire.simos.economy.api.market.PortTaxRule;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.govpolicy.GovAdministrationPlan;
import io.mosire.simos.economy.govpolicy.GovBudgetPolicy;
import io.mosire.simos.economy.govpolicy.GovPortPolicy;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.spi.EconomySetGovernmentPolicyHandler;
import io.mosire.simos.economy.time.PortEnforcementInput;
import io.mosire.simos.economy.time.ProcurementPriorityInput;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>追加裁定 12（2026-10-12 §21.9）：三类政府政策搬进 economy 之后的组合根验收</b>。
 *
 * <p><b>判据来源 = 设计书 §21.5 逐条</b>（不是按代码反推）：
 *
 * <ol>
 *   <li><b>正向往返</b>：设一条口岸规则 → 推进 → 重启（新 Shell 重读 store）→ 规则仍在、读数一致；
 *   <li><b>缺省中性（负向）</b>：没设过政策的世界，口岸面必须**逐值退回** {@code PortEnforcementInput.none()}， 优先级面不注入 ——
 *       不是"静默收 0 / 静默封死"；
 *   <li><b>非法政策 fail-closed（负向）</b>：负限制 / 未知商品 / 未知币种 / 未登记政府 ⇒ 具名拒 + <b>零 revision</b>；
 *   <li><b>权限（负向）</b>：本命令标 {@code GmOnlyCommand}，决策人的<b>令</b>与 <b>RegisterEffect</b> 两条路径都不放大 （决策人
 *       catalog 那一面见 {@code
 *       GovToolsZ6Test.gmOnlyGovCommandsAreMarkedAndInvisibleInDecisionCatalog}）；
 *   <li><b>GOV 删除收益</b>：政府记录没了 ⇒ 规则随之消失、无悬空键、别的政府不受影响；
 *   <li><b>两桥一致</b>：{@link PortRegimeBridge} 与 {@link ProcurementPriorityBridge}
 *       读<b>同一份</b>规则（同一政府记录）， 交叉改动只动各自那一面；
 * </ol>
 *
 * <p>★ 另实测钉死实现方点名的一条等价性（§21.5 判据 6 的邻域）：多政府世界里 {@link ProcurementPriorityBridge} 的<b>遍历序</b> —— 旧序
 * = GOV 单位 id 升序，新序 = {@code GovernmentId} 升序；同前缀 ⇒ 两者同向。 这里用 ≥3 个政府（含会被字典序暴露差异的 id
 * 构造）实测，而不是只看静态论证。
 */
class GovPolicyIntoEconomyZ6Test {

  private static final UnitId CENTRAL = UnitId.parse("gov-central");
  private static final UnitId PROVINCE = UnitId.parse("gov-province");
  private static final GovernmentId WORLD_SILVER = new GovernmentId("world-silver");

  @TempDir Path tempDir;

  // ── 判据 1：正向往返（设 → 推进 → 重启 → 规则仍在、读数一致）────────────────────────

  @Test
  void portRuleSetThroughTheCommandSurvivesAdvanceAndShellRestartWithIdenticalReadings() {
    Path store = tempDir.resolve("policy-round-trip");
    ShellConfig config = configFor(store);
    long head;
    GovPortPolicy provincePortBefore;
    PortRegimeDay portBefore;
    ProcurementPriorityDay priorityBefore;
    try (Harness harness = genesisWithDecisionMakers(config, "policy-round-trip")) {
      head = harness.head();
      assertThat(submit(harness.shell(), head, portPayload(PROVINCE, true)))
          .as("GM 命令面写口岸政策（省 = 测试市场区那格的管辖政府）")
          .isInstanceOf(CommandResult.Committed.class);
      assertThat(harness.head()).as("一条命令 = 恰一条 revision").isEqualTo(head + 1L);
      assertThat(submit(harness.shell(), head + 1L, portPayload(CENTRAL, true)))
          .as("第二条命令给另一个政府（各自一条 revision）")
          .isInstanceOf(CommandResult.Committed.class);
      assertThat(harness.head()).isEqualTo(head + 2L);

      harness.advance(0L, 1L);
      head = harness.head();
      SimulationState state = harness.state();
      provincePortBefore = governmentOf(state, PROVINCE).portPolicy();
      assertThat(provincePortBefore.controlsMarket()).as("规则真的落进 EconomyData.governments").isTrue();
      portBefore = portDay(state);
      priorityBefore = procurementDay(state);
      assertThat(portBefore.contacts()).as("图里必须有口岸触点（否则下面的读数无判别力）").isPositive();
      assertThat(portBefore.restrictedClasses()).as("设过的规则真的被读到").isPositive();
      assertThat(priorityBefore.controllingGovernments()).as("两个 GOV 都要求管控市场").isEqualTo(2);
    }

    // ★ 重启：新 Shell 读同一个 store（走 checkpoint/快照解码那条物化路径）。
    try (Shell reopened = Shell.start(config)) {
      SimulationState reread = reopened.coreSimos().replay(GovZ6WorldFixture.ref(head));

      assertThat(governmentOf(reread, PROVINCE).portPolicy())
          .as("重启后规则仍在（逐值）")
          .isEqualTo(provincePortBefore);
      assertThat(portDay(reread)).as("重启后口岸读数逐值一致").isEqualTo(portBefore);
      assertThat(procurementDay(reread)).as("重启后优先级读数逐值一致").isEqualTo(priorityBefore);
    }
  }

  // ── 判据 2：缺省中性（负向；"静默付 0"的反面）────────────────────────────────────────

  @Test
  void neutralPoliciesInjectNothingAndAreValueIdenticalToTheGenesisWorld() {
    SimulationState base = withSingleHexMarketZone(GovZ6WorldFixture.smallWorldState("Map1"));
    EconomyData genesis = GovZ6WorldFixture.economySlice(base);
    EconomyData resetToNeutral = withNeutralPolicies(genesis);

    PortRegimeDay genesisPort = portDay(base);
    PortRegimeDay neutralPort = portDay(base, resetToNeutral);
    assertThat(neutralPort).as("三类政策全 = 中性 ⇒ 口岸读数与创世世界逐值相同").isEqualTo(genesisPort);
    assertThat(neutralPort.active()).as("没设过规则 ⇒ 不注入管制力").isFalse();
    assertThat(neutralPort.input())
        .as("缺省 = PortEnforcementInput.none()（完全开放，不是静默封死）")
        .isEqualTo(PortEnforcementInput.none());
    assertThat(neutralPort.regimes()).isEmpty();
    assertThat(neutralPort.restrictedClasses()).isZero();

    ProcurementPriorityDay genesisPriority = procurementDay(base);
    ProcurementPriorityDay neutralPriority = procurementDay(base, resetToNeutral);
    assertThat(neutralPriority).as("优先级读数同样逐值退回").isEqualTo(genesisPriority);
    assertThat(neutralPriority.active()).as("没人要求管控市场 ⇒ 不注入优先级").isFalse();
    assertThat(neutralPriority.input()).isEqualTo(ProcurementPriorityInput.none());
    assertThat(neutralPriority.controllingGovernments()).isZero();
  }

  // ── 判据 3：非法政策 fail-closed + 零 revision（shell 边界实测）──────────────────────

  @Test
  void illegalPolicyIsNamedRejectedAtTheShellBoundaryWithZeroRevision() {
    Path store = tempDir.resolve("policy-illegal");
    try (Harness harness = genesisWithDecisionMakers(configFor(store), "policy-illegal")) {
      long head = harness.head();
      Map<String, String> illegal = new LinkedHashMap<>();
      illegal.put(
          portPayload(CENTRAL, true)
              .replace("\"entryRestrictionPerMille\":400", "\"entryRestrictionPerMille\":-1"),
          "不得为负");
      illegal.put(portPayload(CENTRAL, true).replace("\"grain\"", "\"unobtainium\""), "未知商品");
      illegal.put(portPayload(CENTRAL, true).replace("\"silver\"", "\"ghost-coin\""), "未知币种");
      illegal.put(
          "{\"governmentId\":\"gov-unit-gov-missing\",\"policies\":{\"port\":{}}}",
          "government-missing");

      for (Map.Entry<String, String> entry : illegal.entrySet()) {
        CommandResult result = submit(harness.shell(), head, entry.getKey());
        assertThat(result)
            .as("非法政策必须被拒: %s", entry.getValue())
            .isInstanceOf(CommandResult.Rejected.class);
        assertThat(((CommandResult.Rejected) result).reason())
            .as("拒因必须具名: %s", entry.getValue())
            .contains(entry.getValue());
        assertThat(harness.head())
            .as("被拒不推 revision（零 revision）: %s", entry.getValue())
            .isEqualTo(head);
      }
    }
  }

  // ── 判据 4：权限（负向）—— 令 / RegisterEffect 两条路径不放大 ────────────────────────

  @Test
  void gmOnlyMarkerAndBothDecisionPathsRefuseThePolicyWrite() {
    assertThat(new EconomySetGovernmentPolicyHandler())
        .as("标 GmOnlyCommand 是「三条路径都不放大」的共同依据（决策人 catalog 一面见 GovToolsZ6Test）")
        .isInstanceOf(GmOnlyCommand.class);

    Path store = tempDir.resolve("policy-permission");
    try (Harness harness = genesisWithDecisionMakers(configFor(store), "policy-permission")) {
      Shell shell = harness.shell();
      SimulationState state = harness.state();
      long head = harness.head();
      String dmId = firstDecisionMakerId(state);
      long worldTick = state.meta().timestamp().tick();

      // ① 令：真 Shell 注册的 IssueDirectiveHandler 带「注册面 − GmOnly」白名单。
      String directive =
          "{\"directiveId\":\"d-gm-only\",\"decisionMakerId\":\""
              + dmId
              + "\",\"tick\":"
              + worldTick
              + ",\"intentInfo\":\"越权尝试写政府政策\",\"commands\":[{\"type\":\""
              + EconomySetGovernmentPolicyHandler.TYPE
              + "\",\"payloadJson\":\"{}\"}]}";
      CommandResult viaDirective = submit(shell, head, "sd.IssueDirective", directive);
      assertThat(viaDirective).as("令里嵌 GmOnly 命令必须被拒").isInstanceOf(CommandResult.Rejected.class);
      assertThat(((CommandResult.Rejected) viaDirective).reason())
          .as("拒因 = 白名单（不是别的校验先拦下）")
          .contains("决策命令不在白名单")
          .contains(EconomySetGovernmentPolicyHandler.TYPE);
      assertThat(harness.head()).as("零 revision").isEqualTo(head);

      // ② RegisterEffect：可入队白名单 = 同一份「注册面 − GmOnly」。
      String effect =
          "{\"effectId\":\"e-gm-only\",\"kind\":\"SCHEDULED\","
              + "\"trigger\":{\"@class\":\"at_or_after_tick\",\"tick\":5},"
              + "\"action\":{\"@class\":\"enqueue_unit_command\",\"type\":\""
              + EconomySetGovernmentPolicyHandler.TYPE
              + "\",\"payloadJson\":\"{}\"}}";
      CommandResult viaEffect = submit(shell, head, "sd.RegisterEffect", effect);
      assertThat(viaEffect)
          .as("RegisterEffect 里塞 GmOnly 命令必须被拒")
          .isInstanceOf(CommandResult.Rejected.class);
      assertThat(((CommandResult.Rejected) viaEffect).reason())
          .as("拒因 = 白名单")
          .contains("不在白名单")
          .contains(EconomySetGovernmentPolicyHandler.TYPE);
      assertThat(harness.head()).as("零 revision").isEqualTo(head);
    }
  }

  // ── 判据 5：GOV 删除收益（规则随记录走；无悬空键、不影响别的政府）─────────────────────

  @Test
  void deletingAGovernmentRecordRemovesItsRulesWithoutDanglingKeysAndKeepsOthersIntact() {
    SimulationState base = withSingleHexMarketZone(GovZ6WorldFixture.smallWorldState("Map1"));
    EconomyData withRules =
        withPortPolicy(
            withPortPolicy(GovZ6WorldFixture.economySlice(base), PROVINCE, true), CENTRAL, true);
    GovernmentId provinceId = GovernmentIds.ofUnit(PROVINCE.value());

    Map<GovernmentId, Government> kept = new LinkedHashMap<>(withRules.governments());
    Government removed = kept.remove(provinceId);
    assertThat(removed).as("前提：省政府的记录确实存在").isNotNull();
    EconomyData afterDeletion = withRules.withGovernments(kept);

    assertThat(afterDeletion.governments()).as("删掉记录 ⇒ 没有任何悬空键").doesNotContainKey(provinceId);
    assertThatThrownBy(() -> afterDeletion.governmentOf(PROVINCE.value()))
        .as("记录没了 = 状态缺失（governmentOf fail-closed，不静默给中性默认）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有对应的政府记录");
    assertThat(afterDeletion.governmentOrDefault(PROVINCE.value()).portPolicy())
        .as("该政府的规则随之消失（读回中性：不限制/不收税/不要求管控）")
        .isEqualTo(GovPortPolicy.empty());
    assertThat(afterDeletion.governments().get(GovernmentIds.ofUnit(CENTRAL.value())).portPolicy())
        .as("别的政府逐值不受影响")
        .isEqualTo(withRules.governments().get(GovernmentIds.ofUnit(CENTRAL.value())).portPolicy());

    // 两桥读数同样只少那一个政府：要求管控的政府数恰好少 1，别的不动。
    SimulationState afterState = stateWithEconomy(base, afterDeletion);
    assertThat(
            procurementDay(base, withRules).controllingGovernments()
                - procurementDay(afterState).controllingGovernments())
        .as("要求管控的政府数恰好减一（没有级联、没有连带）")
        .isEqualTo(1);
    assertThat(portDay(afterState).restrictedClasses())
        .as("口岸面读数只可能减少（省政府那一份规则没了）")
        .isLessThanOrEqualTo(portDay(base, withRules).restrictedClasses());
  }

  // ── 判据 6：两桥一致（同一份规则；交叉改动各动一面）──────────────────────────────────

  @Test
  void bothBridgesReadTheSameGovernmentRecordAndCrossedMutationsMoveEachReadingIndependently() {
    SimulationState base = withSingleHexMarketZone(GovZ6WorldFixture.smallWorldState("Map1"));
    EconomyData neutral = GovZ6WorldFixture.economySlice(base);

    // ① 只给「管制规则」、不开 marketControl ⇒ 口岸面动、优先级面不动。
    EconomyData rulesOnly = withPortPolicy(neutral, PROVINCE, false);
    assertThat(portDay(base, rulesOnly).restrictedClasses()).as("口岸桥读到规则").isPositive();
    assertThat(procurementDay(base, rulesOnly).controllingGovernments())
        .as("没要求管控市场 ⇒ 优先级桥不动")
        .isZero();

    // ② 只开 marketControl、规则为空 ⇒ 优先级面动、口岸面不动。
    EconomyData controlOnly = withMarketControl(neutral, PROVINCE, true);
    assertThat(portDay(base, controlOnly).restrictedClasses()).as("没有规则 ⇒ 口岸面不动").isZero();
    assertThat(procurementDay(base, controlOnly).controllingGovernments())
        .as("优先级桥读到同一份记录的 controlsMarket")
        .isEqualTo(1);
    assertThat(procurementDay(base, controlOnly).unitsByGovernment().keySet())
        .as("读的正是 economy.governments 里那一条（键 = GovernmentId）")
        .containsExactly(GovernmentIds.ofUnit(PROVINCE.value()).value());

    // ③ 两样都设 ⇒ 两面同时动（同一条记录的两个字段各自被各自的桥读）。
    EconomyData both = withPortPolicy(neutral, PROVINCE, true);
    assertThat(portDay(base, both).restrictedClasses()).isPositive();
    assertThat(procurementDay(base, both).controllingGovernments()).isEqualTo(1);
  }

  // ── 顺序等价性（实现方点名要求实测钉死）──────────────────────────────────────────────

  @Test
  void procurementPriorityOrderIsGovernmentIdAscendingWhichCoincidesWithGovUnitIdAscending() {
    // ★ 故意给「数字序 vs 字典序」会分叉的 id 构造：gov-2 / gov-10 / gov-1。
    List<String> controllingUnitIds = List.of("gov-2", "gov-10", "gov-1");
    Map<GovernmentId, Government> governments = new LinkedHashMap<>();
    for (String unitId : controllingUnitIds) {
      governments.put(GovernmentIds.ofUnit(unitId), government(unitId, true));
    }
    // 不要求管控的 GOV：不进表。
    governments.put(GovernmentIds.ofUnit("gov-3"), government("gov-3", false));
    // 世界级政府（不是任何 GOV 单位）：即使要求管控也没有编制可折 ⇒ 不进表。
    governments.put(
        WORLD_SILVER,
        new Government(
            WORLD_SILVER,
            "world",
            HouseholdActors.of(GovernmentHouseholds.of("world-silver")),
            Set.of(),
            0L,
            Map.of(),
            new GovPortPolicy(Map.of(), Map.of(), true),
            GovBudgetPolicy.neutral(),
            GovAdministrationPlan.neutral()));
    EconomyData economy = EconomyData.empty().withGovernments(governments);
    Map<UnitId, GovernmentServiceLaborBridge.Supply> supplyByUnit =
        Map.of(
            UnitId.parse("gov-2"), new GovernmentServiceLaborBridge.Supply(1_000L, 0L),
            UnitId.parse("gov-10"), new GovernmentServiceLaborBridge.Supply(500L, 500L));
    ProcurementPriorityDay day =
        ProcurementPriorityBridge.compute(supplyByUnit, economy, 1_000L, 7L);

    List<String> byGovernmentId =
        new ArrayList<>(List.of("gov-unit-gov-1", "gov-unit-gov-10", "gov-unit-gov-2"));
    byGovernmentId.sort(String::compareTo);
    List<String> byGovUnitId =
        controllingUnitIds.stream().sorted().map(unitId -> "gov-unit-" + unitId).toList();

    assertThat(day.unitsByGovernment().keySet())
        .as("遍历序 = GovernmentId 升序（旧序 = GOV 单位 id 升序；同前缀 ⇒ 两者同向）")
        .containsExactlyElementsOf(byGovernmentId);
    assertThat(byGovernmentId)
        .as("实测：两条排序逐位相同（不是只靠「同前缀」这句话）")
        .containsExactlyElementsOf(byGovUnitId);
    assertThat(day.unitsByGovernment())
        .as("逐政府的池 = 行政劳动 ÷ 标准定额（缺编制供给 ⇒ 0）")
        .containsExactly(
            Map.entry("gov-unit-gov-1", 0L),
            Map.entry("gov-unit-gov-10", 1L),
            Map.entry("gov-unit-gov-2", 1L));
    assertThat(day.controllingGovernments()).as("要求管控的 GOV 恰三个").isEqualTo(3);
    assertThat(day.zeroPoolGovernments()).as("其中 1 个折不出池（gov-1 缺供给）").isEqualTo(1);
    assertThat(day.unitsByGovernment())
        .as("不要求管控的 GOV 与世界级政府都不进表")
        .doesNotContainKeys("gov-unit-gov-3", "world-silver");
  }

  // ── 夹具与助手 ──────────────────────────────────────────────────────────────────────

  private static ShellConfig configFor(Path store) {
    return ShellConfig.defaults(store).withPorts(0, 0, 0).withWorldId(WorldRegistry.SMALL_WORLD);
  }

  /**
   * ★ <b>给世界造一个单格市场区</b>：口岸是「市场区 ↔ 外区接壤」<b>默认存在</b>的（用户 2026-10-09 原话），小世界出厂只有 <b>派生市场</b>（价表成员格 =
   * 全图，没有持久区）⇒ 先得立一个<b>持久区</b>，才有暴露边与接触面。
   *
   * <p>锚格取<b>省辖区里的第一格</b> ⇒ 该格暴露边的归属 = {@code gov-province}（于是省政府的规则真的抓得住）。
   * 立区的同一批里把那格从派生价表里摘出来、并给新区一张（成员格为空的）价表 —— 这是 {@code withMarketZonesAndTables} 存在的理由（"一个 hex
   * 属于哪个市场"只许有一个答案）。
   */
  private static SimulationState withSingleHexMarketZone(SimulationState state) {
    GameMap map = gameMap(state);
    UnitState units = GovZ6WorldFixture.unitSlice(state);
    EconomyData economy = GovZ6WorldFixture.economySlice(state);
    RegionId regionId =
        units
            .units()
            .get(PROVINCE)
            .jurisdiction()
            .orElseThrow()
            .taxRatePerMilleByRegion()
            .keySet()
            .iterator()
            .next();
    HexCoord anchor = map.regions().get(regionId).hexes().iterator().next();
    MarketZoneId zoneId = new MarketZoneId("zone-policy-test");
    MarketZone zone =
        new MarketZone(
            zoneId, anchor, 0, Set.of(anchor), MoneyVocabulary.SILVER_CURRENCY, Map.of());

    Map<MarketZoneId, MarketZone> zones = new LinkedHashMap<>(economy.marketZones());
    zones.put(zoneId, zone);
    Map<MarketZoneId, MarketTable> tables = new LinkedHashMap<>();
    for (Map.Entry<MarketZoneId, MarketTable> entry : economy.marketTables().entrySet()) {
      MarketTable table = entry.getValue();
      if (!table.hexes().contains(anchor)) {
        tables.put(entry.getKey(), table);
        continue;
      }
      // 新区的价表：同一份价与利率，成员格为空（持久形态下成员格权威 = MarketZone.hexes）。
      tables.put(
          zoneId,
          new MarketTable(
              table.numeraire(),
              Set.of(),
              table.prices(),
              table.loanRatePerMille(),
              table.loanRateAlphaPerMille()));
      Set<HexCoord> remaining = new LinkedHashSet<>(table.hexes());
      remaining.remove(anchor);
      if (!remaining.isEmpty()) {
        tables.put(
            entry.getKey(),
            new MarketTable(
                table.numeraire(),
                remaining,
                table.prices(),
                table.loanRatePerMille(),
                table.loanRateAlphaPerMille()));
      }
    }
    return stateWithEconomy(state, economy.withMarketZonesAndTables(zones, tables));
  }

  /** 真壳小世界创世（形制同 {@code GovToolsZ6Test}：sd 片替换为带决策人的状态）。 */
  private static Harness genesisWithDecisionMakers(ShellConfig config, String label) {
    Shell shell = Shell.start(config);
    try {
      SimulationState state = withSingleHexMarketZone(SmallWorld.state(config.mapId()));
      StateMeta meta = state.meta();
      state =
          GovZ6WorldFixture.withModule(
              state,
              "sd",
              new SdSnapshot(
                  meta.ref(),
                  meta.timestamp(),
                  SdState.empty().withDecisionMakers(decisionMakers())));
      shell.coreSimos().bootstrapGenesis(state);
      return new Harness(shell, label);
    } catch (RuntimeException e) {
      shell.close();
      throw e;
    }
  }

  private static Map<DecisionMakerId, DecisionMaker> decisionMakers() {
    Map<DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(
        new DecisionMakerId("dm-central"),
        new DecisionMaker(
            new DecisionMakerId("dm-central"),
            new Affiliation.Gov(CENTRAL),
            Set.of(),
            AccessLimit.empty(),
            1));
    return makers;
  }

  private static String firstDecisionMakerId(SimulationState state) {
    SdState sd = ((SdSnapshot) state.module("sd").orElseThrow()).state();
    assertThat(sd.decisionMakers()).as("夹具世界必须至少有一个决策人（令的落点）").isNotEmpty();
    return sd.decisionMakers().keySet().iterator().next().value();
  }

  private static CommandResult submit(Shell shell, long expectedRevision, String payload) {
    return submit(shell, expectedRevision, EconomySetGovernmentPolicyHandler.TYPE, payload);
  }

  private static CommandResult submit(
      Shell shell, long expectedRevision, String type, String payload) {
    return shell
        .coreSimos()
        .submit(
            new CommandEnvelope(
                "cmd-" + type + "-" + expectedRevision,
                "corr-" + type + "-" + expectedRevision,
                "test:gov-policy-into-economy",
                GovZ6WorldFixture.MAIN,
                new RevisionId(expectedRevision),
                type,
                payload));
  }

  private static String portPayload(UnitId govUnitId, boolean marketControl) {
    return "{\"governmentId\":\""
        + GovernmentIds.ofUnit(govUnitId.value()).value()
        + "\",\"policies\":{\"port\":{"
        + "\"commodityRules\":{\"grain\":{\"entryRestrictionPerMille\":400,"
        + "\"exitRestrictionPerMille\":600,"
        + "\"entryTax\":{\"mode\":\"per_unit_milli\",\"amount\":3}}},"
        + "\"currencyRules\":{\"silver\":{\"lending\":{\"entryRestrictionPerMille\":1000}}},"
        + "\"marketControl\":"
        + marketControl
        + "}}}";
  }

  private static Government government(String govUnitId, boolean marketControl) {
    HouseholdId treasury = GovernmentHouseholds.of(govUnitId);
    return new Government(
        GovernmentIds.ofUnit(govUnitId),
        "world",
        HouseholdActors.of(treasury),
        Set.of(),
        0L,
        Map.of(),
        new GovPortPolicy(Map.of(), Map.of(), marketControl),
        GovBudgetPolicy.neutral(),
        GovAdministrationPlan.neutral());
  }

  private static Government governmentOf(SimulationState state, UnitId govUnitId) {
    return GovZ6WorldFixture.economySlice(state).governmentOrDefault(govUnitId.value());
  }

  private static PortRegimeDay portDay(SimulationState state) {
    return portDay(state, GovZ6WorldFixture.economySlice(state));
  }

  private static PortRegimeDay portDay(SimulationState state, EconomyData economy) {
    return PortRegimeBridge.compute(
        GovZ6WorldFixture.unitSlice(state), gameMap(state), economy, Map.of(), 1L);
  }

  private static ProcurementPriorityDay procurementDay(SimulationState state) {
    return procurementDay(state, GovZ6WorldFixture.economySlice(state));
  }

  private static ProcurementPriorityDay procurementDay(SimulationState state, EconomyData economy) {
    UnitState units = GovZ6WorldFixture.unitSlice(state);
    Map<UnitId, GovernmentServiceLaborBridge.Supply> supplyByUnit = new LinkedHashMap<>();
    for (UnitId unitId : units.units().keySet()) {
      supplyByUnit.put(unitId, new GovernmentServiceLaborBridge.Supply(16_000L, 16_000L));
    }
    return ProcurementPriorityBridge.compute(supplyByUnit, economy, 16_000L, 1L);
  }

  private static GameMap gameMap(SimulationState state) {
    return ((MapSnapshot) state.module("map").orElseThrow()).map();
  }

  /** 三类政策全部换成中性/空值（其余字段逐字不动）—— 「没设过政策」的最强构造。 */
  private static EconomyData withNeutralPolicies(EconomyData economy) {
    Map<GovernmentId, Government> governments = new LinkedHashMap<>();
    for (Map.Entry<GovernmentId, Government> entry : economy.governments().entrySet()) {
      Government row = entry.getValue();
      governments.put(
          entry.getKey(),
          row.withPortPolicy(GovPortPolicy.empty())
              .withBudgetPolicy(GovBudgetPolicy.neutral())
              .withAdministrationPlan(GovAdministrationPlan.neutral()));
    }
    return economy.withGovernments(governments);
  }

  /** 只给该政府加「商品规则 + 币种规则」（marketControl 由调用方给）。 */
  private static EconomyData withPortPolicy(
      EconomyData economy, UnitId govUnitId, boolean marketControl) {
    GovernmentId id = GovernmentIds.ofUnit(govUnitId.value());
    Government row = economy.governments().get(id);
    assertThat(row).as("夹具世界里必须有该政府记录").isNotNull();
    Map<GovernmentId, Government> governments = new LinkedHashMap<>(economy.governments());
    governments.put(
        id,
        row.withPortPolicy(
            new GovPortPolicy(
                Map.of(
                    EconomyCommodities.GRAIN,
                    new PortRule(400L, 600L, PortTaxRule.none(), PortTaxRule.none())),
                Map.of(
                    MoneyVocabulary.SILVER_CURRENCY,
                    Map.of(
                        MarketOrderKind.LENDING,
                        new PortRule(1_000L, 0L, PortTaxRule.none(), PortTaxRule.none()))),
                marketControl)));
    return economy.withGovernments(governments);
  }

  private static EconomyData withMarketControl(
      EconomyData economy, UnitId govUnitId, boolean marketControl) {
    GovernmentId id = GovernmentIds.ofUnit(govUnitId.value());
    Government row = economy.governments().get(id);
    Map<GovernmentId, Government> governments = new LinkedHashMap<>(economy.governments());
    governments.put(id, row.withPortPolicy(new GovPortPolicy(Map.of(), Map.of(), marketControl)));
    return economy.withGovernments(governments);
  }

  private static SimulationState stateWithEconomy(SimulationState state, EconomyData economy) {
    return GovZ6WorldFixture.withModule(
        state,
        "economy",
        new EconomySnapshot(state.meta().ref(), state.meta().timestamp(), economy));
  }

  /** 一座真壳小世界（创世已落在 (main, 1)）+ 逐日推进；只服务本用例的"设→推进→重启"。 */
  private static final class Harness implements AutoCloseable {

    private final Shell shell;
    private final String label;
    private long advanceSeq;

    private Harness(Shell shell, String label) {
      this.shell = shell;
      this.label = label;
    }

    Shell shell() {
      return shell;
    }

    long head() {
      return shell.coreSimos().head(GovZ6WorldFixture.MAIN).orElseThrow().value();
    }

    SimulationState state() {
      return shell.coreSimos().replay(new StateRef(GovZ6WorldFixture.MAIN, new RevisionId(head())));
    }

    long advance(long fromTick, long toTick) {
      long head = head();
      shell.advanceAndDrain(
          new AdvanceTime(
              "cmd-" + label + "-" + (advanceSeq++),
              "corr-" + label + "-" + advanceSeq,
              label,
              GovZ6WorldFixture.MAIN,
              new RevisionId(head),
              new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick)))));
      return head();
    }

    @Override
    public void close() {
      shell.close();
    }
  }
}
