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
    Map<CommodityId, Long> durableCommodityCapacityPerUnit) {

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
  }

  /**
   * ★★ <b>通用生产配方</b>（V7；四个分量 + 铸币一路）：把本记录里散着的四段打成一个视图。
   *
   * <p>★ <b>纯派生</b>（{@code inputPerUnit} 由 {@link #cycleInputPerUnit()} 合计而来）⇒ 不存在第二份手写的数。
   */
  public ProductionRecipe recipe() {
    return new ProductionRecipe(
        capacityPerUnit,
        inputPerUnit(),
        laborPerUnit,
        outputPerUnit,
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
