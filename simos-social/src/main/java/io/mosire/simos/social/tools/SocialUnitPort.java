package io.mosire.simos.social.tools;

import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * social 工具的**单位编制口**：家户与 unit 的容纳关系（"这家户现在编在哪个单位里"）。
 *
 * <p>★★ <b>为什么必须注入</b>：单位编制的事实住在 {@code simos-unit}，而 social **结构上够不着它**—— {@code simos-social} 的
 * enforcer 禁 {@code simos-unit}，且 {@code simos-unit} 反过来显式依赖 {@code simos-social} （方案 A：Social
 * 是人员/家户唯一域）⇒ 一旦 social 直连 unit，Maven 建图期就是**环**（{@code unit → social → unit}）。 家户归属是**跨域事实**：权威在
 * unit 侧，读它必须经组合根（app 同时认识两边）。
 *
 * <p>★★ <b>为什么校验也走端口</b>：{@code UnitOperations.setUnitHouseholds} 是单位侧容纳列表的**唯一守卫**
 * （不撞名/不重复/不跨单位重属），工具在组批前必须先跑它一遍（坏输入不提交半笔）。抄一份守卫 = 第二份编制规则，必然漂移 ⇒ 端口转调同一个静态方法，违例时**原样抛出**它自己的
 * {@link IllegalArgumentException}（文案逐字，工具折 {@code BAD_REQUEST}）。
 *
 * <p>★ <b>投影形状</b>：端口只回 unit id 与它的 households（字符串稳定序），不回 {@code Unit} 本体——social 不需要单位的其它字段，
 * 而"少给"是边界纪律的一部分（拿到什么就依赖什么）。
 */
public interface SocialUnitPort {

  /** 单位存在 ⇒ 其 id + households（单位切片迭代序）；不存在 ⇒ 空。 */
  Optional<UnitHouseholds> unit(SimulationState state, String unitId);

  /**
   * 哪些单位持有该家户（按单位切片迭代序；长度 &gt; 1 = 坏状态）。
   *
   * <p>★ 判据是 unit 侧 households 列表的**真实成员关系**，不看 {@code household.location()}（坏状态里两者可能已经不一致）。
   */
  List<String> householdOwners(SimulationState state, String householdId);

  /**
   * 单位侧守卫（宿主转调 {@code UnitOperations.setUnitHouseholds}）：不变量违例 ⇒ 抛出它自己的 {@link
   * IllegalArgumentException}（文案逐字）。
   */
  void requireValidHouseholds(SimulationState state, String unitId, List<String> householdIds);

  /**
   * 写单位容纳列表的命令类型（宿主回 {@code SetUnitHouseholdsHandler.TYPE}）。
   *
   * <p>★ <b>为什么类型串也走端口</b>：命名的**唯一拼写点**在 unit 域的 handler 常量上；social 看不见 unit ⇒ 若在域内手抄 {@code
   * "unit.SetUnitHouseholds"}，就是第二处拼写（写歪不会抛异常，只会让命令路由不到 ⇒ 静默失败）。
   */
  String unitHouseholdsCommandType();

  /**
   * 单位必须存在；不存在 ⇒ 抛出与既有工具逐字相同的 {@link IllegalArgumentException}。
   *
   * @param field 出错时点名的字段（如 {@code unitId} / {@code location.unitId} / {@code household.location}）
   */
  default UnitHouseholds requireUnit(SimulationState state, String rawUnitId, String field) {
    return unit(state, rawUnitId)
        .orElseThrow(() -> new IllegalArgumentException("参数 " + field + " 指定的单位不存在: " + rawUnitId));
  }

  /** 一个单位的编制投影。 */
  record UnitHouseholds(String unitId, List<String> households) {

    public UnitHouseholds {
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(households, "households");
      // ★ 保序冻结在赋值处（本仓明文口径：不用 List.copyOf —— 迭代序不是内容的纯函数）。
      households = Collections.unmodifiableList(new ArrayList<>(households));
    }
  }
}
