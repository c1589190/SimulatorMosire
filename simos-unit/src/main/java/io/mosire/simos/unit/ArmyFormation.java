package io.mosire.simos.unit;

import com.fasterxml.jackson.annotation.JsonProperty;
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
 * role} = 兵种/职责短名（自由文本，<b>词表后置</b>）；{@code militaryDutiesOfHousehold} = 以 {@link HouseholdId}
 * 为键的军官/军职配置 （S3b，2026-10-09 用户裁定）。
 *
 * <p>★★ <b>军官家户配置的落点</b>：军官团/主官可以单独建小家户、再在本表挂 {@link MilitaryDutyOfHousehold}；基层军官也可以不单独立户，而是把
 * 配置挂在所属大家户上。键是 {@code HouseholdId}；人口真值仍在 Social 家户成员批次里，本表不含人数/战力数值。配置键必须出现在 {@link
 * Unit#households()} 里（由 {@link UnitState} 构造期统一校验，拒绝"挂一个不属于本单位的家户"）。
 *
 * <p>★★ <b>第四组件 = 军俸政策</b>（P4b，2026-10-15）：{@code militaryPayPolicy} 声明“认哪个国库当付款方、给哪些军户各发多少、
 * 多久发一次”；它是 unit 侧的唯一权威，app 每天把它派生成 P4a 的周期规则执行（政策本身不落 {@code EconomyData}）。缺键 / JSON {@code null}
 * / 旧 2 参或 3 参构造 ⇒ {@link MilitaryPayPolicy#disabled()}（旧档兼容，不静默发明军俸）。三张表的键同样必须落在 {@link
 * Unit#households()} 里（{@link UnitState} 构造期与 duties 同款具名拒）。
 *
 * <p>★ <b>不变量</b>：{@code masterGov}（Optional 本身）非 null；{@code role} 非 null、非空白；{@code
 * militaryDutiesOfHousehold} 非 null、键与值都非 null、且键 == {@code value.householdId()}（保序不可变）；{@code
 * militaryPayPolicy} 非 null（缺省归一成 {@link MilitaryPayPolicy#disabled()}）。违反一律当场抛 {@link
 * IllegalArgumentException}。
 *
 * <p>★ 它<b>不算战力</b>：Army 模块一期只做编制/隶属/视野派生（裁定 8），"一个 Army 认一个 GOV、视野 = 位置 + 半径"是 gov/army 侧的算法， 不在
 * unit 侧。
 *
 * @param masterGov 认领的 GOV 单位（未认领用 {@code Optional.empty()}；Optional 本身非 null）
 * @param role 兵种/职责短名（非 null、非空白；词表后置）
 * @param militaryDutiesOfHousehold 军官/军职家户配置（键 = 家户；保序不可变；空表 = 尚未配置）
 * @param militaryPayPolicy 军俸政策（非 null；缺省/旧档 = {@link MilitaryPayPolicy#disabled()}）
 */
public record ArmyFormation(
    Optional<UnitId> masterGov,
    String role,
    // ★ R4 改名批次：Java 侧实质化为 militaryDutiesOfHousehold，持久化 JSON 键保持旧名（旧档零迁移）。
    @JsonProperty("householdDuties")
        Map<HouseholdId, MilitaryDutyOfHousehold> militaryDutiesOfHousehold,
    // ★ P4b：第四组件；缺键/旧档归一成 disabled（canonical 构造器里处理 null）。
    @JsonProperty("militaryPayPolicy") MilitaryPayPolicy militaryPayPolicy)
    implements UnitModule {

  public ArmyFormation {
    if (masterGov == null) {
      throw new IllegalArgumentException("masterGov 不得为 null（未认领用 Optional.empty()）");
    }
    if (role == null || role.isBlank()) {
      throw new IllegalArgumentException("role 不得为空白");
    }
    if (militaryDutiesOfHousehold == null) {
      // ★ S3b：旧档/旧调用点没有该键 ⇒ Jackson 给 null；这里归一成空表（与 Unit 的 jurisdiction/module/stateDescriptions
      // 同款旧档兼容）。
      militaryDutiesOfHousehold = Map.of();
    }
    Map<HouseholdId, MilitaryDutyOfHousehold> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, MilitaryDutyOfHousehold> entry :
        militaryDutiesOfHousehold.entrySet()) {
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
    militaryDutiesOfHousehold =
        Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP）
    if (militaryPayPolicy == null) {
      // ★ P4b：旧档/缺键/JSON null ⇒ 停发态（不静默发明军俸；不给任何军户派规则）。
      militaryPayPolicy = MilitaryPayPolicy.disabled();
    }
  }

  /**
   * ★ <b>旧 3 参签名兼容</b>（S3b，2026-10-09；P4b 起第四个组件缺省）：军官家户配置有来源、军俸政策没有来源 ⇒ 政策取停发态正是唯一正确的语义。 ★
   * 它不是生产拷贝点该用的形状——拷贝点两个组件都有来源，走 canonical 4 参。
   */
  public ArmyFormation(
      Optional<UnitId> masterGov,
      String role,
      Map<HouseholdId, MilitaryDutyOfHousehold> militaryDutiesOfHousehold) {
    this(masterGov, role, militaryDutiesOfHousehold, MilitaryPayPolicy.disabled());
  }

  /**
   * ★ <b>旧 2 参签名兼容</b>（S3b，2026-10-09；P4b 起后两个组件缺省）：duties 与 policy 都取空/停发态。★ 它不是生产拷贝点该用的形状——
   * 拷贝点有来源，走 canonical 4 参。
   */
  public ArmyFormation(Optional<UnitId> masterGov, String role) {
    this(masterGov, role, Map.of(), MilitaryPayPolicy.disabled());
  }
}
