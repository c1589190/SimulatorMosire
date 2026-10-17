package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.IndustryId;
import java.util.Optional;

/**
 * ★★ <b>跨地区生产技能查询口</b>（D-031 spec §6.1/§6.4；修正 C）。
 *
 * <p>它是**只读查询**的契约面，住 production 契约包：economy 适配器（Z3）与 culture（Z1）都只认这个接口， 具体实现 {@code
 * io.mosire.simos.production.skill.ProductionSkillCatalog}（非 contract 包）只由 production/app
 * 装配并**注入**。
 *
 * <p>★★ <b>未命中 ⇒ {@code Optional.empty()}</b>：不解析 {@code IndustryId} 文本、不做"键归一"猜产业种类 （修正
 * C）。缺技能只令文化贡献按 1000 中性并给具名原因，<b>不</b>使产业不可见（修正 B）。
 */
public interface ProductionSkillLookup {

  /** 该产业的跨地区技能键；未命中 ⇒ empty（不猜、不解析 id 文本）。 */
  Optional<ProductionSkillKey> keyOf(IndustryId id);
}
