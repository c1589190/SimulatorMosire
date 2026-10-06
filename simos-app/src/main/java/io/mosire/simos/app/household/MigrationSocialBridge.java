package io.mosire.simos.app.household;

import io.mosire.simos.economy.time.EconomyPopulationTransfer;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.workorder.HouseholdWorkOrder;
import io.mosire.simos.social.workorder.HouseholdWorkOrderBook;
import io.mosire.simos.social.workorder.HouseholdWorkOrderPlan;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>P0（2026-10-10）：迁移 outbox → Social 工单的桥</b>（住在 {@code simos-app}：唯一同时认识 economy 与 social
 * 的组合根）。
 *
 * <p>App 的 {@code PopulationEconomyTimeParticipant} 在 {@code EconomyDayStepper.step(day)} 之后拿到
 * {@link EconomyPopulationTransfer} 的有序 outbox，逐条翻译成 Social 工单并调用 {@link
 * HouseholdWorkOrderBook#apply(SocialData, HouseholdWorkOrder, long)}：
 *
 * <pre>
 * 逐条 transfer（保持 outbox 顺序）：
 *   ① 目标 Social 家户缺失 ⇒ CREATE_HOUSEHOLD（位置 = HEX(targetHex)，profile 由 mode 迁移命名，
 *      vitalRates 空表 ⇒ 走全局默认率）；已存在 ⇒ 只加 TRANSFER_MEMBERS（合并）
 *   ② 从当前源户成员份额（PeopleLotId.value() 升序）用 ProportionalSplit.byDenominator(population, weights,
 *      sourceHouseholdPopulation) 确定性选出恰好 population 人；对每个 take &gt; 0 的批次追加一条 TRANSFER_MEMBERS
 *   ③ orderId = {@code mode-migration:<day>:<序号>:<source>:<target>}（序号从 1 起，按 outbox 顺序）——
 *      由 HouseholdWorkOrderBook 的 {@code work-order:} 标记事件挡重复提交（跨 revision/重启幂等）
 *   ④ 任一步失败（源户人口不足/批次份额不足/Social 守恒失败/目标 id 冲突）⇒ 整单具名
 *      {@link IllegalStateException}，不部分回滚；整次 advance 失败、不落 revision
 * </pre>
 *
 * <p>★★ <b>为什么是"整单具名抛"而不是逐户 try/catch</b>：人口权威只有 Social 一份；经济腿已经把资产/钱/债按
 * "这笔搬多少人"落好了，任何一条人的转移失败都意味着两条腿要一起回滚 —— 具名失败让整条 advance 红色拒绝， 而不是在部分家户上留下错账。
 *
 * <p>★ <b>日志归属</b>：本类不另开 logger —— Social 腿的工单日志（受理/逐操作/拒绝/幂等命中）全部由 {@link HouseholdWorkOrderBook}
 * 记在 {@code SocialLog.workOrder()}，App 只记跨域汇总 {@code MODE_MIGRATION_BRIDGED}（见 {@code
 * PopulationEconomyTimeParticipant}）。
 *
 * <p>★ <b>纯函数</b>：不写任何外部状态；同一输入（base + transfers + day）⇒ 同一输出。
 */
public final class MigrationSocialBridge {

  /** 工单来源描述（进 Social 审计/幂等标记事件）。 */
  private static final String SOURCE = "MODE_MIGRATION";

  /** profile description（进 Social 家户画像，可审计："这个家户是模式迁移迁入的"）。 */
  private static final String PROFILE_DESCRIPTION = "mode-migration";

  private MigrationSocialBridge() {}

  /**
   * 把一批经济腿迁移事实翻译成 Social 工单并顺序受理。
   *
   * @param base 受理基准（命令当前 revision 的 social 切片）
   * @param transfers 经济腿 outbox（保序 = 经济 move 执行序；非 null，元素非 null）
   * @param day 世界当前日（进 orderId 与标记事件）
   * @return 受理全部工单后的新 {@link SocialData}（base 从不被改）
   * @throws IllegalStateException 任一工单失败（含源户人口不足 / 批次份额不足 / Social 守恒失败 / 目标冲突）； 具名带
   *     source/target/orderId 上下文
   */
  public static SocialData apply(
      SocialData base, List<EconomyPopulationTransfer> transfers, long day) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(transfers, "transfers");
    if (day < 0L) {
      throw new IllegalArgumentException("MigrationSocialBridge.apply 的 day 不得为负: " + day);
    }
    SocialData current = base;
    int index = 0;
    for (EconomyPopulationTransfer transfer : transfers) {
      index++;
      current = applyOne(current, Objects.requireNonNull(transfer, "transfer"), day, index);
    }
    return current;
  }

  /** 单条 transfer：形状校验 → 选批次 → 组装工单 → 受理。 */
  private static SocialData applyOne(
      SocialData current, EconomyPopulationTransfer transfer, long day, int index) {
    HouseholdId source = transfer.source();
    HouseholdId target = transfer.target();
    String orderId =
        "mode-migration:" + day + ":" + index + ":" + source.value() + ":" + target.value();
    try {
      if (source.equals(target)) {
        throw new IllegalStateException("迁移 outbox 的 source == target（Social 侧无法表达自转移）: " + source);
      }
      Household sourceHousehold = current.households().get(source);
      if (sourceHousehold == null) {
        throw new IllegalStateException(
            "迁移源 Social 家户不存在（拒绝静默丢人）: order=" + orderId + " source=" + source);
      }
      long sourcePopulation = current.householdPopulation(source);
      if (sourcePopulation < transfer.population()) {
        throw new IllegalStateException(
            "迁移源 Social 家户人口不足（拒绝抽成负人口）: order="
                + orderId
                + " source="
                + source
                + " socialPopulation="
                + sourcePopulation
                + " transfer="
                + transfer.population());
      }

      List<HouseholdWorkOrderPlan.Step> steps = new ArrayList<>();
      // ① 缺失目标 ⇒ CREATE_HOUSEHOLD（仅此一步创建；已存在 ⇒ 合并，不覆盖位置/画像）。
      if (!current.households().containsKey(target)) {
        steps.add(
            new HouseholdWorkOrderPlan.CreateHousehold(
                target,
                new HouseholdLocation.Hex(transfer.targetHex()),
                new HouseholdProfile(
                    "迁入户:" + target.value(), PROFILE_DESCRIPTION, Map.of("source", SOURCE)),
                new HouseholdVitalRates(List.of())));
      }
      // ② 按成员份额确定性选人（今序 + 最大余数法）：权重 = 该批次份额，分母 = 源户当前总人口。
      List<PeopleLotId> lots = new ArrayList<>(sourceHousehold.members().keySet());
      lots.sort(Comparator.comparing(PeopleLotId::value));
      long[] weights = new long[lots.size()];
      for (int i = 0; i < lots.size(); i++) {
        weights[i] = sourceHousehold.memberCount(lots.get(i));
      }
      long[] takes =
          ProportionalSplit.byDenominator(transfer.population(), weights, sourcePopulation);
      long selected = 0L;
      for (int i = 0; i < takes.length; i++) {
        if (takes[i] > 0L) {
          steps.add(
              new HouseholdWorkOrderPlan.TransferMembers(source, target, lots.get(i), takes[i]));
          selected = Math.addExact(selected, takes[i]);
        }
      }
      if (selected != transfer.population()) {
        throw new IllegalStateException(
            "迁移批次分派不守恒（Σ take != 迁移人口）: order="
                + orderId
                + " selected="
                + selected
                + " transfer="
                + transfer.population());
      }
      String reason =
          transfer.reason() == null || transfer.reason().isBlank() ? SOURCE : transfer.reason();
      HouseholdWorkOrder order =
          new HouseholdWorkOrder(
              orderId, target, reason, SOURCE, new HouseholdWorkOrderPlan(steps));
      return HouseholdWorkOrderBook.apply(current, order, day);
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException(
          "迁移 Social 工单失败（整批中止，不部分回滚）: order="
              + orderId
              + " source="
              + source
              + " target="
              + target
              + " transferPopulation="
              + transfer.population()
              + " error="
              + failure.getMessage(),
          failure);
    }
  }
}
