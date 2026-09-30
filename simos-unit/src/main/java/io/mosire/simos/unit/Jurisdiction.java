package io.mosire.simos.unit;

import io.mosire.simos.map.region.RegionId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单位侧的管辖（辖区阶段 5，2026-09-30 裁定）：{@code Unit} 挂载的富结构，表示"这个单位管哪些 Region、每区域长期税率、
 * 一次性抽取单命令上限、行政能力（已退役）"。
 *
 * <p>★ <b>职责边界</b>：空间事实（哪些 hex 属于哪个 Region）归 {@code simos-map} 的 {@code Region}；这里只记"谁管这片 +
 * 用什么政策管"。{@code taxRatePerMilleByRegion} 的 <b>key 集就是管辖区域集</b>，空 map = 无管辖——不把管辖权写回 Region 的
 * tag（用户裁定：管辖挂在 Unit 上）。
 *
 * <p>★★ <b>2026-09-30 口径（阶段 6，计划 §6.0-1）</b>：三个 {@code levy*CapPerCommand} 是 <b>一条</b>抽取命令 （{@code
 * simos.unit.levyRegion}）的上限，<b>0 = 该类无额度、拒</b>。★ <b>周期累计额度账本本批不建</b>（具名：本结构不含"每周期已抽多少"
 * 的计数器，也不在周期边界重置任何东西）；字段名改叫 {@code CapPerCommand} 就是为了不让名字撒谎，逐周期累计留待测试阶段后按需再裁。
 *
 * <p>★ <b>保序不可变</b>：{@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结（与 {@code
 * Unit.equipment} / {@code GameMap} 同形制）；<b>不用</b> {@code Map.copyOf}——它会打乱插入序，而税率表的序是给
 * 展示/结算迭代用的稳定序。
 *
 * <p>★ <b>构造期校验</b>：map/键/值非 null；每个税率 ∈ {@code [0,1000]}；三个 {@code levy*CapPerCommand} ≥ 0；{@code
 * administrationPerMille ∈ [0,1000]}。负值与越界一律当场抛 {@link IllegalArgumentException}，不静默钳制。
 *
 * <p>★★ <b>{@code administrationPerMille} 已退役：生产路径零读取</b>（阶段 11b，计划 §2.5；用户裁定 7/8）。长期税的行政效率改由
 * {@code simos-gov} 的每 tick 读数（{@code GovOfficeState.efficiencyPerMille}）提供；本字段只为 <b>旧档兼容</b> 保留
 * ——旧 JSON 里仍有它时照常读入、构造期照常校验，但没有任何生产代码再读它来决定征不征税。无 {@code GovFormation}/无 GOV 读数 ⇒
 * 该单位整单位跳过、不征（不是回退到本字段的旧值）。
 *
 * <p>★ 本类型<b>只依赖 {@code RegionId}</b>（unit 已依赖 map），不引 app/core/economy。
 *
 * @param taxRatePerMilleByRegion key 集 = 管辖区域；value = 每周期长期税率（‰）；空 map = 无管辖
 * @param levyGrainCapPerCommand 一次性抽取：粮/次（**一条抽取命令**的上限；0 = 该类无额度、拒）
 * @param levyMoneyCapPerCommand 一次性抽取：钱/次（**一条抽取命令**的上限；0 = 该类无额度、拒）
 * @param levyManpowerCapPerCommand 一次性抽取：人/次（组军用；**一条抽取命令**的上限；0 = 该类无额度、拒）
 * @param administrationPerMille <b>已退役：生产路径零读取</b>；仅为旧档兼容保留（旧字段的范围校验照旧 {@code [0,1000]}）
 */
public record Jurisdiction(
    Map<RegionId, Long> taxRatePerMilleByRegion,
    long levyGrainCapPerCommand,
    long levyMoneyCapPerCommand,
    long levyManpowerCapPerCommand,
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
    requireNonNegative(levyGrainCapPerCommand, "levyGrainCapPerCommand");
    requireNonNegative(levyMoneyCapPerCommand, "levyMoneyCapPerCommand");
    requireNonNegative(levyManpowerCapPerCommand, "levyManpowerCapPerCommand");
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
