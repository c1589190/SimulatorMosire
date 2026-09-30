package io.mosire.simos.gov;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.StaffRole;
import java.util.Map;

/**
 * 行政效率（阶段 11a，计划 §2.2 / §3）：由编制供给与辖区逐格需求算出**覆盖率 / 超编加成 / 行政效率**三个 per-mille 读数。
 *
 * <p>★★ <b>这是全仓唯一的行政效率算法</b>（用户裁定 1/2：行政力与战斗力分开算，unit 侧不存任何力量数值；{@code unit} 只给编制与政策）。 算式里的每个常量都引用
 * {@link GovRules}。
 *
 * <p>★★ <b>算法逐行</b>：
 *
 * <ul>
 *   <li><b>供给</b>：治安 ← {@code staff[YAMEN]}；文书 ← {@code staff[SCRIBE] + staff[POST]}（互不通用）；
 *   <li><b>汇总</b>：对 {@code demand} 的每格值求和，得到需求维度的 {@code supply / demand} 总量（同一套求和也供每日信号 evidence
 *       用）；
 *   <li><b>覆盖</b>：需求为 0 的维度记 {@link GovRules#COVERAGE_FULL_PER_MILLE}（无需求 = 全额覆盖）；有需求时 {@code
 *       coverage = min(COVERAGE_FULL_PER_MILLE, supply × COVERAGE_FULL_PER_MILLE /
 *       demand)}（整数、向下取整）；
 *   <li><b>加成</b>：<b>仅当两维 coverage 都 = 1000‰</b>。对每维算 {@code surplusFraction = (supply − demand) /
 *       demand}（需求 0 维 <b>skip</b>，不参与也不把它的供给算进超支），取<b>需求加权的平均超支</b>（权重 = 该维需求；两维需求都为 0 ⇒ 无加成）：
 *       {@code s = floor(100 × Σ剩余 / Σ需求)}；{@code bonus‰ = min(MAX_BONUS_PER_MILLE,
 *       MAX_BONUS_PER_MILLE × s / (s + BONUS_SATURATION))}（整数、向下取整）；
 *   <li><b>效率</b>：{@code coverageMin = min(securityCoverage, paperworkCoverage)}；{@code efficiency‰
 *       = min(COVERAGE_FULL_PER_MILLE + MAX_BONUS_PER_MILLE, coverageMin × (COVERAGE_FULL_PER_MILLE
 *       + bonus) / COVERAGE_FULL_PER_MILLE)}。
 * </ul>
 *
 * <p>★★ <b>10 万人口、非城市格的手算例</b>（与 {@link GovRules} 类注同源：{@code ceil(100000/500)=200} 名治安、 {@code
 * ceil(100000/1000)=100} 名文书；合计需求 300，其中治安 200、文书 100）：
 *
 * <ul>
 *   <li><b>满编</b> {@code YAMEN=200, SCRIBE+POST=100}：两维 coverage 都是 1000‰ ⇒ 剩余 0 ⇒ {@code s=0} ⇒
 *       {@code bonus=0‰} ⇒ {@code efficiency = 1000×(1000+0)/1000 = 1000‰}；
 *   <li><b>超编 +10%</b> {@code YAMEN=220, SCRIBE+POST=110}：剩余 20+10=30，加权超支 {@code s =
 *       floor(100×30/300) = 10} ⇒ {@code bonus = floor(100×10/(10+10)) = 50‰} ⇒ {@code efficiency =
 *       1000×(1000+50)/1000 = 1050‰}；
 *   <li><b>超编 +20%</b> {@code YAMEN=240, SCRIBE+POST=120}：剩余 40+20=60 ⇒ {@code s =
 *       floor(100×60/300) = 20} ⇒ {@code bonus = floor(100×20/30) = 66‰}（精确 66.7‰，向下取整）⇒ {@code
 *       efficiency = 1000×1066/1000 = 1066‰}；
 *   <li><b>只覆盖一维</b> {@code YAMEN=150, SCRIBE+POST=100}：文书 coverage=1000、治安 {@code =
 *       floor(150×1000/200) = 750} ⇒ 两维不都满 ⇒ {@code bonus=0‰} ⇒ {@code efficiency = min(1100,
 *       750×(1000+0)/1000) = 750‰}。
 * </ul>
 *
 * <p>★ <b>空需求表</b>：两维需求都是 0 ⇒ 两维 coverage 都记 1000‰、bonus=0、efficiency=1000‰（"无需求 =
 * 全额覆盖"是公式值，不是"没有数据"； 无数据是 {@code GovOfficeState.empty} 的全 0 读数）。
 *
 * <p>★ <b>纯函数</b>：不写状态、不调命令；同一输入逐字段相同。
 */
public final class GovEfficiency {

  private GovEfficiency() {}

  /**
   * 算一个 GOV 编制的行政效率读数。
   *
   * @param gov 编制（{@code staff} 提供供给、{@code policy} 不参与效率）；不得为 null
   * @param demand 逐格需求（{@link GovDemand#of} 的输出；空表 = 无需求）；不得为 null、键值不得为 null
   * @return 覆盖率 / 加成 / 效率读数（四个字段都做界校验）
   */
  public static Efficiency of(GovFormation gov, Map<HexCoord, GovDemand.HexDemand> demand) {
    requireGov(gov);
    requireDemand(demand);

    long securitySupply = securitySupply(gov);
    long paperworkSupply = paperworkSupply(gov);
    long securityDemand = securityDemand(demand);
    long paperworkDemand = paperworkDemand(demand);

    long securityCoverage = coverage(securitySupply, securityDemand);
    long paperworkCoverage = coverage(paperworkSupply, paperworkDemand);

    long bonusPerMille = 0L;
    if (securityCoverage == GovRules.COVERAGE_FULL_PER_MILLE
        && paperworkCoverage == GovRules.COVERAGE_FULL_PER_MILLE) {
      bonusPerMille = bonus(securitySupply, securityDemand, paperworkSupply, paperworkDemand);
    }

    long coverageMin = Math.min(securityCoverage, paperworkCoverage);
    long efficiencyPerMille =
        Math.min(
            GovRules.COVERAGE_FULL_PER_MILLE + GovRules.MAX_BONUS_PER_MILLE,
            Math.floorDiv(
                coverageMin * (GovRules.COVERAGE_FULL_PER_MILLE + bonusPerMille),
                GovRules.COVERAGE_FULL_PER_MILLE));

    return new Efficiency(securityCoverage, paperworkCoverage, bonusPerMille, efficiencyPerMille);
  }

  /** 治安供给：{@code YAMEN} 在编人数（缺角色 = 0）。package-private：每日结算的信号 evidence 与本方法共用同一份求和。 */
  static long securitySupply(GovFormation gov) {
    requireGov(gov);
    return gov.staff().getOrDefault(StaffRole.YAMEN, 0L);
  }

  /** 文书供给：{@code SCRIBE + POST} 在编人数（缺角色 = 0）。★ 两个角色同口径，但驿传不另算第三种需求。 */
  static long paperworkSupply(GovFormation gov) {
    requireGov(gov);
    return gov.staff().getOrDefault(StaffRole.SCRIBE, 0L)
        + gov.staff().getOrDefault(StaffRole.POST, 0L);
  }

  /** 治安需求汇总（逐格 {@code security} 求和）。 */
  static long securityDemand(Map<HexCoord, GovDemand.HexDemand> demand) {
    requireDemand(demand);
    long total = 0L;
    for (GovDemand.HexDemand hexDemand : demand.values()) {
      total += hexDemand.security();
    }
    return total;
  }

  /** 文书需求汇总（逐格 {@code paperwork} 求和）。 */
  static long paperworkDemand(Map<HexCoord, GovDemand.HexDemand> demand) {
    requireDemand(demand);
    long total = 0L;
    for (GovDemand.HexDemand hexDemand : demand.values()) {
      total += hexDemand.paperwork();
    }
    return total;
  }

  /** 覆盖率：需求 0 ⇒ 满；否则 {@code min(满, supply×满/demand)}（整数、向下取整）。 */
  private static long coverage(long supply, long demand) {
    if (demand == 0L) {
      return GovRules.COVERAGE_FULL_PER_MILLE;
    }
    return Math.min(
        GovRules.COVERAGE_FULL_PER_MILLE,
        Math.floorDiv(supply * GovRules.COVERAGE_FULL_PER_MILLE, demand));
  }

  /**
   * 超编加成（只在两维覆盖率都满时调用）。
   *
   * <p>★ 需求 0 维 <b>skip</b>：它的"剩余供给"不算进分子、需求也不算进分母（否则一个没有文书需求的辖区会因为书吏多而虚增加成）。 两维需求都为 0 ⇒ 返回 0。★
   * 只取需求 &gt; 0 的维度加权，权重 = 该维需求；因为 {@code surplusFraction_i × demand_i = supply_i −
   * demand_i}，加权平均可精确化简为 {@code Σ剩余 / Σ需求}，不逐维取整、只在 {@code s} 与 {@code bonus} 两处向下取整。
   */
  private static long bonus(
      long securitySupply, long securityDemand, long paperworkSupply, long paperworkDemand) {
    long totalDemand = securityDemand + paperworkDemand;
    if (totalDemand == 0L) {
      return 0L;
    }
    long totalSurplus = 0L;
    if (securityDemand > 0L) {
      totalSurplus += securitySupply - securityDemand;
    }
    if (paperworkDemand > 0L) {
      totalSurplus += paperworkSupply - paperworkDemand;
    }
    long surplusPercent = Math.floorDiv(GovRules.MAX_BONUS_PER_MILLE * totalSurplus, totalDemand);
    return Math.min(
        GovRules.MAX_BONUS_PER_MILLE,
        Math.floorDiv(
            GovRules.MAX_BONUS_PER_MILLE * surplusPercent,
            surplusPercent + GovRules.BONUS_SATURATION));
  }

  private static void requireGov(GovFormation gov) {
    if (gov == null) {
      throw new IllegalArgumentException("gov 不得为 null");
    }
  }

  private static void requireDemand(Map<HexCoord, GovDemand.HexDemand> demand) {
    if (demand == null) {
      throw new IllegalArgumentException("demand 不得为 null（无需求用 Map.of()）");
    }
    for (Map.Entry<HexCoord, GovDemand.HexDemand> entry : demand.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("demand 的键与值都不得为 null");
      }
    }
  }

  /**
   * 行政效率读数（阶段 11a）：四个 per-mille 字段，界与 {@link GovOfficeState} 逐字段相同。
   *
   * <p>★ <b>构造期校验</b>：{@code securityCoveragePerMille}/{@code paperworkCoveragePerMille ∈
   * [0,1000]}；{@code bonusPerMille ∈ [0,100]}；{@code efficiencyPerMille ∈ [0,1100]}（= 覆盖满 1000‰ ×
   * 最高 +10% 加成）。越界当场抛 {@link IllegalArgumentException}，不静默钳制。
   *
   * @param securityCoveragePerMille 治安覆盖率（‰；0..1000）
   * @param paperworkCoveragePerMille 文书覆盖率（‰；0..1000）
   * @param bonusPerMille 超编加成（‰；0..100，仅两维覆盖都满时可为正）
   * @param efficiencyPerMille 行政效率（‰；coverageMin × (1 + bonus)，上限 1100）
   */
  public record Efficiency(
      long securityCoveragePerMille,
      long paperworkCoveragePerMille,
      long bonusPerMille,
      long efficiencyPerMille) {

    public Efficiency {
      requireRange(
          securityCoveragePerMille,
          0L,
          GovRules.COVERAGE_FULL_PER_MILLE,
          "securityCoveragePerMille");
      requireRange(
          paperworkCoveragePerMille,
          0L,
          GovRules.COVERAGE_FULL_PER_MILLE,
          "paperworkCoveragePerMille");
      requireRange(bonusPerMille, 0L, GovRules.MAX_BONUS_PER_MILLE, "bonusPerMille");
      requireRange(
          efficiencyPerMille,
          0L,
          GovRules.COVERAGE_FULL_PER_MILLE + GovRules.MAX_BONUS_PER_MILLE,
          "efficiencyPerMille");
    }

    private static void requireRange(long value, long min, long max, String field) {
      if (value < min || value > max) {
        throw new IllegalArgumentException(field + " 必须 ∈ [" + min + "," + max + "]: " + value);
      }
    }
  }
}
