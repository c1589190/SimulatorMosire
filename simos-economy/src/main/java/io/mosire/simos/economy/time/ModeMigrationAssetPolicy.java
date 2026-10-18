package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** P0.5-E1 机械拆分自 {@code ModeMigrationPolicy}；职责见类名与各方法注释。 */
final class ModeMigrationAssetPolicy {

  private ModeMigrationAssetPolicy() {}

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
      IndustryDefinitionView industry, long laborMilliPlan, Map<AssetKind, Long> carriedByKind) {
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
  static long ceilDiv(long numerator, long denominator) {
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
   * 商品腿 = 商品账里的工具（{@link MerchantTradeTemplate#TOOL_COMMODITY}）⇒ 计入 {@code AssetKind.SMALL_TOOL}
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
      carried.merge(AssetKind.SMALL_TOOL, toolCommodity, Math::addExact);
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
   * ★★★ <b>W7（2026-10-11）/ W7-fix（2026-10-11）：这一批人**随迁携带**的**作业资本**量</b> —— 判据与执行期 {@code
   * ModeMigrationSettlement.migrateAssetsForMove} 的取整**逐式对齐**：
   *
   * <pre>
   * 取整 = 整户迁空（take ≥ 源户人口）        ⇒ 全部数量
   *        否则                             ⇒ ⌊数量 × take ÷ 源户人口⌋   （与执行期同一式、同一 floor）
   * 范围 = owner == 源户 actor ∧ asset ∈ {模板的 capacityPerUnit 那一维} ∧ quantity &gt; 0
   * </pre>
   *
   * <p>★★ <b>W7-fix（用户裁定 v3.28 §7）</b>：那一维从 {@code CATTLE} 换成 {@code SMALL_TOOL} —— 与 {@link
   * MerchantTradeTemplate#industry(HexCoord)} 的 {@code capacityPerUnit} 键**同源**（不是第二个拼写点： 本方法直接从模板的
   * {@code capacityPerUnit.keySet()} 取，模板改哪一维这里跟着改）。
   *
   * <p>★ 它是"**不凭空造资产**"在决策层的落点：按需创建的 {@code trade@H} 只承载这一份真实存在的作业资本。 ★ 执行期还有一条"已被别的组织实际使用 ⇒
   * 留原户并具名"的排除（{@code foreignUsedOwnershipStakeIds}）—— 那种极端情形由 {@code ModeMigrationSettlement}
   * 在执行前**具名跳过该笔迁移**（不静默丢人、也不让 tick 抛）。
   */
  static Map<AssetKind, Long> carriedCarrierAssetsForMove(
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
  static long requiredWorkCapitalScale(IndustryDefinitionView industry, long laborMilli) {
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

  static boolean reserveIdleAssetsFor(
      IndustryDefinitionView industry,
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
  static boolean usedByUnit(OwnershipStake share, ProductionProcess unit) {
    Objects.requireNonNull(share, "share");
    Objects.requireNonNull(unit, "unit");
    return share.quantity() > 0L
        && share.industry().equals(unit.industry())
        && share.operator().equals(unit.operator());
  }
}
