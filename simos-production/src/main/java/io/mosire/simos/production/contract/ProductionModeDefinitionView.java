package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.ProductionModeId;

/**
 * ★★ <b>生产方式定义的公开契约视图</b>（D-031 spec §6.1；修正①（控制方）保留真实既有 {@code version}）。
 *
 * <p>它是 {@code io.mosire.simos.economy.model.ProductionMode} 的只读定义面：稳定 ID + 名称 + 版本 + 绑定的阶层结构。
 * <b>不伪称</b> Industry 与 Mode 有一一映射（两者是各自独立的稳定 ID），也不把运行实例混进来。
 *
 * <p>★ {@code version} 是真实既有字段（源类型构造期判 ≥ 1），不是本视图新造。
 *
 * @param id 生产方式稳定身份
 * @param name 展示名；非空白
 * @param version 规则版本；≥ 1
 * @param classStructureId 本生产方式绑定的阶层结构身份
 */
public record ProductionModeDefinitionView(
    ProductionModeId id, String name, int version, ClassStructureId classStructureId) {

  public ProductionModeDefinitionView {
    if (id == null) {
      throw new IllegalArgumentException("ProductionModeDefinitionView.id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("ProductionModeDefinitionView.name 不得为空白");
    }
    if (version < 1) {
      throw new IllegalArgumentException("ProductionModeDefinitionView.version 必须 ≥ 1: " + version);
    }
    if (classStructureId == null) {
      throw new IllegalArgumentException("ProductionModeDefinitionView.classStructureId 不得为 null");
    }
  }
}
