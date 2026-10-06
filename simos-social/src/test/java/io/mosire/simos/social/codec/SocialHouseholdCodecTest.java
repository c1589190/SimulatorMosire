package io.mosire.simos.social.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S2 家户/事件线格式验收（架构 §7 第 5 条）：{@code households} / {@code populationEvents} 进
 * Snapshot/ChangeSet，并且能被 {@link SocialCodec} 往返；负的 GM_ADJUST 也必须活着。
 */
class SocialHouseholdCodecTest {

  private static final SocialCodec CODEC = new SocialCodec();
  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final long YEAR = 365L;

  @Test
  void snapshotRoundTripsTheWholeHouseholdAndEventTable() {
    SocialData data = fixture();
    SocialSnapshot snapshot =
        new SocialSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)), SimosTimestamp.of(7), data);

    SocialSnapshot back = (SocialSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).isEqualTo(snapshot);
    assertThat(back.data().households()).as("家户位置/画像/成员表必须活过 JSON").isEqualTo(data.households());
    assertThat(back.data().populationEvents())
        .as("事件表逐条（含负 GM_ADJUST）必须活过 JSON")
        .isEqualTo(data.populationEvents());
  }

  @Test
  void changeSetRoundTripsHouseholdsAndEventsAndRebuildsTheState() {
    SocialData data = fixture();
    SocialChangeSet upsert = SocialChangeSet.between(SocialData.empty(), data);

    SocialChangeSet back = (SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert));

    assertThat(back).as("ChangeSet 的 households/populationEvents 两个新组件必须逐值往返").isEqualTo(upsert);
    assertThat(SocialChangeSet.apply(back, SocialData.empty()))
        .as("铁律 5：apply(between(base,target), base) == target")
        .isEqualTo(data);

    SocialChangeSet remove = SocialChangeSet.between(data, SocialData.empty());
    SocialChangeSet removeBack =
        (SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove));
    assertThat(removeBack).isEqualTo(remove);
    assertThat(SocialChangeSet.apply(removeBack, data)).isEqualTo(SocialData.empty());
  }

  @Test
  void byteLevelEncodingIsStableForHouseholdBearingSnapshots() {
    SocialSnapshot snapshot =
        new SocialSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(0), fixture());
    String once = CODEC.encodeSnapshot(snapshot);
    String twice = CODEC.encodeSnapshot(CODEC.decodeSnapshot(once));

    assertThat(twice).as("键序/事件序必须是内容的纯函数").isEqualTo(once);
  }

  private static SocialData fixture() {
    SocialData data = SocialData.empty();
    HouseholdId hexHousehold = HouseholdId.parse("hh-codec-hex");
    HouseholdId unitHousehold = HouseholdId.parse("hh-codec-unit");
    data =
        HouseholdBook.create(
            data,
            hexHousehold,
            new HouseholdLocation.Hex(H00),
            new HouseholdProfile("甲户", "描述", Map.of("k", "v")),
            new HouseholdVitalRates(List.of()));
    data =
        HouseholdBook.create(
            data,
            unitHousehold,
            new HouseholdLocation.Unit("u-codec"),
            new HouseholdProfile("乙户", null, Map.of()),
            new HouseholdVitalRates(List.of()));

    PeopleLotId man = PeopleLotId.parse("codec-man");
    PeopleLotId woman = PeopleLotId.parse("codec-woman");
    data =
        HouseholdBook.addMembers(data, hexHousehold, man, Sex.MALE, 100L, 20L * YEAR, 0L, "seed");
    data =
        HouseholdBook.addMembers(
            data, hexHousehold, woman, Sex.FEMALE, 50L, 30L * YEAR, 0L, "seed");
    // ★ ppm/tick 引擎首见余数键会给稳定的哈希初相位（0..999_999），低率（如 40 ppm）在 day 0 不会凑满一人。
    //   夹具要把 BIRTH 事件钉进事件表，故用 1_000_000 ppm（每 1 人 1 tick 生 1 个）压过初相位；死亡给 0。
    HouseholdVitalRates table =
        new HouseholdVitalRates(
            List.of(
                new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.FEMALE, 1_000_000L, 0L),
                new HouseholdVitalRate(AgeBracket.ADULT.key(), Sex.MALE, 0L, 0L)));
    data = HouseholdBook.setVitalRates(data, hexHousehold, table, "率表");
    data = HouseholdBook.setVitalRates(data, unitHousehold, table, "乙率");
    data = HouseholdBook.removeMembers(data, hexHousehold, woman, 10L, "征收");
    data = HouseholdBook.transferMembers(data, hexHousehold, unitHousehold, woman, 40L, "调防");

    SocialData settled = HouseholdBook.settleVitalEvents(data, 0L);
    assertThat(settled.populationEvents().values())
        .anySatisfy(
            event -> {
              assertThat(event.type())
                  .isEqualTo(io.mosire.simos.social.api.population.PopulationEventType.GM_ADJUST);
              assertThat(event.count()).isNegative();
            });
    assertThat(settled.populationEvents().values())
        .anySatisfy(
            event ->
                assertThat(event.type())
                    .isEqualTo(io.mosire.simos.social.api.population.PopulationEventType.BIRTH));
    return settled;
  }
}
