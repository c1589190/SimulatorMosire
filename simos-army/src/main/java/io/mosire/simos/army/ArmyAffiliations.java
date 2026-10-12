package io.mosire.simos.army;

import io.mosire.simos.util.spi.DecisionMakerId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>army 归属表</b>（用户 2026-10-12 裁定「<b>归属肯定归模块</b>」＋「<b>战斗模块，包括阶段之类的，归 Army 模块管</b>」）： {@code 决策人
 * → 军队} 这张表的 <b>唯一形态与唯一裁定点</b>。
 *
 * <p>★★★ <b>乙收尾（本批）起它是"写入方"</b>（3d-1 时只是裁定方 —— 那张表的写口当时仍挂在 {@code ga.CreateDecisionMaker} 的 {@code
 * affiliation} 载荷上，见规划 §18.2 的 D1 偏离）：表本体住 {@link ArmyData#armyAffiliations()}，<b>写口 = {@code
 * army.AssignDecisionMakerArmy}</b>（用户原话「<b>同样搞工单</b>」）。★ 这也正是 3d-1 台账 §D.2 记下的那条 「乙第 4 步在 army 自己的
 * {@code army.Assign*DecisionMaker} 命令里把存在性校验补回」——本批兑现。
 *
 * <p>★★ <b>权威在哪</b>：{@link ArmyData#armies()} 是"这个军队存在吗"的<b>唯一</b>权威。归属表里的值已经是 {@link ArmyId}
 * （写入方在命令期已具名拒过不存在的军队）⇒ 本类的裁定只剩"那个军队后来还在不在"这一条：不在 {@code armies} 里 ⇒ 该决策人 <b>没有军队归属</b>（{@link
 * #armyOf} 返回空）。
 *
 * <p>★★★ <b>"读不到归属 ⇒ deny-all" 的落点</b>：调用方（app 的 {@code ArmyScope}）拿到空 ⇒ <b>四个命名空间全部 {@code
 * ResourceScope.none()}</b>，<b>不抛、不回落、不给全量</b>——世界数据的问题只降权、不报错（与 {@code NationScope} / {@code
 * GovScope} 的"无匹配目标 ⇒ 显式 deny-all"同一口径）；而<b>装配</b>问题（缺切片/类型不对）仍然当场抛。
 *
 * <p>★ <b>裁剪方向是 fail-closed</b>：解析不了的引用、指向不存在军队的引用，都在构造期<b>丢弃</b>（不是保留成"半个归属"）——
 * 保留它只会让读侧多一条"看起来有归属、其实够不着"的路径；丢弃后语义唯一：不在表里 = 没有归属。
 *
 * <p>★ <b>保序不可变</b>：{@code LinkedHashMap} 拷贝 + 赋值处 {@code Collections.unmodifiableMap} 冻结（<b>不用</b>
 * {@code Map.copyOf}——迭代序不是内容的纯函数）。本类型<b>不进任何状态树</b>（它是每次读现算的派生视图，与 {@code DecisionScopeFunction}
 * 的"是函数不是配置"同一条口径）。
 */
public record ArmyAffiliations(Map<DecisionMakerId, ArmyId> byDecisionMaker) {

  /**
   * ★ <b>本种类的名字</b>（<b>模块自报</b>）：范围函数注册表（{@code DecisionScopeFunctions}）与归属标签 （{@code
   * kind:ref}）都以它为准 —— 新增一个模块时，这个名字只在<b>它自己的模块里</b>拼写一次。
   */
  public static final String KIND = "army";

  public ArmyAffiliations {
    if (byDecisionMaker == null) {
      throw new IllegalArgumentException("byDecisionMaker 不得为 null（空表用 Map.of()）");
    }
    Map<DecisionMakerId, ArmyId> copy = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, ArmyId> entry : byDecisionMaker.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("army 归属表的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    byDecisionMaker = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /** 空表（还没有任何决策人归属任何军队）。 */
  public static ArmyAffiliations empty() {
    return new ArmyAffiliations(Map.of());
  }

  /**
   * ★ <b>唯一的建表入口</b>：把状态里那张归属表按 {@code armies} 裁成成立的那部分。
   *
   * @param table {@code ArmyData.armyAffiliations()}（写入方 = {@code army.AssignDecisionMakerArmy}）
   * @param armies {@code ArmyData.armies()}——"这个军队存在吗"的权威
   */
  public static ArmyAffiliations resolve(
      Map<DecisionMakerId, ArmyId> table, Map<ArmyId, Army> armies) {
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(armies, "armies");
    Map<DecisionMakerId, ArmyId> resolved = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, ArmyId> entry : table.entrySet()) {
      DecisionMakerId decisionMaker = entry.getKey();
      ArmyId armyId = entry.getValue();
      if (decisionMaker == null || armyId == null) {
        continue; // 形状坏的引用：不是"归属",是坏数据 ⇒ 丢弃（fail-closed）
      }
      if (!armies.containsKey(armyId)) {
        continue; // ★ 权威裁定：army 不认识这个军队 ⇒ 这个决策人没有军队归属
      }
      resolved.put(decisionMaker, armyId);
    }
    return new ArmyAffiliations(resolved);
  }

  /** 该决策人的军队归属；**空 = 没有归属**（读不到 ⇒ 调用方必须 deny-all）。 */
  public Optional<ArmyId> armyOf(DecisionMakerId decisionMaker) {
    Objects.requireNonNull(decisionMaker, "decisionMaker");
    return Optional.ofNullable(byDecisionMaker.get(decisionMaker));
  }

  /**
   * ★ <b>本种类的一条归属"长什么样"</b>（给人看的那句话）——由<b>本模块</b>渲染，调用方（app / 提示语）不得自己拼。
   *
   * <p>★★ <b>逐字保留收窄前的渲染</b>：用户可见文本（决策人开场提示语）此前嵌的是 {@code Affiliation.Army(String)} 那一支的原文（{@code
   * "军队（armyId=a1）"}），换一份渲染就会改掉对外文本。
   */
  public static String describe(String ref) {
    return "军队（armyId=" + ref + "）";
  }

  /**
   * ★ <b>收窄前 {@code Affiliation.Army} 的 {@code toString()} 原文</b>（工具拒因里嵌的就是它）：调用方需要"归属类型文本"
   * 时用它，不自己拼第二份。
   */
  public static String affiliationText(String ref) {
    return "Army[armyId=" + ref + "]";
  }
}
