package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 资源维**不空转**的证明：把范围函数算出来的东西交回 **AgentLib 自己的判定器**（{@link ResourceAuthorizer}）， 看它对"具体的资源"怎么判。
 *
 * <p>★ 为什么要有这一层：{@code NationScopeTest}/{@code ArmyScopeTest} 断言的是**前缀字符串**——
 * 字符串对不代表"判定链真的会拦"。这里换一条链证明拼出来的路径是**同一种货币**：判定器拿到的 {@code ResourceId} 正是工具调 {@code require}/{@code
 * allows} 时给的那一个（同一对 (namespace, path)）。
 *
 * <p>★ 三条语义（spec §5.2 逐条）在这里被**实测**而非推断：
 *
 * <ol>
 *   <li>同一工具、同一资源、两个调用者 ⇒ 一个放过一个拒（"资源维不是装饰"的唯一证明）；
 *   <li>**粗断言 × 细围栏 = 整调被拒**（不是"部分可见"）⇒ 所以细粒度围栏必须与工具侧的细粒度断言 **成对上线**（本阶段的 T2 与 T10 是成对的）；
 *   <li>**空图（不表态）放行、{@code none()} 拦下**——两条方向相反，配错即静默放宽。
 * </ol>
 */
class ScopeFenceTest {

  private static final SimulationState STATE =
      ScopeFixtures.state(
          ScopeFixtures.mapOf(
              ScopeFixtures.nationRegion("701", "FRA", new HexCoord(1, 1)),
              ScopeFixtures.nationRegion("201", "GER", new HexCoord(1, 2))),
          ScopeFixtures.units(ScopeFixtures.unitWithVision("u-1", new HexCoord(1, 1), 1)),
          ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));

  /** 一个决策人的权限集：白名单不设限，只有**资源维**在说话（这样红的就一定是范围）。 */
  private static ResourceAuthorizer authorizerFor(ResourceScopeMap scopes) {
    return ResourceAuthorizer.of(
        new AgentPermissionSet(
            AccessToken.DEFAULT,
            Set.of(AgentPermissionSet.ALL_TOOLS),
            Set.of(),
            true,
            true,
            false,
            scopes),
        ToolSupport.MAP_READ);
  }

  private static ResourceScopeMap nationScopes(String nationId) {
    return DecisionScopeFunctions.defaults()
        .scopesFor(ScopeFixtures.nationDm("dm-" + nationId, nationId), STATE, ScopeFixtures.MAP_ID);
  }

  @Test
  void oneResourceTwoCallersOnePassesOneIsDenied() {
    ResourceAuthorizer fra = authorizerFor(nationScopes("FRA"));
    ResourceAuthorizer ger = authorizerFor(nationScopes("GER"));

    assertThat(fra.allows(Operation.READ, ToolSupport.resourceRegion("demo", "701")))
        .as("FRA 的区域：本国决策人读得到")
        .isTrue();
    assertThat(ger.allows(Operation.READ, ToolSupport.resourceRegion("demo", "701")))
        .as("同一资源、同一操作，另一个决策人被拒 —— 资源维不是装饰")
        .isFalse();
    assertThat(ger.allows(Operation.READ, ToolSupport.resourceRegion("demo", "201"))).isTrue();
  }

  @Test
  void armyScopeIsAlsoARealFence() {
    ResourceAuthorizer army =
        authorizerFor(
            DecisionScopeFunctions.defaults()
                .scopesFor(ScopeFixtures.armyDm("dm-a1", "a1"), STATE, ScopeFixtures.MAP_ID));

    assertThat(army.allows(Operation.READ, ToolSupport.resourceHex("demo", 1, 1))).isTrue();
    assertThat(army.allows(Operation.READ, ToolSupport.resourceHex("demo", 2, 1)))
        .as("邻格在视野内")
        .isTrue();
    assertThat(army.allows(Operation.READ, ToolSupport.resourceHex("demo", 3, 3)))
        .as("圈外格够不着")
        .isFalse();
  }

  /** ★ spec §5.2 第 2 条：粗断言（{@code map:<mapId>}）+ 细围栏 ⇒ **整调被拒**，不是"部分可见"。 */
  @Test
  void aCoarseAssertionAgainstAFineFenceIsDeniedWholeCall() {
    ResourceAuthorizer fra = authorizerFor(nationScopes("FRA"));

    assertThat(fra.allows(Operation.READ, ToolSupport.resourceRegion("demo", "701"))).isTrue();
    assertThat(
            fra.allows(Operation.READ, io.mosire.agentlib.permission.ResourceId.of("map", "demo")))
        .as("粗断言落在细围栏之外 ⇒ 拒（T2 与 T10 成对上线的原因）")
        .isFalse();
  }

  /** ★ spec §5.2 第 3 条：空图 = "本层不表态" ⇒ 回落工具缺省（这里是 READ_ONLY ⇒ 读放行）。 */
  @Test
  void anEmptyScopeMapIsNotADenial() {
    ResourceAuthorizer undeclared = authorizerFor(ResourceScopeMap.empty());

    assertThat(
            undeclared.allows(
                Operation.READ, io.mosire.agentlib.permission.ResourceId.of("map", "demo")))
        .as("不表态 ⇒ 放行（配错方向就是静默放宽）")
        .isTrue();
    assertThat(undeclared.allows(Operation.WRITE, ToolSupport.resourceRegion("demo", "701")))
        .as("工具缺省策略 READ_ONLY 仍然管得住写")
        .isFalse();
  }

  @Test
  void anExplicitNoneDeniesAndTripsThePreGate() {
    ResourceAuthorizer denied =
        authorizerFor(ResourceScopeMap.of(ToolSupport.MAP_NAMESPACE, ResourceScope.none()));

    assertThat(denied.allows(Operation.READ, ToolSupport.resourceRegion("demo", "701"))).isFalse();
    assertThat(denied.denial()).as("显式 deny-all 还让**声明式前置闸**响（够不着任何声明面 ⇒ 连审批都不问）").isPresent();
  }
}
