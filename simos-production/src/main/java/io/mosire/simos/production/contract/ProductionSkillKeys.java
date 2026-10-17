package io.mosire.simos.production.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>P0 声明的六种播种产业 kind → 跨地区稳定技能键的唯一拼写点</b>（D-031 P1 spec §9.2）。
 *
 * <p>★★ <b>为什么必须显式写进权威定义</b>：P1 起技能权威 = {@link IndustryDefinitionView#skill()}。创世的六个声明 kind（{@code
 * farm/weave/craft/trade/mint/office}）的技能键<b>不解析 {@code IndustryId} 猜</b>，而是在种子生成点按本表 显式写入 —— 跨格同
 * kind 得同技能键。自定义/未知 kind 才留 {@code Optional.empty()}（中性）。
 *
 * <p>★ 本类只承载映射常量，不含行为/公式。
 */
public final class ProductionSkillKeys {

  /** 跨地区稳定技能键：农业。 */
  public static final ProductionSkillKey ARABLE_FARMING = new ProductionSkillKey("arable-farming");

  /** 跨地区稳定技能键：纺织。 */
  public static final ProductionSkillKey WEAVING = new ProductionSkillKey("weaving");

  /** 跨地区稳定技能键：手工业。 */
  public static final ProductionSkillKey CRAFTING = new ProductionSkillKey("crafting");

  /** 跨地区稳定技能键：跑商/贸易。 */
  public static final ProductionSkillKey TRADING = new ProductionSkillKey("trading");

  /** 跨地区稳定技能键：铸币。 */
  public static final ProductionSkillKey MINTING = new ProductionSkillKey("minting");

  /** 跨地区稳定技能键：行政/官署。 */
  public static final ProductionSkillKey ADMINISTRATION = new ProductionSkillKey("administration");

  private static final Map<String, ProductionSkillKey> BY_KIND = byKind();

  private static Map<String, ProductionSkillKey> byKind() {
    Map<String, ProductionSkillKey> map = new LinkedHashMap<>();
    map.put("farm", ARABLE_FARMING);
    map.put("weave", WEAVING);
    map.put("craft", CRAFTING);
    map.put("trade", TRADING);
    map.put("mint", MINTING);
    map.put("office", ADMINISTRATION);
    return java.util.Collections.unmodifiableMap(map);
  }

  private ProductionSkillKeys() {}

  /**
   * 六个声明 kind 之一的稳定技能键；未知 kind（自定义产业）⇒ {@code Optional.empty()}（中性）。
   *
   * <p>★ 只做<b>精确 kind 文本匹配</b>，不解析 {@code IndustryId}、不猜种类。
   */
  public static Optional<ProductionSkillKey> skillOfKind(String kind) {
    if (kind == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(BY_KIND.get(kind));
  }
}
