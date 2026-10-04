package io.mosire.simos.social.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.ChangeSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>S3a 正式验收：social 家户命令 handler</b>（架构 §4.1/§7；S3a spec §4.1）。
 *
 * <p>七条命令各验三件事：<b>提交后状态正确</b>（走真 handler + 真 {@code SocialChangeSet.apply}）、<b>坏载荷具名
 * Rejected</b>（零改动）、以及 <b>ChangeSet 往返</b>（{@code between(base, next)} 再 apply 逐值重建 next ——
 * 命令重放/回放的同一判据）。
 */
class HouseholdCommandHandlersTest {

  private static final CreateHouseholdHandler CREATE = new CreateHouseholdHandler();
  private static final SetHouseholdLocationHandler SET_LOCATION = new SetHouseholdLocationHandler();
  private static final AddHouseholdMembersHandler ADD = new AddHouseholdMembersHandler();
  private static final RemoveHouseholdMembersHandler REMOVE = new RemoveHouseholdMembersHandler();
  private static final TransferHouseholdMembersHandler TRANSFER = new TransferHouseholdMembersHandler();
  private static final SetHouseholdVitalRatesHandler SET_RATES = new SetHouseholdVitalRatesHandler();
  private static final AdjustHouseholdPopulationHandler ADJUST = new AdjustHouseholdPopulationHandler();

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HouseholdId HH_MAIN = HouseholdId.parse("hh-main");
  private static final HouseholdId HH_OTHER = HouseholdId.parse("hh-other");
  private static final PeopleLotId LOT_MAIN = PeopleLotId.parse("lot-main");
  private static final SocialCodec CODEC = new SocialCodec();

  @Test
  void typeNamesAreTheOnesTheArchitectureNames() {
    assertThat(CREATE.type()).isEqualTo("social.CreateHousehold");
    assertThat(SET_LOCATION.type()).isEqualTo("social.SetHouseholdLocation");
    assertThat(ADD.type()).isEqualTo("social.AddHouseholdMembers");
    assertThat(REMOVE.type()).isEqualTo("social.RemoveHouseholdMembers");
    assertThat(TRANSFER.type()).isEqualTo("social.TransferHouseholdMembers");
    assertThat(SET_RATES.type()).isEqualTo("social.SetHouseholdVitalRates");
    assertThat(ADJUST.type()).isEqualTo("social.AdjustHouseholdPopulation");
  }

  // ── social.CreateHousehold ────────────────────────────────────────────

  @Test
  void createHouseholdAppliesAndRejectsBadPayload() {
    SocialData base = SocialData.empty();
    HandlerOutcome.Applied applied =
        applied(
            CREATE,
            base,
            "{\"householdId\":\"hh-main\","
                + "\"location\":{\"type\":\"HEX\",\"hex\":{\"q\":1,\"r\":1}},"
                + "\"profile\":{\"name\":\"城东民户\",\"description\":\"d\",\"metadata\":{\"k\":\"v\"}},"
                + "\"vitalRates\":[{\"bracketId\":\"0-14\",\"sex\":\"MALE\","
                + "\"birthRatePerMillePerTick\":0,\"deathRatePerMillePerTick\":5}],"
                + "\"reason\":\"seed\"}");

    SocialData after = roundTrip(applied, base);
    Household household = after.requireHousehold(HH_MAIN);
    assertThat(household.location()).isEqualTo(new HouseholdLocation.Hex(H11));
    assertThat(household.profile().name()).isEqualTo("城东民户");
    assertThat(household.profile().metadata()).containsEntry("k", "v");
    assertThat(household.vitalRates().find("0-14", Sex.MALE))
        .as("vitalRates 进了家户本体")
        .isPresent();

    HandlerOutcome.Rejected rejected =
        rejected(CREATE, base, "{\"householdId\":\"hh-main\",\"location\":{\"type\":\"HEX\","
            + "\"hex\":{\"q\":1,\"r\":1}},\"profile\":{},\"reason\":\"seed\"}");
    assertThat(rejected.reason()).isNotBlank().contains("name");
  }

  // ── social.SetHouseholdLocation ───────────────────────────────────────

  @Test
  void setHouseholdLocationAppliesHexToUnitAndRejectsUnknownHousehold() {
    SocialData base = baseWithHouseholdAndMembers();
    HandlerOutcome.Applied applied =
        applied(
            SET_LOCATION,
            base,
            "{\"householdId\":\"hh-main\",\"location\":{\"type\":\"UNIT\",\"unitId\":\"u-1\"},"
                + "\"reason\":\"assign\"}");

    SocialData after = roundTrip(applied, base);
    assertThat(after.requireHousehold(HH_MAIN).location())
        .isEqualTo(new HouseholdLocation.Unit("u-1"));
    assertThat(after.requireHousehold(HH_MAIN).memberLots())
        .as("改位置不得动成员关系")
        .containsExactly(LOT_MAIN);

    HandlerOutcome.Rejected rejected =
        rejected(
            SET_LOCATION,
            base,
            "{\"householdId\":\"hh-missing\",\"location\":{\"type\":\"UNIT\",\"unitId\":\"u-1\"},"
                + "\"reason\":\"assign\"}");
    assertThat(rejected.reason()).contains("家户不存在").contains("hh-missing");
  }

  // ── social.AddHouseholdMembers ────────────────────────────────────────

  @Test
  void addHouseholdMembersAppliesAndRejectsNonPositiveCount() {
    SocialData base = baseWithHouseholdAndMembers();
    HandlerOutcome.Applied applied =
        applied(
            ADD,
            base,
            "{\"householdId\":\"hh-main\",\"lotId\":\"lot-new\",\"sex\":\"FEMALE\",\"count\":10,"
                + "\"ageAtAnchorDays\":0,\"reason\":\"gm-add\"}");

    SocialData after = roundTrip(applied, base);
    assertThat(after.groups().get(PeopleLotId.parse("lot-new")).count()).isEqualTo(10L);
    assertThat(after.requireHousehold(HH_MAIN).memberLots())
        .as("新批次进家户成员表")
        .contains(LOT_MAIN, PeopleLotId.parse("lot-new"));

    HandlerOutcome.Rejected rejected =
        rejected(
            ADD,
            base,
            "{\"householdId\":\"hh-main\",\"lotId\":\"lot-new\",\"sex\":\"FEMALE\",\"count\":0,"
                + "\"reason\":\"gm-add\"}");
    assertThat(rejected.reason()).contains("count 必须 > 0");
  }

  // ── social.RemoveHouseholdMembers ─────────────────────────────────────

  @Test
  void removeHouseholdMembersAppliesAndRejectsOverDraw() {
    SocialData base = baseWithHouseholdAndMembers();
    HandlerOutcome.Applied applied =
        applied(
            REMOVE,
            base,
            "{\"householdId\":\"hh-main\",\"lotId\":\"lot-main\",\"count\":4,"
                + "\"reason\":\"gm-remove\"}");

    SocialData after = roundTrip(applied, base);
    assertThat(after.groups().get(LOT_MAIN).count()).isEqualTo(6L);
    assertThat(after.requireHousehold(HH_MAIN).memberLots()).containsExactly(LOT_MAIN);

    HandlerOutcome.Rejected rejected =
        rejected(
            REMOVE,
            base,
            "{\"householdId\":\"hh-main\",\"lotId\":\"lot-main\",\"count\":11,"
                + "\"reason\":\"gm-remove\"}");
    assertThat(rejected.reason()).contains("超出批次人数").contains("count=10");
  }

  // ── social.TransferHouseholdMembers ───────────────────────────────────

  @Test
  void transferHouseholdMembersAppliesAndRejectsUnknownTarget() {
    SocialData base = baseWithTwoHouseholds();
    HandlerOutcome.Applied applied =
        applied(
            TRANSFER,
            base,
            "{\"from\":\"hh-main\",\"to\":\"hh-other\",\"lotId\":\"lot-main\",\"count\":4,"
                + "\"reason\":\"gm-transfer\"}");

    SocialData after = roundTrip(applied, base);
    assertThat(after.householdPopulation(HH_MAIN))
        .as("源户减 4")
        .isEqualTo(base.householdPopulation(HH_MAIN) - 4L);
    assertThat(after.householdPopulation(HH_OTHER)).as("目标户加 4").isEqualTo(4L);
    assertThat(after.requireHousehold(HH_OTHER).memberLots()).hasSize(1);

    HandlerOutcome.Rejected rejected =
        rejected(
            TRANSFER,
            base,
            "{\"from\":\"hh-main\",\"to\":\"hh-missing\",\"lotId\":\"lot-main\",\"count\":4,"
                + "\"reason\":\"gm-transfer\"}");
    assertThat(rejected.reason()).contains("家户不存在").contains("hh-missing");
  }

  // ── social.SetHouseholdVitalRates ─────────────────────────────────────

  @Test
  void setVitalRatesAppliesAndRejectsNegativeRate() {
    SocialData base = baseWithHouseholdAndMembers();
    HandlerOutcome.Applied applied =
        applied(
            SET_RATES,
            base,
            "{\"householdId\":\"hh-main\",\"rates\":[{\"bracketId\":\"0-14\",\"sex\":\"FEMALE\","
                + "\"birthRatePerMillePerTick\":7,\"deathRatePerMillePerTick\":2}],"
                + "\"reason\":\"gm-rates\"}");

    SocialData after = roundTrip(applied, base);
    Household household = after.requireHousehold(HH_MAIN);
    assertThat(household.vitalRates().rates()).hasSize(1);
    assertThat(household.vitalRates().find("0-14", Sex.FEMALE).orElseThrow().birthRatePerMillePerTick())
        .isEqualTo(7L);
    assertThat(after.populationEvents().values())
        .as("RATE_SET 审计事件进持久事件表（可回放）")
        .anySatisfy(event -> assertThat(event.type().name()).isEqualTo("RATE_SET"));

    HandlerOutcome.Rejected rejected =
        rejected(
            SET_RATES,
            base,
            "{\"householdId\":\"hh-main\",\"rates\":[{\"bracketId\":\"0-14\",\"sex\":\"FEMALE\","
                + "\"deathRatePerMillePerTick\":-1}],\"reason\":\"gm-rates\"}");
    assertThat(rejected.reason()).contains("不得为负");
  }

  // ── social.AdjustHouseholdPopulation ──────────────────────────────────

  @Test
  void adjustHouseholdPopulationAppliesBothSignsAndRejectsZero() {
    SocialData base = baseWithHouseholdAndMembers();
    long before = base.householdPopulation(HH_MAIN);

    HandlerOutcome.Applied up =
        applied(
            ADJUST,
            base,
            "{\"householdId\":\"hh-main\",\"sex\":\"MALE\",\"ageBracketId\":\"0-14\","
                + "\"delta\":3,\"reason\":\"gm-adjust\"}");
    SocialData afterUp = roundTrip(up, base);
    assertThat(afterUp.householdPopulation(HH_MAIN)).isEqualTo(before + 3L);

    HandlerOutcome.Applied down =
        applied(
            ADJUST,
            baseWithHouseholdAndMembers(),
            "{\"householdId\":\"hh-main\",\"sex\":\"MALE\",\"ageBracketId\":\"0-14\","
                + "\"delta\":-2,\"reason\":\"gm-adjust\"}");
    SocialData afterDown = roundTrip(down, baseWithHouseholdAndMembers());
    assertThat(afterDown.householdPopulation(HH_MAIN)).isEqualTo(before - 2L);

    HandlerOutcome.Rejected rejected =
        rejected(
            ADJUST,
            base,
            "{\"householdId\":\"hh-main\",\"sex\":\"MALE\",\"ageBracketId\":\"0-14\","
                + "\"delta\":0,\"reason\":\"gm-adjust\"}");
    assertThat(rejected.reason()).contains("delta 不得为 0");
  }

  // ── 装置 ──────────────────────────────────────────────────────────────

  private static SocialData baseWithHouseholdAndMembers() {
    SocialData base =
        HouseholdBook.create(
            SocialData.empty(),
            HH_MAIN,
            new HouseholdLocation.Hex(H11),
            new HouseholdProfile("主户", null, Map.of()),
            new HouseholdVitalRates(List.of()));
    return HouseholdBook.addMembers(base, HH_MAIN, LOT_MAIN, Sex.MALE, 10L, 0L, 0L, "seed");
  }

  private static SocialData baseWithTwoHouseholds() {
    SocialData base = baseWithHouseholdAndMembers();
    return HouseholdBook.create(
        base,
        HH_OTHER,
        new HouseholdLocation.Hex(H12),
        new HouseholdProfile("另一户", null, Map.of()),
        new HouseholdVitalRates(List.of()));
  }

  private static HandlerOutcome.Applied applied(
      io.mosire.simos.util.spi.CommandHandler handler, SocialData base, String payload) {
    HandlerOutcome outcome = handler.handle(SocialSpiFixture.state(base), payload);
    assertThat(outcome).as("合法载荷必须 Applied: %s", payload).isInstanceOf(HandlerOutcome.Applied.class);
    return (HandlerOutcome.Applied) outcome;
  }

  private static HandlerOutcome.Rejected rejected(
      io.mosire.simos.util.spi.CommandHandler handler, SocialData base, String payload) {
    HandlerOutcome outcome = handler.handle(SocialSpiFixture.state(base), payload);
    assertThat(outcome).as("坏载荷必须 Rejected: %s", payload).isInstanceOf(HandlerOutcome.Rejected.class);
    return (HandlerOutcome.Rejected) outcome;
  }

  /** ChangeSet 往返/命令重放判据：{@code between(base, next)} 过 apply 必须逐值重建 next。 */
  private static SocialData roundTrip(HandlerOutcome.Applied applied, SocialData base) {
    SocialData next = SocialChangeSet.apply((SocialChangeSet) applied.changeSet(), base);
    assertThat(((SocialChangeSet) applied.changeSet()).isEmpty())
        .as("Applied 的变更集必须非空（空变更 = 不该落 revision）")
        .isFalse();
    assertThat(SocialChangeSet.apply(SocialChangeSet.between(base, next), base))
        .as("命令重放：同一份变更集 apply 回 base 必须逐值重建 next")
        .isEqualTo(next);
    ChangeSet decoded = CODEC.decodeChangeSet(CODEC.encodeChangeSet(applied.changeSet()));
    assertThat(SocialChangeSet.apply((SocialChangeSet) decoded, base))
        .as("ChangeSet JSON 往返：过线后 apply 回 base 仍逐值重建 next")
        .isEqualTo(next);
    return next;
  }
}
