package io.mosire.simos.economy.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 制度分配函数（新经济设计 §5 逐字）：v1 两种形状，参数版本化（改参数 = 改 {@code rulesVersion}）。
 *
 * <p>★ **权重是"版本的查询口径/制度参数"，不是人的永久属性**（§5 末条）：本类型只承载参数，**不含任何结算公式** —— 怎么把剩余产品按权重分给各阶层，是 R4 的活儿（§八
 * R4 行）。
 *
 * <ul>
 *   <li>{@link Split} —— 小农 / 封建租佃 / 手工业：{@code (生产资料权重, 劳动权重)}，两者之和 = 1000‰ （{@code
 *       Split(500,500)} 小农、{@code Split(700,300)} 封建租佃、{@code Split(400,600)} 手工业）。
 *   <li>{@link WageFirst} —— 资本主义工业：工人先拿工资（{@code wagePerLaborMilli}，千分劳动单价），剩余归企业主 （{@code
 *       ownerResidual} 按商品计）。
 * </ul>
 *
 * <p>★ **sealed 多态**：裸往返不可能（同 {@code FieldDelta}/{@code Affiliation}）⇒ 类型信息以**注解钉在类型上** （{@code
 * Id.NAME} + 封闭子类集），跟着类型走、不依赖某台 mapper 上的 mixin。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = AllocationRule.Split.class, name = "split"),
  @JsonSubTypes.Type(value = AllocationRule.WageFirst.class, name = "wage_first"),
})
public sealed interface AllocationRule {

  /**
   * 按权重分配（小农 / 封建租佃 / 手工业）。
   *
   * <p>★ **不变量（构造期判）**：两个千分权重各自 {@code ≥ 0} 且**之和恰为 1000**（§6 分配口径：权重是同一 1000‰ 的两半）。
   *
   * @param meansWeightPerMille 生产资料权重（千分数）
   * @param laborWeightPerMille 劳动权重（千分数）
   */
  record Split(int meansWeightPerMille, int laborWeightPerMille) implements AllocationRule {

    public Split {
      if (meansWeightPerMille < 0) {
        throw new IllegalArgumentException(
            "AllocationRule.Split.meansWeightPerMille 不得为负: " + meansWeightPerMille);
      }
      if (laborWeightPerMille < 0) {
        throw new IllegalArgumentException(
            "AllocationRule.Split.laborWeightPerMille 不得为负: " + laborWeightPerMille);
      }
      if (meansWeightPerMille + laborWeightPerMille != 1000) {
        throw new IllegalArgumentException(
            "AllocationRule.Split 两权重之和必须为 1000: "
                + meansWeightPerMille
                + " + "
                + laborWeightPerMille);
      }
    }
  }

  /**
   * 工资优先（资本主义工业）：工人按劳动拿工资，剩余产品归企业主。
   *
   * <p>★ **不变量（构造期判）**：{@code wagePerLaborMilli ≥ 0}；{@code ownerResidual} 不得为 null、键值都不得为 null、逐值
   * {@code ≥ 0}，且**保序不可变**（冻结写在字段赋值处，见类注释）。
   *
   * @param wagePerLaborMilli 每千分劳动的工资（实物按当周期"粮值"折算，口径见 §7）
   * @param ownerResidual 企业主剩余（按商品计；可为空 map）
   */
  record WageFirst(long wagePerLaborMilli, Map<CommodityId, Long> ownerResidual)
      implements AllocationRule {

    public WageFirst {
      if (wagePerLaborMilli < 0) {
        throw new IllegalArgumentException(
            "AllocationRule.WageFirst.wagePerLaborMilli 不得为负: " + wagePerLaborMilli);
      }
      if (ownerResidual == null) {
        throw new IllegalArgumentException(
            "AllocationRule.WageFirst.ownerResidual 不得为 null（无剩余用空 map）");
      }
      Map<CommodityId, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : ownerResidual.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "AllocationRule.WageFirst.ownerResidual 的键与值都不得为 null: " + entry.getKey());
        }
        if (entry.getValue() < 0) {
          throw new IllegalArgumentException(
              "AllocationRule.WageFirst.ownerResidual 的数量不得为负：商品 "
                  + entry.getKey()
                  + " = "
                  + entry.getValue());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      // ★ 冻在赋值处（EI_EXPOSE_REP 只认它看得见的包装），且**绝不用 Map.copyOf**（迭代序不是内容的纯函数）。
      ownerResidual = Collections.unmodifiableMap(copy);
    }
  }
}
