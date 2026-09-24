package io.mosire.simos.social.gen;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个国家的**军事编制**（P4 才用；本笔只解析并暴露，不做任何计算）。另带全局的 {@code armKits} 装备配比表。
 *
 * <p>★ 逐键对应冻结输入 {@code config/worldgen/v17levant-nations.json} 的 {@code nations[].army} 与顶层 {@code
 * armKits}：{@code peacetime}/{@code mobilization} 是文档给的平时/动员人数，{@code establishment} 是 Turn 编制
 * 各兵种人数（key = 兵种名，value = 人数）。两者**不必然相等**（德意志编制 25500 vs 文档动员 25000，差 500 如实保留）。
 *
 * <p>★ {@code armKits} 是**全局**表（顶层一份），每个国家的 {@code ArmyPlan} 都指向同一份不可变视图；它是拟定的 _derived
 * 数据（文档只给兵种与人数，不给装备明细），P4 按每 100 人向上取整换算成 {@code unit.CreateUnit} 的 {@code Map<String,Integer>}。
 *
 * <p>★ 三个容器**保序不可变**（{@code LinkedHashMap} + {@code unmodifiableMap}，内含 Map 也逐层复制），且各量不得为负。
 *
 * <p>★ 本类型不加任何 {@code isXxx()} 实例方法（见 {@link PlannedCity} 类注释）。
 *
 * @param peacetime 平时兵力；&ge; 0
 * @param mobilization 动员兵力；&ge; 0
 * @param establishment 编制表（兵种 → 人数）；值都 &ge; 0
 * @param armKits 装备配比表（兵种 → （装备名 → 每百人件数））；值都 &ge; 0
 */
// ★ 豁免 EI_EXPOSE_REP（2026-09-24，跑 clean verify 时发现）：紧凑构造器已把两张表**逐层复制**并包成
//   unmodifiableMap（见 copyCounts），故访问器返回的既不是调用方的原对象、也改不动 ⇒ 真正的"暴露内部表示"不存在。
//   SpotBugs 看不穿私有 helper（copyCounts）的返回值是不变量，故按类豁免；理由写在这里，不动语义。
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "两张表在紧凑构造器里已逐层复制 + unmodifiableMap（copyCounts）；SpotBugs 看不穿该私有 helper 的返回值")
public record ArmyPlan(
    int peacetime,
    int mobilization,
    Map<String, Integer> establishment,
    Map<String, Map<String, Integer>> armKits) {

  public ArmyPlan {
    if (peacetime < 0) {
      throw new IllegalArgumentException("peacetime 不得为负: " + peacetime);
    }
    if (mobilization < 0) {
      throw new IllegalArgumentException("mobilization 不得为负: " + mobilization);
    }
    establishment = copyCounts(establishment, "establishment");
    Map<String, Map<String, Integer>> kits = new LinkedHashMap<>();
    if (armKits == null) {
      throw new IllegalArgumentException("armKits 不得为 null（无装备表用 Map.of()）");
    }
    for (Map.Entry<String, Map<String, Integer>> entry : armKits.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("armKits 的兵种名不得为空白");
      }
      kits.put(entry.getKey(), copyCounts(entry.getValue(), "armKits." + entry.getKey()));
    }
    armKits = Collections.unmodifiableMap(kits);
  }

  private static Map<String, Integer> copyCounts(Map<String, Integer> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException(field + " 不得为 null");
    }
    Map<String, Integer> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException(field + " 的键不得为空白");
      }
      if (entry.getValue() == null || entry.getValue() < 0) {
        throw new IllegalArgumentException(field + " 的值必须是非负整数: " + entry);
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  /** 换装备配比表（其余不动），P4 装配用。 */
  public ArmyPlan withArmKits(Map<String, Map<String, Integer>> value) {
    return new ArmyPlan(peacetime, mobilization, establishment, value);
  }
}
