package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 产业（新经济设计 §3.1 逐字）：**一整个产业**的聚合状态——制度、周期与进度、投入、产出、允许的阶层槽位、分配函数，外加 **V7 的通用生产配方** （{@link
 * #capacityPerUnit()} / {@link #laborPerUnit()} / {@link #inputPerUnit()} / {@link
 * #outputPerUnit()}）。
 *
 * <p>★ **不再是逐生产单位**（§1 取代表）：周期/进度/投入/产出函数都挂在产业这一层，复杂度不随人口或单位数线性增长。
 *
 * <p>★★ **R3（V7）起"每单位什么"是数据**（spec §五 / §一.4）：本类新添 {@code capacityPerUnit} 与 {@code laborPerUnit}，
 * 并把两个投入表的**值侧**从"无商品维度的标量"换成 {@code Map<CommodityId, Long>}（原来表达不了"消耗 IRON"）。四个分量合起来就是 {@link
 * #recipe()}：
 *
 * <pre>
 * capacityPerUnit[k]       每 1 单位规模需要多少生产资料 k（LAND 千分亩 / 其余件）   ← "每单位什么"的那一维
 * inputPerUnit()[j]        每 1 单位规模每周期消耗多少商品 j（毫单位）
 * laborPerUnit             每 1 单位规模需要多少劳动（千分劳动）
 * outputPerUnit[j]         每 1 单位规模产出多少商品 j（**商品单位**，结算时 ×1000 换毫）
 * </pre>
 *
 * ★★ **{@code cycleInputPerUnit} 与 {@code inputPerUnit()} 的关系**（一处真相 + 一个镜像）：前者是**按生产资料种类分列**的投入表
 * （"经由这一路要消耗什么"），后者 = 各路的合计。**合计是本方法算的，不是第二份手写的数** —— 于是"两处"在结构上不可能漂开。
 *
 * <p>★★ **量纲陷阱（两张表的单位刻意不同，别混）**：
 *
 * <ul>
 *   <li>{@code cycleInputPerUnit}/{@code dailyInputPerUnit} 的值是**最小计量单位 / 单位规模**（粮 ⇒ 毫粮：每亩需种 8,000
 *       毫粮）；
 *   <li>{@code outputPerUnit} 的值是**商品单位 / 单位规模**（粮 ⇒ 粮：每亩 67 粮），结算时 × {@code
 *       MILLI_PER_COMMODITY_UNIT} 换成最小计量单位。
 * </ul>
 *
 * 这个不对称是 V2 标定值的既成事实（67 粮/亩 与 8,000 毫粮/亩 各有用例钉着），统一它只会为了好看而改动其中一个数。
 *
 * <p>★ **本类只存形状，不含公式**（§八 R1 行："模块化、无公式"）：{@code progressDays} 怎么推进、投入怎么扣、{@code outputPerUnit}
 * 怎么乘，都是结算逻辑（{@code EconomySettlement}），不在本类。
 *
 * <p>★ **不变量（构造期判，§3.1 + §6）**：
 *
 * <ul>
 *   <li>{@code cycleDays ≥ 1}、{@code 0 ≤ progressDays ≤ cycleDays}（§3.1"当前进度 0..cycleDays"）
 *   <li>{@code dailyLaborPerUnit ≥ 0}、{@code laborPerUnit ≥ 0}；四张表的逐值 {@code ≥ 0}（§6.4 存量非负的下界）
 *   <li>**{@code capacityPerUnit} 不得为空**（"单位规模"的锚；见 {@link ProductionRecipe} 的构造期守卫）
 *   <li>**{@code slots} 非空、逐项非空、{@code id} 不重复**（槽位是"制度允许的角色"，**不含人口占比**—— 占比是 {@code
 *       ClassRow.population} 的观测派生，见 {@link ClassSlot} 的类注释）
 * </ul>
 *
 * <p>★ **四张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——迭代序不是内容的纯函数（会产出不同字节）。冻结那一步**写在字段赋值处**（SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。
 *
 * @param id 稳定身份
 * @param name 展示名
 * @param regime 生产制度（小农 / 封建租佃 / 手工业 / 家户自给 …）：决定允许哪些阶层槽位
 * @param cycleDays 生产周期（天）；农业 120、手工业可短；必须 ≥ 1
 * @param progressDays 当前进度（天）；必须 ∈ [0, cycleDays]
 * @param capacityPerUnit 每 1 单位规模需要的生产资料（{@code LAND} 按**千分亩**、其余按件）；键值非空、逐值 ≥ 0。空表 =
 *     无产能约束（只受劳动与投入约束）
 * @param dailyInputPerUnit 每 1 单位规模**每日**原料需求（按生产资料种类归类）；键值非空、逐值 ≥ 0。★ **本轮仍是零读取点** （spec §3.3
 *     明说两个字段并存、语义各自清楚；"每日原料"在 §四 的后续增量里）
 * @param dailyLaborPerUnit 每 1 单位规模的每日劳动需求（千分劳动）；不得为负。★ 本轮零读取点（同上）
 * @param laborPerUnit 每 1 单位规模需要的劳动（**千分劳动**）—— **收获时的劳动瓶颈读它**；不得为负，0 = 不施加劳动约束
 * @param outputPerUnit 每 1 单位规模的基准产出（农业 = 每亩 67 粮，v2 spec §10.3 标定值；织机 = 每台 N 匹布）；键值非空、 逐值 ≥ 0。★
 *     键已能放多商品，V7 起"每单位什么"由 {@link #capacityPerUnit()} 说清楚，不再是隐式约定（亩）
 * @param cycleInputPerUnit 每 1 单位规模**每周期一次性**投入（v2 spec §3.3；R3 换型：值侧带上商品维度）。**量纲**：最小计量单位 /
 *     单位规模（{@code LAND} 的键值是 **毫粮/亩**）。★ 别与 {@code ClassRow.meansOfProduction} 的 {@code
 *     LAND}（**千分亩**） 混——现扣步里要先 {@code / 1000} 换成亩。**键 = 这段投入挂在哪种生产资料上**（一种归类，不要求它同时是产能约束：
 *     "工具的保养要耗粮"完全可以只出现在投入表里）；**合计**才进规模公式。键值非空、逐值 ≥ 0
 * @param slots 该制度允许的阶层槽位；非空、id 不重复（**不含人口占比**）
 * @param allocation 制度分配函数（版本化参数；本类不执行它）
 * @param cycleLaborMilli 本周期**累计的实际投入劳动**（千分劳动·日）：日结算每天把该产业名下的劳动配额之和累加进来 （**供收获时用**）。
 *     收获当天先加当日量再取平均（{@code /cycleDays}），据此按 {@link #laborPerUnit()} 算规模上限；周期关账后清零。不得为负
 * @param cycleInputUsedMilli 本周期**实际扣到的投入**（毫单位，**按商品**）累加器，形制同 {@link #cycleLaborMilli()}：
 *     播种日（{@code progressDays == 0}）逐行累加、周期关账后清零。键值非空、逐值 ≥ 0。 ★ 它同时是"投入的计量"（v2 spec §二
 *     把留种列在**数**里）：收获日用 {@code cycleInputUsedMilli[j] / inputPerUnit[j]} 得**该投入能支撑的规模**，构成投入那一路瓶颈。
 *     ★ **R3 起是 Map**（原来只有"种子"一个标量）：作坊要"消耗 FIBER 与 IRON"，一条标量表达不了两种原料
 */
public record Industry(
    IndustryId id,
    String name,
    RegimeId regime,
    long cycleDays,
    long progressDays,
    Map<AssetKind, Long> capacityPerUnit,
    Map<AssetKind, Map<CommodityId, Long>> dailyInputPerUnit,
    long dailyLaborPerUnit,
    long laborPerUnit,
    Map<CommodityId, Long> outputPerUnit,
    Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
    List<ClassSlot> slots,
    AllocationRule allocation,
    long cycleLaborMilli,
    Map<CommodityId, Long> cycleInputUsedMilli) {

  public Industry {
    if (id == null) {
      throw new IllegalArgumentException("Industry.id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Industry.name 不得为空白");
    }
    if (regime == null) {
      throw new IllegalArgumentException("Industry.regime 不得为 null");
    }
    if (allocation == null) {
      throw new IllegalArgumentException("Industry.allocation 不得为 null");
    }
    if (cycleDays < 1) {
      throw new IllegalArgumentException("Industry.cycleDays 必须 ≥ 1: " + cycleDays);
    }
    if (progressDays < 0 || progressDays > cycleDays) {
      throw new IllegalArgumentException(
          "Industry.progressDays 必须 ∈ [0, cycleDays]：progressDays="
              + progressDays
              + ", cycleDays="
              + cycleDays);
    }
    if (capacityPerUnit == null) {
      throw new IllegalArgumentException("Industry.capacityPerUnit 不得为 null（无产能约束用空 map）");
    }
    if (dailyInputPerUnit == null) {
      throw new IllegalArgumentException("Industry.dailyInputPerUnit 不得为 null（无投入用空 map）");
    }
    if (outputPerUnit == null) {
      throw new IllegalArgumentException("Industry.outputPerUnit 不得为 null（无产出用空 map）");
    }
    if (cycleInputPerUnit == null) {
      throw new IllegalArgumentException("Industry.cycleInputPerUnit 不得为 null（无一次投入用空 map）");
    }
    if (cycleInputUsedMilli == null) {
      throw new IllegalArgumentException("Industry.cycleInputUsedMilli 不得为 null（未投入用空 map）");
    }
    if (dailyLaborPerUnit < 0) {
      throw new IllegalArgumentException("Industry.dailyLaborPerUnit 不得为负: " + dailyLaborPerUnit);
    }
    if (laborPerUnit < 0) {
      throw new IllegalArgumentException("Industry.laborPerUnit 不得为负: " + laborPerUnit);
    }
    if (cycleLaborMilli < 0) {
      throw new IllegalArgumentException("Industry.cycleLaborMilli 不得为负: " + cycleLaborMilli);
    }
    if (slots == null) {
      throw new IllegalArgumentException("Industry.slots 不得为 null");
    }
    // ★★ **五段构造逐字展开、不抽 helper**（照本仓先例）：SpotBugs 的 EI_EXPOSE_REP **不做跨过程分析**，
    //   只认它看得见的包装 ⇒ 把"复制 + unmodifiableMap"塞进 helper 会让这五张表全部被报为"暴露内部表示"
    //   （实测：verify 的 spotbugs-check 直接 7 个 Medium 把构建打红）。
    Map<AssetKind, Long> capacityCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : capacityPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Industry.capacityPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      // ★ 产能需求必须为正：0 那一档恒无约束（÷0 更是不允许），它不是一个"约束"，是写错了。
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "Industry.capacityPerUnit 的数量必须为正：" + entry.getKey() + " = " + entry.getValue());
      }
      capacityCopy.put(entry.getKey(), entry.getValue());
    }
    capacityPerUnit = Collections.unmodifiableMap(capacityCopy); // ★ 冻在赋值处
    Map<AssetKind, Map<CommodityId, Long>> dailyInputCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Map<CommodityId, Long>> entry : dailyInputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Industry.dailyInputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      dailyInputCopy.put(entry.getKey(), freezeLine(entry.getValue(), "dailyInputPerUnit"));
    }
    dailyInputPerUnit = Collections.unmodifiableMap(dailyInputCopy); // ★ 冻在赋值处
    Map<CommodityId, Long> outputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : outputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Industry.outputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "Industry.outputPerUnit 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      outputCopy.put(entry.getKey(), entry.getValue());
    }
    outputPerUnit = Collections.unmodifiableMap(outputCopy); // ★ 冻在赋值处
    Map<AssetKind, Map<CommodityId, Long>> cycleInputCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Map<CommodityId, Long>> entry : cycleInputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Industry.cycleInputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      cycleInputCopy.put(entry.getKey(), freezeLine(entry.getValue(), "cycleInputPerUnit"));
    }
    cycleInputPerUnit = Collections.unmodifiableMap(cycleInputCopy); // ★ 冻在赋值处
    Map<CommodityId, Long> cycleInputUsedCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : cycleInputUsedMilli.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Industry.cycleInputUsedMilli 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "Industry.cycleInputUsedMilli 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      cycleInputUsedCopy.put(entry.getKey(), entry.getValue());
    }
    cycleInputUsedMilli = Collections.unmodifiableMap(cycleInputUsedCopy); // ★ 冻在赋值处
    // ★★ **产能那一路不能空**：它是"单位规模"的**锚**（见 {@link ProductionRecipe} 的构造期守卫）。
    if (capacityPerUnit.isEmpty()) {
      throw new IllegalArgumentException("Industry.capacityPerUnit 不得为空（它是\"单位规模\"的锚，没有它规模无上界）");
    }
    List<ClassSlot> slotsCopy = new ArrayList<>();
    Set<ClassSlotId> slotIds = new LinkedHashSet<>();
    for (ClassSlot slot : slots) {
      if (slot == null) {
        throw new IllegalArgumentException("Industry.slots 不得含 null");
      }
      if (!slotIds.add(slot.id())) {
        throw new IllegalArgumentException("Industry.slots 的 id 不得重复: " + slot.id());
      }
      slotsCopy.add(slot);
    }
    if (slotsCopy.isEmpty()) {
      throw new IllegalArgumentException("Industry.slots 不得为空");
    }
    slots = Collections.unmodifiableList(slotsCopy); // ★ 冻在赋值处
  }

  /**
   * ★★ **通用生产配方**（V7；spec §五 的四个分量）：把本记录里散着的四段打成一个视图。
   *
   * <p>★ **它是纯派生**（{@code inputPerUnit} 由 {@link #cycleInputPerUnit()} 合计而来）⇒ 不存在第二份手写的数， 也不可能与
   * {@code Industry} 的字段漂开。每次调用新建一个不可变记录（很小；只被收获那一步读一次）。
   */
  public ProductionRecipe recipe() {
    return new ProductionRecipe(capacityPerUnit, inputPerUnit(), laborPerUnit, outputPerUnit);
  }

  /**
   * ★ **本周期实际扣到的种子**（毫粮）= {@link #cycleInputUsedMilli()} 在**粮**这一商品上的投影 —— 农业的"留种的计量" 读它（v2 spec §二
   * 把留种列在**数**里）。
   *
   * <p>★ **它是派生视图、不是第二份真相**：同一个 {@code cycleInputUsedMilli} 表，多商品的累计读原表、只关心种子的那几处读它。 这样"R3
   * 把累加器从标量换成按商品的表"就不必把每一处"本周期扣了多少种子"的断言都改写成取键 —— 而那些断言测的东西一个字没变。
   */
  public long cycleSeedUsedMilli() {
    return cycleInputUsedMilli.getOrDefault(
        new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID), 0L);
  }

  /**
   * **每 1 单位规模每周期消耗的商品**（毫单位）= 各生产资料分路的**合计**（见类注释）。
   *
   * <p>★ 合计不是"再算一遍"，而是把 {@code cycleInputPerUnit} 那张按种类分列的表摊平 —— 口径的唯一落点仍是那张表。
   */
  public Map<CommodityId, Long> inputPerUnit() {
    Map<CommodityId, Long> merged = new LinkedHashMap<>();
    for (Map<CommodityId, Long> line : cycleInputPerUnit.values()) {
      for (Map.Entry<CommodityId, Long> entry : line.entrySet()) {
        merged.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    return Collections.unmodifiableMap(merged);
  }

  /**
   * 一张**投入表的内层**（生产资料 → 商品表）的冻结：键值非空、逐值 ≥ 0。
   *
   * <p>★ **这一层可以抽 helper**（与五段构造逐字展开的规矩不冲突）：SpotBugs 报的是**记录组件**的 {@code
   * EI_EXPOSE_REP}（"返回了内部表示"），而本方法的返回值是**装进外层 copy 的元素**、不是从访问器直接交出去的引用 —— 外层的 {@code
   * Collections.unmodifiableMap} 就写在字段赋值处，它看得见。
   */
  private static Map<CommodityId, Long> freezeLine(Map<CommodityId, Long> source, String field) {
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Industry." + field + " 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "Industry." + field + " 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }
}
