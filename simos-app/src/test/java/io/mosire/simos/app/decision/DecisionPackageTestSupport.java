package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionPackageSnapshot;
import io.mosire.agentlib.decision.DecisionPackageTransitions;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.ProposeRequest;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.testing.GmActAsWorldFixture;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionCallMeta;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.spi.ApplyDecisionPackageHandler;
import io.mosire.simos.ga.tools.ToolBuckets;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** §10 决策包测试的共享小件：只造值/读 head，不复制生产状态机。 */
public final class DecisionPackageTestSupport {

  public static final BranchId MAIN = GmActAsWorldFixture.MAIN;
  public static final String INITIATOR = "test:decision-package";
  public static final String GM_INSTANCE = "gm-test";

  private DecisionPackageTestSupport() {}

  public static PackageItem item(int index, String toolName, Map<String, Object> args) {
    return new PackageItem(
        index,
        toolName,
        Map.copyOf(args),
        DecisionItemDigest.argsDigest(args),
        DecisionItemDigest.itemSummary(toolName, args));
  }

  public static DecisionCallMeta meta() {
    return new DecisionCallMeta(
        List.of(new CommandTarget("gov", "gov-central")),
        "{\"preview\":true}",
        List.of("scope-ok", "preview-ok"),
        Optional.empty(),
        Optional.empty());
  }

  public static DecisionCallMeta mergedMeta(String mergedPlanId) {
    return new DecisionCallMeta(
        List.of(), "{}", List.of("scope-ok"), Optional.of(mergedPlanId), Optional.empty());
  }

  public static DecisionPackageSnapshot proposed(
      String packetId, DecisionMakerId proposer, long now, List<PackageItem> items) {
    ProposeRequest request =
        new ProposeRequest(proposer.value(), AccessToken.DEFAULT, items, Long.MAX_VALUE);
    return DecisionPackageTransitions.propose(packetId, request, now);
  }

  public static DecisionRecord proposedRecord(
      String packetId,
      DecisionMakerId proposer,
      long tick,
      long createdAtRevision,
      List<PackageItem> items,
      String intent,
      Map<Integer, DecisionCallMeta> callMeta) {
    DecisionPackageSnapshot snapshot = proposed(packetId, proposer, tick, items);
    return new DecisionRecord(
        DecisionPacketId.parse(packetId),
        MAIN.value(),
        tick,
        proposer,
        intent,
        createdAtRevision,
        OptionalLong.empty(),
        Optional.empty(),
        OptionalLong.empty(),
        callMeta,
        snapshot);
  }

  public static DecisionRecord withSnapshot(
      DecisionRecord record, DecisionPackageSnapshot snapshot, OptionalLong decidedAtRevision) {
    return new DecisionRecord(
        record.id(),
        record.branch(),
        record.tick(),
        record.proposerId(),
        record.intent(),
        record.createdAtRevision(),
        record.confirmedAtRevision(),
        record.reasonInfoId(),
        decidedAtRevision,
        record.callMeta(),
        snapshot);
  }

  public static DecisionRecord record(CoreSimos core, String packetId) {
    return ga(core).decisionPackets().get(DecisionPacketId.parse(packetId));
  }

  public static GaState ga(CoreSimos core) {
    SimulationState state = core.replay(new StateRef(MAIN, head(core)));
    return ((GaSnapshot) state.module("ga").orElseThrow()).state();
  }

  public static SimulationState state(CoreSimos core) {
    return core.replay(new StateRef(MAIN, head(core)));
  }

  public static RevisionId head(CoreSimos core) {
    return core.head(MAIN).orElseThrow();
  }

  /** 提交一条 {@code ga.ApplyDecisionPackage}；返回原始结局，negative 用例要看具名码。 */
  public static BatchResult apply(
      CoreSimos core, long expectedContentVersion, DecisionRecord record) {
    RevisionId expected = head(core);
    String batchId = UUID.randomUUID().toString();
    CommandEnvelope envelope =
        new CommandEnvelope(
            UUID.randomUUID().toString(),
            batchId,
            INITIATOR,
            MAIN,
            expected,
            ApplyDecisionPackageHandler.TYPE,
            GaCodec.encodeApplyDecisionPackagePayload(expectedContentVersion, record));
    return core.submitBatch(List.of(envelope));
  }

  public static void applyCommitted(
      CoreSimos core, long expectedContentVersion, DecisionRecord record) {
    BatchResult result = apply(core, expectedContentVersion, record);
    assertThat(result)
        .as("测试夹具：ApplyDecisionPackage 必须提交成功；实得 %s", result)
        .isInstanceOf(BatchResult.Committed.class);
  }

  public static ToolRegistry gmRegistry(Shell shell) {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(shell.toolsFor(SimosToolSource.Role.GM));
    return registry;
  }

  public static AgentTool gmTool(Shell shell, String name) {
    return shell.toolsFor(SimosToolSource.Role.GM).stream()
        .filter(tool -> tool.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("GM 面缺工具: " + name));
  }

  /** 经真角色门执行 GM 工具（非 GM 用例可传自己的 permissions）。 */
  public static ToolResult executeGm(Shell shell, String name, Map<String, Object> args) {
    AgentTool tool = gmTool(shell, name);
    AgentPermissionSet permissions =
        AgentPermissionSet.unrestricted(AccessToken.DEFAULT).withRoles(RoleSet.of("GM"));
    ToolContext context =
        new ToolContext(
                AccessToken.DEFAULT,
                permissions,
                Map.of(),
                args,
                AgentIdentity.subagent(
                    GM_INSTANCE, io.mosire.agentlib.permission.CommandMode.FULL, "", 0))
            .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
    return new ToolCallAuthorizer(new ToolExecutionGuard())
        .execute(gmRegistry(shell), name, context);
  }

  public static ToolCallAuthorizer gmAuthorizer() {
    return new ToolCallAuthorizer(new ToolExecutionGuard());
  }

  public static ToolContext gmContext(AgentTool tool, Map<String, Object> args) {
    AgentPermissionSet permissions =
        AgentPermissionSet.unrestricted(AccessToken.DEFAULT).withRoles(RoleSet.of("GM"));
    return new ToolContext(
            AccessToken.DEFAULT,
            permissions,
            Map.of(),
            args,
            AgentIdentity.subagent(
                GM_INSTANCE, io.mosire.agentlib.permission.CommandMode.FULL, "", 0))
        .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
  }

  public static ToolContext nonGmContext(AgentTool tool, Map<String, Object> args, String role) {
    AgentPermissionSet permissions =
        AgentPermissionSet.unrestricted(AccessToken.DEFAULT).withRoles(RoleSet.of(role));
    return new ToolContext(
            AccessToken.DEFAULT,
            permissions,
            Map.of(),
            args,
            AgentIdentity.subagent("not-gm", io.mosire.agentlib.permission.CommandMode.FULL, "", 0))
        .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
  }

  public static ToolRegistry decisionRegistry(Shell shell) {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
    return registry;
  }

  public static DecisionCallerFactory decisionCaller(Shell shell) {
    return DecisionCallerFactory.forDecisionFace(
        shell.toolAuthorizer(),
        ToolBuckets.namesOf(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)));
  }

  public static ToolResult executeDm(
      Shell shell,
      ToolRegistry dmRegistry,
      DecisionCallerFactory factory,
      DecisionMaker dm,
      String tool,
      Map<String, Object> args) {
    return factory.execute(
        dmRegistry, tool, dm, state(shell.coreSimos()), shell.config().mapId(), args);
  }

  public static Map<Integer, DecisionCallMeta> metaFor(List<PackageItem> items) {
    Map<Integer, DecisionCallMeta> out = new LinkedHashMap<>();
    for (PackageItem item : items) {
      out.put(item.index(), meta());
    }
    return out;
  }

  public static final String SAMPLE_PACKET_ID = "pkt-dm-coverage-0";

  /** {@code ga.ApplyDecisionPackage} 的最小合法载荷（AdjudicateTick 目标声明覆盖用）。 */
  public static String sampleApplyPayload() {
    List<PackageItem> items = List.of(item(0, "simos.ga.request", Map.of("text", "coverage")));
    DecisionRecord record =
        proposedRecord(
            SAMPLE_PACKET_ID,
            new DecisionMakerId("dm-coverage"),
            0L,
            1L,
            items,
            "coverage",
            metaFor(items));
    return GaCodec.encodeApplyDecisionPackagePayload(0L, record);
  }

  public static List<Integer> indexes(List<PackageItem> items) {
    List<Integer> out = new ArrayList<>(items.size());
    for (PackageItem item : items) {
      out.add(item.index());
    }
    return out;
  }
}
