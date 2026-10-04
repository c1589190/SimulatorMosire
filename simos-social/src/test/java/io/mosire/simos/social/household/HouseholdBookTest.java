package io.mosire.simos.social.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.PopulationEventType;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S2 家户生命周期验收（架构 §5/§7 第 2、4、5 条）：建户 / 位置 / 画像 / 成员增删 / 整体转移与拆分 / 守恒 /
 * 重复事件拒绝 / 事件回放。
 *
 * <p>★ 这些用例按架构 §5 的接口逐条写，不按实现反推；断言逐值（count / id / location），不用"非空"式弱断言。
 */
class HouseholdBookTest {

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);

  private static final HouseholdId HEX_HOUSEHOLD = HouseholdId.parse("hh-hex");
  private static final HouseholdId UNIT_HOUSEHOLD = HouseholdId.parse("hh-unit");
  private static final PeopleLotId LOT = PeopleLotId.parse("lot-a");
  private static final long ADULT_AGE_DAYS = 20L * 365L;

  private static final HouseholdProfile PROFILE = new HouseholdProfile("甲户", null, Map.of());

  @Test
  void createSupportsHexAndUnitHouseholdsAndRejectsDuplicateId() {
    SocialData data = SocialData.empty();
    data =
        HouseholdBook.create(
            data,
            HEX_HOUSEHOLD,
            new HouseholdLocation.Hex(H00),
            PROFILE,
            new HouseholdVitalRates(List.of()));
    data =
        HouseholdBook.create(
            data,
            UNIT_HOUSEHOLD,
            new HouseholdLocation.Unit("u-1"),
            new HouseholdProfile("乙户", "城里的户", Map.of("note", "unit")),
            new HouseholdVitalRates(List.of()));

    assertThat(data.requireHousehold(HEX_HOUSEHOLD).location())
        .isEqualTo(new HouseholdLocation.Hex(H00));
    assertThat(data.requireHousehold(UNIT_HOUSEHOLD).location())
        .isEqualTo(new HouseholdLocation.Unit("u-1"));
    assertThat(data.requireHousehold(HEX_HOUSEHOLD).profile().name()).isEqualTo("甲户");

    SocialData current = data;
    assertThatThrownBy(
            () ->
                HouseholdBook.create(
                    current,
                    HEX_HOUSEHOLD,
                    new HouseholdLocation.Hex(H10),
                    PROFILE,
                    new HouseholdVitalRates(List.of())))
        .as("重复 id 建户必须拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已存在");
  }

  @Test
  void setLocationMovesHexToUnitAndBackWithoutChangingLotId() {
    SocialData data = seedHexHouseholdWithOneLot();

    data = HouseholdBook.setLocation(data, HEX_HOUSEHOLD, new HouseholdLocation.Unit("u-9"), "调防");
    assertThat(data.requireHousehold(HEX_HOUSEHOLD).location())
        .isEqualTo(new HouseholdLocation.Unit("u-9"));
    assertThat(data.unitOfLot(LOT)).contains("u-9");
    assertThat(data.hexOfLot(LOT)).isEmpty();
    assertThat(data.groups().get(LOT).id()).as("迁位不改批次 id").isEqualTo(LOT);

    data = HouseholdBook.setLocation(data, HEX_HOUSEHOLD, new HouseholdLocation.Hex(H10), "回迁");
    assertThat(data.requireHousehold(HEX_HOUSEHOLD).location())
        .isEqualTo(new HouseholdLocation.Hex(H10));
    assertThat(data.hexOfLot(LOT)).contains(H10);
    assertThat(data.unitOfLot(LOT)).isEmpty();
    assertThat(data.groups().get(LOT).id()).as("再迁位仍不改 id").isEqualTo(LOT);
  }

  @Test
  void setProfileReplacesProfileAndRejectsBlankReason() {
    SocialData data = seedHexHouseholdWithOneLot();
    HouseholdProfile next = new HouseholdProfile("改名户", "描述", Map.of("k", "v"));

    SocialData changed = HouseholdBook.setProfile(data, HEX_HOUSEHOLD, next, "GM 改名");

    assertThat(changed.requireHousehold(HEX_HOUSEHOLD).profile()).isEqualTo(next);
    assertThat(data.requireHousehold(HEX_HOUSEHOLD).profile()).as("原状态不被就地修改").isEqualTo(PROFILE);
    assertThatThrownBy(() -> HouseholdBook.setProfile(data, HEX_HOUSEHOLD, next, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");
  }

  @Test
  void addMembersCreatesLotAndRejectsDuplicateIdBadCountAndUnknownHousehold() {
    SocialData data = seedHexHouseholdWithOneLot();
    PeopleLotId second = PeopleLotId.parse("lot-b");

    data =
        HouseholdBook.addMembers(
            data, HEX_HOUSEHOLD, second, Sex.FEMALE, 50L, ADULT_AGE_DAYS, 0L, "GM 增人");

    assertThat(data.groups().get(second).count()).isEqualTo(50L);
    assertThat(data.requireHousehold(HEX_HOUSEHOLD).memberLots())
        .as("memberLots 是唯一成员关系，保插入序")
        .containsExactly(LOT, second);

    SocialData current = data;
    assertThatThrownBy(
            () ->
                HouseholdBook.addMembers(
                    current, HEX_HOUSEHOLD, second, Sex.FEMALE, 1L, ADULT_AGE_DAYS, 0L, "重复"))
        .as("批次 id 已存在 ⇒ 拒（不静默合并）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("批次已存在");
    assertThatThrownBy(
            () ->
                HouseholdBook.addMembers(
                    current, HEX_HOUSEHOLD, PeopleLotId.parse("lot-c"), Sex.MALE, 0L, 0L, 0L, "零人"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("count 必须 > 0");
    assertThatThrownBy(
            () ->
                HouseholdBook.addMembers(
                    current,
                    HouseholdId.parse("hh-missing"),
                    PeopleLotId.parse("lot-d"),
                    Sex.MALE,
                    1L,
                    0L,
                    0L,
                    "无主"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("家户不存在");
  }

  @Test
  void removeMembersPartialThenToZeroDeletesLot() {
    SocialData data = seedHexHouseholdWithOneLot();

    SocialData partial = HouseholdBook.removeMembers(data, HEX_HOUSEHOLD, LOT, 30L, "抽丁");
    assertThat(partial.groups().get(LOT).count()).isEqualTo(70L);
    assertThat(partial.requireHousehold(HEX_HOUSEHOLD).memberLots()).contains(LOT);

    SocialData empty = HouseholdBook.removeMembers(partial, HEX_HOUSEHOLD, LOT, 70L, "全抽");
    assertThat(empty.groups()).as("减到 0 ⇒ 删批次").doesNotContainKey(LOT);
    assertThat(empty.requireHousehold(HEX_HOUSEHOLD).memberLots()).isEmpty();

    assertThatThrownBy(() -> HouseholdBook.removeMembers(data, HEX_HOUSEHOLD, LOT, 101L, "超扣"))
        .as("超扣必须拒，不许把 count 写成负")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超出批次人数");
    assertThat(data.groups().get(LOT).count()).as("失败不产生半截状态").isEqualTo(100L);
  }

  @Test
  void transferWholeMovesLotWithSameIdAndLocationFollowsHousehold() {
    SocialData data = seedHexHouseholdWithOneLot();
    data =
        HouseholdBook.create(
            data,
            UNIT_HOUSEHOLD,
            new HouseholdLocation.Unit("u-1"),
            new HouseholdProfile("乙户", null, Map.of()),
            new HouseholdVitalRates(List.of()));

    SocialData moved = HouseholdBook.transferMembers(data, HEX_HOUSEHOLD, UNIT_HOUSEHOLD, LOT, 100L, "整户调");

    assertThat(moved.requireHousehold(HEX_HOUSEHOLD).memberLots()).isEmpty();
    assertThat(moved.requireHousehold(UNIT_HOUSEHOLD).memberLots()).containsExactly(LOT);
    assertThat(moved.groups().get(LOT).id()).as("整体移动 id 不变").isEqualTo(LOT);
    assertThat(moved.unitOfLot(LOT)).contains("u-1");

    SocialData relocated =
        HouseholdBook.setLocation(moved, UNIT_HOUSEHOLD, new HouseholdLocation.Hex(H10), "换驻地");
    assertThat(relocated.hexOfLot(LOT)).contains(H10);
    assertThat(relocated.groups().get(LOT).id()).as("位置变了，批次 id 仍不变").isEqualTo(LOT);
    assertThat(relocated.populationAt(H10)).isEqualTo(100L);
  }

  @Test
  void transferSplitKeepsSourceAndDerivesStableMovedLotMergingOnRepeat() {
    SocialData data = seedHexHouseholdWithOneLot();
    data =
        HouseholdBook.create(
            data,
            UNIT_HOUSEHOLD,
            new HouseholdLocation.Unit("u-1"),
            new HouseholdProfile("乙户", null, Map.of()),
            new HouseholdVitalRates(List.of()));

    SocialData split = HouseholdBook.transferMembers(data, HEX_HOUSEHOLD, UNIT_HOUSEHOLD, LOT, 40L, "分户");
    PeopleLotId movedLot = PeopleLotId.parse(LOT.value() + "@" + UNIT_HOUSEHOLD.value());

    assertThat(split.groups().get(LOT).count()).as("源批次保留 id、只减人数").isEqualTo(60L);
    assertThat(split.groups().get(movedLot).count()).isEqualTo(40L);
    assertThat(split.requireHousehold(HEX_HOUSEHOLD).memberLots()).containsExactly(LOT);
    assertThat(split.requireHousehold(UNIT_HOUSEHOLD).memberLots()).containsExactly(movedLot);

    SocialData splitAgain = HouseholdBook.transferMembers(split, HEX_HOUSEHOLD, UNIT_HOUSEHOLD, LOT, 10L, "再分");
    assertThat(splitAgain.groups().get(LOT).count()).as("源 60−10").isEqualTo(50L);
    assertThat(splitAgain.groups().get(movedLot).count())
        .as("同一目标重复拆分 ⇒ 合并进同一个派生批次（不产生第二份身份）")
        .isEqualTo(50L);
    assertThat(splitAgain.requireHousehold(UNIT_HOUSEHOLD).memberLots()).containsExactly(movedLot);

    SocialData relocated =
        HouseholdBook.setLocation(splitAgain, UNIT_HOUSEHOLD, new HouseholdLocation.Hex(H00), "驻防");
    assertThat(relocated.groups().get(movedLot).id()).as("派生批次 id 迁位后不变").isEqualTo(movedLot);
    assertThat(relocated.hexOfLot(movedLot)).contains(H00);
  }

  @Test
  void conservationHoldsForEveryOperationAndConstructorRejectsIllegalOwnership() {
    SocialData data = seedHexHouseholdWithOneLot();
    data = ensureUnitHousehold(data, UNIT_HOUSEHOLD, "u-1");
    data =
        HouseholdBook.transferMembers(
            data, HEX_HOUSEHOLD, UNIT_HOUSEHOLD, LOT, 100L, "整移");

    assertThat(HouseholdBook.requireConservation(data)).isSameAs(data);
    assertThat(data.householdOfLot(LOT)).isPresent();
    assertThat(data.households().values().stream().filter(h -> h.hasMember(LOT)).count())
        .as("每个 group 恰属一个 household")
        .isEqualTo(1L);

    HouseholdId h1 = HouseholdId.parse("hh-dup-1");
    HouseholdId h2 = HouseholdId.parse("hh-dup-2");
    PopulationGroup group = new PopulationGroup(LOT, Sex.MALE, 1L, 0L, 0L);
    Household hh1 =
        new Household(
            h1,
            new HouseholdLocation.Hex(H00),
            new HouseholdProfile("户1", null, Map.of()),
            List.of(LOT),
            new HouseholdVitalRates(List.of()));
    Household hh2 =
        new Household(
            h2,
            new HouseholdLocation.Hex(H10),
            new HouseholdProfile("户2", null, Map.of()),
            List.of(LOT),
            new HouseholdVitalRates(List.of()));

    assertThatThrownBy(
            () ->
                new SocialData(
                    Map.of(),
                    Map.of(),
                    Map.of(LOT, group),
                    Map.of(h1, hh1, h2, hh2),
                    Map.of()))
        .as("双主批次必须被构造期拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同时被家户");
    assertThatThrownBy(
            () -> new SocialData(Map.of(), Map.of(), Map.of(LOT, group), Map.of(), Map.of()))
        .as("无主批次必须被构造期拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有被任何家户引用");
  }

  @Test
  void negativeGmAdjustmentBeyondBatchCountIsRejectedWithoutMutation() {
    SocialData data = seedHexHouseholdWithOneLot();

    assertThatThrownBy(
            () ->
                HouseholdBook.adjustPopulation(
                    data, HEX_HOUSEHOLD, Sex.MALE, AgeBracket.ADULT.key(), -101L, "GM 超扣"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("扣减不足");
    assertThat(data.groups().get(LOT).count()).isEqualTo(100L);

    assertThatThrownBy(
            () -> HouseholdBook.removeMembers(data, HEX_HOUSEHOLD, LOT, 101L, "GM 超扣"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超出批次人数");
    assertThat(data.groups().get(LOT).count()).isEqualTo(100L);
  }

  @Test
  void duplicateEventIdsAreRejectedBothSingleAndInBatch() {
    SocialData data = seedHexHouseholdWithOneLot();
    HouseholdPopulationEvent event =
        new HouseholdPopulationEvent(
            "evt-rate-set",
            HEX_HOUSEHOLD,
            PopulationEventType.RATE_SET,
            Sex.MALE,
            AgeBracket.CHILD.key(),
            0L,
            0L,
            "test",
            "HouseholdBookTest");

    SocialData once = HouseholdBook.applyEvent(data, event);
    assertThat(once.populationEvents()).containsKey(event.id());

    assertThatThrownBy(() -> HouseholdBook.applyEvent(once, event))
        .as("同 id 单条重复落账必须拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已存在");
    assertThatThrownBy(
            () -> HouseholdBook.applyEvents(data, List.of(event, event, event)))
        .as("批内重复同样拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("批内重复");
  }

  @Test
  void applyEventIsReplayableAndRejectsBadDirectionOrMissingTarget() {
    SocialData seed = seedHexHouseholdWithOneLot();
    HouseholdPopulationEvent birth =
        new HouseholdPopulationEvent(
            "evt-birth",
            HEX_HOUSEHOLD,
            PopulationEventType.BIRTH,
            Sex.FEMALE,
            AgeBracket.CHILD.key(),
            5L,
            3L,
            "test",
            "HouseholdBookTest");

    SocialData applied = HouseholdBook.applyEvent(seed, birth);
    SocialData replayed = HouseholdBook.applyEvent(seed.withPopulationEvents(Map.of()), birth);

    assertThat(replayed.groups()).as("同一条事件从同一前态回放 ⇒ 批次表逐值相同").isEqualTo(applied.groups());
    assertThat(replayed.households()).isEqualTo(applied.households());
    assertThat(replayed.populationEvents()).containsKey(birth.id());

    assertThatThrownBy(
            () ->
                new HouseholdPopulationEvent(
                    "evt-bad-neg",
                    HEX_HOUSEHOLD,
                    PopulationEventType.DEATH,
                    Sex.MALE,
                    AgeBracket.ADULT.key(),
                    -1L,
                    0L,
                    "test",
                    "HouseholdBookTest"))
        .as("非 GM_ADJUST 的负人数在事件构造期就拒")
        .isInstanceOf(IllegalArgumentException.class);

    HouseholdPopulationEvent missingLotDeath =
        new HouseholdPopulationEvent(
            "evt-bad-2",
            HEX_HOUSEHOLD,
            PopulationEventType.DEATH,
            Sex.MALE,
            AgeBracket.ADULT.key(),
            1L,
            0L,
            "test",
            "HouseholdBookTest",
            PeopleLotId.parse("lot-missing"));
    assertThatThrownBy(() -> HouseholdBook.applyEvent(seed, missingLotDeath))
        .as("死亡指向不存在的批次必须拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("批次不存在");

    HouseholdPopulationEvent wrongHousehold =
        new HouseholdPopulationEvent(
            "evt-bad-3",
            HouseholdId.parse("hh-missing"),
            PopulationEventType.GM_ADJUST,
            Sex.MALE,
            AgeBracket.ADULT.key(),
            1L,
            0L,
            "test",
            "HouseholdBookTest");
    assertThatThrownBy(() -> HouseholdBook.applyEvent(seed, wrongHousehold))
        .as("事件指向不存在的家户必须拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不存在的家户");
  }

  @Test
  void transferEventPairsReplayToTheSameOwnershipAndCounts() {
    SocialData base = seedHexHouseholdWithOneLot();
    base = ensureUnitHousehold(base, UNIT_HOUSEHOLD, "u-1");
    PeopleLotId movedLot = PeopleLotId.parse(LOT.value() + "@" + UNIT_HOUSEHOLD.value());

    // 拆分：OUT 40 → IN 40（派生批次），两条事件按写入序回放。
    SocialData split =
        HouseholdBook.applyEvents(
            base,
            List.of(
                new HouseholdPopulationEvent(
                    "evt-out-split",
                    HEX_HOUSEHOLD,
                    PopulationEventType.TRANSFER_OUT,
                    Sex.MALE,
                    AgeBracket.ADULT.key(),
                    40L,
                    0L,
                    "replay",
                    "replay",
                    LOT),
                new HouseholdPopulationEvent(
                    "evt-in-split",
                    UNIT_HOUSEHOLD,
                    PopulationEventType.TRANSFER_IN,
                    Sex.MALE,
                    AgeBracket.ADULT.key(),
                    40L,
                    0L,
                    "replay",
                    "replay",
                    movedLot)));
    assertThat(split.groups().get(LOT).count()).isEqualTo(60L);
    assertThat(split.householdOfLot(LOT).orElseThrow().id()).isEqualTo(HEX_HOUSEHOLD);
    assertThat(split.groups().get(movedLot).count()).isEqualTo(40L);
    assertThat(split.householdOfLot(movedLot).orElseThrow().id()).isEqualTo(UNIT_HOUSEHOLD);

    // 整体：OUT 100 → IN 100，OUT 把批次删掉、IN 在目标家户按同一 id 重建。
    SocialData whole =
        HouseholdBook.applyEvents(
            base,
            List.of(
                new HouseholdPopulationEvent(
                    "evt-out-whole",
                    HEX_HOUSEHOLD,
                    PopulationEventType.TRANSFER_OUT,
                    Sex.MALE,
                    AgeBracket.ADULT.key(),
                    100L,
                    0L,
                    "replay",
                    "replay",
                    LOT),
                new HouseholdPopulationEvent(
                    "evt-in-whole",
                    UNIT_HOUSEHOLD,
                    PopulationEventType.TRANSFER_IN,
                    Sex.MALE,
                    AgeBracket.ADULT.key(),
                    100L,
                    0L,
                    "replay",
                    "replay",
                    LOT)));
    assertThat(whole.groups().get(LOT).count()).isEqualTo(100L);
    assertThat(whole.householdOfLot(LOT).orElseThrow().id()).isEqualTo(UNIT_HOUSEHOLD);
    assertThat(whole.requireHousehold(HEX_HOUSEHOLD).memberLots()).isEmpty();

    // 拒绝方向：目标家户还不是该批次的 owner 就 IN ⇒ 拒。
    HouseholdPopulationEvent inWhileStillOwned =
        new HouseholdPopulationEvent(
            "evt-in-rejected",
            UNIT_HOUSEHOLD,
            PopulationEventType.TRANSFER_IN,
            Sex.MALE,
            AgeBracket.ADULT.key(),
            10L,
            0L,
            "replay",
            "replay",
            LOT);
    SocialData current = base;
    assertThatThrownBy(() -> HouseholdBook.applyEvent(current, inWhileStillOwned))
        .as("批次仍属于源家户 ⇒ 直接 IN 到目标家户必须拒（不做静默改挂）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("属于家户");
  }

  private static SocialData seedHexHouseholdWithOneLot() {
    SocialData data =
        HouseholdBook.create(
            SocialData.empty(),
            HEX_HOUSEHOLD,
            new HouseholdLocation.Hex(H00),
            PROFILE,
            new HouseholdVitalRates(List.of()));
    return HouseholdBook.addMembers(
        data, HEX_HOUSEHOLD, LOT, Sex.MALE, 100L, ADULT_AGE_DAYS, 0L, "创世播种");
  }

  private static SocialData ensureUnitHousehold(
      SocialData data, HouseholdId id, String unitId) {
    if (data.households().containsKey(id)) {
      return data;
    }
    return HouseholdBook.create(
        data,
        id,
        new HouseholdLocation.Unit(unitId),
        new HouseholdProfile("乙户", null, Map.of()),
        new HouseholdVitalRates(List.of()));
  }
}
