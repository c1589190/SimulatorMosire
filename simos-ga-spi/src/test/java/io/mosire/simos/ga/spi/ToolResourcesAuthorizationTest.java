package io.mosire.simos.ga.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.util.spi.ResourcePaths;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>判据 B：本层的 fail-closed 语义 —— 逐条造<b>负向</b>用例（判不出/没表态 ⇒ 拒，不许静默兜底）</b>。
 *
 * <h2>为什么这一层的 fail-closed 必须有人守</h2>
 *
 * <p>{@code ToolResources} 里的三处声明（{@code PEER_WRITE} / {@code ALL_READ}.actor / {@code
 * ALL_WRITE}）都是 <b>策略取值</b>，而策略取值的坏法<b>没有任何症状</b>：{@code ResourceAuthorizer} 的判定表对"调用者未表态"的命名空间回落成
 * <b>工具声明的策略</b> ⇒
 *
 * <pre>
 * PEER_WRITE = UNRESTRICTED ⇒ 未授权的 DM 也能给任何人发信（I2 整条失效）
 * ALL_READ.actor = READ_ONLY ⇒ 未表态的调用者整片放行 actor 面（且声明式前置闸永远通过）
 * </pre>
 *
 * <p>⇒ 本类的每条负向都带一个<b>反事实对照</b>（同一调用者、同一路径，把策略换成"看起来更宽松的那个"⇒ 立刻放行）。<b>没有这条对照，
 * "拒了"可能只是夹具坏了</b>；有了它，用例红的唯一原因就是那个策略被改宽了。
 *
 * <p>★ 全部用例走<b>真判定器</b>（{@code ResourceAuthorizer.of(调用者权限集, 工具声明)} → {@code
 * ToolContext.withResources}），不自己实现判定逻辑——否则测的是夹具，不是契约。
 */
class ToolResourcesAuthorizationTest {

  // ────────────────────────── ① PEER_WRITE：未授权 ⇒ 谁都够不着 ──────────────────────────

  /**
   * ★★ <b>判据 B：{@code peer} 域未表态的调用者够不着任何别人（而自指域由基础可达面单独给）</b>。
   *
   * <p>守的坏法（{@code PEER_WRITE} 的常量注逐字记着这条实测）：把策略从 {@code DENY} 改成 {@code UNRESTRICTED} ⇒ "没被 GM
   * 授权就够不着别人"这条唯一的闸门失效，<b>未授权 DM 能给任何人发信</b>——而症状是"信发出去了"，没有任何一层报错。
   */
  @Test
  void anUnauthorizedDecisionMakerReachesNobodyInThePeerNamespace() {
    ToolContext unauthorized = contextOf(callerStatingNothing(), ToolResources.PEER_WRITE);

    assertThatThrownBy(
            () ->
                unauthorized
                    .resources()
                    .require(Operation.WRITE, ToolResources.resourcePeer("bob")))
        .as("★★ 判据 B：调用者未在 peer 表态 + 声明为 DENY ⇒ 够不着别人（不许静默放行）")
        .isInstanceOf(ResourceDeniedException.class)
        .hasMessageContaining("peer:bob");
    assertThat(unauthorized.resources().denial())
        .as("★★ 声明式前置闸也在执行前就拒（连审批都不问——审批解决不了「够不着」）")
        .isPresent();

    // ★ 反事实对照：同一个未授权调用者，只把策略换成 UNRESTRICTED ⇒ 立刻够得着任何人（证明上面那条拒**靠的是 DENY**）
    ToolContext failOpen =
        contextOf(
            callerStatingNothing(),
            ResourceManifest.of(ToolResources.PEER_NAMESPACE, ResourcePolicy.UNRESTRICTED));
    assertThatCode(
            () -> failOpen.resources().require(Operation.WRITE, ToolResources.resourcePeer("bob")))
        .as("★ 反事实：策略取 UNRESTRICTED 时未授权调用者**畅通** ⇒ 本用例红的唯一原因就是策略被改宽")
        .doesNotThrowAnyException();
    assertThat(failOpen.resources().denial()).isEmpty();

    // ★ 正向对照：调用者显式表态 peer:自己 ⇒ 自指域通（未表态 = 拒，表态 = 通，两者形状相同、只差这一句）
    ToolContext selfFace = contextOf(callerDeclaringPeer("alice"), ToolResources.PEER_WRITE);
    assertThatCode(
            () ->
                selfFace.resources().require(Operation.WRITE, ToolResources.resourcePeer("alice")))
        .as("对照：显式表态 peer:alice ⇒ 自指域通（基础可达面在 narrowTo 之后补的那一段）")
        .doesNotThrowAnyException();
  }

  /**
   * ★★ <b>判据 B：可达面是<b>段边界</b>前缀，不是字符串前缀</b>（{@code peer:alice} 不含 {@code peer:alice2}），且 {@code
   * peer:gm} 是人人皆有的基础面。
   *
   * <p>守的坏法：把前缀当 {@code startsWith} 用（或把决策人 id 拼成 {@code alice/…} 这类带段的形式）⇒ **授权给 alice 等于 授权给
   * alice2**——同一条判据在 fs 围栏上就是"授权 {@code /srv/work} 等于授权 {@code /srv/work-evil}" （{@code
   * ResourceScope} 的类注专门写了这条）。★ 本仓的 DM id 是短名（{@code alice} 与 {@code alice2} 都可能存在）⇒ 这不是假想。
   */
  @Test
  void thePeerPrefixIsASegmentBoundaryNotAStringPrefix() {
    ToolContext alice =
        contextOf(callerDeclaringPeer("alice", ToolResources.GM_ID), ToolResources.PEER_WRITE);

    assertThatCode(
            () -> alice.resources().require(Operation.WRITE, ToolResources.resourcePeer("alice")))
        .as("前提：自己的域通")
        .doesNotThrowAnyException();
    assertThatCode(
            () -> alice.resources().require(Operation.WRITE, ToolResources.resourcePeer("gm")))
        .as("★ DM→GM 的疑问不需要事先授权（peer:gm 在基础可达面上）")
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () -> alice.resources().require(Operation.WRITE, ToolResources.resourcePeer("alice2")))
        .as("★★ 判据 B：peer:alice 不许顺带放行 peer:alice2（字符串前缀 = 授权被静默放大）")
        .isInstanceOf(ResourceDeniedException.class);
    assertThatThrownBy(
            () -> alice.resources().require(Operation.WRITE, ToolResources.resourcePeer("bob")))
        .as("★★ 判据 B：没被授权的人仍然够不着")
        .isInstanceOf(ResourceDeniedException.class);
  }

  // ────────────────────────── ② ALL_READ 的 actor 一维 ──────────────────────────

  /**
   * ★★ <b>判据 B：{@code ALL_READ} 的 {@code actor} 一维对未表态的调用者是<b>拒</b>（fail-closed），而 map/social/unit
   * 三维照旧可读</b>。
   *
   * <p>守的坏法（{@code ALL_READ} 的常量注逐字记着）：actor 一维若抄成 {@code READ_ONLY}，则 <b>未表态的调用者整片放行 actor
   * 面</b>；更要命的是"声明式前置闸"会对任何调用者都算够得着 ⇒ 那九条读工具的前置闸 <b>永远通过</b>（而那道闸正是"够不着就连审批都不问"的地方）。★ 反向也不能错：不声明
   * actor 一维，工具里那条 actor 分支 <b>恒为假</b>（实测症状是"空候选"，看起来像"这格没数据"）。
   */
  @Test
  void theActorDimensionOfAllReadIsClosedForACallerThatStatedNothing() {
    ToolContext unstated = contextOf(callerStatingNothing(), ToolResources.ALL_READ);
    ResourceId actorOfHex = ResourceId.of(ToolResources.ACTOR_NAMESPACE, ResourcePaths.actor(1, 1));

    assertThatCode(
            () ->
                unstated
                    .resources()
                    .require(Operation.READ, ToolResources.resourceHex("Map1", 1, 1)))
        .as("前提：map 维照旧可读")
        .doesNotThrowAnyException();
    assertThatCode(
            () -> unstated.resources().require(Operation.READ, ToolResources.resourceSocial(1, 1)))
        .as("前提：social 维照旧可读")
        .doesNotThrowAnyException();
    assertThatCode(
            () -> unstated.resources().require(Operation.READ, ToolResources.resourceUnit("u-1")))
        .as("前提：unit 维照旧可读")
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> unstated.resources().require(Operation.READ, actorOfHex))
        .as("★★ 判据 B：actor 一维声明为 DENY ⇒ 未表态的调用者读不到（与「没声明」同结果、只有拒因文案不同）")
        .isInstanceOf(ResourceDeniedException.class)
        .hasMessageContaining("actor:1_1");

    // ★ 反事实对照：只把 actor 那一维换成 READ_ONLY ⇒ 同一个未表态的调用者立刻读得到（这正是要防的静默放宽）
    ToolContext widened =
        contextOf(
            callerStatingNothing(),
            ResourceManifest.of(
                Map.of(
                    ToolResources.MAP_NAMESPACE, ResourcePolicy.READ_ONLY,
                    ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.READ_ONLY,
                    ToolResources.UNIT_NAMESPACE, ResourcePolicy.READ_ONLY,
                    ToolResources.ACTOR_NAMESPACE, ResourcePolicy.READ_ONLY)));
    assertThatCode(() -> widened.resources().require(Operation.READ, actorOfHex))
        .as("★ 反事实：actor 取 READ_ONLY 时未表态调用者畅通 ⇒ 本用例红的唯一原因就是那一维被改宽")
        .doesNotThrowAnyException();

    // ★ 正向对照：显式表过态的调用者（GM 组的 unlimited）看得见 actor 面
    ToolContext gmFace =
        contextOf(
            callerWith(Map.of(ToolResources.ACTOR_NAMESPACE, ResourceScope.unlimited())),
            ToolResources.ALL_READ);
    assertThatCode(() -> gmFace.resources().require(Operation.READ, actorOfHex))
        .as("对照：显式表态 unlimited ⇒ 看得见（DENY 只挡「未表态」的人）")
        .doesNotThrowAnyException();
  }

  // ────────────────────────── ③ 通用写断言：逐条都判 ──────────────────────────

  /**
   * ★★ <b>判据 B：{@code requireAllWrite} 逐条断言三个命名空间，缺一即拒，且拒因点名缺的那一条</b>。
   *
   * <p>守的坏法：{@code requireAll} 是"逐条 require"的唯一入口——若它被改成"只要有一条通就算过"（或提前 return），
   * <b>受限调用者会拿到一半的写权限</b>：命令落盘、只有一半的资源被守，而返回值是成功。★ 两个负向分别打在<b>第一条</b>与
   * <b>第三条</b>资源上（证明不是"只看第一条"、也不是"只看最后一条"）。
   */
  @Test
  void theGenericWriteAssertionRequiresEveryNamespaceAndNamesTheMissingOne() {
    ToolContext unlimited =
        contextOf(
            callerWith(
                Map.of(
                    ToolResources.MAP_NAMESPACE, ResourceScope.unlimited(),
                    ToolResources.UNIT_NAMESPACE, ResourceScope.unlimited(),
                    ToolResources.SOCIAL_NAMESPACE, ResourceScope.unlimited())),
            ToolResources.ALL_WRITE);
    assertThatCode(() -> ToolResources.requireAllWrite(unlimited, "Map1"))
        .as("对照：三条都 unlimited ⇒ 通过")
        .doesNotThrowAnyException();

    // 负向①：第三条（unit）全拒，其余不限 ⇒ 仍然拒，且点名 unit:*
    ToolContext unitBlocked =
        contextOf(
            callerWith(
                Map.of(
                    ToolResources.MAP_NAMESPACE, ResourceScope.unlimited(),
                    ToolResources.UNIT_NAMESPACE, ResourceScope.none(),
                    ToolResources.SOCIAL_NAMESPACE, ResourceScope.unlimited())),
            ToolResources.ALL_WRITE);
    assertThatThrownBy(() -> ToolResources.requireAllWrite(unitBlocked, "Map1"))
        .as("★★ 判据 B：三条缺一即拒（不许「有一条通就算过」）")
        .isInstanceOf(ResourceDeniedException.class)
        .hasMessageContaining("unit:*");

    // 负向②：第一条（map:<mapId>）全拒，其余不限 ⇒ 拒，且点名 map:Map1（证明逐条都判，不是只看最后一条）
    ToolContext mapBlocked =
        contextOf(
            callerWith(
                Map.of(
                    ToolResources.MAP_NAMESPACE, ResourceScope.none(),
                    ToolResources.UNIT_NAMESPACE, ResourceScope.unlimited(),
                    ToolResources.SOCIAL_NAMESPACE, ResourceScope.unlimited())),
            ToolResources.ALL_WRITE);
    assertThatThrownBy(() -> ToolResources.requireAllWrite(mapBlocked, "Map1"))
        .as("★★ 判据 B：第一条就被判（不是只看最后一条）")
        .isInstanceOf(ResourceDeniedException.class)
        .hasMessageContaining("map:Map1");
  }

  /**
   * ★★ <b>判据 B：通用写断言走的是 {@code Operation.WRITE}，不是 {@code READ}</b>。
   *
   * <p>守的坏法：把操作维度写成 {@code READ} ⇒ 在 {@code READ_ONLY} 声明下"写"会被判成允许—— {@code ResourceAuthorizer}
   * 的判定表里读/写的唯一差别就在这里（{@code READ_ONLY + 未表态 + WRITE ⇒ 拒}）⇒ 一个字写错， <b>整条写路径的围栏消失而全绿</b>。
   */
  @Test
  void theGenericWriteAssertionAsksForWriteNotRead() {
    ResourceManifest readOnly =
        ResourceManifest.of(
            Map.of(
                ToolResources.MAP_NAMESPACE, ResourcePolicy.READ_ONLY,
                ToolResources.UNIT_NAMESPACE, ResourcePolicy.READ_ONLY,
                ToolResources.SOCIAL_NAMESPACE, ResourcePolicy.READ_ONLY));
    ToolContext unstated = contextOf(callerStatingNothing(), readOnly);

    assertThatCode(
            () ->
                unstated
                    .resources()
                    .require(Operation.READ, ResourceId.of(ToolResources.MAP_NAMESPACE, "Map1")))
        .as("前提：同一份声明下**读**是通的（否则下面那条「写被拒」什么都证明不了）")
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> ToolResources.requireAllWrite(unstated, "Map1"))
        .as("★★ 判据 B：read-only 声明下 WRITE 必须拒（若断言的是 READ，这里会静默通过）")
        .isInstanceOf(ResourceDeniedException.class);
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 组装一个真判定器的上下文（调用者权限集 × 工具声明）——与宿主在唯一入口里做的是同一件事。 */
  private static ToolContext contextOf(AgentPermissionSet permissions, ResourceManifest manifest) {
    return ToolContext.of(AccessToken.DEFAULT, permissions)
        .withResources(ResourceAuthorizer.of(permissions, manifest));
  }

  /** 一个在资源维度上**什么都没表态**的调用者（{@code resourceScopes} 空图 = 本层不表态）。 */
  private static AgentPermissionSet callerStatingNothing() {
    return AgentPermissionSet.unrestricted(AccessToken.DEFAULT);
  }

  /** 逐命名空间表态的调用者。 */
  private static AgentPermissionSet callerWith(Map<String, ResourceScope> scopes) {
    return callerStatingNothing().withResourceScopes(ResourceScopeMap.of(scopes));
  }

  /** 决策人基础可达面形制：{@code peer:<自己>} ∪ {@code peer:gm}。 */
  private static AgentPermissionSet callerDeclaringPeer(String... peerIds) {
    return callerWith(Map.of(ToolResources.PEER_NAMESPACE, ResourceScope.of(peerIds)));
  }
}
