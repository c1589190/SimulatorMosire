package io.mosire.simos.production.contract;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>本 tick 的生产生效条件</b>——D-032 A 批「同 tick 统一生效条件」的落形
 * （`2026-10-17-d032-stage2-handoff-review-decision.md` §2.1；设计书 §13.2 第 1 条① / §13.7 第 1 条； ★ R5
 * 起冻结<b>全部</b>影响当 tick 生产公式的经验输入）。
 *
 * <p>★★ <b>裁决原文要解决的问题</b>：「同 tick 的生产效果取同一时点的生效条件，新增经验统一计入该 tick 结果；<b>不能因实例处理先后改变另一实例本 tick
 * 的熟练度</b>。」旧形态里"先执行的实例先涨经验 ⇒ 后执行的 实例看到更高的熟练度"就是遍历顺序决定效果。
 *
 * <p>★★ <b>本记录怎么消除它</b>：条件在 <b>tick 起点冻结一次</b>（经验按明确日序衰减完之后），此后 本 tick 的每一次 {@code probe}/{@code
 * execute} 都读<b>这一份</b>快照制的熟练度与原始经验，而不是当刻候选态里 已被本 tick 前面几次执行改过的经验账。⇒ 逐值等价于"全部实例并行、都取 tick
 * 起点的经验输入"， 与实例处理顺序无关（测试判据：同户多实例调整遍历次序无额外熟练度收益）。
 *
 * <p>★★ <b>冻结的是「全部影响当 tick 生产公式的输入」</b>（R5 / §15.5）：熟练度 a/b（{@link #masteryPerMille}）<b>加上</b>
 * 主经验原始值（{@link #experience}）。{@code ProductionExperienceUse.PRIMARY_EXPERIENCE_INPUT} 是合法公式输入，
 * 它若仍读当刻候选态， "同 tick 先执行的实例涨经验"就会改变后执行实例本 tick 的效率倍率——那是与熟练度同类的顺序依赖。
 *
 * <p>★★ <b>它与"当前候选态"是两件事</b>（裁决同条末句）：候选态（{@code ProductionRuntime.experienceStateOf}
 * 等读口）<b>可以</b>包含本 tick 已累计的新经验，供迁移/后续阶段读取；本记录是<b>本 tick 生效条件</b>本身。 二者都必须存在，不得用一个代替另一个。
 *
 * <p>★ <b>内容</b>：只装"本 tick 生效"的只读事实 —— tick 号与逐 (家户 × 经验条) 的熟练度/原始经验。 <b>不</b>装"本 tick
 * 涨了多少经验"（那是结果，不是条件）。
 *
 * @param tick 世界 tick（由既有调度/引擎上下文给出；不得为负）
 * @param masteryPerMille 逐 (家户 × 经验条) 的当 tick 生效熟练度（千分制）；不得为 null。 缺键 = 该家户在该经验条上熟练度
 *     0（"还没这门技术"，不是错误）——不在这里补零行
 * @param experience 逐 (家户 × 经验条) 的当 tick 生效<b>原始经验值</b>；不得为 null。 缺键 = 该家户在该经验条上经验 0——同样不在这里补零行
 */
public record ProductionTickCondition(
    long tick,
    Map<HouseholdExperienceKey, Long> masteryPerMille,
    Map<HouseholdExperienceKey, Long> experience) {

  public ProductionTickCondition {
    if (tick < 0L) {
      throw new IllegalArgumentException("ProductionTickCondition.tick 不得为负: " + tick);
    }
    if (masteryPerMille == null) {
      throw new IllegalArgumentException(
          "ProductionTickCondition.masteryPerMille 不得为 null（无条件请给空表）");
    }
    // ★ 保序不可变 + 逐值非负（绝不用 Map.copyOf —— 迭代序不是内容的纯函数）。
    Map<HouseholdExperienceKey, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdExperienceKey, Long> entry : masteryPerMille.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionTickCondition.masteryPerMille 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionTickCondition.masteryPerMille 不得为负："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    masteryPerMille = Collections.unmodifiableMap(copy); // ★ 冻在赋值处

    if (experience == null) {
      throw new IllegalArgumentException("ProductionTickCondition.experience 不得为 null（无条件请给空表）");
    }
    // ★ 同一形制：保序不可变 + 键/值非 null + 值非负。
    Map<HouseholdExperienceKey, Long> experienceCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdExperienceKey, Long> entry : experience.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionTickCondition.experience 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionTickCondition.experience 不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      experienceCopy.put(entry.getKey(), entry.getValue());
    }
    experience = Collections.unmodifiableMap(experienceCopy); // ★ 冻在赋值处
  }

  /** ★ <b>本条件是否恒中性</b>（没有任何非零熟练度）——"纯劳动世界"的合法形态；也是"经验条尚未播种"的 读数判据（那种世界不该被当成故障）。 */
  public boolean isNeutral() {
    for (Long value : masteryPerMille.values()) {
      if (value != 0L) {
        return false;
      }
    }
    return true;
  }

  /** ★ 该家户在该经验条上的当 tick 生效熟练度（缺键 ⇒ 0）。 */
  public long masteryOf(HouseholdId household, ExperienceTrackId track) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(track, "track");
    return masteryPerMille.getOrDefault(new HouseholdExperienceKey(household, track), 0L);
  }

  /**
   * ★★ 该家户在该经验条上的当 tick 生效<b>原始经验值</b>（缺键 ⇒ 0）——R5 / §15.5：它是公式输入 {@code primary_experience}
   * 的冻结来源，{@code execute} 的效率倍率<b>不得</b>再读当刻候选态。
   */
  public long experienceOf(HouseholdId household, ExperienceTrackId track) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(track, "track");
    return experience.getOrDefault(new HouseholdExperienceKey(household, track), 0L);
  }

  /** ★ 本条件与方案无关的通用部分是否可被另一 tick 复用（只判号；供"重复衰减/跨 tick 混用"检测读）。 */
  public boolean isFor(long otherTick) {
    return tick == otherTick;
  }

  /** ★ 面向日志/审计的摘要（不含全表：表可能很大）。 */
  public String summary() {
    return "tick="
        + tick
        + " masteryRows="
        + masteryPerMille.size()
        + " experienceRows="
        + experience.size()
        + " neutral="
        + isNeutral();
  }
}
