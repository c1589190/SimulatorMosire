package io.mosire.simos.app.sim;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.testing.GovZ6WorldFixture.World;
import io.mosire.simos.app.tools.write.GovWorldBootstrap;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.SimulationState;
import java.nio.file.Path;
import java.util.List;

/**
 * ★★ <b>批 M（2026-10-15）：120 天小世界长跑从 JUnit 测试类搬成独立跑法</b>。
 *
 * <p>★★ <b>用户原话（逐字，2026-10-15）</b>：「先停一停，我不是说，测试class里不要塞多tick吗？要跑全真世界模拟自己额外创世界跑， 别塞进测试里！」
 *
 * <p>★ <b>搬了什么</b>：原 {@code
 * io.mosire.simos.app.gov.GovGenesisZ6Test#oneHundredTwentyDaysAdvanceWithoutContractFailure}
 * 的整段（{@code advanceDays(120)} = 120 次逐日推进 = 120 tick，判据 M1 的第二个坏形态"多次单日的推进"） 连同**全部断言逐字**搬到这里；门禁里留
 * 3 天的同名 smoke（{@code GovGenesisZ6Test} 里改名成 {@code
 * threeDaysAdvanceWithoutContractFailureAndCommitmentsIntact}），保的是同一批机制读数 （每日一条
 * revision、GOV_SERVICE 承诺不被静默缩、无 ADMIN_CONTRACT 危机、三省 GOV 在位）。
 *
 * <p>★ 跑法：{@code tools/multitick-sim.sh gov-genesis-120d}（独立进程 + 独立 store，不进 {@code clean verify}）。
 */
public final class GovGenesis120DaySim {

  private GovGenesis120DaySim() {}

  /** 两个 GOV 单位（字面取自 {@code GovGenesisZ6Test} 的同名常量，与生产装配同源）。 */
  private static final UnitId CENTRAL = UnitId.parse(GovWorldBootstrap.CENTRAL_GOV_ID);

  private static final UnitId PROVINCE = UnitId.parse(GovWorldBootstrap.PROVINCE_GOV_ID);

  /** 120 天逐日推进（小世界，真 {@code Shell}）—— 断言全部来自原测试，原文未改。 */
  public static void run(Path storeDir) {
    try (World world = GovZ6WorldFixture.smallWorldGenesis(storeDir, "long")) {
      long tick = world.advanceDays(120L);

      assertThat(tick).isEqualTo(120L);
      assertThat(world.head()).as("每日恰一条 revision ⇒ head = 121").isEqualTo(121L);

      SimulationState state = world.state();
      GovState gov = GovZ6WorldFixture.govSlice(state);
      EconomyData economy = GovZ6WorldFixture.economySlice(state);
      // C7 口径：GOV_SERVICE 承诺不因结算/死亡比例缩放被静默缩小（超预算 ⇒ fail-closed，测试会当场红）。
      for (UnitId govId : List.of(CENTRAL, PROVINCE)) {
        HouseholdId official = HouseholdId.parse("hh-unit:" + govId.value());
        long committed =
            economy.allocations().values().stream()
                .filter(c -> c.kind() == LaborCommitmentKind.GOV_SERVICE)
                .filter(c -> c.household().equals(official))
                .mapToLong(HouseholdLaborCommitment::laborMilli)
                .sum();
        assertThat(committed)
            .as(govId.value() + " 120 天后 GOV_SERVICE 承诺仍为 32000（未被静默缩）")
            .isEqualTo(32_000L);
        assertThat(gov.offices().get(govId)).isNotNull();
      }
      long socialPopulation = totalSocialPopulation(GovZ6WorldFixture.socialSlice(state));
      assertThat(socialPopulation).as("120 天后 Social 人口仍为正（每日生死正常结算）").isPositive();
      assertThat(GovZ6WorldFixture.socialSlice(state).households())
          .as("120 天后 Social 家户表仍在（未被清空）")
          .isNotEmpty();
      assertThat(economy.crisisSignals().values())
          .as("120 天长跑无 ADMIN_CONTRACT 契约告警")
          .noneMatch(signal -> signal.kind() == HexCrisisSignal.Kind.ADMIN_CONTRACT);
      assertThat(economy.governments()).hasSize(3);
    }
  }

  private static long totalSocialPopulation(SocialData social) {
    long total = 0L;
    for (Household household : social.households().values()) {
      for (long members : household.members().values()) {
        total += members;
      }
    }
    return total;
  }
}
