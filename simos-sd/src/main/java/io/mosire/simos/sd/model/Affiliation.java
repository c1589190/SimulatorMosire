package io.mosire.simos.sd.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.unit.UnitId;

/**
 * 决策人的归属（spec §三.2）：{@code Nation(不透明国家引用)} | {@code Army(ArmyId)} | {@code Gov(UnitId)}。
 *
 * <p>★ **归属是默认权限边界，不是硬约束**（spec §二.3）：一个 Directive 的归属"可以是这个决策人能管理的东西，甚至还可以不是"。
 *
 * <p>★ **{@code Gov} 的语义（阶段 10a）**：归属到**一个 GOV 单位**（不是 Nation 那种政治实体）——实际拥有行政职能的政府 单位挂 {@code
 * GovernmentFormation}，其决策人读的是**本级直辖**（见 {@code GovScope}）。★ 它只认 {@code UnitId}、不认识 gov 模块（sd
 * 是领域下游，gov 不得反向依赖 sd）。
 *
 * <p>★★★ <b>2026-10-12 批次 3c：{@code Nation} 支<b>收窄为不透明引用</b>（用户裁定「归属肯定归模块」＋「{@code NationId} 落点 →
 * GOV模块」）</b>：{@code NationId} / {@code Nation} 已随外交整表搬进 {@code simos-gov}，而 {@code sd} <b>不得依赖
 * {@code gov}</b>（enforcer 互禁）⇒ 本支的载荷由 {@code NationId} 换成 <b>{@code String}</b>——sd
 * 只搬运这个串，<b>不解释它</b>； 解释它（{@code NationId.parse}）并裁定"这个国家存在吗"的地方是 gov 的 {@link
 * io.mosire.simos.gov.nation.NationAffiliations}。
 *
 * <ul>
 *   <li>★ <b>这是"收窄"，不是"删除"</b>：删除 {@code Affiliation} 本体、把归属表的<b>写入</b>也搬进各模块（{@code
 *       gov.Assign*DecisionMaker} 命令）属规划 §18.2 的乙第 4 步，<b>不在本批</b>（那要新增命令类型 ⇒ 目录从 143 变 144，本批红线）；
 *   <li>★ <b>读不到归属 ⇒ deny-all</b>：{@code NationScope} 拿到这个串后向 gov 要归属，<b>拿不到就四个命名空间全 deny-all</b>
 *       （不回落、不给全量）——见 {@code NationScope} 的类注；
 *   <li>★ <b>线格式变化（如实记）</b>：{@code {"@class":"nation","nationId":{"value":"FRA"}}} → {@code
 *       {"@class":"nation","nationId":"FRA"}}。Alpha 阶段不兼容旧档（AGENTS §一.11）：旧档读不了就重建世界。
 * </ul>
 *
 * <p>★ 这是 **sealed 多态类型**：裸往返不可能（与 {@code FieldDelta} 同族，见其类注释）⇒ 类型信息以**注解钉在类型上** （{@code Id.NAME}
 * + 封闭子类集），跟着类型走、不依赖某台 mapper 上的 mixin。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Affiliation.Nation.class, name = "nation"),
  @JsonSubTypes.Type(value = Affiliation.Army.class, name = "army"),
  @JsonSubTypes.Type(value = Affiliation.Gov.class, name = "gov"),
})
public sealed interface Affiliation {

  /**
   * 归属国家：<b>不透明引用</b>（{@code NationId} 的裸值原文；2026-10-12 批次 3c 收窄，见接口类注）。
   *
   * <p>★ sd **不认识**这个串的含义，也不在 sd 侧校验它指向的国家是否存在——那两件事都在 gov（裁定方 = {@code
   * io.mosire.simos.gov.nation.NationAffiliations}）。★ 这里只判"非空白"：空白串不可能是一个国家引用，属**形状**问题（ 与收窄前的
   * {@code NationId} 构造器同一条拒因）。
   */
  record Nation(String nationId) implements Affiliation {

    public Nation {
      if (nationId == null || nationId.isBlank()) {
        throw new IllegalArgumentException("nationId 不得为空白");
      }
    }
  }

  /** 归属军队。 */
  record Army(ArmyId armyId) implements Affiliation {

    public Army {
      if (armyId == null) {
        throw new IllegalArgumentException("armyId 不得为 null");
      }
    }
  }

  /**
   * 归属政府单位（阶段 10a）：{@code govUnit} 必须在 unit 切片里存在、且带 {@code GovernmentFormation}——创建期由 {@code
   * ga.CreateDecisionMaker} 具名拒，运行期由 {@code GovScope} deny-all 三命名空间兜底（fail-closed）。
   */
  record Gov(UnitId govUnit) implements Affiliation {

    public Gov {
      if (govUnit == null) {
        throw new IllegalArgumentException("govUnit 不得为 null");
      }
    }
  }
}
