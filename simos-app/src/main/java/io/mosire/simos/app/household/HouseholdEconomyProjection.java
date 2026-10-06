package io.mosire.simos.app.household;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>HouseholdEconomy.population 的 Social 家户投影（2026-10-09 家户结构修复）</b>。
 *
 * <pre>
 * 对每个 HouseholdEconomy：
 *     Social.householdPopulation(row.id()) → row.population
 *     （人口变化时，laborMilli 按同一比例缩放；当日的 labor 预算随后由 app 从 Social 成员重新展开）
 * </pre>
 *
 * <p>★★ <b>为什么必须是 1:1 按 HouseholdId</b>：P2-A 之后 Social 与 Economy 的家户身份已经统一为 {@code (格, 居住类型, 阶层)} 的
 * {@link HouseholdId}；Economy 的 `classes` 键就是同一个 id。把 `(格, 居住类型)`
 * 当作“一组多户”再平均，会把贫农/中农/富农/地主之间的人口差在投影里悄悄抹平—— 那是**跨阶层错账**，不是对账。
 *
 * <p>★★ <b>本类不做的事</b>：不移动人、不改 Social 成员份额、不新建/合并家户、不做任何组内分摊/平均。 它只把经济侧的人口视图拉回 Social 真值。
 *
 * <p>★ <b>fail-closed</b>：经济侧出现 Social 不存在的家户、或 Social 家户缺经济行，都进 {@code unresolved}， 调用方据此告警；**不静默按
 * 0 处理、也不做平均兜底**。
 */
public final class HouseholdEconomyProjection {

  private static final Logger LOG = LoggerFactory.getLogger(HouseholdEconomyProjection.class);

  private HouseholdEconomyProjection() {}

  /** 投影结果：{@code projected=true} 时 {@code data} 是重投影后的经济状态；否则原样返回且带 {@code unresolved} 原因。 */
  public record Result(
      EconomyData data,
      boolean projected,
      int changedRows,
      long populationDelta,
      List<String> unresolved) {

    public Result {
      Objects.requireNonNull(data, "data");
      unresolved = List.copyOf(Objects.requireNonNull(unresolved, "unresolved"));
    }
  }

  /**
   * 按 {@link HouseholdId} 1:1 把 {@code economy.classes()} 的人口投影回 Social 家户成员之和。
   *
   * <p>Social 家户为空（旧世界/未播种）⇒ 原样返回（投影无从谈起，不是坏数据）；经济侧为空同理。
   */
  public static Result project(EconomyData economy, SocialData social) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(social, "social");
    if (economy.classes().isEmpty() || social.households().isEmpty()) {
      return new Result(economy, false, 0, 0L, List.of());
    }

    List<String> unresolved = new ArrayList<>();

    // ① 逐 Social 家户算出真值人口（含零成员家户 = 0，不再因“推不出 residence”而失败）。
    Map<HouseholdId, Long> targetPopulation = new LinkedHashMap<>();
    for (HouseholdId householdId : social.households().keySet()) {
      targetPopulation.put(householdId, social.householdPopulation(householdId));
    }

    // ② 逐经济行 1:1 直接查 id；查不到 ⇒ 具名 unresolved。
    Map<HouseholdId, HouseholdEconomy> next = new LinkedHashMap<>();
    int changedRows = 0;
    long populationDelta = 0L;
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry : economy.classes().entrySet()) {
      HouseholdId householdId = entry.getKey();
      HouseholdEconomy householdEconomy = entry.getValue();
      Long target = targetPopulation.get(householdId);
      if (target == null) {
        unresolved.add("经济侧家户在 Social 里不存在（拒绝当作 0，也不做组内平均）: " + householdId);
        next.put(householdId, householdEconomy);
        continue;
      }
      long before = householdEconomy.population();
      long after = target;
      if (before == after) {
        next.put(householdId, householdEconomy);
        continue;
      }
      long laborAfter =
          before == 0L
              ? householdEconomy.laborMilli()
              : Math.multiplyExact(householdEconomy.laborMilli(), after) / before;
      next.put(householdId, householdEconomy.withPopulationAndLabor(after, laborAfter));
      changedRows++;
      populationDelta = Math.addExact(populationDelta, Math.abs(after - before));
    }

    // ③ Social 有家户但经济侧缺行 ⇒ 也是具名 unresolved（否则那户的需求没有落点）。
    for (HouseholdId householdId : targetPopulation.keySet()) {
      if (!economy.classes().containsKey(householdId)) {
        unresolved.add("Social 家户缺经济行（需求/劳动没有落点）: " + householdId);
      }
    }

    if (!unresolved.isEmpty()) {
      LOG.warn(
          "event=CLASSROW_POPULATION_PROJECTION_SKIPPED unresolved={} first={}",
          unresolved.size(),
          unresolved.get(0));
      return new Result(economy, false, 0, 0L, unresolved);
    }
    if (changedRows == 0) {
      return new Result(economy, false, 0, 0L, List.of());
    }
    EconomyData projected = economy.withHouseholdEconomies(next);
    LOG.info(
        "event=CLASSROW_POPULATION_PROJECTED households={} changedRows={} absPopulationDelta={}",
        targetPopulation.size(),
        changedRows,
        populationDelta);
    return new Result(projected, true, changedRows, populationDelta, List.of());
  }
}
