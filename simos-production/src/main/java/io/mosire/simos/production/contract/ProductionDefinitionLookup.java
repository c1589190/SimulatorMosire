package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>生产定义的跨模块只读查询口</b>（D-031 P1 spec §3.1/§3.2）。
 *
 * <p>★★ <b>定义权威在 production</b>：本接口由 {@code ProductionData} 与 {@code ProductionSnapshot} 实现；
 * economy / culture / app 只经本契约读定义，<b>不</b> import 生产实现包。
 *
 * <p>★★ <b>技能可缺不挡可见性</b>（修正 B）：{@link #industry(IndustryId)} 只要真实定义存在就返回完整视图； {@link
 * IndustryDefinitionView#skill()} 可以是 {@code Optional.empty()}，调用方按中性处理，<b>不得</b>因此把产业判为不存在。
 *
 * <p>★ <b>全表迭代</b>：{@link #industries()} / {@link #modes()} 返回规范保序的不可变视图（结算与候选需要枚举）。
 *
 * <p>★ 运行实例字段（operator/progressDays/capacity/cycleLaborMilli/cycleInputUsedMilli）不进任何视图。
 */
public interface ProductionDefinitionLookup {

  /** 真实产业定义存在即返回完整视图；不存在才 empty（技能可缺，见类注）。 */
  Optional<IndustryDefinitionView> industry(IndustryId id);

  /** 生产方式定义。 */
  Optional<ProductionModeDefinitionView> mode(ProductionModeId id);

  /** 规范保序的全部产业定义（不可变视图）。 */
  Map<IndustryId, IndustryDefinitionView> industries();

  /** 规范保序的全部生产方式定义（不可变视图）。 */
  Map<ProductionModeId, ProductionModeDefinitionView> modes();

  /**
   * 产业派生配方（当前没有独立 {@code RecipeId} 权威目录，故按产业取）。
   *
   * <p>★ 不存在 ⇒ {@code Optional.empty()}；<b>不</b>制造 {@code RecipeId}↔{@code IndustryId} 一对一映射。
   */
  default Optional<ProductionRecipe> recipeOf(IndustryId id) {
    return industry(id).map(IndustryDefinitionView::recipe);
  }
}
