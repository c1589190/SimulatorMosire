package io.mosire.simos.gov.nation;

import io.mosire.simos.util.spi.DecisionMakerId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>nation 归属表</b>（用户 2026-10-12 裁定「<b>归属肯定归模块</b>」＋「外交是 gov 的事」）：{@code 决策人 → 国家} 这张表的
 * <b>唯一形态与唯一裁定点</b>。
 *
 * <p>★★ <b>它在本批（3c）里的角色 —— 裁定方，不是写入方（如实记，这是与约束设计书措辞的偏离）</b>：本批的红线是 <b>命令目录恒 143</b>（只改 4
 * 条命令的名字，不新增命令类型），而 {@code CommandBus} 的<b>一条命令只写一个命名空间</b> （见规划 §16.1）：绑定决策人与国家的那条既有路径是 {@code
 * ga.CreateDecisionMaker} 的 {@code affiliation} 载荷 ⇒ 它<b>只能</b>落 {@code ga}。⇒
 * 本批把<b>权威搬过来</b>（下一条），而"gov 自己写这张表"要等乙第 4 步的 {@code gov.Assign*DecisionMaker} 命令（规划 §18.2 第 4 步 /
 * §5.3 第 2 步，属批次 5 的 gov 命令面）。<b>不新增命令是本批的硬判据</b>，故不在这里造一个"没人能写"的表。
 *
 * <p>★★ <b>权威在哪（本批就成立）</b>：{@link io.mosire.simos.gov.GovState#nations()} 是"这个国家存在吗"的<b>唯一</b>权威。
 * 决策人记录里那份国家归属只剩一个<b>不透明引用</b>（{@code Affiliation.Nation(String)}，sd 侧不认识 {@link NationId}）⇒
 * <b>解释它、并裁定它是否成立的地方是这里</b>：引用解析不出 {@link NationId}、或解析出的国家不在 {@code nations} 里 ⇒ 该决策人
 * <b>没有国家归属</b>（{@link #nationOf} 返回空）。
 *
 * <p>★★★ <b>"读不到归属 ⇒ deny-all" 的落点</b>：调用方（app 的 {@code NationScope}）拿到空 ⇒ <b>四个命名空间全部 {@code
 * ResourceScope.none()}</b>，<b>不抛、不回落、不给全量</b>——世界数据的问题只降权、不报错（与 {@code NationScope} 既有的 "无匹配区域 ⇒
 * 显式 deny-all" 同一口径）；而<b>装配</b>问题（缺切片/类型不对）仍然当场抛。
 *
 * <p>★ <b>裁剪方向是 fail-closed</b>：解析不了的引用、指向不存在国家的引用，都在构造期<b>丢弃</b>（不是保留成"半个归属"）——
 * 保留它只会让读侧多一条"看起来有归属、其实够不着"的路径；丢弃后语义唯一：不在表里 = 没有归属。
 *
 * <p>★ <b>保序不可变</b>：{@code LinkedHashMap} 拷贝 + 赋值处 {@code Collections.unmodifiableMap} 冻结（<b>不用</b>
 * {@code Map.copyOf}——迭代序不是内容的纯函数）。本类型<b>不进任何状态树</b>（它是每次读现算的派生视图，与 {@code DecisionScopeFunction}
 * 的"是函数不是配置"同一条口径）。
 */
public record NationAffiliations(Map<DecisionMakerId, NationId> byDecisionMaker) {

  public NationAffiliations {
    if (byDecisionMaker == null) {
      throw new IllegalArgumentException("byDecisionMaker 不得为 null（空表用 Map.of()）");
    }
    Map<DecisionMakerId, NationId> copy = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, NationId> entry : byDecisionMaker.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("nation 归属表的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    byDecisionMaker = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /** 空表（还没有任何决策人归属任何国家）。 */
  public static NationAffiliations empty() {
    return new NationAffiliations(Map.of());
  }

  /**
   * ★ <b>唯一的建表入口</b>：把"决策人自报的不透明引用"按 {@code nations} 裁成成立的那部分。
   *
   * @param claims 决策人 → 不透明国家引用（原文；来自 {@link io.mosire.simos.gov.spi.NationAffiliationLookup}
   *     的端口实现）
   * @param nations {@code GovState.nations()}——"这个国家存在吗"的权威
   */
  public static NationAffiliations resolve(
      Map<DecisionMakerId, String> claims, Map<NationId, Nation> nations) {
    Objects.requireNonNull(claims, "claims");
    Objects.requireNonNull(nations, "nations");
    Map<DecisionMakerId, NationId> resolved = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, String> entry : claims.entrySet()) {
      DecisionMakerId decisionMaker = entry.getKey();
      String claim = entry.getValue();
      if (decisionMaker == null || claim == null || claim.isBlank()) {
        continue; // 形状坏的引用：不是"归属",是坏数据 ⇒ 丢弃（fail-closed）
      }
      NationId nationId;
      try {
        nationId = NationId.parse(claim);
      } catch (IllegalArgumentException e) {
        continue; // 同上：解释不了 ⇒ 没有归属
      }
      if (!nations.containsKey(nationId)) {
        continue; // ★ 权威裁定：gov 不认识这个国家 ⇒ 这个决策人没有国家归属
      }
      resolved.put(decisionMaker, nationId);
    }
    return new NationAffiliations(resolved);
  }

  /** 该决策人的国家归属；**空 = 没有归属**（读不到 ⇒ 调用方必须 deny-all）。 */
  public Optional<NationId> nationOf(DecisionMakerId decisionMaker) {
    Objects.requireNonNull(decisionMaker, "decisionMaker");
    return Optional.ofNullable(byDecisionMaker.get(decisionMaker));
  }
}
