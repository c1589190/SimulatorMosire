package io.mosire.simos.gov;

import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.DecisionMakerId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>gov 归属表</b>（用户 2026-10-12 裁定「<b>归属肯定归模块</b>」）：{@code 决策人 → GOV 单位} 这张表的 <b>唯一形态与唯一裁定点</b>。
 *
 * <p>★★ <b>本批（乙收尾）起它是"写入方"</b>（3c/3d-1 时 gov 只是 nation 归属的裁定方 —— 见 {@link
 * io.mosire.simos.gov.nation.NationAffiliations} 的类注）：表本体住 {@link GovState#govAffiliations()}，
 * <b>写口 = {@code gov.AssignDecisionMakerGov}</b>（用户原话「<b>同样搞工单</b>」），本类只负责 <b>解释 + 裁定 + 渲染</b>。
 *
 * <p>★★ <b>裁定的是什么</b>：{@code GovState.govAffiliations()} 里的键值都是类型化的（{@link DecisionMakerId} /
 * {@link UnitId}），但"这个单位此刻还在不在、还是不是一个 GOV"要读 <b>unit 切片</b>（gov 允许依赖 unit）⇒ {@link #resolve}
 * 把"单位不存在 / 不是 GOV"的引用<b>裁剪丢弃</b>（fail-closed：不在表里 = 没有归属 ⇒ 读侧 deny-all）。
 *
 * <p>★ <b>单位被删 / 编制被摘不会让世界打不开</b>：那是世界数据的问题，只降权、不抛（与 {@code NationScope} 既有的"无匹配区域 ⇒ 显式
 * deny-all"同一口径）；<b>装配</b>问题（缺 unit 切片）仍然当场抛 —— 缺切片的判定在调用方 {@code ToolSupport} 那一层。
 *
 * <p>★ <b>保序不可变</b>：{@code LinkedHashMap} 拷贝 + 赋值处 {@code Collections.unmodifiableMap} 冻结（<b>不用</b>
 * {@code Map.copyOf}——迭代序不是内容的纯函数）。本类型<b>不进任何状态树</b>（它是每次读现算的派生视图，与 {@code DecisionScopeFunction}
 * 的"是函数不是配置"同一条口径）。
 */
public record GovAffiliations(Map<DecisionMakerId, UnitId> byDecisionMaker) {

  /**
   * ★ <b>本种类的名字</b>（<b>模块自报</b>）：范围函数注册表（{@code DecisionScopeFunctions}）与归属标签 （{@code
   * kind:ref}）都以它为准 —— 新增一个模块时，这个名字只在<b>它自己的模块里</b>拼写一次。
   */
  public static final String KIND = "gov";

  public GovAffiliations {
    if (byDecisionMaker == null) {
      throw new IllegalArgumentException("byDecisionMaker 不得为 null（空表用 Map.of()）");
    }
    Map<DecisionMakerId, UnitId> copy = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, UnitId> entry : byDecisionMaker.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("gov 归属表的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    byDecisionMaker = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /** 空表（还没有任何决策人归属任何 GOV 单位）。 */
  public static GovAffiliations empty() {
    return new GovAffiliations(Map.of());
  }

  /**
   * ★ <b>唯一的建表入口</b>：把状态里那张表按 {@code units} 裁成成立的那部分。
   *
   * @param table {@code GovState.govAffiliations()}（写入方 = {@code gov.AssignDecisionMakerGov}）
   * @param units {@code UnitState.units()}——"这个单位还在吗、还是 GOV 吗"的权威
   */
  public static GovAffiliations resolve(
      Map<DecisionMakerId, UnitId> table, Map<UnitId, Unit> units) {
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(units, "units");
    Map<DecisionMakerId, UnitId> resolved = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, UnitId> entry : table.entrySet()) {
      DecisionMakerId decisionMaker = entry.getKey();
      UnitId unitId = entry.getValue();
      if (decisionMaker == null || unitId == null) {
        continue; // 形状坏的引用：不是"归属",是坏数据 ⇒ 丢弃（fail-closed）
      }
      Unit unit = units.get(unitId);
      if (unit == null || !(unit.module().orElse(null) instanceof GovernmentFormation)) {
        continue; // ★ 权威裁定：单位不存在 / 不是 GOV ⇒ 这个决策人没有 gov 归属
      }
      resolved.put(decisionMaker, unitId);
    }
    return new GovAffiliations(resolved);
  }

  /** 该决策人的 gov 归属；**空 = 没有归属**（读不到 ⇒ 调用方必须 deny-all）。 */
  public Optional<UnitId> govUnitOf(DecisionMakerId decisionMaker) {
    Objects.requireNonNull(decisionMaker, "decisionMaker");
    return Optional.ofNullable(byDecisionMaker.get(decisionMaker));
  }

  /**
   * ★ <b>本种类的一条归属"长什么样"</b>（给人看的那句话）——由<b>本模块</b>渲染，调用方（app / 提示语）不得自己拼。
   *
   * <p>★★ <b>逐字保留收窄前的渲染</b>：用户可见文本（决策人开场提示语、工具拒因）此前嵌的是 {@code Affiliation.Gov(UnitId).toString()}
   * 的原文（{@code "政府（govUnit=g1）"}），换一份渲染就会改掉对外文本。
   */
  public static String describe(String ref) {
    return "政府（govUnit=" + ref + "）";
  }

  /**
   * ★ <b>收窄前 {@code Affiliation.Gov} 的 {@code toString()} 原文</b>（工具拒因里嵌的就是它）：调用方需要"归属类型文本"时
   * 用它，不自己拼第二份。
   */
  public static String affiliationText(String ref) {
    return "Gov[govUnit=" + ref + "]";
  }
}
