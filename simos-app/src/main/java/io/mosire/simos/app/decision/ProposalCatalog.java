package io.mosire.simos.app.decision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.write.GovDispatchTeamTool;
import io.mosire.simos.app.tools.write.GovRecruitTool;
import io.mosire.simos.app.tools.write.GovRetireStaffTool;
import io.mosire.simos.app.tools.write.GovSelectExamineesTool;
import io.mosire.simos.app.tools.write.LevyRegionTool;
import io.mosire.simos.app.tools.write.RaiseUnitTool;
import io.mosire.simos.app.tools.write.SocialHouseholdMembersTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * **决策人可提议工具目录**（D2 决策包计划 §3.3）：初始清单的每条工具都现场构造一个真实例，用一个**系统预览上下文** 跑目标工具的真 {@code preview=true}
 * 路径（不落盘），并从 args + preview 递归提取目标资源。
 *
 * <p>★★ **为什么必须真调目标工具**：另写一套"预览推导"就是第二份业务真相（AGENTS 铁律 4 同族）——目标工具改口径时不会有人 记得同步它，且两边的漂移**不会报错**。本类的
 * preview 只做三件事：拼系统上下文、转发 args、把成功结果解析成 Map。
 *
 * <p>★★ **系统上下文**：{@link AccessToken#SYSTEM} + {@link AgentPermissionSet#unrestricted}，并**显式**
 * {@code withResources(ResourceAuthorizer.of(permissions, tool.resources()))}——不注入判定者时工具内部的 {@code
 * requireAll} 会撞上 fail-closed 的 {@code denying()} 缺省；只跑 {@code preview=true} 且调用方在 args 里塞 {@code
 * preview=false} 也会被本类覆盖成 true（绝不落盘）。
 *
 * <p>★★ **目标提取**（按契约 §3.3 的键表）：{@code regionId} → 区域；{@code at}/{@code hex}/{@code
 * treasuryLocation}（含 {@code q,r} 的对象）→ map hex + social hex；{@code householdId}/ {@code
 * governmentHouseholdId}/{@code manpowerTargetHousehold}/{@code from}/{@code to} → 查家户位置（hex ⇒
 * social、unit ⇒ unit；查不到 = 新家户 ⇒ 跳过）；{@code unitId}/{@code unit} → 单位；任意层级的 {@code sources[]}
 * 递归（source 元素里的 {@code householdId}/{@code household}/{@code hex}/{@code q,r}）。
 *
 * <p>★ **本类的两处具名扩展**（与契约键表并存的工具实参名差异，控制方复核）：退休工具的 {@code toHouseholdId} 与预览 {@code
 * targetHouseholdId} 同按家户目标提取；{@code targetHex} 同按 hex 目标提取——否则"回退到辖区外家户"会绕过拟稿 目标校验。创建型工具的新单位
 * id（{@code newUnitId}）跳过、由 {@code at}/{@code regionId} 兜住。
 */
public final class ProposalCatalog {

  private static final Set<String> REGION_KEYS = Set.of("regionId");
  private static final Set<String> HEX_KEYS = Set.of("at", "hex", "treasuryLocation", "targetHex");
  private static final Set<String> HOUSEHOLD_KEYS =
      Set.of(
          "householdId",
          "governmentHouseholdId",
          "manpowerTargetHousehold",
          "from",
          "to",
          "toHouseholdId",
          "targetHouseholdId");
  private static final Set<String> UNIT_KEYS =
      Set.of("unitId", "unit", "targetGovUnitId");

  /** source 元素里的家户键（{@code levyRegion} 的 manpower source 用短名 {@code household}）。 */
  private static final Set<String> SOURCE_HOUSEHOLD_KEYS = Set.of("householdId", "household");

  /**
   * 递归上限：够走到 {@code grain.sources[]}/{@code manpowerAllocation.sources[]}/{@code workOrder} 这几层。
   */
  private static final int MAX_DEPTH = 4;

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final Map<String, Entry> byName;
  private final String mapId;

  public ProposalCatalog(
      CoreSimos core,
      CalendarService calendarService,
      QueryService query,
      String initiator,
      String mapId) {
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(calendarService, "calendarService");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(initiator, "initiator");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    List<Entry> entries =
        List.of(
            entry(
                new RaiseUnitTool(core, query, initiator, mapId, calendarService),
                Set.of("newUnitId"),
                true),
            entry(
                new GovRecruitTool(core, query, initiator, mapId, calendarService),
                Set.of(),
                false),
            entry(new GovRetireStaffTool(core, query, initiator, mapId), Set.of(), false),
            entry(new SocialHouseholdMembersTool(core, query, initiator), Set.of(), false),
            entry(
                new LevyRegionTool(core, query, initiator, mapId, calendarService),
                Set.of(),
                false),
            entry(
                new GovSelectExamineesTool(core, query, initiator, mapId, calendarService),
                Set.of("newUnitId"),
                true),
            entry(
                new GovDispatchTeamTool(core, query, initiator, mapId, calendarService),
                Set.of("newUnitId"),
                true));
    Map<String, Entry> byName = new LinkedHashMap<>();
    for (Entry value : entries) {
      byName.put(value.toolName(), value);
    }
    this.byName = Collections.unmodifiableMap(byName);
  }

  /** 工具是否在本批初始清单里（propose 的第一道具名拒）。 */
  public boolean contains(String toolName) {
    return toolName != null && byName.containsKey(toolName);
  }

  /**
   * 跑目标工具的**真预览**（{@code preview=true}），把成功结果解析成 Map。
   *
   * @throws IllegalArgumentException 工具不在清单 / 预览返回错误（含 {@code BAD_REQUEST} 与资源拒因）/ 预览不是 JSON 对象
   */
  public Map<String, Object> preview(
      String toolName, SimulationState state, Map<String, Object> args) {
    Entry entry = require(toolName);
    AgentTool tool = entry.tool();
    Map<String, Object> previewArgs = new LinkedHashMap<>(args);
    // ★ 只允许 preview=true：模型塞进来的 false 一律覆盖掉（本路径绝不落盘）。
    previewArgs.put("preview", true);
    // ★ branch/revision 原样透传 base 状态：目标工具与拟稿读的是同一份世界。
    previewArgs.put("branch", state.meta().ref().branch().value());
    // ★ 目标工具可能用 ToolSupport.target(args) 读 revision（不是 expectedRevision）；两处都给成同一个 base，
    //   否则 branch/revision 输入下的预览会落到 head，与 scope 校验读的状态不是同一份。
    previewArgs.put("revision", state.meta().ref().revision().value());
    previewArgs.put("expectedRevision", state.meta().ref().revision().value());
    AgentPermissionSet permissions = AgentPermissionSet.unrestricted(AccessToken.SYSTEM);
    // ★ 显式注入判定者（不是工具自己发的许可）：ToolContext 的缺省是 denying()，直接用它会让每个 requireAll 判否；
    //   manifest 取目标工具自己的 resources()（只跑 preview=true，系统上下文不放宽任何领域语义）。
    ToolContext context =
        new ToolContext(
                AccessToken.SYSTEM, permissions, Map.of(), previewArgs, AgentIdentity.external())
            .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
    ToolResult result = tool.execute(context);
    if (!result.success()) {
      throw new IllegalArgumentException("目标工具预览失败(" + result.code() + "): " + result.message());
    }
    return parseObject(result.message(), toolName);
  }

  /** 从 args + preview 递归提取跨命名空间目标（保序去重）。 */
  public List<CommandTarget> targets(
      String toolName,
      SimulationState state,
      Map<String, Object> args,
      Map<String, Object> preview) {
    Entry entry = require(toolName);
    SocialData social = ToolSupport.socialData(state);
    GameMap gameMap = ToolSupport.gameMap(state);
    LinkedHashSet<CommandTarget> out = new LinkedHashSet<>();
    // ★ 创建型工具的新单位 id **跨 args + preview 汇总**：raiseUnit 的预览把新单位发在 {unitId}，而
    //   args 里的 {newUnitId} 才是它的名字——只在单张表里比对会把它误判成"既存单位越界"。
    Set<String> createdUnitIds = new LinkedHashSet<>();
    if (entry.createsUnit()) {
      addCreatedUnitId(args, createdUnitIds);
      addCreatedUnitId(preview, createdUnitIds);
    }
    collect(args, entry, social, gameMap, out, createdUnitIds, 0);
    collect(preview, entry, social, gameMap, out, createdUnitIds, 0);
    return List.copyOf(out);
  }

  private static void addCreatedUnitId(Map<?, ?> node, Set<String> out) {
    String newUnitId = stringValue(node.get("newUnitId"));
    if (newUnitId != null) {
      out.add(newUnitId);
    }
  }

  private Entry entry(AgentTool tool, Set<String> skipKeys, boolean createsUnit) {
    return new Entry(tool.name(), tool, skipKeys, createsUnit, this);
  }

  private Entry require(String toolName) {
    Entry entry = toolName == null ? null : byName.get(toolName);
    if (entry == null) {
      throw new IllegalArgumentException("工具不在 ProposalCatalog 初始清单，不可 propose: " + toolName);
    }
    return entry;
  }

  private static Map<String, Object> parseObject(String json, String toolName) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("目标工具 " + toolName + " 的预览结果为空");
    }
    try {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "目标工具 " + toolName + " 的预览结果不是 JSON 对象: " + e.getMessage(), e);
    }
  }

  /** 递归一趟：识别目标键、找 {@code sources[]}、下钻未知 Map（不下钻 List，避免把 route/commands 误当目标）。 */
  private void collect(
      Map<?, ?> node,
      Entry entry,
      SocialData social,
      GameMap gameMap,
      Set<CommandTarget> out,
      Set<String> createdUnitIds,
      int depth) {
    if (depth > MAX_DEPTH) {
      return;
    }
    for (Map.Entry<?, ?> raw : node.entrySet()) {
      if (!(raw.getKey() instanceof String key) || entry.skipKeys().contains(key)) {
        continue;
      }
      Object value = raw.getValue();
      if (REGION_KEYS.contains(key)) {
        String regionId = stringValue(value);
        if (regionId != null) {
          out.add(
              new CommandTarget(ToolSupport.MAP_NAMESPACE, ResourcePaths.region(mapId, regionId)));
        }
        continue;
      }
      if (HEX_KEYS.contains(key)) {
        if (value instanceof Map<?, ?> hex) {
          addHex(hex, out);
        } else if (value instanceof String text) {
          addHexText(text, out);
        }
        continue;
      }
      if (HOUSEHOLD_KEYS.contains(key)) {
        String householdId = stringValue(value);
        if (householdId != null) {
          addHousehold(householdId, social, out);
        }
        continue;
      }
      if (UNIT_KEYS.contains(key)) {
        String unitId = stringValue(value);
        if (unitId != null && !createdUnitIds.contains(unitId)) {
          out.add(new CommandTarget(ToolSupport.UNIT_NAMESPACE, ResourcePaths.unit(unitId)));
        }
        continue;
      }
      if ("sources".equals(key) && value instanceof List<?> sources) {
        for (Object element : sources) {
          if (element instanceof Map<?, ?> source) {
            collectSource(source, social, gameMap, out);
          }
        }
        continue;
      }
      if (value instanceof Map<?, ?> nested) {
        collect(nested, entry, social, gameMap, out, createdUnitIds, depth + 1);
      }
    }
  }

  /** 一个 source 元素：家户（{@code householdId}/{@code household}）+ 格（{@code hex} 对象或同级 {@code q,r}）。 */
  private void collectSource(
      Map<?, ?> source, SocialData social, GameMap gameMap, Set<CommandTarget> out) {
    for (Map.Entry<?, ?> raw : source.entrySet()) {
      if (!(raw.getKey() instanceof String key)) {
        continue;
      }
      if (SOURCE_HOUSEHOLD_KEYS.contains(key)) {
        String householdId = stringValue(raw.getValue());
        if (householdId != null) {
          addHousehold(householdId, social, out);
        }
      } else if ("hex".equals(key) && raw.getValue() instanceof Map<?, ?> hex) {
        addHex(hex, out);
      }
    }
    addHex(source, out); // 同级 q/r（RegionAllocations 的 AccountSource 视图）
  }

  /** {@code {q,r}} → map hex + social hex。 */
  private void addHex(Map<?, ?> node, Set<CommandTarget> out) {
    Optional<Integer> q = intValue(node.get("q"));
    Optional<Integer> r = intValue(node.get("r"));
    if (q.isEmpty() || r.isEmpty()) {
      return;
    }
    out.add(
        new CommandTarget(ToolSupport.MAP_NAMESPACE, ResourcePaths.hex(mapId, q.get(), r.get())));
    out.add(
        new CommandTarget(ToolSupport.SOCIAL_NAMESPACE, ResourcePaths.social(q.get(), r.get())));
  }

  /** {@code "q_r"} 文本形态的格目标（可选键的另一种线格式）；解析失败 ⇒ 跳过。 */
  private void addHexText(String text, Set<CommandTarget> out) {
    int underscore = text.lastIndexOf('_');
    if (underscore <= 0 || underscore == text.length() - 1) {
      return;
    }
    try {
      int q = Integer.parseInt(text.substring(0, underscore));
      int r = Integer.parseInt(text.substring(underscore + 1));
      out.add(new CommandTarget(ToolSupport.MAP_NAMESPACE, ResourcePaths.hex(mapId, q, r)));
      out.add(new CommandTarget(ToolSupport.SOCIAL_NAMESPACE, ResourcePaths.social(q, r)));
    } catch (NumberFormatException ignored) {
      // 不是 q_r 文本 ⇒ 不是目标；形状兜底，不伪造。
    }
  }

  /** 家户 id → 位置；查不到（新家户）或坏 id ⇒ 跳过（契约口径）。 */
  private static void addHousehold(String householdId, SocialData social, Set<CommandTarget> out) {
    Household household;
    try {
      household = social.households().get(HouseholdId.parse(householdId));
    } catch (IllegalArgumentException e) {
      return; // 坏 id：目标工具预览已过 ⇒ 这里是形状兜底，跳过而不是抛（不伪造目标）
    }
    if (household == null) {
      return; // 新家户尚未存在于 state ⇒ 不误判越界
    }
    if (household.location() instanceof HouseholdLocation.Hex hex) {
      out.add(
          new CommandTarget(
              ToolSupport.SOCIAL_NAMESPACE, ResourcePaths.social(hex.hex().q(), hex.hex().r())));
    } else if (household.location() instanceof HouseholdLocation.Unit unit) {
      out.add(new CommandTarget(ToolSupport.UNIT_NAMESPACE, ResourcePaths.unit(unit.unitId())));
    }
  }

  private static String stringValue(Object value) {
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  /** 只接受整型 Number（含无小数位的浮点表示），且落在 int 范围内。 */
  private static Optional<Integer> intValue(Object value) {
    if (!(value instanceof Number number)) {
      return Optional.empty();
    }
    if (number instanceof Double || number instanceof Float) {
      double asDouble = number.doubleValue();
      if (!Double.isFinite(asDouble)
          || BigDecimal.valueOf(asDouble).stripTrailingZeros().scale() > 0) {
        return Optional.empty();
      }
    }
    long asLong = number.longValue();
    if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
      return Optional.empty();
    }
    return Optional.of((int) asLong);
  }

  /** 目录条目（工具名 + 真实例 + 目标提取配置）；实现 {@link DecisionProposable} 的预览/目标入口。 */
  private record Entry(
      String toolName,
      AgentTool tool,
      Set<String> skipKeys,
      boolean createsUnit,
      ProposalCatalog owner)
      implements DecisionProposable {

    private Entry {
      Objects.requireNonNull(toolName, "toolName");
      Objects.requireNonNull(tool, "tool");
      Objects.requireNonNull(owner, "owner");
      skipKeys = Set.copyOf(skipKeys);
    }

    @Override
    public Map<String, Object> preview(SimulationState state, Map<String, Object> args) {
      return owner.preview(toolName, state, args);
    }

    @Override
    public List<CommandTarget> targets(
        SimulationState state, Map<String, Object> args, Map<String, Object> preview) {
      return owner.targets(toolName, state, args, preview);
    }
  }

  /** 目录里的工具名清单（诊断/测试读口）。 */
  public List<String> toolNames() {
    return List.copyOf(new ArrayList<>(byName.keySet()));
  }
}
