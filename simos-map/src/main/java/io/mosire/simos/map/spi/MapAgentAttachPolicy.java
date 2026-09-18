package io.mosire.simos.map.spi;

import io.mosire.simos.map.resolve.MapResolver;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.spi.AgentAttachPolicy;
import java.util.Objects;

/**
 * 地图侧的可绑性策略（spec §6，M5 T10）：**任意（存在的）{@code Region} 可绑决策人**。
 *
 * <p>★ **取代说明（对 spec §6 / 总纲 §5.5 的"Region 且 {@code type=Nation}"）**：总纲 §5.5 与 spec §6 举的例子是"Region
 * 且 {@code type=Nation}"，但 M2 落地的 {@link io.mosire.simos.map.region.Region} 与 {@link
 * io.mosire.simos.map.region.RegionMeta} 是 {@code (color, tag, description, annexedBy)}——**没有
 * {@code type} 字段**，{@code Nation} 这个概念在领域类型里根本不存在。按控制器裁定，此处实现**老实版本**："可解析为已存在 {@code Region}
 * 即可绑"， 把这个"例子 ←→ 已落地领域类型"的缺口记在 {@code t10-report.md} 的取代说明里，不臆造字段。
 *
 * <p>★ **判定只经本模块自己的类型**：委托 {@link MapResolver} 解析——它认领的 kind 里只有 {@code region} 产出 {@code typeName
 * = "Region"}；地图根（{@code "Map"}）、格（{@code "Hex"}）、城市（{@code "City"}）都落选。 app 层看不到 {@code Region}
 * 这个类，只知道"map 策略说行不行"。
 */
public final class MapAgentAttachPolicy implements AgentAttachPolicy {

  private static final String NAMESPACE = "map";
  private static final String REGION_TYPE = "Region";

  private final MapResolver resolver = new MapResolver();

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public boolean canAttach(Address subject, ResolveContext ctx) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(ctx, "ctx");
    if (!NAMESPACE.equals(subject.namespace())) {
      return false; // 外来命名空间不服务：认领与否由返回值表达，不去触碰本模块的状态
    }
    QueryResult result = resolver.resolve(subject, ctx);
    return result.candidates().stream()
        .anyMatch(candidate -> REGION_TYPE.equals(candidate.typeName()));
  }
}
