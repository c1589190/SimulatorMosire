package io.mosire.simos.production;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.production.contract.ExperienceTrack;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.HouseholdExperienceKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionExperienceFeature;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ★★ <b>家户生产经验的增长 / 衰减 / 解锁（production 内部，非契约）</b>（D-032 约束设计书 §4.1、I7、I9； 用户 U2/U4/U10；★ A 批按复核裁决
 * §4 修精度）。
 *
 * <p>★★ <b>四条口径的唯一拼写点</b>：
 *
 * <ol>
 *   <li><b>只按实际生产贡献累计</b>（I9）：增长量按<b>该家户本次实际投入的劳动</b>换算 （{@link
 *       ExperienceTrack#growthPerLaborMilli()}），不按"参与过的方案数"、不按"出过工"就发全额；
 *   <li>★★ <b>足够精度累计并结转余数</b>（A 批；复核裁决 §4）：见 {@link #accrueFrom}——旧式 {@code floor(labor×g÷1000)}
 *       <b>逐次截断、不结转</b>会让"增长率 1、每次 100 毫劳动"永远 0； 现在把<b>未截断的分数部分</b>（{@link
 *       ProductionExperienceFeature#remainder()}， ∈ [0,1000)）写回特征，下一 tick 继续参与 ⇒ 长期不再系统性丢精度；
 *   <li>★★ <b>A′ 批：先按 家户×经验条 归并、再一次性换算</b>（§13.8 末段 / §13.7 第 1 条 / 复核 §2.1）： 同一 tick
 *       内同一家户可能分布在多个实例上，但增长换算只做<b>一次</b>——调用方（引擎）把本 tick 的实际贡献按 {@code (家户, 经验条)} 汇总，连同<b>本 tick
 *       起点</b>的基线特征交给 {@link #accrueFrom}。⇒ 同户同条的实际劳动只累加一次（不因分布在多个实例而重复计），
 *       归并顺序不影响结果（换算与顺序无关：整条公式只看"基线 + 汇总劳动"）；
 *   <li><b>逐 tick 衰减只降经验、不撤销解锁</b>（I7；用户 U10「肯定算数」）：{@link #decayTick} 只减主值；
 *       本类<b>没有</b>任何"撤销解锁"的方法——解锁集合只增不减（{@link #grantUnlocks} 只 add）；
 *   <li><b>上限到顶即停</b>（{@link ExperienceTrack#clampExperience}）：经验主值不得越上限，且 <b>不</b>把本该溢出的量藏进余数（见
 *       {@link #accrueFrom}）。
 * </ol>
 *
 * <p>★ <b>为什么是纯静态函数而不是一个长生命周期对象</b>：本模块没有自己的 {@code TimeParticipant} （见 {@code ProductionLogSource}
 * 的类注）；经验面的每一步都由组合根经 {@code ProductionRuntime} 驱动， 状态改写仍走"本域候选态 → 本域 ChangeSet → 同一 revision"。
 */
final class ProductionExperience {

  /** ★ 每千分劳动的增长换算分母（千分定点约定；{@link ProductionExperienceFeature#REMAINDER_MODULUS} 同源）。 */
  private static final long PER_MILLE = ProductionExperienceFeature.REMAINDER_MODULUS;

  private ProductionExperience() {}

  /**
   * ★★ <b>增长换算的精确值</b>：{@code 实际劳动(千分劳动·日) × 每千分劳动增长量}，<b>分子不截断</b>。
   *
   * <p>★ 返回的是"千分定点"的精确增量：整数部分 {@code exact / 1000}、分数部分 {@code exact % 1000}。 调用方（{@link
   * #accrueFrom}）负责把分数部分结转——<b>不</b>在这里先除一次（那就是旧的系统性丢失）。
   */
  static long exactGrowthNumerator(ExperienceTrack track, long laborMilli) {
    if (laborMilli <= 0L || track.growthPerLaborMilli() <= 0L) {
      return 0L;
    }
    return Math.multiplyExact(laborMilli, track.growthPerLaborMilli());
  }

  /**
   * ★★ <b>一条经验账的"同 tick 归并"增长写回（A′ 批：先按 家户×经验条 汇总实际贡献，再一次性换算）</b>。
   *
   * <p>★ <b>算法</b>（{@code tickBaseline} = 本 tick 起点、即衰减后的那条特征；{@code aggregatedLaborMilli} = 本 tick
   * 到目前为止该 (家户, 经验条) 上汇总到的实际贡献）：
   *
   * <pre>
   * exact    = 汇总劳动 × growthPerLaborMilli        // 不截断（整条公式只看基线 + 汇总劳动）
   * whole    = exact / 1000  +  tickBaseline.remainder / 1000 的进位
   * numerator= exact + tickBaseline.remainder
   * next     = min(tickBaseline.value + numerator/1000, maxExperience)
   * 余数      = numerator % 1000                       // 结转进特征，下一 tick 继续参与
   * </pre>
   *
   * <p>★★ <b>为什么是"基线 + 汇总劳动"而不是"逐实例各算一次"</b>：两者在数值上等价（滚动余数的 总和 = 一次汇总），但前者的<b>归并单位就是
   * 家户×经验条</b>——同户同条分布在多个实例上的实际劳动 只被加一次，且结果与实例的遍历顺序<b>按构造</b>无关（§13.7 第 1 条判据"调整遍历次序无额外熟练度
   * 收益"因此是结构性的，不是巧合）。
   *
   * <p>★★ <b>到上限时的边界（复核裁决 §4 第 2 条"不能藏出超过上限的经验"）</b>：主值夹到上限后， {@code 退还量}
   * 对应的<b>整数</b>部分不再保留（不塞进余数）；余数只记<b>本次汇总贡献自己的分数部分</b>。 ⇒
   * 状态里永远没有"超过上限的经验"，也没有"被拒的旧零头"。主值因衰减离开上限后，只有<b>新的</b>实际 贡献（+新余数）能继续增长。
   *
   * <p>★ <b>经验条不存在 ⇒ 原样返回</b>（不猜、不建条）；★ 汇总劳动 ≤ 0 或增长换算为 0 ⇒ 原样返回 （没有要写的字节，不产生假变更集行）。
   *
   * @param state 当刻本域候选态
   * @param key 家户 × 经验条（归并键）；不得为 null
   * @param tickBaseline 本 tick 起点（衰减后）的那条特征；不得为 null
   * @param aggregatedLaborMilli 本 tick 已汇总的实际贡献（千分劳动·日）；不得为负
   */
  static ProductionData accrueFrom(
      ProductionData state,
      HouseholdExperienceKey key,
      ProductionExperienceFeature tickBaseline,
      long aggregatedLaborMilli) {
    if (key == null || tickBaseline == null) {
      throw new IllegalArgumentException("ProductionExperience.accrueFrom 的键与基线不得为 null");
    }
    if (aggregatedLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionExperience.accrueFrom 的汇总劳动不得为负: " + aggregatedLaborMilli);
    }
    ExperienceTrack definition = state.experienceTracks().get(key.track());
    if (definition == null) {
      return state;
    }
    long exact = exactGrowthNumerator(definition, aggregatedLaborMilli);
    if (exact <= 0L) {
      return state;
    }
    long numerator = Math.addExact(exact, tickBaseline.remainder());
    long whole = numerator / PER_MILLE;
    long nextRemainder = numerator % PER_MILLE;
    long grown = Math.addExact(tickBaseline.value(), whole);
    long clamped = definition.clampExperience(grown);
    ProductionExperienceFeature next = new ProductionExperienceFeature(clamped, nextRemainder);
    if (next.equals(state.experienceFeatureOf(key.household(), key.track()))) {
      return state;
    }
    return state.withHouseholdExperience(key.household(), key.track(), next);
  }

  /**
   * ★★ <b>逐 tick 衰减</b>（用户 U2 原文「随着 tick 增加，所有经验的数值积累都会少量变少」）： 对<b>全部</b>家户经验行按各自经验条的 {@code
   * decayPerTick} 减，夹到 0。
   *
   * <p>★ <b>不撤销解锁</b>（I7）：本方法只改 {@code householdExperience}；{@code unlocks} 一个字节都不动。 ★
   * <b>余数原样保留</b>（复核裁决 §4 第 2 条）：它记的是"已发生的实际劳动的分数部分"，不是"旧零头"， 故归零时不清它、也不重新授予已进主值的整数部分。 ★ 减到 0
   * 的行<b>保留</b>（不是删行）：0 与"没有这一行"逐值等价，但保留行让"这个家户曾经做过这门" 在读数上可见，且避免每天产生一堆"删行/建行"的假变更集。
   */
  static ProductionData decayTick(ProductionData state) {
    if (state.householdExperience().isEmpty()) {
      return state;
    }
    var next = new java.util.LinkedHashMap<>(state.householdExperience());
    boolean changed = false;
    for (var entry : state.householdExperience().entrySet()) {
      ExperienceTrackId trackId = entry.getKey().track();
      ExperienceTrack definition = state.experienceTracks().get(trackId);
      if (definition == null || definition.decayPerTick() <= 0L) {
        continue;
      }
      ProductionExperienceFeature feature = entry.getValue();
      long decayed = Math.max(0L, feature.value() - definition.decayPerTick());
      if (decayed != feature.value()) {
        // ★ 余数原样带过（衰减只作用于主值）。
        next.put(entry.getKey(), new ProductionExperienceFeature(decayed, feature.remainder()));
        changed = true;
      }
    }
    return changed ? state.withHouseholdExperience(next) : state;
  }

  /**
   * ★★ <b>按门槛授予解锁</b>（只加不减；I7）：遍历本模块<b>全部方案</b>，把"该家户已够门槛、且尚未解锁"的 方案加进 {@code unlocks}。
   *
   * <p>★★ <b>A 批：授予时机是"达到门槛的那一个 tick"</b>（复核裁决 §2 第 7 项 + §5 第 5 条）：本方法由
   * 引擎在<b>每个生产日</b>（每一次实际贡献之后）调用，<b>不</b>等关账日；衰减后资格仍保留（只加不减）。
   *
   * <p>★ 不要求解锁的方案（门槛 0）<b>不进</b> {@code unlocks}——"一开始就能用"与"曾经解锁过"是两件事， 混在一起会让读数（"这个户解锁了什么"）失真。
   *
   * @return 本次新授予的方案集合（保序；空集 = 没有新解锁）
   */
  static Set<IndustryId> grantUnlocks(ProductionData state, HouseholdId household) {
    Set<IndustryId> already = state.unlocksOf(household);
    Set<IndustryId> newly = new LinkedHashSet<>();
    for (IndustryDefinitionView definition : state.industries().values()) {
      if (!definition.experience().requiresUnlock()) {
        continue;
      }
      IndustryId plan = definition.id();
      if (already.contains(plan)) {
        continue;
      }
      ExperienceTrackId primary = definition.experience().primaryTrack().orElse(null);
      if (primary == null) {
        continue; // 构造期守卫已禁止"有门槛却没专门经验条"，这里只是防御性跳过
      }
      long experience = state.experienceOf(household, primary);
      if (experience >= definition.experience().unlockAtPrimaryExperience()) {
        newly.add(plan);
      }
    }
    return newly;
  }

  /** ★ 把 {@link #grantUnlocks} 的结果写回状态（幂等；无新解锁 ⇒ 原样返回）。 */
  static ProductionData applyUnlocks(
      ProductionData state, HouseholdId household, Set<IndustryId> plans) {
    ProductionData next = state;
    for (IndustryId plan : plans) {
      next = next.withUnlock(household, plan);
    }
    return next;
  }
}
