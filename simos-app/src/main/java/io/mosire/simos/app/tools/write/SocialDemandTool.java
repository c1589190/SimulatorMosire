package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.provisioning.DemandCoefficient;
import io.mosire.simos.social.provisioning.DemandPeriod;
import io.mosire.simos.social.provisioning.SocialProvisioning;
import io.mosire.simos.social.provisioning.SocialProvisioningEdits;
import io.mosire.simos.social.provisioning.SocialProvisioningEdits.DemandEdit;
import io.mosire.simos.social.spi.SetDemandCoefficientHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ {@code simos.social.demand}（2026-10-09 家户结构修复计划 Batch 4；D 批 2026-10-09 加批量）：<b>GM 改/清 social
 * 需求系数窄工具</b>—— {@code social.SetDemandCoefficient} 的封装（全局默认或单家户覆盖，键 {@code (年龄档, 性别, 商品)}）。
 *
 * <p>★ <b>两种形状</b>（互斥；单条 = Batch 4 的老形状，行为一字未改）：
 *
 * <ul>
 *   <li><b>单条</b>：给 {@code ageBracket/sex/commodity}（{@code householdId} 可选）；
 *   <li><b>批量</b>：给 {@code entries:[{householdId?, ageBracket, sex, commodity, amountMilli?,
 *       period?, cycleDays?, reason?}…]}，顶层不得再给单条参数 —— 一次调用 = 一条 revision，批内任一条不合法整批拒 （零
 *       revision），老的单条调用逐字走老路径。
 * </ul>
 *
 * <p>★ <b>参数语义</b>（与命令同源）：
 *
 * <ul>
 *   <li>{@code householdId} 缺席 = 改全局默认；给了 = 改该家户覆盖（家户必须存在）；
 *   <li>{@code amountMilli} 给了 = upsert；缺席 = 清除该家户覆盖键并回落全局（无 householdId ⇒ 命令具名拒，全局删键不允许）；
 *   <li>{@code period}/{@code cycleDays}：两者都缺席 ⇒ 从该商品的全局默认口径推断；两者都给 ⇒ 按值构造； 只给一个 ⇒
 *       拒。家户覆盖显式口径必须与全局口径一致。
 * </ul>
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：preview 与 apply 都先经 {@link SocialProvisioningEdits}（单条走
 * {@code setDemand}/{@code clearDemand}、批量走 {@code editDemands}）调 {@link SocialProvisioning} 的不可变
 * copy-with 算出目标 provisioning（零状态写入、零 revision）；若 {@code preview=false} 才把同一份载荷提交给 {@link
 * CoreSimos#submit}。 所以预览绝不产生 revision，也不依赖另一套算法。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；命令本身标 {@code GmOnlyCommand}，工具名不是命令类型 ⇒
 * 不进 catalog / {@code PAYLOAD_HINTS}。只写 social 命名空间。★ 批量的加入**没有**新增命令类型、没有新增工具名、没有新增写 资源 ⇒
 * GM/决策人两桶的权限面一字未变（权限单调性）。
 *
 * <p>★ <b>旧档作废、不迁移</b>：预览/提交都只认带第 6 组件 {@code provisioning} 的新档；旧档在状态构造期即具名拒。
 */
public final class SocialDemandTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.social.demand";

  /** 批量形状的参数名（与命令载荷的 {@code entries} 同名字，唯一拼写点见 handler）。 */
  private static final String ENTRIES = SetDemandCoefficientHandler.ENTRIES_FIELD;

  /** 单条形状的参数清单：与 {@code entries} 互斥（给了 entries 就不许再给这些）。 */
  private static final List<String> SINGLE_ARGS =
      List.of(
          "householdId", "ageBracket", "sex", "commodity", "amountMilli", "period", "cycleDays");

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"));

  public SocialDemandTool(CoreSimos core, QueryService query, String initiator) {
    super(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 改/清社会需求系数（social.SetDemandCoefficient 窄封装，一次调用 = 一条 revision）："
        + "单条参数 {householdId?, ageBracket(0-14|15-59|60+), sex(MALE|FEMALE), commodity(如 grain|cloth|tool), "
        + "amountMilli?, period?(PER_CYCLE_DAYS|PER_CALENDAR_YEAR), cycleDays?, reason, preview?(缺省 true=只算不写), "
        + "branch?, expectedRevision?(preview=false 必填)}；"
        + "批量参数 {entries:[{householdId?, ageBracket, sex, commodity, amountMilli?, period?, cycleDays?, "
        + "reason?}…], reason, preview?, branch?, expectedRevision?}（★ 与单条参数互斥；一次改多条、整批原子）。"
        + "householdId 缺席=改全局默认（必须给 amountMilli）；给了=改家户覆盖；amountMilli 缺席=清除该家户覆盖键并回落全局"
        + "（全局不允许删键）。period/cycleDays 都缺席则按该商品全局口径推断；家户覆盖显式口径必须与全局一致。"
        + "返回 {preview, submitted, scope(global|household|batch), householdId?, key?, entries?, before, after, "
        + "commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("householdId", ToolSupport.prop("string", "家户稳定 id；缺席 = 改全局默认，给了 = 改该家户覆盖（家户必须存在）"));
    props.put("ageBracket", ToolSupport.prop("string", "年龄档 0-14 | 15-59 | 60+（单条形状必填）"));
    props.put("sex", ToolSupport.prop("string", "MALE | FEMALE（单条形状必填）"));
    props.put("commodity", ToolSupport.prop("string", "商品 id，如 grain | cloth | tool（单条形状必填）"));
    props.put(
        "amountMilli",
        ToolSupport.prop(
            "integer", "每人每个时间口径的最小计量单位数（≥ 0）；给了 = upsert，缺席 = 清除家户覆盖键（无 householdId ⇒ 拒）"));
    props.put(
        "period",
        ToolSupport.prop(
            "string",
            "时间口径 PER_CYCLE_DAYS | PER_CALENDAR_YEAR；与 cycleDays 必须同时给或同时缺席（缺席=按商品全局口径推断）"));
    props.put(
        "cycleDays",
        ToolSupport.prop(
            "integer", "PER_CYCLE_DAYS 的周期天数（≥ 1）；PER_CALENDAR_YEAR 必须为 0；与 period 同进同出"));
    props.put(
        "entries",
        ToolSupport.prop(
            "array",
            "★ 批量形状（与单条参数 householdId/ageBracket/sex/commodity/amountMilli/period/cycleDays 互斥）："
                + "[{householdId?, ageBracket, sex, commodity, amountMilli?, period?, cycleDays?, reason?}…]；"
                + "一次改多条 = 一条 revision，任一条不合法整批拒（零 revision）"));
    props.put("reason", ToolSupport.prop("string", "改动原因（必填非空白；进命令载荷与事件；批量为批级审计理由）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交命令"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    // ★ reason 是两种形状共有的必填；单条的 ageBracket/sex/commodity 与批量的 entries 各自在自己的形状里必填，
    //   由 run() 逐形状强制（这里是"形状相关"的必填，放不到一张静态 required 表里）。
    return ToolSupport.schema(props, List.of("reason"));
  }

  @Override
  protected ResourceManifest resourceManifest() {
    return SOCIAL_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources() {
    return WRITE_RESOURCES;
  }

  @Override
  protected ToolResult run(Request request) {
    SocialData base = ToolSupport.socialData(request.state());
    Map<String, Object> args = request.args();
    if (args.get(ENTRIES) != null) {
      return runBatch(request, base, args.get(ENTRIES)); // D 批：批量形状（与单条参数互斥）
    }
    String householdText = ToolSupport.optionalText(args, "householdId", null);
    HouseholdId householdId = householdText == null ? null : HouseholdId.parse(householdText);
    AgeBracket ageBracket = ageBracketArg(args, "ageBracket");
    Sex sex = sexArg(args, "sex");
    CommodityId commodity = commodityArg(args, "commodity");
    Long amountMilli = ToolSupport.optionalLong(args, "amountMilli");
    DemandPeriod period = optionalDemandPeriodArg(args, "period");
    Long cycleDays = ToolSupport.optionalLong(args, "cycleDays");
    if (amountMilli == null && (period != null || cycleDays != null)) {
      throw new IllegalArgumentException("amountMilli 缺席（= 清除家户覆盖键）时不得给 period/cycleDays（没有系数可构造）");
    }
    SocialData projected =
        amountMilli == null
            ? SocialProvisioningEdits.clearDemand(base, householdId, ageBracket, sex, commodity)
            : SocialProvisioningEdits.setDemand(
                base, householdId, ageBracket, sex, commodity, amountMilli, period, cycleDays);

    Map<String, Object> payload = new LinkedHashMap<>();
    if (householdId != null) {
      payload.put("householdId", householdId.value());
    }
    payload.put("ageBracket", ageBracket.key());
    payload.put("sex", sex.name());
    payload.put("commodity", commodity.value());
    if (amountMilli != null) {
      payload.put("amountMilli", amountMilli);
    }
    if (period != null) {
      payload.put("period", period.name());
    }
    if (cycleDays != null) {
      payload.put("cycleDays", cycleDays);
    }
    payload.put("reason", request.reason());

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scope", householdId == null ? "global" : "household");
    if (householdId != null) {
      view.put("householdId", householdId.value());
    }
    view.put("key", demandKeyView(ageBracket, sex, commodity));
    view.put(
        "before",
        coefficientView(
            effectiveDemand(base.provisioning(), householdId, ageBracket, sex, commodity)));
    view.put(
        "after",
        coefficientView(
            effectiveDemand(projected.provisioning(), householdId, ageBracket, sex, commodity)));
    view.put("commandsPreview", List.of(commandPreview(SetDemandCoefficientHandler.TYPE, payload)));
    view.put("reason", request.reason());
    if (request.preview()) {
      return preview(view);
    }
    return submitCommand(request, SetDemandCoefficientHandler.TYPE, payload, view);
  }

  /**
   * ★★ <b>批量形状</b>（D 批 2026-10-09）：{@code entries:[…]} ⇒ 一次改多条 = 一条 revision，整批原子。
   *
   * <p>★ 与单条形状**互斥**：给了 {@code entries} 就不许再给顶层单条参数（否则"以哪个为准"没有唯一解释）⇒ 具名拒。 载荷的逐条目字段即 {@link
   * DemandEdit} 的字段；命令载荷由**解析后的** {@code edit} 重建（不直接透传工具参数树）， 因此预览与提交走的是同一份值。
   */
  private ToolResult runBatch(Request request, SocialData base, Object rawEntries) {
    Map<String, Object> args = request.args();
    if (!(rawEntries instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 " + ENTRIES + " 必须是数组（每项 = 一条需求编辑对象）");
    }
    for (String field : SINGLE_ARGS) {
      if (args.get(field) != null) {
        throw new IllegalArgumentException(
            "参数 " + ENTRIES + "（批量）与单条参数 " + field + " 互斥：批量用 entries 逐条给键，单条用顶层参数");
      }
    }
    List<DemandEdit> edits = new ArrayList<>(list.size());
    List<Map<String, Object>> payloadEntries = new ArrayList<>(list.size());
    List<Map<String, Object>> views = new ArrayList<>(list.size());
    for (int index = 0; index < list.size(); index++) {
      Object raw = list.get(index);
      if (!(raw instanceof Map<?, ?> rawMap)) {
        throw new IllegalArgumentException(
            "参数 " + ENTRIES + "[" + index + "] 必须是对象 {ageBracket,sex,commodity,…}");
      }
      Map<String, Object> entry = stringKeyed(rawMap, index);
      DemandEdit edit = demandEditArg(entry, index);
      // ★ 条目自带的 reason 是可选补充信息（进载荷条目、只作该条的审计补充）；这条命令的审计理由 = 工具级 reason。
      String entryReason = entryReasonArg(entry, index);
      edits.add(edit);
      payloadEntries.add(editPayload(edit, entryReason));
    }
    SocialData projected = SocialProvisioningEdits.editDemands(base, edits);
    for (int index = 0; index < edits.size(); index++) {
      DemandEdit edit = edits.get(index);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("scope", edit.householdId() == null ? "global" : "household");
      if (edit.householdId() != null) {
        view.put("householdId", edit.householdId().value());
      }
      view.put("key", demandKeyView(edit.ageBracket(), edit.sex(), edit.commodity()));
      view.put(
          "before",
          coefficientView(
              effectiveDemand(
                  base.provisioning(),
                  edit.householdId(),
                  edit.ageBracket(),
                  edit.sex(),
                  edit.commodity())));
      view.put(
          "after",
          coefficientView(
              effectiveDemand(
                  projected.provisioning(),
                  edit.householdId(),
                  edit.ageBracket(),
                  edit.sex(),
                  edit.commodity())));
      views.add(view);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(ENTRIES, payloadEntries);
    payload.put("reason", request.reason());

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scope", "batch");
    view.put("entries", views);
    view.put("commandsPreview", List.of(commandPreview(SetDemandCoefficientHandler.TYPE, payload)));
    view.put("reason", request.reason());
    if (request.preview()) {
      return preview(view);
    }
    return submitCommand(request, SetDemandCoefficientHandler.TYPE, payload, view);
  }

  /** 工具参数树的嵌套对象 ⇒ 键为字符串的拷贝（JSON 参数树的键恒为字符串；不是 ⇒ 具名拒）。 */
  private static Map<String, Object> stringKeyed(Map<?, ?> rawMap, int index) {
    Map<String, Object> entry = new LinkedHashMap<>();
    for (Map.Entry<?, ?> raw : rawMap.entrySet()) {
      if (!(raw.getKey() instanceof String key)) {
        throw new IllegalArgumentException("参数 " + ENTRIES + "[" + index + "] 的字段名必须是字符串");
      }
      entry.put(key, raw.getValue());
    }
    return entry;
  }

  /** 一条条目参数 ⇒ {@link DemandEdit}（与单条路径同一批参数解析器）；坏条目 ⇒ 带序号的具名拒。 */
  private static DemandEdit demandEditArg(Map<String, Object> entry, int index) {
    try {
      return new DemandEdit(
          optionalHouseholdIdArg(entry),
          ageBracketArg(entry, "ageBracket"),
          sexArg(entry, "sex"),
          commodityArg(entry, "commodity"),
          ToolSupport.optionalLong(entry, "amountMilli"),
          optionalDemandPeriodArg(entry, "period"),
          ToolSupport.optionalLong(entry, "cycleDays"));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "参数 " + ENTRIES + "[" + index + "] 不合法 ⇒ " + e.getMessage(), e);
    }
  }

  /** 条目里的可选 {@code reason}（缺席 ⇒ null；给了但非文本/空白 ⇒ 带序号的具名拒）。 */
  private static String entryReasonArg(Map<String, Object> entry, int index) {
    try {
      return ToolSupport.optionalText(entry, "reason", null);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "参数 " + ENTRIES + "[" + index + "] 不合法 ⇒ " + e.getMessage(), e);
    }
  }

  /** 条目里的可选 {@code householdId}（缺席 ⇒ null = 全局默认）。 */
  private static HouseholdId optionalHouseholdIdArg(Map<String, Object> entry) {
    String text = ToolSupport.optionalText(entry, "householdId", null);
    return text == null ? null : HouseholdId.parse(text);
  }

  /** 由**解析后的** {@link DemandEdit} 重建命令载荷条目（与单条路径的载荷构造同形）： 预览与提交因此共用同一份值， 不存在"预览算一套、载荷写另一套"的接缝。 */
  private static Map<String, Object> editPayload(DemandEdit edit, String entryReason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (edit.householdId() != null) {
      payload.put("householdId", edit.householdId().value());
    }
    payload.put("ageBracket", edit.ageBracket().key());
    payload.put("sex", edit.sex().name());
    payload.put("commodity", edit.commodity().value());
    if (edit.amountMilli() != null) {
      payload.put("amountMilli", edit.amountMilli());
    }
    if (edit.period() != null) {
      payload.put("period", edit.period().name());
    }
    if (edit.cycleDays() != null) {
      payload.put("cycleDays", edit.cycleDays());
    }
    if (entryReason != null) {
      payload.put("reason", entryReason);
    }
    return payload;
  }

  /** 有效需求系数：家户覆盖优先、全局默认兜底；都没有 ⇒ 空（清除覆盖后的 after 可以是这种形态）。 */
  private static Optional<DemandCoefficient> effectiveDemand(
      SocialProvisioning provisioning,
      HouseholdId householdId,
      AgeBracket ageBracket,
      Sex sex,
      CommodityId commodity) {
    return householdId == null
        ? provisioning.globalDemand(ageBracket, sex, commodity)
        : provisioning.findDemandOptionally(householdId, ageBracket, sex, commodity);
  }

  private static Map<String, Object> demandKeyView(
      AgeBracket ageBracket, Sex sex, CommodityId commodity) {
    Map<String, Object> key = new LinkedHashMap<>();
    key.put("ageBracket", ageBracket.key());
    key.put("sex", sex.name());
    key.put("commodity", commodity.value());
    return key;
  }

  /** 系数视图：存在 ⇒ 值三件套；不存在 ⇒ {@code {present:false}}（不填 0，避免把"没有这条键"伪装成 0 需求）。 */
  private static Map<String, Object> coefficientView(Optional<DemandCoefficient> coefficient) {
    Map<String, Object> view = new LinkedHashMap<>();
    if (coefficient.isEmpty()) {
      view.put("present", false);
      return view;
    }
    DemandCoefficient value = coefficient.get();
    view.put("present", true);
    view.put("amountMilli", value.amountMilli());
    view.put("period", value.period().name());
    view.put("cycleDays", value.cycleDays());
    return view;
  }
}
