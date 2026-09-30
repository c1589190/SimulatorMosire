package io.mosire.simos.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link GovEfficiency} 逐值钉住阶段 11a 的公式表（计划 §3）：
 *
 * <ul>
 *   <li>满编 200/100 ⇒ (1000,1000,0,1000)；
 *   <li>+10% ⇒ 50‰ 加成、效率 1050；+20% ⇒ 66‰、效率 1066；
 *   <li>只覆盖一维 ⇒ 750‰ 且无加成（加成只在双满时给）；
 *   <li>空需求 ⇒ (1000,1000,0,1000)（无需求 = 全额覆盖，不是“没数据”）；
 *   <li>非对称加权（治安剩余 20、文书剩余 30、总需求 300）⇒ s=16、bonus=61、效率 1061。
 * </ul>
 *
 * <p>★ 供给口径也逐值钉：文书 = {@code SCRIBE + POST}（驿传与书吏同口径），治安只认 {@code YAMEN}。
 */
class GovEfficiencyTest {

  private static final HexCoord H1 = new HexCoord(1, 1);

  @Test
  void fullStaffGivesNoBonusAndEfficiencyOneThousand() {
    GovEfficiency.Efficiency efficiency = GovEfficiency.of(gov(200L, 100L, 0L), demand(200L, 100L));

    assertThat(efficiency).isEqualTo(new GovEfficiency.Efficiency(1000L, 1000L, 0L, 1000L));
  }

  @Test
  void tenPercentOverstaffGivesFiftyPerMilleBonus() {
    GovEfficiency.Efficiency efficiency = GovEfficiency.of(gov(220L, 110L, 0L), demand(200L, 100L));

    assertThat(efficiency).isEqualTo(new GovEfficiency.Efficiency(1000L, 1000L, 50L, 1050L));
  }

  @Test
  void twentyPercentOverstaffGivesSixtySixPerMilleBonus() {
    GovEfficiency.Efficiency efficiency = GovEfficiency.of(gov(240L, 120L, 0L), demand(200L, 100L));

    assertThat(efficiency).isEqualTo(new GovEfficiency.Efficiency(1000L, 1000L, 66L, 1066L));
  }

  @Test
  void coveringOnlyOneDimensionLeavesEfficiencyAtCoverageMinimum() {
    GovEfficiency.Efficiency efficiency = GovEfficiency.of(gov(150L, 100L, 0L), demand(200L, 100L));

    assertThat(efficiency).isEqualTo(new GovEfficiency.Efficiency(750L, 1000L, 0L, 750L));
  }

  @Test
  void emptyDemandIsFullCoverageWithNoBonus() {
    GovEfficiency.Efficiency efficiency = GovEfficiency.of(gov(0L, 0L, 0L), Map.of());

    assertThat(efficiency).isEqualTo(new GovEfficiency.Efficiency(1000L, 1000L, 0L, 1000L));
  }

  @Test
  void asymmetricSurplusIsDemandWeighted() {
    // 治安：200/200 超 20；文书：100/100 超 30；总剩余 50 / 总需求 300 ⇒ s=floor(100×50/300)=16
    // ⇒ bonus=floor(100×16/(16+10))=61 ⇒ efficiency=floor(1000×1061/1000)=1061。
    GovEfficiency.Efficiency efficiency = GovEfficiency.of(gov(220L, 90L, 40L), demand(200L, 100L));

    assertThat(efficiency).isEqualTo(new GovEfficiency.Efficiency(1000L, 1000L, 61L, 1061L));
  }

  @Test
  void paperworkSupplyIsScribePlusPost() {
    GovEfficiency.Efficiency efficiency = GovEfficiency.of(gov(200L, 60L, 40L), demand(200L, 100L));

    assertThat(efficiency)
        .as("文书供给 = SCRIBE 60 + POST 40 = 100，正好满编")
        .isEqualTo(new GovEfficiency.Efficiency(1000L, 1000L, 0L, 1000L));

    GovEfficiency.Efficiency missingRoles = GovEfficiency.of(gov(0L, 0L, 0L), demand(200L, 100L));
    assertThat(missingRoles)
        .as("缺角色 = 0 供给：治安覆盖 0、文书覆盖 0")
        .isEqualTo(new GovEfficiency.Efficiency(0L, 0L, 0L, 0L));
  }

  @Test
  void oneDimensionEmptySkipsItsSurplusInBonus() {
    // 治安有需求且供给 220（剩余 20）；文书需求 0、供给给 1000——需求 0 维的剩余不得算进加成。
    GovEfficiency.Efficiency efficiency = GovEfficiency.of(gov(220L, 1000L, 0L), demand(200L, 0L));

    assertThat(efficiency)
        .as("总需求 200、总剩余 20 ⇒ s=floor(100×20/200)=10 ⇒ bonus=50、效率 1050；文书空需求不计其剩余")
        .isEqualTo(new GovEfficiency.Efficiency(1000L, 1000L, 50L, 1050L));
  }

  @Test
  void bonusIsZeroUnlessBothDimensionsAreFullyCovered() {
    GovEfficiency.Efficiency efficiency = GovEfficiency.of(gov(150L, 130L, 0L), demand(200L, 100L));

    assertThat(efficiency.securityCoveragePerMille()).isEqualTo(750L);
    assertThat(efficiency.paperworkCoveragePerMille()).isEqualTo(1000L);
    assertThat(efficiency.bonusPerMille()).as("一维不满 ⇒ 加成必须为 0").isZero();
    assertThat(efficiency.efficiencyPerMille()).isEqualTo(750L);
  }

  @Test
  void coverageIsCappedAtOneThousandEvenWhenSupplyExceedsDemand() {
    GovEfficiency.Efficiency efficiency =
        GovEfficiency.of(gov(2000L, 1000L, 0L), demand(200L, 100L));

    assertThat(efficiency.securityCoveragePerMille())
        .as("min(1000, supply×1000/demand)")
        .isEqualTo(1000L);
    assertThat(efficiency.paperworkCoveragePerMille()).isEqualTo(1000L);
    // 剩余 1800+900=2700 / 300 ⇒ s=900 ⇒ bonus=floor(90000/910)=98 ⇒ eff=1098（仍在上限 1100 内）
    assertThat(efficiency.bonusPerMille()).isEqualTo(98L);
    assertThat(efficiency.efficiencyPerMille()).isEqualTo(1098L);
  }

  @Test
  void nullGovOrDemandThrows() {
    assertThatThrownBy(() -> GovEfficiency.of(null, demand(200L, 100L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gov 不得为 null");
    assertThatThrownBy(() -> GovEfficiency.of(gov(1L, 1L, 0L), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("demand 不得为 null");
  }

  @Test
  void efficiencyRecordBoundsMatchGovOfficeState() {
    assertThatThrownBy(() -> new GovEfficiency.Efficiency(-1L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityCoveragePerMille");
    assertThatThrownBy(() -> new GovEfficiency.Efficiency(1001L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("securityCoveragePerMille");
    assertThatThrownBy(() -> new GovEfficiency.Efficiency(0L, 1001L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paperworkCoveragePerMille");
    assertThatThrownBy(() -> new GovEfficiency.Efficiency(0L, 0L, -1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bonusPerMille");
    assertThatThrownBy(() -> new GovEfficiency.Efficiency(0L, 0L, 101L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bonusPerMille");
    assertThatThrownBy(() -> new GovEfficiency.Efficiency(0L, 0L, 0L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("efficiencyPerMille");
    assertThatThrownBy(() -> new GovEfficiency.Efficiency(0L, 0L, 0L, 1101L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("efficiencyPerMille");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static GovFormation gov(long yamen, long scribe, long post) {
    Map<StaffRole, Long> staff = new LinkedHashMap<>();
    staff.put(StaffRole.YAMEN, yamen);
    staff.put(StaffRole.SCRIBE, scribe);
    staff.put(StaffRole.POST, post);
    return new GovFormation(
        staff, new OfficePolicy(0L, 0L, 0L, 0L, Map.of()), Optional.empty(), GovLevel.CENTRAL);
  }

  private static Map<HexCoord, GovDemand.HexDemand> demand(long security, long paperwork) {
    Map<HexCoord, GovDemand.HexDemand> demand = new LinkedHashMap<>();
    demand.put(H1, new GovDemand.HexDemand(security, paperwork));
    return demand;
  }
}
