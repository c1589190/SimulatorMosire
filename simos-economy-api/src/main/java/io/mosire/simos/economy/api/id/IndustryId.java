package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>产业 / 生产方案 ID</b>（新经济设计 §3.1；D-032 A 批口径收敛）：农业/手工业等**产业整体**的稳定身份， 同时是 D-032
 * 的<b>方案（Plan）稳定身份</b>。
 *
 * <p>聚合式设计不再逐生产单位登记：一个 {@code IndustryId} 就是"一整个产业"，周期/进度/日投入/产出函数都挂在它上面。 裸值 {@code toString()} +
 * {@code static parse} 三件套（铁律 1）。
 *
 * <p>★★ <b>D-032 A 批：本类型只承载"身份"，不承载"地点"</b>（复核裁决 §1「方案拥有<b>不绑地点</b>的稳定身份； 实例拥有自己的稳定身份 +
 * <b>显式地点</b>并引用方案」）：
 *
 * <ul>
 *   <li>构造/解析<b>只校验非空白</b>（{@link #parse} 原文未变）——值里<b>可能</b>带 {@code <kind>@<q>_<r>}
 *       形状的地点段，那是<b>生成侧约定</b>（见 {@link IndustryHexKeys}），<b>不是</b>本类型在构造期强制的 身份约束：不带地点段的方案 id
 *       同样合法（"一份方案定义被不同地点复用"由此在类型面成立）；
 *   <li>地点自 A 批起由<b>实例</b>显式携带（{@code production.contract.ProductionLocation}）； {@link
 *       IndustryHexKeys#hexKeyOf} 只服务于"按格索引既有方案"的读口，<b>不再</b>是实例地点的来源；
 *   <li>身份与地点是两件事：改名、迁移、合户都不得改写本值。
 * </ul>
 */
public record IndustryId(String value) {

  public IndustryId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("IndustryId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static IndustryId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("IndustryId 不得为空白: " + text);
    }
    return new IndustryId(text);
  }
}
