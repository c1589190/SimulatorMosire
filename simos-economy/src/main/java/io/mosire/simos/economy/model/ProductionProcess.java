package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>生产单元：一次实际发生的生产活动</b>（R3B.2；设计稿 §4.2 + R3 决策单 §1.3）。
 *
 * <p>★★ <b>三件事从此各归各位</b>：
 *
 * <pre>
 * OwnershipStake    —— 实物总账：谁拥有、谁使用、多少（唯一的 quantity 真相）
 * Industry      —— 技术模板：怎么做、每单位要什么/产什么（不再携带 operator/进度/产能总量）
 * ProductionProcess—— 谁在实际生产：operator + 进度 + 本周期实扣投入（结算的唯一主体）
 * </pre>
 *
 * <p>★★ <b>为什么需要它</b>：旧 {@code Industry} 同时是"模板 + 实例 + 一个经营者"，同一格同一产业只能有一个经营者、一份 {@code
 * progressDays}/{@code cycleInputUsedMilli} ⇒ 庄园自营与佃耕无法各记各的账。本记录把"实际生产活动"独立出来：同一 {@code industry}
 * 下可以有多个 unit（不同 operator 或同一 operator 的多段活动），投入/产出/关系分账都落在它身上。
 *
 * <p>★★ <b>量纲与口径</b>：{@code progressDays} = 本周期已过天数（∈ [0, industry.cycleDays]，上界由 {@code
 * EconomyData} 的跨表守卫判 —— 本类型看不见产业模板）；{@code cycleLaborMilli} = 本周期累计实际投入劳动（千分劳动·日）； {@code
 * cycleInputUsedMilli} = 本周期实际扣到的投入（毫单位，按商品）。
 *
 * <p>★ <b>规模不在这里</b>：unit 的可用实物资产由 {@link ProductionProcessBook#usableAssets(ProductionProcess, Map)} 从
 * {@code OwnershipStake} <b>纯派生</b>，本记录不存第二份（存了就会与总账漂开）。
 *
 * <p>★ <b>命名</b>：{@code modeKey} 是"这次生产按哪种方式做"的稳定键（E3 的经验/学习读物）—— 旧档 = {@code
 * industry.id().value()}，将来的候选预设 = {@code candidateId+"@"+version}。本批只保证它在 unit 上稳定存在。
 *
 * @param id 稳定身份；新生成走 {@link ProductionUnitId#idOf(IndustryId, ActorRef)}，旧档 id 原样 opaque 读
 * @param industry 技术模板身份；不得为 null（unit 的地点取自它 id 里的格键）
 * @param operator 实际经营者；投入/产出/关系/账户都归它；不得为 null
 * @param modeKey 生产方式键；不得为空白；旧档 = {@code industry.id().value()}
 * @param progressDays 本周期已过天数；不得为负（上界跨表判）
 * @param cycleLaborMilli 本周期累计实际投入劳动（千分劳动·日）；不得为负
 * @param cycleInputUsedMilli 本周期实际扣到的投入（毫单位，按商品）；不得为 null、逐值不得为负
 */
public record ProductionProcess(
    ProductionUnitId id,
    IndustryId industry,
    ActorRef operator,
    String modeKey,
    long progressDays,
    long cycleLaborMilli,
    Map<CommodityId, Long> cycleInputUsedMilli) {

  public ProductionProcess {
    Objects.requireNonNull(id, "ProductionProcess.id 不得为 null");
    Objects.requireNonNull(industry, "ProductionProcess.industry 不得为 null");
    Objects.requireNonNull(operator, "ProductionProcess.operator 不得为 null");
    if (modeKey == null || modeKey.isBlank()) {
      throw new IllegalArgumentException("ProductionProcess.modeKey 不得为空白");
    }
    if (progressDays < 0L) {
      throw new IllegalArgumentException("ProductionProcess.progressDays 不得为负: " + progressDays);
    }
    if (cycleLaborMilli < 0L) {
      throw new IllegalArgumentException("ProductionProcess.cycleLaborMilli 不得为负: " + cycleLaborMilli);
    }
    if (cycleInputUsedMilli == null) {
      throw new IllegalArgumentException("ProductionProcess.cycleInputUsedMilli 不得为 null（未投入用空表）");
    }
    // ★ 保序不可变 + 逐值非负：LinkedHashMap + unmodifiableMap（绝不用 Map.copyOf —— 迭代序不是内容的纯函数）。
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : cycleInputUsedMilli.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionProcess.cycleInputUsedMilli 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionProcess.cycleInputUsedMilli 不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    cycleInputUsedMilli = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /** 换本周期状态（进度 / 累计劳动 / 累计投入），身份与经营者逐字带过 —— 日结算的唯一写回形制。 */
  public ProductionProcess withCycleState(
      long nextProgressDays, long nextCycleLaborMilli, Map<CommodityId, Long> nextInputUsedMilli) {
    return new ProductionProcess(
        id, industry, operator, modeKey, nextProgressDays, nextCycleLaborMilli, nextInputUsedMilli);
  }
}
