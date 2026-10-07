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
 *       {@code governmentPostsOfHousehold} 或 {@code externalPosts} 任一非空，staff 只是那些岗位家户承诺的**投影**（app
 *       组合根现算校核），不得再当第二本权威；
 *   <li>{@code governmentPostsOfHousehold}（S3b，2026-10-09 用户裁定）：以 {@link HouseholdId}
 *       为键的**内部**领导层/官吏家户配置（{@link
 *       GovernmentPostOfHousehold}）。领导层可单独建小家户，再在本表挂配置；本表不含人数——人数从家户成员现算；
 *   <li>★★ {@code externalPosts}（Z3d，2026-10-23 用户裁定 B 路）：以 {@link HouseholdId} 为键的**外部**岗位配置—— 家户
 *       <b>不要求</b> ∈ {@code Unit.households}，保留它原来的单位/ Social 位置，只承接本 GOV 的行政任务；与 {@code
 *       governmentPostsOfHousehold} <b>互斥</b>（同一家户不得同时在两边，构造期具名拒）。外部户是否存在（Social/economy 行）由 app
 *       工具预检——unit 模块看不见 Social（见 Z3d 台账的残余裸提交边界）；
 *   <li>{@code policy}：编制政策（定额/上限/退休待遇）；
 *   <li>{@code superiorGov}：上级 GOV 单位 id——中央为空；多数省直接指中央；
 *   <li>{@code level}：层级（中央 / 省）。
 * </ul>
 *
 * <p>★★ <b>2026-10-09 唯一列表裁定</b>：本类型<b>不再有 {@code households}</b> 组件（曾与 {@code Unit.households}
 * 重复）。 “谁在这个 Unit 里”的唯一实质列表是 {@link Unit#households()}；政府家户（{@code hh-gov-<unitId>}）也必须出现在 它里面——由
 * {@link UnitOperations#setGovernmentFormation} 同批写入，由 {@link UnitState} 构造期判“GOV 单位恰一个、 且逐字等于
 * {@code hh-gov-<unitId>}”。{@code governmentPostsOfHousehold} 只是角色配置，键必须 ⊆ {@code Unit.households}。
 *
 * <p>★ <b>不变量</b>：{@code staff} 非 null、键非 null、值非 null 且 ≥ 0，保序不可变；{@code
 * governmentPostsOfHousehold}/{@code externalPosts} 的键与值都非 null、且键 == {@code value.householdId()}；
 * 两张岗位表的键集 <b>互斥</b>（同一家户不得同时在内部与外部岗位）；{@code policy}、{@code superiorGov}（Optional 本身）、{@code
 * level} 都不得为 null；违反一律当场抛 {@link IllegalArgumentException}。 ★ <b>不做跨字段校验</b>（如“中央必须没有上级”）——那是 GOV
 * 侧创建期的事，本类型只保证自己的字段合法（照 {@code Jurisdiction} 只守自己一亩地的先例）。内部岗位键是否属于本单位的 {@code
 * households}、外部岗位键是否**不在**其中，由 {@link UnitState} 构造期统一校验。
 *
 * <p>★ <b>它不含任何力量/效率数值</b>：行政力、加成、税收覆盖全部在 {@code simos-gov} 里算（用户裁定 1/2）。
 *
 * <p>★★ <b>S3a/S3b/Z3d 的拷贝纪律</b>：所有只改某个组件的重建点（{@code
 * UnitOperations.withGovernmentPolicy/withGovernmentSuperior/withGovernmentStaff/withGovernmentPosts/withGovernmentExternalPosts}）
 * 都必须原样带过另一张岗位表——漏传 = 静默丢领导/外部配置，本仓最贵教训的共同形态。★ 本 record **没有 5 参兼容构造器**：每个 {@code new
 * GovernmentFormation(...)} 都被迫显式给出 {@code externalPosts}，漏带当场编译失败（拷贝 纪律的编译期判别力；测试调用点按编译必需做了机械补参）。
 *
 * @param staff 各行政角色在编人数（兼容字段/家户投影；保序不可变；键非 null，值 ≥ 0）
 * @param governmentPostsOfHousehold 内部领导层家户配置（键 = 家户；必须 ⊆ Unit.households；保序不可变；旧档缺键 ⇒ 空表）
 * @param policy 编制政策（非 null）
 * @param superiorGov 上级 GOV（中央/无上级用 {@code Optional.empty()}；Optional 本身非 null）
 * @param level 层级（非 null）
 * @param externalPosts 外部岗位配置（键 = 家户，**不要求** ∈ Unit.households、且必须 ∉；与内部表互斥；保序不可变； 旧档缺键 ⇒ 空表）
 */
public record GovernmentFormation(
    Map<StaffRole, Long> staff,
    // ★ R4 改名批次：Java 侧实质化为 governmentPostsOfHousehold，持久化 JSON 键保持旧名（旧档零迁移）。
    @JsonProperty("householdPosts")
        Map<HouseholdId, GovernmentPostOfHousehold> governmentPostsOfHousehold,
    OfficePolicy policy,
    Optional<UnitId> superiorGov,
    GovernmentLevel level,
    // ★ Z3d（2026-10-23）：外部岗位独立表，持久化 JSON 键 = externalPosts（旧档缺键 ⇒ 空表）。
    @JsonProperty("externalPosts") Map<HouseholdId, GovernmentPostOfHousehold> externalPosts)
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
    for (Map.Entry<HouseholdId, GovernmentPostOfHousehold> entry :
        governmentPostsOfHousehold.entrySet()) {
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
    if (externalPosts == null) {
      // ★ Z3d：旧档没有该键 ⇒ 归一成空表（与 householdPosts 同款旧档兼容落点）。
      externalPosts = Map.of();
    }
    Map<HouseholdId, GovernmentPostOfHousehold> externalCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, GovernmentPostOfHousehold> entry : externalPosts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("externalPosts 的键与值都不得为 null");
      }
      if (!entry.getKey().equals(entry.getValue().householdId())) {
        throw new IllegalArgumentException(
            "externalPosts 的键必须等于配置的 householdId: 键="
                + entry.getKey()
                + " 值="
                + entry.getValue().householdId());
      }
      if (governmentPostsOfHousehold.containsKey(entry.getKey())) {
        throw new IllegalArgumentException(
            "同一家户不得同时在 householdPosts（内部岗位）与 externalPosts（外部岗位）: " + entry.getKey());
      }
      externalCopy.put(entry.getKey(), entry.getValue());
    }
    externalPosts = Collections.unmodifiableMap(externalCopy); // ★ 冻在赋值处
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

  /**
   * {@code staff} 是否已是“岗位家户投影”的口径（内部或外部岗位表非空 = 由 app 侧按家户承诺校核）。
   *
   * <p>★ Z3d：外部岗位与内部岗位**同权**——只要任一张表非空，{@code staff} 就不再是权威，所有直改 staff 的写口必须具名拒（C4 一处真相）。旧档两表都空 ⇒
   * 返回 false，旧行为逐值保留。
   */
  public boolean staffIsHouseholdProjection() {
    return hasAnyPosts();
  }

  /** 内部或外部岗位表是否非空（Z3d：两张表同权参与“是否有岗位”的判定）。 */
  public boolean hasAnyPosts() {
    return !governmentPostsOfHousehold.isEmpty() || !externalPosts.isEmpty();
  }

  /**
   * 按家户取岗位（内部优先，其次外部）；不存在 ⇒ 空。两张表互斥由构造期保证，因此结果唯一。
   *
   * <p>★ 只读、不建表：桥/读口逐户取岗位用它，避免在热路径上复制合并表。
   */
  public Optional<GovernmentPostOfHousehold> postOf(HouseholdId household) {
    java.util.Objects.requireNonNull(household, "household");
    GovernmentPostOfHousehold post = governmentPostsOfHousehold.get(household);
    if (post == null) {
      post = externalPosts.get(household);
    }
    return Optional.ofNullable(post);
  }

  /**
   * 两张岗位表的确定性合并视图（内部表插入序在前、外部表插入序在后；两表互斥 ⇒ 键唯一）。
   *
   * <p>★ 保序不可变：{@code LinkedHashMap} + 赋值处冻结，<b>不用</b> {@code Map.copyOf}。外部表为空时直接返回内部表（旧世界零拷贝）。
   */
  public Map<HouseholdId, GovernmentPostOfHousehold> allPosts() {
    if (externalPosts.isEmpty()) {
      return governmentPostsOfHousehold;
    }
    Map<HouseholdId, GovernmentPostOfHousehold> merged =
        new LinkedHashMap<>(governmentPostsOfHousehold);
    merged.putAll(externalPosts);
    return Collections.unmodifiableMap(merged);
  }

  /**
   * ★★ <b>Z4/C4：{@code staff} 的唯一派生口径</b>——按 {@link GovernmentPostOfHousehold#role()} 聚合这些岗位家户的
   * 人数/承诺量（Z3d 起内部 + 外部岗位同权纳入）。
   *
   * <p>★ <b>为什么入参是函数</b>：本 record 在 unit 切片，看不见 Social 家户人口，也看不见 economy 的 {@code
   * HouseholdLaborCommitment}（模块边界）。调用方（app 组合根）提供"某家户对 GA 的贡献量"：Z4 用 Social 家户人口；Z3 接入承诺劳动后改用
   * {@code GOV_SERVICE} 承诺的小时数/人数当量——公式只在这里一份。
   *
   * <p>★ <b>确定性/保序</b>：按 {@link #allPosts()} 的插入序遍历（内部表在前、外部表在后），角色键沿用其首次出现的顺序；返回 {@code
   * LinkedHashMap} + 赋值处冻结（不用 {@code Map.copyOf}）。求和溢出 ⇒ 具名 {@link IllegalArgumentException}。
   *
   * @param householdAmount 家户 → 贡献量（非负；调用方保证家户存在时的口径）
   * @return 角色 → 投影量（只含被指派到的角色；空表 = 没有岗位家户）
   */
  public Map<StaffRole, Long> projectedStaff(
      java.util.function.ToLongFunction<HouseholdId> householdAmount) {
    java.util.Objects.requireNonNull(householdAmount, "householdAmount");
    Map<StaffRole, Long> projection = new LinkedHashMap<>();
    for (GovernmentPostOfHousehold post : allPosts().values()) {
      long amount = householdAmount.applyAsLong(post.householdId());
      if (amount < 0L) {
        throw new IllegalArgumentException(
            "projectedStaff 的 householdAmount 不得为负: " + post.householdId() + "=" + amount);
      }
      projection.merge(
          post.role(),
          amount,
          (left, right) -> {
            try {
              return Math.addExact(left, right);
            } catch (ArithmeticException e) {
              throw new IllegalArgumentException(
                  "projectedStaff 角色 " + post.role() + " 的投影量溢出 long", e);
            }
          });
    }
    return Collections.unmodifiableMap(projection); // ★ 冻在赋值处（保序不可变）
  }
}
