package io.mosire.simos.production.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

/**
 * ★★ <b>方案可用性条件</b>（D-032 约束设计书 §4.1/§4.5；用户 U5「生产方式层面可以要求单个家户必须具有 其他模块的什么属性，例如文化模块的识字率」）。
 *
 * <p>★★ <b>求值与属性供给分离</b>（§4.5 原文）：条件只声明"要什么、门槛多少"；属性的<b>值</b>由所属领域提供、 组合根装配成 {@link
 * ExternalAttributeFacts} 后作为<b>本次调用的入参</b>传入。production <b>不感知</b>属性来自哪个模块（U5
 * 原文：「这些定义是自由的，不必让生产方式模块知道这些参数来自哪里」）。
 *
 * <p>★★ <b>缺失/未知 ⇒ fail-closed</b>（§4.5 原文）：属性事实里没有这个键、或键的类型/值读不出来 ⇒ 该条件 <b>不满足</b>，并给出<b>具名 info
 * 理由</b>（{@link #describe()}），<b>不静默放行</b>。这一条是安全侧的硬约束： 属性通路断掉时世界应当"少解锁一个方案"，而不是"所有方案都对所有人开放"。
 *
 * @see ExternalAttributeFacts
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(
      value = ProductionAvailabilityCondition.MinimumExperience.class,
      name = "min_experience"),
  @JsonSubTypes.Type(
      value = ProductionAvailabilityCondition.ExternalAttribute.class,
      name = "external_attribute"),
})
public sealed interface ProductionAvailabilityCondition {

  /** 具名理由（业务拒绝 = INFO 时的 reason 文本；稳定、可断言，不是给人读的长句）。 */
  String describe();

  /**
   * ★ <b>家户在某经验条上的最低经验</b>（与 {@link ProductionExperienceUse#unlockAtPrimaryExperience()}
   * 的区别：本条件可以要求<b>任意</b>经验条的门槛，包括共用经验条 b；解锁门槛只针对方案的专门经验条 a）。
   *
   * @param track 经验条；不得为 null
   * @param minimumExperience 最低经验（含）；不得为负
   */
  record MinimumExperience(ExperienceTrackId track, long minimumExperience)
      implements ProductionAvailabilityCondition {

    public MinimumExperience {
      Objects.requireNonNull(
          track, "ProductionAvailabilityCondition.MinimumExperience.track 不得为 null");
      if (minimumExperience < 0L) {
        throw new IllegalArgumentException(
            "ProductionAvailabilityCondition.MinimumExperience.minimumExperience 不得为负: "
                + minimumExperience);
      }
    }

    @Override
    public String describe() {
      return "experience-below-minimum:" + track.value() + ">=" + minimumExperience;
    }
  }

  /**
   * ★ <b>家户外部属性门槛</b>（如识字率）。属性的<b>来源模块不在本类型里</b>（§4.5：production 不感知来源）。
   *
   * @param key 属性键（由组合根与属性所属领域约定；非空白）。★ 未知键 ⇒ 条件不满足（fail-closed）
   * @param minimumValue 最低值（含）；不得为负
   */
  record ExternalAttribute(String key, long minimumValue)
      implements ProductionAvailabilityCondition {

    public ExternalAttribute {
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException(
            "ProductionAvailabilityCondition.ExternalAttribute.key 不得为空白");
      }
      if (minimumValue < 0L) {
        throw new IllegalArgumentException(
            "ProductionAvailabilityCondition.ExternalAttribute.minimumValue 不得为负: " + minimumValue);
      }
    }

    @Override
    public String describe() {
      return "external-attribute-below-minimum:" + key + ">=" + minimumValue;
    }
  }
}
