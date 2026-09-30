package io.mosire.simos.economy.api.id;

/**
 * 债务 ID（新经济设计 §3.3 + v2 spec §7.2）：阶层行之间"阶层 → 阶层"的**聚合债权**一条的稳定身份，归 {@code economy} 切片的债务表。
 *
 * <p>★★ **id 的三条硬要求**（v2 spec §7.2；拼写在 {@code 旧结算引擎（R3a 已删除）.debtIdOf} 这**唯一一处**）：
 *
 * <ol>
 *   <li>**确定性** —— 同一 {@code (周期, 债务人, 债权人, 商品)} 必须给出同一个 id（重放/分支可比）；
 *   <li>**不含 {@code "."}** —— 地址 {@code economy:<mapId>:debt.<id>} 由 {@code AddressParser} 在**第一个
 *       {@code "."}** 处拆开（{@code debt.<id>} ⇒ {@code Entity(kind="debt", name=<id>)}），id
 *       里带点会把名字**截断**成另一个名字 （该债务的地址从此解析不到）。故各段用 {@code "-"} 与 {@code ">"} 分隔；
 *   <li>**跨周期不同** —— 周期号在 id 里 ⇒ 新周期的借入不覆盖旧条。
 * </ol>
 *
 * <p>存量/流量分离（§3.3 末条）：债务只能由借入/赊购产生（**计息并入本金**是本批唯一允许的例外，理由见 {@code 旧结算引擎（R3a
 * 已删除）.chargeInterest}），**本金是存量**，不在 {@code FlowRow} 里冒充。 裸值 {@code toString()} + {@code static
 * parse} 三件套（铁律 1）。
 *
 * <p>★ {@link #parse} 只校验非空白、**不校验格式**（与其余 id 同款：格式的权威是产出方那唯一一处拼写）。
 */
public record DebtId(String value) {

  public DebtId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DebtId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static DebtId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DebtId 不得为空白: " + text);
    }
    return new DebtId(text);
  }
}
