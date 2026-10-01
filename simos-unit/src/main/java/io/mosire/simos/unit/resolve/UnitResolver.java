package io.mosire.simos.unit.resolve;

import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.Resolver;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit:} 命名空间的地址解析器（M3 spec §4.8）。**canonical = {@code unit:<unitId>}**（用户裁定 U2）：
 * 链式地址（{@code unit:"高地人旅指挥部.1营指挥部"}）是**定位/人读形式**，与 ID 形两路都收，**canonical 一律回 ID**。
 *
 * <p>★ **两段地址的判据：先按 ID 查，未命中即走链式定位。**（**取代说明（计划期 R-12-a）**：计划原稿是"未命中且名字含 {@code .} 才走链式"，但它与计划自己的用例
 * {@code chainWithMultipleHitsIsOrderedByUnitId} 自相矛盾—— {@code unit:"同名连"}
 * 是**单元素链**（无点），按原守卫直接空候选，而用例期望 2 个候选。spec §4.8 的链式定位 同样不要求链"必须含点"。故删掉该守卫：ID 未命中即按 {@code .}
 * 切分走链式，单元素链 = 在根层按 name 匹配。 原"ID 优先 = 铁律 1 身份优先于名字"的理由仍成立；连带核查过 {@code unit:u-ghost}（根层无人叫 u-ghost
 * ⇒ 仍空候选）， 其余用例不受影响。M1 的地址 AST 不保留引号，{@code unit:u-f82a} 与 {@code unit:"A.B.C"} 结构同形，只能按内容分；
 * 含点的名字只能用 ID 形定位（spec §4.8 记录在案的取舍）。）
 *
 * <p>★ 链式定位只服务**两段地址**（{@code unit:"链":equipment.X} 这类组合 ⇒ 空候选，避免链多解 × 子实体的组合爆炸）。
 *
 * <p>**空候选与抛的分工**同 {@code MapResolver}/{@code SocialResolver}：合法但不服务的一律空候选；
 * 抛只有两处——装配故障与认领路径上**名字解析失败**（{@code UnitId.parse} 自己的 IAE，不包不吞）。
 */
public final class UnitResolver implements Resolver {

  private static final String NAMESPACE = "unit";

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    if (!NAMESPACE.equals(address.namespace())) {
      return empty(); // 认领与否由返回值表达；未知命名空间抛是注册表的职责
    }
    // 装配故障先于形状判定：解析任何 unit: 地址时就炸，不留到某条查询路径上静默 miss
    UnitState state = stateOf(ctx);
    SimosTimestamp at = ctx.at();
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity second) || second.kind().isPresent()) {
      return empty(); // 第 2 段必须是缺 kind 的根主体；unit:hex.4_3 等在此列
    }
    if (segments.size() == 2) {
      return resolveRootLevel(state, second.name(), at);
    }
    if (segments.size() > 3) {
      return empty(); // 属性访问（unit:u1:equipment.步枪:count）M3 不服务
    }
    return resolveChild(state, second.name(), segments.get(2));
  }

  /** 两段：先 ID、后链式（取代说明见类注释 R-12-a：未命中即走链式，不要求名字含 {@code .}）。 */
  private static QueryResult resolveRootLevel(UnitState state, String name, SimosTimestamp at) {
    UnitId id = UnitId.parse(name);
    Unit byId = state.units().get(id);
    if (byId != null) {
      return single(byId, "Unit");
    }
    return resolveChain(state, List.of(name.split("\\.", -1)), at);
  }

  /** 链式定位：按查询时刻 {@code at} 从**无父者**逐级按 {@code name} 匹配；每个命中都是一个候选。 */
  private static QueryResult resolveChain(UnitState state, List<String> names, SimosTimestamp at) {
    List<Unit> frontier = new ArrayList<>();
    for (Unit unit : state.units().values()) {
      if (unit.parent().valueAt(at).isEmpty()) {
        frontier.add(unit);
      }
    }
    for (int level = 0; level < names.size(); level++) {
      String wanted = names.get(level);
      List<Unit> hits = new ArrayList<>();
      for (Unit unit : frontier) {
        if (unit.name().equals(wanted)) {
          hits.add(unit);
        }
      }
      if (hits.isEmpty()) {
        return empty(); // 合法但任一级无命中：空候选，不是错误
      }
      if (level == names.size() - 1) {
        hits.sort(Comparator.comparing(unit -> unit.id().value())); // 多解按 UnitId 字典序保序
        List<ResolvedSubject> subjects = new ArrayList<>(hits.size());
        for (Unit hit : hits) {
          subjects.add(subjectOf(hit, "Unit"));
        }
        return new QueryResult(subjects);
      }
      frontier = childrenAt(state, hits, at);
    }
    return empty();
  }

  /** 下一级：在查询时刻 {@code at} 以这些命中为父的单位。 */
  private static List<Unit> childrenAt(UnitState state, List<Unit> parents, SimosTimestamp at) {
    List<Unit> children = new ArrayList<>();
    for (Unit unit : state.units().values()) {
      Optional<UnitId> parent = unit.parent().valueAt(at);
      if (parent.isPresent()
          && parents.stream().anyMatch(candidate -> candidate.id().equals(parent.get()))) {
        children.add(unit);
      }
    }
    return children;
  }

  /** 三段：只服务 {@code unit:<id>:equipment.<名>}（ID 形；链式后接子实体 ⇒ 空候选）。 */
  private static QueryResult resolveChild(UnitState state, String name, AddressSegment third) {
    if (!(third instanceof Entity entity) || entity.kind().isEmpty()) {
      return empty(); // Property 段与缺 kind 的实体不服务
    }
    UnitId id = UnitId.parse(name);
    Unit unit = state.units().get(id);
    if (unit == null) {
      return empty();
    }
    if (!"equipment".equals(entity.kind().get())) {
      return empty(); // hex.c1 等合法地址，M3 不服务
    }
    if (unit.equipment().stream().noneMatch(entry -> entry.type().equals(entity.name()))) {
      return empty(); // 合法但没有该类型装备：空候选，不是错误
    }
    SubjectId subjectId = new SubjectId("unit.equipment", id.value() + "/" + entity.name());
    Address canonical =
        new Address(
            List.of(
                new Namespace(NAMESPACE),
                Entity.of(id.value()),
                Entity.of("equipment", entity.name())));
    return new QueryResult(
        List.of(new ResolvedSubject(subjectId, canonical.canonical(), "Equipment")));
  }

  private static ResolvedSubject subjectOf(Unit unit, String typeName) {
    Address canonical =
        new Address(List.of(new Namespace(NAMESPACE), Entity.of(unit.id().value())));
    return new ResolvedSubject(
        new SubjectId(NAMESPACE, unit.id().value()), canonical.canonical(), typeName);
  }

  /** 切片只能从 unit 模块拿（铁律 3/4：SimulationState 没有跨模块访问器）。缺席或类型不对都是装配故障。 */
  private static UnitState stateOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 unit 模块切片——UnitResolver 需要 UnitSnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalArgumentException(
          "unit 模块切片不是 UnitSnapshot：" + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  // ★ canonical 一律由 Address AST 构造后调 canonical() 产出（R13/U2）：§3.4 的加引规则不许在这里手写重实现。

  private static QueryResult single(Unit unit, String typeName) {
    return new QueryResult(List.of(subjectOf(unit, typeName)));
  }

  private static QueryResult empty() {
    return new QueryResult(List.of());
  }
}
