package io.mosire.simos.unit.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 六条编制命令 handler（阶段 10a/10b-i，控制方修订：编制字段在 {@code Unit.module}，故全走 {@code unit.*}）： {@code
 * unit.SetGovFormation} / {@code SetArmyFormation} / {@code SetGovPolicy} / {@code SetGovSuperior}
 * / {@code RecruitStaff} / {@code DismissStaff}。
 *
 * <p>★ 判据三块：① happy path 的 {@code Applied} + 应用后状态**逐值** + 变更集过线往返（{@code UnitCodec} 编解码后 {@code
 * apply} 仍重建 target）；② 每条具名拒都走 {@link HandlerOutcome.Rejected} 路径（不是抛异常），理由只断言关键片段、 名字点到具体
 * id/字段/数字，不整句复刻；③ 每条都钉 {@link CommandTargets#targetPaths}（只声明载荷点名的 unitId）与 {@code
 * apply(changeSet, base) == target}（铁律 5）。
 *
 * <p>★ 世界夹具 = {@link SpiFixture} 的走廊 + 本单位；单位侧同时备有 GOV / Army / 无编制三类，保证"已有 Army ⇒ 拒 Gov"这类
 * 互斥用例有真样本。
 */
class GovFormationCommandHandlersTest {

  private static final SetGovernmentFormationHandler SET_GOV = new SetGovernmentFormationHandler();
  private static final SetArmyFormationHandler SET_ARMY = new SetArmyFormationHandler();
  private static final SetGovPolicyHandler SET_POLICY = new SetGovPolicyHandler();
  private static final SetGovSuperiorHandler SET_SUPERIOR = new SetGovSuperiorHandler();
  private static final RecruitStaffHandler RECRUIT = new RecruitStaffHandler();
  private static final DismissStaffHandler DISMISS = new DismissStaffHandler();
  private static final UnitCodec CODEC = new UnitCodec();

  private static final UnitId GOV1 = new UnitId("g-1");
  private static final UnitId GOV2 = new UnitId("g-2");
  private static final UnitId ARMY = new UnitId("a-1");
  private static final UnitId PLAIN = new UnitId("u-plain");

  // ── type() / targetPaths() ─────────────────────────────────────

  @Test
  void typeNamesMatchTheCommandTable() {
    assertThat(SET_GOV.type()).isEqualTo("unit.SetGovFormation");
    assertThat(SET_ARMY.type()).isEqualTo("unit.SetArmyFormation");
    assertThat(SET_POLICY.type()).isEqualTo("unit.SetGovPolicy");
    assertThat(SET_SUPERIOR.type()).isEqualTo("unit.SetGovSuperior");
    assertThat(RECRUIT.type()).isEqualTo("unit.RecruitStaff");
    assertThat(DISMISS.type()).isEqualTo("unit.DismissStaff");
  }

  /** ★ 每条命令都只声明**载荷点名的 unitId**（命名空间内路径，不含命名空间名）。 */
  @Test
  void targetPathsOfAllSixCommandsNameThePayloadUnit() {
    String payload = "{\"unitId\":\"g-9\"}";
    for (CommandHandler handler :
        List.of(SET_GOV, SET_ARMY, SET_POLICY, SET_SUPERIOR, RECRUIT, DISMISS)) {
      assertThat(handler).isInstanceOf(CommandTargets.class);
      assertThat(((CommandTargets) handler).targetPaths(SpiFixture.MAP_ID, payload))
          .as("%s 只声明载荷点名的 unitId", handler.type())
          .containsExactly("g-9");
    }
  }

  /** 判不出目标的坏载荷必须抛（调用方 fail-closed），不能返回空表当"没有目标"。 */
  @Test
  void targetPathsRejectPayloadWithoutUnitId() {
    for (CommandHandler handler :
        List.of(SET_GOV, SET_ARMY, SET_POLICY, SET_SUPERIOR, RECRUIT, DISMISS)) {
      assertThatThrownBy(
              () ->
                  ((CommandTargets) handler)
                      .targetPaths(SpiFixture.MAP_ID, "{\"role\":\"SCRIBE\"}"))
          .as("%s：缺 unitId", handler.type())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unitId");
    }
  }

  // ── unit.SetGovFormation ───────────────────────────────────────

  @Test
  void setGovFormationCreatesTheFormationWithStaffPolicyAndSuperior() {
    UnitState target =
        applied(
            SET_GOV,
            "g-2",
            world(baseUnits()),
            "{\"unitId\":\"g-2\",\"level\":\"PROVINCE\",\"superiorGov\":\"g-1\","
                + "\"staff\":{\"SCRIBE\":3,\"YAMEN\":2},"
                + "\"policy\":{\"moneyPerStaffPerTick\":4,\"staffCap\":{\"SCRIBE\":40}}}");

    Unit unit = target.units().get(GOV2);
    GovernmentFormation gov = (GovernmentFormation) unit.module().orElseThrow();
    assertThat(new ArrayList<>(gov.staff().keySet()))
        .as("staff 词表按载荷序落成保序表")
        .containsExactly(StaffRole.SCRIBE, StaffRole.YAMEN);
    assertThat(gov.staff())
        .containsExactly(Map.entry(StaffRole.SCRIBE, 3L), Map.entry(StaffRole.YAMEN, 2L));
    assertThat(gov.policy().grainPerStaffPerTick())
        .as("policy 部分覆盖：没给的字段取 OfficePolicy.defaults()（每人每日 83 毫粮，不是 120 天口粮 10000）")
        .isEqualTo(EconomyVocabulary.dailyRationMilli(1L, 1L))
        .isEqualTo(83L)
        .isNotEqualTo(EconomyVocabulary.RATION_MILLI_PER_PERSON);
    assertThat(gov.policy().clothPerStaffPerCycle())
        .isEqualTo(EconomyVocabulary.CLOTH_MILLI_PER_PERSON);
    assertThat(gov.policy().moneyPerStaffPerTick()).as("给了 ⇒ 覆盖").isEqualTo(4L);
    assertThat(gov.policy().retirementPerStaff()).as("没给 ⇒ 默认 0").isZero();
    assertThat(gov.policy().staffCap()).containsExactly(Map.entry(StaffRole.SCRIBE, 40L));
    assertThat(gov.superiorGov()).contains(GOV1);
    assertThat(gov.level()).isEqualTo(GovernmentLevel.PROVINCE);
    assertThat(unit.name()).as("其余字段照常").isEqualTo("单位 g-2");
    assertThat(unit.position().valueAt(SpiFixture.T0)).contains(SpiFixture.H11);
  }

  @Test
  void setGovFormationReplacesAnExistingGovFormationWholesale() {
    GovernmentFormation old =
        new GovernmentFormation(
            orderedStaff(Map.entry(StaffRole.YAMEN, 9L)),
            Map.of(),
            new OfficePolicy(1L, 2L, 3L, 4L, orderedStaff(Map.entry(StaffRole.YAMEN, 7L))),
            Optional.of(GOV1),
            GovernmentLevel.PROVINCE,
            Map.of());
    UnitState base = stateWith(govUnit(GOV1, central()), govUnit(GOV2, old));

    UnitState target =
        applied(
            SET_GOV,
            "g-2",
            world(base),
            "{\"unitId\":\"g-2\",\"level\":\"CENTRAL\",\"staff\":{\"POST\":1}}");

    GovernmentFormation gov = (GovernmentFormation) target.units().get(GOV2).module().orElseThrow();
    assertThat(gov.staff())
        .as("同类型重复设置 = 整体替换：旧 YAMEN 不被合并保留")
        .containsExactly(Map.entry(StaffRole.POST, 1L));
    assertThat(gov.policy())
        .as("policy 缺省 = defaults()，不是保留旧 policy")
        .isEqualTo(OfficePolicy.defaults());
    assertThat(gov.superiorGov()).as("缺 superiorGov ⇒ 中央（empty）").isEmpty();
    assertThat(gov.level()).isEqualTo(GovernmentLevel.CENTRAL);
  }

  @Test
  void setGovFormationRejectsAUnitThatAlreadyHasArmyFormation() {
    UnitState base = stateWith(armyUnit(ARMY));
    String reason =
        reason(SET_GOV, "a-1", world(base), "{\"unitId\":\"a-1\",\"level\":\"CENTRAL\"}");

    assertThat(reason).as("一单位至多一个标签，且不静默替换").contains("ArmyFormation").contains("至多一个");
    assertThat(unitOf(base, ARMY).module().orElseThrow()).isInstanceOf(ArmyFormation.class);
  }

  /** ★ S3b：{@code householdPosts} 载荷落进编制，且政府家户同批进 {@code Unit.households}（唯一列表）。 */
  @Test
  void setGovFormationCarriesHouseholdPostsAndAddsGovernmentHousehold() {
    HouseholdId postHousehold = HouseholdId.parse("hh-a");
    UnitState seeded =
        UnitOperations.setUnitHouseholds(
            stateWith(govUnit(GOV1, central()), plainUnit(GOV2)), GOV2, List.of(postHousehold));

    UnitState target =
        applied(
            SET_GOV,
            "g-2",
            world(seeded),
            "{\"unitId\":\"g-2\",\"level\":\"CENTRAL\",\"householdPosts\":"
                + "[{\"household\":\"hh-a\",\"role\":\"SCRIBE\",\"level\":\"CENTRAL\",\"head\":true}]}");

    GovernmentFormation gov = (GovernmentFormation) target.units().get(GOV2).module().orElseThrow();
    GovernmentPostOfHousehold post =
        new GovernmentPostOfHousehold(
            postHousehold, StaffRole.SCRIBE, GovernmentLevel.CENTRAL, true);
    assertThat(gov.governmentPostsOfHousehold()).containsExactly(Map.entry(postHousehold, post));
    assertThat(target.units().get(GOV2).households())
        .as("唯一列表：领导配置家户 + 该 GOV 单位自己的政府家户")
        .containsExactly(postHousehold, GovernmentHouseholds.of(GOV2.value()));
  }

  /** ★ S3b 不变量：{@code householdPosts} 的键必须 ⊆ {@code Unit.households}（挂外部家户 = 配置与人口脱钩）。 */
  @Test
  void setGovFormationRejectsHouseholdPostsOutsideUnitHouseholds() {
    UnitState base = stateWith(govUnit(GOV1, central()), plainUnit(GOV2));

    String reason =
        reason(
            SET_GOV,
            "g-2",
            world(base),
            "{\"unitId\":\"g-2\",\"level\":\"CENTRAL\",\"householdPosts\":"
                + "[{\"household\":\"hh-ghost\",\"role\":\"SCRIBE\",\"level\":\"CENTRAL\"}]}");

    assertThat(reason)
        .as("拒因必须点名配置键与所在列表")
        .contains("householdPosts")
        .contains("households")
        .contains("hh-ghost");
  }

  /** ★ S3b 兼容口径：载荷缺 {@code householdPosts} ⇒ 保持既有领导配置（不是清空）。 */
  @Test
  void setGovFormationWithoutHouseholdPostsKeepsExistingPosts() {
    HouseholdId postHousehold = HouseholdId.parse("hh-a");
    GovernmentPostOfHousehold post =
        new GovernmentPostOfHousehold(
            postHousehold, StaffRole.SCRIBE, GovernmentLevel.CENTRAL, true);
    UnitState base =
        stateWith(
            govUnit(GOV1, central()), govUnit(GOV2, govWithPosts(Map.of(postHousehold, post))));

    UnitState target =
        applied(
            SET_GOV,
            "g-2",
            world(base),
            "{\"unitId\":\"g-2\",\"level\":\"CENTRAL\",\"staff\":{\"POST\":1}}");

    GovernmentFormation gov = (GovernmentFormation) target.units().get(GOV2).module().orElseThrow();
    assertThat(gov.governmentPostsOfHousehold())
        .as("缺 householdPosts ⇒ 保持既有配置")
        .containsExactly(Map.entry(postHousehold, post));
  }

  /** ★ 2026-10-09 唯一列表裁定：旧线格式键 {@code households} 必须具名拒（不是静默忽略）。 */
  @Test
  void setGovFormationRejectsRetiredHouseholdsKey() {
    UnitState base = stateWith(govUnit(GOV1, central()), plainUnit(GOV2));

    String reason =
        reason(
            SET_GOV,
            "g-2",
            world(base),
            "{\"unitId\":\"g-2\",\"level\":\"CENTRAL\",\"households\":[\"hh-a\"]}");

    assertThat(reason)
        .as("旧键必须指路唯一整体替换口")
        .contains("不再接收 households")
        .contains("unit.SetUnitHouseholds");
  }

  @Test
  void setGovFormationRejectsMissingNonGovAndSelfSuperior() {
    UnitState base = stateWith(govUnit(GOV1, central()), plainUnit(GOV2), plainUnit(PLAIN));

    assertThat(
            reason(
                SET_GOV,
                "g-2",
                world(base),
                "{\"unitId\":\"g-2\",\"level\":\"PROVINCE\",\"superiorGov\":\"ghost\"}"))
        .as("上级查无此人 ⇒ 具名拒")
        .contains("superiorGov")
        .contains("不存在")
        .contains("ghost");
    assertThat(
            reason(
                SET_GOV,
                "g-2",
                world(base),
                "{\"unitId\":\"g-2\",\"level\":\"PROVINCE\",\"superiorGov\":\"u-plain\"}"))
        .as("上级不是 GOV ⇒ 另一条纠正方向")
        .contains("superiorGov")
        .contains("没有 GovernmentFormation");
    assertThat(
            reason(
                SET_GOV,
                "g-1",
                world(base),
                "{\"unitId\":\"g-1\",\"level\":\"CENTRAL\",\"superiorGov\":\"g-1\"}"))
        .as("上级不得指向自身")
        .contains("不得指向自身");
  }

  @Test
  void setGovFormationRejectsUnknownUnitLevelAndStaffRole() {
    UnitState base = stateWith(plainUnit(GOV2));

    assertThat(
            reason(SET_GOV, "ghost", world(base), "{\"unitId\":\"ghost\",\"level\":\"CENTRAL\"}"))
        .contains("单位不存在")
        .contains("ghost");
    assertThat(reason(SET_GOV, "g-2", world(base), "{\"unitId\":\"g-2\",\"level\":\"KINGDOM\"}"))
        .as("未知层级点名合法词表")
        .contains("CENTRAL|PROVINCE");
    assertThat(
            reason(
                SET_GOV,
                "g-2",
                world(base),
                "{\"unitId\":\"g-2\",\"level\":\"CENTRAL\",\"staff\":{\"CLERK\":1}}"))
        .as("staff 词表外的角色具名拒，不静默丢条目")
        .contains("CLERK")
        .contains("SCRIBE / YAMEN / POST");
  }

  // ── unit.SetArmyFormation ──────────────────────────────────────

  @Test
  void setArmyFormationCreatesTheFormationWithAndWithoutMasterGov() {
    UnitState base = stateWith(govUnit(GOV1, central()), plainUnit(GOV2));

    UnitState withMaster =
        applied(
            SET_ARMY,
            "g-2",
            world(base),
            "{\"unitId\":\"g-2\",\"masterGov\":\"g-1\",\"role\":\"garrison\"}");
    ArmyFormation army = (ArmyFormation) withMaster.units().get(GOV2).module().orElseThrow();
    assertThat(army.masterGov()).contains(GOV1);
    assertThat(army.role()).isEqualTo("garrison");
    assertThat(withMaster.units().get(GOV2).name()).isEqualTo("单位 g-2");

    UnitState withoutMaster =
        applied(SET_ARMY, "g-2", world(base), "{\"unitId\":\"g-2\",\"role\":\"militia\"}");
    ArmyFormation noMaster = (ArmyFormation) withoutMaster.units().get(GOV2).module().orElseThrow();
    assertThat(noMaster.masterGov()).as("masterGov 可空 = 未认主子").isEmpty();
    assertThat(noMaster.role()).isEqualTo("militia");
  }

  @Test
  void setArmyFormationReplacesAnExistingArmyFormationWholesale() {
    UnitState base =
        stateWith(govUnit(GOV1, central()), armyUnit(GOV2, Optional.of(GOV1), "old-role"));

    UnitState target =
        applied(
            SET_ARMY,
            "g-2",
            world(base),
            "{\"unitId\":\"g-2\",\"masterGov\":\"g-1\",\"role\":\"new-role\"}");

    ArmyFormation army = (ArmyFormation) target.units().get(GOV2).module().orElseThrow();
    assertThat(army.role()).isEqualTo("new-role");
    assertThat(army.masterGov()).contains(GOV1);
  }

  @Test
  void setArmyFormationRejectsAUnitThatAlreadyHasGovFormation() {
    UnitState base = stateWith(govUnit(GOV1, central()));
    String reason =
        reason(SET_ARMY, "g-1", world(base), "{\"unitId\":\"g-1\",\"role\":\"garrison\"}");

    assertThat(reason).as("一单位至多一个标签，且不静默替换").contains("GovernmentFormation").contains("至多一个");
  }

  @Test
  void setArmyFormationRejectsMissingNonGovMasterAndBlankRole() {
    UnitState base = stateWith(govUnit(GOV1, central()), plainUnit(PLAIN));

    assertThat(
            reason(
                SET_ARMY,
                "u-plain",
                world(base),
                "{\"unitId\":\"u-plain\",\"masterGov\":\"ghost\",\"role\":\"garrison\"}"))
        .as("认主子必须存在")
        .contains("masterGov")
        .contains("不存在")
        .contains("ghost");
    assertThat(
            reason(
                SET_ARMY,
                "u-plain",
                world(stateWith(plainUnit(GOV1), plainUnit(PLAIN))),
                "{\"unitId\":\"u-plain\",\"masterGov\":\"g-1\",\"role\":\"garrison\"}"))
        .as("masterGov 存在但不是 GOV ⇒ 另一条纠正方向")
        .contains("masterGov")
        .contains("没有 GovernmentFormation");
    assertThat(
            reason(
                SET_ARMY, "u-plain", world(base), "{\"unitId\":\"u-plain\",\"role\":\"  \\t \"}"))
        .as("role 空白拒")
        .contains("role 不得为空白");
  }

  // ── unit.SetGovPolicy ──────────────────────────────────────────

  @Test
  void setGovPolicyPartiallyOverridesAndClearsStaffCap() {
    UnitState base = stateWith(govUnit(GOV1, govWithPolicy()));

    UnitState partial =
        applied(
            SET_POLICY,
            "g-1",
            world(base),
            "{\"unitId\":\"g-1\",\"moneyPerStaffPerTick\":9,\"staffCap\":{\"SCRIBE\":40}}");
    GovernmentFormation gov =
        (GovernmentFormation) partial.units().get(GOV1).module().orElseThrow();
    assertThat(gov.policy().moneyPerStaffPerTick()).as("给了 ⇒ 覆盖").isEqualTo(9L);
    assertThat(gov.policy().grainPerStaffPerTick()).as("没给 ⇒ 保持").isEqualTo(101L);
    assertThat(gov.policy().clothPerStaffPerCycle()).as("没给 ⇒ 保持").isEqualTo(202L);
    assertThat(gov.policy().retirementPerStaff()).as("没给 ⇒ 保持").isEqualTo(7L);
    assertThat(gov.policy().staffCap())
        .as("staffCap 给了 ⇒ 整体替换（旧 YAMEN 不保留）")
        .containsExactly(Map.entry(StaffRole.SCRIBE, 40L));

    UnitState cleared =
        applied(SET_POLICY, "g-1", world(base), "{\"unitId\":\"g-1\",\"staffCap\":{}}");
    GovernmentFormation clearedGov =
        (GovernmentFormation) cleared.units().get(GOV1).module().orElseThrow();
    assertThat(clearedGov.policy().staffCap()).as("空表 = 清空上限").isEmpty();
    assertThat(clearedGov.policy().grainPerStaffPerTick()).as("清上限不得动别的字段").isEqualTo(101L);
  }

  @Test
  void setGovPolicyRejectsNegativeAmountsAndNonGovUnits() {
    UnitState base = stateWith(govUnit(GOV1, govWithPolicy()), plainUnit(GOV2), armyUnit(ARMY));

    assertThat(
            reason(
                SET_POLICY, "g-1", world(base), "{\"unitId\":\"g-1\",\"grainPerStaffPerTick\":-1}"))
        .contains("grainPerStaffPerTick")
        .contains("≥ 0");
    assertThat(
            reason(
                SET_POLICY, "g-1", world(base), "{\"unitId\":\"g-1\",\"retirementPerStaff\":-5}"))
        .contains("retirementPerStaff")
        .contains("≥ 0");
    assertThat(
            reason(
                SET_POLICY,
                "g-1",
                world(base),
                "{\"unitId\":\"g-1\",\"staffCap\":{\"SCRIBE\":-2}}"))
        .contains("staffCap")
        .contains("SCRIBE=-2");
    assertThat(
            reason(
                SET_POLICY, "g-2", world(base), "{\"unitId\":\"g-2\",\"moneyPerStaffPerTick\":1}"))
        .as("无编制 ⇒ 指路 SetGovFormation")
        .contains("没有 GovernmentFormation")
        .contains("unit.SetGovFormation");
    assertThat(
            reason(
                SET_POLICY, "a-1", world(base), "{\"unitId\":\"a-1\",\"moneyPerStaffPerTick\":1}"))
        .as("Army 编制 ⇒ 另一条拒因")
        .contains("ArmyFormation");
    assertThat(
            reason(
                SET_POLICY,
                "ghost",
                world(base),
                "{\"unitId\":\"ghost\",\"moneyPerStaffPerTick\":1}"))
        .contains("单位不存在");
  }

  // ── unit.SetGovSuperior ────────────────────────────────────────

  @Test
  void setGovSuperiorSetsAndClearsTheSuperior() {
    UnitState base = stateWith(govUnit(GOV1, central()), govUnit(GOV2, province(Optional.empty())));

    UnitState linked =
        applied(SET_SUPERIOR, "g-2", world(base), "{\"unitId\":\"g-2\",\"superiorGov\":\"g-1\"}");
    assertThat(
            ((GovernmentFormation) linked.units().get(GOV2).module().orElseThrow()).superiorGov())
        .contains(GOV1);

    UnitState cleared =
        applied(SET_SUPERIOR, "g-2", world(linked), "{\"unitId\":\"g-2\",\"superiorGov\":null}");
    assertThat(
            ((GovernmentFormation) cleared.units().get(GOV2).module().orElseThrow()).superiorGov())
        .as("缺省 / null = 中央（无上级）")
        .isEmpty();
  }

  @Test
  void setGovSuperiorRejectsDirectAndIndirectCyclesAndSelf() {
    // 直连环：g-1 → g-2，再把 g-2 的上级设成 g-1。
    UnitState direct =
        stateWith(
            govUnit(GOV1, province(Optional.of(GOV2))), govUnit(GOV2, province(Optional.empty())));
    assertThat(
            reason(
                SET_SUPERIOR, "g-2", world(direct), "{\"unitId\":\"g-2\",\"superiorGov\":\"g-1\"}"))
        .as("直接成环")
        .contains("成环");

    // 间接环：g-1 → g-2 → g-3，再把 g-3 的上级设成 g-1（上溯会回到自己）。
    UnitState indirect =
        stateWith(
            govUnit(GOV1, province(Optional.of(GOV2))),
            govUnit(GOV2, province(Optional.of(new UnitId("g-3")))),
            govUnit(new UnitId("g-3"), province(Optional.empty())));
    assertThat(
            reason(
                SET_SUPERIOR,
                "g-3",
                world(indirect),
                "{\"unitId\":\"g-3\",\"superiorGov\":\"g-1\"}"))
        .as("间接成环（沿 superiorGov 上溯命中自己）")
        .contains("成环");

    UnitState single = stateWith(govUnit(GOV1, central()));
    assertThat(
            reason(
                SET_SUPERIOR, "g-1", world(single), "{\"unitId\":\"g-1\",\"superiorGov\":\"g-1\"}"))
        .as("自指")
        .contains("不得指向自身");
  }

  @Test
  void setGovSuperiorRejectsNonGovSuperiorAndUnknownUnit() {
    UnitState base = stateWith(govUnit(GOV1, central()), plainUnit(PLAIN));
    assertThat(
            reason(
                SET_SUPERIOR,
                "g-1",
                world(base),
                "{\"unitId\":\"g-1\",\"superiorGov\":\"u-plain\"}"))
        .contains("没有 GovernmentFormation");
    assertThat(
            reason(
                SET_SUPERIOR,
                "ghost",
                world(base),
                "{\"unitId\":\"ghost\",\"superiorGov\":\"g-1\"}"))
        .contains("单位不存在");
  }

  /** ★ **环检测的 64 层边界**：从新上级上溯 64 个 GOV 允许（走到链尾自然结束）；第 65 个触发具名拒。 两侧都跑，防"永远拒绝"或"永远放行"的坏实现。 */
  @Test
  void setGovSuperiorAcceptsA64NodeChainAndRejectsThe65thLayer() {
    // g-0 是目标；从 g-1 上溯恰 64 个节点（g-1…g-64，g-64 无上级）⇒ 合法。
    SimulationState atBoundary = world(govChain(64));
    UnitState accepted =
        applied(SET_SUPERIOR, "g-0", atBoundary, "{\"unitId\":\"g-0\",\"superiorGov\":\"g-1\"}");
    assertThat(
            ((GovernmentFormation) accepted.units().get(new UnitId("g-0")).module().orElseThrow())
                .superiorGov())
        .contains(new UnitId("g-1"));

    // 65 个节点（g-1…g-65）⇒ 第 65 层处 depth 已到 64，具名拒。
    SimulationState overBoundary = world(govChain(65));
    assertThat(
            reason(
                SET_SUPERIOR, "g-0", overBoundary, "{\"unitId\":\"g-0\",\"superiorGov\":\"g-1\"}"))
        .as("链超过 64 层 ⇒ 拒，且理由带数字")
        .contains("64");
  }

  // ── unit.RecruitStaff ──────────────────────────────────────────

  @Test
  void recruitStaffAddsToRosterKeepingOrderAndAcceptsSourceShapes() {
    UnitState base =
        stateWith(
            govUnit(
                GOV1,
                new GovernmentFormation(
                    orderedStaff(Map.entry(StaffRole.YAMEN, 1L), Map.entry(StaffRole.POST, 2L)),
                    Map.of(),
                    new OfficePolicy(0L, 0L, 0L, 0L, orderedStaff(Map.entry(StaffRole.SCRIBE, 5L))),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    Map.of())));

    UnitState target =
        applied(
            RECRUIT,
            "g-1",
            world(base),
            "{\"unitId\":\"g-1\",\"role\":\"SCRIBE\",\"count\":3,"
                + "\"sources\":[{\"kind\":\"social_group\",\"id\":\"g-9\",\"count\":2},"
                + "{\"kind\":\"unit\",\"id\":\"u-9\",\"count\":1}]}");

    GovernmentFormation gov = (GovernmentFormation) target.units().get(GOV1).module().orElseThrow();
    assertThat(new ArrayList<>(gov.staff().keySet()))
        .as("既有键保持原位、新角色追加在末尾")
        .containsExactly(StaffRole.YAMEN, StaffRole.POST, StaffRole.SCRIBE);
    assertThat(gov.staff())
        .containsExactly(
            Map.entry(StaffRole.YAMEN, 1L),
            Map.entry(StaffRole.POST, 2L),
            Map.entry(StaffRole.SCRIBE, 3L));
    assertThat(gov.policy().staffCap()).containsEntry(StaffRole.SCRIBE, 5L);

    // sources 缺省 / null 都合法（只校形状，不解析来源域对象）。
    UnitState noSources =
        applied(
            RECRUIT, "g-1", world(base), "{\"unitId\":\"g-1\",\"role\":\"SCRIBE\",\"count\":1}");
    assertThat(staffOf(noSources, GOV1)).containsEntry(StaffRole.SCRIBE, 1L);
    UnitState nullSources =
        applied(
            RECRUIT,
            "g-1",
            world(base),
            "{\"unitId\":\"g-1\",\"role\":\"SCRIBE\",\"count\":1,\"sources\":null}");
    assertThat(staffOf(nullSources, GOV1)).containsEntry(StaffRole.SCRIBE, 1L);
  }

  @Test
  void recruitStaffRejectsNonArrayAndNonObjectSources() {
    UnitState base = stateWith(govUnit(GOV1, province(Optional.empty())));
    assertThat(
            reason(
                RECRUIT,
                "g-1",
                world(base),
                "{\"unitId\":\"g-1\",\"role\":\"SCRIBE\",\"count\":1,\"sources\":\"g-9\"}"))
        .as("sources 非数组")
        .contains("sources")
        .contains("数组");
    assertThat(
            reason(
                RECRUIT,
                "g-1",
                world(base),
                "{\"unitId\":\"g-1\",\"role\":\"SCRIBE\",\"count\":1,\"sources\":[42]}"))
        .as("sources 元素非对象")
        .contains("sources")
        .contains("对象");
  }

  @Test
  void recruitStaffRejectsCapExceededWithNumbersInReason() {
    UnitState base =
        stateWith(
            govUnit(
                GOV1,
                new GovernmentFormation(
                    orderedStaff(Map.entry(StaffRole.SCRIBE, 3L)),
                    Map.of(),
                    new OfficePolicy(0L, 0L, 0L, 0L, orderedStaff(Map.entry(StaffRole.SCRIBE, 5L))),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    Map.of())));

    String reason =
        reason(RECRUIT, "g-1", world(base), "{\"unitId\":\"g-1\",\"role\":\"SCRIBE\",\"count\":3}");

    assertThat(reason)
        .as("超限拒必须带现有 / 上限 / 请求三个数字，不截断")
        .contains("SCRIBE")
        .contains("现有 3")
        .contains("请求 3")
        .contains("staffCap 5");
    assertThat(staffOf(base, GOV1)).as("拒绝 ⇒ 原状态一字不动").containsEntry(StaffRole.SCRIBE, 3L);

    // cap 只对表内角色生效：表里没有 YAMEN ⇒ YAMEN 不设限。
    UnitState recruited =
        applied(
            RECRUIT, "g-1", world(base), "{\"unitId\":\"g-1\",\"role\":\"YAMEN\",\"count\":100}");
    assertThat(staffOf(recruited, GOV1)).containsEntry(StaffRole.YAMEN, 100L);
  }

  @Test
  void recruitStaffRejectsBadCountUnknownRoleAndNonGovUnits() {
    UnitState base =
        stateWith(govUnit(GOV1, province(Optional.empty())), plainUnit(GOV2), armyUnit(ARMY));

    assertThat(
            reason(
                RECRUIT,
                "g-1",
                world(base),
                "{\"unitId\":\"g-1\",\"role\":\"SCRIBE\",\"count\":0}"))
        .as("count < 1")
        .contains("count")
        .contains("≥ 1");
    assertThat(
            reason(
                RECRUIT, "g-1", world(base), "{\"unitId\":\"g-1\",\"role\":\"CLERK\",\"count\":1}"))
        .as("角色词表外拒")
        .contains("SCRIBE|YAMEN|POST");
    assertThat(
            reason(
                RECRUIT,
                "g-2",
                world(base),
                "{\"unitId\":\"g-2\",\"role\":\"SCRIBE\",\"count\":1}"))
        .as("无编制")
        .contains("没有 GovernmentFormation");
    assertThat(
            reason(
                RECRUIT,
                "a-1",
                world(base),
                "{\"unitId\":\"a-1\",\"role\":\"SCRIBE\",\"count\":1}"))
        .as("Army 编制")
        .contains("ArmyFormation");
    assertThat(
            reason(
                RECRUIT,
                "ghost",
                world(base),
                "{\"unitId\":\"ghost\",\"role\":\"SCRIBE\",\"count\":1}"))
        .contains("单位不存在");
  }

  // ── unit.DismissStaff ──────────────────────────────────────────

  @Test
  void dismissStaffKeepsZeroKeyAndTheRosterOrder() {
    UnitState base =
        stateWith(
            govUnit(
                GOV1,
                new GovernmentFormation(
                    orderedStaff(
                        Map.entry(StaffRole.SCRIBE, 5L),
                        Map.entry(StaffRole.YAMEN, 3L),
                        Map.entry(StaffRole.POST, 2L)),
                    Map.of(),
                    OfficePolicy.defaults(),
                    Optional.empty(),
                    GovernmentLevel.CENTRAL,
                    Map.of())));

    UnitState target =
        applied(DISMISS, "g-1", world(base), "{\"unitId\":\"g-1\",\"role\":\"YAMEN\",\"count\":3}");
    GovernmentFormation gov = (GovernmentFormation) target.units().get(GOV1).module().orElseThrow();

    assertThat(new ArrayList<>(gov.staff().keySet()))
        .as("减到 0 保留键、键序不变")
        .containsExactly(StaffRole.SCRIBE, StaffRole.YAMEN, StaffRole.POST);
    assertThat(gov.staff())
        .containsExactly(
            Map.entry(StaffRole.SCRIBE, 5L),
            Map.entry(StaffRole.YAMEN, 0L),
            Map.entry(StaffRole.POST, 2L));
    assertThat(gov.policy()).as("离编不得动 policy").isEqualTo(OfficePolicy.defaults());
  }

  @Test
  void dismissStaffRejectsInsufficientCountBadCountUnknownRoleAndNonGovUnits() {
    UnitState base =
        stateWith(govUnit(GOV1, provinceWithStaff(1L)), plainUnit(GOV2), armyUnit(ARMY));

    assertThat(
            reason(
                DISMISS,
                "g-1",
                world(base),
                "{\"unitId\":\"g-1\",\"role\":\"SCRIBE\",\"count\":2}"))
        .as("人数不足必须带现有与请求数字")
        .contains("现有 1")
        .contains("请求 2");
    assertThat(
            reason(
                DISMISS,
                "g-1",
                world(base),
                "{\"unitId\":\"g-1\",\"role\":\"SCRIBE\",\"count\":0}"))
        .as("count < 1")
        .contains("count")
        .contains("≥ 1");
    assertThat(
            reason(
                DISMISS, "g-1", world(base), "{\"unitId\":\"g-1\",\"role\":\"CLERK\",\"count\":1}"))
        .as("角色词表外拒")
        .contains("SCRIBE|YAMEN|POST");
    assertThat(
            reason(
                DISMISS,
                "g-2",
                world(base),
                "{\"unitId\":\"g-2\",\"role\":\"SCRIBE\",\"count\":1}"))
        .contains("没有 GovernmentFormation");
    assertThat(
            reason(
                DISMISS,
                "a-1",
                world(base),
                "{\"unitId\":\"a-1\",\"role\":\"SCRIBE\",\"count\":1}"))
        .contains("ArmyFormation");
    assertThat(
            reason(
                DISMISS,
                "ghost",
                world(base),
                "{\"unitId\":\"ghost\",\"role\":\"SCRIBE\",\"count\":1}"))
        .contains("单位不存在");
  }

  // ── 世界/单位夹具 ──────────────────────────────────────────────

  /** 四件单位：中央 GOV、无编制、Army、无编制旁观者。 */
  private static UnitState baseUnits() {
    return stateWith(govUnit(GOV1, central()), plainUnit(GOV2), armyUnit(ARMY), plainUnit(PLAIN));
  }

  private static SimulationState world(UnitState base) {
    return SpiFixture.state(SpiFixture.map(), base);
  }

  private static UnitState unitSlice(SimulationState world) {
    return ((UnitSnapshot) world.module("unit").orElseThrow()).state();
  }

  private static UnitState applied(
      CommandHandler handler, String unitId, SimulationState world, String payload) {
    UnitState base = unitSlice(world);
    UnitChangeSet changeSet = changeSetOf(handler, unitId, world, payload);
    UnitState target = UnitChangeSet.apply(changeSet, base);

    assertThat(UnitChangeSet.apply(UnitChangeSet.between(base, target), base))
        .as("%s：between 往返重建 = target（铁律 5）", handler.type())
        .isEqualTo(target);
    UnitChangeSet wire = (UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(changeSet));
    assertThat(wire).as("%s：变更集过线逐值相等", handler.type()).isEqualTo(changeSet);
    assertThat(UnitChangeSet.apply(wire, base))
        .as("%s：过线后 apply 仍重建 = target", handler.type())
        .isEqualTo(target);
    return target;
  }

  private static UnitChangeSet changeSetOf(
      CommandHandler handler, String unitId, SimulationState world, String payload) {
    assertTargetPaths(handler, unitId, payload);
    HandlerOutcome outcome = handler.handle(world, payload);
    assertThat(outcome).as("期望 Applied 而不是 %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
  }

  private static String reason(
      CommandHandler handler, String unitId, SimulationState world, String payload) {
    assertTargetPaths(handler, unitId, payload);
    HandlerOutcome outcome = handler.handle(world, payload);
    assertThat(outcome)
        .as("期望 Rejected 而不是 %s", outcome)
        .isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  private static void assertTargetPaths(CommandHandler handler, String unitId, String payload) {
    assertThat(handler).isInstanceOf(CommandTargets.class);
    assertThat(((CommandTargets) handler).targetPaths(SpiFixture.MAP_ID, payload))
        .as("%s：targetPaths 只声明载荷点名的 unitId", handler.type())
        .containsExactly(unitId);
  }

  private static Unit unitOf(UnitState state, UnitId id) {
    return state.units().get(id);
  }

  private static Map<StaffRole, Long> staffOf(UnitState state, UnitId id) {
    return ((GovernmentFormation) state.units().get(id).module().orElseThrow()).staff();
  }

  private static GovernmentFormation central() {
    return new GovernmentFormation(
        Map.of(),
        Map.of(),
        OfficePolicy.defaults(),
        Optional.empty(),
        GovernmentLevel.CENTRAL,
        Map.of());
  }

  private static GovernmentFormation province(Optional<UnitId> superior) {
    return new GovernmentFormation(
        Map.of(), Map.of(), OfficePolicy.defaults(), superior, GovernmentLevel.PROVINCE, Map.of());
  }

  private static GovernmentFormation provinceWithStaff(long scribe) {
    return new GovernmentFormation(
        orderedStaff(Map.entry(StaffRole.SCRIBE, scribe)),
        Map.of(),
        OfficePolicy.defaults(),
        Optional.empty(),
        GovernmentLevel.PROVINCE,
        Map.of());
  }

  private static GovernmentFormation govWithPolicy() {
    return new GovernmentFormation(
        orderedStaff(Map.entry(StaffRole.SCRIBE, 4L), Map.entry(StaffRole.YAMEN, 2L)),
        Map.of(),
        new OfficePolicy(
            101L,
            202L,
            3L,
            7L,
            orderedStaff(Map.entry(StaffRole.SCRIBE, 6L), Map.entry(StaffRole.YAMEN, 8L))),
        Optional.empty(),
        GovernmentLevel.CENTRAL,
        Map.of());
  }

  /** 带领导家户配置的 GOV 编制（配置键会被 {@link #householdsFor} 编进 unit households）。 */
  private static GovernmentFormation govWithPosts(
      Map<HouseholdId, GovernmentPostOfHousehold> governmentPostsOfHousehold) {
    return new GovernmentFormation(
        Map.of(),
        governmentPostsOfHousehold,
        OfficePolicy.defaults(),
        Optional.empty(),
        GovernmentLevel.CENTRAL,
        Map.of());
  }

  private static Unit govUnit(UnitId id, GovernmentFormation gov) {
    return unit(id, Optional.of(gov));
  }

  private static Unit armyUnit(UnitId id) {
    return armyUnit(id, Optional.empty(), "garrison");
  }

  private static Unit armyUnit(UnitId id, Optional<UnitId> masterGov, String role) {
    return unit(id, Optional.of(new ArmyFormation(masterGov, role)));
  }

  private static Unit plainUnit(UnitId id) {
    return unit(id, Optional.empty());
  }

  private static Unit unit(UnitId id, Optional<UnitModule> module) {
    return new Unit(
        id,
        "单位 " + id.value(),
        new SegmentedSeries<>(
            List.of(new Segment<>(SpiFixture.T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(SpiFixture.T0, Optional.of(SpiFixture.H11))), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(SpiFixture.T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(SpiFixture.T0, Optional.<RelativeOffset>empty())),
            List.of(),
            null),
        Optional.empty(),
        3,
        Optional.empty(),
        module,
        Map.of(),
        householdsFor(id, module));
  }

  /**
   * 单位 households（S3b 唯一列表裁定）：GOV 编制 ⇒ 恰含自己的政府家户 {@code hh-gov-<unitId>}，领导家户配置的键也在列表里； 其余编制 ⇒
   * 空表。夹具自动补齐，好让各用例把靶子放在命令面上。
   */
  private static List<HouseholdId> householdsFor(UnitId id, Optional<UnitModule> module) {
    if (!(module.orElse(null) instanceof GovernmentFormation governmentFormation)) {
      return List.of();
    }
    List<HouseholdId> households = new ArrayList<>();
    households.add(GovernmentHouseholds.of(id.value()));
    for (HouseholdId household : governmentFormation.governmentPostsOfHousehold().keySet()) {
      if (!households.contains(household)) {
        households.add(household);
      }
    }
    return households;
  }

  private static UnitState stateWith(Unit... units) {
    return SpiFixture.unitState(units);
  }

  /**
   * 一条 GOV 上级链：{@code g-0} 是目标（无上级），{@code g-1…g-N} 依次向上，{@code g-N} 无上级。
   *
   * <p>直接拼 state（不经命令）：本用例量的是 handler 的环检测边界，夹具用 65 条命令会把被测点埋在 O(N²) 的循环里。
   */
  private static UnitState govChain(int ancestorCount) {
    Unit[] units = new Unit[ancestorCount + 1];
    units[0] = govUnit(new UnitId("g-0"), province(Optional.empty()));
    for (int i = 1; i <= ancestorCount; i++) {
      Optional<UnitId> superior =
          i < ancestorCount ? Optional.of(new UnitId("g-" + (i + 1))) : Optional.empty();
      units[i] = govUnit(new UnitId("g-" + i), province(superior));
    }
    return stateWith(units);
  }

  @SafeVarargs
  private static Map<StaffRole, Long> orderedStaff(Map.Entry<StaffRole, Long>... entries) {
    Map<StaffRole, Long> staff = new LinkedHashMap<>();
    for (Map.Entry<StaffRole, Long> entry : entries) {
      staff.put(entry.getKey(), entry.getValue());
    }
    return staff;
  }
}
