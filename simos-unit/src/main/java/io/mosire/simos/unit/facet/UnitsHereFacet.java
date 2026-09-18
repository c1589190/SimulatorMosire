package io.mosire.simos.unit.facet;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.facet.FacetProvider;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * "此格上有哪些单位"（spec §5.2，M5 T3）：unit 模块注册的第一个真 {@link FacetProvider}，关闭总纲 §3.2 的遗留。
 *
 * <p>★ **subject 形态**：{@code map:<mapId>:hex.<q>_<r>}——只用 {@link Address} AST 判定（**不字符串切分**）， 第 2
 * 段是缺 kind 的根主体、第 3 段是 {@code kind=hex} 的 {@link Entity}。非该形态（别命名空间、别 kind、Index Human
 * 形、段数不符、名字非坐标）一律**空列表**——空列表 = "这一面在此主体上没有内容"，不是错误（{@link FacetProvider} 契约）。
 *
 * <p>★ **一次一件**：每个命中单位产出一个 {@link FacetEntry}，{@code value} 是该单位的 canonical 地址 {@code
 * unit:<id>}（String，JSON 友好；spec §〇.3-5 的类型契约）。条目按 **unit id 字典序**排序——装配顺序与查询次数都改不了它。
 *
 * <p>★ **位置用 {@link UnitState#effectivePosition(UnitId,
 * io.mosire.simos.util.time.SimosTimestamp)}**： 自身无位置时向父取，故"编制上的父在哪、未单独部署的子就在哪"这一语义照 M3 口径直接成立。
 *
 * <p>★ **canonical 只能由 {@link Address} AST 构造后调 {@code canonical()} 产出**（M1 §3.4 的按需加引规则不在本类重实现）。
 */
public final class UnitsHereFacet implements FacetProvider {

  private static final String FACET_NAME = "unitsHere";
  private static final String MAP_NAMESPACE = "map";
  private static final String UNIT_NAMESPACE = "unit";
  private static final String HEX_KIND = "hex";

  @Override
  public String facetName() {
    return FACET_NAME;
  }

  @Override
  public List<FacetEntry> query(Address subject, ResolveContext ctx) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(ctx, "ctx");
    // ★ 先判 subject 形状再碰 state：外来主体不该因为"状态里没有 unit 切片"而抛（空列表语义优先）。
    Optional<HexCoord> hex = hexSubject(subject);
    if (hex.isEmpty()) {
      return List.of();
    }
    UnitState state = stateOf(ctx);
    List<Unit> here = new ArrayList<>();
    for (Unit unit : state.units().values()) {
      if (state.effectivePosition(unit.id(), ctx.at()).filter(hex.get()::equals).isPresent()) {
        here.add(unit);
      }
    }
    here.sort(Comparator.comparing(unit -> unit.id().value()));
    List<FacetEntry> entries = new ArrayList<>(here.size());
    for (Unit unit : here) {
      entries.add(
          new FacetEntry(UNIT_NAMESPACE, unit.name(), "Unit", canonicalUnitAddress(unit.id())));
    }
    return List.copyOf(entries);
  }

  /**
   * 从 {@link Address} AST 判"这是不是一个 map-hex 主体"，是则给出坐标。
   *
   * <p>只认 canonical 的 {@code hex.<q>_<r>}（{@link Entity} 段）；Index Human 形（{@code
   * map:m1:[q,r]}）**不服务**—— facet 的输入契约是 canonical 主体（spec §5.2）。名字不是合法坐标时同样返回空：对 facet
   * 而言它只是"我不服务的主体"。
   */
  private static Optional<HexCoord> hexSubject(Address subject) {
    if (!MAP_NAMESPACE.equals(subject.namespace())) {
      return Optional.empty();
    }
    List<AddressSegment> segments = subject.segments();
    if (segments.size() != 3) {
      return Optional.empty();
    }
    if (!(segments.get(1) instanceof Entity root) || root.kind().isPresent()) {
      return Optional.empty();
    }
    if (!(segments.get(2) instanceof Entity hex) || hex.kind().isEmpty()) {
      return Optional.empty();
    }
    if (!HEX_KIND.equals(hex.kind().get())) {
      return Optional.empty();
    }
    try {
      return Optional.of(HexCoord.parse(hex.name()));
    } catch (IllegalArgumentException e) {
      return Optional.empty(); // 认领了 kind 但名字非法 ⇒ 仍然是"我不服务的主体"，不升级成错误
    }
  }

  /** canonical 只由 AST 产出（R13/U2 的纪律）。 */
  private static String canonicalUnitAddress(UnitId id) {
    Address canonical = new Address(List.of(new Namespace(UNIT_NAMESPACE), Entity.of(id.value())));
    return canonical.canonical();
  }

  /** 切片只能从 unit 模块拿（铁律 3/4：SimulationState 没有跨模块访问器）。缺席或类型不对都是装配故障。 */
  private static UnitState stateOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(UNIT_NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 unit 模块切片——UnitsHereFacet 需要 UnitSnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalArgumentException(
          "unit 模块切片不是 UnitSnapshot：" + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }
}
