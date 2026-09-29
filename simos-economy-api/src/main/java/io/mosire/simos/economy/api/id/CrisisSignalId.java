package io.mosire.simos.economy.api.id;

import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ <b>hex 危机信号（{@code HexCrisisSignal}）的稳定身份</b>（理想架构 §2.8/§7.3；E5a）：一条"某格在某一天出现某种危机"
 * 的信号记录。身份 = {@code (hex, kind)} 的纯函数 —— <b>同 hex 同 kind 只保留最新一条</b>（覆盖即更新，读口可回溯
 * day/severity/evidence）。
 *
 * <p>★ 规范串（{@link #toString()} / {@link #parse(String)} 互逆）：{@code crisis-<q>_<r>-<KIND>}，例如
 * {@code crisis-0_0-FOOD}。<b>不含 {@code "."}</b>：地址会被 {@code AddressParser} 在第一个点处截断 ⇒
 * 构造期当场抛。
 *
 * <p>★★ <b>为什么 {@link #idOf(HexCoord, String)} 收 {@code String kind} 而不是模型里的枚举</b>：本类型住在
 * {@code simos-economy-api}（切片最底层契约），而 {@code HexCrisisSignal.Kind} 住在 {@code simos-economy}
 * 的 {@code model} 包 —— 依赖方向只允许 economy → economy-api，反向引用会形成环。故 {@code idOf} 只认"种类名"这一个
 * 稳定文本段（{@link #idOf(HexCoord, String)} 是唯一拼写点）；模型侧的调用点传 {@code signal.kind().name()}。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record CrisisSignalId(String value) {

  /** 新 id 的固定前缀（{@link #idOf(HexCoord, String)} 的唯一拼写点）。 */
  public static final String PREFIX = "crisis-";

  public CrisisSignalId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CrisisSignalId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("CrisisSignalId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束（格式的权威在 {@link #idOf(HexCoord, String)}）。 */
  public static CrisisSignalId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CrisisSignalId 不得为空白: " + text);
    }
    return new CrisisSignalId(text);
  }

  /**
   * ★★ <b>新 id 的唯一拼写点</b>：{@code crisis-<q>_<r>-<kind>}（不含 {@code "."}）。
   *
   * <p>★ {@code kind} 是 {@code HexCrisisSignal.Kind} 的稳定名（本包不能依赖 model，见类注）。同一
   * {@code (hex, kind)} 恒得同一个 id ⇒ 同键覆盖即更新。
   *
   * @param hex 危机发生的格；不得为 null
   * @param kind 危机种类名；不得为空白
   */
  public static CrisisSignalId idOf(HexCoord hex, String kind) {
    if (hex == null) {
      throw new IllegalArgumentException("CrisisSignalId.idOf 的 hex 不得为 null");
    }
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("CrisisSignalId.idOf 的 kind 不得为空白");
    }
    String value = PREFIX + hex + "-" + kind;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("CrisisSignal id 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new CrisisSignalId(value);
  }
}
