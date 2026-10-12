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
 * <p>★★ <b>它在本批（3d-1）里的角色 —— 裁定方，不是写入方（如实记，与 3c 的 {@code NationAffiliations} 逐条同构）</b>：本批的红线是
 * <b>命令目录恒 143</b>（只改 2 条命令的名字，不新增命令类型），而 {@code CommandBus} 的<b>一条命令只写一个命名空间</b>（见规划
 * §16.1）：绑定决策人与军队的那条既有路径是 {@code ga.CreateDecisionMaker} 的 {@code affiliation} 载荷 ⇒ 它<b>只能</b>落
 * {@code ga}。⇒ 本批把<b>权威搬过来</b>（下一条），而"army 自己写这张表"要等乙第 4 步的 {@code army.Assign*DecisionMaker} 命令（规划
 * §18.2 第 4 步，属批次 5）。<b>不新增命令是本批的硬判据</b>，故不在这里造一个"没人能写"的表。
 *
 * <p>★★ <b>权威在哪（本批就成立）</b>：{@link ArmyData#armies()} 是"这个军队存在吗"的<b>唯一</b>权威。决策人记录里那份军队归属只剩一个
 * <b>不透明引用</b>（{@code Affiliation.Army(String)}，sd 侧不认识 {@link ArmyId}）⇒ <b>解释它、并裁定它是否成立的地方是这里</b>：
 * 引用解析不出 {@link ArmyId}、或解析出的军队不在 {@code armies} 里 ⇒ 该决策人 <b>没有军队归属</b>（{@link #armyOf} 返回空）。
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
   * ★ <b>唯一的建表入口</b>：把"决策人自报的不透明引用"按 {@code armies} 裁成成立的那部分。
   *
   * @param claims 决策人 → 不透明军队引用（原文；来自 {@link io.mosire.simos.army.spi.ArmyAffiliationPort} 的端口实现）
   * @param armies {@code ArmyData.armies()}——"这个军队存在吗"的权威
   */
  public static ArmyAffiliations resolve(
      Map<DecisionMakerId, String> claims, Map<ArmyId, Army> armies) {
    Objects.requireNonNull(claims, "claims");
    Objects.requireNonNull(armies, "armies");
    Map<DecisionMakerId, ArmyId> resolved = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, String> entry : claims.entrySet()) {
      DecisionMakerId decisionMaker = entry.getKey();
      String claim = entry.getValue();
      if (decisionMaker == null || claim == null || claim.isBlank()) {
        continue; // 形状坏的引用：不是"归属",是坏数据 ⇒ 丢弃（fail-closed）
      }
      ArmyId armyId;
      try {
        armyId = ArmyId.parse(claim);
      } catch (IllegalArgumentException e) {
        continue; // 同上：解释不了 ⇒ 没有归属
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
}
