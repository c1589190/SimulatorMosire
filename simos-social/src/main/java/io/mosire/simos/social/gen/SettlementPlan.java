package io.mosire.simos.social.gen;

import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次生成的完整结果：**逐格农村人口 + 城市表 + 逐格审计量**，另加一个显式的"城市承载缺口"。
 *
 * <p>★ **三条不变量在生成器出口断言过一次**（见 {@link SettlementGenerator}）：{@code Σ rural == ruralTotal}、{@code Σ
 * city.population == urbanTotal}、{@code ruralTotal + urbanTotal == total}。{@link #shortfall()}
 * **不是**其中一条 —— 它是 建模诚实性：当"城市承载 &lt; 城市人口需求"时如实报出缺口，而不是静默把总人口调低。
 *
 * <p>★ 三个容器**保序不可变**（{@code LinkedHashMap} + {@code unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**：它的迭代序不是 内容的纯函数），且 {@code cities} 按（人口降序，id 升序）固定排序 ——
 * 这样"同种子同输出"与"换插入顺序同输出"两条用例才逐字段成立。
 *
 * @param ruralPopulation 逐格农村人口；**海洋/零产格缺席**（不是 0 —— 写 0 会让"该格有人口序列"变成假话）
 * @param cities 城市表，按（人口降序，id 升序）排序
 * @param audit 逐格中间量（键集 == 有承载力的格集，与 {@code ruralPopulation} 同键集）
 * @param urbanCapacity 城市承载合计（稀有惩罚后、归一化前的城市权重之和，四舍五入）；&ge; 0
 * @param shortfall 缺口 = {@code max(0, urbanTotal - urbanCapacity)}；&gt; 0 表示承载不足、被归一化**放大**过
 */
public record SettlementPlan(
    Map<HexCoord, Long> ruralPopulation,
    List<PlannedCity> cities,
    Map<HexCoord, SurplusEstimate> audit,
    long urbanCapacity,
    long shortfall) {

  public SettlementPlan {
    if (ruralPopulation == null) {
      throw new IllegalArgumentException("ruralPopulation 不得为 null");
    }
    if (cities == null) {
      throw new IllegalArgumentException("cities 不得为 null");
    }
    if (audit == null) {
      throw new IllegalArgumentException("audit 不得为 null");
    }
    if (urbanCapacity < 0) {
      throw new IllegalArgumentException("urbanCapacity 不得为负: " + urbanCapacity);
    }
    if (shortfall < 0) {
      throw new IllegalArgumentException("shortfall 不得为负: " + shortfall);
    }
    Map<HexCoord, Long> ruralCopy = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, Long> entry : ruralPopulation.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ruralPopulation 的键与值都不得为 null");
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException("ruralPopulation 不得为负: " + entry);
      }
      ruralCopy.put(entry.getKey(), entry.getValue());
    }
    Map<HexCoord, SurplusEstimate> auditCopy = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, SurplusEstimate> entry : audit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("audit 的键与值都不得为 null");
      }
      auditCopy.put(entry.getKey(), entry.getValue());
    }
    List<PlannedCity> citiesCopy = new ArrayList<>(cities.size());
    for (PlannedCity city : cities) {
      if (city == null) {
        throw new IllegalArgumentException("cities 不得含 null");
      }
      citiesCopy.add(city);
    }
    ruralPopulation = Collections.unmodifiableMap(ruralCopy); // ★ 冻在赋值处（SpotBugs 只认它看得见的）
    audit = Collections.unmodifiableMap(auditCopy);
    cities = Collections.unmodifiableList(citiesCopy);
  }

  /** 换城市表（其余不动）。 */
  public SettlementPlan withCities(List<PlannedCity> value) {
    return new SettlementPlan(ruralPopulation, value, audit, urbanCapacity, shortfall);
  }
}
