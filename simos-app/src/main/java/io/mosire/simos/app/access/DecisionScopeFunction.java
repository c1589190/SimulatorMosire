package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.SimulationState;
import java.util.Collection;

/**
 * 决策人可见范围函数（spec §3.1）：**决策人 + 世界状态 → AgentLib 资源范围**。
 *
 * <p>★ **是函数，不是配置**（用户裁定）：不缓存、不落盘、不写进 revision —— **每次调用现算**， 世界状态变则范围变（军队移动 ⇒ 可见格随之变化，判据 J4）。
 *
 * <p>★ **分派按 {@code Affiliation} 的运行时类型**（见 {@link DecisionScopeFunctions}），
 * 故实现类只负责一种归属；调用方拿到的永远是"世界当前状态下的"范围。
 */
@FunctionalInterface
public interface DecisionScopeFunction {

  /**
   * 算这次调用者能碰哪些资源。
   *
   * @param dm 决策人（范围由它的 {@code affiliation} 决定）
   * @param state 当前世界状态（切片只读；现算的输入）
   * @param mapId 地图标识（前缀的第一段；资源路径语法见 spec §3.3）
   */
  ResourceScopeMap scopesFor(DecisionMaker dm, SimulationState state, String mapId);

  /**
   * ★ **"什么都没有"的唯一写法**：空前缀集 ⇒ {@link ResourceScope#none()}（显式 deny-all）。
   *
   * <p>为什么不写 {@code ResourceScope.of()} / 空 {@code ResourceScopeMap}： ①{@code ResourceScope.of()}
   * 无参直接就抛（"要哪里都不许请用 none()"——AgentLib 自己在构造期拦）； ②**空图是"本层不表态"**，会回落到工具的 {@code ResourceManifest}
   * 缺省策略 ⇒ **静默放宽**， 与"够不着"行为相反（spec §5.2 第 3 条）。
   *
   * <p>放在接口上而不是各实现里各写一遍：这条语义错了不会报错、只会静默放宽，**只允许有一个写点**。
   */
  static ResourceScope scopeOfPrefixes(Collection<String> prefixes) {
    return prefixes.isEmpty()
        ? ResourceScope.none()
        : ResourceScope.of(prefixes.toArray(String[]::new));
  }
}
