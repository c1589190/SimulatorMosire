package io.mosire.simos.sd.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.NationId;

/**
 * 决策人的归属（spec §三.2）：{@code Nation(NationId)} | {@code Army(ArmyId)}。
 *
 * <p>★ **归属是默认权限边界，不是硬约束**（spec §二.3）：一个 Directive 的归属"可以是这个决策人能管理的东西，甚至还可以不是"。
 *
 * <p>★ 这是 **sealed 多态类型**：裸往返不可能（与 {@code FieldDelta} 同族，见其类注释）⇒ 类型信息以**注解钉在类型上** （{@code Id.NAME}
 * + 封闭子类集），跟着类型走、不依赖某台 mapper 上的 mixin。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Affiliation.Nation.class, name = "nation"),
  @JsonSubTypes.Type(value = Affiliation.Army.class, name = "army"),
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
}
