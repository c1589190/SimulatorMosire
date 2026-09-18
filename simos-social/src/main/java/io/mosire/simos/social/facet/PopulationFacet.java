package io.mosire.simos.social.facet;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.facet.FacetProvider;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * "此格人口几何"（spec §5.2，M5 T3）：social 模块注册的真 {@link FacetProvider}，关闭总纲 §3.2 的遗留。
 *
 * <p>★ **subject 形态**与 {@link io.mosire.simos.unit.facet.UnitsHereFacet} 逐字同款（{@code
 * map:<mapId>:hex.<q>_<r>}），判定同样只走 {@link Address} AST。非该形态、或该格没有人口序列 ⇒ **空列表**。
 *
 * <p>★ **值直接来自领域 API**：{@code SocialData.populations().get(hex).valueAt(ctx.at())}——本类不重算人口、不缓存。
 * {@code value} 是 {@link Number}（{@code Long}，JSON 友好；spec §〇.3-5 的类型契约），label 是 hex 的展示形（{@code
 * q_r}）。
 *
 * <p>★ 两个模块各自实现同一段 subject 解析，**不共享 helper**：铁律 3——simos-social 不知道 simos-unit，且 map-hex 只是
 * 地址形态（util 层不认识 map 的 {@link HexCoord}）。
 */
public final class PopulationFacet implements FacetProvider {

  private static final String FACET_NAME = "population";
  private static final String MAP_NAMESPACE = "map";
  private static final String SOCIAL_NAMESPACE = "social";
  private static final String HEX_KIND = "hex";

  @Override
  public String facetName() {
    return FACET_NAME;
  }

  @Override
  public List<FacetEntry> query(Address subject, ResolveContext ctx) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(ctx, "ctx");
    // ★ 先判 subject 形状再碰 state：外来主体不该因为"状态里没有 social 切片"而抛。
    Optional<HexCoord> hex = hexSubject(subject);
    if (hex.isEmpty()) {
      return List.of();
    }
    SocialData data = dataOf(ctx);
    PopulationSeries series = data.populations().get(hex.get());
    if (series == null) {
      return List.of(); // 合法的一格，但本世界没有它的人口序列：空列表，不是错误
    }
    long value = series.valueAt(ctx.at());
    return List.of(new FacetEntry(SOCIAL_NAMESPACE, hex.get().toString(), "Population", value));
  }

  /**
   * 从 {@link Address} AST 判"这是不是一个 map-hex 主体"，是则给出坐标（与 unit 侧同形，理由不重复）。
   *
   * <p>只认 canonical 的 {@code hex.<q>_<r>}（{@link Entity} 段）；Index Human 形与非法坐标名一律返回空。
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
      return Optional.empty();
    }
  }

  /** 切片只能从 social 模块拿（铁律 3/4）。缺席或类型不对都是装配故障。 */
  private static SocialData dataOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(SOCIAL_NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 social 模块切片——PopulationFacet 需要 SocialSnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalArgumentException(
          "social 模块切片不是 SocialSnapshot：" + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }
}
