package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>阶层位置</b>（理想架构 §2.2）：给定生产方式下"对生产资料、劳动、剩余的固定结构位置"。
 *
 * <p>★★ <b>它不等于社会阶层身份</b>：{@code SocialClassId}（贫农/地主/工匠…）是产业无关的人口身份；本类型是在某个 {@code ProductionMode}
 * 下、由该生产方式的阶层结构定义的位置。同一个人口可以在模式变迁后处于另一个位置（见 {@code HouseholdClassMembership}）。
 *
 * <p>★★ <b>三条结构维先落定，规则只留扩展位</b>：E1 只把 {@code relationToMeans} / {@code laborRole} / {@code
 * surplusRole} 三个维度作为权威状态落下来。设计稿 §2.2 的 {@code assetRights} / {@code laborObligation} / {@code
 * consumptionNorms} / {@code debtRules} / {@code liquidationRules} / {@code upward/downwardRules}
 * 尚未裁决具体公式，故只保留 {@link #ruleExtensions} 这个不可变扩展位 —— <b>不凭空发明复杂公式</b>，也不把未决规则散成多个同名空字段。
 *
 * <p>★ <b>扩展位语义</b>：键是规则名、值是该规则的可序列化定值文本；E2–E6 可以逐个把规则从这里迁移成有类型的字段。本类不解释它的内容；空表 = 没有额外规则。
 *
 * @param id 阶层位置稳定身份；不得为 null
 * @param modeId 所属生产方式身份；不得为 null（{@code ClassStructure} 构造期还会再判它与结构一致）
 * @param name 展示名；不得为空白
 * @param relationToMeans 与生产资料的关系；不得为 null
 * @param laborRole 劳动角色；不得为 null
 * @param surplusRole 剩余/分配角色；不得为 null
 * @param ruleExtensions 未裁决规则的扩展位；不得为 null（没有规则给空表）；键非空白、值非 null，保序不可变
 */
public record ProductionRole(
    ClassPositionId id,
    ProductionModeId modeId,
    String name,
    RelationToMeans relationToMeans,
    LaborRole laborRole,
    SurplusRole surplusRole,
    Map<String, String> ruleExtensions) {

  /** ★ 与生产资料的关系（设计稿 §2.2 的封闭词表）。 */
  public enum RelationToMeans {
    /** 占有者（own/possess）。 */
    OWNER,
    /** 经营者（实际使用/经营，但未必占有）。 */
    OPERATOR,
    /** 直接劳动者（不占有、不经营，以劳动参与生产）。 */
    DIRECT_LABORER,
    /** 混合态（自耕农/独立工匠等既经营又劳动的位置）。 */
    MIXED
  }

  /** ★ 劳动角色（组织/提供劳动的结构位置）。 */
  public enum LaborRole {
    /** 组织者（决定投入与过程）。 */
    ORGANIZER,
    /** 劳动提供者。 */
    PROVIDER,
    /** 两者兼具。 */
    BOTH,
    /** 无劳动角色（依附者等）。 */
    NONE
  }

  /** ★ 剩余/分配角色。 */
  public enum SurplusRole {
    /** 剩余索取者。 */
    SURPLUS_RECEIVER,
    /** 挣工资者。 */
    WAGE_EARNER,
    /** 自给自足（产出归自己、基本不进入工资/地租关系）。 */
    SELF_SUBSISTENCE,
    /** 被供养者。 */
    DEPENDENT
  }

  public ProductionRole {
    if (id == null) {
      throw new IllegalArgumentException("ProductionRole.id 不得为 null");
    }
    if (modeId == null) {
      throw new IllegalArgumentException("ProductionRole.modeId 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("ProductionRole.name 不得为空白");
    }
    if (relationToMeans == null) {
      throw new IllegalArgumentException("ProductionRole.relationToMeans 不得为 null");
    }
    if (laborRole == null) {
      throw new IllegalArgumentException("ProductionRole.laborRole 不得为 null");
    }
    if (surplusRole == null) {
      throw new IllegalArgumentException("ProductionRole.surplusRole 不得为 null");
    }
    if (ruleExtensions == null) {
      throw new IllegalArgumentException("ProductionRole.ruleExtensions 不得为 null（没有规则给空表）");
    }
    Map<String, String> copy = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : ruleExtensions.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException(
            "ProductionRole.ruleExtensions 的键不得为空白: " + entry.getKey());
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRole.ruleExtensions 的值不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    ruleExtensions = Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 只认它看得见的包装）
  }

  /** 六参便利构造：没有额外规则时用空扩展表（本批所有调用点都走这里）。 */
  public ProductionRole(
      ClassPositionId id,
      ProductionModeId modeId,
      String name,
      RelationToMeans relationToMeans,
      LaborRole laborRole,
      SurplusRole surplusRole) {
    this(id, modeId, name, relationToMeans, laborRole, surplusRole, Map.of());
  }
}
