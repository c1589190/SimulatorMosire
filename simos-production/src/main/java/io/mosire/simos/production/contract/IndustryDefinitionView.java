package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>产业定义的权威公开类型</b>（D-031 P1；原 P0 只读契约视图升级为生产切片的持久定义类型）。
 *
 * <p>它是完整产业定义：稳定 ID、制度、配方与阶层槽位，外加可缺的跨地区技能键；<b>明确排除运行实例字段</b> （operator / progressDays / capacity /
 * cycleLaborMilli / cycleInputUsedMilli）——那些是生产进度/存量，住在 {@code ProductionProcess} / {@code
 * OwnershipStake}。
 *
 * <p>★★ <b>不引用任何 economy 实现类</b>（spec §4 无环证明）：字段只取 util / map / economy-api / production
 * 自身契约（{@code AssetKind} 经 economy-api 传递可见，见 {@code simos-production/pom.xml}）。分配函数、阶层槽位与铸币产出
 * 都以本契约包内的同族值类型承载（{@link AllocationRule} / {@link ClassSlot} / {@link MoneyOutput}）。
 *
 * <p>★★ <b>技能可缺</b>（修正 B）：{@code skill = Optional.empty()} 只表示"这个产业没绑上跨地区技能键"，
 * <b>不</b>表示产业不存在；产业定义只要存在就照常返回本记录。
 *
 * <p>★ <b>保序不可变</b>：四张表（含两张嵌套表）与 {@code slots} 一律拷贝后冻结，<b>绝不用 {@code Map.copyOf}/{@code
 * List.copyOf}</b>——迭代序不是内容的纯函数。
 *
 * <p>★ <b>强不变量照旧产业模板</b>（P1 把契约视图升级为权威状态后必须补齐，否则往返/创世会放进非法状态）： {@code capacityPerUnit} 非空且逐值 {@code
 * > 0}（"单位规模"的锚）；{@code slots} 非空且 id 不重复； 逐表键值非 null、逐值非负。分配函数与铸币产出各自在自己的契约类型里守护。
 *
 * @param id 产业稳定身份
 * @param name 展示名；非空白
 * @param regime 生产制度
 * @param skill ★ 可缺：未命中技能目录 ⇒ empty，不使产业不可见
 * @param cycleDays 生产周期（天）；≥ 1
 * @param buildDays ★★ <b>D-032 B2c 新增</b>（裁定八：候选的 {@code buildDays} 搬进方案定义）：建设期（天）；≥ 0。 ★
 *     它只进"需求剩余窗口"与"投入/口粮覆盖窗口"两个门槛（{@code horizon = buildDays + cycleDays}）， <b>不</b>推迟结算进度 ——
 *     与旧候选口径逐字相同（见 {@code EconomyEntrySettlement} 类注）。
 * @param capacityPerUnit 每 1 单位规模需要的生产资料（LAND 千分亩、其余件）；逐值 &gt; 0；空表 = 没有 AssetKind 通道 （与 {@code
 *     durableCommodityCapacityPerUnit} 至少一边非空）
 * @param dailyInputPerUnit 每 1 单位规模的每日原料需求（按生产资料种类归类）
 * @param dailyLaborPerUnit 每 1 单位规模的每日劳动需求（毫小时/区域/日；1 小时 = 1000 毫小时）
 * @param laborPerUnit 每 1 单位规模需要的劳动（毫小时/区域；200 = 0.2 小时/区域）
 * @param outputPerUnit 每 1 单位规模的基准产出（商品单位）
 * @param cycleInputPerUnit 每 1 单位规模每周期一次性投入（按生产资料种类归类）
 * @param slots 该制度允许的阶层槽位；非空、id 不重复
 * @param allocation 制度分配函数
 * @param moneyOutputPerUnit 铸币产出率；空表 = 不铸币
 * @param experience ★★ <b>D-032 新增</b>：本方案引用的经验条（专门 a + 可共用 b）、熟练度→效率倍率公式与解锁门槛； 不得为 null（不挂经验面请给
 *     {@link ProductionExperienceUse#NONE}）
 * @param availability ★★ <b>D-032 新增</b>：本方案的可用性条件（外部属性门槛、任意经验条门槛）； 不得为 null（无条件用空 list）。★ 求值
 *     fail-closed（属性缺失 ⇒ 不满足），见 {@link ProductionAvailabilityCondition} / {@link
 *     ExternalAttributeFacts}
 * @param acceptedRightKinds ★★ <b>D-032 B2c 新增</b>（裁定八：候选的 {@code acceptedRightKinds} 搬进方案定义）：
 *     本方案声明可接受的<b>资产权利性质</b>（{@link AssetRightKind}）；不得为 null（空集合法 = 不接受任何权利， 采用算法据此具名拒绝 {@code
 *     NO_RIGHT_KIND}）。★ economy 分配资产时按它过滤份额的 {@code kind}
 * @param roles ★★ <b>D1 新增</b>：角色定义；非空、id 唯一、产品份额合计 ≤ 1000（纯单户方案也要有一个经营者角色）
 * @param roleShareRemainderRule ★★ <b>D1 新增</b>：份额不足 1000 时的余数规则；{@code UNALLOCATED} 最终归 organizer
 *     residual
 * @param upgradesTo ★★ <b>D1 新增</b>：技术升级元数据（可空；只登记/读口/选择参考，不产生行为；未知 id 在使用处 fail-closed）
 * @param mode ★★ <b>D1 新增</b>：绑定的生产方式/阶层结构（仅元数据；可缺）
 * @param durableCommodityCapacityPerUnit ★★ <b>D-032 C5a 新增</b>：每 1 单位规模需要的耐久商品资本（容量锚，不消耗）； 键 =
 *     {@link CommodityId}、值必须 &gt; 0；空表 = 没有这一路。它与 {@code capacityPerUnit} 至少一边非空， 并与消耗性投入（{@code
 *     cycleInputPerUnit}）<b>完全分离</b>。
 * @param stages ★★ <b>D-032 U4a 新增</b>：多阶段生产定义（保序不可变）；空表 = legacy 单周期（现有行为不变）。 非空时 {@code
 *     cycleDays} 必须等于各阶段 {@code durationDays} 之和，最后一项必须是 {@link ProductionStageEndAction#HARVEST}，且
 *     legacy {@code cycleInputPerUnit}/{@code dailyInputPerUnit}/{@code outputPerUnit}
 *     必须为空（避免两套真相）。阶段级 <b>没有</b> capacity/labor：v1 沿用产业级字段。
 */
public record IndustryDefinitionView(
    IndustryId id,
    String name,
    RegimeId regime,
    Optional<ProductionSkillKey> skill,
    long cycleDays,
    long buildDays,
    Map<AssetKind, Long> capacityPerUnit,
    Map<AssetKind, Map<CommodityId, Long>> dailyInputPerUnit,
    long dailyLaborPerUnit,
    long laborPerUnit,
    Map<CommodityId, Long> outputPerUnit,
    Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
    List<ClassSlot> slots,
    AllocationRule allocation,
    MoneyOutput moneyOutputPerUnit,
    ProductionExperienceUse experience,
    List<ProductionAvailabilityCondition> availability,
    Set<AssetRightKind> acceptedRightKinds,
    List<ProductionRoleDefinition> roles,
    RoleShareRemainder roleShareRemainderRule,
    List<IndustryId> upgradesTo,
    Optional<ProductionModeId> mode,
    Map<CommodityId, Long> durableCommodityCapacityPerUnit,
    List<ProductionStageDefinition> stages) {

  /**
   * ★★ <b>单户默认 operator 便利构造器</b>（C0 下游机械收口；不是第二定义权威）。
   *
   * <p>仅供仍以“单户/单经营者”构造方案的既有调用点使用：它把 D1 新增的四个组件固定为 {@code roles =
   * List.of(ProductionRoleDefinition.defaultOperator())}、{@code roleShareRemainderRule =
   * UNALLOCATED}、{@code upgradesTo = []}、{@code mode = empty}。其中默认 operator 角色是 D1 §2.8 的缺省形态：
   * id={@code operator}、share=1000、{@code intraRoleSplit=EQUAL}（单角色下 EQUAL/BY_RECOGNIZED_LABOR 对
   * 1000 份额无差别；取 D1 缺省明写的 EQUAL，避免凭空引入劳动语义）、payment=PRODUCT_SHARE、无经验增长。
   *
   * <p>★★ <b>边界</b>：多角色方案必须显式使用 canonical 构造器传 {@code roles}；本构造器不会吞掉显式 roles， 也不代表 economy
   * 已建立名册。C2 会把生产名册显式落成 participant，并改走带 roles 的构造路径。
   */
  public IndustryDefinitionView(
      IndustryId id,
      String name,
      RegimeId regime,
      Optional<ProductionSkillKey> skill,
      long cycleDays,
      long buildDays,
      Map<AssetKind, Long> capacityPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> dailyInputPerUnit,
      long dailyLaborPerUnit,
      long laborPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
      List<ClassSlot> slots,
      AllocationRule allocation,
      MoneyOutput moneyOutputPerUnit,
      ProductionExperienceUse experience,
      List<ProductionAvailabilityCondition> availability,
      Set<AssetRightKind> acceptedRightKinds) {
    this(
        id,
        name,
        regime,
        skill,
        cycleDays,
        buildDays,
        capacityPerUnit,
        dailyInputPerUnit,
        dailyLaborPerUnit,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        slots,
        allocation,
        moneyOutputPerUnit,
        experience,
        availability,
        acceptedRightKinds,
        List.of(ProductionRoleDefinition.defaultOperator()),
        RoleShareRemainder.UNALLOCATED,
        List.of(),
        Optional.empty(),
        Map.of());
  }

  /**
   * ★ <b>C5a 之前的 canonical 22 参形状</b>（无耐久商品资本通道）：等价于 {@code durableCommodityCapacityPerUnit =
   * Map.of()}；旧调用点逐值不变。
   *
   * <p>★ 需要显式耐久商品资本的调用方改用 canonical 23 参构造器（末位传 {@code Map<CommodityId, Long>}）。
   */
  public IndustryDefinitionView(
      IndustryId id,
      String name,
      RegimeId regime,
      Optional<ProductionSkillKey> skill,
      long cycleDays,
      long buildDays,
      Map<AssetKind, Long> capacityPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> dailyInputPerUnit,
      long dailyLaborPerUnit,
      long laborPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
      List<ClassSlot> slots,
      AllocationRule allocation,
      MoneyOutput moneyOutputPerUnit,
      ProductionExperienceUse experience,
      List<ProductionAvailabilityCondition> availability,
      Set<AssetRightKind> acceptedRightKinds,
      List<ProductionRoleDefinition> roles,
      RoleShareRemainder roleShareRemainderRule,
      List<IndustryId> upgradesTo,
      Optional<ProductionModeId> mode) {
    this(
        id,
        name,
        regime,
        skill,
        cycleDays,
        buildDays,
        capacityPerUnit,
        dailyInputPerUnit,
        dailyLaborPerUnit,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        slots,
        allocation,
        moneyOutputPerUnit,
        experience,
        availability,
        acceptedRightKinds,
        roles,
        roleShareRemainderRule,
        upgradesTo,
        mode,
        Map.of());
  }

  /**
   * ★ <b>U4a 之前的 canonical 23 参形状</b>（无多阶段定义）：等价于 {@code stages = List.of()}；旧调用点逐值不变。
   *
   * <p>★ 需要显式多阶段定义的调用方改用 canonical 24 参构造器（末位传 {@code List<ProductionStageDefinition>}）。
   */
  public IndustryDefinitionView(
      IndustryId id,
      String name,
      RegimeId regime,
      Optional<ProductionSkillKey> skill,
      long cycleDays,
      long buildDays,
      Map<AssetKind, Long> capacityPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> dailyInputPerUnit,
      long dailyLaborPerUnit,
      long laborPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
      List<ClassSlot> slots,
      AllocationRule allocation,
      MoneyOutput moneyOutputPerUnit,
      ProductionExperienceUse experience,
      List<ProductionAvailabilityCondition> availability,
      Set<AssetRightKind> acceptedRightKinds,
      List<ProductionRoleDefinition> roles,
      RoleShareRemainder roleShareRemainderRule,
      List<IndustryId> upgradesTo,
      Optional<ProductionModeId> mode,
      Map<CommodityId, Long> durableCommodityCapacityPerUnit) {
    this(
        id,
        name,
        regime,
        skill,
        cycleDays,
        buildDays,
        capacityPerUnit,
        dailyInputPerUnit,
        dailyLaborPerUnit,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        slots,
        allocation,
        moneyOutputPerUnit,
        experience,
        availability,
        acceptedRightKinds,
        roles,
        roleShareRemainderRule,
        upgradesTo,
        mode,
        durableCommodityCapacityPerUnit,
        List.of());
  }

  public IndustryDefinitionView {
    if (id == null) {
      throw new IllegalArgumentException("IndustryDefinitionView.id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("IndustryDefinitionView.name 不得为空白");
    }
    if (regime == null) {
      throw new IllegalArgumentException("IndustryDefinitionView.regime 不得为 null");
    }
    if (skill == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.skill 不得为 null（可缺请给 Optional.empty()）");
    }
    if (allocation == null) {
      throw new IllegalArgumentException("IndustryDefinitionView.allocation 不得为 null");
    }
    if (moneyOutputPerUnit == null) {
      throw new IllegalArgumentException("IndustryDefinitionView.moneyOutputPerUnit 不得为 null");
    }
    if (experience == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.experience 不得为 null（不挂经验面请给 ProductionExperienceUse.NONE）");
    }
    if (availability == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.availability 不得为 null（无条件用空 list）");
    }
    // ★★ B2c：acceptedRightKinds 的冻结与校验（空集合法 = 不接受任何权利；逐项非 null、保序不可变）。
    if (acceptedRightKinds == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.acceptedRightKinds 不得为 null（空集请给 Set.of()）");
    }
    Set<AssetRightKind> rightsCopy = new LinkedHashSet<>();
    for (AssetRightKind right : acceptedRightKinds) {
      if (right == null) {
        throw new IllegalArgumentException("IndustryDefinitionView.acceptedRightKinds 不得含 null");
      }
      rightsCopy.add(right);
    }
    acceptedRightKinds = Collections.unmodifiableSet(rightsCopy); // ★ 冻在赋值处
    // ★★ D1：角色/份额/升级/mode 的冻结与校验（保序不可变；id 唯一、份额合计 ≤ 1000）。
    if (roleShareRemainderRule == null) {
      throw new IllegalArgumentException("IndustryDefinitionView.roleShareRemainderRule 不得为 null");
    }
    if (roles == null) {
      throw new IllegalArgumentException("IndustryDefinitionView.roles 不得为 null（至少一个经营者角色）");
    }
    List<ProductionRoleDefinition> rolesCopy = new ArrayList<>();
    Set<ProductionRoleId> roleIds = new LinkedHashSet<>();
    long shareTotal = 0L;
    for (ProductionRoleDefinition role : roles) {
      if (role == null) {
        throw new IllegalArgumentException("IndustryDefinitionView.roles 不得含 null");
      }
      if (!roleIds.add(role.id())) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.roles 的 id 不得重复: " + role.id().value());
      }
      shareTotal += role.sharePerMille();
      rolesCopy.add(role);
    }
    if (rolesCopy.isEmpty()) {
      throw new IllegalArgumentException("IndustryDefinitionView.roles 不得为空（纯单户方案也要有一个经营者角色）");
    }
    if (shareTotal > 1000L) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.roles 的 sharePerMille 合计不得 > 1000: " + shareTotal);
    }
    roles = Collections.unmodifiableList(rolesCopy);
    if (upgradesTo == null) {
      throw new IllegalArgumentException("IndustryDefinitionView.upgradesTo 不得为 null（无升级用空 list）");
    }
    List<IndustryId> upgradesCopy = new ArrayList<>();
    for (IndustryId upgrade : upgradesTo) {
      if (upgrade == null) {
        throw new IllegalArgumentException("IndustryDefinitionView.upgradesTo 不得含 null");
      }
      upgradesCopy.add(upgrade);
    }
    upgradesTo = Collections.unmodifiableList(upgradesCopy);
    if (mode == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.mode 不得为 null（可缺请给 Optional.empty()）");
    }
    // ★ 可用性条件的冻结与校验：逐条非 null，保序不可变（迭代序是内容的纯函数）。
    List<ProductionAvailabilityCondition> availabilityCopy = new ArrayList<>();
    for (ProductionAvailabilityCondition condition : availability) {
      if (condition == null) {
        throw new IllegalArgumentException("IndustryDefinitionView.availability 不得含 null");
      }
      availabilityCopy.add(condition);
    }
    availability = Collections.unmodifiableList(availabilityCopy); // ★ 冻在赋值处
    if (slots == null) {
      throw new IllegalArgumentException("IndustryDefinitionView.slots 不得为 null（无槽位用空 list）");
    }
    if (cycleDays < 1L) {
      throw new IllegalArgumentException("IndustryDefinitionView.cycleDays 必须 ≥ 1: " + cycleDays);
    }
    if (buildDays < 0L) {
      throw new IllegalArgumentException("IndustryDefinitionView.buildDays 不得为负: " + buildDays);
    }
    if (dailyLaborPerUnit < 0L) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.dailyLaborPerUnit 不得为负: " + dailyLaborPerUnit);
    }
    if (laborPerUnit < 0L) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.laborPerUnit 不得为负: " + laborPerUnit);
    }
    // ★★ 下面各段逐字展开、不抽 helper（照原 Industry 的冻结纪律）：EI_EXPOSE_REP 只认它看得见的包装。
    if (capacityPerUnit == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.capacityPerUnit 不得为 null（无约束用空 map）");
    }
    Map<AssetKind, Long> capacityCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : capacityPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.capacityPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.capacityPerUnit 的数量必须为正："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      capacityCopy.put(entry.getKey(), entry.getValue());
    }
    capacityPerUnit = Collections.unmodifiableMap(capacityCopy);

    // ★★ C5a：耐久商品资本的容量锚（与 AssetKind capacity 平行、与消耗性投入分离）。
    if (durableCommodityCapacityPerUnit == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.durableCommodityCapacityPerUnit 不得为 null（无此通道用空 map）");
    }
    Map<CommodityId, Long> durableCapacityCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : durableCommodityCapacityPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.durableCommodityCapacityPerUnit 的键与值都不得为 null: "
                + entry.getKey());
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.durableCommodityCapacityPerUnit 的数量必须为正："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      durableCapacityCopy.put(entry.getKey(), entry.getValue());
    }
    durableCommodityCapacityPerUnit = Collections.unmodifiableMap(durableCapacityCopy); // ★ 冻在赋值处
    if (capacityPerUnit.isEmpty() && durableCommodityCapacityPerUnit.isEmpty()) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView 的 capacityPerUnit 与 durableCommodityCapacityPerUnit 至少一边非空"
              + "（\"单位规模\"必须有锚）");
    }

    if (dailyInputPerUnit == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.dailyInputPerUnit 不得为 null（无投入用空 map）");
    }
    Map<AssetKind, Map<CommodityId, Long>> dailyInputCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Map<CommodityId, Long>> entry : dailyInputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.dailyInputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      dailyInputCopy.put(entry.getKey(), freezeLine(entry.getValue(), "dailyInputPerUnit"));
    }
    dailyInputPerUnit = Collections.unmodifiableMap(dailyInputCopy);

    if (outputPerUnit == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.outputPerUnit 不得为 null（无产出用空 map）");
    }
    Map<CommodityId, Long> outputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : outputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.outputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.outputPerUnit 的数量不得为负："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      outputCopy.put(entry.getKey(), entry.getValue());
    }
    outputPerUnit = Collections.unmodifiableMap(outputCopy);

    if (cycleInputPerUnit == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.cycleInputPerUnit 不得为 null（无投入用空 map）");
    }
    Map<AssetKind, Map<CommodityId, Long>> cycleInputCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Map<CommodityId, Long>> entry : cycleInputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.cycleInputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      cycleInputCopy.put(entry.getKey(), freezeLine(entry.getValue(), "cycleInputPerUnit"));
    }
    cycleInputPerUnit = Collections.unmodifiableMap(cycleInputCopy);

    List<ClassSlot> slotsCopy = new ArrayList<>();
    Set<SocialClassId> slotIds = new LinkedHashSet<>();
    for (ClassSlot slot : slots) {
      if (slot == null) {
        throw new IllegalArgumentException("IndustryDefinitionView.slots 不得含 null");
      }
      if (!slotIds.add(slot.id())) {
        throw new IllegalArgumentException("IndustryDefinitionView.slots 的 id 不得重复: " + slot.id());
      }
      slotsCopy.add(slot);
    }
    if (slotsCopy.isEmpty()) {
      throw new IllegalArgumentException("IndustryDefinitionView.slots 不得为空");
    }
    slots = Collections.unmodifiableList(slotsCopy);

    // ★★ U4a：多阶段定义（保序不可变；空表 = legacy 单周期，现有行为不变）。
    //   ★ 阶段级没有 capacity/labor 字段：v1 沿用上面的产业级 capacityPerUnit/laborPerUnit，不在这里拆阶段。
    if (stages == null) {
      throw new IllegalArgumentException(
          "IndustryDefinitionView.stages 不得为 null（legacy 单周期请给空 list）");
    }
    List<ProductionStageDefinition> stagesCopy = new ArrayList<>();
    Set<String> stageIds = new LinkedHashSet<>();
    long stageDaysTotal = 0L;
    for (ProductionStageDefinition stage : stages) {
      if (stage == null) {
        throw new IllegalArgumentException("IndustryDefinitionView.stages 不得含 null");
      }
      if (!stageIds.add(stage.stageId())) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.stages 的 stageId 不得重复: " + stage.stageId());
      }
      try {
        stageDaysTotal = Math.addExact(stageDaysTotal, stage.durationDays());
      } catch (ArithmeticException e) {
        // ★ fail-closed：合计溢出不是“接近上限”，直接具名拒绝。
        throw new IllegalArgumentException(
            "IndustryDefinitionView.stages 的 durationDays 合计溢出（long）: " + stage.stageId(), e);
      }
      stagesCopy.add(stage);
    }
    if (!stagesCopy.isEmpty()) {
      if (cycleDays != stageDaysTotal) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.cycleDays 必须等于各阶段 durationDays 之和: cycleDays="
                + cycleDays
                + ", ΣdurationDays="
                + stageDaysTotal);
      }
      for (int index = 0; index < stagesCopy.size(); index++) {
        ProductionStageDefinition stage = stagesCopy.get(index);
        boolean last = index == stagesCopy.size() - 1;
        if (last) {
          if (stage.endAction() != ProductionStageEndAction.HARVEST) {
            throw new IllegalArgumentException(
                "IndustryDefinitionView.stages 最后一项 endAction 必须是 HARVEST: "
                    + stage.stageId()
                    + " = "
                    + stage.endAction());
          }
        } else {
          if (stage.endAction() == ProductionStageEndAction.HARVEST) {
            throw new IllegalArgumentException(
                "IndustryDefinitionView.stages 的 HARVEST 只能出现在最后一项: " + stage.stageId());
          }
          if (stage.endAction() != ProductionStageEndAction.NEXT_STAGE) {
            throw new IllegalArgumentException(
                "IndustryDefinitionView.stages 的非最后一项 endAction 必须是 NEXT_STAGE: "
                    + stage.stageId()
                    + " = "
                    + stage.endAction());
          }
        }
      }
      // ★ 两套真相禁止：有 stages 时 legacy 配方必须为空，避免“阶段投入”与“周期投入”同时存在。
      if (!cycleInputPerUnit.isEmpty()) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.stages 非空时 cycleInputPerUnit 必须为空（避免两套真相）");
      }
      if (!dailyInputPerUnit.isEmpty()) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.stages 非空时 dailyInputPerUnit 必须为空（避免两套真相）");
      }
      if (!outputPerUnit.isEmpty()) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView.stages 非空时 outputPerUnit 必须为空（避免两套真相）");
      }
    }
    stages = Collections.unmodifiableList(stagesCopy);
  }

  /**
   * ★★ <b>通用生产配方</b>（V7；四个分量 + 铸币一路）：把本记录里散着的四段打成一个视图。
   *
   * <p>★ <b>纯派生</b>：{@code inputPerUnit} 与 {@code outputPerUnit} 在 legacy（{@code stages} 为空）时逐值取自
   * {@link #inputPerUnit()} / {@link #outputPerUnit()}；staged 时分别取 {@link #effectiveInputPerUnit()}
   * / {@link #effectiveOutputPerUnit()}。⇒ 不写回第二套真相，也不改变 legacy 的配方结果。
   */
  public ProductionRecipe recipe() {
    return new ProductionRecipe(
        capacityPerUnit,
        effectiveInputPerUnit(),
        laborPerUnit,
        effectiveOutputPerUnit(),
        moneyOutputPerUnit,
        durableCommodityCapacityPerUnit);
  }

  /** <b>每 1 单位规模每周期消耗的商品</b>（毫单位）= 各生产资料分路的<b>合计</b>（口径的唯一落点仍是 {@code cycleInputPerUnit}）。 */
  public Map<CommodityId, Long> inputPerUnit() {
    Map<CommodityId, Long> merged = new LinkedHashMap<>();
    for (Map<CommodityId, Long> line : cycleInputPerUnit.values()) {
      for (Map.Entry<CommodityId, Long> entry : line.entrySet()) {
        merged.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    return Collections.unmodifiableMap(merged);
  }

  /**
   * ★★ <b>阶段有效周期投入</b>（每 1 单位规模）：{@code Σ_stage(startInput + tickInput × durationDays)}。
   *
   * <p>★ legacy（{@code stages} 为空）⇒ 逐值返回 {@link #inputPerUnit()}。staged ⇒ 只从阶段定义派生，<b>不</b>写回
   * {@code cycleInputPerUnit}/{@code dailyInputPerUnit}。同商品跨阶段按键求和；键序取首次出现序，返回保序不可变 map。
   *
   * <p>★ 乘法与加法都走 {@code Math.multiplyExact}/{@code Math.addExact}：溢出是定义数据不可表示，fail-closed。
   */
  public Map<CommodityId, Long> effectiveInputPerUnit() {
    if (stages.isEmpty()) {
      return inputPerUnit();
    }
    Map<CommodityId, Long> effective = new LinkedHashMap<>();
    for (ProductionStageDefinition stage : stages) {
      long durationDays = stage.durationDays();
      for (Map.Entry<CommodityId, Long> start : stage.startInputPerUnit().entrySet()) {
        long tick = stage.tickInputPerUnit().getOrDefault(start.getKey(), 0L);
        long stageInput = Math.addExact(start.getValue(), Math.multiplyExact(tick, durationDays));
        effective.merge(start.getKey(), stageInput, Math::addExact);
      }
      for (Map.Entry<CommodityId, Long> tick : stage.tickInputPerUnit().entrySet()) {
        if (stage.startInputPerUnit().containsKey(tick.getKey())) {
          continue;
        }
        long stageInput = Math.multiplyExact(tick.getValue(), durationDays);
        effective.merge(tick.getKey(), stageInput, Math::addExact);
      }
    }
    return Collections.unmodifiableMap(effective);
  }

  /**
   * ★★ <b>阶段有效计划产出</b>（每 1 单位规模）：最后一个 {@link ProductionStageEndAction#HARVEST} 阶段的 {@code
   * outputPerUnit}。{@code NEXT_STAGE} 的中间产出 v1 不计入计划产出。
   *
   * <p>★ legacy（{@code stages} 为空）⇒ 逐值返回 {@link #outputPerUnit()}。staged ⇒ 只读阶段定义，返回保序不可变 map；
   * 构造期已保证最后一个阶段必须是 HARVEST。
   */
  public Map<CommodityId, Long> effectiveOutputPerUnit() {
    if (stages.isEmpty()) {
      return outputPerUnit();
    }
    for (int index = stages.size() - 1; index >= 0; index--) {
      ProductionStageDefinition stage = stages.get(index);
      if (stage.endAction() == ProductionStageEndAction.HARVEST) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(stage.outputPerUnit()));
      }
    }
    throw new IllegalStateException(
        "IndustryDefinitionView.stages 非空但没有 HARVEST 阶段: " + id.value());
  }

  /**
   * ★★ <b>当前阶段投入需求（唯一拼写点）</b>：从 {@link ProductionProcess#stageState()} 取当前阶段，按 {@code startDue} 决定本
   * tick 的每单位规模需求。
   *
   * <p>规则（U4b2b）：
   *
   * <ul>
   *   <li>{@code stages} 为空 ⇒ 本 helper 不服务 legacy 路径（直接拒绝调用；legacy 仍读 {@link #inputPerUnit()}）。
   *   <li>{@code stageState} 为空 ⇒ 第一段；非空时 {@code stageId} 必须存在于 {@link #stages()}，否则抛 {@link
   *       ProductionContractFault}。
   *   <li>{@code startDue = stageState 为空 || (stageProgressDays == 0 && stageStartInputsUsedMilli
   *       为空)}。
   *   <li>{@code perScale = startDue ? startInputPerUnit + tickInputPerUnit :
   *       tickInputPerUnit}；同商品相加， 保序不可变。
   * </ul>
   *
   * <p>★ 这是 economy S4 调查/扣料、DailyDemandBill、{@code inputShortfallOf} 与 production 引擎共用的 同一份 stage
   * 字段读口；不得在调用点再拼一套 stageId/start/tick 选择逻辑。
   */
  public CurrentStageInput currentStageInput(ProductionProcess process) {
    Objects.requireNonNull(process, "IndustryDefinitionView.currentStageInput 的 process 不得为 null");
    if (stages.isEmpty()) {
      throw new IllegalStateException(
          "IndustryDefinitionView.currentStageInput 不服务 legacy（stages 为空）: " + id.value());
    }
    ProductionStageState state = process.stageState().orElse(null);
    int stageIndex = 0;
    if (state != null) {
      stageIndex = -1;
      for (int index = 0; index < stages.size(); index++) {
        if (stages.get(index).stageId().equals(state.stageId())) {
          stageIndex = index;
          break;
        }
      }
      if (stageIndex < 0) {
        throw new ProductionContractFault(
            "stage-state-unknown-stage",
            "实例 "
                + process.id().value()
                + " 的当前阶段 "
                + state.stageId()
                + " 不在方案 "
                + id.value()
                + " 的 stages 中");
      }
    }
    ProductionStageDefinition stage = stages.get(stageIndex);
    boolean startDue =
        state == null
            || (state.stageProgressDays() == 0L && state.stageStartInputsUsedMilli().isEmpty());
    Map<CommodityId, Long> perScale = new LinkedHashMap<>();
    if (startDue) {
      mergeStageInputs(perScale, stage.startInputPerUnit());
    }
    mergeStageInputs(perScale, stage.tickInputPerUnit());
    return new CurrentStageInput(stage, stageIndex, startDue, perScale);
  }

  /** 同商品跨 start/tick 相加；保持首次出现序，并用 {@code Math.addExact} fail-closed。 */
  private static void mergeStageInputs(Map<CommodityId, Long> target, Map<CommodityId, Long> line) {
    for (Map.Entry<CommodityId, Long> entry : line.entrySet()) {
      target.merge(entry.getKey(), entry.getValue(), Math::addExact);
    }
  }

  /**
   * ★ <b>当前阶段投入的只读投影</b>：阶段本体、它在 {@link #stages()} 中的位置、本 tick 是否处于开始投入日， 以及本 tick 的每单位规模需求（保序不可变）。
   *
   * @param stage 当前阶段定义
   * @param stageIndex 当前阶段在 {@link IndustryDefinitionView#stages()} 中的下标
   * @param startDue 本 tick 是否要叠加 {@link ProductionStageDefinition#startInputPerUnit()}
   * @param inputPerUnit 本 tick 每单位规模需求（startDue 时已把 start 与 tick 同商品相加）
   */
  public record CurrentStageInput(
      ProductionStageDefinition stage,
      int stageIndex,
      boolean startDue,
      Map<CommodityId, Long> inputPerUnit) {

    public CurrentStageInput {
      Objects.requireNonNull(stage, "CurrentStageInput.stage 不得为 null");
      if (stageIndex < 0) {
        throw new IllegalArgumentException("CurrentStageInput.stageIndex 不得为负: " + stageIndex);
      }
      Objects.requireNonNull(inputPerUnit, "CurrentStageInput.inputPerUnit 不得为 null");
      Map<CommodityId, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : inputPerUnit.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "CurrentStageInput.inputPerUnit 的键与值都不得为 null: " + entry.getKey());
        }
        if (entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              "CurrentStageInput.inputPerUnit 不得为负：" + entry.getKey() + " = " + entry.getValue());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      inputPerUnit = Collections.unmodifiableMap(copy);
    }

    /** 本 tick 是否存在正数投入需求；全 0/空表 ⇒ 不构成“有需求”。 */
    public boolean hasPositiveDemand() {
      for (long value : inputPerUnit.values()) {
        if (value > 0L) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * 一张投入表的内层（生产资料 → 商品表）的冻结：键值非空、逐值 ≥ 0。
   *
   * <p>★ 这一层可以抽 helper：SpotBugs 报的是<b>记录组件</b>的 EI_EXPOSE_REP，而本方法的返回值是装进外层 copy 的元素、
   * 不是从访问器直接交出去的引用。
   */
  private static Map<CommodityId, Long> freezeLine(Map<CommodityId, Long> source, String field) {
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView." + field + " 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "IndustryDefinitionView."
                + field
                + " 的数量不得为负："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }
}
