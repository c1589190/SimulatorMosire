package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.model.DiplomaticRelation;
import io.mosire.simos.sd.model.DiplomaticRelationKey;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.spi.RecordDiplomaticEventHandler;
import io.mosire.simos.sd.spi.SetDiplomaticRelationHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.MutationGuard;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D5（D-003/D-005/R6）决策人**外交写入**的署名校验判据：{@code sd.SetDiplomaticRelation} 的 {@code from} 必须等于调用者
 * Nation；{@code sd.RecordDiplomaticEvent} 的 {@code participants} 必须含调用者 Nation；归属不是 Nation ⇒ 具名拒；
 * 未知决策人 ⇒ 具名拒。负例**先于资源判定**（{@code AbstractNarrowWriteTool.execute} 的第一段），正例证明"拒是署名判的，
 * 不是工具整体不可用"——自己的 Nation 名义 + 自己的决策域资源 ⇒ 真落一条 revision。
 *
 * <p>★ 真命令总线（{@code sd.SetDiplomaticRelation} / {@code sd.RecordDiplomaticEvent} 的 handler 都是真件）；
 * 世界是手搭创世 checkpoint（map 空 + sd：两国 + 三类归属的决策人）。
 */
class DiplomaticWriteToolsTest {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final String FRA = "FRA";
  private static final String GER = "GER";

  @TempDir Path tempDir;

  // ── sd.SetDiplomaticRelation：from 署名 ─────────────────────────────────────────────

  @Test
  void relationFromAnotherNationIsRejectedByName() {
    try (Fixture fx = Fixture.open(tempDir.resolve("from-other"))) {
      ToolResult result =
          fx.callRelation("dm-fra", "{\"from\":\"GER\",\"to\":\"FRA\",\"text\":\"以别国名义立边\"}");

      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("REJECTED");
      assertThat(result.message())
          .contains("from 必须是调用者 Nation")
          .contains("from=GER")
          .contains("调用者=FRA")
          .contains("不许以别国名义写入");
      assertThat(fx.head()).as("署名拒 ⇒ 零 revision").isEqualTo(1L);
      assertThat(fx.handlerCalls).isEmpty();
    }
  }

  @Test
  void relationFromArmyAffiliatedDecisionMakerIsRejected() {
    try (Fixture fx = Fixture.open(tempDir.resolve("from-army"))) {
      ToolResult result =
          fx.callRelation("dm-army", "{\"from\":\"FRA\",\"to\":\"GER\",\"text\":\"军队决策人无外交署名权\"}");

      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("REJECTED");
      assertThat(result.message()).contains("只对 Nation 归属的决策人开放").contains("归属");
      assertThat(fx.head()).isEqualTo(1L);
    }
  }

  @Test
  void relationFromUnknownDecisionMakerIsRejected() {
    try (Fixture fx = Fixture.open(tempDir.resolve("from-ghost"))) {
      ToolResult result =
          fx.callRelation("dm-ghost", "{\"from\":\"FRA\",\"to\":\"GER\",\"text\":\"幽灵\"}");

      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("REJECTED");
      assertThat(result.message()).contains("不是本世界已知的决策人").contains("dm-ghost");
      assertThat(fx.head()).isEqualTo(1L);
    }
  }

  @Test
  void relationInTheCallersOwnNameIsCommitted() {
    try (Fixture fx = Fixture.open(tempDir.resolve("relation-ok"))) {
      ToolResult result =
          fx.callRelation(
              "dm-fra",
              "{\"from\":\"FRA\",\"to\":\"GER\",\"kind\":\"称臣纳贡\",\"text\":\"自然语言关系描述\"}");

      assertThat(result.success()).as(result.message()).isTrue();
      assertThat(fx.head()).as("一条命令 = 一条 revision").isEqualTo(2L);
      SdState sd = fx.sd(fx.stateAt(2L));
      DiplomaticRelation relation =
          sd.diplomaticRelations()
              .get(new DiplomaticRelationKey(new NationId(FRA), new NationId(GER)));
      assertThat(relation).isNotNull();
      assertThat(relation.kind()).contains("称臣纳贡");
      assertThat(relation.text()).isEqualTo("自然语言关系描述");
      assertThat(relation.updatedTick()).as("tick 缺省 = 世界当前 tick（7）").isEqualTo(7L);
      assertThat(sd.diplomaticRelations()).hasSize(1);
    }
  }

  // ── sd.RecordDiplomaticEvent：participants 署名 ─────────────────────────────────────

  @Test
  void eventParticipantsWithoutCallerNationIsRejectedByName() {
    try (Fixture fx = Fixture.open(tempDir.resolve("evt-missing"))) {
      ToolResult result =
          fx.callEvent("dm-fra", "{\"participants\":[\"GER\",\"AAA\"],\"text\":\"替别国记名\"}");

      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("REJECTED");
      assertThat(result.message())
          .contains("participants 必须包含调用者 Nation")
          .contains("调用者=FRA")
          .contains("不许替别国记名");
      assertThat(fx.head()).as("署名拒 ⇒ 零 revision").isEqualTo(1L);
    }
  }

  @Test
  void eventFromArmyAffiliatedDecisionMakerIsRejected() {
    try (Fixture fx = Fixture.open(tempDir.resolve("evt-army"))) {
      ToolResult result =
          fx.callEvent("dm-army", "{\"participants\":[\"FRA\",\"GER\"],\"text\":\"军队决策人无外交署名权\"}");
      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("REJECTED");
      assertThat(result.message()).contains("只对 Nation 归属的决策人开放");
      assertThat(fx.head()).isEqualTo(1L);
    }
  }

  @Test
  void eventWithCallerNationIsCommittedAndKeepsParticipantOrder() {
    try (Fixture fx = Fixture.open(tempDir.resolve("evt-ok"))) {
      ToolResult result =
          fx.callEvent(
              "dm-fra",
              "{\"eventId\":\"e-1\",\"participants\":[\"GER\",\"FRA\"],\"text\":\"多国谈判内容\"}");

      assertThat(result.success()).as(result.message()).isTrue();
      assertThat(fx.head()).isEqualTo(2L);
      SdState sd = fx.sd(fx.stateAt(2L));
      assertThat(sd.diplomaticEvents()).hasSize(1);
      DiplomaticEvent event = sd.diplomaticEvents().values().iterator().next();
      assertThat(event.id().value()).isEqualTo("e-1");
      assertThat(event.tick()).isEqualTo(7L);
      assertThat(event.participants())
          .as("participants 保写序（不是排序后）")
          .containsExactly(new NationId(GER), new NationId(FRA));
      assertThat(event.text()).isEqualTo("多国谈判内容");
    }
  }

  // ── 装置 ────────────────────────────────────────────────────────────────────────────

  private static final class Fixture implements AutoCloseable {

    final CoreSimos core;
    final QueryService query;
    final List<String> handlerCalls = new ArrayList<>();
    final Set<String> rejectTypes = new java.util.LinkedHashSet<>();
    private final SetDiplomaticRelationTool relationTool;
    private final RecordDiplomaticEventTool eventTool;

    private Fixture(CoreSimos core) {
      this.core = core;
      this.query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
      this.relationTool = new SetDiplomaticRelationTool(core, query, "agent:t2b-diplo", "Map1");
      this.eventTool = new RecordDiplomaticEventTool(core, query, "agent:t2b-diplo", "Map1");
      core.register(recording(new SetDiplomaticRelationHandler()));
      core.register(recording(new RecordDiplomaticEventHandler()));
      core.register(
          new MutationGuard() {
            @Override
            public String name() {
              return "t2b-reject-by-type";
            }

            @Override
            public Optional<String> rejection(
                SimulationState state, String commandType, String payloadJson) {
              return rejectTypes.contains(commandType)
                  ? Optional.of("T2b 测试守卫：故意拒 " + commandType)
                  : Optional.empty();
            }
          });
    }

    static Fixture open(Path storeDir) {
      CoreSimos core =
          new CoreSimos(
              new CoreConfig(storeDir, 100, new com.fasterxml.jackson.databind.ObjectMapper()));
      core.register(new MapCodec()).register(new SdCodec());
      Fixture fixture = new Fixture(core);
      core.bootstrapGenesis(genesis());
      return fixture;
    }

    ToolResult callRelation(String decisionMakerId, String payloadJson) {
      return relationTool.execute(context(relationTool, decisionMakerId, payloadJson));
    }

    ToolResult callEvent(String decisionMakerId, String payloadJson) {
      return eventTool.execute(context(eventTool, decisionMakerId, payloadJson));
    }

    long head() {
      return core.head(main()).orElseThrow().value();
    }

    SimulationState stateAt(long revision) {
      return core.replay(new StateRef(main(), new RevisionId(revision)));
    }

    SdState sd(SimulationState state) {
      return ((SdSnapshot) state.module("sd").orElseThrow()).state();
    }

    private CommandHandler recording(CommandHandler delegate) {
      return new CommandHandler() {
        @Override
        public String type() {
          return delegate.type();
        }

        @Override
        public HandlerOutcome handle(SimulationState state, String payloadJson) {
          handlerCalls.add(delegate.type());
          return delegate.handle(state, payloadJson);
        }
      };
    }

    @Override
    public void close() {
      core.close();
    }
  }

  private static ToolContext context(AgentTool tool, String decisionMakerId, String payloadJson) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", payloadJson);
    args.put("branch", "main");
    args.put("expectedRevision", 1L);
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow(tool.name())
            .sensitiveAllowed(true)
            // 决策人自己的 sd 决策域（与 AbstractNarrowWriteTool.decisionWriteResources 同源）。
            .resourceScopes(
                ResourceScopeMap.of(
                    ToolSupport.SD_NAMESPACE,
                    ResourceScope.of("decision-maker/" + decisionMakerId)))
            .build();
    AgentIdentity identity =
        AgentIdentity.subagent(
            DecisionCallerFactory.INSTANCE_ID_PREFIX + decisionMakerId,
            CommandMode.LIMITED,
            DecisionCallerFactory.GOAL,
            1);
    return new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), args, identity)
        .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(main(), new RevisionId(1));
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    for (String nation : List.of(FRA, GER, "AAA")) {
      NationId id = new NationId(nation);
      nations.put(id, new Nation(id, "国家 " + nation, new RegionId("r-" + nation), 0));
    }
    Map<io.mosire.simos.sd.id.DecisionMakerId, DecisionMaker> makers = new LinkedHashMap<>();
    makers.put(
        new DecisionMakerId("dm-fra"), maker("dm-fra", new Affiliation.Nation(new NationId(FRA))));
    makers.put(
        new DecisionMakerId("dm-ger"), maker("dm-ger", new Affiliation.Nation(new NationId(GER))));
    makers.put(
        new DecisionMakerId("dm-army"),
        maker("dm-army", new Affiliation.Army(new ArmyId("army-1"))));
    Map<ArmyId, Army> armies = new LinkedHashMap<>();
    armies.put(
        new ArmyId("army-1"),
        new Army(
            new ArmyId("army-1"), Optional.empty(), new io.mosire.simos.unit.UnitId("u-1"), "军队一"));
    SdState sd = SdState.empty().withNations(nations).withArmies(armies).withDecisionMakers(makers);
    return new SimulationState(
        new StateMeta(ref, T7),
        Map.of(
            "map", new MapSnapshot(ref, T7, GameMap.empty()),
            "sd", new SdSnapshot(ref, T7, sd)),
        InMemoryInfoSystem.empty());
  }

  private static DecisionMaker maker(String id, Affiliation affiliation) {
    return new DecisionMaker(
        new DecisionMakerId(id), affiliation, Set.of(), AccessLimit.empty(), 1);
  }

  private static BranchId main() {
    return new BranchId("main");
  }
}
