package io.mosire.simos.sd.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.unit.UnitId;

/**
 * 决策人的归属（spec §三.2）：{@code Nation(NationId)} | {@code Army(ArmyId)} | {@code Gov(UnitId)}。
 *
 * <p>★ **归属是默认权限边界，不是硬约束**（spec §二.3）：一个 Directive 的归属"可以是这个决策人能管理的东西，甚至还可以不是"。
 *
 * <p>★ **{@code Gov} 的语义（阶段 10a）**：归属到**一个 GOV 单位**（不是 sd.Nation 那种政治实体）——实际拥有行政职能的政府 单位挂 {@code
 * GovernmentFormation}，其决策人读的是**本级直辖**（见 {@code GovScope}）。{@code sd.Nation} 与 {@code
 * Affiliation.Nation} 继续并存：中央行政用 Gov，政治/外交用 Nation（用户裁定 5/7）。★ 它只认 {@code UnitId}、不认识 gov 模块（sd
 * 是领域下游，gov 不得反向依赖 sd）。
 *
 * <p>★ 这是 **sealed 多态类型**：裸往返不可能（与 {@code FieldDelta} 同族，见其类注释）⇒ 类型信息以**注解钉在类型上** （{@code Id.NAME}
 * + 封闭子类集），跟着类型走、不依赖某台 mapper 上的 mixin。旧档没有 {@code "gov"} 子类型不影响读取（新增子类型是纯扩展）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Affiliation.Nation.class, name = "nation"),
  @JsonSubTypes.Type(value = Affiliation.Army.class, name = "army"),
  @JsonSubTypes.Type(value = Affiliation.Gov.class, name = "gov"),
})
public sealed interface Affiliation {

  /** 归属国家。 */
  record Nation(NationId nationId) implements Affiliation {

    public Nation {
      if (nationId == null) {
        throw new IllegalArgumentException("nationId 不得为 null");
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
   * sd.CreateDecisionMaker} 具名拒，运行期由 {@code GovScope} deny-all 三命名空间兜底（fail-closed）。
   */
  record Gov(UnitId govUnit) implements Affiliation {

    public Gov {
      if (govUnit == null) {
        throw new IllegalArgumentException("govUnit 不得为 null");
      }
    }
  }
}
