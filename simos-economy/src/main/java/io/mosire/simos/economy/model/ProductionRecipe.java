package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ **通用生产配方（V7；spec 第三阶段设计稿 §五）**：把"每单位**什么**"从隐式约定（亩）变成**数据**。
 *
 * <p>★★ **它解决的那处硬绑**（spec §一.4，逐行复核过）：{@code EconomySettlement.harvest} 里三路瓶颈**全是"亩"**、产出键**写死
 * {@code GRAIN}**、分配权重**只读土地** ⇒ "每座工坊产 N 匹布"**表达不出来**（那正是 201 个手工业产业恒产 0 的根因）。 本类型把 {@code
 * harvest} 的公式泛化成"**每种 capacity 一路 + 劳动一路 + 每种投入一路**"：
 *
 * <pre>
 * capacity requirements:  Map&lt;AssetKind, Long&gt;   // 每 1 单位规模需要多少生产资料（LAND 按千分亩、其余按件）
 * consumed inputs:        Map&lt;CommodityId, Long&gt; // 每 1 单位规模每周期消耗什么（GRAIN=种子 / FIBER / IRON…）
 * labor:                  long                    // 每 1 单位规模需要多少劳动（千分劳动）
 * outputs:                Map&lt;CommodityId, Long&gt; // 每 1 单位规模产出什么
 * </pre>
 *
 * <p>★★ **规模 = 最紧约束**（spec §五 原文："生产规模统一根据最紧约束决定"）：
 *
 * <pre>
 * scale = min( ⌊可用生产资料_k ÷ capacityPerUnit_k⌋  …每种 capacity 一路
 *            , ⌊平均每日实际劳动 ÷ laborPerUnit⌋     …劳动一路
 *            , ⌊本周期实际扣到的投入_j ÷ inputPerUnit_j⌋ …每种投入一路 )
 * </pre>
 *
 * **取小后向下取整**（整数运算，禁 double；量纲标定值见 spec §7/§十）。★ 某一路的"每单位需求"为 {@code 0} ⇒ **不施加那一路约束**（不是"规模 0"）——
 * 这正是旧代码 {@code seedPerMu == 0} 的处置，旧档与未配投入的产业据此与 V2 逐值一致。
 *
 * <p>★★ **"每单位规模"是什么，由 {@link #capacityPerUnit} 说清楚**（这是本轮新增的那一维）：农业的规模单位是**亩**（{@code {LAND:
 * 1000}} = 每 1 亩要 1,000 千分亩），故"每亩 67 粮"里的"每亩"从此是**数据**而不是约定；手工业的规模单位是**座**（{@code {WORKSHOP: 1}}）。
 *
 * <p>★ **参数目录不在本轮**（spec 裁定的顺序是"先让经济跑对，目录随后"）：故本类型的四个分量**仍住在 {@code Industry} 实例里** （{@code
 * Industry} 用 {@code recipe()} 把四个字段打成一个视图），**不建半套目录**。
 *
 * <p>★ **四张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——迭代序不是内容的纯函数（字节级往返因此不成立）。冻结那一步**写在字段赋值处**（SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。
 *
 * @param capacityPerUnit 每 1 单位规模需要多少生产资料（{@code LAND} 的量纲是**千分亩**、其余按**件**）；键值非空、逐值 {@code ≥
 *     0}、**且不得为空表**（它是"单位规模"的锚，见构造期守卫）
 * @param inputPerUnit 每 1 单位规模**每周期一次性**消耗的商品（{@code GRAIN} 作种子、{@code FIBER}/{@code IRON} 作原料）；
 *     键值非空、逐值 {@code ≥ 0}。空表 = 不消耗任何商品
 * @param laborPerUnit 每 1 单位规模需要的劳动（**千分劳动**）；不得为负。{@code 0} = 不施加劳动约束
 * @param outputPerUnit 每 1 单位规模的产出（农业 = 每亩 67 粮；织机 = 每台 N 匹布）；键值非空、逐值 {@code ≥ 0}
 */
public record ProductionRecipe(
    Map<AssetKind, Long> capacityPerUnit,
    Map<CommodityId, Long> inputPerUnit,
    long laborPerUnit,
    Map<CommodityId, Long> outputPerUnit) {

  public ProductionRecipe {
    if (capacityPerUnit == null) {
      throw new IllegalArgumentException("ProductionRecipe.capacityPerUnit 不得为 null（无产能约束用空 map）");
    }
    if (inputPerUnit == null) {
      throw new IllegalArgumentException("ProductionRecipe.inputPerUnit 不得为 null（无投入用空 map）");
    }
    if (outputPerUnit == null) {
      throw new IllegalArgumentException("ProductionRecipe.outputPerUnit 不得为 null（无产出用空 map）");
    }
    if (laborPerUnit < 0) {
      throw new IllegalArgumentException("ProductionRecipe.laborPerUnit 不得为负: " + laborPerUnit);
    }
    // ★★ **产能那一路不能空**：它是"单位规模"的**锚** —— 没有它，"每 1 单位规模"就没有定义（规模无上界 ⇒ 产出随"可用"无限涨，
    //   即凭空造物）。★ 本轮所有配方都锚在一个生产资料上（农业锚土地、织机锚工具、作坊锚工坊）；"纯劳动 / 纯投入"的配方
    //   要等出现真实需求再开口子（届时"单位规模"得先有定义）。
    if (capacityPerUnit.isEmpty()) {
      throw new IllegalArgumentException(
          "ProductionRecipe.capacityPerUnit 不得为空（它是\"单位规模\"的锚，没有它规模无上界）");
    }
    Map<AssetKind, Long> capacityCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : capacityPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRecipe.capacityPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      // ★ **必须为正**：产能需求为 0 的那一路恒无约束（÷0 更是不允许），它不是一个"约束"，是写错了。
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionRecipe.capacityPerUnit 的数量必须为正："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      capacityCopy.put(entry.getKey(), entry.getValue());
    }
    capacityPerUnit = Collections.unmodifiableMap(capacityCopy); // ★ 冻在赋值处
    // ★★ **两段逐字展开、不抽 helper 去冻**（照 {@code Industry} 的先例）：SpotBugs 的 EI_EXPOSE_REP
    //   **不做跨过程分析**，只认它看得见的包装 —— 实测把这两段的"复制 + unmodifiableMap"塞进 helper
    //   会让 verify 的 spotbugs-check 报 Medium 并把构建打红。
    Map<CommodityId, Long> inputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : inputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRecipe.inputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionRecipe.inputPerUnit 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      inputCopy.put(entry.getKey(), entry.getValue());
    }
    inputPerUnit = Collections.unmodifiableMap(inputCopy); // ★ 冻在赋值处
    Map<CommodityId, Long> outputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : outputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRecipe.outputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionRecipe.outputPerUnit 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      outputCopy.put(entry.getKey(), entry.getValue());
    }
    outputPerUnit = Collections.unmodifiableMap(outputCopy); // ★ 冻在赋值处
  }
}
