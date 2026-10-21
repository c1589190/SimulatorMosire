package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ **通用生产配方（V7；spec 第三阶段设计稿 §五）**：把"每单位**什么**"从隐式约定（亩）变成**数据**。
 *
 * <p>★★ **它解决的那处硬绑**（spec §一.4，逐行复核过）：{@code 旧结算引擎（R3a 已删除）.harvest} 里三路瓶颈**全是"亩"**、产出键**写死 {@code
 * GRAIN}**、分配权重**只读土地** ⇒ "每座工坊产 N 匹布"**表达不出来**（那正是 201 个手工业产业恒产 0 的根因）。 本类型把 {@code harvest}
 * 的公式泛化成"**每种 capacity 一路 + 劳动一路 + 每种投入一路**"：
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
 * @param capacityPerUnit 每 1 单位规模需要多少生产资料（{@code LAND} 的量纲是**千分亩**、其余按**件**）；键值非空、逐值 {@code > 0}。空表
 *     = 没有 AssetKind 通道；它与 {@link #durableCommodityCapacityPerUnit()} 至少一边非空（"单位规模"的锚，见构造期守卫）
 * @param inputPerUnit 每 1 单位规模**每周期一次性**消耗的商品（{@code GRAIN} 作种子、{@code FIBER}/{@code IRON} 作原料）；
 *     键值非空、逐值 {@code ≥ 0}。空表 = 不消耗任何商品
 * @param laborPerUnit 每 1 单位规模需要的劳动（**千分劳动**）；不得为负。{@code 0} = 不施加劳动约束
 * @param outputPerUnit 每 1 单位规模的产出（农业 = 每亩 67 粮；织机 = 每台 N 匹布）；键值非空、逐值 {@code ≥ 0}
 * @param moneyOutputPerUnit ★★ <b>W5：每 1 单位规模的铸币产出（毫货币/规模·周期）</b>；{@link MoneyOutput#NONE}（空表）=
 *     这一路不存在。★ 与商品产出<b>刻意同形、但量纲不同</b>（货币最小单位就是毫 ⇒ 不再 ×1000）；键类型是 {@code CurrencyId}（另一个身份命名空间）——
 *     理由见 {@link MoneyOutput} 的类注
 * @param durableCommodityCapacityPerUnit ★★ <b>D-032 C5a：每 1 单位规模需要的耐久商品资本（容量锚，不消耗）</b>； 键 = 商品
 *     id（如 {@code transport_horse}）、值 = 每单位规模的数量。 ★ 它与 {@link #inputPerUnit()} 是<b>两条不同通道</b>：
 *     durable 只参与规模计算、<b>不</b>在周期里扣减；消耗性投入仍只走 {@code inputPerUnit}。空表 = 没有这一路； 逐值必须 {@code > 0}。★
 *     {@code capacityPerUnit} 与它<b>至少一边非空</b>（"单位规模"必须有锚）。
 */
public record ProductionRecipe(
    Map<AssetKind, Long> capacityPerUnit,
    Map<CommodityId, Long> inputPerUnit,
    long laborPerUnit,
    Map<CommodityId, Long> outputPerUnit,
    MoneyOutput moneyOutputPerUnit,
    Map<CommodityId, Long> durableCommodityCapacityPerUnit) {

  /**
   * ★ <b>W5 之前的 4 参形状</b>：{@code moneyOutputPerUnit} 取 {@link MoneyOutput#NONE}（不铸币）。
   *
   * <p>★ 它服务不铸币的既有调用点/夹具：语义 = 这个配方<b>没有铸币这一路</b>，逐值退回 W5 之前。
   */
  public ProductionRecipe(
      Map<AssetKind, Long> capacityPerUnit,
      Map<CommodityId, Long> inputPerUnit,
      long laborPerUnit,
      Map<CommodityId, Long> outputPerUnit) {
    this(capacityPerUnit, inputPerUnit, laborPerUnit, outputPerUnit, MoneyOutput.NONE, Map.of());
  }

  /**
   * ★ <b>C5a 之前的 5 参形状</b>（含铸币产出、无耐久商品资本通道）：等价于 {@code durableCommodityCapacityPerUnit =
   * Map.of()}；旧调用点逐值不变。
   */
  public ProductionRecipe(
      Map<AssetKind, Long> capacityPerUnit,
      Map<CommodityId, Long> inputPerUnit,
      long laborPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      MoneyOutput moneyOutputPerUnit) {
    this(capacityPerUnit, inputPerUnit, laborPerUnit, outputPerUnit, moneyOutputPerUnit, Map.of());
  }

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
    // ★★ **产能锚不能两条都空**：它是"单位规模"的**锚** —— 没有它，"每 1 单位规模"就没有定义（规模无上界 ⇒ 产出随"可用"无限涨，
    //   即凭空造物）。★ 本批起锚有两条合法通道：AssetKind capacity 与耐久商品资本 capacity（至少一边非空）；
    //   "纯劳动 / 纯投入"的配方仍要等出现真实需求再开口子（届时"单位规模"得先有定义）。
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
    if (durableCommodityCapacityPerUnit == null) {
      throw new IllegalArgumentException(
          "ProductionRecipe.durableCommodityCapacityPerUnit 不得为 null（无此通道用空 map）");
    }
    Map<CommodityId, Long> durableCapacityCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : durableCommodityCapacityPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRecipe.durableCommodityCapacityPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionRecipe.durableCommodityCapacityPerUnit 的数量必须为正："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      durableCapacityCopy.put(entry.getKey(), entry.getValue());
    }
    durableCommodityCapacityPerUnit = Collections.unmodifiableMap(durableCapacityCopy); // ★ 冻在赋值处
    if (capacityPerUnit.isEmpty() && durableCommodityCapacityPerUnit.isEmpty()) {
      throw new IllegalArgumentException(
          "ProductionRecipe 的 capacityPerUnit 与 durableCommodityCapacityPerUnit 至少一边非空"
              + "（\"单位规模\"必须有锚）");
    }
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
    // ★★ W5：铸币那一路只判"非 null"（逐值 > 0 与冻结在 {@link MoneyOutput} 自己的构造期做完 —— 不在这里
    //   把同一条判据写第二遍；两处判同一条边界正是本仓最贵的那类漂移）。
    if (moneyOutputPerUnit == null) {
      throw new IllegalArgumentException(
          "ProductionRecipe.moneyOutputPerUnit 不得为 null（不铸币请给 MoneyOutput.NONE）");
    }
  }
}
