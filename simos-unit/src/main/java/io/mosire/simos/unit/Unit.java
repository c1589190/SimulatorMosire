package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TemporalSeries;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * canonical 16 参形态**（漏传 = 静默丢字段——包括后来加的第 15/16 组件，本仓最贵的教训形态）。
 *
 * <p>★ **管辖（辖区阶段 5，2026-09-30）**：第 15 组件 {@code jurisdiction} = 单位侧的管辖富结构（管辖区域 + 每区域长期税率 + 一次性抽取上限
 * + 行政能力）。缺省 {@link Optional#empty()} ⇒ 旧档/旧调用点行为逐字不变；**所有重建既有 Unit 的拷贝点都必须原样带过 {@code
 * before.jurisdiction()}**（漏传 = 静默丢管辖，同一条最贵教训）。
 *
 * <p>★ **编制模块（阶段 9，2026-09-30）**：第 16 组件 {@code module} = 单位侧的编制标签（{@link UnitModule} 的 sealed 子类型：
 * {@link GovFormation} 或 {@link ArmyFormation}，一单位至多一个，互斥由类型保证）。缺省 {@link Optional#empty()} ⇒
 * 旧档/旧调用点行为逐字不变；**所有重建既有 Unit 的拷贝点都必须原样带过 {@code before.module()}**（漏传 = 静默丢编制，同一条最贵教训）； 创建点显式给
 * {@link Optional#empty()}。★ 它只放编制成分/隶属/层级，**不算任何力量**（用户裁定 1/2：行政力与战斗力分开算，分别归 gov/army 模块）。
 *
 * <p>★ **状态描述地址（阶段 D1，2026-10-02 / D-012）**：第 17 组件 {@code stateDescriptions} = **当前回合状态**（自由文本键）→
 * **状态描述地址**（canonical 地址文本）的链接表。★ **本模块不解析目标域**（不 import army/sd/gov 等域）：只校验地址文本是 canonical 形态（语法走
 * util 的 {@link Address}，全仓唯一拼写点），"这个地址指向什么"由读侧/各域解析器回答——交战记录、公文处理状态等都只是 "某个地址"，Unit 对此一无所知。缺省空表 ⇒
 * 旧档/旧调用点行为逐字不变；**所有重建既有 Unit 的拷贝点都必须原样带过 {@code before.stateDescriptions()}**（漏传 =
 * 静默丢链接，同一条最贵教训）；创建点显式给空表。
 */
public record Unit(
    UnitId id,
    String name,
    SegmentedSeries<Optional<UnitId>> parent,
    SegmentedSeries<Optional<HexCoord>> position,
    int member,
    Map<String, Integer> equipment,
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
    if (member < 0) {
      throw new IllegalArgumentException("member 必须 ≥ 0: " + member);
    }
    equipment =
        Collections.unmodifiableMap(
            copyEquipment(equipment)); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
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
   * ★ **兼容构造器**（T1，R1 的对策）：旧 9 参签名 ⇒ 以 {@code parent} 的锚段时刻造 {@code attached}/{@code offset}
   * 的锚段，{@code status = MOVING}、{@code rejoinTarget = empty}、{@code visionRadius = }{@link
   * #DEFAULT_VISION_RADIUS}、{@code jurisdiction = empty}、{@code module = empty}、 {@code
   * stateDescriptions = 空表}。
   *
   * <p>它让全仓约 40 处既有 {@code new Unit(…)} 调用点零改动编过；**生产拷贝点不要用它**（那会丢新字段），一律走 canonical 17 参形态——{@code
   * UnitOperations.copy} / {@code UnitMoves.evaluate} 的 frozen 视图 / {@code
   * UnitTimeParticipant.withPositionAndMovement} 都已改直。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      int member,
      Map<String, Integer> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement) {
    this(
        id,
        name,
        parent,
        position,
        member,
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
   * ★ **第二兼容构造器**（权限阶段 Task 1 / spec §4.1）：T1 的 13 参形态 ⇒ 只补 {@code visionRadius = }{@link
   * #DEFAULT_VISION_RADIUS}、{@code jurisdiction = empty}、{@code module = empty} 与 {@code
   * stateDescriptions = 空表}。
   *
   * <p>**为什么需要它**：T1 那批调用点（夹具与测试里的 13 参规范形态）不是"忘了新字段"的拷贝点——{@code visionRadius}
   * 对它们而言没有来源，取缺省正是**唯一正确**的语义。有了它，新字段不会把既有 13 参调用点逼成编译错误。
   *
   * <p>★ **它同样不是生产拷贝点该用的形状**：拷贝点有来源（{@code 原.visionRadius()} / {@code 原.jurisdiction()} / {@code
   * 原.module()} / {@code 原.stateDescriptions()}），走 canonical 17 参。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      int member,
      Map<String, Integer> equipment,
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
        member,
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
   * ★ **第三兼容构造器**（辖区阶段 5，2026-09-30）：扩容后旧的 14 参 canonical 形态（截至 {@code visionRadius}）⇒ 只补 {@code
   * jurisdiction = empty} 与 {@code module = empty}。
   *
   * <p>**为什么需要它**：既有测试/夹具与少量调用点按 14 参规范形态写（它们不是"忘了新字段"的生产拷贝点——管辖对它们而言没有来源），
   * 取空管辖正是**唯一正确**的语义；有了它，新增第 15 组件不会把既有 14 参调用点逼成编译错误。
   *
   * <p>★ **它同样不是生产拷贝点该用的形状**：拷贝点有来源（{@code 原.jurisdiction()} / {@code 原.module()} / {@code
   * 原.stateDescriptions()}），走 canonical 17 参——漏传 = 静默丢管辖/编制/链接。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      int member,
      Map<String, Integer> equipment,
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
        member,
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
   * ★ **第四兼容构造器**（阶段 9，2026-09-30）：扩容后旧的 15 参 canonical 形态（截至 {@code jurisdiction}）⇒ 只补 {@code
   * module = empty}。
   *
   * <p>**为什么需要它**：第 16 组件落地前写的调用点/夹具按"15 参规范形态"写（{@code jurisdiction} 有来源、编制模块没有），
   * 取空编制正是**唯一正确**的语义；有了它，新增第 16 组件不会把既有 15 参调用点逼成编译错误。
   *
   * <p>★ **它同样不是生产拷贝点该用的形状**：拷贝点有来源（{@code 原.module()} / {@code 原.stateDescriptions()}），走 canonical
   * 17 参——漏传 = 静默丢编制/链接。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      int member,
      Map<String, Integer> equipment,
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
        member,
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
   * ★ **第五兼容构造器**（阶段 D1，2026-10-02）：扩容后旧的 16 参 canonical 形态（截至 {@code module}）⇒ 只补 {@code
   * stateDescriptions = 空表}。
   *
   * <p>**为什么需要它**：第 17 组件落地前写的调用点/夹具按"16 参规范形态"写（{@code jurisdiction}/{@code module}
   * 有来源、状态链接没有），取空表正是**唯一正确**的语义；有了它，新增第 17 组件不会把既有 16 参调用点逼成编译错误。
   *
   * <p>★ **它同样不是生产拷贝点该用的形状**：拷贝点有来源（{@code 原.stateDescriptions()}），走 canonical 17 参——漏传 = 静默丢链接。
   */
  public Unit(
      UnitId id,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      int member,
      Map<String, Integer> equipment,
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
        member,
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
        Map.of());
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
   * 拷贝 + 逐键值查 null（不做冻结）。★ 取代说明（R-6-a，有 M2 Task 5 实测先例）：计划原稿的 {@code frozenEquipment} 在 helper
   * **里** {@code unmodifiableMap} 并返回——SpotBugs 只认赋值处 看得见的 {@code
   * Collections.unmodifiable*}，藏在私有方法里就报 {@code EI_EXPOSE_REP}（实测 verify 报 1 条）。照 GameMap
   * 先例改为"helper 只拷贝校验、赋值处冻结"，行为一字不变。
   */
  private static Map<String, Integer> copyEquipment(Map<String, Integer> equipment) {
    if (equipment == null) {
      throw new IllegalArgumentException("equipment 不得为 null");
    }
    Map<String, Integer> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : equipment.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("equipment 的键不得空白");
      }
      if (entry.getValue() == null || entry.getValue() < 0) {
        throw new IllegalArgumentException("equipment 的值必须 ≥ 0: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  /**
   * 拷贝 + 校验状态描述链接（不做冻结，赋值处冻结——同 {@link #copyEquipment} 的 SpotBugs 口径）。
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
