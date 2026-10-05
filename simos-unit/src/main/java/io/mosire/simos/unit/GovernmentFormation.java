package io.mosire.simos.unit;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 政府编制（阶段 9，2026-09-30 裁定 3/5/7）：实际拥有行政职能的政府单位挂它——中央与地方都走这个形状（中央也是 GOV 单位）。
 *
 * <p>★ <b>字段语义</b>：
 *
 * <ul>
 *   <li>{@code staff}：各行政角色的在编人数（{@link StaffRole} → 人）；空 map = 只有编制标签、尚无人员。★ <b>兼容字段</b>（S3b）： 一旦
 *       {@code governmentPostsOfHousehold} 非空，staff 只是那些领导家户人口的**投影**（app 组合根现算校核），不得再当第二本权威；
 *   <li>{@code governmentPostsOfHousehold}（S3b，2026-10-09 用户裁定）：以 {@link HouseholdId} 为键的领导层家户配置（{@link
 *       GovernmentPostOfHousehold}）。领导层可单独建小家户，再在本表挂配置；本表不含人数——人数从家户成员现算；
 *   <li>{@code policy}：编制政策（定额/上限/退休待遇）；
 *   <li>{@code superiorGov}：上级 GOV 单位 id——中央为空；多数省直接指中央；
 *   <li>{@code level}：层级（中央 / 省）。
 * </ul>
 *
 * <p>★★ <b>2026-10-09 唯一列表裁定</b>：本类型<b>不再有 {@code households}</b> 组件（曾与 {@code Unit.households} 重复）。
 * “谁在这个 Unit 里”的唯一实质列表是 {@link Unit#households()}；政府家户（{@code hh-gov-<unitId>}）也必须出现在
 * 它里面——由 {@link UnitOperations#setGovernmentFormation} 同批写入，由 {@link UnitState} 构造期判“GOV 单位恰一个、
 * 且逐字等于 {@code hh-gov-<unitId>}”。{@code governmentPostsOfHousehold} 只是角色配置，键必须 ⊆ {@code Unit.households}。
 *
 * <p>★ <b>不变量</b>：{@code staff} 非 null、键非 null、值非 null 且 ≥ 0，保序不可变；{@code governmentPostsOfHousehold} 的键与值都非
 * null、且键 == {@code value.householdId()}；{@code policy}、{@code superiorGov}（Optional 本身）、{@code level} 都不得为
 * null；违反一律当场抛 {@link IllegalArgumentException}。 ★ <b>不做跨字段校验</b>（如“中央必须没有上级”）——那是 GOV
 * 侧创建期的事，本类型只保证自己的字段合法（照 {@code Jurisdiction} 只守自己一亩地的先例）。配置键是否属于本单位的 {@code households} 由
 * {@link UnitState} 构造期统一校验。
 *
 * <p>★ <b>它不含任何力量/效率数值</b>：行政力、加成、税收覆盖全部在 {@code simos-gov} 里算（用户裁定 1/2）。
 *
 * <p>★★ <b>S3a/S3b 的拷贝纪律</b>：所有只改某个组件的重建点（{@code
 * UnitOperations.withGovernmentPolicy/withGovernmentSuperior/withGovernmentStaff}）都必须原样带过 {@code governmentFormation.governmentPostsOfHousehold()}——漏传 =
 * 静默丢领导配置，本仓最贵教训的共同形态。
 *
 * @param staff 各行政角色在编人数（兼容字段/家户投影；保序不可变；键非 null，值 ≥ 0）
 * @param governmentPostsOfHousehold 领导层家户配置（键 = 家户；保序不可变；旧档缺键 ⇒ 空表）
 * @param policy 编制政策（非 null）
 * @param superiorGov 上级 GOV（中央/无上级用 {@code Optional.empty()}；Optional 本身非 null）
 * @param level 层级（非 null）
 */
public record GovernmentFormation(
    Map<StaffRole, Long> staff,
    // ★ R4 改名批次：Java 侧实质化为 governmentPostsOfHousehold，持久化 JSON 键保持旧名（旧档零迁移）。
    @JsonProperty("householdPosts") Map<HouseholdId, GovernmentPostOfHousehold> governmentPostsOfHousehold,
    OfficePolicy policy,
    Optional<UnitId> superiorGov,
    GovernmentLevel level)
    implements UnitModule {

  public GovernmentFormation {
    if (staff == null) {
      throw new IllegalArgumentException("staff 不得为 null（无人员用 Map.of()）");
    }
    Map<StaffRole, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<StaffRole, Long> entry : staff.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("staff 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "staff 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的 Collections.unmodifiableMap）。
    staff = Collections.unmodifiableMap(copy);
    if (governmentPostsOfHousehold == null) {
      // ★ S3b：旧档没有该键 ⇒ 归一成空表（与 Unit 的 jurisdiction/module/stateDescriptions 同款旧档兼容落点）。
      governmentPostsOfHousehold = Map.of();
    }
    Map<HouseholdId, GovernmentPostOfHousehold> postCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, GovernmentPostOfHousehold> entry : governmentPostsOfHousehold.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("householdPosts 的键与值都不得为 null");
      }
      if (!entry.getKey().equals(entry.getValue().householdId())) {
        throw new IllegalArgumentException(
            "householdPosts 的键必须等于配置的 householdId: 键="
                + entry.getKey()
                + " 值="
                + entry.getValue().householdId());
      }
      postCopy.put(entry.getKey(), entry.getValue());
    }
    governmentPostsOfHousehold = Collections.unmodifiableMap(postCopy); // ★ 冻在赋值处
    if (policy == null) {
      throw new IllegalArgumentException("policy 不得为 null");
    }
    if (superiorGov == null) {
      throw new IllegalArgumentException("superiorGov 不得为 null（无上级用 Optional.empty()）");
    }
    if (level == null) {
      throw new IllegalArgumentException("level 不得为 null");
    }
  }

  /** {@code staff} 是否已是“领导家户投影”的口径（{@code governmentPostsOfHousehold} 非空 = 由 app 侧按家户人口校核）。 */
  public boolean staffIsHouseholdProjection() {
    return !governmentPostsOfHousehold.isEmpty();
  }
}
