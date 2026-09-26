package io.mosire.simos.actor.api.asset;

/**
 * 生产资料种类（新经济设计 §3.1）：土地、耕牛、工具、工坊、机器、船只。**可扩展**——新增一档不改任何既有形状。
 *
 * <p>量纲：土地按**千分亩**、其余按件（见 §7）。本类型只是词表，**不含任何产出/折旧公式**（公式属 R4+）。
 */
public enum AssetKind {
  LAND,
  CATTLE,
  TOOL,
  WORKSHOP,
  MACHINE,
  SHIP
}
