package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TemporalSeries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 单位（M3 spec §4.1）：严格树的一个节点。{@code parent} 与 {@code position} 是**时态序列**（军队会改编、会调动）， 其余是普通值（C3；历史由
 * M4 的 revision 日志承载）。
 *
 * <p>★ {@code parent} 指向**自身 id** 在构造期就抛（便宜）；**跨单位的环**由 {@link UnitState} 构造期查 ——两者分工见 spec §4.2。
 *
 * <p>★ {@code position} 允许为空（"不知道在哪"），无则向父取 / 叠加偏移（{@link UnitState#effectivePosition}）。
 *
 * <p>★ **速度量纲（2026-09-24 日制裁定）**：{@code speed} 的单位是 **MP/小时**（数值语义即此，不是 MP/tick）。日制下 **一天的行程预算 =
 * {@code speed × 1000 × 24} 毫 MP**（常数见 {@link io.mosire.simos.unit.move.UnitMoves#HOURS_PER_DAY}）；
 * 速度**不**预先折算成"每日"——换算只在 {@code UnitMoves.evaluate} 的预算公式里做一处。
 *
 * <p>★ **Unit 扩容 T1 的四个新字段**（spec §一.3 / §三.2）：{@code status}（三态，普通字段）、{@code attached}/{@code
 * offset} （{@code Formation}：是否跟随父 + 相对父的站位，与 {@code parent} 同形的时态序列）、{@code
 * rejoinTarget}（回归意图，普通字段）。 四者的默认值必须让**旧档行为一字不变**：{@code MOVING} / {@code true} / {@code empty} /
 * {@code empty}。旧 9 参签名由下面的**兼容构造器**保留（生产拷贝点一律走 canonical 形态，避免丢字段）。
 *
 * <p>★ **视野半径（权限阶段 Task 1 / spec §4.1）**：{@code visionRadius} = 六角圈数，**缺省 1**（用户裁定⑤），{@code 0}
 * 表示只看自身格。**本轮只加字段**——迷雾/探测/遮挡不在本轮（用户："具体的视野功能后面再在 unit 里面写"）；它当前唯一的读者是 军队决策人的可见范围函数（{@code
 * ArmyScope}，按军队位置 + 本半径算可见 hex）。 与 T1 四字段同一条纪律：兼容构造器取 {@link #DEFAULT_VISION_RADIUS}，**生产拷贝点一律走
 * canonical 18 参形态**（漏传 = 静默丢字段——包括后来加的第 15/16/17 组件，本仓最贵的教训形态）。
 *
 * <p>★ **管辖（辖区阶段 5，2026-09-30）**：第 15 组件 {@code jurisdiction} = 单位侧的管辖富结构（管辖区域 + 每区域长期税率 + 一次性抽取上限
 * + 行政能力）。缺省 {@link Optional#empty()} ⇒ 旧档/旧调用点行为逐字不变；**所有重建既有 Unit 的拷贝点都必须原样带过 {@code
 * before.jurisdiction()}**（漏传 = 静默丢管辖，同一条最贵教训）。
 *
 * <p>★★ **家户容纳（S3a，2026-10-09）**：第 17 组件 {@code households} = 本单位容纳的 {@link HouseholdId}
 * 列表（保序、冻结、不得 null/含 null/重复）。★ <b>它不是第二本人数</b>：人口真值仍在 Social 家户的成员批次里，读口用 {@code
 * PopulationLookup.unitPopulation(unitId)} 现算（架构 §5）。★★ <b>所有重建既有 Unit 的拷贝点都必须原样带过 {@code
 * before.households()}</b>（漏传 = 静默丢家户，本仓最贵教训的共同形态）；创建点显式给空表。 跨单位不变量（同一家户不得同时属于两个 Unit、Unit id 不得与
 * household id 撞名）由 {@link UnitState} 构造期把关。
 *
 * <p>★ **编制模块（阶段 9，2026-09-30）**：第 16 组件 {@code module} = 单位侧的编制标签（{@link UnitModule} 的 sealed 子类型：
 * {@link GovernmentFormation} 或 {@link ArmyFormation}，一单位至多一个，互斥由类型保证）。缺省 {@link Optional#empty()}
 * ⇒ 旧档/旧调用点行为逐字不变；**所有重建既有 Unit 的拷贝点都必须原样带过 {@code before.module()}**（漏传 = 静默丢编制，同一条最贵教训）； 创建点显式给
 * {@link Optional#empty()}。★ 它只放编制成分/隶属/层级，**不算任何力量**（用户裁定 1/2：行政力与战斗力分开算，分别归 gov/army 模块）。
 *
 * <p>★ **状态描述地址（阶段 D1，2026-10-02 / D-012）**：第 17 组件 {@code stateDescriptions} = **当前回合状态**（自由文本键）→
 * **状态描述地址**（canonical 地址文本）的链接表。★ **本模块不解析目标域**（不 import army/sd/gov 等域）：只校验地址文本是 canonical 形态（语法走
 * util 的 {@link Address}，全仓唯一拼写点），"这个地址指向什么"由读侧/各域解析器回答——交战记录、公文处理状态等都只是 "某个地址"，Unit 对此一无所知。缺省空表 ⇒
 * 旧档/旧调用点行为逐字不变；**所有重建既有 Unit 的拷贝点都必须原样带过 {@code before.stateDescriptions()}**（漏传 =
 * 静默丢链接，同一条最贵教训）；创建点显式给空表。
 *
 * <p>★★ <b>装备表（阶段 D3a，2026-10-02 / D-006 + 补裁 R1）</b>：第 5 组件 = {@link CompositionEntry}
 * 的**有序**列表（每条 = {@code type} + {@code amount}；类型当前是自然语义 {@link String}，将来可放宽为结构化数据）。
 *
 * <ul>
 *   <li><b>不变量</b>：不得为 null（空列表合法）；每条 {@code type} 非空白、{@code amount ≥ 0}；**同一张表内不得有重复 type**（"同
 *       type 两条"会让"加/减值"歧义，拒绝是刻意的）；顺序是内容的一部分，一律 `List.copyOf` 风格的保序不可变拷贝（**不用**
 *       `Map.copyOf`——它不承诺保序）；
 *   <li><b>不背旧档</b>（D-011 / R4）：{@code member:int} 与 {@code equipment:Map}
 *       的字段与构造器语义**全部删除**，旧档读不出就让它读不出； 世界替换在后续阶段 D6 做，本类不写迁移 shim、不留"双轨"。
 * </ul>
 *
 * <p>★★ <b>{@code Unit.manpower} 已退役（S3b，2026-10-09）</b>：本 record <b>没有</b> manpower 组件。人员人口的唯一来源是
 * Social 家户（{@link #households()} + Social 成员批次现算，读口 {@code PopulationLookup.unitPopulation}）；旧
 * {@code unit.manpower} 的第二本 headcount、以及依赖它的 unit 侧命令载荷一律具名拒（{@code
 * UnitPayloads.rejectRetiredManpower}）。装备照常保留、发放、调整、战损。
 *
 * <p>变更集侧不另写通道：{@code UnitChangeSet} 按 record 组件整份派生（铁律 5），新列表组件自动随 {@code equals} 进往返断言。
 *
 * <p>★ **构造器矩阵（D3a 收敛；S3b 砍 manpower 后为 17 组件）**：canonical = 17 参（record 自动生成、紧凑构造器校验）；另有
 * 8/12/13/14/15/16 参兼容形态，前 8/12/13/14/15/16 个组件同 canonical。六条兼容形态只补"后加的字段"（T1
 * 四件套、视野半径、管辖、编制、状态链接、家的容纳）的缺省值； **生产拷贝点一律走 canonical 17 参**，兼容构造器只服务"那些后加字段没有来源"的创建/测试调用点。
 */
public record Unit(
    UnitId id,
    String name,
    SegmentedSeries<Optional<UnitId>> parent,
    SegmentedSeries<Optional<HexCoord>> position,
    List<CompositionEntry> equipment,
    int speed,
    int mobilityPerMille,
    Optional<Movement> movement,
    UnitStatus status,
    SegmentedSeries<Boolean> attached,
    SegmentedSeries<Optional<RelativeOffset>> offset,
    Optional<UnitId> rejoinTarget,
    int visionRadius,
    Optional<Jurisdiction> jurisdiction,
    Optional<UnitModule> module,
    Map<String, String> stateDescriptions,
    List<HouseholdId> households) {

  /** ★ **缺省视野半径**（spec §4.1 / 用户裁定⑤）= 1 圈（自身 + 六个邻格 = 7 格）。 */
  public static final int DEFAULT_VISION_RADIUS = 1;

  public Unit {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    requireNoEvents(parent, "parent");
    requireNoEvents(position, "position");
    for (Segment<Optional<UnitId>> segment : parent.segments()) {
      if (segment.value().filter(id::equals).isPresent()) {
        throw new IllegalArgumentException("parent 不得指向自身: " + id);
      }
    }
    if (equipment == null) {
      // ★ 同上：equipment 已从 Map 改成有序条目列表，旧档的 {键:值} 对象会在此之前被 Jackson 拒掉。
      throw new IllegalArgumentException("equipment 不得为 null（空列表合法）");
    }
    // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的 Collections.unmodifiable*）。
    equipment = Collections.unmodifiableList(copyComposition(equipment, "equipment"));
    if (speed < 1) {
      throw new IllegalArgumentException("speed 必须 ≥ 1: " + speed);
    }
    if (mobilityPerMille < 1) {
      throw new IllegalArgumentException("mobilityPerMille 必须 ≥ 1: " + mobilityPerMille);
    }
    if (movement == null) {
      throw new IllegalArgumentException("movement 不得为 null（无在途路线用 Optional.empty()）");
    }
    if (status == null) {
      throw new IllegalArgumentException("status 不得为 null");
    }
    requireNoEvents(attached, "attached");
    requireNoEvents(offset, "offset");
    if (rejoinTarget == null) {
      throw new IllegalArgumentException("rejoinTarget 不得为 null（无回归目标用 Optional.empty()）");
    }
    if (visionRadius < 0) {
      throw new IllegalArgumentException("visionRadius 必须 ≥ 0: " + visionRadius);
    }
    if (jurisdiction == null) {
      // ★ 旧档没有 jurisdiction 键 ⇒ Jackson 对 record 的缺参给 null；这里归一成 empty（旧档兼容的落点）。
      jurisdiction = Optional.empty();
    }
    if (module == null) {
      // ★ 旧档没有 module 键 ⇒ Jackson 对 record 的缺参给 null；这里归一成 empty（与 jurisdiction 同款落点）。
      module = Optional.empty();
    }
    if (stateDescriptions == null) {
      // ★ 旧档没有 stateDescriptions 键 ⇒ Jackson 对 record 的缺参给 null；这里归一成空表（与 jurisdiction/module
      // 同款落点）。
      stateDescriptions = Map.of();
    }
    stateDescriptions =
        Collections.unmodifiableMap(copyStateDescriptions(stateDescriptions)); // ★ 冻在赋值处
    if (households == null) {
      // ★ S3a：不做旧档归一（与 manpower/equipment 同款）——家户列表缺席是坏数据，当场读不出，而不是静默变成空表。
      throw new IllegalArgumentException("households 不得为 null（空列表合法）");
    }
    // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的 Collections.unmodifiableList）。
    households = Collections.unmodifiableList(copyHouseholds(households));
  }

  /**
   * ★ **有效移动速度**（spec §三.2 / P5 / P6）：{@code max(1, floorDiv(speed × factorPerMille + 500, 1000))}。
   *
   * <p>★ **下界 1 是缺口 U1 的裁定**：`speed × factor / 1000` 可能 &lt; 1（如 `speed=2`、`ENGAGED ⇒ 0`），而 {@link
   * Movement} 的 `speedAtDeparture ≥ 1` 是硬约束 ⇒ 用与 {@code TerrainMovementCost.scale} 同款的四舍五入后 clamp 到
   * 1。判据夹具用 `speed ≥ 4` 保证三档可区分。
   *
   * <p>★ 它**只在 `planRoute` 时被读一次**并冻进 {@link Movement}——在途改状态**不回溯**（P6）。
   */
  public int effectiveSpeed() {
    return Math.max(1, Math.floorDiv(speed * status.factorPerMille() + 500, 1000));
  }

  /**
   * ★ **8 参兼容构造器**（T1/R1 的对策；S3b 砍 manpower 后由 9 参降为 8 参）：旧 8 参签名（**前 8 个组件**）⇒ {@code status =
   * MOVING}、{@code attached} = 父序列 anchor 时刻的 {@code true}、{@code offset} = 空、{@code rejoinTarget =
   * empty}、{@code visionRadius = }{@link #DEFAULT_VISION_RADIUS}、{@code jurisdiction =
   * empty}、{@code module = empty}、 {@code stateDescriptions = 空表}。
   *
   * <p>★★ <b>它不是 {member, equipment-map} 语义的旧构造器</b>：第 5 个参数（装备）已经是新表形态（{@link CompositionEntry}
   * 列表）。 旧字段语义（单一人数、装备 map）在 D3a **全部删除**，本构造器只保留"后加的字段没有来源"这一条便民口径。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      List<CompositionEntry> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement) {
    this(
        id,
        name,
        parent,
        position,
        equipment,
        speed,
        mobilityPerMille,
        movement,
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(anchorOf(parent), true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(anchorOf(parent), Optional.<RelativeOffset>empty())),
            List.of(),
            null),
        Optional.empty(),
        DEFAULT_VISION_RADIUS,
        Optional.<Jurisdiction>empty());
  }

  /**
   * ★ **12 参兼容构造器**（权限阶段 Task 1 / spec §4.1；S3b 砍 manpower 后由 13 参降为 12 参）：前 12 个组件（截至 {@code
   * rejoinTarget}）⇒ 只补 {@code visionRadius = }{@link #DEFAULT_VISION_RADIUS}、{@code jurisdiction =
   * empty}、{@code module = empty} 与 {@code stateDescriptions = 空表}。
   *
   * <p>**为什么需要它**：这些调用点不是"忘了新字段"的拷贝点——视野半径对它们而言没有来源，取缺省正是**唯一正确**的语义。
   *
   * <p>★ **它同样不是生产拷贝点该用的形状**：拷贝点有来源（{@code 原.visionRadius()} / {@code 原.jurisdiction()} / {@code
   * 原.module()} / {@code 原.stateDescriptions()}），走 canonical 17 参。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      List<CompositionEntry> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement,
      UnitStatus status,
      SegmentedSeries<Boolean> attached,
      SegmentedSeries<Optional<RelativeOffset>> offset,
      Optional<UnitId> rejoinTarget) {
    this(
        id,
        name,
        parent,
        position,
        equipment,
        speed,
        mobilityPerMille,
        movement,
        status,
        attached,
        offset,
        rejoinTarget,
        DEFAULT_VISION_RADIUS);
  }

  /**
   * ★ **13 参兼容构造器**（辖区阶段 5，2026-09-30；S3b 砍 manpower 后由 14 参降为 13 参）：前 13 个组件（截至 {@code
   * visionRadius}）⇒ 只补 {@code jurisdiction = empty} 与 {@code module = empty}、{@code
   * stateDescriptions = 空表}。
   *
   * <p>**为什么需要它**：辖区对它们而言没有来源，取空管辖正是**唯一正确**的语义。
   *
   * <p>★ **它同样不是生产拷贝点该用的形状**：拷贝点有来源（{@code 原.jurisdiction()} / {@code 原.module()} / {@code
   * 原.stateDescriptions()}），走 canonical 17 参——漏传 = 静默丢管辖/编制/链接/家户。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      List<CompositionEntry> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement,
      UnitStatus status,
      SegmentedSeries<Boolean> attached,
      SegmentedSeries<Optional<RelativeOffset>> offset,
      Optional<UnitId> rejoinTarget,
      int visionRadius) {
    this(
        id,
        name,
        parent,
        position,
        equipment,
        speed,
        mobilityPerMille,
        movement,
        status,
        attached,
        offset,
        rejoinTarget,
        visionRadius,
        Optional.<Jurisdiction>empty());
  }

  /**
   * ★ **14 参兼容构造器**（阶段 9，2026-09-30；S3b 砍 manpower 后由 15 参降为 14 参）：前 14 个组件（截至 {@code
   * jurisdiction}）⇒ 只补 {@code module = empty} 与 {@code stateDescriptions = 空表}。
   *
   * <p>**为什么需要它**：编制模块对它们而言没有来源，取空编制正是**唯一正确**的语义。
   *
   * <p>★ **它同样不是生产拷贝点该用的形状**：拷贝点有来源（{@code 原.module()} / {@code 原.stateDescriptions()} / {@code
   * 原.households()}），走 canonical 17 参——漏传 = 静默丢编制/链接/家户。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      List<CompositionEntry> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement,
      UnitStatus status,
      SegmentedSeries<Boolean> attached,
      SegmentedSeries<Optional<RelativeOffset>> offset,
      Optional<UnitId> rejoinTarget,
      int visionRadius,
      Optional<Jurisdiction> jurisdiction) {
    this(
        id,
        name,
        parent,
        position,
        equipment,
        speed,
        mobilityPerMille,
        movement,
        status,
        attached,
        offset,
        rejoinTarget,
        visionRadius,
        jurisdiction,
        Optional.<UnitModule>empty());
  }

  /**
   * ★ **15 参兼容构造器**（阶段 D1，2026-10-02；S3b 砍 manpower 后由 16 参降为 15 参）：前 15 个组件（截至 {@code module}）⇒ 只补
   * {@code stateDescriptions = 空表}（S3a 起再补第 17 组件 {@code households = 空表}）。
   *
   * <p>**为什么需要它**：第 17 组件落地前写的调用点按"16 参规范形态"写（{@code jurisdiction}/{@code module}
   * 有来源、状态链接没有），取空表正是**唯一正确**的语义；有了它，新增第 17 组件不会把既有 16 参调用点逼成编译错误。
   *
   * <p>★ **它同样不是生产拷贝点该用的形状**：拷贝点有来源（{@code 原.stateDescriptions()} / {@code 原.households()}），走
   * canonical 18 参——漏传 = 静默丢链接/家户。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      List<CompositionEntry> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement,
      UnitStatus status,
      SegmentedSeries<Boolean> attached,
      SegmentedSeries<Optional<RelativeOffset>> offset,
      Optional<UnitId> rejoinTarget,
      int visionRadius,
      Optional<Jurisdiction> jurisdiction,
      Optional<UnitModule> module) {
    this(
        id,
        name,
        parent,
        position,
        equipment,
        speed,
        mobilityPerMille,
        movement,
        status,
        attached,
        offset,
        rejoinTarget,
        visionRadius,
        jurisdiction,
        module,
        Map.of(),
        List.of());
  }

  /**
   * ★ **16 参兼容构造器**（S3a，2026-10-09；S3b 砍 manpower 后由 17 参降为 16 参）：旧 canonical 形态（前 16 个组件，截至 {@code
   * stateDescriptions}）⇒ 只补第 17 组件 {@code households = 空表}。
   *
   * <p>**为什么需要它**：第 17 组件落地前写的调用点（含测试夹具）按"17 参规范形态"写，家户列表对它们而言没有来源，取空表正是**唯一正确** 的语义；有了它，新增第 17
   * 组件不会把既有 17 参调用点逼成编译错误。
   *
   * <p>★★ **它同样不是生产拷贝点该用的形状**：拷贝点有来源（{@code 原.households()}），走 canonical 17 参——漏传 = 静默丢家户。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      List<CompositionEntry> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement,
      UnitStatus status,
      SegmentedSeries<Boolean> attached,
      SegmentedSeries<Optional<RelativeOffset>> offset,
      Optional<UnitId> rejoinTarget,
      int visionRadius,
      Optional<Jurisdiction> jurisdiction,
      Optional<UnitModule> module,
      Map<String, String> stateDescriptions) {
    this(
        id,
        name,
        parent,
        position,
        equipment,
        speed,
        mobilityPerMille,
        movement,
        status,
        attached,
        offset,
        rejoinTarget,
        visionRadius,
        jurisdiction,
        module,
        stateDescriptions,
        List.of());
  }

  /** 兼容构造器的锚时刻取 {@code parent} 的首段（{@code parent} 不得为 null、构造期保证至少一段）。 */
  private static SimosTimestamp anchorOf(SegmentedSeries<?> series) {
    if (series == null) {
      throw new IllegalArgumentException("parent 不得为 null");
    }
    return series.segments().get(0).from();
  }

  /** ★ 两条时态序列的变化一律用"追加段"表达：`ADD` 对 `Optional` 无定义，`SET` 与段重复（spec §4.1 第 3 条）。 */
  private static void requireNoEvents(TemporalSeries<?> series, String field) {
    if (series == null) {
      throw new IllegalArgumentException(field + " 不得为 null");
    }
    if (!series.events().isEmpty()) {
      throw new IllegalArgumentException(field + " 不得带事件：变化一律用追加段表达（spec §4.1）");
    }
  }

  /**
   * 拷贝 + 校验人力/装备表（不做冻结，赋值处冻结——同原先 {@code copyEquipment} 的 SpotBugs 口径）。
   *
   * <p>三条不变量在这里落一次、{@link CompositionEntry} 自己落一次（{@code type} 非空白、{@code amount ≥ 0}）：同 type 重复是
   * 本方法特有的判据——"同一张表里两条同 type"会让后续"加/减值"语义歧义，**刻意拒绝**（D3a 任务书建议项，写进类注）。
   */
  private static List<CompositionEntry> copyComposition(
      List<CompositionEntry> entries, String field) {
    List<CompositionEntry> copy = new ArrayList<>(entries.size());
    Set<String> seen = new HashSet<>();
    for (CompositionEntry entry : entries) {
      if (entry == null) {
        throw new IllegalArgumentException(field + " 的元素不得为 null");
      }
      if (!seen.add(entry.type())) {
        throw new IllegalArgumentException(field + " 不得有重复 type: " + entry.type());
      }
      copy.add(entry);
    }
    return copy;
  }

  /**
   * 拷贝 + 校验家户列表（不做冻结，赋值处冻结——同 {@link #copyComposition} 的 SpotBugs 口径）。
   *
   * <p>三条不变量：非 null（调用方已判）、元素不得为 null、同一 unit 内不得重复 household id；顺序是内容的一部分（保序不可变拷贝）。 ★
   * 跨单位不变量（同一家户不得同时属于两个 Unit、Unit id 不得与 household id 撞名）不在本类型里做——那要看到整张 {@code units} 表，归 {@link
   * UnitState} 构造期。
   */
  private static List<HouseholdId> copyHouseholds(List<HouseholdId> households) {
    List<HouseholdId> copy = new ArrayList<>(households.size());
    Set<HouseholdId> seen = new HashSet<>();
    for (HouseholdId household : households) {
      if (household == null) {
        throw new IllegalArgumentException("households 的元素不得为 null");
      }
      if (!seen.add(household)) {
        throw new IllegalArgumentException("households 不得有重复: " + household);
      }
      copy.add(household);
    }
    return copy;
  }

  /**
   * 拷贝 + 校验状态描述链接（不做冻结，赋值处冻结——同 {@link #copyComposition} 的 SpotBugs 口径）。
   *
   * <p>★ 校验只到**文本形状**这一层：键（状态）非空白；值（地址）非空白且是 canonical 地址文本。地址语法委托 util 的 {@link
   * Address#parse}（全仓唯一拼写点），**不解析目标域**——本模块不知道 {@code army:combat.x} 是交战记录、也不知道 {@code gov:...}
   * 是公文；"这个地址指向什么"由读侧/各域 resolver 回答（铁律 3）。
   *
   * <p>★ 为什么要求 canonical：链接表是**稳定地址**（D-012 的"状态描述地址"接入地址体系），宽容写法（人类形式）会让同一目标有多个 拼写；canonical 唯一性由
   * {@link Address#canonical()} 的往返把守。
   */
  private static Map<String, String> copyStateDescriptions(Map<String, String> stateDescriptions) {
    Map<String, String> copy = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : stateDescriptions.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("stateDescriptions 的键（状态）不得空白");
      }
      String state = entry.getKey();
      String address = entry.getValue();
      if (address == null || address.isBlank()) {
        throw new IllegalArgumentException("stateDescriptions 的地址不得为空白（清除链接请删除该键）: state=" + state);
      }
      Address parsed;
      try {
        parsed = Address.parse(address);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "stateDescriptions 的地址不是合法地址（state="
                + state
                + "）: "
                + address
                + "（"
                + e.getMessage()
                + "）",
            e);
      }
      if (!parsed.canonical().equals(address)) {
        throw new IllegalArgumentException(
            "stateDescriptions 的地址不是 canonical 形态（state="
                + state
                + "）: "
                + address
                + "（canonical="
                + parsed.canonical()
                + "）");
      }
      copy.put(state, address);
    }
    return Collections.unmodifiableMap(copy);
  }
}
