package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ModeMigrationCandidateBook.totalDraftPopulation;
import static io.mosire.simos.economy.time.ModeMigrationRules.currentCurrency;
import static io.mosire.simos.economy.time.ModeMigrationRules.currentOf;
import static io.mosire.simos.economy.time.ModeMigrationRules.demandHorizonDays;
import static io.mosire.simos.economy.time.ModeMigrationRules.globalHexes;
import static io.mosire.simos.economy.time.ModeMigrationRules.producingPositionsByMode;
import static io.mosire.simos.economy.time.ModeMigrationRules.sortedHouseholds;
import static io.mosire.simos.economy.time.ModeMigrationRules.sortedModes;
import static io.mosire.simos.economy.time.ModeMigrationRules.splitDebt;
import static io.mosire.simos.economy.time.ModeMigrationRules.splitMoneyByCurrency;
import static io.mosire.simos.economy.time.ModeMigrationRules.sum;
import static io.mosire.simos.economy.time.ModeMigrationScoreBook.planForSource;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.time.ModeMigrationCandidateBook.CandidateTally;
import io.mosire.simos.economy.time.ModeMigrationCandidateBook.MoveDraft;
import io.mosire.simos.economy.time.ModeMigrationRules.HouseholdMode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>P10.2 / P10.5 家户生产方式迁移计划器（架构 §5.2 的 ⑧；D-024 起换权重来源）</b>：用 {@link ExpectedProfitBook} 对（家户 ×
 * 候选 mode × hex × 市场）现算的<b>预期</b>单位劳动净收益做权重，输出确定性的 "人/钱/债转移到目标 mode 家户"的计划。
 *
 * <p>★★ <b>D-024 的两处替换</b>：① 候选门槛不再要求该 (mode, hex) 已有 {@link EnterpriseProfitBook} 真实读数
 * （`hasReading`/`labor>0` 门槛已删）；② A 规则不再读旧 {@code output × 价 − 投入} 的局部公式，改读同一个 {@link
 * ExpectedProfitBook.Prospect}。{@link EnterpriseProfitBook} 仍由 {@code collect} 产出、供读数对账，只是 不再是候选门槛。
 *
 * <pre>
 * 目标权重 weight = max(0, target.netPerLaborScaled − current.netPerLaborScaled)
 *                （同一把百万分之一单位的高精度尺；两侧都来自 ExpectedProfitBook）
 * 目标排序       weight 降序 → 距离升序（同格 0、邻格 1）→ (hex, mode) 规范串升序 → 家户 id 升序
 * ★★ W9 转业速度 = **预期收益差驱动**（用户裁定 v3.30 §7 第 2 条）：
 *      speedPerMille = clamp(⌊SLOPE × (收益_其他 − 收益_当前) ÷ 分母⌋, 0, 1000)
 *      分母 = max(|收益_当前|, |收益_其他|, **收益规模基准**) ← ★★★ W9-fix：**正参考基准**，见 profitScaleBasisScaled
 *      收益差 ≤ 0 ⇒ 速度 0 ⇒ **不转业**（候选在 buildTargets 就被挡在"活候选"之外）
 *      ⇒ 债务/资产**不再**进转业速度（那条只属于同生产方式内的阶层滑落，ClassSlideSettlement）
 * ★★★ W9-fix 转业**速率上限**（控制方裁定 v3.32 §7 第 2 条）：
 *      每市场轮每户可转人口 ≤ max(1, ⌊人口 × MIGRATION_SPEED_CAP_PER_MILLE ÷ 1000⌋)
 *      与 A 规则（整户消亡满速 1000‰）**分开**：A 规则两支不受本上限约束
 * ★★ W9 目标档位 = **这户带来的库存能支撑的最高档**（用户裁定 v3.29/v3.30 §7）：
 *      档位 = 目标产业 slots() 的四个阶层槽位（贫农→中农→富农→地主）；见 {@link #supportedTierIndex}
 *      支撑不了高档 ⇒ 降到低档；最低档（贫农）**不设门槛**（"没有库存也能转业"，用户原话②）
 * A 规则        expectedNet(current) < 0 且 cash + sellableInventory − nextCycleInputNeed < 0
 *                · 存在 netPerLaborScaled 严格更高的可行目标 ⇒ transferSpeed = 1000‰，把人口转出
 *                · 当前户已是最优（或没有更高可行目标）⇒ 不转移、继续生产，债务/欠款走现有资本化
 *                · 无法维持生产且没有任何能承载的可行目标 ⇒ 转 DISPLACED（同样 1000‰）
 * 新建可行性      同一 plan() 共享"闲置资产预留账本"（IndustryId × AssetKind）；
 *                available = Σ 闲置(quantity>0 ∧ operator==owner ∧ id∉claimed) − 已预留，
 *                claimed = 既有组织 assetSources ∪ 在产 unit 所占份额（同 industry、同 operator、quantity>0），
 *                任一 required 不足则整笔不预留。
 *                后一个新建户/后一遍 allocate 只看前面计划消费后的剩余，与执行期逐笔拆份额一致。
 *                ★ 闲置判据的唯一拼写点 = {@link #isIdleShare(OwnershipStake, Set)}（policy/settlement 共用）：
 *                  只有 owner==operator 且不被任何组织/在产 unit 使用的份额才算闲置（D-024 修复 1b）。
 * </pre>
 *
 * <p>★★ <b>D-022 硬约束</b>：计划里只有 {@code MigrationMove}（源户 → 目标户）；{@code targetMode} 只属于目标家户， 绝不产生"把源户
 * mode/standing/enterprise/unit.modeKey 原地改成 targetMode"的计划项。
 *
 * <p>★★ <b>确定性</b>：无随机、无时钟、无 UUID；全部遍历按稳定 id / (q,r) 排序；同输入同计划。
 */
public final class ModeMigrationPolicy {

  /** 迁移政策日志（migration 分类：候选评估与"为什么没成"，§一.9）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  private ModeMigrationPolicy() {}

  /** A 规则触发后的转移速度（‰；架构 §10）★ 同时是**转业速度的上界**（W9：收益差映射的 clamp 上限）。 */
  public static final long A_RULE_TRANSFER_SPEED_PER_MILLE = 1000L;

  /**
   * ★★★ <b>W9（2026-10-11，用户裁定 v3.30 §7 第 2 条）：转业速度的斜率 —— 政策常量（GM 可调预设）</b>。
   *
   * <pre>
   * speedPerMille = clamp(⌊SLOPE × (收益_其他 − 收益_当前) ÷ 分母⌋, 0, A_RULE_TRANSFER_SPEED_PER_MILLE)
   * 分母（W9-fix 具名）= max(|收益_当前|, |收益_其他|, 收益规模基准)   ← 见 {@link #profitScaleBasisScaled}
   *
   * SLOPE = 100 ⇒ 相对收益差 0.5 ⇒ 50‰/轮（每 4,000 人迁 200 人）
   *                相对收益差 1.0 ⇒ 100‰/轮
   *                相对收益差 ≥ 10 ⇒ 1000‰/轮（旧形状；W9-fix 后分母含 |收益_其他| ⇒ 实际 ≤ 200‰，
   *                再被 {@link #MIGRATION_SPEED_CAP_PER_MILLE} 这类人口闸夹住）
   * </pre>
   *
   * <p>★ <b>为什么取 100（控制方冻结的默认形状，可被推翻）</b>：它让"收益差 10 倍 ⇒ 满速"这个量级与既有的 A 规则（1000‰）对齐，而在常见的 0.1~1
   * 倍差区间里给出 10‰~100‰/轮 —— 与 W4 之前的基线（10‰）同量级，不会让世界在 一个市场轮里整体换位。★ 它是**政策标定值**（非用户裁定），与 α、阶层滑落斜率同族：GM
   * 可调，见本类 §"写口建议"。
   *
   * <p>★★ <b>写口建议（本批不实现，只具名）</b>：本仓既有的"行为预设"落点是 {@code MarketZone} 逐区字段（α 的先例，GM 写口 {@code
   * economy.SetMarketZonePriceAlpha}）与 {@code Government} 的世界级政策旋钮（{@code mintScalePerCycle}，GM 写口
   * {@code economy.SetGovernmentMintScale}）。本批按"编译期常量"落地（与 {@code
   * ClassSlideSettlement.SLIDE_SPEED_PER_MILLE_PER_RATIO} 同制），**不新增持久组件** —— 要 GM 可调就得动持久形状 （铁律 5
   * 三件套 + GM 命令），那是另一批；具名提案见 W9-fix 账本（宿主 = 世界级政策组件，**不是**逐区的 {@code MarketZone}、也**不是**逐辖区的 {@code
   * Government}：转业是全世界同一条口径）。
   */
  public static final long PROFIT_GAP_SPEED_SLOPE_PER_MILLE = 100L;

  /**
   * ★★★ <b>W9-fix（2026-10-11，控制方裁定 v3.32 §7 第 2 条）：转业<b>速率上限</b> —— 政策预设（GM 可调）</b>。
   *
   * <pre>
   * 每市场轮每户可转人口 ≤ max(1, ⌊该户人口 × MIGRATION_SPEED_CAP_PER_MILLE ÷ 1000⌋)
   * 默认 100‰ = 每轮最多 10% 人口；1000‰ 只属于 **A 规则**（整户消亡 / 流民吸收满速，见 A_RULE_TRANSFER_SPEED_PER_MILLE）
   * </pre>
   *
   * <p>★★ <b>为什么必须有它（W9d 实测，不是洁癖）</b>：没有上限时，"预期收益差"会把整个世界一次搬走 —— W9 首版 three-powers 0→240 实测 **sw
   * 68/91、tp 62/93 笔 = 1000‰**、单笔最多搬 **199 人**（整户迁空）⇒ 一轮搬空家户/整格 ⇒ **21 个零成交市场轮**（W7e 基线 =
   * 0，硬牙被打破）。用户原话只说"差越大越快"，**没有**说"可以无限快" ⇒ 速率上限是"差越大越快"这条判据的**必要卫生**，不是对它打折。
   *
   * <p>★ <b>与 A 规则分开（用户口径边界）</b>：A 规则（{@code expectedNet < 0 ∧ 流动性 < 下一轮投入} ⇒ 1000‰ 满速转出， 含 {@code
   * DISPLACED_ABSORBED} 支）**不受本上限约束** —— 那两支护的是"整户消亡/被吸收"，不是"收益差驱动的转业"。
   *
   * <p>★ <b>它不是速度算式的一部分</b>：{@code MigrationMove.transferSpeedPerMille} 仍报收益差算式的值（判据"差 &gt; 0 有速度"
   * 逐值可读），本上限是**独立的人口闸**（可迁人口 ≤ 人口 × 上限‰ ÷ 1000）—— 两者在 {@code MIGRATION_MOVE} 里两栏同时可读。
   *
   * <p>★★ <b>取值与标定</b>：默认 **100‰**（保守值，控制方裁定"建议保守"）。它列 **W6 标定首查项**（与 α、滑落斜率、铸币旋钮同族）： 100‰
   * 是否让世界"搬得太快/太慢"，用真世界的人口分布 + 零成交轮次标定。★ 它是**政策标定值**（非用户裁定）。
   *
   * <p>★★ <b>GM 写口（本批只具名，未实现；见账本"具名提案"）</b>：宿主应当是**世界级政策组件**（转业是全世界同一条口径）， 而不是逐区的 {@code
   * MarketZone}（α 那种）或逐辖区的 {@code Government}；落地形态 = 新增 {@code EconomyData} 政策组件 +
   * Codec/ChangeSet（铁律 5 三件套 + 往返）+ GM-only 写命令（形制照 {@code economy.SetGovernmentMintScale}： 与现值相同 ⇒
   * 具名拒 {@code cap-unchanged}）。缺省语义中性：没有该组件 ⇒ 用本常量。
   */
  public static final long MIGRATION_SPEED_CAP_PER_MILLE = 100L;

  /** 单个家户承载上限（架构 §10：新家户 200 人/户；合并目标同样按它判剩余容量）。 */
  public static final long MAX_HOUSEHOLD_POPULATION = 200L;

  /** 需求簿视界的兜底（没有任何产业模板时）：与真实运行时世界的 120 天周期同值。 */
  /** 一条迁移计划（不可变、保序：按源家户 id、再按目标排序）。 */
  public record MigrationPlan(List<MigrationMove> moves) {

    public MigrationPlan {
      Objects.requireNonNull(moves, "moves");
      List<MigrationMove> copy = new ArrayList<>(moves.size());
      for (MigrationMove move : moves) {
        copy.add(Objects.requireNonNull(move, "MigrationMove"));
      }
      moves = Collections.unmodifiableList(copy);
    }

    public static MigrationPlan empty() {
      return new MigrationPlan(List.of());
    }

    public boolean isEmpty() {
      return moves.isEmpty();
    }
  }

  /**
   * 一条迁移：源户人口/货币/债务按比例转到目标家户。
   *
   * <p>★ {@code target} 已有家户（合并）或计划新建家户 id（新建）；{@code target != source} 由构造期守卫判死； {@code
   * targetMode} 是<b>目标家户</b>的 mode，源户 mode 在整条执行路径上一字不改。
   *
   * <p>★★ <b>D-023：货币随迁按全部币种</b> —— 权威口径是 {@link #moneyByCurrency()}（逐币种按人口比例
   * floor；源户迁空时该币种余数随最后一笔走；<b>不做 FX</b>）。{@link #moneyMilli()} 只保留为旧读口（计划的主币种 份额），执行器以 {@code
   * moneyByCurrency} 为准；空 map = 旧/手工计划 ⇒ 执行器就地按人口比例补算全部币种。
   *
   * <p>★★ <b>M-D 追加 {@code primaryPositionId}：主业落点</b>（设计书 §13 I-A/I-D、I-3）—— 该笔 move
   * 的**生产方式排序表**里， 与 {@code (targetMode, targetHex)} 相符的那一项的**位置 id**；它就是新建目标家户 {@code
   * currentPositionId} 的落点 （"排序表第 1 项 ⇒ 主业"，落点仍走既有模式变迁路径 {@code ModeMigrationSettlement}）。★ {@code
   * null} = 没有排序表落点 （旧调用方/夹具）⇒ 执行器退回既有的 {@code pickTargetPosition}（按 relationToMeans+surplusRole
   * 匹配、再位置 id 升序）—— **逐值不变**。
   *
   * @param primaryPositionId 排序表第 1 项的落点位置（可 {@code null} = 按既有口径选位置）
   * @param targetTierIndex ★★★ <b>W9：这一笔落到目标产业的第几档</b>（{@code IndustryClassSlots.IDS} 的下标：0 = 贫农 …
   *     3 = 地主；见 {@link #supportedTierIndex}）—— 它只决定**新建目标户**的阶层槽位（{@code view.stratum}）与落点档位；
   *     并入既有户时目标户的档位是它自己的（本栏只是"这笔本可支撑到第几档"的读数）。旧/手工计划缺这一维 ⇒ 0（最低档 = 不设门槛的兜底档，逐值等于"没有库存也能转业"）。
   */
  public record MigrationMove(
      HouseholdId source,
      HouseholdId target,
      HexCoord targetHex,
      ProductionModeId targetMode,
      long transferSpeedPerMille,
      long population,
      long moneyMilli,
      long debtMilli,
      String reason,
      Map<CurrencyId, Long> moneyByCurrency,
      ClassPositionId primaryPositionId,
      int targetTierIndex) {

    /** 迁移原因词表（规范串）。 */
    public static final String REASON_PROFIT_WEIGHTED = "PROFIT_WEIGHTED";

    public static final String REASON_A_RULE_MAX_SPEED = "A_RULE_MAX_SPEED";
    public static final String REASON_DISPLACED_ABSORBED = "DISPLACED_ABSORBED";

    /** 旧读口的 10 参构造（没有排序表落点 ⇒ {@code primaryPositionId = null}，执行器按既有口径选位置）。 */
    public MigrationMove(
        HouseholdId source,
        HouseholdId target,
        HexCoord targetHex,
        ProductionModeId targetMode,
        long transferSpeedPerMille,
        long population,
        long moneyMilli,
        long debtMilli,
        String reason,
        Map<CurrencyId, Long> moneyByCurrency) {
      this(
          source,
          target,
          targetHex,
          targetMode,
          transferSpeedPerMille,
          population,
          moneyMilli,
          debtMilli,
          reason,
          moneyByCurrency,
          null,
          0);
    }

    /** 旧读口的 9 参构造（全币种 map 为空 ⇒ 执行器就地按人口比例补算，不静默丢任何币种）。 */
    public MigrationMove(
        HouseholdId source,
        HouseholdId target,
        HexCoord targetHex,
        ProductionModeId targetMode,
        long transferSpeedPerMille,
        long population,
        long moneyMilli,
        long debtMilli,
        String reason) {
      this(
          source,
          target,
          targetHex,
          targetMode,
          transferSpeedPerMille,
          population,
          moneyMilli,
          debtMilli,
          reason,
          Map.of(),
          null,
          0);
    }

    public MigrationMove {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(targetHex, "targetHex");
      Objects.requireNonNull(targetMode, "targetMode");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(moneyByCurrency, "moneyByCurrency");
      if (source.equals(target)) {
        throw new IllegalArgumentException(
            "MigrationMove.target 不得 == source（D-022：不原地改源户）: " + source);
      }
      if (population <= 0L) {
        throw new IllegalArgumentException("MigrationMove.population 必须 > 0: " + population);
      }
      if (moneyMilli < 0L || debtMilli < 0L) {
        throw new IllegalArgumentException(
            "MigrationMove 的 moneyMilli/debtMilli 不得为负: " + moneyMilli + "/" + debtMilli);
      }
      // ★★ W9（2026-10-11，用户裁定 v3.30 §7 第 2 条）：速度**由预期收益差驱动** ——
      //   speed = clamp(⌊SLOPE × 收益差 ÷ max(1,|收益_当前|)⌋, 0, 1000)，**0 = 不转业**（调用方在产生 move 之前挡掉）。
      //   口径 = ‰/轮，合法区间 [1, 1000]（0 的 move 不是"慢"，是"不该存在"）。
      if (transferSpeedPerMille < 1L || transferSpeedPerMille > A_RULE_TRANSFER_SPEED_PER_MILLE) {
        throw new IllegalArgumentException(
            "MigrationMove.transferSpeedPerMille 必须在 [1, "
                + A_RULE_TRANSFER_SPEED_PER_MILLE
                + "]（W9：收益差驱动 = clamp(⌊SLOPE × 收益差 ÷ max(1,|收益_当前|)⌋, 0, "
                + A_RULE_TRANSFER_SPEED_PER_MILLE
                + ")；收益差 ≤ 0 ⇒ 0 ⇒ 不产生 move）: "
                + transferSpeedPerMille);
      }
      if (targetTierIndex < 0) {
        throw new IllegalArgumentException(
            "MigrationMove.targetTierIndex 不得为负（0 = 最低档/兜底档）: " + targetTierIndex);
      }
      LinkedHashMap<CurrencyId, Long> moneyCopy = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> entry : moneyByCurrency.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "MigrationMove.moneyByCurrency 的键与值都不得为 null: " + entry.getKey());
        }
        if (entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              "MigrationMove.moneyByCurrency 的份额不得为负: "
                  + entry.getKey()
                  + " = "
                  + entry.getValue());
        }
        if (entry.getValue() > 0L) {
          moneyCopy.put(entry.getKey(), entry.getValue());
        }
      }
      moneyByCurrency = Collections.unmodifiableMap(moneyCopy); // ★ 冻在赋值处
    }
  }

  /** 当前户的预期读数（A 规则两条件与"能否维持生产"都读它）。 */
  public record ExpectedProfit(
      long expectedNetMilli,
      long expectedLaborMilli,
      long nextCycleInputNeedMilli,
      long liquidityMilli,
      boolean canSustainProduction,
      String reason) {

    public ExpectedProfit {
      Objects.requireNonNull(reason, "reason");
      if (expectedLaborMilli < 0L || nextCycleInputNeedMilli < 0L || liquidityMilli < 0L) {
        throw new IllegalArgumentException("ExpectedProfit 的劳动/投入/流动性读数不得为负");
      }
    }

    public long expectedNetPerLaborMilli() {
      return expectedNetMilli / Math.max(1L, expectedLaborMilli);
    }
  }

  /** 计划入口：只读全部入参，返回确定性计划。 */
  public static MigrationPlan plan(
      EconomyDayView base,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<MarketZoneId, MarketTable> markets,
      Map<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      EnterpriseProfitBook.Book book,
      long day,
      MarketTopology topology,
      List<MarketReport> marketReports,
      ProductionRuntime productionRuntime) {
    AssetOccupancyBudget assetBudget =
        ProductionProcessBook.assetOccupancyBudget(
            units,
            assetShares,
            base.assetOccupancies(),
            base.industries(),
            base.productionRosters(),
            laborCommitments,
            day);
    return planWithBudget(
        base,
        enterprises,
        units,
        householdEconomies,
        classMemberships,
        assetShares,
        relations,
        laborCommitments,
        markets,
        debts,
        accounts,
        book,
        day,
        topology,
        marketReports,
        productionRuntime,
        assetBudget);
  }

  /** ★★★ D4-Ib2a：调用方已持有同一 revision 的唯一占用预算时走本重载。 */
  public static MigrationPlan plan(
      EconomyDayView base,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<MarketZoneId, MarketTable> markets,
      Map<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      EnterpriseProfitBook.Book book,
      long day,
      MarketTopology topology,
      List<MarketReport> marketReports,
      ProductionRuntime productionRuntime,
      AssetOccupancyBudget assetBudget) {
    return planWithBudget(
        base,
        enterprises,
        units,
        householdEconomies,
        classMemberships,
        assetShares,
        relations,
        laborCommitments,
        markets,
        debts,
        accounts,
        book,
        day,
        topology,
        marketReports,
        productionRuntime,
        assetBudget);
  }

  private static MigrationPlan planWithBudget(
      EconomyDayView base,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<MarketZoneId, MarketTable> markets,
      Map<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      EnterpriseProfitBook.Book book,
      long day,
      MarketTopology topology,
      List<MarketReport> marketReports,
      ProductionRuntime productionRuntime,
      AssetOccupancyBudget assetBudget) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(enterprises, "organizations");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(householdEconomies, "rows");
    Objects.requireNonNull(classMemberships, "standings");
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(laborCommitments, "allocations");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(book, "book");
    Objects.requireNonNull(relations, "relations");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(marketReports, "marketReports（没有报告给空表）");
    Objects.requireNonNull(
        productionRuntime, "productionRuntime（§15.12 B：候选/当前户的物理量必须取自 production 的试算）");
    // ★★ P10.6：计划期共享的"闲置资产预留账本"。同一 plan() 的所有源户、所有目标新建户、allocate 的两遍
    //    都读写同一份账本 ⇒ 后一个新建户看到的是前面计划已消费后的剩余闲置资产，与执行期逐笔拆份额一致。
    Map<IndustryId, Map<AssetKind, Long>> reservedIdle = new LinkedHashMap<>();
    // ★★ P10.7 / P10.8：既有 ProductionEnterprise.assetSources 正在使用的份额不是"闲置"——执行期把它拆/移走后，
    //    组织引用会指向已删除的份额 id，EconomyData 当即以「生产组织使用的资产份额必须已存在」拒绝。
    //    claimed 必须读调用方传入的当天工作副本 enterprises + units + assetShares（可能已被当天自动组织阶段加入
    //    新组织/新 unit），不能用当天开始时的不可变 base；只在这里构建一次，与预留账本一起透传；谓词与执行期共用
    //    {@link #isIdleShare}，不新增持久组件。
    //    ★★ D-024 修复 1：同一份集合也作为唯一入参透传给所有 {@link ExpectedProfitBook#prospect} 调用
    //    （当前户读数 + 候选），prospect 内部不得再从 base.productionOrganizations() 重算（那是过期快照）。
    //    ★★ D-024 修复 1b：claimed 的"单位占用"格 = 与在产 unit 同 industry、同 operator 且 quantity>0 的份额。
    //    seeder 直接以 RegimeOperators.defaultOperator(...)（ESTATE 等）建立的 farm/weave/craft/trade 主 unit
    // 没有
    //    对应 ProductionEnterprise，只按组织引用格会把它们的土地/作坊/船畜误判成"闲置可租"。
    //    单参兼容重载只算组织引用格，生产路径一律走三参版本。
    Set<AssetShareId> claimedByEnterprises =
        claimedOwnershipStakes(enterprises, units, assetShares);
    if (base.modes().isEmpty()) {
      return MigrationPlan.empty(); // 旧档（无 modes）：逐字 no-op
    }
    Objects.requireNonNull(assetBudget, "assetBudget 不得为 null（D4 预算不可用时 fail-closed）");

    // ★★ D-024 / 设计 §3.3：需求簿只构建一次，透传给所有源户/候选评估（horizonDays = 世界产业 cycleDays 的统一值；
    //   多个不同值时取最大者——本轮真实世界都是 120）。
    long horizonDays = demandHorizonDays(base, units);
    MarketDemandBook.Book demandBook =
        MarketDemandBook.build(
            marketReports,
            topology,
            householdEconomies,
            units,
            base.industries(),
            assetShares,
            base.shipments(),
            accounts,
            markets,
            day,
            horizonDays,
            assetBudget);

    // ★★★ W9（2026-10-11，用户裁定 v3.30 §7）：**"壳户"不做源户候选**（用户派单 ⑥）。
    //   W7e 真世界读数：`MODE_MIGRATION_PROSPECT_INFEASIBLE` 的 9,547(sw)/21,443(tp) 条里
    //   **82~93% 是 `NO_MERCHANT_CAPACITY:no-labor`** —— 它们来自**人口 0 的壳户**与**劳动 0 的城镇地主户**：
    //   这两种户**在本生产方式与其他生产方式里都没有劳动可投**（任何位置的可生产判据都过不了），
    //   每轮空转只会刷屏，不会产生任何迁移。⇒ 在候选阶段具名跳过，**只进轮级汇总**（不逐户刷屏）。
    //   ★ 这不是"缺劳动 ⇒ 否决"：它只是"没有劳动可投的户不参与候选评估"——用户 ② 的"没库存也要能转业"
    //     针对的是**有劳动、没库存**的户，那些户照旧进候选（并落到最低档，见 supportedTierIndex）。
    // ★★★ W9-fix（2026-10-11，U1 修复，控制方裁定 v3.32 §7 第 3 条）：**两张表必须分开** ——
    //   改前的缺陷：同一张 `modeByHousehold` 既当**源户候选表**、又当 `bestExistingTarget` 的**并入目标表**
    //   ⇒ "参与率折算劳动 = 0"的户在候选阶段被跳过之后，**它们也不再能被并入**（W9 前可以）。
    //   真世界影响面（W7e 实测）：`no-labor` 的 82~93% 来自**城镇地主族**（人口 > 0、劳动 0）—— 它们正是
    //   "迁入城镇时最自然的并入目标"；被剔出去之后，迁入只能新建户（并撞上 in-kind 闸/资产闸 ⇒ 计划变空）。
    //   ⇒ 现在：
    //     ① {@code modeByHousehold} = **并入目标注册表**：与 W9 之前**逐值同形**（人口 > 0 ∧ 有阶层归属 ∧ 位置可解析），
    //        **不**因"劳动 0"剔户；
    //     ② {@code sourceCandidates} = **源户候选表**：在 ① 之上再要求"参与率折算劳动 > 0"（W9 ⑥ 的原意，
    //        逐值等于 W9 的候选集）。
    CandidateTally tally = new CandidateTally();
    Map<HouseholdId, HouseholdMode> modeByHousehold = new LinkedHashMap<>();
    LinkedHashSet<HouseholdId> sourceCandidates = new LinkedHashSet<>();
    for (HouseholdId household : sortedHouseholds(householdEconomies)) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      HouseholdClassMembership classMembership = classMemberships.get(household);
      if (householdEconomy == null) {
        tally.shellSkipped(true);
        continue;
      }
      if (householdEconomy.population() <= 0L || classMembership == null) {
        tally.shellSkipped(true);
        continue;
      }
      // ★ 计数口径与 W9 逐值相同（先判劳动、再解析位置）：劳动 0 的户**照旧计数**，只是不再被剔出目标表。
      boolean noLabor = householdEconomy.participationAdjustedLaborMilli() <= 0L;
      if (noLabor) {
        tally.shellSkipped(false);
      }
      ProductionRole position = base.classPositions().get(classMembership.currentPositionId());
      if (position == null) {
        continue;
      }
      modeByHousehold.put(
          household,
          new HouseholdMode(
              household, householdEconomy.view().hex(), position.modeId(), position.id()));
      if (!noLabor) {
        sourceCandidates.add(household);
      }
    }
    if (sourceCandidates.isEmpty()) {
      tally.logRound(day, 0L, 0L);
      return MigrationPlan.empty();
    }

    List<HexCoord> hexes = globalHexes(householdEconomies, units, markets, base.marketZones());
    List<ProductionModeDefinitionView> modes = sortedModes(base);
    Map<ProductionModeId, List<ProductionRole>> producingPositions =
        producingPositionsByMode(base, modes);

    // ★★★ W9（2026-10-11，用户裁定 v3.30 §7 第 2 条）：**转业速度不再读债务/资产** ——
    //   那条口径（W4 的 `SettlementOrderBook` 快照 + `ClassSlideSettlement.speedPerMille`）**整体退出转业**，
    //   只留给"同生产方式内的阶层滑落"（用户原话④：「债务规模决定本生产方式内阶层跌落速度，生产方式收益决定
    //   生产方式间转业速度」）。转业速度现在逐候选按**预期收益差**现算（{@link #profitGapSpeedPerMille}），
    //   在 {@code buildTargets} 里随 Target 一起产出。
    // ★★★ W7（2026-10-11）：本轮候选评估的**具名账**（只进日志、不是状态 —— 见 {@link CandidateTally}）。
    LinkedHashMap<HouseholdId, List<MoveDraft>> draftsBySource = new LinkedHashMap<>();
    // ★★★ W9-fix：**源户循环只走 {@code sourceCandidates}**（劳动 > 0 的户）；并入目标表 {@code modeByHousehold}
    //   是另一张表（含劳动 0 的户，见上面的 U1 修复）—— 两张表混用正是 W9 首版的未声明副作用。
    for (HouseholdId source : sourceCandidates) {
      HouseholdMode current = modeByHousehold.get(source);
      if (current == null) {
        continue;
      }
      HouseholdEconomy sourceHouseholdEconomy = householdEconomies.get(source);
      List<MoveDraft> drafts =
          planForSource(
              base,
              source,
              sourceHouseholdEconomy,
              current,
              hexes,
              modes,
              producingPositions,
              modeByHousehold,
              enterprises,
              units,
              householdEconomies,
              assetShares,
              relations,
              markets,
              accounts,
              reservedIdle,
              claimedByEnterprises,
              demandBook,
              topology,
              day,
              tally,
              productionRuntime,
              assetBudget);
      if (!drafts.isEmpty()) {
        draftsBySource.put(source, drafts);
      }
    }
    // ★★★ W7（2026-10-11，§一.9）：**"为什么这一轮没有新跑商"必须能从日志里读出来**（用户派单 ⑤）。
    //   改前这条链在 {@code !prospect.feasible()} 处**静默 continue** —— 调查者只看日志会误判成"非城市格从来没被
    //   评估过"，而真相是"被评估了、每次都具名失败、失败理由一行没打"（运力调查报告 §2.2 的实测）。
    //   ★ 轮级一行（DEBUG，含逐格候选数与**首个失败理由**）+ 逐候选具名行（DEBUG，只对
    //     {@code NO_MERCHANT_CAPACITY:*} 一族发，见 {@link CandidateTally#failure}）。
    tally.logRound(day, draftsBySource.size(), totalDraftPopulation(draftsBySource));
    if (draftsBySource.isEmpty()) {
      return MigrationPlan.empty();
    }

    List<MigrationMove> moves = new ArrayList<>();
    for (HouseholdId source : sortedHouseholds(householdEconomies)) {
      List<MoveDraft> drafts = draftsBySource.get(source);
      if (drafts == null) {
        continue;
      }
      long sourcePopulation = householdEconomies.get(source).population();
      long[] populations = new long[drafts.size()];
      for (int i = 0; i < drafts.size(); i++) {
        populations[i] = drafts.get(i).population;
      }
      boolean emptiesSource = sum(populations) == sourcePopulation;
      List<Map<CurrencyId, Long>> moneySharesByCurrency =
          splitMoneyByCurrency(accounts, populations, sourcePopulation, source, emptiesSource);
      // ★ 旧读口 moneyMilli 仍报"主币种"份额；权威全币种表在 moneyByCurrency（D-023）。
      HouseholdMode legacyCurrent = currentOf(modeByHousehold, source);
      CurrencyId legacyCurrency =
          currentCurrency(
              legacyCurrent,
              accounts,
              markets,
              MarketZoneBook.zoneOfHex(base, legacyCurrent.hex()).orElse(null));
      long[] debtShares = splitDebt(populations, sourcePopulation, source, debts, emptiesSource);
      for (int i = 0; i < drafts.size(); i++) {
        MoveDraft draft = drafts.get(i);
        Map<CurrencyId, Long> moneyByCurrency = moneySharesByCurrency.get(i);
        long legacyMoneyMilli =
            legacyCurrency == null ? 0L : moneyByCurrency.getOrDefault(legacyCurrency, 0L);
        moves.add(
            new MigrationMove(
                source,
                draft.target,
                draft.targetHex,
                draft.targetMode,
                draft.transferSpeedPerMille,
                draft.population,
                legacyMoneyMilli,
                debtShares[i],
                draft.reason,
                moneyByCurrency,
                draft.primaryPositionId,
                draft.tierIndex));
      }
    }
    return new MigrationPlan(moves);
  }

  // ── 逐源户计划 ─────────────────────────────────────────────────────────────────────────────

  // ── P0.5-E1：原包内/公开入口保留，逐项委托到同包 helper（行为不变）────────────────────────

  static int supportedTierIndex(
      IndustryDefinitionView industry, long laborMilliPlan, Map<AssetKind, Long> carriedByKind) {
    return ModeMigrationAssetPolicy.supportedTierIndex(industry, laborMilliPlan, carriedByKind);
  }

  static Map<AssetKind, Long> carriedCapitalByKind(
      HouseholdId source,
      Map<AssetShareId, OwnershipStake> assetShares,
      Set<AssetShareId> claimedByEnterprises,
      Map<CommodityId, Long> goods) {
    return ModeMigrationAssetPolicy.carriedCapitalByKind(
        source, assetShares, claimedByEnterprises, goods);
  }

  static ClassPositionId tierPosition(List<ProductionRole> producing, int tierIndex) {
    return ModeMigrationAssetPolicy.tierPosition(producing, tierIndex);
  }

  static String inKindLegUnavailableReasonOf(
      IndustryId industryId,
      IndustryDefinitionView industry,
      List<CommodityId> inKindCommodities,
      Map<CommodityId, Long> householdStock,
      MarketTable market,
      RegimeId regime) {
    return ModeMigrationScoreBook.inKindLegUnavailableReasonOf(
        industryId, industry, inKindCommodities, householdStock, market, regime);
  }

  static long requiredWorkCapitalScale(IndustryDefinitionView industry, long laborMilli) {
    return ModeMigrationAssetPolicy.requiredWorkCapitalScale(industry, laborMilli);
  }

  static long laborCarriedByMoveUpperBound(
      long sourceLaborMilli, long sourcePopulation, long take) {
    return ModeMigrationAssetPolicy.laborCarriedByMoveUpperBound(
        sourceLaborMilli, sourcePopulation, take);
  }

  static long profitGapSpeedPerMille(long otherNetPerLaborScaled, long currentNetPerLaborScaled) {
    return ModeMigrationScoreBook.profitGapSpeedPerMille(
        otherNetPerLaborScaled, currentNetPerLaborScaled);
  }

  static long profitGapSpeedPerMille(
      long otherNetPerLaborScaled, long currentNetPerLaborScaled, long profitScaleBasisScaled) {
    return ModeMigrationScoreBook.profitGapSpeedPerMille(
        otherNetPerLaborScaled, currentNetPerLaborScaled, profitScaleBasisScaled);
  }

  static long profitScaleBasisScaled(ExpectedProfitBook.Prospect prospect) {
    return ModeMigrationScoreBook.profitScaleBasisScaled(prospect);
  }

  static long moveablePopulation(long population, long speedPerMille) {
    return ModeMigrationScoreBook.moveablePopulation(population, speedPerMille);
  }

  static List<IndustryId> industriesForMode(
      EconomyDayView base, HexCoord hex, ProductionModeId modeId) {
    return ModeMigrationRules.industriesForMode(base, hex, modeId);
  }

  static List<IndustryId> industriesForMode(
      Map<IndustryId, IndustryDefinitionView> industries, HexCoord hex, ProductionModeId modeId) {
    return ModeMigrationRules.industriesForMode(industries, hex, modeId);
  }

  static boolean isIdleShare(OwnershipStake share, Set<AssetShareId> claimedByEnterprises) {
    return ModeMigrationAssetPolicy.isIdleShare(share, claimedByEnterprises);
  }

  static Set<AssetShareId> claimedOwnershipStakes(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    return ModeMigrationAssetPolicy.claimedOwnershipStakes(enterprises);
  }

  public static Set<AssetShareId> claimedOwnershipStakes(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares) {
    return ModeMigrationAssetPolicy.claimedOwnershipStakes(enterprises, units, assetShares);
  }
}
