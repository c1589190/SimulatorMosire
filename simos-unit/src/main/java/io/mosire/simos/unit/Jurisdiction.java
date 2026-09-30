package io.mosire.simos.unit;

import io.mosire.simos.map.region.RegionId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单位侧的管辖（辖区阶段 5，2026-09-30 裁定）：{@code Unit} 挂载的富结构，表示"这个单位管哪些 Region、每区域长期税率、 周期性一次性抽取上限、行政能力"。
 *
 * <p>★ <b>职责边界</b>：空间事实（哪些 hex 属于哪个 Region）归 {@code simos-map} 的 {@code Region}；这里只记"谁管这片 +
 * 用什么政策管"。{@code taxRatePerMilleByRegion} 的 <b>key 集就是管辖区域集</b>，空 map = 无管辖——不把管辖权写回 Region 的
 * tag（用户裁定：管辖挂在 Unit 上）。
 *
 * <p>★ <b>保序不可变</b>：{@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结（与 {@code
 * Unit.equipment} / {@code GameMap} 同形制）；<b>不用</b> {@code Map.copyOf}——它会打乱插入序，而税率表的序是给
 * 展示/结算迭代用的稳定序。
 *
 * <p>★ <b>构造期校验</b>：map/键/值非 null；每个税率 ∈ {@code [0,1000]}；三个 {@code levy*CapPerCycle} ≥ 0；{@code
 * administrationPerMille ∈ [0,1000]}。负值与越界一律当场抛 {@link IllegalArgumentException}，不静默钳制。
 *
 * <p>★ 本类型<b>只依赖 {@code RegionId}</b>（unit 已依赖 map），不引 app/core/economy。
 *
 * @param taxRatePerMilleByRegion key 集 = 管辖区域；value = 每周期长期税率（‰）；空 map = 无管辖
 * @param levyGrainCapPerCycle 一次性抽取：粮/周期
 * @param levyMoneyCapPerCycle 一次性抽取：钱/周期
 * @param levyManpowerCapPerCycle 一次性抽取：人/周期（组军用）
 * @param administrationPerMille 行政能力：0 = 无班子（只能一次性抽），&gt;0 = 可长期税；范围 {@code [0,1000]}
 */
public record Jurisdiction(
    Map<RegionId, Long> taxRatePerMilleByRegion,
    long levyGrainCapPerCycle,
    long levyMoneyCapPerCycle,
    long levyManpowerCapPerCycle,
    long administrationPerMille) {

  public Jurisdiction {
    if (taxRatePerMilleByRegion == null) {
      throw new IllegalArgumentException("taxRatePerMilleByRegion 不得为 null（无管辖用 Map.of()）");
    }
    Map<RegionId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<RegionId, Long> entry : taxRatePerMilleByRegion.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("taxRatePerMilleByRegion 的键与值都不得为 null");
      }
      if (entry.getValue() < 0 || entry.getValue() > 1000) {
        throw new IllegalArgumentException(
            "税率必须 ∈ [0,1000]: " + entry.getKey() + "=" + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的 Collections.unmodifiableMap）。
    taxRatePerMilleByRegion = Collections.unmodifiableMap(copy);
    requireNonNegative(levyGrainCapPerCycle, "levyGrainCapPerCycle");
    requireNonNegative(levyMoneyCapPerCycle, "levyMoneyCapPerCycle");
    requireNonNegative(levyManpowerCapPerCycle, "levyManpowerCapPerCycle");
    if (administrationPerMille < 0 || administrationPerMille > 1000) {
      throw new IllegalArgumentException(
          "administrationPerMille 必须 ∈ [0,1000]: " + administrationPerMille);
    }
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " 必须 ≥ 0: " + value);
    }
  }
}
