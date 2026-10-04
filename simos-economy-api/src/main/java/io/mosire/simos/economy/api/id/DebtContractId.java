package io.mosire.simos.economy.api.id;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.social.api.id.HouseholdId;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/**
 * ★★ <b>连续债务合同的稳定身份</b>（E4a；理想架构 §2.7/§3.3）：{@code (debtor, creditor, unit, terms)}
 * 的确定性纯函数。同一四元组**跨周期恒同 id**（连续欠账），不同 unit 或不同 terms **必然不同 id** （不允许静默合并）。
 *
 * <p>★★ <b>三条硬要求</b>（与 {@link DebtId} 同族）：
 *
 * <ol>
 *   <li><b>确定性</b>：{@code idOf} 是纯函数（无计数器、无时间、无迭代序）⇒ 重放/分支可比；
 *   <li><b>不含 {@code "."}</b>：四段原始串先做 UTF-8 十六进制转义、再用 {@code "-"} 连接 ⇒ 最终串只含 {@code [0-9a-f-]}，地址
 *       {@code economy:<mapId>:debt.<id>} 不会被第一个点截断；
 *   <li><b>单射</b>：段内十六进制不含 {@code "-"}，段间分隔唯一 ⇒ 四元组不同则 id 必不同（不是哈希近似）。
 * </ol>
 *
 * <pre>
 * debtc-&lt;hex(debtor)&gt;-&lt;hex(creditor)&gt;-&lt;hex(unit.key())&gt;-&lt;hex(terms.identityToken())
 * </pre>
 *
 * <p>★ {@link #parse(String)} 只校验非空白、**不校验格式**（与其余 opaque id 同款：格式权威只在 {@link #idOf} 这一处拼写点）。
 */
public record DebtContractId(String value) {

  public DebtContractId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DebtContractId 不得为空白");
    }
  }

  @Override
  @JsonValue
  public String toString() {
    return value;
  }

  /** 只校验非空白（格式的权威是 {@link #idOf}；旧档 opaque 串也走这里读入）。 */
  @JsonCreator
  public static DebtContractId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DebtContractId 不得为空白: " + text);
    }
    return new DebtContractId(text);
  }

  /** ★★ <b>唯一拼写点</b>：由合同身份四元组派生稳定 id。参数都必须非 null；空/坏值由各类型自己的构造期守卫拦下。 */
  public static DebtContractId idOf(
      HouseholdId debtor, HouseholdId creditor, DebtUnit unit, DebtTerms terms) {
    Objects.requireNonNull(debtor, "DebtContractId.idOf 的 debtor 不得为 null");
    Objects.requireNonNull(creditor, "DebtContractId.idOf 的 creditor 不得为 null");
    Objects.requireNonNull(unit, "DebtContractId.idOf 的 unit 不得为 null");
    Objects.requireNonNull(terms, "DebtContractId.idOf 的 terms 不得为 null");
    return new DebtContractId(
        "debtc-"
            + hex(debtor.value())
            + "-"
            + hex(creditor.value())
            + "-"
            + hex(unit.key())
            + "-"
            + hex(terms.identityToken()));
  }

  /** UTF-8 字节的十六进制（再被 {@code "-"} 分隔 ⇒ 段内无分隔符，编码单射）。 */
  private static String hex(String value) {
    return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
  }
}
