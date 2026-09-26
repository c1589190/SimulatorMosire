package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ **T10 的收尾缺口**（Task 9 报上来的）：「{@code actor:} 地址在 **MCP resolve 面**返回空候选」。
 *
 * <p>同一地址走 GUI {@code /api/resolve} ⇒ 200 + 1 个候选（Task 9 判活实测），而 MCP 的 {@code simos.state.resolve}
 * ⇒ {@code {"candidates":[]}}。根因在 {@link ToolSupport#subjectVisible} 的 switch： 它按主体的**命名空间**映射到资源，而
 * {@code actor} 一路落进 {@code default -> false}（fail-closed 的兜底）， 于是**判不了 ⇒
 * 不可见**——不是"装配坏了"，是**这条可见性表达式从来没写**。
 *
 * <p>★ **本类走真装配**（真 {@code Shell} + 真注册表 + 真解析器 + 真工具清单）：可见性缺口修的是"真工具的真清单"这件事， 夹具工具（自己声明一份
 * manifest）永远证不出它——那正是本仓记过的"变异体没打到被测那一层"。 故：
 *
 * <ul>
 *   <li>**正例用真 GM 面**（{@link Shell#gmCaller()}，来自 {@code Shell#gmPermissionSet}）：GM 的 actor 表态若被
 *       谁删掉，这里当场红；
 *   <li>**反例逐条换掉 actor 那一维**（{@code none()} / 不含该路径的前缀 / 压根不表态），其余分量逐字保留。
 * </ul>
 *
 * <p>★ **本类在 {@code io.mosire.simos.app} 而不是 {@code …app.tools}**：{@code Shell#gmCaller()}
 * 是**包内可见**的装配自检口径 （"不是对外 API"），照 {@code GmPermissionGroupTest} 的先例落在本包。
 */
class ActorResolveVisibilityTest {

  private static final String ACTOR_ROOT_ADDRESS = "actor:Map1";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;

  @BeforeEach
  void startShell() {
    // ★ 夹具走**生产的那条创世路径**（`ShellMain.seedGenesisIfEmpty` ⇒ `bootstrapGenesis(RichWorld…)`），
    //   与 Task 9 判活时那次"空库就地初始化"是同一条：actor 切片由 RichWorld 补给（未激活的空切片）。
    //   ⇒ 不自己搓夹具状态：解析器要的"六切片齐全"是装配事实，手搓的夹具证不出它。
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
    assertThat(ShellMain.seedGenesisIfEmpty(shell)).as("空库 ⇒ 就地初始化 v17levant 世界").isTrue();
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  /** ★ **正例**：MCP 面（GM 组）解析 {@code actor:Map1} ⇒ 拿到切片根主体那一个候选。 */
  @Test
  void theMcpFaceCallerResolvesTheActorSliceRoot() throws Exception {
    JsonNode candidates = candidates(resolveAs(Shell.gmCaller(), ACTOR_ROOT_ADDRESS));

    assertThat(candidateAddresses(candidates))
        .as("actor 切片根主体必须解析得到（Task 9 时这里是空的）")
        .containsExactly(ACTOR_ROOT_ADDRESS);
    assertThat(candidates.get(0).get("typeName").asText())
        .as("★ 判别力在 typeName 上：它只在 ActorResolver 命中真切片时才出现")
        .isEqualTo("ActorData");
    assertThat(candidates.get(0).get("id").get("namespace").asText()).isEqualTo("actor");
  }

  /** ★ **反例一**：actor 维显式 deny-all（{@code none()}）⇒ 同一条地址一个候选都不给。 */
  @Test
  void aCallerWhoExplicitlyDeniesActorSeesNothing() throws Exception {
    ToolContext denied = withActorScope(Shell.gmCaller(), ResourceScope.none());

    assertThat(candidateAddresses(candidates(resolveAs(denied, ACTOR_ROOT_ADDRESS))))
        .as("★ 显式 none() 是『哪里都不许』，与『未表态』是两种取值")
        .isEmpty();
  }

  /** ★ **反例二**：actor 维表态了、但前缀**不含**该路径（{@code Map2}）⇒ 拒——即"有 actor 面"≠"放行一切"。 */
  @Test
  void anActorScopeWithAnotherPathIsARealFence() throws Exception {
    ToolContext otherMap = withActorScope(Shell.gmCaller(), ResourceScope.of("Map2"));

    assertThat(candidateAddresses(candidates(resolveAs(otherMap, ACTOR_ROOT_ADDRESS))))
        .as("前缀 Map2 够不着资源 actor:Map1（段边界前缀匹配）")
        .isEmpty();
  }

  /**
   * ★★ **反例三：压根不表态 actor 的调用者**（{@link AgentPermissionSet#system()}：资源维空图）。
   *
   * <p>这条钉的是**失效方向**：actor 在工具清单里的缺省策略若是 {@code READ_ONLY}（照 map/social/unit 的形制顺手抄），
   * "不表态"就会**静默放行** actor 面（spec §5.2 第 3 条），且同时把用这份清单的九条读工具的**声明式前置闸**变成永远通过—— 故此处取 {@code
   * DENY}，本用例把那条方向钉死。
   */
  @Test
  void aCallerWhoNeverMentionedActorStaysLockedOut() throws Exception {
    ToolContext silent = withPermissions(Shell.gmCaller(), AgentPermissionSet.system());

    assertThat(candidateAddresses(candidates(resolveAs(silent, ACTOR_ROOT_ADDRESS))))
        .as("不表态 ⇒ 看不见（fail-closed）；顺手抄成 READ_ONLY 会让这里红")
        .isEmpty();
  }

  // ────────────────────────────── 装置 ──────────────────────────────

  /** 经**真工具的真清单**执行一次 {@code simos.state.resolve}（资源判定者用工具自己的 manifest）。 */
  private ToolResult resolveAs(ToolContext caller, String address) {
    AgentTool tool = shell.toolRegistry().find("simos.state.resolve").orElseThrow();
    return tool.execute(
        new ToolContext(
                caller.caller(),
                caller.permissions(),
                Map.of(),
                Map.of("address", address),
                caller.identity())
            .withResources(ResourceAuthorizer.of(caller.permissions(), tool.resources())));
  }

  /** 同一个调用者、只把 **actor 那一维**换成给定范围（其余分量逐字保留）。 */
  private static ToolContext withActorScope(ToolContext base, ResourceScope actorScope) {
    return withPermissions(
        base,
        base.permissions()
            .withResourceScopes(
                base.permissions()
                    .resourceScopes()
                    .withNamespace(ToolSupport.ACTOR_NAMESPACE, actorScope)));
  }

  private static ToolContext withPermissions(ToolContext base, AgentPermissionSet permissions) {
    return new ToolContext(base.caller(), permissions, base.config(), Map.of(), base.identity());
  }

  private static JsonNode candidates(ToolResult result) throws Exception {
    assertThat(result.success()).as("期望成功，实际：%s %s", result.code(), result.message()).isTrue();
    return JSON.readTree(result.message()).get("candidates");
  }

  private static List<String> candidateAddresses(JsonNode candidates) {
    return candidates.findValuesAsText("canonicalAddress");
  }
}
