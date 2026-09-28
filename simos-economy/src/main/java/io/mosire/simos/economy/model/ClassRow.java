package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>家户行</b>（新经济设计 §3.2；<b>2026-09-27 H0 起行就是家户</b>，裁定 K2；<b>H1 起行里没有商品库存</b>，裁定 D3-C/K1）： 人口 /
 * 劳动 / 参与率 / 债务引用 / 两类需求 —— **同一批人的视图**。
 *
 * <p>★★ <b>键 = {@link CohortKey}（格 + 居住类型 + 阶层），不再是"产业 + 槽位"</b>（裁定 K2 / D3-C）：家户是**持有商品与货币的经济主体**，
 * 而人口数、劳动、需求、压力、生死是**同一批人的视图** —— 就是本记录。一个家户给多个产业出劳动（农村家庭既种地又织布）⇒ <b>仍然只有一份账</b>（V9 /
 * I1.2），而"哪些行属于这个产业"改由**劳动配额表**推（见 {@code EconomySettlement.householdKeysOf}）。
 *
 * <p>★★ <b>为什么没有 {@code goods}</b>（H1；裁定 D3-C "家户主体化" + I6.1/I7.1 "一本账"）：商品库存的**唯一持久真源**是 actor
 * 切片里该家户 actor 的 {@code GoodsAccount}（键 {@code (HouseholdActors.of(key), key.hex())}）。 economy
 * 的日结算要读库存 ⇒ 它在**会话工作副本**里读（{@code EconomyDayStepper} 持有、就地更新，见 {@link
 * io.mosire.simos.economy.time.EconomyDayStepper}），**不是第二本持久账**。 ★ <b>辨别口径</b>：本字段一旦回来（或守恒式里重新出现
 * {@code ΔΣRowGoods} 这一项），就说明"还有一本账没搬完"。
 *
 * <p>★★ <b>为什么没有 {@code meansOfProduction}</b>（裁定 K3，2026-09-27）：产能（亩/织机/作坊）是**该格该产业的技术属性**， 已搬到
 * {@link Industry#capacity()}。搬走的**代价如实记**：改前 {@code scaleOf} 用 Σ各行的产能 ⇒ 隐含"贫农缸空 ⇒
 * 它的地荒着"的阶级差异；搬走之后这条不再由产能表达，改由"投入由谁出"表达（C3，后续批次）—— 本阶段以**人口占比**折算各行"想扣多少" （见 {@code
 * EconomySettlement.rowSharesOf}（★ H3 已删），真档数值因此**允许变**。
 *
 * <p>★ **它是存量**（§3.3 末条"存量/流量分离"）：本期的发生额在 {@link FlowRow} 里、**结算后清零**；绝不用"生产成本"或"资产减少"
 * 冒充负债——债务只能由借入/赊购产生，引用 {@link #debts} 指向债务表。
 *
 * <p>★ **量纲**（§7）：{@code population} 人；{@code laborMilli} 千分劳动；{@code naturalNeeds}/{@code
 * effectiveDemand} 按最小计量单位；{@code money} 最小币值；{@code participationPerMille} 千分数。
 *
 * <p>★ **不变量（构造期判，§6.4）**：{@code population ≥ 0}、{@code laborMilli ≥ 0}、{@code money ≥ 0}、两个表的逐值
 * {@code ≥ 0}、{@code participationPerMille ∈ [0, 1000]}（§6.3 的上界部分；"≤ 槽位上限"要跨对象，见 §6.3，判在 {@code
 * EconomyData}）。
 *
 * <p>★ **两张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——迭代序不是内容的纯函数。冻结那一步**写在字段赋值处**（SpotBugs 的 {@code EI_EXPOSE_REP} 不做跨过程分析，只认它看得见的包装）⇒
 * 逐段构造**逐字展开、不抽 helper**（照 {@code Account}/{@code Industry} 的先例）。
 *
 * @param id ★★ <b>家户稳定身份</b>（S1）：是 {@code EconomyData.classes} 的键，迁移/分层/居住变化都不改它
 * @param view 当前视图（格 + 居住类型 + 阶层）：可变，不再是身份
 * @param population 人口（人）；不得为负
 * @param laborMilli **未按参与率折算的**每日劳动（千分劳动/日）——由 social 的人数 × 年龄×性别系数而来；不得为负 ★
 *     M1.8：它是"这份人有多少劳动能力"的毛量；按阶层参与率折算后的可用劳动<b>不存成第二份字段</b>，而是由 {@link
 *     #participationAdjustedLaborMilli()} 现算（唯一算法，见该方法）
 * @param participationPerMille 本期实际劳动投入率（≤ 该格各产业的槽位上限）；必须 ∈ [0, 1000]
 * @param money 货币（最小币值）；不得为负
 * @param debts 指向债务表的引用；可空、不得含 null
 * @param naturalNeeds 本期自然需求（生存/再生产；v1 只做前两档）；键值非空、逐值 ≥ 0
 * @param effectiveDemand 有效需求（= 有支付力的那部分，§十四/§十五 的分野）；键值非空、逐值 ≥ 0
 * @param cycleNaturalNeedMilli ★★ <b>M2.7 丙条仪器：本周期累计自然口粮需要</b>（毫粮）= {@code Σ_d
 *     dailyRationMilli(population_d, d)}，{@code population_d} = 第 d 天结算前的行人口（日初人口）—— 由 {@code
 *     EconomySettlement} 逐日累加、新周期第一天重置为当天需要（见 {@code withDailyNeed} 与流水清零点旁的注释）。
 *     <b>它是唯一与"周期累计未满足需求"同窗口的自然需求分母</b>；旧的"某一天人口 × 整周期配额"不得再与它并排当同一分母（丙条）。 旧档（M2.7 之前）缺本键 ⇒
 *     0（fail-closed 的"还没开始累计"），由 {@code EconomyPayloads.classRow} 与 Jackson 的记录绑定分别兜底。 不得为负
 */
public record ClassRow(
    HouseholdId id,
    CohortKey view,
    long population,
    long laborMilli,
    int participationPerMille,
    long money,
    List<DebtId> debts,
    Map<CommodityId, Long> naturalNeeds,
    Map<CommodityId, Long> effectiveDemand,
    long cycleNaturalNeedMilli) {

  public ClassRow {
    if (id == null) {
      throw new IllegalArgumentException("ClassRow.id 不得为 null（家户稳定身份，S1 起与视图分离）");
    }
    if (view == null) {
      throw new IllegalArgumentException("ClassRow.view 不得为 null（当前格 + 居住类型 + 阶层）");
    }
    if (population < 0) {
      throw new IllegalArgumentException("ClassRow.population 不得为负: " + population);
    }
    if (laborMilli < 0) {
      throw new IllegalArgumentException("ClassRow.laborMilli 不得为负: " + laborMilli);
    }
    if (participationPerMille < 0 || participationPerMille > 1000) {
      throw new IllegalArgumentException(
          "ClassRow.participationPerMille 必须 ∈ [0, 1000]: " + participationPerMille);
    }
    if (money < 0) {
      throw new IllegalArgumentException("ClassRow.money 不得为负: " + money);
    }
    if (cycleNaturalNeedMilli < 0L) {
      throw new IllegalArgumentException(
          "ClassRow.cycleNaturalNeedMilli 不得为负（它是逐日累加的自然口粮需要，不是赤字）: " + cycleNaturalNeedMilli);
    }
    if (naturalNeeds == null) {
      throw new IllegalArgumentException("ClassRow.naturalNeeds 不得为 null（无需求用空 map）");
    }
    if (effectiveDemand == null) {
      throw new IllegalArgumentException("ClassRow.effectiveDemand 不得为 null（无需求用空 map）");
    }
    if (debts == null) {
      throw new IllegalArgumentException("ClassRow.debts 不得为 null（无债务用空 list）");
    }
    Map<CommodityId, Long> needsCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : naturalNeeds.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ClassRow.naturalNeeds 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "ClassRow.naturalNeeds 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      needsCopy.put(entry.getKey(), entry.getValue());
    }
    naturalNeeds = Collections.unmodifiableMap(needsCopy); // ★ 冻在赋值处
    Map<CommodityId, Long> demandCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : effectiveDemand.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ClassRow.effectiveDemand 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "ClassRow.effectiveDemand 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      demandCopy.put(entry.getKey(), entry.getValue());
    }
    effectiveDemand = Collections.unmodifiableMap(demandCopy); // ★ 冻在赋值处
    List<DebtId> debtsCopy = new ArrayList<>();
    for (DebtId debt : debts) {
      if (debt == null) {
        throw new IllegalArgumentException("ClassRow.debts 不得含 null");
      }
      debtsCopy.add(debt);
    }
    debts = Collections.unmodifiableList(debtsCopy); // ★ 冻在赋值处
  }

  /**
   * ★ 当前视图（{@code 格 + 居住类型 + 阶层}）—— 旧调用点的兼容别名；新代码请写 {@link #view()}。
   *
   * <p>★★ <b>它不再是可以当身份用的键</b>：{@code EconomyData.classes/flows} 的键是 {@link #id()}。
   */
  public CohortKey key() {
    return view;
  }

  /**
   * ★★ <b>M1.8：按阶层参与率折算后的每日可用劳动</b>（千分劳动/日）= {@code laborMilli × participationPerMille ÷
   * 1000}（整数、向下取整）。
   *
   * <p>★★ <b>它是"同一份劳动最多只能折算一次"的唯一拼写点</b>：本方法（及其 static 形态）是**仅有的**把 {@code participationPerMille}
   * 乘进劳动量的地方；三处读者都调它，谁也不许再各乘一次：
   *
   * <ul>
   *   <li>{@code EconomySeeder.industryDailyLabor}（产业当日的配额总量）；
   *   <li>{@code EconomySettlement.laborOfCohort}（关账时逐 cohort 的**本周期**劳动量，再乘 {@code cycleDays}）；
   *   <li>读口 {@code ApiViews.classRowView} 的 {@code participationAdjustedLaborMilli} 一栏。
   * </ul>
   *
   * <p>★★ <b>为什么不把折算结果存成字段</b>：① 存了就有两个数（毛量与折算量），任何一处忘记同步都会让"劳动总量守恒"悄悄漂开； ②
   * 状态记录加组件会牵动变更集/codec/往返（铁律 5）—— 而本折算只是 {@code (laborMilli, participationPerMille)}
   * 的纯函数，没有存它的理由。★ 本阶段 {@code participationPerMille} 不在运行期变化（创世发一次、人死只缩 {@code laborMilli}），
   * 故"现算"与"存一份"逐值等价、而现算不可能漂开。
   */
  public long participationAdjustedLaborMilli() {
    return participationAdjustedLaborMilli(laborMilli, participationPerMille);
  }

  /**
   * ★★ <b>折算算法的 static 形态</b>（服务于还没有 {@code ClassRow} 对象的调用点：{@code
   * EconomySeeder.industryDailyLabor} 在生成载荷时用逐行的"人数 × 池人均劳动"临时量算总量）。
   *
   * <p>公式与不变量见 {@link #participationAdjustedLaborMilli()} —— 两个形态是<b>同一处</b>拼写点，实例方法只负责取自己的两个字段。
   *
   * @param laborMilli 未折算的每日劳动（千分劳动/日）；不得为负
   * @param participationPerMille 参与率（千分）；必须 ∈ [0, 1000]
   * @throws IllegalArgumentException 劳动量为负、或参与率越界（"折算系数"越界不是一种状态，是坏数据）
   */
  public static long participationAdjustedLaborMilli(long laborMilli, int participationPerMille) {
    if (laborMilli < 0L) {
      throw new IllegalArgumentException("折算的 laborMilli 不得为负: " + laborMilli);
    }
    if (participationPerMille < 0 || participationPerMille > 1000) {
      throw new IllegalArgumentException(
          "折算的 participationPerMille 必须 ∈ [0, 1000]: " + participationPerMille);
    }
    return laborMilli * participationPerMille / 1000L;
  }
}
