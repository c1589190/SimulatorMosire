package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.market.PortRule;
import io.mosire.simos.economy.api.market.PortTaxMode;
import io.mosire.simos.economy.api.market.PortTaxRule;
import io.mosire.simos.economy.api.money.CurrencyDef;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.govpolicy.GovAdministrationPlan;
import io.mosire.simos.economy.govpolicy.GovBudgetCategory;
import io.mosire.simos.economy.govpolicy.GovBudgetLine;
import io.mosire.simos.economy.govpolicy.GovBudgetPolicy;
import io.mosire.simos.economy.govpolicy.GovOfficialSalaryRule;
import io.mosire.simos.economy.govpolicy.GovPortPolicy;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>{@code economy.SetGovernmentPolicy} 命令边界（追加裁定 12，2026-10-12 §21.9 D-1~D-4）</b>：三类政府政策 （口岸 /
 * 预算 / 编制）的<b>唯一通用写口</b>。
 *
 * <p><b>判据来源</b>：设计书 §21.5 判据 3（非法政策 fail-closed + 零 revision）、§21.4 I-B1/I-B2（缺省中性、缺省语义唯一拼写点）、
 * I-B3（铁律 5 往返）、I-B4（权限单调性 —— 标记面见 app 的 {@code GovPolicyIntoEconomyZ6Test}），以及 §21.9 的
 * 通用写口契约（一条命令改任意子集 / 恰一条 revision / 逐值相同 ⇒ 具名拒）。
 *
 * <p><b>夹具</b>：真 {@link SimulationState} + 只有 economy 切片（不打 DB，形态照 {@code EconomySeedHandlerTest}）；
 * 世界里登记两个政府（{@code gov-unit-gov-central} / {@code gov-unit-gov-province}）与一种真币种 {@code silver}。
 */
class EconomySetGovernmentPolicyHandlerTest {

  private static final EconomySetGovernmentPolicyHandler HANDLER =
      new EconomySetGovernmentPolicyHandler();
  private static final EconomyCodec CODEC = new EconomyCodec();
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final GovernmentId CENTRAL = new GovernmentId("gov-unit-gov-central");
  private static final GovernmentId PROVINCE = new GovernmentId("gov-unit-gov-province");
  private static final CurrencyId SILVER = new CurrencyId("silver");

  // ── ① 正例：子集写入 / 恰一个组件变化 ────────────────────────────────────────────────

  @Test
  void portSubsetWritesPortPolicyAndChangesOnlyTheGovernmentsComponent() throws Exception {
    EconomyData base = baseEconomy();
    HandlerOutcome outcome = HANDLER.handle(state(base), portOnlyPayload());

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    EconomyChangeSet changeSet = (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(changedComponents(changeSet))
        .as("三类政策挂在 Government 记录上（不新增独立组件）⇒ 只碰 governments 一个组件")
        .containsExactly("governments");

    EconomyData next = EconomyChangeSet.apply(changeSet, base);
    Government central = next.governments().get(CENTRAL);
    assertThat(central.portPolicy().commodityRules()).containsOnlyKeys(EconomyCommodities.GRAIN);
    assertThat(central.portPolicy().commodityRules().get(EconomyCommodities.GRAIN))
        .isEqualTo(
            new PortRule(
                250L,
                1_000L,
                new PortTaxRule(PortTaxMode.PER_UNIT_MILLI, 5L),
                new PortTaxRule(PortTaxMode.AD_VALOREM_PER_MILLE, 100L)));
    assertThat(central.portPolicy().currencyRules())
        .as("币种表两层：币种 → 挂单类型 → 四元组")
        .containsOnlyKeys(SILVER);
    assertThat(central.portPolicy().currencyRules().get(SILVER))
        .containsOnlyKeys(MarketOrderKind.LENDING, MarketOrderKind.COMMODITY);
    assertThat(central.portPolicy().controlsMarket()).isTrue();
    assertThat(central.budgetPolicy()).as("子集缺席 = 该类一个数都不动").isEqualTo(GovBudgetPolicy.neutral());
    assertThat(central.administrationPlan())
        .as("子集缺席 = 该类一个数都不动")
        .isEqualTo(GovAdministrationPlan.neutral());
    assertThat(next.governments().get(PROVINCE))
        .as("别的政府逐值不动")
        .isEqualTo(base.governments().get(PROVINCE));
  }

  @Test
  void threeSubsetsInOneCommandProduceExactlyOneAppliedChangeSet() throws Exception {
    EconomyData base = baseEconomy();
    String payload =
        "{\"governmentId\":\"gov-unit-gov-central\",\"policies\":{"
            + portNode()
            + ",\"budget\":{\"orderedCategories\":[{\"category\":\"ADMIN_SALARY\","
            + "\"minPerCycle\":0,\"capPerCycle\":1000}],"
            + "\"officialSalaryRule\":{\"grainMilliPerCommittedHour\":10,"
            + "\"silverMilliPerCommittedHour\":1},\"remittancePerMilleToSuperior\":500},"
            + "\"administration\":{\"securityPlannedLaborMilli\":12345,"
            + "\"paperworkPlannedLaborMilli\":12345,\"portPlannedLaborMilli\":777}}}";

    HandlerOutcome outcome = HANDLER.handle(state(base), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    EconomyChangeSet changeSet = (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(changedComponents(changeSet))
        .as("三类同批写入 = 恰一条变更集（只有 governments 组件变）")
        .containsExactly("governments");
    Government central = EconomyChangeSet.apply(changeSet, base).governments().get(CENTRAL);
    assertThat(central.portPolicy().controlsMarket()).isTrue();
    assertThat(central.budgetPolicy().remittancePerMilleToSuperior()).isEqualTo(500L);
    assertThat(central.budgetPolicy().orderedCategories())
        .extracting(GovBudgetLine::category)
        .containsExactly(GovBudgetCategory.ADMIN_SALARY);
    assertThat(central.administrationPlan().securityPlannedLaborMilli()).isEqualTo(12_345L);
    assertThat(central.administrationPlan().portPlannedLaborMilli())
        .as("口岸维计划量随三类政策一起进 economy")
        .isEqualTo(777L);
    assertThat(central.administrationPlan().postTiers())
        .as("编制计划缺省展开 = 默认 3 档")
        .isEqualTo(GovAdministrationPlan.DEFAULT_POST_TIERS);
  }

  @Test
  void aSubsetAbsentInASecondCommandKeepsWhatTheFirstCommandWrote() {
    EconomyData base = baseEconomy();
    EconomyData afterPort =
        EconomyChangeSet.apply(
            (EconomyChangeSet)
                ((HandlerOutcome.Applied) HANDLER.handle(state(base), portOnlyPayload()))
                    .changeSet(),
            base);

    String budgetOnly =
        "{\"governmentId\":\"gov-unit-gov-central\",\"policies\":{\"budget\":"
            + "{\"orderedCategories\":[{\"category\":\"OTHER\",\"capPerCycle\":9}],"
            + "\"officialSalaryRule\":{\"grainMilliPerCommittedHour\":0,"
            + "\"silverMilliPerCommittedHour\":0},\"remittancePerMilleToSuperior\":0}}}";
    Government after =
        EconomyChangeSet.apply(
                (EconomyChangeSet)
                    ((HandlerOutcome.Applied) HANDLER.handle(state(afterPort), budgetOnly))
                        .changeSet(),
                afterPort)
            .governments()
            .get(CENTRAL);

    assertThat(after.portPolicy())
        .as("缺 port 子集 ⇒ 口岸政策逐值保留")
        .isEqualTo(afterPort.governments().get(CENTRAL).portPolicy());
    assertThat(after.budgetPolicy().orderedCategories())
        .extracting(GovBudgetLine::category)
        .containsExactly(GovBudgetCategory.OTHER);
  }

  @Test
  void identicalPoliciesAreRejectedAsUnchanged() {
    EconomyData base = baseEconomy();
    EconomyData afterPort =
        EconomyChangeSet.apply(
            (EconomyChangeSet)
                ((HandlerOutcome.Applied) HANDLER.handle(state(base), portOnlyPayload()))
                    .changeSet(),
            base);

    HandlerOutcome again = HANDLER.handle(state(afterPort), portOnlyPayload());

    assertThat(again).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) again).reason())
        .as("与现值逐值相同 ⇒ 具名拒，不落空 revision")
        .startsWith("policies-unchanged");

    // ★ 负向对照（证明上面那条不是"恒拒"）：改一个数 ⇒ 当场 Applied。
    String oneNumberDifferent =
        portOnlyPayload()
            .replace("\"entryRestrictionPerMille\":250", "\"entryRestrictionPerMille\":251");
    assertThat(HANDLER.handle(state(afterPort), oneNumberDifferent))
        .as("改一个数就该写进去")
        .isInstanceOf(HandlerOutcome.Applied.class);
  }

  // ── ② 负向：坏载荷 / 未知政府 / 非法政策 ⇒ 具名拒（判据 3）──────────────────────────

  @Test
  void badPayloadShapesAreNamedRejects() {
    List<String> badPayloads =
        List.of(
            "{}",
            "{\"governmentId\":\"gov-unit-gov-central\"}",
            "{\"governmentId\":\"gov-unit-gov-central\",\"policies\":null}",
            "{\"governmentId\":\"gov-unit-gov-central\",\"policies\":{}}",
            "{\"governmentId\":\"gov-unit-gov-central\",\"policies\":[]}",
            "{\"governmentId\":\"gov-unit-gov-central\",\"policies\":{\"port\":7}}",
            "{\"governmentId\":\"\",\"policies\":{\"port\":{}}}",
            "{\"policies\":{\"port\":{}}}",
            "{\"governmentId\":\"gov-unit-gov-central\",\"govUnitId\":\"gov-central\","
                + "\"policies\":{\"port\":{}}}");
    for (String payload : badPayloads) {
      HandlerOutcome outcome = HANDLER.handle(state(baseEconomy()), payload);
      assertThat(outcome).as("坏载荷必须具名拒: %s", payload).isInstanceOf(HandlerOutcome.Rejected.class);
      assertThat(((HandlerOutcome.Rejected) outcome).reason())
          .as("拒因必须具名（bad-payload）: %s", payload)
          .startsWith("bad-payload");
    }
  }

  @Test
  void unknownGovernmentIsANamedRejectAndNoChangeSetIsProduced() {
    HandlerOutcome outcome =
        HANDLER.handle(
            state(baseEconomy()),
            "{\"governmentId\":\"gov-unit-gov-missing\",\"policies\":{\"port\":{}}}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("政府不存在 ⇒ 具名拒 government-missing（不凭空建政府）")
        .startsWith("government-missing")
        .contains("gov-unit-gov-missing");
    assertThat(baseEconomy().governments())
        .as("本命令不建政府：RegisterGovernment 才管建（附带国库/发钞权）")
        .doesNotContainKey(new GovernmentId("gov-unit-gov-missing"));
  }

  @Test
  void illegalPolicyValuesAreNamedRejects() {
    // 每条 = {载荷, 期望拒因片段}：全部由 EconomyGovPolicyPayloads / 三个领域 record 的构造期具名拒。
    Map<String, String> illegal = new LinkedHashMap<>();
    illegal.put(portNodeWithCommodity("\"entryRestrictionPerMille\":-1"), "不得为负");
    illegal.put(
        portNodeWithCommodity("\"entryTax\":{\"mode\":\"per_unit_milli\",\"amount\":-5}"), "不得为负");
    illegal.put(portNodeWithCommodity("\"entryRestrictionPerMille\":\"高\""), "整数");
    illegal.put("{\"commodityRules\":{\"unobtainium\":{\"entryRestrictionPerMille\":1}}}", "未知商品");
    illegal.put(
        "{\"currencyRules\":{\"ghost-coin\":{\"lending\":{\"entryRestrictionPerMille\":1}}}}",
        "未知币种");
    illegal.put(
        "{\"currencyRules\":{\"silver\":{\"barter\":{\"entryRestrictionPerMille\":1}}}}",
        // ★ 拒因经 EconomyCommandPayloads.logReason 截到第一个 '['（日志不进载荷明文）⇒ 只留可读前缀。
        "字段 currencyRules");
    illegal.put(
        "{\"commodityRules\":{\"grain\":{\"entryRestrictionPemille\":1}}}",
        // ★ 同上：拒因被 logReason 截到第一个 '['（不把载荷原文写进日志/理由）。
        "字段 commodityRules");
    illegal.put("{\"marketControl\":\"true\"}", "必须是布尔");
    for (Map.Entry<String, String> entry : illegal.entrySet()) {
      String payload =
          "{\"governmentId\":\"gov-unit-gov-central\",\"policies\":{\"port\":"
              + entry.getKey()
              + "}}";
      HandlerOutcome outcome = HANDLER.handle(state(baseEconomy()), payload);
      assertThat(outcome)
          .as("非法口岸政策必须具名拒: %s", entry.getKey())
          .isInstanceOf(HandlerOutcome.Rejected.class);
      assertThat(((HandlerOutcome.Rejected) outcome).reason())
          .as("拒因必须点名: %s", entry.getKey())
          .startsWith("bad-payload")
          .contains(entry.getValue());
    }

    Map<String, String> illegalBudget = new LinkedHashMap<>();
    illegalBudget.put("{\"orderedCategories\":[{\"category\":\"NOT_A_CATEGORY\"}]}", "未知预算类别");
    illegalBudget.put(
        "{\"orderedCategories\":[{\"category\":\"OTHER\",\"minPerCycle\":10,\"capPerCycle\":5}]}",
        "min");
    illegalBudget.put("{\"remittancePerMilleToSuperior\":1001}", "remittancePerMilleToSuperior 必须");
    illegalBudget.put("{\"orderedCategories\":\"ADMIN_SALARY\"}", "必须是数组");
    for (Map.Entry<String, String> entry : illegalBudget.entrySet()) {
      String payload =
          "{\"governmentId\":\"gov-unit-gov-central\",\"policies\":{\"budget\":"
              + entry.getKey()
              + "}}";
      HandlerOutcome outcome = HANDLER.handle(state(baseEconomy()), payload);
      assertThat(((HandlerOutcome.Rejected) requireRejected(outcome, entry.getKey())).reason())
          .as("非法预算政策必须具名拒: %s", entry.getKey())
          .startsWith("bad-payload")
          .contains(entry.getValue());
    }

    Map<String, String> illegalPlan = new LinkedHashMap<>();
    illegalPlan.put("{\"securityPlannedLaborMilli\":-1}", "≥ 0");
    illegalPlan.put("{\"postTiers\":[{\"tierId\":\"t1\"},{\"tierId\":\"t2\"}]}", "恰 3 档");
    illegalPlan.put(
        "{\"postTiers\":[{\"tierId\":\"\"},{\"tierId\":\"t2\"},{\"tierId\":\"t3\"}]}", "tierId");
    for (Map.Entry<String, String> entry : illegalPlan.entrySet()) {
      String payload =
          "{\"governmentId\":\"gov-unit-gov-central\",\"policies\":{\"administration\":"
              + entry.getKey()
              + "}}";
      HandlerOutcome outcome = HANDLER.handle(state(baseEconomy()), payload);
      assertThat(((HandlerOutcome.Rejected) requireRejected(outcome, entry.getKey())).reason())
          .as("非法编制计划必须具名拒: %s", entry.getKey())
          .startsWith("bad-payload")
          .contains(entry.getValue());
    }
  }

  // ── ③ 缺省中性（I-B1/I-B2）──────────────────────────────────────────────────────────

  @Test
  void neutralDefaultsAreValueIdenticalAndDefaultIsWideOpenNotSilentlyZero() {
    Government oldShape = new Government(CENTRAL, "world", treasury("gov-central"), Set.of(SILVER));
    assertThat(oldShape.portPolicy()).isEqualTo(GovPortPolicy.empty());
    assertThat(oldShape.budgetPolicy()).isEqualTo(GovBudgetPolicy.neutral());
    assertThat(oldShape.administrationPlan()).isEqualTo(GovAdministrationPlan.neutral());

    EconomyData empty = EconomyData.empty();
    assertThatThrownBy(() -> empty.governmentOf("gov-central"))
        .as("没有记录 = 状态缺失（不猜、不静默给中性默认）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有对应的政府记录");
    Government neutral = empty.governmentOrDefault("gov-central");
    assertThat(neutral.portPolicy()).isEqualTo(GovPortPolicy.empty());
    assertThat(neutral.budgetPolicy()).isEqualTo(GovBudgetPolicy.neutral());
    assertThat(neutral.administrationPlan()).isEqualTo(GovAdministrationPlan.neutral());

    // ★★ "缺省 = 不限制/不收税"必须逐值成立：缺省是**完全开放**（1000‰ 可通行），不是"静默封死"。
    PortRule unrestricted = GovPortPolicy.empty().ruleOfCommodity(EconomyCommodities.GRAIN);
    assertThat(unrestricted).isEqualTo(PortRule.unrestricted());
    assertThat(unrestricted.entryRestrictionPerMille())
        .isEqualTo(PortRule.RESTRICTION_NONE_PER_MILLE);
    assertThat(unrestricted.exitRestrictionPerMille())
        .isEqualTo(PortRule.RESTRICTION_NONE_PER_MILLE);
    assertThat(unrestricted.entryTax()).isEqualTo(PortTaxRule.none());
    assertThat(unrestricted.exitTax()).isEqualTo(PortTaxRule.none());
    assertThat(GovPortPolicy.empty().ruleOfCurrency(SILVER, MarketOrderKind.LENDING))
        .isEqualTo(PortRule.unrestricted());
    assertThat(GovPortPolicy.empty().controlsMarket()).as("缺省不要求管控市场").isFalse();
    assertThat(GovBudgetPolicy.neutral().officialSalaryRule())
        .isEqualTo(GovOfficialSalaryRule.zero());
    assertThat(GovBudgetPolicy.neutral().orderedCategories()).isEmpty();
    assertThat(GovBudgetPolicy.neutral().remittancePerMilleToSuperior()).isZero();
  }

  // ── ④ 不丢失（铁律 5 / I-B3）：编解码往返 + with* 保政策 ────────────────────────────

  @Test
  void codecRoundTripKeepsAllThreePoliciesByteForByteIncludingEnumKeyedMap() {
    EconomyData rich = baseEconomy().withGovernments(governments(richCentral()));
    String first = CODEC.encodeSnapshot(new EconomySnapshot(REF, T7, rich));

    EconomyData readBack = ((EconomySnapshot) CODEC.decodeSnapshot(first)).data();

    assertThat(readBack).as("新代码写出去的档必须能被新代码逐字段读回（铁律 5）").isEqualTo(rich);
    assertThat(readBack.governments().get(CENTRAL).portPolicy())
        .isEqualTo(rich.governments().get(CENTRAL).portPolicy());
    assertThat(
            readBack.governments().get(CENTRAL).portPolicy().currencyRules().get(SILVER).keySet())
        .as("枚举做 map 键（MarketOrderKind）必须逐键读回且保序")
        .containsExactly(MarketOrderKind.LENDING, MarketOrderKind.COMMODITY);
    assertThat(first)
        .as("嵌套枚举键真的写进了线格式（不是靠读侧兜默认）")
        .contains("\"lending\"")
        .contains("\"commodity\"");
    assertThat(CODEC.encodeSnapshot(new EconomySnapshot(REF, T7, readBack)))
        .as("再编码逐字节相同（字节级往返；保序表是内容的纯函数）")
        .isEqualTo(first);

    // ★ 变更集也要能读回并逐字段重建 target（命令落盘走的就是这条路）。
    EconomyChangeSet changeSet = EconomyChangeSet.between(baseEconomy(), rich);
    String changeSetJson = CODEC.encodeChangeSet(changeSet);
    EconomyChangeSet decoded = (EconomyChangeSet) CODEC.decodeChangeSet(changeSetJson);
    assertThat(EconomyChangeSet.apply(decoded, baseEconomy()))
        .as("变更集往返：apply(decode(encode(cs)), base) == target")
        .isEqualTo(rich);
  }

  @Test
  void everyCopyWithAccessorKeepsAllThreePolicies() {
    Government base = richCentral();
    List<Government> rewritten =
        List.of(
            base.withMintScalePerCycle(1_234L),
            base.withMintedLastCyclePerCycle(Map.of(SILVER, 7L)),
            base.withIssuable(Set.of(SILVER, new CurrencyId("copper"))),
            base.withTreasury(new ActorRef(ActorKind.GOVERNMENT, "gov-other")),
            base.withOfficialRate(new OfficialRate(SILVER, new CurrencyId("copper"), 5L, 4L)));

    for (Government rewrittenGovernment : rewritten) {
      assertThat(rewrittenGovernment.portPolicy())
          .as("with* 重建时不得静默丢掉口岸政策")
          .isEqualTo(base.portPolicy());
      assertThat(rewrittenGovernment.budgetPolicy())
          .as("with* 重建时不得静默丢掉预算政策")
          .isEqualTo(base.budgetPolicy());
      assertThat(rewrittenGovernment.administrationPlan())
          .as("with* 重建时不得静默丢掉编制计划")
          .isEqualTo(base.administrationPlan());
    }
  }

  // ── ⑤ 目标声明（D-4：复用既有 CommandTargets）────────────────────────────────────

  @Test
  void targetPathsFollowsTheGovernmentIdAndRejectsAGovernanceWithoutAnyAddress() {
    assertThat(HANDLER.targetPaths("Map1", "{\"governmentId\":\"gov-unit-gov-central\"}"))
        .containsExactly("gov-unit-gov-central");
    assertThat(HANDLER.targetPaths("Map1", "{\"govUnitId\":\"gov-central\"}"))
        .as("只给单位 id ⇒ 空表（economy 不碰 unit 命名空间 ⇒ 无可寻址目标，fail-closed）")
        .isEmpty();
    assertThatThrownBy(() -> HANDLER.targetPaths("Map1", "{}"))
        .as("判不出目标就不得放行")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static HandlerOutcome requireRejected(HandlerOutcome outcome, String label) {
    assertThat(outcome).as("必须具名拒: %s", label).isInstanceOf(HandlerOutcome.Rejected.class);
    return outcome;
  }

  /** 变更集里"真的变了"的组件名（反射枚举 record 组件 ⇒ 加了组件也不会漏判）。 */
  private static List<String> changedComponents(EconomyChangeSet changeSet) throws Exception {
    List<String> changed = new ArrayList<>();
    for (RecordComponent component : EconomyChangeSet.class.getRecordComponents()) {
      Object value = component.getAccessor().invoke(changeSet);
      if (value instanceof io.mosire.simos.util.state.FieldDelta<?> delta && delta.changed()) {
        changed.add(component.getName());
      }
    }
    return changed;
  }

  private static String portOnlyPayload() {
    return "{\"governmentId\":\"gov-unit-gov-central\",\"policies\":{" + portNode() + "}}";
  }

  private static String portNode() {
    return "\"port\":"
        + portNodeWithCommodity(
            "\"entryRestrictionPerMille\":250,\"exitRestrictionPerMille\":1000,"
                + "\"entryTax\":{\"mode\":\"per_unit_milli\",\"amount\":5},"
                + "\"exitTax\":{\"mode\":\"ad_valorem_per_mille\",\"amount\":100}");
  }

  /** 一条商品规则节点（{@code 额外字段} 用来把某条字段换成非法值；商品固定 grain）。 */
  private static String portNodeWithCommodity(String extraFields) {
    return "{\"commodityRules\":{\"grain\":{"
        + extraFields
        + "}},\"currencyRules\":{\"silver\":{\"lending\":{\"entryRestrictionPerMille\":1000},"
        + "\"commodity\":{\"exitRestrictionPerMille\":250}}},\"marketControl\":true}";
  }

  private static Government richCentral() {
    Map<CommodityId, PortRule> commodities = new LinkedHashMap<>();
    commodities.put(
        EconomyCommodities.GRAIN,
        new PortRule(
            250L, 1_000L, new PortTaxRule(PortTaxMode.PER_UNIT_MILLI, 5L), PortTaxRule.none()));
    commodities.put(
        EconomyCommodities.CLOTH,
        new PortRule(
            0L, 500L, PortTaxRule.none(), new PortTaxRule(PortTaxMode.AD_VALOREM_PER_MILLE, 100L)));
    Map<MarketOrderKind, PortRule> silverRules = new LinkedHashMap<>();
    silverRules.put(
        MarketOrderKind.LENDING, new PortRule(1_000L, 0L, PortTaxRule.none(), PortTaxRule.none()));
    silverRules.put(
        MarketOrderKind.COMMODITY, new PortRule(0L, 250L, PortTaxRule.none(), PortTaxRule.none()));
    Map<CurrencyId, Map<MarketOrderKind, PortRule>> currencies = new LinkedHashMap<>();
    currencies.put(SILVER, silverRules);
    GovPortPolicy port = new GovPortPolicy(commodities, currencies, true);

    GovBudgetPolicy budget =
        new GovBudgetPolicy(
            List.of(
                new GovBudgetLine(GovBudgetCategory.ADMIN_SALARY, 0L, 1_000L),
                new GovBudgetLine(GovBudgetCategory.OTHER, 10L, Long.MAX_VALUE)),
            new GovOfficialSalaryRule(10L, 1L),
            500L);
    GovAdministrationPlan plan =
        new GovAdministrationPlan(
            16_000L,
            16_000L,
            777L,
            GovAdministrationPlan.DEFAULT_POST_TIERS,
            1_000L,
            1_000L,
            900L,
            1_000L,
            1_100L,
            1_000L,
            2L);
    return new Government(
        CENTRAL,
        "world",
        treasury("gov-central"),
        Set.of(SILVER),
        0L,
        Map.of(),
        port,
        budget,
        plan);
  }

  private static Government neutralCentral() {
    return new Government(CENTRAL, "world", treasury("gov-central"), Set.of(SILVER));
  }

  private static Government neutralProvince() {
    // ★ 同一币种只能有一个发行主体（EconomyData 构造期守卫）⇒ 省不是发行人。
    return new Government(PROVINCE, "world", treasury("gov-province"), Set.of());
  }

  private static ActorRef treasury(String govUnitId) {
    return new ActorRef(ActorKind.HOUSEHOLD, "hh-gov-" + govUnitId);
  }

  private static EconomyData baseEconomy() {
    return EconomyData.empty()
        .withCurrencies(Map.of(SILVER, new CurrencyDef("silver", 3, "银")))
        .withGovernments(governments(neutralCentral(), neutralProvince()));
  }

  private static Map<GovernmentId, Government> governments(Government... rows) {
    Map<GovernmentId, Government> map = new LinkedHashMap<>();
    for (Government row : rows) {
      map.put(row.id(), row);
    }
    return map;
  }

  private static SimulationState state(EconomyData data) {
    return new SimulationState(
        new StateMeta(REF, T7),
        Map.of("economy", new EconomySnapshot(REF, T7, data)),
        InMemoryInfoSystem.empty());
  }
}
