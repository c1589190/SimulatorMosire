package io.mosire.simos.social.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.SocialLogSource;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.spi.CreateHouseholdHandler;
import io.mosire.simos.social.tools.SocialToolReads;
import io.mosire.simos.social.tools.SocialUnitPort;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code simos.social.household.create}（S3a，2026-10-09）：<b>GM 创建家户窄工具</b>—— {@code
 * social.CreateHousehold} 的封装；若位置是 {@code UNIT(...)}，同批追加 {@code unit.SetUnitHouseholds}
 * 把家户加入目标单位，保证"Household.location = UNIT(unitId) 且 unit.households 含该家户"两个切片在<b>同一条 revision</b>
 * 内同时成立（架构 §3.3）。
 *
 * <p>★★ <b>preview / apply 共用同一份纯推导</b>：{@link HouseholdBook#create}（social 真值）+ {@code
 * UnitOperations.setUnitHouseholds}（unit 侧守卫）都在 preview 时先跑一遍；{@code preview=true（缺省）}只出入参校验、
 * 目标状态视图与逐条命令预览，一个字节都不写；{@code preview=false} 才走 {@code ToolWritePort.submitBatchWithReceipt}（一批 =
 * 一条 revision， 任一条失败整批不落）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：工具名不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}。
 *
 * <p>★ <b>资源声明</b>：写 {@code social} + {@code unit} 两个命名空间（UNIT 位置会同时写 unit 侧；GM 侧两者 unlimited）。
 */
public final class SocialHouseholdCreateTool extends AbstractHouseholdGmTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.social.household.create";

  /** 单位编制口（跨域事实：家户归属的权威在 unit 侧；social 结构上看不见 unit 切片）。 */
  private final SocialUnitPort units;

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(
          ResourceId.of(ToolResources.SOCIAL_NAMESPACE, "*"),
          ResourceId.of(ToolResources.UNIT_NAMESPACE, "*"));

  public SocialHouseholdCreateTool(ToolWritePort writes, ToolReadPort reads, SocialUnitPort units) {
    super(writes, reads);
    this.units = Objects.requireNonNull(units, "units");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 创建家户（social.CreateHousehold 窄封装，最多再加一条 unit.SetUnitHouseholds，同批一条 revision）："
        + "参数 {householdId, location:{type:HEX|UNIT, hex:{q,r}|unitId}, name, description?, metadata?, "
        + "vitalRates?:[{bracketId,sex,birthRatePerMillionPerTick?,deathRatePerMillionPerTick?}], reason, "
        + "preview?(缺省 true=只算不写), branch?, expectedRevision?(preview=false 必填)}。"
        + "location 为 UNIT 时先校验单位存在、家户 id 不与 unit id 撞名，并同批把家户加入 unit.households"
        + "（保证社会位置与 unit 侧容纳列表一致）；location 为 HEX 时只落一条 CreateHousehold。"
        + "返回 {preview, submitted, household:{id,location,name,population}, unitHouseholds?, commandsPreview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("householdId", ToolSchema.prop("string", "家户稳定 id（必填；不得与 unit id 撞名）"));
    props.put(
        "location",
        ToolSchema.prop(
            "object", "位置 {type:HEX|UNIT, hex:{q,r}（HEX 必填）| unitId（UNIT 必填）}；HEX 必须在本世界地图上"));
    props.put("name", ToolSchema.prop("string", "家户画像显示名（必填非空白）"));
    props.put("description", ToolSchema.prop("string", "家户画像描述（可选）"));
    props.put("metadata", ToolSchema.prop("object", "家户画像元数据 {键:字符串}（可选）"));
    props.put(
        "vitalRates",
        ToolSchema.prop(
            "array",
            "出生/死亡率表 [{bracketId(如 15-59),sex(MALE|FEMALE),birthRatePerMillionPerTick?,"
                + "deathRatePerMillionPerTick?}]（可选，缺省空表；两个率缺省 0，负数/重复 (bracketId,sex) 拒；单位 ppm/tick）"));
    props.put("reason", ToolSchema.prop("string", "创建原因（必填非空白；进命令载荷与工具结果）"));
    props.put("preview", ToolSchema.prop("boolean", "true（缺省）= 只算不写；false = 提交命令批"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("householdId", "location", "name", "reason"));
  }

  @Override
  protected ResourceManifest resourceManifest() {
    return SOCIAL_AND_UNIT_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources() {
    return WRITE_RESOURCES;
  }

  @Override
  protected ToolResult run(Request request) {
    SocialData base = SocialToolReads.socialData(request.state());
    Map<String, Object> args = request.args();
    HouseholdId id = HouseholdId.parse(ToolSchema.requiredText(args, "householdId"));
    HouseholdLocation location = locationArg(args, "location");
    if (location instanceof HouseholdLocation.Hex hex) {
      requireHexOnMap(request.state(), hex.hex());
    }
    HouseholdProfile profile =
        new HouseholdProfile(
            ToolSchema.requiredText(args, "name"),
            ToolSchema.optionalText(args, "description", null),
            stringMapArg(args, "metadata"));
    List<HouseholdVitalRate> rates = vitalRatesArg(args, "vitalRates");
    // ★ 与 handler 同源的纯推导：id 重复 / profile / 率表形状在 preview 阶段就具名拒（不提交半笔）。
    SocialData projected =
        HouseholdBook.create(base, id, location, profile, new HouseholdVitalRates(rates));

    List<PlannedCommand> batch = new ArrayList<>();
    List<Map<String, Object>> commandsPreview = new ArrayList<>();

    Map<String, Object> createPayload = new LinkedHashMap<>();
    createPayload.put("householdId", id.value());
    createPayload.put("location", locationPayload(location));
    Map<String, Object> profilePayload = new LinkedHashMap<>();
    profilePayload.put("name", profile.name());
    if (profile.description() != null) {
      profilePayload.put("description", profile.description());
    }
    if (!profile.metadata().isEmpty()) {
      profilePayload.put("metadata", new LinkedHashMap<>(profile.metadata()));
    }
    createPayload.put("profile", profilePayload);
    if (!rates.isEmpty()) {
      createPayload.put("vitalRates", ratesPayload(rates));
    }
    createPayload.put("reason", request.reason());
    batch.add(new PlannedCommand(CreateHouseholdHandler.TYPE, createPayload));
    commandsPreview.add(commandPreview(CreateHouseholdHandler.TYPE, createPayload));

    Map<String, Object> unitView = null;
    if (location instanceof HouseholdLocation.Unit unitLocation) {
      String rawUnitId = unitLocation.unitId();
      // ★ 单位编制是跨域事实（权威在 unit 侧）⇒ 经注入的单位编制口读；文案与既有实现逐字相同。
      SocialUnitPort.UnitHouseholds unit =
          units.requireUnit(request.state(), rawUnitId, "location.unitId");
      if (unit.unitId().equals(id.value())) {
        throw new IllegalArgumentException("家户 id 不得与 unit id 撞名: " + id.value());
      }
      if (unit.households().contains(id.value())) {
        throw new IllegalArgumentException(
            "单位 " + rawUnitId + " 的 households 已声明家户 " + id + "，但 Social 中尚不存在该家户（状态不一致，拒绝覆盖）");
      }
      List<HouseholdId> beforeHouseholds =
          SocialHouseholdMoveTool.parseHouseholds(unit.households());
      List<HouseholdId> nextHouseholds = new ArrayList<>(beforeHouseholds);
      nextHouseholds.add(id);
      // ★ unit 侧守卫（不撞名/不重复/跨单位不重属）在建批前先跑一遍，坏输入不提交半笔。
      units.requireValidHouseholds(request.state(), unit.unitId(), householdIds(nextHouseholds));
      Map<String, Object> unitPayload = new LinkedHashMap<>();
      unitPayload.put("unitId", rawUnitId);
      unitPayload.put("households", householdIds(nextHouseholds));
      unitPayload.put("reason", request.reason());
      batch.add(new PlannedCommand(units.unitHouseholdsCommandType(), unitPayload));
      commandsPreview.add(commandPreview(units.unitHouseholdsCommandType(), unitPayload));
      unitView = new LinkedHashMap<>();
      unitView.put("unitId", rawUnitId);
      unitView.put("householdsBefore", householdIds(beforeHouseholds));
      unitView.put("householdsAfter", householdIds(nextHouseholds));
    }

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("householdId", id.value());
    view.put("location", locationView(location));
    view.put("name", profile.name());
    view.put("population", projected.householdPopulation(id));
    if (unitView != null) {
      view.put("unit", unitView);
    }
    view.put("commandsPreview", commandsPreview);
    view.put("reason", request.reason());
    if (request.preview()) {
      return preview(view);
    }
    if (location instanceof HouseholdLocation.Unit unitLocation) {
      return submitBatch(
          request,
          batch,
          view,
          () ->
              EventLog.channel(SocialLog.command())
                  .info(
                      LogEvent.of(
                          "UNIT_HOUSEHOLD_ASSIGN",
                          SocialLogSource.SOCIAL_COMMAND,
                          "unit",
                          unitLocation.unitId(),
                          "household",
                          id,
                          "location",
                          "UNIT:" + unitLocation.unitId(),
                          "created",
                          true,
                          "reasonLength",
                          request.reason() == null ? 0 : request.reason().length())));
    }
    return submitCommand(request, CreateHouseholdHandler.TYPE, createPayload, view);
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
