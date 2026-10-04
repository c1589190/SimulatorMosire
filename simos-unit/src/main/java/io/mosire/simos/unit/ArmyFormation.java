package io.mosire.simos.unit;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 军队编制（阶段 9，2026-09-30 裁定 3/8；S3b 2026-10-09 扩军官家户配置）：被打上军队模块负责标签的单位挂它——一期只记"认哪个 GOV 当主子 + 职责短名 +
 * 军官/军职家户配置"。
 *
 * <p>★ <b>字段语义</b>：{@code masterGov} = 认领的 GOV 单位（指挥/视野的隶属关系，未认领用 {@code Optional.empty()}）；{@code
 * role} = 兵种/职责短名（自由文本，<b>词表后置</b>）；{@code householdDuties} = 以 {@link HouseholdId} 为键的军官/军职配置
 * （S3b，2026-10-09 用户裁定）。
 *
 * <p>★★ <b>军官家户配置的落点</b>：军官团/主官可以单独建小家户、再在本表挂 {@link MilitaryHouseholdDuty}；基层军官也可以不单独立户，而是把
 * 配置挂在所属大家户上。键是 {@code HouseholdId}；人口真值仍在 Social 家户成员批次里，本表不含人数/战力数值。配置键必须出现在 {@link
 * Unit#households()} 里（由 {@link UnitState} 构造期统一校验，拒绝"挂一个不属于本单位的家户"）。
 *
 * <p>★ <b>不变量</b>：{@code masterGov}（Optional 本身）非 null；{@code role} 非 null、非空白；{@code
 * householdDuties} 非 null、键与值都非 null、且键 == {@code value.householdId()}（保序不可变）。违反一律当场抛 {@link
 * IllegalArgumentException}。
 *
 * <p>★ 它<b>不算战力</b>：Army 模块一期只做编制/隶属/视野派生（裁定 8），"一个 Army 认一个 GOV、视野 = 位置 + 半径"是 gov/army 侧的算法， 不在
 * unit 侧。
 *
 * @param masterGov 认领的 GOV 单位（未认领用 {@code Optional.empty()}；Optional 本身非 null）
 * @param role 兵种/职责短名（非 null、非空白；词表后置）
 * @param householdDuties 军官/军职家户配置（键 = 家户；保序不可变；空表 = 尚未配置）
 */
public record ArmyFormation(
    Optional<UnitId> masterGov,
    String role,
    Map<HouseholdId, MilitaryHouseholdDuty> householdDuties)
    implements UnitModule {

  public ArmyFormation {
    if (masterGov == null) {
      throw new IllegalArgumentException("masterGov 不得为 null（未认领用 Optional.empty()）");
    }
    if (role == null || role.isBlank()) {
      throw new IllegalArgumentException("role 不得为空白");
    }
    if (householdDuties == null) {
      // ★ S3b：旧档/旧调用点没有该键 ⇒ Jackson 给 null；这里归一成空表（与 GovFormation.households 同款旧档兼容）。
      householdDuties = Map.of();
    }
    Map<HouseholdId, MilitaryHouseholdDuty> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, MilitaryHouseholdDuty> entry : householdDuties.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("householdDuties 的键与值都不得为 null");
      }
      if (!entry.getKey().equals(entry.getValue().householdId())) {
        throw new IllegalArgumentException(
            "householdDuties 的键必须等于配置的 householdId: 键="
                + entry.getKey()
                + " 值="
                + entry.getValue().householdId());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    householdDuties = Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP）
  }

  /**
   * ★ <b>旧 2 参签名兼容</b>（S3b，2026-10-09）：军官家户配置对旧调用点没有来源 ⇒ 取空表正是唯一正确的语义。★ 它不是生产拷贝点该用的形状——
   * 拷贝点有来源（{@code army.householdDuties()}），走 canonical 3 参。
   */
  public ArmyFormation(Optional<UnitId> masterGov, String role) {
    this(masterGov, role, Map.of());
  }
}
