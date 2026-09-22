package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * **国家决策人**的可见范围（spec §3.2）：本国区域。
 *
 * <p>逻辑：扫 {@code GameMap.regions()}，取 {@code region.meta().tag()} **逐字等于** {@link NationTag#tagFor}
 * 的区域 ⇒ 前缀 {@code <mapId>/region/<rid>} 每条。
 *
 * <p>★ **为什么按区域、不逐 hex**：{@code ResourceScope} 的前缀是段边界匹配，逐 hex 要给几万条； 区域级只需几条到几十条（真档 59223 hex、98
 * 区域）。
 *
 * <p>★ **tag 比较用逐字相等**：{@code "nation:FRAX"} 不是 {@code "nation:FRA"}——
 * 写成前缀/子串匹配会把邻国（甚至任意带该前缀的名字）划进本国范围，且**不会报错**。
 *
 * <p>★ **无匹配区域 ⇒ 显式 deny-all**（{@link DecisionScopeFunction#scopeOfPrefixes}）：
 * 国家还没圈地、或区域全被删掉时，这个决策人**什么都看不见**，而不是"回落到不设限"。
 *
 * <p>★ **本轮只表态 {@code map} 命名空间**：{@code unit}/{@code social} 的断言还是字面量 {@code "*"} （{@code
 * ToolSupport#requireUnitRead}），此刻给它们配受限前缀会把读工具**整调拒掉** （spec §5.2 第 2 条：粗断言 + 细围栏 =
 * 整调被拒）——那两维的细粒度化与"读工具按 scope 过滤"同轮做。
 */
public final class NationScope implements DecisionScopeFunction {

  /** 无状态实现 ⇒ 一个实例够用（注册表里按类型存的就是它）。 */
  public static final NationScope INSTANCE = new NationScope();

  private NationScope() {}

  @Override
  public ResourceScopeMap scopesFor(DecisionMaker dm, SimulationState state, String mapId) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(mapId, "mapId");
    if (!(dm.affiliation() instanceof Affiliation.Nation nation)) {
      throw new IllegalArgumentException(
          "NationScope 只服务 Affiliation.Nation，收到 "
              + dm.affiliation().getClass().getName()
              + "（分派由 DecisionScopeFunctions 负责）");
    }
    String nationTag = NationTag.tagFor(nation.nationId());
    GameMap map = ToolSupport.gameMap(state);

    Set<String> prefixes = new TreeSet<>(); // ★ 有序：范围内容与区域迭代序无关（Set.copyOf 不保序）
    for (Region region : map.regions().values()) {
      if (nationTag.equals(region.meta().tag())) {
        prefixes.add(ToolSupport.resourceRegion(mapId, region.id().value()).path());
      }
    }
    return ResourceScopeMap.of(
        ToolSupport.MAP_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(prefixes));
  }
}
