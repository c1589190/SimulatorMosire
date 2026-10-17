package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>R4-E2b：一次方案采用评估的结果（进程内审计行）</b>——"这一格、这一户、这个方案"在某一个日结算日要么被采用、 要么被具名拒绝。
 *
 * <p>★★ <b>为什么需要它</b>：进入算法只写 {@code relations}/{@code assetShares}/{@code allocations}
 * 等状态组件，从终态反查不到"哪些户被评估过、 为什么没进"。 没有这份审计，读口只能看到"有没有新 unit"，看不到"资产不足 / 劳动不足 / 投入不足 / 需求窗口太短 /
 * 无价"这些具名原因 ⇒ 无法回答计划要求的"无可行方案 ⇒ 需求维持未满足"。故本类型只服务读口归因，<b>不落盘、不进 {@code
 * EconomyData}/ChangeSet/Codec</b>。
 *
 * <p>★★ <b>B2c（裁定八）：候选面退役 ⇒ 本记录按<b>方案定义</b>重键</b>：
 *
 * <ul>
 *   <li>{@code candidateId} + {@code version} → {@link #plan()}（稳定 {@code IndustryId}）—— 版本键已删；
 *   <li>{@code modeKey} 不再单独存：新口径下 {@code modeKey} <b>就是方案 id 字面</b>（唯一写入点是 {@code
 *       EconomyEntrySettlement} 的 {@code createInstance} 请求），本记录不抄第二份；读口如需展示该列， 按 {@code
 *       plan.value()} 映射；
 *   <li>{@code industryId} 仍是"本格物化定义 id"（{@code <planId>@<q>_<r>}，{@code IndustryHexKeys} 的唯一拼写点）。
 * </ul>
 *
 * <p>★ <b>字段口径</b>：{@code day} = 评估发生的日结算日；{@code accepted} 若真则 {@code reason} 以 {@code entered:}
 * 开头； 若假则 {@code reason} 是具名拒绝码（可带少量数字，<b>不得携带大对象</b>）。{@code trialScale/expectedDay/laborMilli}
 * 在拒绝时为 0/0/0 （没有可行规模可报）。{@code inputPlan} 是"一周期计划投入（毫单位/规模已乘 trialScale）"，只读快照。
 *
 * @param day 日结算日（绝对 world day；≥ 1）
 * @param hexKey 家户/方案所在格的键（{@code <q>_<r>}；读口按它过滤）
 * @param household 被评估的家户
 * @param plan 被采用的方案稳定 id（不绑地点；{@code modeKey} 即它的字面）
 * @param industryId 若采用会用的本格物化定义 id（{@code <planId>@<q>_<r>}）
 * @param accepted 是否被采用（建 unit）
 * @param reason 具名结果（{@code entered:...} 或拒绝码）
 * @param trialScale 采用时的试产规模（单位规模）；拒绝时 0
 * @param expectedDay 采用时预计首次关账产生出的日子（= 进入日 + cycleDays − 1：进入当天记第 1 天）；拒绝时 0
 * @param inputPlan 采用时的一周期计划投入（商品 → 毫单位）；保序不可变
 * @param laborMilli 采用时新增的劳动配额（千分劳动·日）；拒绝时 0
 */
public record EntryOutcome(
    long day,
    String hexKey,
    HouseholdId household,
    IndustryId plan,
    IndustryId industryId,
    boolean accepted,
    String reason,
    long trialScale,
    long expectedDay,
    Map<String, Long> inputPlan,
    long laborMilli) {

  public EntryOutcome {
    if (day < 1L) {
      throw new IllegalArgumentException("EntryOutcome.day 必须 ≥ 1（创世是第 0 天）: " + day);
    }
    if (hexKey == null || hexKey.isBlank()) {
      throw new IllegalArgumentException("EntryOutcome.hexKey 不得为空白");
    }
    Objects.requireNonNull(household, "EntryOutcome.household 不得为 null");
    Objects.requireNonNull(plan, "EntryOutcome.plan 不得为 null");
    Objects.requireNonNull(industryId, "EntryOutcome.industryId 不得为 null");
    if (reason == null) {
      throw new IllegalArgumentException("EntryOutcome.reason 不得为 null（没有原因请给具名码）");
    }
    if (trialScale < 0L || expectedDay < 0L || laborMilli < 0L) {
      throw new IllegalArgumentException(
          "EntryOutcome 的 trialScale/expectedDay/laborMilli 不得为负: "
              + trialScale
              + "/"
              + expectedDay
              + "/"
              + laborMilli);
    }
    // ★ 保序不可变（绝不用 Map.copyOf —— 迭代序不是内容的纯函数）。
    Map<String, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : inputPlan.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("EntryOutcome.inputPlan 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "EntryOutcome.inputPlan 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    inputPlan = Collections.unmodifiableMap(copy);
  }
}
