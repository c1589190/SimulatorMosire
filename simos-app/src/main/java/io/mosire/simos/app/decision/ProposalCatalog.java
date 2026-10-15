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
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.ga.spi.DecisionProposable;
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
 * **决策人可提议工具目录**（D2 决策包计划 §3.3）：收集工具面上**自己声明了可被提议**的工具实例（{@link DecisionProposable}），用一个**系统预览上下文**
 * 跑目标工具的真 {@code preview=true} 路径（不落盘），并从 args + preview 递归提取目标资源。
 *
 * <p>★★ <b>批 K ①「让声明成为唯一真相」（用户 2026-10-15：「这玩意不是在程序代码上已经写死了吗……为啥额外要张表？」）</b>： 本类的名单**从工具面派生**——遍历
 * {@code face}，把实现了 {@link DecisionProposable} 的工具逐个包成 {@link Entry}； **源码里没有一个工具名字面量、也没有一条 {@code
 * new XxxTool(...)}**（「谁可被提议」的唯一拼写点 = 工具自己的 {@code implements} 声明，不变量 K1）。以前那张 16 条手写 {@code
 * List.of(...)} 是**第二处声明**：它与工具自己的 {@code roles()} 互不相干、漂移无症状（原始清单里 7 条是 GM-only
 * 却照样能被提议，没有任何东西拦）。装配期由 {@code ProposalDeclarationAudit} fail-closed 校验（实现者 {@code roles()} 必须含
 * DM，不变量 K2）。
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
 *
 * <p>★★ <b>批 3b：类2 世界写的资源面覆盖</b>（设计书 §8.3 第 1/2 条）。九条 {@code simos.gov.*}（{@code {DM,GM} +
 * Ask}，即类2）加进本清单之后，「这条提议的 DM 够不够得着」这一维**仍然只由 {@code ProposeCallTool.validateCall} 那一段判**（{@code
 * ReviseCallTool} 与它共用同一份，本批**不新写第二段校验**）—— 那一段的唯一判据来源是 {@link #targets}。⇒ 补资源面 =
 * **让本类的目标提取覆盖这九条工具真正会碰的资源**，于是那一维自动落进既有的 scope 校验（越界 ⇒ {@code FORBIDDEN}、包不变）。
 *
 * <p>本批补进去的两维（此前**完全不做**越界校验）：
 *
 * <ul>
 *   <li><b>GOV 单位</b>（{@code govUnitId}，见 {@link #UNIT_KEYS}）——九条的资源断言锚点；
 *   <li><b>国库家户</b>（{@code treasuryHousehold}，见 {@link #HOUSEHOLD_KEYS}）——{@code gov.issueMoney}。
 * </ul>
 *
 * <p>★ <b>覆盖不到的（如实记，见批 3b 台账）</b>：九条里有四条声明的是 {@code economy} 命名空间（{@code defineCurrency} / {@code
 * renameCurrency} / {@code setFxRate} / {@code issueMoney}），而 {@code ResourcePaths} 里
 * **没有币种的可寻址路径**（{@code economy(q,r)} 是逐格路径，与"某种币"无关）⇒ **币种这一维不做越界校验**；{@code issueMoney}
 * 的国库格路径（{@code actor:<q>_<r>}）同样不提（把 hex 目标一概当成 actor 目标会让既有工具在辖区格上误拒）。
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
          "targetHouseholdId",
          // ★★ 批 3b：国库家户（`simos.gov.issueMoney` 的 `treasuryHousehold`，也是 GOV 的国库账所在户）。
          //   九条 gov 工具里只有它把国库**显式报进视图**；国库格路径（`actor:<q>_<r>`）不在本键表内——
          //   见类注「资源面覆盖」一节：把 hex 目标同时算成 actor 会让既有工具在辖区格上误拒。
          "treasuryHousehold");

  /**
   * 单位键。
   *
   * <p>★★ 批 3b：加 {@code govUnitId} —— 九条 {@code simos.gov.*} 类2 工具的**资源断言锚点就是它** （其 {@code
   * resources()} 声明 unit 命名空间、{@code requireGovWrite} 断言的也是这个 GOV 单位）。★ 少了这一维， 一个「unit 面够不着自己
   * GOV」的决策人照样能 propose 成功，而那条 call 要等 GM 执行时才见分晓。
   */
  private static final Set<String> UNIT_KEYS =
      Set.of("unitId", "unit", "targetGovUnitId", "govUnitId");

  /** source 元素里的家户键（{@code levyRegion} 的 manpower source 用短名 {@code household}）。 */
  private static final Set<String> SOURCE_HOUSEHOLD_KEYS = Set.of("householdId", "household");

  /**
   * 递归上限：够走到 {@code grain.sources[]}/{@code manpowerAllocation.sources[]}/{@code workOrder} 这几层。
   */
  private static final int MAX_DEPTH = 4;

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final Map<String, Entry> byName;
  private final String mapId;

  /**
   * ★★ <b>唯一形态：名单**从工具面派生**</b>（批 K ① / 不变量 K1）——遍历 {@code face}，把**实现了 {@link
   * DecisionProposable}** 的工具实例逐个包成 {@link Entry}。
   *
   * <p>★ <b>没有第二处名单</b>：本类源码里**一个工具名字面量、一条 {@code new XxxTool(...)} 都没有**（`git grep` 可证）；「我能不能被提议」
   * 的唯一拼写点是工具自己的 {@code implements DecisionProposable}。★ 没实现它的工具**缺省不可提议**——这是"没声明就进不来"，不是靠一张排除名单。
   *
   * <p>★ 工具实例**取自工具面本身**（不是另造一份）：装配期 {@code SimosToolSource} 把可提议工具族 {@code addAll} 进桶、再用**同一个
   * List** 构造本目录 ⇒ 桶里的实例与目录里的实例是同一批对象（"实例现成的"，设计书 §3.2）。
   *
   * @param face 工具面（决策人桶的写面；可提议族必须在其上——装配期由 {@code ProposalDeclarationAudit} 的 C1/C2 钉死）
   * @param mapId 本世界的 map 称谓（目标提取拼 {@code map:} 路径用）
   * @throws IllegalStateException 同一个工具名在面上出现两次（名单按名索引 ⇒ 重名 = 装配故障，不静默取一条）
   */
  public ProposalCatalog(List<AgentTool> face, String mapId) {
    Objects.requireNonNull(face, "face");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    Map<String, Entry> byName = new LinkedHashMap<>();
    for (AgentTool tool : face) {
      if (!(tool instanceof DecisionProposable declaration)) {
        continue; // 「不可提议」是缺省：没实现接口就进不来（不靠一张"排除名单"）。
      }
      Entry entry = new Entry(tool, declaration);
      if (byName.putIfAbsent(tool.name(), entry) != null) {
        throw new IllegalStateException(
            "可提议工具面装配故障："
                + tool.name()
                + " 在工具面上出现两次 —— 本目录按工具名索引，重名会让「这条工具在不在名单里」取决于遍历序（装配故障，不静默取一条）。");
      }
    }
    this.byName = Collections.unmodifiableMap(byName);
  }

  /**
   * 兼容形（用例与诊断**直接建目录**、不经过两桶）：工具面取装配的**同一个唯一构造点** {@link SimosToolSource#decisionProposalTools}。
   *
   * <p>★ 它不是第二条声明路径：可提议族的实例只在那个方法里 {@code new} 一次（决策人桶注册与两个目录都取它）⇒ 「谁能被提议」仍然只由工具自己的 {@code
   * implements DecisionProposable} 决定。
   */
  public ProposalCatalog(
      CoreSimos core,
      CalendarService calendarService,
      QueryService query,
      String initiator,
      String mapId) {
    this(
        SimosToolSource.decisionProposalTools(core, calendarService, query, initiator, mapId),
        mapId);
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

  /**
   * 目录条目：**工具实例**（预览与执行都用它）+ 它**自己声明**的两个目标提取开关。
   *
   * <p>★ 批 K ① 起本记录**不再实现** {@link DecisionProposable}：接口已由**工具自己**实现（那里才是"我能不能被提议"的声明处），
   * 本记录只是"这条工具在名单里"的索引项——两个开关**从工具读**（{@code declaration}），不在目录里另立一份配置。 {@code preview}/{@code
   * targets} 的**唯一实现**仍在本类（不变量 K3）。
   */
  private record Entry(AgentTool tool, DecisionProposable declaration) {

    private Entry {
      Objects.requireNonNull(tool, "tool");
      Objects.requireNonNull(declaration, "declaration");
    }

    /** 工具名（索引键即 {@link AgentTool#name()}，不从别处抄一遍）。 */
    String toolName() {
      return tool.name();
    }

    /** 目标提取时整键跳过的字段（**从工具自己读**）。 */
    Set<String> skipKeys() {
      return declaration.skipKeys();
    }

    /** 是否创建型（**从工具自己读**）。 */
    boolean createsUnit() {
      return declaration.createsUnit();
    }
  }

  /** 目录里的工具名清单（诊断/测试读口）。 */
  public List<String> toolNames() {
    return List.copyOf(new ArrayList<>(byName.keySet()));
  }

  /**
   * 取清单里目标工具的**真实例**（D3 执行器用：拿它跑 {@code planOnly=true} 的系统调用）。
   *
   * @throws IllegalArgumentException 工具不在清单（执行面 fail-closed，不落到任意工具）
   */
  public AgentTool requireTool(String toolName) {
    return require(toolName).tool();
  }
}
