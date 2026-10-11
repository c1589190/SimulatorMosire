package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
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
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
  private static final long DEFAULT_DEMAND_HORIZON_DAYS = 120L;

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
      List<MarketReport> marketReports) {
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
            horizonDays);

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
    List<ProductionMode> modes = sortedModes(base);
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
              tally);
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
              MarketZoneBook.zoneOfHex(base, legacyCurrent.hex).orElse(null));
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

  private static List<MoveDraft> planForSource(
      EconomyDayView base,
      HouseholdId source,
      HouseholdEconomy sourceHouseholdEconomy,
      HouseholdMode current,
      List<HexCoord> hexes,
      List<ProductionMode> modes,
      Map<ProductionModeId, List<ProductionRole>> producingPositions,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<MarketZoneId, MarketTable> markets,
      AccountSession accounts,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByEnterprises,
      MarketDemandBook.Book demandBook,
      MarketTopology topology,
      long day,
      CandidateTally tally) {
    // ★★ D-024：当前户读数改由同一个预期利润计算器现算（真实利润账退位为事后对账）。
    Industry currentIndustry = currentIndustryOf(base, source, current, units, enterprises);
    ExpectedProfitBook.Prospect currentProspect =
        ExpectedProfitBook.prospect(
            base,
            source,
            current.modeId,
            current.hex,
            currentIndustry,
            marketOf(markets, topology, current.hex),
            demandBook,
            assetShares,
            claimedByEnterprises,
            accounts,
            units,
            relations,
            topology,
            day);
    long currentPerLabor = currentProspect.netPerLaborScaled();
    // ★★★ W9-fix（控制方裁定 v3.32 §7 第 1 条）：速度算式的**正参考基准**（分母下限）之一 ——
    //   **当前生产方式的"收益规模基准"**（1 单位规模劳动的毛收入 / 口粮成本，见 {@link #profitScaleBasisScaled}）。
    //   ★ 它绝不能由 `max(1, |收益_当前|)` 顶替：那正是 W9 首版"收益 ≈ 0 ⇒ 分母退化 1 ⇒ 打满 1000‰"的根因。
    long currentProfitBasisScaled = profitScaleBasisScaled(currentProspect);
    // ★★ M-D：被排序输入挡下的跑商候选（具名；进排序表的 excluded 与 DEBUG 日志）。
    List<PrimaryModeRanking.Excluded> excluded = new ArrayList<>();
    List<Target> targets =
        buildTargets(
            base,
            source,
            sourceHouseholdEconomy,
            current,
            currentPerLabor,
            currentProfitBasisScaled,
            excluded,
            hexes,
            modes,
            producingPositions,
            modeByHousehold,
            householdEconomies,
            units,
            assetShares,
            claimedByEnterprises,
            relations,
            markets,
            accounts,
            demandBook,
            topology,
            day,
            tally);
    // ★★ M-D（设计书 §13 I-A/I-B）：**生产方式排序表** —— 当前项（主业）+ 全部成立候选，
    //   排序输入 = 市场议价权（本格运力占比‰，G-1 同一拼写点）+ 库存（货币余额 + 商品库存折价 + 生产资料份额）；
    //   破平 = 位置 id 升序（I-3）。★ 纯读数：本表不写任何状态，落点仍走既有模式变迁路径。
    PrimaryModeRanking.Table table =
        rankingTable(
            base, source, current, currentProspect, targets, excluded, accounts, markets, topology);
    PrimaryModeRanking.logTable(day, table);
    // ★★ M-D（I-C）：排序表第 1 项 = **加速流向的那一项目标** —— 只把该目标提到候选序最前
    //   （权重、承载约束、门槛一字不改；"副业足够赚钱"就体现在它排到了第 1 项）。
    List<Target> ranked = primaryFirst(targets, table);
    ExpectedProfit expected =
        expectedProfit(
            currentProspect, liquidityMilli(accounts, markets, topology, source, current.hex));
    boolean aRule =
        expected.expectedNetMilli() < 0L
            && expected.liquidityMilli() < expected.nextCycleInputNeedMilli();

    List<MoveDraft> drafts;
    if (aRule) {
      List<Target> higher = ranked.stream().filter(target -> target.weight > 0L).toList();
      if (!higher.isEmpty()) {
        drafts =
            allocate(
                source,
                sourceHouseholdEconomy.population(),
                sourceHouseholdEconomy.population(),
                A_RULE_TRANSFER_SPEED_PER_MILLE,
                MigrationMove.REASON_A_RULE_MAX_SPEED,
                higher,
                base,
                sourceHouseholdEconomy,
                assetShares,
                reservedIdle,
                claimedByEnterprises);
      } else if (!expected.canSustainProduction()) {
        List<Target> displaced =
            buildDisplacedTargets(
                source,
                current,
                hexes,
                modes,
                producingPositions,
                modeByHousehold,
                householdEconomies);
        drafts =
            displaced.isEmpty()
                ? List.of()
                : allocate(
                    source,
                    sourceHouseholdEconomy.population(),
                    sourceHouseholdEconomy.population(),
                    A_RULE_TRANSFER_SPEED_PER_MILLE,
                    MigrationMove.REASON_DISPLACED_ABSORBED,
                    displaced,
                    base,
                    sourceHouseholdEconomy,
                    assetShares,
                    reservedIdle,
                    claimedByEnterprises);
      } else {
        // 当前户已是最优（或没有可承载目标）：不转移，债务按现有路径继续增长。
        drafts = List.of();
      }
    } else {
      // ★★★ W9（2026-10-11，用户裁定 v3.30 §7 第 2 条）：**转业速度 = 预期收益差驱动**。
      //   「只要其他生产方式比目前生产方式预期收益更高，就产生转业速度，差越大越快」⇒
      //     ① 活候选 = weight > 0 **且** speed > 0（收益差 ≤ 0 ⇒ speed 0 ⇒ 该目标不是去处，直接不进候选）；
      //     ② 可迁人口 = 该户**最快的那一档速度**折算（max(1, ⌊人口 × 最快速度 ÷ 1000⌋)）——
      //        它是"这一轮最多能走多少人"的上界，落到哪个目标仍由 allocate 按 weight 比例分；
      //     ③ 每一笔 move 带**该目标自己的速度**（allocate 的 speedOverride = 0 ⇒ 用 Target.speedPerMille）：
      //        "差越大越快"因此是逐目标的，不是整户一个数。
      // ★★★ W9-fix（2026-10-11，控制方裁定 v3.32 §7 第 2 条）：**转业速率上限**（政策预设
      //   {@link #MIGRATION_SPEED_CAP_PER_MILLE}）—— 本户本轮可转人口再被"每市场轮 ≤ 人口 × 上限‰ ÷ 1000"夹一次。
      //   ★ 与 A 规则**分开**：A 规则两支（上面 aRule 的两支）显式传 1000‰ 且 moveable = 全户人口，不走这里。
      //   ★ 上限是**独立的人口闸**：它不改 {@code Target.speedPerMille}（"差越大越快"的读数保持逐值可读），
      //     只把"这一轮最多走多少人"夹住 —— 两者在 MIGRATION_MOVE 里两栏同时可读。
      long bestSpeedPerMille = 0L;
      for (Target target : ranked) {
        if (target.weight > 0L) {
          bestSpeedPerMille = Math.max(bestSpeedPerMille, target.speedPerMille);
        }
      }
      long moveable =
          Math.min(
              moveablePopulation(sourceHouseholdEconomy.population(), bestSpeedPerMille),
              moveablePopulation(
                  sourceHouseholdEconomy.population(), MIGRATION_SPEED_CAP_PER_MILLE));
      List<Target> positive =
          ranked.stream()
              .filter(target -> target.weight > 0L && target.speedPerMille > 0L)
              .toList();
      drafts =
          positive.isEmpty() || moveable <= 0L
              ? List.of()
              : allocate(
                  source,
                  sourceHouseholdEconomy.population(),
                  moveable,
                  // ★ 0 = "用每个目标自己的收益差速度"（A 规则两支仍显式传 1000‰ 覆盖）。
                  0L,
                  MigrationMove.REASON_PROFIT_WEIGHTED,
                  positive,
                  base,
                  sourceHouseholdEconomy,
                  assetShares,
                  reservedIdle,
                  claimedByEnterprises);
    }
    // ★★ M-D（冻结项 7）：INFO = **主业变更**（谁、从哪到哪、为什么）—— 只在真的产生了迁移、且排序表第 1 项
    //   换了生产方式时打点；表本身与判据在 DEBUG（{@link PrimaryModeRanking#logTable}）。
    maybeLogPrimaryChange(day, source, current, currentPerLabor, table, drafts);
    return drafts;
  }

  // ── M-D：生产方式排序表（§13 I-A..I-E；唯一拼写点在本节）───────────────────────────────────

  /**
   * ★★ <b>把"当前项 + 全部成立候选"排成一张表</b>（I-A/I-B）—— <b>纯读数</b>，不写任何状态。
   *
   * <p>★ 当前项用该户**真实的 {@code currentPositionId}**（"从哪"要准确），收益率用同一个 {@link
   * ExpectedProfitBook.Prospect}（与候选同一把尺，A 规则与排序表不可能漂开）。DISPLACED 一类没有位置的候选不进表。
   */
  private static PrimaryModeRanking.Table rankingTable(
      EconomyDayView base,
      HouseholdId source,
      HouseholdMode current,
      ExpectedProfitBook.Prospect currentProspect,
      List<Target> targets,
      List<PrimaryModeRanking.Excluded> excluded,
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology) {
    List<PrimaryModeRanking.Row> rows = new ArrayList<>();
    String currentGate =
        DefaultProductionModes.MERCHANT.equals(current.modeId)
            ? PrimaryModeRanking.merchantGateReason(
                base, source, current.hex, accounts.householdGoods())
            : null;
    if (currentGate == null) {
      rows.add(
          new PrimaryModeRanking.Row(
              current.positionId,
              current.modeId,
              current.hex,
              currentProspect.netPerLaborScaled(),
              bargainingPowerPerMille(base, source, current.modeId, current.hex, accounts),
              PrimaryModeRanking.inventoryMilli(accounts, markets, topology, source, current.hex),
              currentProspect.reason()));
    } else {
      // ★ 当前主业就是跑商、但**工具不够一趟**（§12 H-D/H-5）：库存是排序输入之一 ⇒ 它进不了"第 1 项"
      //   （跑商在决策层不成立；它在市场轮的既有 M-C 门槛另有具名 tool-short）。★ 只影响**本表的读数**，
      //   A 规则读的 expectedNet 一字不改（冻结项 5）。
      excluded.add(new PrimaryModeRanking.Excluded(current.modeId, current.hex, currentGate));
    }
    for (Target target : targets) {
      if (target.positionId == null) {
        continue; // 没有位置的目标（DISPLACED）：排序表只排"生产方式位置"，它不进表
      }
      rows.add(
          new PrimaryModeRanking.Row(
              target.positionId,
              target.mode,
              target.hex,
              target.yieldPerLaborScaled,
              bargainingPowerPerMille(base, source, target.mode, target.hex, accounts),
              PrimaryModeRanking.inventoryMilli(accounts, markets, topology, source, target.hex),
              target.prospectReason));
    }
    return PrimaryModeRanking.rank(source, rows, excluded);
  }

  /**
   * ★★ <b>排序输入①：市场议价权（占比‰）</b>—— 只有跑商行非零（议价权是**运力**概念；§11.4 G-1 的唯一口径， 经 {@link
   * MerchantCapacityPool#sharePerMilleAsProviderAt}）。
   */
  private static long bargainingPowerPerMille(
      EconomyDayView base,
      HouseholdId source,
      ProductionModeId mode,
      HexCoord hex,
      AccountSession accounts) {
    if (!DefaultProductionModes.MERCHANT.equals(mode)) {
      return 0L;
    }
    return MerchantCapacityPool.sharePerMilleAsProviderAt(
        base, source, hex, accounts.householdGoods());
  }

  /**
   * ★★ <b>I-C：把排序表第 1 项对应的目标提到候选序最前</b>（"副业足够赚钱 ⇒ 既有 A 规则加速流向它"）。
   *
   * <p>★ 只改**次序**：权重比例、承载约束、新建可行性、门槛一律不动 ⇒ 与改前逐值可比。 ★ 第 1 项就是当前项（主业没变）或候选里没有它的 (mode, hex) ⇒
   * 原序返回（一字不改）。
   */
  private static List<Target> primaryFirst(List<Target> targets, PrimaryModeRanking.Table table) {
    PrimaryModeRanking.Row primary = table.primary();
    if (primary == null || targets.size() <= 1) {
      return targets;
    }
    Target first = null;
    List<Target> rest = new ArrayList<>(targets.size());
    for (Target target : targets) {
      if (first == null
          && target.mode.equals(primary.modeId())
          && target.hex.equals(primary.hex())) {
        first = target;
      } else {
        rest.add(target);
      }
    }
    if (first == null) {
      return targets;
    }
    List<Target> ordered = new ArrayList<>(targets.size());
    ordered.add(first);
    ordered.addAll(rest);
    return ordered;
  }

  /** ★★ <b>INFO：主业变更</b>（谁、从哪到哪、为什么）—— 排序表第 1 项的生产方式与该户当前主业不同、且本轮确实产生了 迁移时才打点（否则只是读数，进 DEBUG）。 */
  private static void maybeLogPrimaryChange(
      long day,
      HouseholdId source,
      HouseholdMode current,
      long currentYield,
      PrimaryModeRanking.Table table,
      List<MoveDraft> drafts) {
    PrimaryModeRanking.Row primary = table.primary();
    if (primary == null || drafts.isEmpty() || primary.modeId().equals(current.modeId)) {
      return;
    }
    // "从哪"的收益率取**该户当前项那一次的 prospect 读数**（与本方法无关的 A 规则同源），不依赖当前行是否在表里。
    PrimaryModeRanking.logPrimaryChange(
        day, source, current.positionId, current.modeId, primary, currentYield);
  }

  /**
   * 目标候选（已有家户 + 可新建；同 (hex, mode) 一条，已有家户优先）。
   *
   * <p>★★ <b>D-024</b>：删除"该 (mode, hex) 必须有 {@link EnterpriseProfitBook} 真实读数"的门槛， 对每个 距离≤1 的 hex ×
   * 每个 mode 调 {@link ExpectedProfitBook#prospect} 现算；不可行/规模 0 ⇒ 跳过。 权重仍是同一把百万分之一尺：{@code max(0,
   * target.netPerLaborScaled − current.netPerLaborScaled)}。
   *
   * <p>★★ <b>D-024 修复 1 / 1b</b>：{@code claimedByEnterprises} 由 {@link #plan} 从当天工作副本 {@code
   * enterprises + units + assetShares} 算出后传入，并原样透传给每个候选 {@code prospect(...)}；本方法不从 {@code base} 重算
   * claimed，避免周期开始时的过期快照把既有组织/在产 unit 正在使用的份额（含 ESTATE 土地）当 "闲置可租"。
   */
  private static List<Target> buildTargets(
      EconomyDayView base,
      HouseholdId source,
      HouseholdEconomy sourceHouseholdEconomy,
      HouseholdMode current,
      long currentPerLabor,
      long currentProfitBasisScaled,
      List<PrimaryModeRanking.Excluded> excluded,
      List<HexCoord> hexes,
      List<ProductionMode> modes,
      Map<ProductionModeId, List<ProductionRole>> producingPositions,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares,
      Set<AssetShareId> claimedByEnterprises,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<MarketZoneId, MarketTable> markets,
      AccountSession accounts,
      MarketDemandBook.Book demandBook,
      MarketTopology topology,
      long day,
      CandidateTally tally) {
    List<Target> targets = new ArrayList<>();
    for (HexCoord hex : hexes) {
      int distance = sourceHouseholdEconomy.view().hex().distanceTo(hex);
      if (distance > 1) {
        continue;
      }
      for (ProductionMode mode : modes) {
        if (DefaultProductionModes.DISPLACED.equals(mode.id())) {
          continue; // displaced 只在 A 规则"无路可走"支里出现
        }
        if (mode.id().equals(current.modeId) && hex.equals(current.hex)) {
          continue;
        }
        List<ProductionRole> positions = producingPositions.get(mode.id());
        if (positions == null || positions.isEmpty()) {
          continue;
        }
        // 允许条件：该 hex 有产业模板；merchant 额外允许"有商号运力"（无 trade 模板仍会在 prospect 里具名失败）。
        boolean hasIndustry = hasIndustryTemplate(base, hex);
        boolean merchantCapacity =
            DefaultProductionModes.MERCHANT.equals(mode.id()) && hasMerchantCapacityAt(base, hex);
        if (!hasIndustry && !merchantCapacity) {
          continue;
        }
        // ★★★ W7（2026-10-11）：候选评估的两个具名落点（§一.9）—— ① "评估过"（逐格计数）；
        //   ② "为什么没成"（prospect 的具名理由 / 门槛 / 实物腿），轮级一行汇总（见 CandidateTally.logRound）。
        boolean merchantCandidate = DefaultProductionModes.MERCHANT.equals(mode.id());
        tally.candidate(hex, merchantCandidate);
        // ★★★ W9：并入既有户这一支也要目标格真能承载这种生产方式（W7 判据，保留）。
        boolean hostIndustryAvailable =
            !DefaultProductionModes.MERCHANT.equals(mode.id())
                || !industriesForMode(base, hex, mode.id()).isEmpty();
        HouseholdId existing =
            bestExistingTarget(
                source,
                hex,
                mode,
                modeByHousehold,
                positions,
                householdEconomies,
                hostIndustryAvailable);
        // ★★★ W9（用户裁定 v3.29/v3.30 §7 第 2/4 条）：**档位 = 这户带来的库存能支撑的最高档**。
        //   ① 档位按**整户**能带走的作业资本算（carriedCapitalByKind：自有 + 可移动 + 未被占用 + 工具商品库存）；
        //   ② 目标产业模板 = 该 (mode, hex) 解析出来的那一个（农场/作坊/商号各自的 slots() 与 capacityPerUnit）；
        //   ③ 只有**新建**目标才落档位（并入既有户时，目标户的档位是它自己的 —— 不是这笔能决定的）。
        Industry tierIndustry = industryForTier(base, hex, mode.id(), merchantCandidate);
        int tierIndex =
            tierIndustry == null
                ? 0
                : supportedTierIndex(
                    tierIndustry,
                    sourceHouseholdEconomy.participationAdjustedLaborMilli(),
                    carriedCapitalByKind(
                        source,
                        assetShares,
                        claimedByEnterprises,
                        accounts.householdGoods().getOrDefault(source, Map.of())));
        List<ProductionRole> tierPositions = producingPositions.get(mode.id());
        ClassPositionId tierPositionId =
            existing == null && tierIndex > 0 && tierPositions != null
                ? tierPosition(tierPositions, tierIndex)
                : null;
        ExpectedProfitBook.Prospect prospect =
            ExpectedProfitBook.prospect(
                base,
                source,
                mode.id(),
                hex,
                null,
                marketOf(markets, topology, hex),
                demandBook,
                assetShares,
                claimedByEnterprises,
                accounts,
                units,
                relations,
                topology,
                day,
                tierPositionId);
        // ★★★ W9（用户裁定 v3.30 §7 第 1 条「没有库存就要么去当前生产方式的最低档…为啥要拦着？」）：
        //   **降档重试** —— 默认位置因为"这户拿不出作业资本/劳动"而不可行时，再按**最低档的位置**试一次
        //   （只有"新建目标"才试；并入既有户不生成新关系、也不新建 unit，位置不是这笔能决定的）。
        //   ★ 方向：它**只会新增候选**（每个候选仍由同一个 ExpectedProfitBook 判可行）⇒ 不静默放行任何不可行目标；
        //     真的落到最低档时，落点随 `tierPositionId` 进计划（档位仍是 0 = 兜底档，槽位照抄源户，见 D1）。
        if ((!prospect.feasible() || prospect.feasibleScale() <= 0L)
            && existing == null
            && tierPositionId == null
            && tierPositions != null
            && tierPositions.size() > 1) {
          ClassPositionId lowestPosition = tierPosition(tierPositions, 0);
          if (lowestPosition != null && !lowestPosition.equals(prospect.positionId())) {
            ExpectedProfitBook.Prospect lowerProspect =
                ExpectedProfitBook.prospect(
                    base,
                    source,
                    mode.id(),
                    hex,
                    null,
                    marketOf(markets, topology, hex),
                    demandBook,
                    assetShares,
                    claimedByEnterprises,
                    accounts,
                    units,
                    relations,
                    topology,
                    day,
                    lowestPosition);
            if (lowerProspect.feasible() && lowerProspect.feasibleScale() > 0L) {
              prospect = lowerProspect;
              tierPositionId = lowestPosition;
            }
          }
        }
        if (!prospect.feasible() || prospect.feasibleScale() <= 0L) {
          tally.failure(
              hex,
              mode.id(),
              prospect.reason(),
              merchantCandidate,
              source,
              merchantCandidate
                  ? MerchantCapacityPool.providerCapacityMilli(
                      base, source, hex, accounts.householdGoods())
                  : 0L,
              sourceHouseholdEconomy,
              accounts.householdGoods());
          continue; // 不可行/规模 0：不凭空造目标（理由在 prospect 里具名）
        }
        // ★★ M-D：排序输入在**跑商候选**上的门槛（Q-18 库存 = tool 不够一趟；Q-19 议价权占比‰ = 0）。
        //   不成立 ⇒ 该候选不进排序表、也不成目标（缺工具的"预期收益" = 0，§12 H-D/H-5 在决策层的同一条门槛）；
        //   被挡下的行**具名**进排序表的 excluded（DEBUG 打印，不静默）。
        if (DefaultProductionModes.MERCHANT.equals(mode.id())) {
          String gate =
              PrimaryModeRanking.merchantGateReason(base, source, hex, accounts.householdGoods());
          if (gate != null) {
            excluded.add(new PrimaryModeRanking.Excluded(mode.id(), hex, gate));
            tally.failure(
                hex,
                mode.id(),
                gate,
                true,
                source,
                MerchantCapacityPool.providerCapacityMilli(
                    base, source, hex, accounts.householdGoods()),
                sourceHouseholdEconomy,
                accounts.householdGoods());
            continue;
          }
        }
        long weight =
            Math.max(0L, Math.subtractExact(prospect.netPerLaborScaled(), currentPerLabor));
        // ★★★ W9（用户裁定 v3.30 §7 第 2 条）：**速度 = 预期收益差驱动**（差 ≤ 0 ⇒ 0 ⇒ 该目标不是去处）。
        //   ★★★ W9-fix（控制方裁定 v3.32 §7 第 1 条）：分母 = max(|收益_当前|, |收益_其他|, **收益规模基准**)，
        //     基准 = 两侧生产方式的"1 单位规模劳动的毛收入 / 口粮成本"取大（见 {@link #profitScaleBasisScaled}）
        //     —— 它保证"收益 ≈ 0 的户"不会拿到无穷大的相对差（那正是 21 个零成交轮的根因）。
        long speedPerMille =
            profitGapSpeedPerMille(
                prospect.netPerLaborScaled(),
                currentPerLabor,
                Math.max(currentProfitBasisScaled, profitScaleBasisScaled(prospect)));
        // ★★★ W2-fix7 / D1①（控制方裁定 v3.10 §7）：**目标生产方式的实物腿商品拿不到 ⇒ 该户不迁入这个 mode**。
        //   判据（详见 inKindLegUnavailableReason）：目标产业的产出表里没有这个商品 **且** 该户没有可用库存
        //   **且** 目标格的市场也不交易它 ⇒ 迁过去只会得到一条永远付不出的租腿（改前是 day 540 的 E14 400，
        //   世界当场卡死）。★ 只判**新建**目标：并入既有户不生成新关系（既有关系早就在跑）。
        //   ★ 被挡下的目标进排序表的 excluded（不静默）+ 一条具名 INFO。
        if (existing == null) {
          String refusal =
              inKindLegUnavailableReason(base, source, mode.id(), hex, accounts, markets, topology);
          if (refusal != null) {
            excluded.add(new PrimaryModeRanking.Excluded(mode.id(), hex, refusal));
            tally.failure(
                hex,
                mode.id(),
                refusal,
                merchantCandidate,
                source,
                merchantCandidate
                    ? MerchantCapacityPool.providerCapacityMilli(
                        base, source, hex, accounts.householdGoods())
                    : 0L,
                sourceHouseholdEconomy,
                accounts.householdGoods());
            if (weight > 0L) {
              // ★ 只在"它本来是活候选"时报 INFO（weight = 0 的目标本来就不会被选中，报它只是噪声）。
              EventLog.channel(EconomyLog.migration())
                  .info(
                      LogEvent.of(
                          "MIGRATION_IN_KIND_LEG_UNAVAILABLE_REFUSED",
                          EconomyLogSource.ECONOMY_MIGRATION,
                          "day",
                          day,
                          "household",
                          source.value(),
                          "targetHex",
                          hex.q() + "," + hex.r(),
                          "targetMode",
                          mode.id().value(),
                          "reason",
                          refusal));
            }
            continue;
          }
        }
        long room =
            existing == null
                ? 0L
                : Math.max(
                    0L, MAX_HOUSEHOLD_POPULATION - householdEconomies.get(existing).population());
        targets.add(
            new Target(
                hex,
                mode.id(),
                existing,
                weight,
                distance,
                true,
                room,
                canonicalKey(hex, mode.id()),
                // ★ W9：新建目标 + 支撑到高档 ⇒ 落点是**那一档的位置**；否则用 prospect 自己的落点（逐值等于改前）。
                tierPositionId != null ? tierPositionId : prospect.positionId(),
                prospect.netPerLaborScaled(),
                prospect.reason(),
                speedPerMille,
                tierIndex));
      }
    }
    targets.sort(
        Comparator.comparingLong((Target target) -> target.weight)
            .reversed()
            .thenComparingInt(target -> target.distance)
            .thenComparing(target -> target.canonical)
            .thenComparing(target -> target.existing == null ? "" : target.existing.value()));
    return targets;
  }

  /**
   * ★★ <b>W9：档位判据要读哪一个产业模板</b> —— 与 {@code ExpectedProfitBook.resolveIndustry} / {@code
   * ModeMigrationSettlement.industriesForMode} 同源（按 mode 的默认 regime 在该格挑，id 升序第一个）。
   *
   * <p>★ merchant 在**非城市格**没有 {@code trade} 模板 ⇒ 用按需模板 {@link
   * MerchantTradeTemplate#industry(HexCoord)} （与决策层预估、执行期真的写进工作副本的那一份**同一个拼写点**）。都没有 ⇒ {@code
   * null}（调用方按最低档兜底）。
   */
  private static Industry industryForTier(
      EconomyDayView base, HexCoord hex, ProductionModeId modeId, boolean merchant) {
    for (IndustryId industryId : industriesForMode(base, hex, modeId)) {
      Industry industry = base.industries().get(industryId);
      if (industry != null) {
        return industry;
      }
    }
    return merchant ? MerchantTradeTemplate.industry(hex) : null;
  }

  /**
   * ★★★ <b>W9（2026-10-11，用户裁定 v3.29 §7 第 2 条）：一个候选目标"能支撑到第几档"—— 用户原话「取库存最能支持的最高家户」</b>。
   *
   * <pre>
   * 档位阶梯（升序）= 目标产业模板的 slots()（{@link IndustryClassSlots}：0 贫农 950‰ / 1 中农 900 / 2 富农 750 / 3 地主 100）
   * 每档需要压住的作业资本（**我定的支撑判据**）：
   *   规模_劳动(t) = ⌊劳动投入 ÷ max(1, laborPerUnit)⌋                  // 这户的劳动自己能撑的规模
   *   规模_额外(t) = t == 0 ? 0 : max(0, ⌈规模_劳动 × 1000 ÷ 参与率(t)⌉ − 规模_劳动)
   *   需求(t, k)   = capacityPerUnit[k] × 规模_额外(t)
   *   支撑(t)     ⟺ ∀k: 带来的作业资本(k) ≥ 需求(t, k)
   * 档位 = 满足支撑的**最高** t；一个都不满足（等价于 t=0 也不满足，而 需求(0,·) ≡ 0 ⇒ 不会发生）⇒ 0
   * ★★★ W9-fix（U5）：规模_劳动 = 0（劳动不足 1 个规模单位）⇒ **直接 0**（最低档），不进上面的循环
   * </pre>
   *
   * <p>★★ <b>W9-fix（U5）为什么规模 0 必须落最低档</b>：{@code 规模_额外(t)} 以 {@code 规模_劳动} 为分母的对照量 （{@code ⌈规模×1000
   * ÷ 参与率⌉ − 规模}），规模 = 0 时它对每一档都退化成 0 ⇒ 循环会把 {@code best} 一路推到 **最高档**（地主档）。"劳动撑不起 1
   * 个规模单位"与"能当地主"是矛盾的（改前实测：{@code tier(trade, 999, 0) == 3}）。
   *
   * <p>★★ <b>为什么最低档不设门槛（用户②「没有库存就要么去当前生产方式的最低档…为啥要拦着？」）</b>：{@code 需求(0,·) ≡ 0} —— 贫农档（950‰
   * 参与率）是"自己劳动 + 租入/受雇"的形态，**不需要自带任何作业资本** ⇒ "零库存户也能转业并落到最低档"是 **结构上必然**，不是特例分支。
   *
   * <p>★★ <b>为什么高档要更多资本（算式怎么读）</b>：{@code 规模_额外(t)} = "这一档自己不劳动的那一部分也要撑住的规模"。 参与率越低（地主
   * 100‰），自己不劳动的份额越大 ⇒ 需要压住的作业资本越多。⇒ 单调：贫农 0 ≤ 中农 &lt; 富农 &lt; 地主。 ★
   * 这是**控制方口径**（用户只给了"够地主档就当地主，不够逐级降"的意图，没有给算式）—— 见实现账本偏离记录。
   *
   * <p>★ <b>只有"带得走的"才算</b>：{@code carriedByKind} 由 {@link #carriedCapitalByKind} 给出（本户自有、
   * **可移动**、未被别的组织/在产 unit 占用的份额 + 工具商品库存）—— 不可移动的 LAND/WORKSHOP 不计（它们带不走），
   * 目标格**可租的闲置份额也不计**（那不是"这户带来的库存"，且对所有户同值 ⇒ 没有区分度）。
   *
   * @param industry 目标产业模板（slots / capacityPerUnit / laborPerUnit 三个读口的唯一来源）
   * @param laborMilliPlan 这一户的劳动投入（整户口径的规划估计；执行期按逐笔人口比例切）
   * @param carriedByKind 这户带得走的作业资本（逐 AssetKind；缺键 = 0）
   * @return 档位下标（0 = 贫农/兜底档；恒 ≥ 0 且 &lt; slots().size()）
   */
  static int supportedTierIndex(
      Industry industry, long laborMilliPlan, Map<AssetKind, Long> carriedByKind) {
    Objects.requireNonNull(industry, "industry");
    Objects.requireNonNull(carriedByKind, "carriedByKind");
    List<ClassSlot> slots = industry.slots();
    if (slots.isEmpty()) {
      return 0; // 没有档位表（旧档/未裁决产业）⇒ 只有兜底档
    }
    long laborPerUnit = Math.max(1L, industry.recipe().laborPerUnit());
    long scaleLabor = Math.max(0L, laborMilliPlan / laborPerUnit);
    // ★★★ W9-fix（2026-10-11，U5 修复，控制方裁定 v3.32 §7 第 4 条）：**规模 = 0 ⇒ 一律落最低档（t = 0）**。
    //   改前的缺陷：规模 = 0 ⇒ 每一档的"额外规模"都算成 0 ⇒ 每一档都"天然支撑" ⇒ 循环把 best 推到**最高档**
    //   （地主档）—— 一个连 1 个规模单位都撑不起的极小户（laborMilli < laborPerUnit）会被抬到该 mode 的
    //   最高位置（单测逐值钉住过：`tier(trade, 999L, 0L) == 3`）。
    //   口径：规模是整条阶梯的分母（额外规模 = ⌈规模×1000 ÷ 参与率⌉ − 规模）；规模为 0 时阶梯没有定义，
    //   而"兜底档不需要任何作业资本"（需求(0,·) ≡ 0）本来就是"零库存也能转业"的结构根 ⇒ 落 t = 0。
    if (scaleLabor <= 0L) {
      return 0;
    }
    int best = 0;
    for (int tier = 1; tier < slots.size(); tier++) {
      long participation = Math.max(1L, slots.get(tier).laborParticipationPerMille());
      long extraScale = ceilDiv(Math.multiplyExact(scaleLabor, 1000L), participation) - scaleLabor;
      if (extraScale <= 0L) {
        best = tier; // 这一档不需要额外规模（劳动已够）⇒ 天然支撑
        continue;
      }
      boolean supported = true;
      for (Map.Entry<AssetKind, Long> required : industry.recipe().capacityPerUnit().entrySet()) {
        long perUnit = required.getValue() == null ? 0L : required.getValue();
        if (perUnit <= 0L) {
          continue;
        }
        long need = Math.multiplyExact(extraScale, perUnit);
        if (carriedByKind.getOrDefault(required.getKey(), 0L) < need) {
          supported = false;
          break;
        }
      }
      if (supported) {
        best = tier;
      }
    }
    return best;
  }

  /** 向上取整的整数除法（{@code denominator > 0}）。 */
  private static long ceilDiv(long numerator, long denominator) {
    if (numerator <= 0L) {
      return 0L;
    }
    return (numerator + denominator - 1L) / denominator;
  }

  /**
   * ★★★ <b>W9：这一户<b>带得走</b>的作业资本（逐 {@code AssetKind}）</b> —— 档位判据（{@link #supportedTierIndex}）的输入。
   *
   * <pre>
   * 份额腿 = owner == 该户 actor ∧ quantity &gt; 0 ∧ !claimedByEnterprises ∧ {@link ModeMigrationSettlement#isMobileAsset}(asset)
   * 商品腿 = 商品账里的工具（{@link MerchantTradeTemplate#TOOL_COMMODITY}）⇒ 计入 {@code AssetKind.TOOL}
   *          （工具是唯一"既有商品形态、又有资产份额形态"的作业资本；两边的量纲都是毫）
   * </pre>
   *
   * <p>★ 与执行期 {@code ModeMigrationSettlement.migrateAssetsForMove} 的"能带走什么"同一组判据（owner / 可移动 /
   * 未被占用）， 只是这里按**整户**计（规划期的口径，见实现账本：部分迁移按比例切，部分迁移不会比整户更能支撑高档）。
   */
  static Map<AssetKind, Long> carriedCapitalByKind(
      HouseholdId source,
      Map<AssetShareId, OwnershipStake> assetShares,
      Set<AssetShareId> claimedByEnterprises,
      Map<CommodityId, Long> goods) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(claimedByEnterprises, "claimedByEnterprises");
    Objects.requireNonNull(goods, "goods");
    ActorRef sourceActor = HouseholdActors.of(source);
    Map<AssetKind, Long> carried = new LinkedHashMap<>();
    for (OwnershipStake share : assetShares.values()) {
      if (share == null
          || share.quantity() <= 0L
          || !share.owner().equals(sourceActor)
          || claimedByEnterprises.contains(share.id())
          || !ModeMigrationSettlement.isMobileAsset(share.asset())) {
        continue;
      }
      carried.merge(share.asset(), share.quantity(), Math::addExact);
    }
    long toolCommodity = goods.getOrDefault(MerchantTradeTemplate.TOOL_COMMODITY, 0L);
    if (toolCommodity > 0L) {
      carried.merge(AssetKind.TOOL, toolCommodity, Math::addExact);
    }
    return carried;
  }

  /**
   * ★★ <b>W9：档位 → 落点位置</b> —— 目标 mode 的**可生产位置**按 {@link ClassLadderBook#POSITION_LADDER_ASCENDING}
   * 升序排列，取第 {@code min(档位, n−1)} 个（档位数多于位置数时封顶到最高位置）。
   *
   * <p>★ 位置的"高低"用的是既有的位阶（{@code relationToMeans × surplusRole}）—— 本批**不新造**社会位阶 （W4 的账本已具名记过：legacy
   * 的贫/中/富农角色元组相同、机械序分辨不出它们；转业档位因此用**产业模板的 slots()** 排序， 位置只是它的落点）。
   */
  static ClassPositionId tierPosition(List<ProductionRole> producing, int tierIndex) {
    Objects.requireNonNull(producing, "producing");
    if (producing.isEmpty()) {
      return null;
    }
    List<ProductionRole> ascending = new ArrayList<>(producing);
    ascending.sort(ClassLadderBook.POSITION_LADDER_ASCENDING);
    int index = Math.min(Math.max(0, tierIndex), ascending.size() - 1);
    return ascending.get(index).id();
  }

  /**
   * ★★★ <b>W2-fix7 / D1①（控制方裁定 v3.10 §7 第 ① 条）：一个"新建目标"能不能迁 —— 实物腿商品的可得性判据</b>。
   *
   * <pre>
   * 目标产业 I   = industriesForMode(base, hex, mode) 的第一个（与 ExpectedProfitBook.resolveIndustry 同源）
   * 模板制度 R   = ModeMigrationSettlement.migrationTemplateRegime(mode, I, base)（唯一映射点，不另写一份）
   * 实物腿商品 C = RegimeRelations.inKindCommodities(R)（模板里所有 commodity 非空的腿，去重保序）
   * 逐 c ∈ C（只要有一条**拿不到**就拒这个目标）：
   *   ① c ∈ I.outputPerUnit()                ⇒ 拿得到（本 unit 自己就产它）
   *   ② 该户（承担这条租腿的那个家户）手头有 c ⇒ 拿得到（家户库存）
   *   ③ 目标格所属市场的价目表里有 c 这一行    ⇒ 拿得到（能买；含 0 价 = 免费交易，债务式清算下"没钱也能买"）
   *   三者都不成立 ⇒ 返回具名理由（调用方：不迁入这个 mode、进 excluded、INFO 一条），世界继续跑
   * </pre>
   *
   * <p>★★ <b>"该户"= 被决策的这个家户（迁移源户）</b>：判据读的是它手头的库存（{@code accounts.householdGoods()}）。 ★
   * <b>一处如实记的边界</b>：本仓的迁移 ①–⑥ 只搬人/钱/债/资产/劳动配额，<b>不搬实物库存</b>（既有事实，本批不改）⇒
   * 源户"有库存"放行的迁移，在**新建**目标户那一侧头一轮仍然可能欠租 —— 那一轮由三级支付链的 ③ 具名缺口 + 债权 资本化承接（不是死结，也不是静默 0）。★
   * 并入既有户不走本判据（{@code existing != null} 时调用方直接跳过）： 那种 move <b>不生成新关系</b>，既有关系的合法性早由 E14 把守。
   *
   * <p>★★ <b>为什么"市场有这一行"就等于可得</b>：S-6/§2.1 的口径是"表里有这一行（含 0）= 可交易；没有这一行 = 不交易"， 且债务式清算下"没钱也能买（记债）"⇒
   * 市场交易该商品 = 这户有拿到它的通道。★ 反向（市场不交易、产出表也没有、 自己也没库存）才是真死路 —— 那正是要挡的形态。
   *
   * <p>★ <b>为什么按 mode→模板→商品，而不是按"已生成的关系"</b>：决策发生在关系生成之前；商品集合只由 {@link RegimeRelations}
   * 的表决定（受方/格/居住类型不改变商品）⇒ 模板就是权威。
   *
   * @return 具名拒绝理由；null = 可以迁
   */
  private static String inKindLegUnavailableReason(
      EconomyDayView base,
      HouseholdId source,
      ProductionModeId mode,
      HexCoord hex,
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology) {
    IndustryId industryId = null;
    Industry industry = null;
    for (IndustryId candidate : industriesForMode(base, hex, mode)) {
      Industry resolved = base.industries().get(candidate);
      if (resolved != null) {
        industryId = candidate;
        industry = resolved;
        break;
      }
    }
    if (industryId == null || industry == null) {
      return null; // 没有产业模板：本方法不判（该目标的可行性由 prospect / merchant 门槛具名把关）
    }
    Optional<RegimeId> regime =
        ModeMigrationSettlement.migrationTemplateRegime(mode, industryId, base);
    if (regime.isEmpty()) {
      return null; // 模板制度未登记（如 merchant）⇒ 关系走具名回退空规则，没有实物腿可判
    }
    return inKindLegUnavailableReasonOf(
        industryId,
        industry,
        RegimeRelations.inKindCommodities(regime.get()),
        accounts.householdGoods().getOrDefault(source, Map.of()),
        marketOf(markets, topology, hex),
        regime.get());
  }

  /**
   * ★★ <b>{@link #inKindLegUnavailableReason} 的判据本体（包内可见 ⇒ 可被逐值探针直接钉住）</b>： 三条"拿得到"的通道逐商品判，
   * 只要有一条商品三条都不成立 ⇒ 返回具名理由。
   *
   * @param industry 目标产业（产出表 = 唯一的"自己能产"权威）
   * @param inKindCommodities 目标模板的实物腿商品（{@link RegimeRelations#inKindCommodities}）
   * @param householdStock 该户手头库存（逐商品；缺键 = 0）
   * @param market 目标格所属市场的价目表（null = 该格没有市场 ⇒ 市场那一档不成立）
   * @return 具名拒绝理由；null = 可以迁
   */
  static String inKindLegUnavailableReasonOf(
      IndustryId industryId,
      Industry industry,
      List<CommodityId> inKindCommodities,
      Map<CommodityId, Long> householdStock,
      MarketTable market,
      RegimeId regime) {
    StringBuilder refused = new StringBuilder();
    for (CommodityId commodity : inKindCommodities) {
      if (industry.outputPerUnit().containsKey(commodity)) {
        continue; // ① 本产业能产
      }
      if (householdStock.getOrDefault(commodity, 0L) > 0L) {
        continue; // ② 该户手头有库存
      }
      if (market != null && market.hasPrice(commodity)) {
        continue; // ③ 目标格的市场交易它（含 0 价 = 免费交易）
      }
      if (refused.length() > 0) {
        refused.append('|');
      }
      refused
          .append(commodity.value())
          .append("(industry=")
          .append(industryId.value())
          .append(" outputs=")
          .append(industry.outputPerUnit().keySet())
          .append(",stock=0,market=")
          .append(market == null ? "none" : market.hasPrice(commodity))
          .append(')');
    }
    if (refused.length() == 0) {
      return null;
    }
    return "in-kind-leg-unavailable:" + refused + ":regime=" + regime.value();
  }

  /** A 规则专用：DISPLACED 目标（已有流民户或同一/邻格新建；不要求产业/资产）。 */
  private static List<Target> buildDisplacedTargets(
      HouseholdId source,
      HouseholdMode current,
      List<HexCoord> hexes,
      List<ProductionMode> modes,
      Map<ProductionModeId, List<ProductionRole>> producingPositions,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    ProductionMode displaced =
        modes.stream()
            .filter(mode -> DefaultProductionModes.DISPLACED.equals(mode.id()))
            .findFirst()
            .orElse(null);
    if (displaced == null) {
      return List.of();
    }
    List<Target> targets = new ArrayList<>();
    for (HexCoord hex : hexes) {
      int distance = current.hex.distanceTo(hex);
      if (distance > 1) {
        continue;
      }
      List<ProductionRole> positions = producingPositions.get(displaced.id());
      if (positions == null || positions.isEmpty()) {
        continue;
      }
      HouseholdId existing =
          bestExistingTarget(
              source, hex, displaced, modeByHousehold, positions, householdEconomies, true);
      long room =
          existing == null
              ? 0L
              : Math.max(
                  0L, MAX_HOUSEHOLD_POPULATION - householdEconomies.get(existing).population());
      if (existing == null) {
        targets.add(
            new Target(
                hex,
                displaced.id(),
                null,
                0L,
                distance,
                true,
                MAX_HOUSEHOLD_POPULATION,
                canonicalKey(hex, displaced.id()),
                null,
                0L,
                "DISPLACED",
                0L,
                0));
      } else if (room > 0L) {
        targets.add(
            new Target(
                hex,
                displaced.id(),
                existing,
                0L,
                distance,
                false,
                room,
                canonicalKey(hex, displaced.id()),
                null,
                0L,
                "DISPLACED",
                0L,
                0));
      }
    }
    targets.sort(
        Comparator.comparingInt((Target target) -> target.distance)
            .thenComparing(target -> target.canonical)
            .thenComparing(target -> target.existing == null ? "" : target.existing.value()));
    return targets;
  }

  /**
   * 分配可迁人口：按目标排序贪心填充（已有户剩余容量 → 同目标可新建容量 → 下一个目标）。
   *
   * <p>★ 新建目标的资产可行性在这里用"本次实际要建的人口"复核（不足 ⇒ 该目标的新建容量记 0）。溢出部分留在源户， 不丢人、也不凭空造承载。★★ P10.6：预留账本由调用方（同一
   * plan 顶层）传入，两遍循环共用、不重置。
   */
  private static List<MoveDraft> allocate(
      HouseholdId source,
      long sourcePopulation,
      long moveable,
      long speedPerMille,
      String reason,
      List<Target> targets,
      EconomyDayView base,
      HouseholdEconomy sourceHouseholdEconomy,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByEnterprises) {
    List<MoveDraft> drafts = new ArrayList<>();
    if (targets.isEmpty()) {
      return drafts;
    }
    long total = Math.min(moveable, sourcePopulation);
    if (total <= 0L) {
      return drafts;
    }
    // ★ 权重分配：先按 weight 比例切出各目标的名义额度，再按目标序在额度内落地；某目标承载不足时
    //   余额顺延给后面的目标（不丢人、也不偏离"按权重转出"）。
    long[] weights = new long[targets.size()];
    long weightSum = 0L;
    for (int i = 0; i < targets.size(); i++) {
      weights[i] = Math.max(0L, targets.get(i).weight);
      weightSum = Math.addExact(weightSum, weights[i]);
    }
    long[] wants =
        io.mosire.simos.util.economy.ProportionalSplit.byDenominator(total, weights, weightSum);
    long remaining = total;
    int[] newOrdinal = {0};
    for (int i = 0; i < targets.size() && remaining > 0L; i++) {
      long placed =
          placeInto(
              targets.get(i),
              Math.min(remaining, wants[i]),
              source,
              speedPerMille,
              reason,
              base,
              sourceHouseholdEconomy,
              assetShares,
              drafts,
              newOrdinal,
              reservedIdle,
              claimedByEnterprises);
      remaining -= placed;
    }
    // 第二遍：把前面目标没承载下的余量按目标序顺延（仍受真实容量/资产约束）。
    for (int i = 0; i < targets.size() && remaining > 0L; i++) {
      long placed =
          placeInto(
              targets.get(i),
              remaining,
              source,
              speedPerMille,
              reason,
              base,
              sourceHouseholdEconomy,
              assetShares,
              drafts,
              newOrdinal,
              reservedIdle,
              claimedByEnterprises);
      remaining -= placed;
    }
    return drafts;
  }

  /**
   * 往一个目标里放人口（已有户剩余容量 → 该 (hex, mode) 至多新建一户）；返回实际放入人口。
   *
   * <p>★★ P10.6：新建部分的"资产可行性"是<b>检查并预留</b>（{@code reserveIdleAssetsFor}）；预留成功才产出 MoveDraft， 账本由同一
   * {@code plan()} 的所有源户/所有目标共享。
   */
  private static long placeInto(
      Target target,
      long allow,
      HouseholdId source,
      long speedOverridePerMille,
      String reason,
      EconomyDayView base,
      HouseholdEconomy sourceHouseholdEconomy,
      Map<AssetShareId, OwnershipStake> assetShares,
      List<MoveDraft> drafts,
      int[] newOrdinal,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByEnterprises) {
    long placed = 0L;
    if (allow <= 0L) {
      return 0L;
    }
    // ★★★ W9（用户裁定 v3.30 §7 第 2 条）：速度**逐目标**取 —— 覆盖值 &gt; 0（A 规则两支传 1000‰）用它，
    //   否则用该目标自己的收益差速度（{@link #profitGapSpeedPerMille}）。速度 ≤ 0 ⇒ 这个目标不是去处（不落地）。
    long speedPerMille = speedOverridePerMille > 0L ? speedOverridePerMille : target.speedPerMille;
    if (speedPerMille <= 0L) {
      return 0L;
    }
    if (target.existing != null && target.existingRoom > 0L) {
      long take = Math.min(allow, target.existingRoom);
      drafts.add(
          new MoveDraft(
              target.existing,
              target.hex,
              target.mode,
              speedPerMille,
              take,
              reason,
              target.positionId,
              target.tierIndex));
      placed += take;
    }
    if (placed >= allow || !target.newFeasible) {
      return placed;
    }
    long want = Math.min(allow - placed, MAX_HOUSEHOLD_POPULATION);
    long room =
        perMoveNewRoom(
            target.hex,
            target.mode,
            want,
            base,
            sourceHouseholdEconomy,
            assetShares,
            reservedIdle,
            claimedByEnterprises);
    long take = Math.min(want, room);
    if (take > 0L) {
      drafts.add(
          new MoveDraft(
              newHouseholdId(source, target.hex, target.mode, newOrdinal[0]++),
              target.hex,
              target.mode,
              speedPerMille,
              take,
              reason,
              target.positionId,
              target.tierIndex));
      placed += take;
    }
    return placed;
  }

  /**
   * 新建户可承载人口：由本次 take 的劳动反推规模，再看该 hex 的产业模板里有没有"闲置/可租赁资产够且预留成功"的； 第一个预留成功的产业即返回
   * take（预留已计入共享账本）。没有产业可预留 ⇒ 0。
   */
  private static long perMoveNewRoom(
      HexCoord hex,
      ProductionModeId mode,
      long take,
      EconomyDayView base,
      HouseholdEconomy sourceHouseholdEconomy,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByEnterprises) {
    if (DefaultProductionModes.DISPLACED.equals(mode)) {
      return take;
    }
    // 以源户的"劳动/人口"比例折算这批人带来的劳动（与执行期 laborTake 同源；不按人口 1:1 猜劳动）。
    // ★★★ W9-fix2：口径 = {@link #laborCarriedByMoveUpperBound}（唯一拼写点，向上取整 = 按较大者预留）。
    long laborEstimate =
        laborCarriedByMoveUpperBound(
            sourceHouseholdEconomy.laborMilli(), sourceHouseholdEconomy.population(), take);
    // ★ 与执行期（ModeMigrationSettlement.createEnterpriseAndProcess）同一份"该 mode 能用哪些产业模板"：
    //   按 mode 的默认 regime 优先；merchant 找不到 trade 模板 ⇒ 空（绝不把农场模板当商号）。
    List<IndustryId> templates = industriesForMode(base, hex, mode);
    // ★★★ W7（2026-10-11）：**非城市格的跑商** —— 本格没有 merchant 制度的产业模板时，走"按需创建"那一支：
    //   估算用的模板 = {@link MerchantTradeTemplate#industry(HexCoord)}（与 {@code ExpectedProfitBook}
    // 的可行性预估、
    //   与迁移执行真的写进工作副本的那一份**同一个拼写点**）；可用的**作业资本 = 工具** = **该户自备/随迁携带**的
    //   那一份（D-023 §4 可移动资产随迁；判据与执行期 {@code migrateAssetsForMove} 的按人口比例取整逐式对齐）。
    //   ★★ W7-fix（2026-10-11，用户裁定 v3.28 §7）：判据从 **CATTLE** 换成 **工具**（= 模板的
    //     {@code capacityPerUnit} 那一维）—— 牲口不再是跑商的可行性门槛，也不再是运力的来源。
    //     没有作业资本（工具）的户 ⇒ 这里算不出可用量 ⇒ 返回 0 ⇒ 不就这一格建目标
    //     （"新建户凑不齐作业资本就建不出商号"仍是诚实的约束，且与执行期 {@code planAssetMoves} 同一口径）。
    if (templates.isEmpty() && DefaultProductionModes.MERCHANT.equals(mode)) {
      Industry onDemand = MerchantTradeTemplate.industry(hex);
      Map<AssetKind, Long> carried =
          carriedCarrierAssetsForMove(sourceHouseholdEconomy, take, assetShares);
      // ★★★ W9-fix2（2026-10-11，本批修掉的具名不一致）：规模口径**必须与执行期同一份算式** ——
      //   {@code createEnterpriseAndProcess} 对**新建**目标户算
      //   {@code scale = requiredWorkCapitalScale(模板, 目标行.laborMilli)}，而那里的"目标行"是
      //   **带本笔随迁投影的工作行视图**（{@code 0 + 本笔迁入劳动}），**不是**落盘行。
      //   ⇒ 改前在这里传 {@code laborPerUnit}（把 P0 的落盘 labor = 0 当执行期读法 ⇒ scale 恒 1）是**错的**：
      //     计划按 1 份预留、执行按 ⌊laborTake ÷ laborPerUnit⌋ 份索取 ⇒ N 倍缺口 ⇒ 执行期守卫当场抛
      //     （three-powers day=270：labor=8857 ⇒ scale=8，计划只留了 1 份；HTTP 500）。
      //   ⇒ 现在传 {@link #laborCarriedByMoveUpperBound}（= 执行期会读到的那一份劳动，按较大者预留），
      //     与执行期共用 {@link #requiredWorkCapitalScale} 这一个拼写点。
      //   ★ 产业规模**随后由资产派生**（ProductionProcessBook 读 OwnershipStake）：一户带 N 毫工具过去
      //     就得到 N 单位规模的商号（上界）；真正的规模由劳动与投入满足率取最紧那一路 ——
      //     "从 1 单位起步、随作业资本长大"，这正是"不凭空造资产"的字面结果。
      //     ★ 但"能建起来"的**门槛**是劳动反解出来的那一份（本文上面那条），不是 1 —— 门槛低于执行期索取
      //       就是"计划包含了一个执行期建不起来的目标"，那是 fail-closed 守卫该拦的（守卫不放宽）。
      // ★★★ W9（2026-10-11，用户裁定 v3.29 §7 第 3 条「没有生产工具不会买吗」）：**没带来作业资本 ⇒ 走"买/租"通道**
      //   —— 与执行期同一判据：允许用**同格其它产业**的闲置同种资产作作业资本（本格市场交易工具是决策层
      //   {@code ExpectedProfitBook} 那一侧的可买判据，执行期 gate 再复核一次）。带得来 ⇒ 逐值等于改前。
      boolean buyOrLease = carried.isEmpty();
      return reserveIdleAssetsFor(
              onDemand,
              laborEstimate,
              assetShares,
              reservedIdle,
              claimedByEnterprises,
              carried,
              buyOrLease,
              hex)
          ? take
          : 0L;
    }
    for (IndustryId industryId : templates) {
      Industry industry = base.industries().get(industryId);
      if (industry != null
          && reserveIdleAssetsFor(
              industry,
              laborEstimate,
              assetShares,
              reservedIdle,
              claimedByEnterprises,
              Map.of(),
              false,
              null)) {
        return take;
      }
    }
    return 0L;
  }

  /**
   * ★★★ <b>W7（2026-10-11）/ W7-fix（2026-10-11）：这一批人**随迁携带**的**作业资本**量</b> —— 判据与执行期 {@code
   * ModeMigrationSettlement.migrateAssetsForMove} 的取整**逐式对齐**：
   *
   * <pre>
   * 取整 = 整户迁空（take ≥ 源户人口）        ⇒ 全部数量
   *        否则                             ⇒ ⌊数量 × take ÷ 源户人口⌋   （与执行期同一式、同一 floor）
   * 范围 = owner == 源户 actor ∧ asset ∈ {模板的 capacityPerUnit 那一维} ∧ quantity &gt; 0
   * </pre>
   *
   * <p>★★ <b>W7-fix（用户裁定 v3.28 §7）</b>：那一维从 {@code CATTLE} 换成 {@code TOOL} —— 与 {@link
   * MerchantTradeTemplate#industry(HexCoord)} 的 {@code capacityPerUnit} 键**同源**（不是第二个拼写点： 本方法直接从模板的
   * {@code capacityPerUnit.keySet()} 取，模板改哪一维这里跟着改）。
   *
   * <p>★ 它是"**不凭空造资产**"在决策层的落点：按需创建的 {@code trade@H} 只承载这一份真实存在的作业资本。 ★ 执行期还有一条"已被别的组织实际使用 ⇒
   * 留原户并具名"的排除（{@code foreignUsedOwnershipStakeIds}）—— 那种极端情形由 {@code ModeMigrationSettlement}
   * 在执行前**具名跳过该笔迁移**（不静默丢人、也不让 tick 抛）。
   */
  private static Map<AssetKind, Long> carriedCarrierAssetsForMove(
      HouseholdEconomy sourceHouseholdEconomy,
      long take,
      Map<AssetShareId, OwnershipStake> assetShares) {
    long population = sourceHouseholdEconomy.population();
    ActorRef sourceActor = HouseholdActors.of(sourceHouseholdEconomy.id());
    boolean empties = population <= 0L || take >= population;
    // ★ W7-fix：作业资本那一维 = 模板声明的 capacityPerUnit 键集（唯一拼写点是模板，不在这里写死 AssetKind）。
    Map<AssetKind, Long> carriedByKind = new LinkedHashMap<>();
    for (AssetKind kind : MerchantTradeTemplate.capacityKinds()) {
      carriedByKind.put(kind, 0L);
    }
    for (OwnershipStake share : assetShares.values()) {
      if (share == null
          || !carriedByKind.containsKey(share.asset())
          || share.quantity() <= 0L
          || !share.owner().equals(sourceActor)) {
        continue;
      }
      long amount =
          empties
              ? share.quantity()
              : Math.multiplyExact(share.quantity(), take) / Math.max(1L, population);
      carriedByKind.merge(share.asset(), amount, Math::addExact);
    }
    Map<AssetKind, Long> coverage = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : carriedByKind.entrySet()) {
      if (entry.getValue() > 0L) {
        coverage.put(entry.getKey(), entry.getValue());
      }
    }
    return coverage;
  }

  /**
   * "检查并预留"：按 laborMilli 推出的规模算每个 required 的 need，闲置资产够（Σ 闲置 − 已预留 ≥ need）则把 need 累加进共享 {@code
   * reservedIdle} 并返回 true；任一 required 不足 ⇒ false，<b>且不做任何部分预留</b>。
   *
   * <p>★ 口径与执行期 {@code ModeMigrationSettlement.planAssetMoves} 对齐：同一份"闲置"定义 （{@link
   * #isIdleShare}：{@code quantity > 0 && operator == owner && id ∉ claimedByEnterprises}）与同一个
   * {@code scale} 公式（★ W9-fix2：那一份公式 = {@link #requiredWorkCapitalScale}，<b>唯一拼写点</b>，两侧不得各拼一遍）； 按
   * {@link IndustryId} 预留 ⇒ 同一产业的多个 mode 共享同一个闲置分母，不会各看各的快照。 执行侧逐笔拆完 share 后，各产业/种类的剩余量与本账本一致。
   *
   * <p>★★★ <b>W9：{@code crossIndustryIdleInHex} / {@code hex}</b> —— 为真时，闲置份额的候选集从"同一产业模板下"放宽到
   * "**同一格内、同一种 AssetKind**"（与执行期 {@code planAssetMoves} 的放宽分支**同一条规则**）：按需新建的 trade 产业名下
   * 不可能有任何份额，不放宽就永远凑不齐作业资本（W7 记的 E3 遗留门槛）。★ 安全性仍来自 {@code isIdleShare} —— 闲置 = 没有任何组织/在产 unit 在用。
   */
  /**
   * ★★★ <b>W9-fix2（2026-10-11）「本笔需要多少作业资本」的<b>唯一拼写点</b></b> —— 计划期<b>预留</b>（{@link
   * #reserveIdleAssetsFor}）与执行期<b>索取</b>（{@code ModeMigrationSettlement.createEnterpriseAndProcess}
   * → {@code planAssetMoves}）必须共用本式，两侧不得各拼一遍。
   *
   * <pre>
   * scale = laborPerUnit ≤ 0 ? 1 : max(1, ⌊laborMilli ÷ laborPerUnit⌋)
   * 需要量 = scale × capacityPerUnit[每维]        （索取/预留那一侧各自按此乘，见 planAssetMoves）
   * </pre>
   *
   * <p>★★★ <b>P0 语义只在这里写一次（{@code laborMilli} 该传哪一行）</b>：本式的 {@code laborMilli} 必须是
   * <b>执行期真的会读到的那一行劳动</b>。对"本次新建的目标家户"，那是<b>带本笔随迁投影的工作行视图</b> （{@code 0 + 本笔迁入劳动}，见 {@code
   * ModeMigrationSettlement.apply} 的 {@code withPopulationAndLabor(plannedPopulation,
   * plannedLabor)}）；<b>落盘行在 P0 下恒为 0</b>（人口/劳动由 App 按 outbox 回写，本类只推投影账）。逐字读落盘行会得到 {@code scale =
   * 1} —— <b>那是计划期的旧假设，不是执行期的读法</b>。
   *
   * <p>★ <b>W9-fix2 修掉的就是这一条具名不一致</b>：改前计划期（{@link #perMoveNewRoom} 的按需 trade 支）按"落盘行 labor = 0"传
   * {@code laborPerUnit}（⇒ scale = 1）预留，执行期却按投影行读（{@code scale = ⌊laborTake ÷ laborPerUnit⌋}）索取 ⇒ N
   * 倍缺口 ⇒ 撞执行期 fail-closed 守卫（three-powers 真世界 day=270：{@code labor=8857}、 {@code
   * laborPerUnit=1000} ⇒ scale = 8，而计划只预留了 1 份）。★ 守卫本身是对的（计划不该包含预留不足的目标）， 修的是<b>预留这一侧</b>，不是放宽守卫。
   *
   * <p>★ <b>语义有歧义 ⇒ 按较大者取</b>（fail-closed：宁可多预留、不许执行期缺口）：两种读法 = P0 字面（labor = 0 ⇒ scale
   * 1）与随迁劳动（labor = 本笔迁入劳动 ⇒ scale N）。本式的 {@code max(1, …)} 把"字面 1"兜成下限，调用方给 <b>执行期会读到的那一份
   * labor</b>（计划期 = {@link #laborCarriedByMoveUpperBound}，按向上取整取较大者）。
   *
   * @param industry 目标产业模板（{@code laborPerUnit} 的唯一来源）
   * @param laborMilli 执行期会读到的那一行劳动（毫劳动；新建户 = 本笔迁入劳动，不是落盘行的 0）
   */
  static long requiredWorkCapitalScale(Industry industry, long laborMilli) {
    Objects.requireNonNull(industry, "industry");
    long laborPerUnit = industry.recipe().laborPerUnit();
    return laborPerUnit <= 0L ? 1L : Math.max(1L, laborMilli / laborPerUnit);
  }

  /**
   * ★★★ <b>W9-fix2 唯一拼写点（口径的另一半）：本笔随迁人口带来的劳动</b> —— 计划期预留用它当 {@link #requiredWorkCapitalScale} 的
   * labor 入参。
   *
   * <pre>
   * ⌈源户劳动 × 迁入人口 ÷ 源户人口⌉        （源户人口 ≤ 0 ⇒ take，与改前同：不按 1:1 猜劳动）
   * </pre>
   *
   * <p>★ 与执行期同源：{@code ModeMigrationSettlement.apply} 的 {@code laborTake} 是同一比例的<b>向下取整</b>（{@code
   * ⌊剩余劳动 × 本笔人口 ÷ 剩余人口⌋}，整户迁空 ⇒ 全部剩余劳动）；两者在"劳动随人口成比例"这一条既有假设下逐值相同。
   *
   * <p>★ <b>为什么这里向上取整</b>（"语义有歧义 ⇒ 按较大者预留"，派单裁定）：① 执行期在<b>运行中</b>的剩余量上取整 （逐笔 floor 的余数会累积 ⇒
   * 执行期读数可能比"按当日初值算的比例值"高至多几毫劳动）；② 向上取整把这一点余量提前 预留掉，代价 ≤ 1 毫劳动（只在恰好落在规模边界下 1 毫内时才多预留 1 个单位规模）。
   */
  static long laborCarriedByMoveUpperBound(
      long sourceLaborMilli, long sourcePopulation, long take) {
    if (sourcePopulation <= 0L) {
      return take;
    }
    long numerator = Math.multiplyExact(sourceLaborMilli, take);
    // ★ 向上取整**不用 {@link #ceilDiv}**：那条是 (numerator + denominator - 1) / denominator，numerator 接近
    //   Long.MAX_VALUE 时会先溢出成负数（静默给错值）；这里用商/余数形态，逐值等价且不溢出。
    long quotient = numerator / sourcePopulation;
    return numerator % sourcePopulation == 0L ? quotient : Math.addExact(quotient, 1L);
  }

  private static boolean reserveIdleAssetsFor(
      Industry industry,
      long laborMilli,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByEnterprises,
      Map<AssetKind, Long> carriedByKind,
      boolean crossIndustryIdleInHex,
      HexCoord hex) {
    long scale = requiredWorkCapitalScale(industry, laborMilli);
    Map<AssetKind, Long> reservedForIndustry = reservedIdle.getOrDefault(industry.id(), Map.of());
    Map<AssetKind, Long> needs = new LinkedHashMap<>();
    String hexKey = hex == null ? null : IndustryHexKeys.hexKey(hex.q(), hex.r());
    for (Map.Entry<AssetKind, Long> required : industry.recipe().capacityPerUnit().entrySet()) {
      long need = Math.multiplyExact(scale, required.getValue());
      // ★★★ W7：**随迁携带**的那一份先计入可用量（按需创建的 {@code trade@H} 下还不存在任何份额，
      //   它的作业资本就是这批人自己背过来的 —— 见 carriedCarrierAssetsForMove；
      //   ★ W7-fix 起那一维 = 工具，改前 = 牲口）。
      long available = carriedByKind.getOrDefault(required.getKey(), 0L);
      for (OwnershipStake share : assetShares.values()) {
        if (share.asset() != required.getKey() || !isIdleShare(share, claimedByEnterprises)) {
          continue;
        }
        boolean sameIndustry = share.industry().equals(industry.id());
        boolean sameHexOtherIndustry =
            crossIndustryIdleInHex
                && !sameIndustry
                && hexKey != null
                && IndustryHexKeys.hexKeyOf(share.industry()).filter(hexKey::equals).isPresent();
        if (!sameIndustry && !sameHexOtherIndustry) {
          continue;
        }
        available = Math.addExact(available, share.quantity());
      }
      long alreadyReserved = reservedForIndustry.getOrDefault(required.getKey(), 0L);
      if (Math.subtractExact(available, alreadyReserved) < need) {
        return false; // 全有或全不：先查完全部 required，确认全部可行后才写账本。
      }
      needs.put(required.getKey(), need);
    }
    Map<AssetKind, Long> bucket =
        reservedIdle.computeIfAbsent(industry.id(), key -> new LinkedHashMap<>());
    for (Map.Entry<AssetKind, Long> need : needs.entrySet()) {
      bucket.merge(need.getKey(), need.getValue(), Math::addExact);
    }
    return true;
  }

  /** 同 (hex, mode) 的已有目标家户：剩余容量最大者，tie 用 id 升序（确定性）。 */
  private static HouseholdId bestExistingTarget(
      HouseholdId source,
      HexCoord hex,
      ProductionMode mode,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      List<ProductionRole> producingPositions,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      boolean hostIndustryAvailable) {
    if (!hostIndustryAvailable) {
      // ★★★ W7：本格**没有**该 mode 的产业模板 ⇒ 不得并入既有户（并入 = 一户没有生产 unit 的空壳；
      //   见调用点的具名理由）。返回 null 让上层走"新建户"那一支（新建会按需建产业 + 由该户自带的资产建 unit）。
      return null;
    }
    Set<ClassPositionId> positionIds = new LinkedHashSet<>();
    for (ProductionRole position : producingPositions) {
      positionIds.add(position.id());
    }
    HouseholdId best = null;
    long bestRoom = 0L;
    for (HouseholdId household : sortedHouseholds(householdEconomies)) {
      if (household.equals(source)) {
        continue;
      }
      HouseholdMode candidate = modeByHousehold.get(household);
      if (candidate == null
          || !candidate.modeId.equals(mode.id())
          || !candidate.hex.equals(hex)
          || !positionIds.contains(candidate.positionId)) {
        continue;
      }
      long room =
          Math.max(0L, MAX_HOUSEHOLD_POPULATION - householdEconomies.get(household).population());
      if (room > bestRoom
          || (room == bestRoom
              && room > 0L
              && (best == null || household.value().compareTo(best.value()) < 0))) {
        best = household;
        bestRoom = room;
      }
    }
    return best;
  }

  // ── 预期收益（A 规则，D-024：与候选评估共用同一个计算器）──────────────────────────────────

  /**
   * ★★ <b>D-024</b>：A 规则与"能否维持生产"改读 {@link ExpectedProfitBook.Prospect} —— 不再保留第二套 {@code output ×
   * 价 − 投入} 的旧公式（那会与候选门槛漂开）。{@code nextCycleInputNeed} = 同一个 prospect 的 {@code
   * inputCostMilli}；{@code canSustainProduction} = prospect 可行且规模 ≥ 1。
   */
  private static ExpectedProfit expectedProfit(
      ExpectedProfitBook.Prospect currentProspect, long liquidityMilli) {
    return new ExpectedProfit(
        currentProspect.netMilli(),
        currentProspect.laborNeedMilli(),
        currentProspect.inputCostMilli(),
        liquidityMilli,
        currentProspect.feasible() && currentProspect.feasibleScale() > 0L,
        currentProspect.reason());
  }

  /**
   * 当前户实际在产的产业模板（组织/unit 优先；找不到 ⇒ null，让 {@link ExpectedProfitBook} 按 mode 默认 regime 挑）。
   * 这样"当前户读数"用真实在产的配方，而不是"该格第一个模板"。
   */
  private static Industry currentIndustryOf(
      EconomyDayView base,
      HouseholdId household,
      HouseholdMode current,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    ActorRef actor = HouseholdActors.of(household);
    for (ProductionOrganizationId organizationId : sortedEnterpriseIds(enterprises)) {
      ProductionEnterprise enterprise = enterprises.get(organizationId);
      if (enterprise == null
          || !enterprise.organizer().equals(actor)
          || !current.modeId.equals(enterprise.modeId())
          || enterprise.unitId().isEmpty()) {
        continue;
      }
      ProductionProcess unit = units.get(enterprise.unitId().get());
      if (unit != null) {
        Industry industry = base.industries().get(unit.industry());
        if (industry != null) {
          return industry;
        }
      }
    }
    // 兜底：本户名下的 unit（旧档/手工夹具可能没有对应组织行），按 unit id 升序取第一个同格模板。
    List<ProductionUnitId> unitIds = new ArrayList<>(units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionProcess unit = units.get(unitId);
      if (unit == null || !unit.operator().equals(actor)) {
        continue;
      }
      HexCoord unitHex = unitHexOrNull(unit);
      if (unitHex != null && unitHex.equals(current.hex)) {
        Industry industry = base.industries().get(unit.industry());
        if (industry != null) {
          return industry;
        }
      }
    }
    return null;
  }

  /**
   * 流动性（毫计价货币）= 本格计价币现金 + 库存按参考价折算（与旧 A 规则同一口径）。
   *
   * <p>★★ <b>M-D：算式的唯一拼写点搬进 {@link PrimaryModeRanking#inventoryMilli}</b>（"库存"是排序表与 A 规则共用的
   * 同一个输入，Q-18）——本方法只做委托，**不保留第二份算式**（含 2026-10-09 的溢出饱和修复）。
   */
  private static long liquidityMilli(
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology,
      HouseholdId household,
      HexCoord hex) {
    return PrimaryModeRanking.inventoryMilli(accounts, markets, topology, household, hex);
  }

  /**
   * ★★★ <b>W9（2026-10-11，用户裁定 v3.30 §7 第 2 条）：转业速度的唯一算式 —— 预期收益差驱动</b>。
   *
   * <pre>
   * gap        = 收益_其他(每单位劳动净收益, 缩放整型) − 收益_当前(同尺)
   * gap ≤ 0    ⇒ 0     （"其他生产方式不比现在赚钱" ⇒ **不产生转业速度** = 不转业）
   * gap &gt; 0    ⇒ clamp(⌊SLOPE × gap ÷ 分母⌋, 1, 1000)
   *
   * 分母（**具名**，W9-fix 口径）= max(|收益_当前|, |收益_其他|, 收益规模基准)
   *   · |收益_当前| / |收益_其他| ⇒ 相对差两侧都算（"比现在多赚几成"），并保证 分母 ≥ |gap| ÷ 2
   *   · 收益规模基准 = 两侧生产方式"1 单位规模劳动的毛收入 / 口粮成本"取大（{@link #profitScaleBasisScaled}）
   *     ⇒ **正参考基准**：收益 ≈ 0 的户（流民/不可行户）不会拿到无穷大的相对差
   *   · 基准 = 0（两侧都没有规模/没有市场 ⇒ 夹具世界）⇒ 分母退化为 max(|收益_当前|, |收益_其他|)：
   *     它在 gap &gt; 0 时**恒 &gt; 0**（两者不会同时为 0），且速度 ≤ 2×SLOPE = 200‰ ⇒ 仍被
   *     {@link #MIGRATION_SPEED_CAP_PER_MILLE} 的人口闸夹住（不是 W9 首版的 1000‰ 爆炸）。
   * </pre>
   *
   * <p>★★★ <b>W9-fix 修的是什么（控制方裁定 v3.32 §7 第 1 条）</b>：W9 首版分母 = {@code max(1, |收益_当前|)} —— "收益_当前 ≈
   * 0"时它退化为 1，把"无收益"变成"无穷大相对差" ⇒ 打满 1000‰（真世界实测 sw 68/91、tp 62/93 笔， 单笔最多 199 人）⇒ 一轮搬空家户/整格 ⇒
   * three-powers **21 个零成交轮**（硬牙 {@code 零成交轮次 = 0} 被打破）。 现在分母**永远含一个正的收益规模基准**（或至少含 |收益_其他|），"收益
   * 0"不再等于"无穷大相对差"。
   *
   * <p>★ <b>量纲</b>：两侧都是 {@link ExpectedProfitBook.Prospect#netPerLaborScaled()}（每单位劳动的净收益， 缩放到整型）⇒
   * 比值无量纲，乘 SLOPE 得 ‰/轮。⇒ 速度与人口无关，人口只决定"这一轮最多走多少人" （{@link #moveablePopulation}）。
   *
   * @param otherNetPerLaborScaled 候选目标的每单位劳动净收益
   * @param currentNetPerLaborScaled 当前生产方式的同一读数
   * @return 转业速度（‰/轮）；0 = 不转业
   */
  static long profitGapSpeedPerMille(long otherNetPerLaborScaled, long currentNetPerLaborScaled) {
    // ★ 没有"收益规模基准"入参的调用点（旧夹具/薄封装）⇒ 基准 = 0；生产路径一律走下面的三参形态。
    return profitGapSpeedPerMille(otherNetPerLaborScaled, currentNetPerLaborScaled, 0L);
  }

  /**
   * ★★★ <b>W9-fix：转业速度算式（含正参考基准）</b> —— 生产路径的唯一入口（{@link #profitGapSpeedPerMille(long, long)}
   * 是本方法的"基准 = 0"薄封装）。
   *
   * @param profitScaleBasisScaled 两侧生产方式的收益规模基准取大后的值（≥ 0；见 {@link #profitScaleBasisScaled}）
   */
  static long profitGapSpeedPerMille(
      long otherNetPerLaborScaled, long currentNetPerLaborScaled, long profitScaleBasisScaled) {
    long gap;
    try {
      gap = Math.subtractExact(otherNetPerLaborScaled, currentNetPerLaborScaled);
    } catch (ArithmeticException overflow) {
      gap = otherNetPerLaborScaled > currentNetPerLaborScaled ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
    if (gap <= 0L) {
      return 0L;
    }
    // ★ 斜率 = 0 的语义（与 α=0 "GM 把价格钉死" 同制）：**收益差不再产生任何转业速度** ⇒ 转业停摆。
    //   没有这一条，下面的"至少 1‰"地板会把"GM 关掉转业"这一合法状态变成"每轮仍走 1‰"。
    if (PROFIT_GAP_SPEED_SLOPE_PER_MILLE <= 0L) {
      return 0L;
    }
    // ★★★ W9-fix：分母 = max(|收益_当前|, |收益_其他|, 收益规模基准) —— **三个都是非负量**，且 gap > 0 时前两项
    //   不会同时为 0 ⇒ 分母恒 > 0（不需要 `max(1, ·)` 那种"不许除零"的补丁，那个补丁正是 W9 首版的病根）。
    long denominator =
        Math.max(
            Math.max(absOrMax(currentNetPerLaborScaled), absOrMax(otherNetPerLaborScaled)),
            Math.max(0L, profitScaleBasisScaled));
    if (denominator <= 0L) {
      return 0L; // 理论不可达（gap > 0 ⇒ 有一侧非 0）；fail-closed 不除零。
    }
    // 先除后乘 + 余数补算：逐值等于 ⌊SLOPE × gap ÷ denominator⌋，但不会在极端值上溢出。
    long whole = gap / denominator * PROFIT_GAP_SPEED_SLOPE_PER_MILLE;
    long residual = gap % denominator * PROFIT_GAP_SPEED_SLOPE_PER_MILLE / denominator;
    long speed;
    try {
      speed = Math.addExact(whole, residual);
    } catch (ArithmeticException overflow) {
      speed = Long.MAX_VALUE;
    }
    if (speed <= 0L) {
      speed = 1L; // gap > 0 且斜率 > 0 ⇒ 至少 1‰（"只要更高就有速度"，不打成 0）
    }
    return Math.min(A_RULE_TRANSFER_SPEED_PER_MILLE, speed);
  }

  /**
   * ★★★ <b>W9-fix（2026-10-11，控制方裁定 v3.32 §7 第 1 条）：一个生产方式的"收益规模基准"（缩放整型）</b> ——
   * 速度算式的**正参考基准**（分母下限）。
   *
   * <pre>
   * 基准(p) = max( 每单位劳动的毛收入   = perLaborScaled(p.revenueMilli,   p.laborNeedMilli),
   *               每单位劳动的口粮/劳动成本 = perLaborScaled(p.laborCostMilli, p.laborNeedMilli) )
   * p.laborNeedMilli ≤ 0（不可行/规模 0）⇒ 0
   * </pre>
   *
   * <p>★ <b>为什么是这两个量（回代码找的正量，不是拍的魔法数）</b>：收益差是"**相对什么**的差" —— 相对的是**这个生产方式 本身的收益规模**：1
   * 单位规模劳动能带来多少产值（{@code revenueMilli}）、至少要吃掉多少口粮（{@code laborCostMilli}）。两者都是 {@link
   * ExpectedProfitBook.Prospect} 现成的读数，且与收益差**同一把尺** （{@link ExpectedProfitBook#perLaborScaled}）⇒
   * 比值无量纲、不会漂开。取 max ⇒ 只要这个生产方式还在产东西、或还要养人， 基准就 &gt; 0。
   *
   * <p>★★ <b>它替掉的是什么</b>：W9 首版分母 {@code max(1, |收益_当前|)} 在"收益 ≈ 0"时退化为 1（把"无收益"读成 "无穷大相对差"）⇒ 打满
   * 1000‰ ⇒ 一次把整户/整格搬空。见 {@link #profitGapSpeedPerMille} 的类注。
   */
  static long profitScaleBasisScaled(ExpectedProfitBook.Prospect prospect) {
    Objects.requireNonNull(prospect, "prospect");
    long laborNeed = prospect.laborNeedMilli();
    if (laborNeed <= 0L) {
      return 0L; // 没有规模（不可行户/流民户）⇒ 没有基准
    }
    return Math.max(
        ExpectedProfitBook.perLaborScaled(prospect.revenueMilli(), laborNeed),
        ExpectedProfitBook.perLaborScaled(prospect.laborCostMilli(), laborNeed));
  }

  /**
   * ★★★ <b>W9-fix：可迁人口（"这一轮这一户最多走多少人"）—— 唯一拼写点</b>。
   *
   * <pre>
   * moveable = max(1, ⌊人口 × 速度‰ ÷ 1000⌋)，再夹到 [0, 人口]
   * 人口 ≤ 0 ∨ 速度 ≤ 0 ⇒ 0（没有可迁的人 / 速度 0 = 不转业）
   * </pre>
   *
   * <p>★ <b>"至少 1 人"地板（J3，逐值等于 W9）</b>：没有它，人口 &lt; 1000/速度 的户在任何速度下都走不动 （"只要更高就有速度"会被整数截断吃掉）。
   *
   * <p>★ <b>乘法溢出 ⇒ 返回整个人口（fail-closed，不静默回绕）</b>：速度 ≤ 1000 ⇒ "溢出的份额"必然 ≥ 100%， 夹到全户是唯一正确的一侧。
   *
   * <p>★ <b>两个调用点</b>：① 收益差速度（{@code bestSpeedPerMille}）；② {@link #MIGRATION_SPEED_CAP_PER_MILLE}
   * 的速率上限。取两者的小者 = "差越大越快，但每轮不超过上限"。
   */
  static long moveablePopulation(long population, long speedPerMille) {
    if (population <= 0L || speedPerMille <= 0L) {
      return 0L;
    }
    long scaled;
    try {
      scaled = Math.multiplyExact(population, speedPerMille) / A_RULE_TRANSFER_SPEED_PER_MILLE;
    } catch (ArithmeticException overflow) {
      return population;
    }
    return Math.min(population, Math.max(1L, scaled));
  }

  /** {@code |value|}，{@code Long.MIN_VALUE} 取饱和（denominator 必须是非负量，不许回绕成负数）。 */
  private static long absOrMax(long value) {
    return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
  }

  /** 本格价表：优先本格市场；无则退到该格所在区的集散节点市场（区内同价）；都没有 ⇒ null。 */
  private static MarketTable marketOf(
      Map<MarketZoneId, MarketTable> markets, MarketTopology topology, HexCoord hex) {
    return PrimaryModeRanking.marketOf(markets, topology, hex);
  }

  /**
   * ★★ <b>M-A1：merchant 允许条件用 —— 该格有没有"选了跑商且有运力"的家户</b>（运力 0 不算）。
   *
   * <p>运力是派生量（{@link MerchantCapacity}）：成员判据 = {@code classStandings} 的 {@code
   * effectivePositionIds()} 含 merchant 生产方式的位置；本查询走纯状态口径（工具维取 0 = 运力下界，见 {@link
   * MerchantCapacityPool#hasCapacityAt(EconomyData, HexCoord)}）。旧"查商号行"的实现已随 {@code merchantFirms}
   * 退役。
   */
  private static boolean hasMerchantCapacityAt(EconomyDayView base, HexCoord hex) {
    return MerchantCapacityPool.hasCapacityAt(base, hex);
  }

  /**
   * 需求簿视界：<b>世界现有 unit 所属产业 cycleDays 的最大者</b>（多个不同值时取最大并在此具名说明；本轮真实世界 所有产业都是 120）。没有 unit（或 unit
   * 模板缺失）时退回"全部产业的最大者"，再没有 ⇒ 120。
   */
  private static long demandHorizonDays(
      EconomyDayView base, Map<ProductionUnitId, ProductionProcess> units) {
    long horizon = 0L;
    for (ProductionProcess unit : units.values()) {
      Industry industry = base.industries().get(unit.industry());
      if (industry != null) {
        horizon = Math.max(horizon, industry.cycleDays());
      }
    }
    if (horizon > 0L) {
      return horizon;
    }
    for (Industry industry : base.industries().values()) {
      if (industry != null) {
        horizon = Math.max(horizon, industry.cycleDays());
      }
    }
    return horizon <= 0L ? DEFAULT_DEMAND_HORIZON_DAYS : horizon;
  }

  /**
   * ★★ <b>该 mode 在该格能用哪些产业模板</b>（策略/执行/预期利润三处共用，避免各写一份 mode→regime 映射）：
   *
   * <ol>
   *   <li>按 mode 的默认 regime（{@link RegimeOperators#defaultRegimeForMode}）筛；有匹配 ⇒ 只回匹配的（按 id 升序）；
   *   <li>没有匹配：merchant ⇒ <b>空</b>（绝不把农场模板当商号）；其余 mode/未登记制度 ⇒ 该格全部模板（保持既有回退）。
   * </ol>
   */
  static List<IndustryId> industriesForMode(
      EconomyDayView base, HexCoord hex, ProductionModeId modeId) {
    Objects.requireNonNull(base, "base");
    return industriesForMode(base.industries(), hex, modeId);
  }

  /**
   * ★★★ <b>W7（2026-10-11）：上一条的"显式产业表"重载</b>（唯一判据逐字相同）。
   *
   * <p>★ <b>为什么要它</b>：W7 起 {@code trade} 可以在迁移时**按需创建**（写进 {@code EconomySession} 的工作副本）， 而"这一格有没有
   * merchant 模板"从创建那一刻起就必须读**工作副本**（不是当日只读视图）—— 否则同一 tick 内后面的 迁移笔看不到刚落的那一条，会重复创建或走错分支。★
   * 判据本体只有这一处拼写。
   */
  static List<IndustryId> industriesForMode(
      Map<IndustryId, Industry> industries, HexCoord hex, ProductionModeId modeId) {
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(modeId, "modeId");
    List<IndustryId> candidates = new ArrayList<>(IndustryHexKeys.at(industries, hex.q(), hex.r()));
    candidates.sort(Comparator.comparing(IndustryId::value));
    Optional<String> wanted = RegimeOperators.defaultRegimeForMode(modeId);
    if (wanted.isEmpty()) {
      return List.copyOf(candidates);
    }
    List<IndustryId> matching = new ArrayList<>();
    for (IndustryId industryId : candidates) {
      Industry industry = industries.get(industryId);
      if (industry != null && wanted.get().equals(industry.regime().value())) {
        matching.add(industryId);
      }
    }
    if (!matching.isEmpty()) {
      return List.copyOf(matching);
    }
    if (DefaultProductionModes.MERCHANT.equals(modeId)) {
      return List.of();
    }
    return List.copyOf(candidates);
  }

  private static HexCoord unitHexOrNull(ProductionProcess unit) {
    return IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse).orElse(null);
  }

  private static List<ProductionOrganizationId> sortedEnterpriseIds(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    List<ProductionOrganizationId> ids = new ArrayList<>(enterprises.keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    return ids;
  }

  // ── 钱/债的比例切分（逐笔 floor；源户人口归零时余数随最后一笔走）────────────────────────────

  private static HouseholdMode currentOf(
      Map<HouseholdId, HouseholdMode> modeByHousehold, HouseholdId source) {
    HouseholdMode mode = modeByHousehold.get(source);
    if (mode == null) {
      throw new IllegalStateException("计划的源家户没有 mode 索引（坏输入）: " + source);
    }
    return mode;
  }

  private static CurrencyId currentCurrency(
      HouseholdMode current,
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketZone zone) {
    // ★ W1：本格价表 = 本格所属市场的那一张（价表每区一张；区内任何格同一张）。
    MarketTable market = zone == null ? null : markets.get(zone.zoneId());
    if (market != null) {
      return market.numeraire();
    }
    Map<CurrencyId, Long> money =
        accounts.householdMoney().getOrDefault(current.household, Map.of());
    CurrencyId smallest = null;
    for (CurrencyId currency : money.keySet()) {
      if (smallest == null || currency.value().compareTo(smallest.value()) < 0) {
        smallest = currency;
      }
    }
    return smallest;
  }

  /**
   * ★★ <b>P11.1 / D-023：按人口比例切源户的“全部币种”余额</b>（逐币种独立、逐笔 floor、<b>不做 FX</b>）。
   *
   * <pre>
   * 逐币种 c（CurrencyId.value 升序）：remaining = 当前余额
   *   第 i 笔（i = 0..n-1）：denom = max(1, sourcePopulation − movedBefore)
   *     share_i = (源户迁空 && i == n−1) ? remaining : ⌊remaining × populations[i] ÷ denom⌋
   *     remaining -= share_i；movedBefore += populations[i]
   * 源户不迁空 ⇒ 每个币种的 floor 余数留在源户；迁空 ⇒ 余数随最后一笔走。
   * </pre>
   *
   * <p>★ 返回的第 i 个 map 就是第 i 笔 move 的 {@code moneyByCurrency}；不同币种并列存在，不互相折算。
   */
  private static List<Map<CurrencyId, Long>> splitMoneyByCurrency(
      AccountSession accounts,
      long[] populations,
      long sourcePopulation,
      HouseholdId source,
      boolean emptiesSource) {
    List<Map<CurrencyId, Long>> shares = new ArrayList<>(populations.length);
    for (int i = 0; i < populations.length; i++) {
      shares.add(new LinkedHashMap<>());
    }
    Map<CurrencyId, Long> balances = accounts.householdMoney().getOrDefault(source, Map.of());
    List<CurrencyId> currencies = new ArrayList<>(balances.keySet());
    currencies.sort(Comparator.comparing(CurrencyId::value));
    for (CurrencyId currency : currencies) {
      long remainingBalance = balances.getOrDefault(currency, 0L);
      if (remainingBalance <= 0L) {
        continue;
      }
      long movedBefore = 0L;
      for (int i = 0; i < populations.length; i++) {
        long denom = Math.max(1L, sourcePopulation - movedBefore);
        long share =
            emptiesSource && i == populations.length - 1
                ? remainingBalance
                : Math.multiplyExact(remainingBalance, populations[i]) / denom;
        if (share < 0L || share > remainingBalance) {
          throw new IllegalStateException(
              "货币切分出现非法份额（坏数据）: source="
                  + source
                  + " currency="
                  + currency
                  + " share="
                  + share
                  + " remaining="
                  + remainingBalance);
        }
        remainingBalance -= share;
        movedBefore = Math.addExact(movedBefore, populations[i]);
        if (share > 0L) {
          shares.get(i).put(currency, share);
        }
      }
      if (remainingBalance < 0L) {
        throw new IllegalStateException("货币切分出现负余额（坏数据）: source=" + source);
      }
    }
    return shares;
  }

  private static long[] splitDebt(
      long[] populations,
      long sourcePopulation,
      HouseholdId source,
      Map<DebtContractId, DebtContract> debts,
      boolean emptiesSource) {
    long[] shares = new long[populations.length];
    List<DebtContract> contracts = new ArrayList<>();
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(source) && contract.principal() > 0L) {
        contracts.add(contract);
      }
    }
    contracts.sort(Comparator.comparing(contract -> contract.id().value()));
    long[] remainingPrincipal = new long[contracts.size()];
    for (int i = 0; i < contracts.size(); i++) {
      remainingPrincipal[i] = contracts.get(i).principal();
    }
    long movedBefore = 0L;
    for (int moveIndex = 0; moveIndex < populations.length; moveIndex++) {
      long denom = Math.max(1L, sourcePopulation - movedBefore);
      long total = 0L;
      for (int contractIndex = 0; contractIndex < contracts.size(); contractIndex++) {
        long take =
            emptiesSource && moveIndex == populations.length - 1
                ? remainingPrincipal[contractIndex]
                : Math.multiplyExact(remainingPrincipal[contractIndex], populations[moveIndex])
                    / denom;
        take = Math.min(take, remainingPrincipal[contractIndex]);
        remainingPrincipal[contractIndex] -= take;
        total = Math.addExact(total, take);
      }
      shares[moveIndex] = total;
      movedBefore = Math.addExact(movedBefore, populations[moveIndex]);
    }
    return shares;
  }

  // ── 小工具 ────────────────────────────────────────────────────────────────────────────────

  private static boolean hasIndustryTemplate(EconomyDayView base, HexCoord hex) {
    return !IndustryHexKeys.at(base.industries(), hex.q(), hex.r()).isEmpty();
  }

  private static Map<ProductionModeId, List<ProductionRole>> producingPositionsByMode(
      EconomyDayView base, List<ProductionMode> modes) {
    Map<ProductionModeId, List<ProductionRole>> result = new LinkedHashMap<>();
    for (ProductionMode mode : modes) {
      ClassStructure structure = base.classStructures().get(mode.classStructureId());
      if (structure == null) {
        result.put(mode.id(), List.of());
        continue;
      }
      List<ProductionRole> positions = new ArrayList<>();
      for (ProductionRole position : structure.positions().values()) {
        if (shouldProduce(position)) {
          positions.add(position);
        }
      }
      positions.sort(Comparator.comparing(position -> position.id().value()));
      result.put(mode.id(), positions);
    }
    return result;
  }

  private static boolean shouldProduce(ProductionRole position) {
    return position.laborRole() != ProductionRole.LaborRole.NONE
        && position.surplusRole() != ProductionRole.SurplusRole.DEPENDENT;
  }

  // ── 闲置资产判据的唯一拼写点（policy / settlement 共用；纯谓词，不新增持久组件）─────────────

  /**
   * ★★ <b>P10.7 闲置/可租赁资产判据的唯一拼写点</b>（计划期 {@code reserveIdleAssetsFor} 与执行期 {@code
   * ModeMigrationSettlement.planAssetMoves} 共用；两侧不得各拼一遍）：
   *
   * <ol>
   *   <li>{@code quantity() > 0}；
   *   <li>{@code operator().equals(owner())}（自有自营；{@code operator != owner} 已被人实际使用）；
   *   <li>{@code id()} <b>不在</b> claimed 集合里 —— claimed = 既有 {@code
   *       ProductionEnterprise.assetSources} 的并集 ∪ 在产 unit 占用的份额（同 industry、同 operator、{@code
   *       quantity>0}，见 {@link #claimedOwnershipStakes(Map, Map, Map)}）。既有商号/组织/在产 unit
   *       正在使用的份额不是闲置： 执行期若把它整条拆走，源份额行会被删除，组织引用会指向不存在的份额 id，{@code EconomyData} 当即以
   *       「生产组织使用的资产份额必须已存在」fail-closed；在产 unit 被抽走资产则会让产能凭空消失。
   * </ol>
   *
   * <p>★ 结论一句话：<b>只有 {@code owner==operator} 且不被任何组织/在产 unit 使用的份额才算闲置</b>（与 P10.7 的原始 意图一致；"unit
   * 占用"是 D-024 修复 1b 新增的显式一格）。
   */
  static boolean isIdleShare(OwnershipStake share, Set<AssetShareId> claimedByEnterprises) {
    Objects.requireNonNull(share, "share");
    Objects.requireNonNull(claimedByEnterprises, "claimedByOrganizations");
    return share.quantity() > 0L
        && share.operator().equals(share.owner())
        && !claimedByEnterprises.contains(share.id());
  }

  /**
   * ★★ <b>兼容重载：只算"被既有组织引用"这一格</b>，等价于 {@code claimedOwnershipStakes(enterprises, Map.of(),
   * Map.of())}。测试/旧调用点可继续用；生产路径 （{@link #plan} 与 {@code
   * ModeMigrationSettlement.createEnterpriseAndProcess}）一律走三参版本，把当天工作副本 {@code units}/{@code
   * assetShares} 一并传入。两参/三参共用下面同一个谓词，不存在第二套拼写。★ Z7d-2：三参版本公开给 app 组合根的逃亡去向排序复用（不复制谓词）。
   */
  static Set<AssetShareId> claimedOwnershipStakes(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    return claimedOwnershipStakes(enterprises, Map.of(), Map.of());
  }

  /**
   * ★★ P10.7 / P10.8 / D-024 修复 1b：<b>"已被占用、不是闲置"的资产份额 id 并集</b>（只读派生，不新增持久组件）。
   *
   * <p>两格显式占用：
   *
   * <ol>
   *   <li>既有 {@link ProductionEnterprise#assetSources()} 引用的份额（组织正在用）；
   *   <li>传入的 {@code units} 里每个在产 unit 所占的份额：满足 {@link #usedByUnit(OwnershipStake,
   *       ProductionProcess)} —— 同 {@code industry}、同 {@code operator}、{@code quantity>0}。seeder
   *       直接以 {@code RegimeOperators.defaultOperator(...)}（ESTATE 等）建立的 {@code
   *       farm/weave/craft/trade} 主 unit 没有对应的 {@code ProductionEnterprise}，只靠第 ① 格会漏掉它们，让城市户把
   *       ESTATE 的土地/作坊/船畜当 "闲置可租"抢占（D-024 第二轮根因 A）。
   * </ol>
   *
   * <p>计划期在 {@link #plan} 顶层从当天工作副本构建一次并透传；执行期在 {@link
   * ModeMigrationSettlement#createEnterpriseAndProcess} 从同一份当天工作副本再构建一次。集合只用于 {@link #isIdleShare}
   * 的包含判断，不参与排序，故迭代序不影响计划确定性。
   */
  public static Set<AssetShareId> claimedOwnershipStakes(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares) {
    Objects.requireNonNull(enterprises, "organizations");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(assetShares, "assetShares");
    Set<AssetShareId> claimed = new LinkedHashSet<>();
    for (ProductionEnterprise enterprise : enterprises.values()) {
      if (enterprise != null) {
        claimed.addAll(enterprise.assetSources());
      }
    }
    for (ProductionProcess unit : units.values()) {
      if (unit == null) {
        continue;
      }
      for (OwnershipStake share : assetShares.values()) {
        if (usedByUnit(share, unit)) {
          claimed.add(share.id());
        }
      }
    }
    return claimed;
  }

  /**
   * "在产 unit 占用份额"的唯一谓词：{@code share.industry == unit.industry ∧ share.operator == unit.operator ∧
   * share.quantity > 0}。产业 id 自带格键 ⇒ 不需要另判 hex；不判 {@code modeKey}（同产业下一条在产 unit 就是一种 占用，mode
   * 只决定生产方式，不决定实物占用）。{@link #claimedOwnershipStakes(Map, Map, Map)} 的两格都经本方法/同一 循环拼装，别处不得再写一份。
   */
  private static boolean usedByUnit(OwnershipStake share, ProductionProcess unit) {
    Objects.requireNonNull(share, "share");
    Objects.requireNonNull(unit, "unit");
    return share.quantity() > 0L
        && share.industry().equals(unit.industry())
        && share.operator().equals(unit.operator());
  }

  private static List<ProductionMode> sortedModes(EconomyDayView base) {
    List<ProductionMode> modes = new ArrayList<>(base.modes().values());
    modes.sort(Comparator.comparing(mode -> mode.id().value()));
    return modes;
  }

  private static List<HouseholdId> sortedHouseholds(
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    List<HouseholdId> keys = new ArrayList<>(householdEconomies.keySet());
    keys.sort(Comparator.comparing(HouseholdId::value));
    return keys;
  }

  private static List<HexCoord> globalHexes(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<MarketZoneId, MarketTable> markets,
      Map<MarketZoneId, MarketZone> marketZones) {
    TreeSet<HexCoord> hexes =
        new TreeSet<>(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      hexes.add(householdEconomy.view().hex());
    }
    hexes.addAll(
        MarketTables.allMarketHexes(markets, marketZones)); // ★ W1：有市场的格（持久 = 有价表的区成员格；派生 = 价表成员格）
    for (ProductionProcess unit : units.values()) {
      Optional<String> key = IndustryHexKeys.hexKeyOf(unit.industry());
      if (key.isPresent()) {
        int separator = key.get().lastIndexOf('_');
        try {
          hexes.add(
              new HexCoord(
                  Integer.parseInt(key.get().substring(0, separator)),
                  Integer.parseInt(key.get().substring(separator + 1))));
        } catch (RuntimeException ignored) {
          // 不猜坐标
        }
      }
    }
    return new ArrayList<>(hexes);
  }

  private static String canonicalKey(HexCoord hex, ProductionModeId modeId) {
    return modeId.value() + "@" + hex.q() + "_" + hex.r();
  }

  private static HouseholdId newHouseholdId(
      HouseholdId source, HexCoord hex, ProductionModeId modeId, int ordinal) {
    // 迁移新建户 id 的唯一拼写点：确定性、无随机、不含 '.'；ordinal 只服务"同源同目标要建多户"。
    return HouseholdId.parse(
        "hh-mig-"
            + hex.q()
            + "_"
            + hex.r()
            + "-"
            + modeId.value()
            + "-"
            + source.value()
            + "-"
            + ordinal);
  }

  private static long sum(long[] values) {
    long total = 0L;
    for (long value : values) {
      total = Math.addExact(total, value);
    }
    return total;
  }

  /** 内部家户 mode/hex/position 索引。 */
  private record HouseholdMode(
      HouseholdId household, HexCoord hex, ProductionModeId modeId, ClassPositionId positionId) {
    HouseholdMode {
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(modeId, "modeId");
      Objects.requireNonNull(positionId, "positionId");
    }
  }

  /**
   * 目标候选（existingRoom 为已有户剩余容量；newFeasible 为同 (hex, mode) 是否能新建）。
   *
   * <p>★★ <b>M-D 追加 {@code positionId} / {@code yieldPerLaborScaled}</b>：目标那一项的**位置 id**（= 该 (hex,
   * mode) 的 {@code ExpectedProfitBook.Prospect.positionId()}，排序表第 1
   * 项的落点候选）与该行的**收益率读数**（同尺，进排序表与日志）。 ★ {@code positionId} 可 {@code null}（DISPLACED 目标等没有 prospect
   * 的场合）。
   *
   * <p>★★★ <b>W9 追加 {@code speedPerMille} / {@code tierIndex}</b>：这一项的**转业速度**（收益差驱动，见 {@link
   * #profitGapSpeedPerMille}）与**档位**（{@link #supportedTierIndex}；0 = 兜底档）。★ 速度 0 ⇒ 该项不进活候选 （{@code
   * weight > 0 && speed > 0} 才是去处）；档位进 {@code MigrationMove}，决定新建目标户落在哪一档。
   */
  private record Target(
      HexCoord hex,
      ProductionModeId mode,
      HouseholdId existing,
      long weight,
      int distance,
      boolean newFeasible,
      long existingRoom,
      String canonical,
      ClassPositionId positionId,
      long yieldPerLaborScaled,
      String prospectReason,
      long speedPerMille,
      int tierIndex) {
    Target {
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(canonical, "canonical");
      if (speedPerMille < 0L || speedPerMille > A_RULE_TRANSFER_SPEED_PER_MILLE) {
        throw new IllegalArgumentException(
            "Target.speedPerMille 必须在 [0, "
                + A_RULE_TRANSFER_SPEED_PER_MILLE
                + "]（0 = 收益差不为正 ⇒ 不转业）: "
                + speedPerMille);
      }
      if (tierIndex < 0) {
        throw new IllegalArgumentException("Target.tierIndex 不得为负: " + tierIndex);
      }
    }
  }

  /**
   * 施工坞（人口切分与目标确认完成后才冻成 {@link MigrationMove}）。
   *
   * <p>★ M-D：{@code primaryPositionId} = 该目标那一项在**生产方式排序表**里的位置（= {@code current} 的落点）； 可 {@code
   * null}（DISPLACED 等没有 prospect 的目标）。
   */
  private static final class MoveDraft {
    final HouseholdId target;
    final HexCoord targetHex;
    final ProductionModeId targetMode;
    final long transferSpeedPerMille;
    final long population;
    final String reason;
    final ClassPositionId primaryPositionId;

    /** ★★ W9：落点档位（0 = 兜底档/贫农；见 {@link #supportedTierIndex}）。 */
    final int tierIndex;

    MoveDraft(
        HouseholdId target,
        HexCoord targetHex,
        ProductionModeId targetMode,
        long transferSpeedPerMille,
        long population,
        String reason,
        ClassPositionId primaryPositionId,
        int tierIndex) {
      this.target = target;
      this.targetHex = targetHex;
      this.targetMode = targetMode;
      this.transferSpeedPerMille = transferSpeedPerMille;
      this.population = population;
      this.reason = reason;
      this.primaryPositionId = primaryPositionId;
      this.tierIndex = tierIndex;
    }
  }

  /**
   * ★★★ <b>W7（2026-10-11）：一轮"生产方式候选"评估的**具名账**（瞬态；只进日志，不是状态组件）</b>。
   *
   * <p>★★ <b>它回答的问题是</b>：「**为什么这一轮没有新跑商？**」—— 用户派单 ⑤ 的原话： 「{@code NO_MERCHANT_CAPACITY:*} 一族（含
   * {@code no-trade-industry} / {@code no-capacity}）在**决策层静默 continue 处** 发具名 DEBUG（或 INFO，按 §一.9
   * 的级别约定），字段含 hex/mode/reason/关键量级；★ 同时把"为什么这一轮没有新跑商" 在轮级 DEBUG 里可读（含每格候选数与首个失败理由）」。
   *
   * <p>★★ <b>W7-fix（2026-10-11）：{@code NO_MERCHANT_CAPACITY:*} 一族的成员换了</b> —— 用户裁定 v3.28 §7
   * 取消牲口要求后， 该族现在是「{@code no-labor}」（没有劳动投入）/「{@code no-tool-input}」（手上没有工具）/ 「{@code
   * no-capacity}」（服务成市格上取较大者后仍为 0 的兜底）；{@code no-trade-industry} 与 {@code no-carrier-asset}
   * 两条**已不存在**（前者被"按需创建模板"取代，后者被用户裁定删除）。★ 本类的机制一字未改。
   *
   * <pre>
   * 轮级一行（DEBUG，事件 MODE_MIGRATION_CANDIDATES）：
   *   candidateRows          本轮评估过的 (家户 × mode × hex) 候选数
   *   merchantCandidates     其中 merchant 候选数
   *   byHex                  逐格候选数（键序 = hex 规范序）
   *   merchantFirstFailure   逐格"首个具名失败理由"（★ W7 前这条链是**静默 continue**，日志里一片空白）
   *   failuresByReason       逐理由计数（NO_MERCHANT_CAPACITY:no-labor / no-tool-input / no-capacity /
   *                          no-deliverable-service-capacity / no-bargaining-power …）
   *   moves / movedPopulation 本轮真的落地的迁移笔数与人口（0 ⇒ 一行也不少 —— "没有"本身就是要读的事实）
   * 逐候选一行（DEBUG，事件 MODE_MIGRATION_PROSPECT_INFEASIBLE，**只对 NO_MERCHANT_CAPACITY:* 一族发**）：
   *   household / mode / hex / reason / merchantCandidate
   * </pre>
   *
   * <p>★ <b>级别取舍（具名记下）</b>：§一.9 把"业务拒绝"定为 INFO，但这里**不逐候选发 INFO** —— 非城市格每天每户都会 产生同样的拒绝（调查报告实测：sw 17
   * 格 × 每户每天），逐行 INFO 会把真正的"这一轮发生了什么"淹掉。⇒ **轮级一行**（"这一轮有几个候选、为什么没成"）承担 INFO 的可读性职责，逐候选明细留在 DEBUG。
   * 这条取舍与"日志失败不得影响结算"同级：本类只读、不写任何状态、不改任何判据。
   */
  private static final class CandidateTally {

    private final Map<HexCoord, Long> candidatesByHex = new LinkedHashMap<>();
    private final Map<HexCoord, Long> merchantCandidatesByHex = new LinkedHashMap<>();
    private final Map<HexCoord, String> merchantFirstFailureByHex = new LinkedHashMap<>();
    private final Map<String, Long> failuresByReason = new LinkedHashMap<>();
    private long candidateRows = 0L;
    private long merchantCandidates = 0L;

    /** ★★ W9：候选阶段跳过的"壳户"（人口 0 / 无阶层归属的那一族）。 */
    private long shellSkippedNoPopulation = 0L;

    /** ★★ W9：候选阶段跳过的"没有劳动可投"的户（人口 &gt; 0 但参与率折算后劳动 0）。 */
    private long shellSkippedNoLabor = 0L;

    /**
     * ★★ <b>W9（用户裁定 v3.30 §7，用户派单 ⑥）：记一次"壳户在候选阶段被跳过"</b> —— {@code noPopulation = true} ⇒ 人口
     * 0/无归属；{@code false} ⇒ 人口 &gt; 0 但没有劳动可投。
     *
     * <p>★ 只计数、**不逐户打点**：W7e 真世界实测这两族每轮上千条（{@code no-labor} 8,844(sw)/17,610(tp)），
     * 逐户日志会把"这一轮到底发生了什么"淹掉；轮级一行（{@link #logRound}）承担可读性职责。
     */
    private void shellSkipped(boolean noPopulation) {
      if (noPopulation) {
        shellSkippedNoPopulation++;
      } else {
        shellSkippedNoLabor++;
      }
    }

    /** 记一次"这个 (家户, mode, hex) 候选被评估过"。 */
    private void candidate(HexCoord hex, boolean merchant) {
      candidateRows++;
      candidatesByHex.merge(hex, 1L, Math::addExact);
      if (merchant) {
        merchantCandidates++;
        merchantCandidatesByHex.merge(hex, 1L, Math::addExact);
      }
    }

    /**
     * 记一次"这个候选没成"（具名理由）。★ 逐候选 DEBUG **只对 {@code NO_MERCHANT_CAPACITY:*} 一族发** ——
     * 那正是用户点名的、改前**一行都不打**的那一族；其余理由（门槛/实物腿/其它 mode 的不可行）只进轮级计数。
     *
     * <p>★★ <b>W7-fix（2026-10-11）：量级字段补上"劳动 + 工具"两项</b> —— 用户裁定 v3.28 §7 之后该族的新成员 （{@code no-labor}
     * / {@code no-tool-input}）说的正是这两项；只报 {@code deliverableCapacityMilli} 会让"可交付运力明明 &gt; 0
     * 却不可行"在同一行里读不出原因（读数面必须自解释）。
     */
    private void failure(
        HexCoord hex,
        ProductionModeId modeId,
        String reason,
        boolean merchant,
        HouseholdId household,
        long deliverableCapacityMilli,
        HouseholdEconomy row,
        Map<HouseholdId, Map<CommodityId, Long>> goods) {
      String named = reason == null ? "UNSPECIFIED" : reason;
      failuresByReason.merge(named, 1L, Math::addExact);
      if (!merchant) {
        return;
      }
      merchantFirstFailureByHex.putIfAbsent(hex, named);
      if (named.startsWith("NO_MERCHANT_CAPACITY:") && LOG.isDebugEnabled()) {
        // ★ 具名行（字段按用户派单 ⑤：hex / mode / reason / **关键量级**）—— 量级 = 该户的
        //   **可交付运力**（{@link MerchantCapacityPool#providerCapacityMilli}，与结算层同一把尺），
        //   外加 W7-fix 的两个可行性操作数（劳动投入 / 手上的工具）。
        //   ★★ W7-fix3（2026-10-11）：工具存量先在**对象口径**取出来（见下面那条注释）—— 改前它是
        //   `goods == null ? 0L : <Long>`：三目里 long 与 Long 混用会**拆箱**，随即又为 varargs **装箱**
        //   （SpotBugs BX_UNBOXING_IMMEDIATELY_REBOXED）。★ 只改取值形态，不碰字段名/值/级别/发射条件。
        Long toolMilli = 0L;
        if (goods != null) {
          toolMilli =
              goods
                  .getOrDefault(household, Map.of())
                  .getOrDefault(MerchantTradeTemplate.TOOL_COMMODITY, 0L);
        }
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MODE_MIGRATION_PROSPECT_INFEASIBLE",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "household",
                    household.value(),
                    "modeId",
                    modeId.value(),
                    "hex",
                    hex,
                    "reason",
                    named,
                    "deliverableCapacityMilli",
                    deliverableCapacityMilli,
                    "laborMilli",
                    row == null ? 0L : row.participationAdjustedLaborMilli(),
                    "toolMilli",
                    toolMilli));
      }
    }

    /** 轮级汇总（DEBUG，一行）—— **无论本轮有没有迁移都发**（"没有"是要读的事实，不是噪声）。 */
    private void logRound(long day, long moves, long movedPopulation) {
      if (!LOG.isDebugEnabled()) {
        return;
      }
      Map<HexCoord, Long> candidatesView = new LinkedHashMap<>(candidatesByHex);
      Map<HexCoord, Long> merchantView = new LinkedHashMap<>(merchantCandidatesByHex);
      Map<HexCoord, String> firstFailureView = new LinkedHashMap<>(merchantFirstFailureByHex);
      Map<String, Long> reasonsView = new LinkedHashMap<>(failuresByReason);
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "MODE_MIGRATION_CANDIDATES",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "candidateRows",
                  candidateRows,
                  "merchantCandidates",
                  merchantCandidates,
                  // ★★ W9：壳户在候选阶段被跳过的具名计数（人口 0 / 无劳动两族）——"没有"也是要读的事实。
                  "skippedShellNoPopulation",
                  shellSkippedNoPopulation,
                  "skippedShellNoLabor",
                  shellSkippedNoLabor,
                  "byHex",
                  candidatesView,
                  "merchantCandidatesByHex",
                  merchantView,
                  "merchantFirstFailureByHex",
                  firstFailureView,
                  "failuresByReason",
                  reasonsView,
                  "moves",
                  moves,
                  "movedPopulation",
                  movedPopulation));
    }
  }

  /** 本轮计划迁出的人口合计（只作轮级读数）。 */
  private static long totalDraftPopulation(Map<HouseholdId, List<MoveDraft>> draftsBySource) {
    long total = 0L;
    for (List<MoveDraft> drafts : draftsBySource.values()) {
      for (MoveDraft draft : drafts) {
        total = Math.addExact(total, draft.population);
      }
    }
    return total;
  }
}
