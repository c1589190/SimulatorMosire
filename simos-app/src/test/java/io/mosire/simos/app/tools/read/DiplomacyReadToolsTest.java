package io.mosire.simos.app.tools.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.model.DiplomaticRelation;
import io.mosire.simos.sd.model.DiplomaticRelationKey;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolverRegistry;
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
 * {@code simos.sd.diplomacy} / {@code simos.sd.diplomatic-events}（D5 / D-003 / D-005 / R6）读口判据：
 *
 * <ul>
 *   <li>关系边：有向边清单按 from→to 字典序、{@code kind} 可空原样、{@code nation} 过滤取 from∪to、未知 Nation ⇒ 空；
 *   <li>事件：按 id 字典序、{@code participants} 保写序、{@code tick} / {@code participant} 过滤及交集、未知 ⇒ 空；
 *   <li>两条都**不做逐格视野过滤**（世界级自然语言，R6 默认四桶共享读）——本用例不注资源范围也能读全。
 * </ul>
 */
class DiplomacyReadToolsTest {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  // ── sd.diplomacy ────────────────────────────────────────────────────────────────────

  @Test
  void relationsAreSortedByDirectionAndCarryNaturalLanguage() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("relations-all"))) {
      ToolResult result = fx.diplomacy(Map.of());
      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode rows = JSON.readTree(result.message()).get("relations");
      assertThat(pairs(rows))
          .as("有向边按 from、再按 to 字典序")
          .containsExactly("AAA→FRA", "FRA→GER", "GER→AAA", "GER→FRA");
      JsonNode tribute = rows.get(1);
      assertThat(tribute.get("kind").asText()).isEqualTo("称臣纳贡");
      assertThat(tribute.get("text").asText()).isEqualTo("r1 自然语言");
      assertThat(tribute.get("updatedTick").asLong()).isEqualTo(7L);
      assertThat(rows.get(0).get("kind").isNull()).as("kind 可空原样（不编造字符串）").isTrue();
    }
  }

  @Test
  void relationsFilterMatchesEitherEndpointAndUnknownNationIsEmpty() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("relations-filter"))) {
      JsonNode byFra =
          JSON.readTree(fx.diplomacy(Map.of("nation", "FRA")).message()).get("relations");
      assertThat(pairs(byFra)).containsExactly("AAA→FRA", "FRA→GER", "GER→FRA");

      JsonNode byAaa =
          JSON.readTree(fx.diplomacy(Map.of("nation", "AAA")).message()).get("relations");
      assertThat(pairs(byAaa)).containsExactly("AAA→FRA", "GER→AAA");

      JsonNode unknown =
          JSON.readTree(fx.diplomacy(Map.of("nation", "ZZZ")).message()).get("relations");
      assertThat(unknown).as("未知 Nation 是空结果（存在性由写命令管）").isEmpty();
    }
  }

  @Test
  void relationsRejectMalformedNationShape() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("relations-bad"))) {
      ToolResult result = fx.diplomacy(Map.of("nation", "   "));
      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("BAD_REQUEST");
    }
  }

  // ── sd.diplomatic-events ────────────────────────────────────────────────────────────

  @Test
  void eventsAreSortedByIdAndKeepParticipantOrder() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("events-all"))) {
      ToolResult result = fx.events(Map.of());
      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode rows = JSON.readTree(result.message()).get("events");
      assertThat(ids(rows)).containsExactly("e-1", "e-2", "e-3");
      assertThat(values(rows.get(0).get("participants")))
          .as("participants 保记录顺序（不是排序后）")
          .containsExactly("FRA", "GER");
      assertThat(values(rows.get(1).get("participants"))).containsExactly("GER", "AAA");
      assertThat(rows.get(0).get("tick").asLong()).isEqualTo(7L);
      assertThat(rows.get(0).get("text").asText()).isEqualTo("ev1 内容");
    }
  }

  @Test
  void eventsFilterByTickAndParticipantAndIntersection() throws Exception {
    try (Fixture fx = Fixture.open(tempDir.resolve("events-filter"))) {
      assertThat(ids(JSON.readTree(fx.events(Map.of("tick", 7L)).message()).get("events")))
          .containsExactly("e-1", "e-3");
      assertThat(
              ids(JSON.readTree(fx.events(Map.of("participant", "FRA")).message()).get("events")))
          .containsExactly("e-1", "e-3");
      assertThat(
              ids(
                  JSON.readTree(fx.events(Map.of("tick", 7L, "participant", "FRA")).message())
                      .get("events")))
          .as("tick 与 participant 过滤是交集")
          .containsExactly("e-1", "e-3");
      assertThat(
              ids(
                  JSON.readTree(fx.events(Map.of("tick", 8L, "participant", "FRA")).message())
                      .get("events")))
          .as("交集为空（不是取并集）")
          .isEmpty();
      assertThat(ids(JSON.readTree(fx.events(Map.of("tick", 999L)).message()).get("events")))
          .isEmpty();
      assertThat(
              ids(JSON.readTree(fx.events(Map.of("participant", "ZZZ")).message()).get("events")))
          .isEmpty();
    }
  }

  // ── 装置 ────────────────────────────────────────────────────────────────────────────

  private static final class Fixture implements AutoCloseable {

    final CoreSimos core;
    final QueryService query;
    final SdDiplomacyTool diplomacyTool;
    final SdDiplomaticEventsTool eventsTool;

    private Fixture(CoreSimos core) {
      this.core = core;
      this.query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
      this.diplomacyTool = new SdDiplomacyTool(query);
      this.eventsTool = new SdDiplomaticEventsTool(query);
    }

    static Fixture open(Path storeDir) {
      CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, new ObjectMapper()));
      core.register(new SdCodec());
      Fixture fixture = new Fixture(core);
      StateRef ref = new StateRef(main(), new RevisionId(1));
      SimulationState genesis =
          new SimulationState(
              new StateMeta(ref, T7),
              Map.of("sd", new SdSnapshot(ref, T7, sdState())),
              InMemoryInfoSystem.empty());
      core.bootstrapGenesis(genesis);
      return fixture;
    }

    ToolResult diplomacy(Map<String, Object> args) {
      return diplomacyTool.execute(context(args));
    }

    ToolResult events(Map<String, Object> args) {
      return eventsTool.execute(context(args));
    }

    @Override
    public void close() {
      core.close();
    }
  }

  private static SdState sdState() {
    Map<DiplomaticRelationKey, DiplomaticRelation> relations = new LinkedHashMap<>();
    relations.put(key("FRA", "GER"), new DiplomaticRelation(Optional.of("称臣纳贡"), "r1 自然语言", 7L));
    relations.put(key("GER", "FRA"), new DiplomaticRelation(Optional.empty(), "r2 自然语言", 8L));
    relations.put(key("AAA", "FRA"), new DiplomaticRelation(Optional.empty(), "r3 自然语言", 7L));
    relations.put(key("GER", "AAA"), new DiplomaticRelation(Optional.empty(), "r4 自然语言", 8L));

    Map<DiplomaticEventId, DiplomaticEvent> events = new LinkedHashMap<>();
    events.put(
        new DiplomaticEventId("e-1"),
        new DiplomaticEvent(
            new DiplomaticEventId("e-1"),
            7L,
            List.of(new NationId("FRA"), new NationId("GER")),
            "ev1 内容"));
    events.put(
        new DiplomaticEventId("e-2"),
        new DiplomaticEvent(
            new DiplomaticEventId("e-2"),
            8L,
            List.of(new NationId("GER"), new NationId("AAA")),
            "ev2 内容"));
    events.put(
        new DiplomaticEventId("e-3"),
        new DiplomaticEvent(
            new DiplomaticEventId("e-3"),
            7L,
            List.of(new NationId("AAA"), new NationId("FRA")),
            "ev3 内容"));
    return SdState.empty().withDiplomaticRelations(relations).withDiplomaticEvents(events);
  }

  private static DiplomaticRelationKey key(String from, String to) {
    return new DiplomaticRelationKey(new NationId(from), new NationId(to));
  }

  private static ToolContext context(Map<String, Object> args) {
    AgentPermissionSet permissions =
        new AgentPermissionSet(
            AccessToken.DEFAULT,
            Set.of(AgentPermissionSet.ALL_TOOLS),
            Set.of(),
            false,
            false,
            false);
    return new ToolContext(
        AccessToken.DEFAULT, permissions, Map.of(), args, AgentIdentity.external());
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static List<String> pairs(JsonNode rows) {
    List<String> out = new ArrayList<>();
    rows.forEach(row -> out.add(row.get("from").asText() + "→" + row.get("to").asText()));
    return out;
  }

  private static List<String> ids(JsonNode rows) {
    List<String> out = new ArrayList<>();
    rows.forEach(row -> out.add(row.get("eventId").asText()));
    return out;
  }

  private static List<String> values(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(node -> out.add(node.asText()));
    return out;
  }
}
