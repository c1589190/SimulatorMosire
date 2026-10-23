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
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.ga.model.DecisionCallMeta;
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
 * 决策包条目的真预览与目标提取器（设计书 §7.3；原 {@code ProposalCatalog} 收窄）。
 *
 * <p>它只回答两个问题：这条工具调用在系统上下文里真预览是什么、从参数与预览里能提取哪些可寻址目标。 “能不能入包”不再由一张目录名单决定——{@code ToolGate.Ask}
 * 与权限链决定之后，本类只负责生成 {@link DecisionCallMeta} 所需的展示/执行元数据。合并计划仍需 fail-closed 地确认效果工具在可执行面内， 故保留
 * {@link #contains(String)} / {@link #requireTool(String)}。
 *
 * <p>目标提取键表与旧实现逐字一致；{@code newUnitId} 是“将要创建的新单位 id”，始终跳过并用于排除 同值单位目标（新单位尚不存在，不能按既有资源判越界）。
 */
public final class DecisionCallPreviewer {

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
          "targetHouseholdId",
          "treasuryHousehold");
  private static final Set<String> UNIT_KEYS =
      Set.of("unitId", "unit", "targetGovUnitId", "govUnitId");
  private static final Set<String> SOURCE_HOUSEHOLD_KEYS = Set.of("householdId", "household");
  private static final String NEW_UNIT_KEY = "newUnitId";
  private static final int MAX_DEPTH = 4;
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final List<String> DRAFT_CHECKS = List.of("scope-ok", "preview-ok");

  private final Map<String, AgentTool> byName;
  private final String mapId;

  /** 从工具面建索引；重名是装配故障，不静默取一条。 */
  public DecisionCallPreviewer(List<AgentTool> face, String mapId) {
    Objects.requireNonNull(face, "face");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    Map<String, AgentTool> indexed = new LinkedHashMap<>();
    for (AgentTool tool : face) {
      Objects.requireNonNull(tool, "face 含 null");
      if (indexed.putIfAbsent(tool.name(), tool) != null) {
        throw new IllegalStateException("决策调用预览面装配故障：" + tool.name() + " 在工具面上出现两次");
      }
    }
    this.byName = Collections.unmodifiableMap(indexed);
  }

  /** 工具是否在可预览/可执行面内（合并计划的 fail-closed 判据）。 */
  public boolean contains(String toolName) {
    return toolName != null && byName.containsKey(toolName);
  }

  /** 取真实工具实例（效果执行器用）。 */
  public AgentTool requireTool(String toolName) {
    AgentTool tool = toolName == null ? null : byName.get(toolName);
    if (tool == null) {
      throw new IllegalArgumentException("工具不在可执行决策面，拒绝执行: " + toolName);
    }
    return tool;
  }

  /** 诊断读口（保序、不可变）。 */
  public List<String> toolNames() {
    return List.copyOf(new ArrayList<>(byName.keySet()));
  }

  /**
   * 跑目标工具的**真预览**（{@code preview=true}），把成功结果解析成 Map。
   *
   * @throws IllegalArgumentException 工具不在面内 / 预览返回错误 / 预览不是 JSON 对象
   */
  public Map<String, Object> preview(
      String toolName, SimulationState state, Map<String, Object> args) {
    AgentTool tool = requireTool(toolName);
    Map<String, Object> previewArgs = new LinkedHashMap<>(args);
    previewArgs.put("preview", true);
    previewArgs.put("branch", state.meta().ref().branch().value());
    previewArgs.put("revision", state.meta().ref().revision().value());
    previewArgs.put("expectedRevision", state.meta().ref().revision().value());
    AgentPermissionSet permissions = AgentPermissionSet.unrestricted(AccessToken.SYSTEM);
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
    requireTool(toolName);
    SocialData social = ToolSupport.socialData(state);
    GameMap gameMap = ToolSupport.gameMap(state);
    LinkedHashSet<CommandTarget> out = new LinkedHashSet<>();
    Set<String> createdUnitIds = new LinkedHashSet<>();
    addCreatedUnitId(args, createdUnitIds);
    addCreatedUnitId(preview, createdUnitIds);
    collect(args, social, gameMap, out, createdUnitIds, 0);
    collect(preview, social, gameMap, out, createdUnitIds, 0);
    return List.copyOf(out);
  }

  /**
   * 生成一条包内条目的 Simos 领域元数据：真预览 + 非空目标提取 + 固定 draftChecks。
   *
   * <p>请求类条目（{@code simos.ga.request}）没有 target，由调用方直接构造空 meta，不进本方法。
   */
  public DecisionCallMeta callMeta(
      String toolName, SimulationState state, Map<String, Object> args) {
    Map<String, Object> preview = preview(toolName, state, args);
    List<CommandTarget> targets = targets(toolName, state, args, preview);
    if (targets.isEmpty()) {
      throw new IllegalArgumentException("未能从参数/预览提取到任何可寻址目标，拒绝入包: " + toolName);
    }
    return new DecisionCallMeta(
        targets, ToolSupport.json(preview), DRAFT_CHECKS, Optional.empty(), Optional.empty());
  }

  private static void addCreatedUnitId(Map<?, ?> node, Set<String> out) {
    Object raw = node.get(NEW_UNIT_KEY);
    if (raw instanceof String value && !value.isBlank()) {
      out.add(value);
    }
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

  /** 递归一趟：识别目标键、找 {@code sources[]}、下钻未知 Map（不下钻 List）。 */
  private void collect(
      Map<?, ?> node,
      SocialData social,
      GameMap gameMap,
      Set<CommandTarget> out,
      Set<String> createdUnitIds,
      int depth) {
    if (depth > MAX_DEPTH) {
      return;
    }
    for (Map.Entry<?, ?> raw : node.entrySet()) {
      if (!(raw.getKey() instanceof String key) || NEW_UNIT_KEY.equals(key)) {
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
            collectSource(source, social, out);
          }
        }
        continue;
      }
      if (value instanceof Map<?, ?> nested) {
        collect(nested, social, gameMap, out, createdUnitIds, depth + 1);
      }
    }
  }

  private void collectSource(Map<?, ?> source, SocialData social, Set<CommandTarget> out) {
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
    addHex(source, out);
  }

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
      // 不是 q_r 文本 ⇒ 不是目标。
    }
  }

  private static void addHousehold(String householdId, SocialData social, Set<CommandTarget> out) {
    Household household;
    try {
      household = social.households().get(HouseholdId.parse(householdId));
    } catch (IllegalArgumentException e) {
      return;
    }
    if (household == null) {
      return;
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
}
