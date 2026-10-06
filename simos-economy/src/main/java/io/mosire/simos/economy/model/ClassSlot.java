package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.SocialClassId;

/**
 * 阶层槽位：一个产业内**该制度允许的一个阶层**（贫农 / 中农 / 地主 …）——即"制度定义可出现的角色"。
 *
 * <p>★★ **一个槽位不是一份人口**（2026-09-25 修正，取代 R1 首版把两者混为一谈的写法）：
 *
 * <ul>
 *   <li>本记录只持**制度参数**：该阶层的**劳动投入率上限**（贫农 950‰ / 中农 900‰ / 富农 750‰ / 地主 100‰）。
 *   <li>**人口与占比不在这里**：它们住在 {@code HouseholdEconomy.population}（该产业的阶层行），比例是**观测** （Σ行 =
 *       该产业人口），不再另存一份 —— 一条真相，避免两处漂移。
 * </ul>
 *
 * 用户资料的"§十八 阶层人口比例（贫农 45%…）"因此是**由行派生**的口径，不是本记录的字段。
 *
 * <p>★ **量纲**：千分数（§7）。{@code 0 ≤ laborParticipationPerMille ≤ 1000}（0 = 不劳动者，如纯收租地主）。
 *
 * <p>★ **本类无公式**：实际投入 = 有效劳动 × 投入率，结算在 R2+ 的服务层。
 *
 * @param id 稳定身份（同一产业内唯一）
 * @param name 展示名（贫农 / 中农 / 地主…）
 * @param laborParticipationPerMille 该阶层的劳动投入率（千分），∈ [0, 1000]
 */
public record ClassSlot(SocialClassId id, String name, int laborParticipationPerMille) {

  public ClassSlot {
    if (id == null) {
      throw new IllegalArgumentException("ClassSlot.id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("ClassSlot.name 不得为空白");
    }
    if (laborParticipationPerMille < 0 || laborParticipationPerMille > 1000) {
      throw new IllegalArgumentException(
          "ClassSlot.laborParticipationPerMille 必须 ∈ [0, 1000]: " + laborParticipationPerMille);
    }
  }
}
