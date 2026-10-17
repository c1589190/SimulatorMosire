package io.mosire.simos.actor.api.asset;

/**
 * 生产资料种类（新经济设计 §3.1）：土地、耕牛、小型工具、大型工具、工坊、机器、船只。**可扩展**——新增一档不改任何既有形状。
 *
 * <p>量纲：土地按**千分亩**、其余按件（见 §7）。本类型只是词表，**不含任何产出/折旧公式**（公式属 R4+）。
 *
 * <p>★ 2026-10-17 D-032 D1 §2.7 冻结：{@code TOOL} 退役，拆为 {@link #SMALL_TOOL} / {@link #LARGE_TOOL}；
 * Alpha 阶段不留别名/兼容位。所有外部文本输入必须经 {@link #parse(String)} —— 旧 {@code TOOL} 在解析边界具名拒绝。
 */
public enum AssetKind {
  LAND,
  CATTLE,
  SMALL_TOOL,
  LARGE_TOOL,
  WORKSHOP,
  MACHINE,
  SHIP;

  /** 已退役词表值（D-032 D1 §2.7）；仅用于解析期具名拒绝，不是可解析的别名。 */
  private static final String RETIRED_TOOL = "TOOL";

  /**
   * 词表解析的**唯一拼写点**：GM 载荷/命令/seed/codec 的 {@code AssetKind} 文本都走这里。
   *
   * <p>已退役的 {@code TOOL} ⇒ {@code asset-kind-retired:TOOL}（不得静默映射成 {@link #SMALL_TOOL} 或 {@link
   * #LARGE_TOOL}）；未知值 ⇒ 具名未知值拒绝并列出合法值。
   */
  public static AssetKind parse(String text) {
    if (RETIRED_TOOL.equals(text)) {
      throw new IllegalArgumentException("asset-kind-retired:TOOL");
    }
    if (text == null) {
      throw new IllegalArgumentException("asset-kind-unknown:null");
    }
    try {
      return AssetKind.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "asset-kind-unknown:" + text + "；合法值: " + java.util.Arrays.toString(values()), e);
    }
  }
}
