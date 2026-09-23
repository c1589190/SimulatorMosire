package io.mosire.simos.app.tools.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.model.AdjudicationStatus;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code sd.VoidAdjudication}（**只在 GM 桶**，2026-09-23）：把某一 tick 的裁决**作废**——世界回滚、令退回待裁决、
 * 记录标作废。
 *
 * <p>★★ **用户的模型**：「只有生效裁决和作废裁决」。一个 tick 可以留多条裁决记录，**至多一条生效**；作废是那"另一档"。
 *
 * <p>★★ **一次作废 = 一条 revision，原子**（这是本类的全部要害）：
 *
 * <ol>
 *   <li>**map / unit / social 走逆变更**：{@code XChangeSet.between(当前, 基态)} —— 就是"把世界改回裁决之前"。
 *       ★ 逆变更集是**白拿**的（同一个 {@code between} 换个参数序）；不需要命令明文（那东西根本没留痕，
 *       见 {@code CommandBus#RESTORE_COMMAND_TYPE} 的类注）；
 *   <li>**sd 不做逆变更、只做显式改写**：那条记录翻成 {@link AdjudicationStatus#VOIDED}（**不删**——留痕：
 *       "第 1 版被作废"这件事本身要看得到），把它翻过的令**退回 {@code ISSUED}**（从基态取回它们当时的记录，
 *       比"构造一个 ISSUED"更忠实：基态里就是它们原本的样子）；
 *   <li>把两半合成**一个** {@link WorldChangeSet}，交给 {@code core.submitRestore} —— 一条 revision 落地。
 * </ol>
 *
 * <p>★★ **为什么"令退回 ISSUED"不走 {@code sd.SetDirectiveStatus}**：那条命令的守卫只放 {@code ISSUED → EXECUTED/CANCELLED}
 * （终态不得翻回），**它是对的、不许放开**——放开之后任何路径都能把已执行的令改回去。作废是在 restore 的**变更集**里直接改写
 * sd 状态，而 {@code submitRestore} 只有 Core 的公开 API 一条入口（本工具是唯一调用方）。
 *
 * <p>★ **只允许作废"仍是最新一条 revision"的裁决**（tip-only）：逆变更是"当前 → 基态"，中间若还夹着别的 revision，
 * 一次撤销会把它们**一起**抹掉。非最新 ⇒ **响亮拒绝**并提示先 {@code simos.fork}。
 *
 * <p>★ **它不是一个命令类型**（与 {@code sd.AdjudicateTick} 同族）：固定的是"作废某一 tick"这一件事，载荷只有坐标 ⇒
 * 不进 catalog、不进令白名单，决策人够不着。
 */
public final class VoidAdjudicationTool implements AgentTool {

  /** 工具名（全局唯一）。★ 不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "sd.VoidAdjudication";

  /** 结果条目 value 里记的"这次裁决落盘得到的 revision"（写路径见 {@code AdjudicateTickTool}）。 */
  private static final String RESULT_REVISION_FIELD = "resultRevision";

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final CoreSimos core;
  private final String initiator;

  public VoidAdjudicationTool(CoreSimos core, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "作废某一 tick 的裁决（GM 专用）：世界**回滚**到那次裁决之前、被它翻过的令退回待裁决、"
        + "那条裁决记录标 VOIDED（不删，留痕）。载荷 {branch, expectedRevision, tick}。"
        + "★ 只能作废**最新一条 revision** 上的裁决（撤销不能跨过别的 revision）——不是最新会明确拒绝并提示先 simos_fork。"
        + "★ 作废之后可以重新裁决该 tick（新条落 #1 并生效）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("tick", ToolSupport.prop("integer", "要作废哪一 tick 的裁决"));
    return ToolSupport.schema(props, List.of("tick"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写（走审批门链）、可外发（noExport=false）——与 sd.AdjudicateTick / sd.RejectDirective 同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    // 只声明 sd 只读策略面（fail-closed：未表态 sd 的调用者在**写**上被拒）——撤销本身跨命名空间，
    // 由 core.submitRestore 落盘，不按资源谓词逐条判。
    return ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "作废裁决 tick="
            + args.get("tick")
            + " branch="
            + args.get("branch")
            + " expected="
            + args.get("expectedRevision"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      // ★ 与 sd.AdjudicateTick 同款：expectedRevision 是**独立载荷**（不是 ToolSupport.target 的 revision）
      //   ——作废必须对着调用方以为的那个 head 来，缺了它就没有"坐标没变"这一层保护。
      BranchId branch = new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      StateRef target = new StateRef(branch, new RevisionId(ToolSupport.requiredLong(args, "expectedRevision")));
      Long tickArg = ToolSupport.optionalLong(args, "tick");
      if (tickArg == null || tickArg < 0) {
        return ToolResult.error("BAD_REQUEST", "tick 必填且 ≥ 0");
      }
      long tick = tickArg;
      // ★ 锁内不变式由 submitRestore 自己把（它复核 head）；这里先把要作废的东西找出来。
      StateRef head = currentHead(target);
      if (!head.revision().equals(target.revision())) {
        return ToolResult.error(
            "CONFLICT", "head 已变：你给的是 " + target.revision().value() + "，现在是 " + head.revision().value());
      }
      SimulationState current = core.replay(head);
      SdState currentSd = sdOf(current);
      String address = Address.parse(AdjudicateTickTool.RESULT_ADDRESS_PREFIX + tick).canonical();
      Optional<SdInfoEntry> effective = effectiveEntry(currentSd, address, tick);
      if (effective.isEmpty()) {
        return ToolResult.error(
            "TOOL_ERROR", "该 tick 没有生效裁决可作废: " + address + "（用 sd.DecisionResults 看有哪些）");
      }
      SdInfoEntry entry = effective.get();
      Optional<Long> resultRevision = resultRevisionOf(entry);
      if (resultRevision.isEmpty()) {
        return ToolResult.error(
            "TOOL_ERROR", "该裁决记录里读不到 resultRevision，无法定位要回滚的那条 revision: " + entry.id().value());
      }
      if (resultRevision.get() != head.revision().value()) {
        return ToolResult.error(
            "TOOL_ERROR",
            "只能作废**最新一条 revision** 上的裁决：该裁决落在 revision "
                + resultRevision.get()
                + "，而当前 head 是 "
                + head.revision().value()
                + "——撤销会把中间那几条一起抹掉。要回退到更早的状态请先 simos_fork。");
      }

      StateRef source = new StateRef(head.branch(), new RevisionId(entry.at().value()));
      SimulationState base = core.replay(source);
      WorldChangeSet changeSet = changeSet(current, currentSd, base, currentSd, entry, address);

      var outcome =
          core.submitRestore(head.branch(), head.revision(), initiator, changeSet);
      if (outcome instanceof io.mosire.simos.core.command.CommandResult.Committed committed) {
        Map<String, Object> view =
            new LinkedHashMap<>(ToolSupport.committedView(committed.ref(), NAME, NAME));
        view.put("tick", tick);
        view.put("voidedEntryId", entry.id().value());
        view.put("rolledBackTo", source.revision().value());
        view.put("restoredDirectives", flippedIds(entry));
        return ToolSupport.ok(view);
      }
      if (outcome instanceof io.mosire.simos.core.command.CommandResult.Conflict conflict) {
        return ToolResult.error(
            "CONFLICT", "坐标已变，作废未落：真实 head = " + conflict.current().revision().value());
      }
      return ToolResult.error(
          "TOOL_ERROR", "作废被拒：" + ((io.mosire.simos.core.command.CommandResult.Rejected) outcome).reason());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /**
   * 合成那**一个**变更集：map/unit/social 逆变更 + sd 显式改写（详见类注）。
   *
   * <p>★ 四个命名空间**恒在**（本世界四个模块都在）——不做"只放变了的"，因为零变更的 delta（{@code Unchanged}）
   * 施加下去是恒等，省它只会多一处分支。
   */
  private static WorldChangeSet changeSet(
      SimulationState current,
      SdState currentSd,
      SimulationState base,
      SdState sdUnused,
      SdInfoEntry entry,
      String address) {
    Map<String, ChangeSet> modules = new LinkedHashMap<>();
    modules.put(
        "map",
        MapChangeSet.between(
            ((MapSnapshot) current.module("map").orElseThrow()).map(),
            ((MapSnapshot) base.module("map").orElseThrow()).map()));
    modules.put(
        "unit",
        UnitChangeSet.between(
            ((UnitSnapshot) current.module("unit").orElseThrow()).state(),
            ((UnitSnapshot) base.module("unit").orElseThrow()).state()));
    modules.put(
        "social",
        SocialChangeSet.between(
            ((SocialSnapshot) current.module("social").orElseThrow()).data(),
            ((SocialSnapshot) base.module("social").orElseThrow()).data()));

    SdState baseSd = sdOf(base);
    // sd 侧：基态 + 那条记录（翻成 VOIDED）。★ 基态的 directives 就是"令退回 ISSUED"的**忠实来源**
    // （它们当时本就是 ISSUED），也把该 tick 的 intentInfo 一并恢复。
    Map<String, List<SdInfoEntry>> info = new LinkedHashMap<>(baseSd.info());
    List<SdInfoEntry> atAddress = new ArrayList<>(info.getOrDefault(address, List.of()));
    atAddress.add(voided(entry));
    info.put(address, List.copyOf(atAddress));
    modules.put("sd", SdChangeSet.between(currentSd, baseSd.withInfo(info)));
    return new WorldChangeSet(modules);
  }

  /** 同一条记录、只换状态（**不删**——留痕）。 */
  private static SdInfoEntry voided(SdInfoEntry entry) {
    return new SdInfoEntry(
        entry.id(),
        entry.tick(),
        entry.tags(),
        entry.affiliations(),
        entry.key(),
        entry.value(),
        entry.note(),
        entry.at(),
        entry.sourceDirective(),
        Optional.of(AdjudicationStatus.VOIDED));
  }

  /** 该地址下**生效**的那一条（至多一条；多条时取最后一条并把异常留给不变量去暴露）。 */
  private static Optional<SdInfoEntry> effectiveEntry(SdState sd, String address, long tick) {
    List<SdInfoEntry> entries = sd.info().getOrDefault(address, List.of());
    SdInfoEntry found = null;
    for (SdInfoEntry entry : entries) {
      if (entry.tick() == tick && SdInfoEntry.isEffective(entry)) {
        found = entry;
      }
    }
    return Optional.ofNullable(found);
  }

  /** 记录里记的"这次裁决落盘得到的 revision"（value 是 JSON 字符串，见写路径）。 */
  private static Optional<Long> resultRevisionOf(SdInfoEntry entry) {
    Object raw = entry.value();
    if (!(raw instanceof String text) || text.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode node = MAPPER.readTree(text);
      JsonNode field = node.get(RESULT_REVISION_FIELD);
      return field != null && field.isNumber() ? Optional.of(field.asLong()) : Optional.empty();
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /** 被这次裁决翻过的令 id（只用于回执，让人看得见"哪几条被打回去了"）。 */
  private static List<String> flippedIds(SdInfoEntry entry) {
    Object raw = entry.value();
    if (!(raw instanceof String text) || text.isBlank()) {
      return List.of();
    }
    try {
      JsonNode flips = MAPPER.readTree(text).get("flips");
      if (flips == null || !flips.isArray()) {
        return List.of();
      }
      List<String> out = new ArrayList<>();
      for (JsonNode flip : flips) {
        JsonNode id = flip.get("directiveId");
        if (id != null && id.isTextual()) {
          out.add(id.asText());
        }
      }
      return List.copyOf(out);
    } catch (Exception e) {
      return List.of();
    }
  }

  private StateRef currentHead(StateRef target) {
    RevisionId head =
        core.head(target.branch())
            .orElseThrow(() -> new IllegalArgumentException("分支不存在: " + target.branch().value()));
    return new StateRef(target.branch(), head);
  }

  private static SdState sdOf(SimulationState state) {
    return ((SdSnapshot) state.module("sd").orElseThrow()).state();
  }
}
