package io.mosire.simos.sd.id;

/**
 * 交战状态 ID（调用方给的短名，spec §三.1/§三.3）。**实际记录在单个时间线状态里**的是 {@code CombatState}——当前阶段、当前
 * hex、参与单位、已选结局；多个跨时间的交战状态合起来 = 一场交战。
 *
 * <p>★ 执行期取代说明：spec §二.1 的 ID 表**漏列**了本类型，而 §三.1 的 {@code SdState} 与 §三.3 的 {@code CombatState}
 * 都用到它 ⇒ 以 §三 为准，补齐此 ID（记台账）。裸值 {@code toString()} + {@code static parse} 三件套；不自增、不用随机 UUID。
 */
public record CombatStateId(String value) {

  public CombatStateId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CombatStateId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static CombatStateId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CombatStateId 不得为空白: " + text);
    }
    return new CombatStateId(text);
  }
}
