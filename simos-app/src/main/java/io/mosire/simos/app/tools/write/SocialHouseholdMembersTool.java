package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.spi.AddHouseholdMembersHandler;
import io.mosire.simos.social.spi.RemoveHouseholdMembersHandler;
import io.mosire.simos.social.spi.TransferHouseholdMembersHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ★★ {@code simos.social.household.members}（S3a，2026-10-09）：<b>GM 家户成员增/减/转移窄工具</b>—— {@code
 * social.AddHouseholdMembers} / {@code social.RemoveHouseholdMembers} / {@code
 * social.TransferHouseholdMembers} 三条命令的统一入口（固定命令类型由 {@code action} 选择）。
 *
 * <p>★ <b>action 语义</b>：
 *
 * <ul>
 *   <li>{@code add}：{@code householdId + sex + count（+ lotId? + ageAtAnchorDays? +
 *       anchorTick?）}；{@code lotId} 缺省用 {@link AddHouseholdMembersHandler#deriveLotId} 的确定性
 *       id（预览与落盘同一个批次身份）；
 *   <li>{@code remove}：{@code householdId + lotId + count}；
 *   <li>{@code transfer}：{@code from + to + lotId + count}。
 * </ul>
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：preview 先调 {@link HouseholdBook} 的对应纯函数并把前后人口折进视图，
 * <b>一个字节都不写</b>；apply 组一条命令走 {@link CoreSimos#submitBatch}。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）；只写 social 命名空间。
 */
public final class SocialHouseholdMembersTool extends AbstractHouseholdGmTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.social.household.members";

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.SOCIAL_NAMESPACE, "*"));

  public SocialHouseholdMembersTool(CoreSimos core, QueryService query, String initiator) {
    super(core, query, initiator);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 家户成员增/减/转移（三条 social 命令的统一窄封装，一条命令 = 一条 revision）："
        + "action=add ⇒ {householdId, sex(MALE|FEMALE), count(>0), lotId?, ageAtAnchorDays?, anchorTick?, reason}；"
        + "action=remove ⇒ {householdId, lotId, count(>0), reason}；"
        + "action=transfer ⇒ {from, to, lotId, count(>0), reason}。"
        + "add 缺 lotId 时用确定性新批次 id（gm-add:<householdId>，冲突追加 -2）；remove 扣到 0 删批次；"
        + "transfer 保持同一 lot id，在 from/to 两侧按份额持有（不派生新批次）。preview?(缺省 true=只算不写), branch?, expectedRevision?(preview=false 必填)。"
        + "返回 {preview, submitted, action, householdId|from/to, lotId, count, populationBefore/After 或 from/to 两侧人口, commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("action", ToolSupport.prop("string", "add | remove | transfer（必填）"));
    props.put("householdId", ToolSupport.prop("string", "家户 id（action=add/remove 必填）"));
    props.put("from", ToolSupport.prop("string", "转出方家户 id（action=transfer 必填）"));
    props.put("to", ToolSupport.prop("string", "转入方家户 id（action=transfer 必填；不得与 from 相同）"));
    props.put("lotId", ToolSupport.prop("string", "成员批次 id（add 可选，缺省确定性生成；remove/transfer 必填）"));
    props.put("sex", ToolSupport.prop("string", "MALE | FEMALE（action=add 必填）"));
    props.put("count", ToolSupport.prop("integer", "人数（必填，> 0）"));
    props.put("ageAtAnchorDays", ToolSupport.prop("integer", "批次锚点年龄天数（add 可选，缺省 0）"));
    props.put("anchorTick", ToolSupport.prop("integer", "批次锚点世界日（add 可选，缺省当前世界日）"));
    props.put("reason", ToolSupport.prop("string", "原因（必填非空白；进命令载荷与事件）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只算不写；false = 提交命令"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("action", "count", "reason"));
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
    String action = ToolSupport.requiredText(args, "action").toLowerCase(Locale.ROOT);
    long count = ToolSupport.requiredLong(args, "count");
    return switch (action) {
      case "add" -> runAdd(request, base, count);
      case "remove" -> runRemove(request, base, count);
      case "transfer" -> runTransfer(request, base, count);
      default ->
          throw new IllegalArgumentException("参数 action 只认 add | remove | transfer: " + action);
    };
  }

  private ToolResult runAdd(Request request, SocialData base, long count) {
    Map<String, Object> args = request.args();
    HouseholdId id = HouseholdId.parse(ToolSupport.requiredText(args, "householdId"));
    String sexText = ToolSupport.requiredText(args, "sex");
    Sex sex;
    try {
      sex = Sex.valueOf(sexText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("参数 sex 必须是 MALE|FEMALE: " + sexText, e);
    }
    String lotText = ToolSupport.optionalText(args, "lotId", null);
    PeopleLotId lotId =
        lotText == null
            ? AddHouseholdMembersHandler.deriveLotId(base, id)
            : PeopleLotId.parse(lotText);
    Long ageArg = ToolSupport.optionalLong(args, "ageAtAnchorDays");
    Long anchorArg = ToolSupport.optionalLong(args, "anchorTick");
    long ageAtAnchorDays = ageArg == null ? 0L : ageArg;
    long anchorTick = anchorArg == null ? request.state().meta().timestamp().tick() : anchorArg;
    SocialData projected =
        HouseholdBook.addMembers(
            base, id, lotId, sex, count, ageAtAnchorDays, anchorTick, request.reason());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("householdId", id.value());
    payload.put("lotId", lotId.value());
    payload.put("sex", sex.name());
    payload.put("count", count);
    payload.put("ageAtAnchorDays", ageAtAnchorDays);
    payload.put("anchorTick", anchorTick);
    payload.put("reason", request.reason());

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "add");
    view.put("householdId", id.value());
    view.put("lotId", lotId.value());
    view.put("count", count);
    view.put("populationBefore", base.householdPopulation(id));
    view.put("populationAfter", projected.householdPopulation(id));
    return submitOrPreview(request, AddHouseholdMembersHandler.TYPE, payload, view);
  }

  private ToolResult runRemove(Request request, SocialData base, long count) {
    Map<String, Object> args = request.args();
    HouseholdId id = HouseholdId.parse(ToolSupport.requiredText(args, "householdId"));
    PeopleLotId lotId = PeopleLotId.parse(ToolSupport.requiredText(args, "lotId"));
    SocialData projected = HouseholdBook.removeMembers(base, id, lotId, count, request.reason());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("householdId", id.value());
    payload.put("lotId", lotId.value());
    payload.put("count", count);
    payload.put("reason", request.reason());

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "remove");
    view.put("householdId", id.value());
    view.put("lotId", lotId.value());
    view.put("count", count);
    view.put("populationBefore", base.householdPopulation(id));
    view.put("populationAfter", projected.householdPopulation(id));
    return submitOrPreview(request, RemoveHouseholdMembersHandler.TYPE, payload, view);
  }

  private ToolResult runTransfer(Request request, SocialData base, long count) {
    Map<String, Object> args = request.args();
    HouseholdId from = HouseholdId.parse(ToolSupport.requiredText(args, "from"));
    HouseholdId to = HouseholdId.parse(ToolSupport.requiredText(args, "to"));
    PeopleLotId lotId = PeopleLotId.parse(ToolSupport.requiredText(args, "lotId"));
    SocialData projected =
        HouseholdBook.transferMembers(base, from, to, lotId, count, request.reason());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("from", from.value());
    payload.put("to", to.value());
    payload.put("lotId", lotId.value());
    payload.put("count", count);
    payload.put("reason", request.reason());

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("action", "transfer");
    view.put("from", from.value());
    view.put("to", to.value());
    view.put("lotId", lotId.value());
    view.put("count", count);
    view.put("fromPopulationBefore", base.householdPopulation(from));
    view.put("fromPopulationAfter", projected.householdPopulation(from));
    view.put("toPopulationBefore", base.householdPopulation(to));
    view.put("toPopulationAfter", projected.householdPopulation(to));
    return submitOrPreview(request, TransferHouseholdMembersHandler.TYPE, payload, view);
  }

  /** preview / apply / planOnly 的共同收口：同一条载荷；preview 不提交、planOnly 只返回待提交命令批（D3 执行器内部用）。 */
  private ToolResult submitOrPreview(
      Request request, String type, Map<String, Object> payload, Map<String, Object> view) {
    if (request.planOnly()) {
      return planOnly(request, type, payload);
    }
    view.put("commandsPreview", List.of(commandPreview(type, payload)));
    view.put("reason", request.reason());
    if (request.preview()) {
      return preview(view);
    }
    return submitCommand(request, type, payload, view);
  }
}
