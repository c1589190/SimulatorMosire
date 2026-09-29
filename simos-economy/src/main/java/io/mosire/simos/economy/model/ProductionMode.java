package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.ProductionModeId;

/**
 * ★★ <b>生产方式</b>（理想架构 §2.1）：经济的第一性结构。它把"生产方式身份 + 版本"与"本生产方式绑定的阶层结构"显式连起来， 后续的生产组织、资产规则、
 * 货币规则与模式变迁都以它为主语。
 *
 * <p>★★ <b>E1 的范围边界</b>：本记录只建立身份与绑定关系。设计稿 §2.1 列出的 {@code productionTemplates} / {@code
 * defaultRelations} / {@code assetRules} / {@code moneyRules} / {@code consumptionNorms} / {@code
 * transitionRules} 等规则字段留到 E2–E6 按各自阶段落地；E1 <b>不预置公式、不把未裁决的规则写进状态</b>。
 *
 * <p>★ <b>不可变</b>：record 的组件全是不可变值（ID、文本、整数）；无集合字段，无需额外冻结。
 *
 * @param id 生产方式稳定身份；不得为 null
 * @param name 展示名；不得为空白
 * @param version 规则版本；必须 ≥ 1（同一 id 的新版本是显式修订）
 * @param classStructureId 本生产方式绑定的阶层结构身份；不得为 null（结构本体住 {@link ClassStructure}）
 */
public record ProductionMode(
    ProductionModeId id, String name, int version, ClassStructureId classStructureId) {

  public ProductionMode {
    if (id == null) {
      throw new IllegalArgumentException("ProductionMode.id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("ProductionMode.name 不得为空白");
    }
    if (version < 1) {
      throw new IllegalArgumentException("ProductionMode.version 必须 ≥ 1: " + version);
    }
    if (classStructureId == null) {
      throw new IllegalArgumentException("ProductionMode.classStructureId 不得为 null");
    }
  }
}
