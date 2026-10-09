package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.SocialLogSource;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.provisioning.DemandCoefficient;
import io.mosire.simos.social.provisioning.DemandPeriod;
import io.mosire.simos.social.provisioning.SocialProvisioningEdits;
import io.mosire.simos.social.provisioning.SocialProvisioningEdits.DemandEdit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code social.SetDemandCoefficient} 命令的处理器（2026-10-09 家户结构修复计划 Batch 4；D 批 2026-10-09 加批量）：
 * 改/清 social 需求系数表（全局默认或单家户覆盖）。
 *
 * <p>★★ <b>两种载荷形状（互斥；批是可选的第二条路，老载荷逐字走老路径）</b>：
 *
 * <pre>{@code
 * ① 单条（老形状，语义与实现一字未改）：
 * {"householdId"?:"hh-1", "ageBracket":"0-14|15-59|60+", "sex":"MALE|FEMALE",
 *  "commodity":"grain|cloth|...", "amountMilli"?:整数,
 *  "period"?:"PER_CYCLE_DAYS|PER_CALENDAR_YEAR", "cycleDays"?:整数, "reason":"..."}
 *
 * ② 批量（D 批新增；一次改多条 = 一条 revision，整批原子）：
 * {"reason":"...", "entries":[{同上单条的逐条目字段（不含 batch 级 reason）}, {...}]}
 * }</pre>
 *
 * <p>★★ <b>语义</b>（与 {@link SocialProvisioningEdits} 同源，本 handler 只做载荷解析 + 日志 + 变更集）：
 *
 * <ul>
 *   <li>{@code householdId} 缺席 = 改<b>全局默认</b>（必须给 {@code amountMilli}）；给了 = 改该<b>家户覆盖</b>（家户必须存在）；
 *   <li>{@code amountMilli} 给了 = upsert 该 {@code (年龄档, 性别, 商品)}；缺席 = 删除该家户覆盖键 （只允许 householdId
 *       在场；全局默认不允许删键 ⇒ 具名拒）；
 *   <li>{@code period}/{@code cycleDays}：两者都缺席 ⇒ 从该商品的全局默认口径推断（找不到 ⇒ 具名拒）； 两者都给 ⇒ 按值构造；只给一个 ⇒
 *       具名拒。家户覆盖的显式口径必须与全局口径一致；
 *   <li>结果只经 {@link SocialData#withProvisioning} 写回，产出 {@link SocialChangeSet}；改/清各 INFO 一条带
 *       scope/household/年龄档/性别/商品/amount 的事件，失败一律 {@link HandlerOutcome.Rejected} 带中文原因。
 * </ul>
 *
 * <p>★★ <b>批量的原子性与形状约束</b>（D 批 2026-10-09，用户原话见设计书 §1.4）：
 *
 * <ul>
 *   <li>{@code entries} 在场 ⇒ 走批：顶层**不得**再给单条字段（{@code householdId/ageBracket/sex/commodity/
 *       amountMilli/period/cycleDays}），两种形状互斥 ⇒ 否则具名拒（不猜"以哪个为准"）；
 *   <li>整批只落**一条** {@link SocialChangeSet}（{@link SocialChangeSet#between} 在全部条目算完之后才比一次） ⇒
 *       任一条不合法就整批具名拒，**零 revision、head 不动**（没有"改了一半"的中间态）；
 *   <li>逐条目字段与单条完全同形（含可选的 {@code reason}，只进逐条 DEBUG 明细）；批级 {@code reason} 仍是必填， 它是这条 revision
 *       的审计理由；
 *   <li>条数上限与"批内目标键不得重复"由 {@link SocialProvisioningEdits#editDemands} 收口（唯一拼写点）。
 * </ul>
 *
 * <p>★★ <b>GM-only</b>（实现 {@link GmOnlyCommand}）：本命令仍注册到 Core、仍进 {@code commandTargets}、GM 的 {@code
 * simos.command.submit} 与窄工具照常可用；但不得作为决策人令 / {@code RegisterEffect} 嵌入， 也不进决策人目录——
 * 需求系数是全世界/全户的调参面，不是普通 GOV 的政治能力。★ 批量的加入**没有**新增命令类型、没有新增工具名、没有 新增写资源 ⇒ GM/决策人两桶的权限面一字未变（权限单调性）。
 *
 * <p>★ <b>旧档作废、不迁移</b>（用户 2026-10-09 裁定"一切从新"）：本命令只认已经带第 6 组件 {@code provisioning} 的新档；旧档缺该组件在
 * {@link SocialData} / {@link SocialChangeSet} 构造期即具名拒，本命令不补默认值、不做双读。
 */
public final class SetDemandCoefficientHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点：Shell 注册、窄工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.SetDemandCoefficient";

  /** 批量载荷的条目数组字段名（唯一拼写点；与 {@code social.SeedGroups} 的 {@code entries} 同名同制）。 */
  public static final String ENTRIES_FIELD = "entries";

  /** 单条形状的字段清单：批载荷里出现任何一个 ⇒ 具名拒（两种形状互斥）。 */
  private static final List<String> SINGLE_FIELDS =
      List.of(
          "householdId", "ageBracket", "sex", "commodity", "amountMilli", "period", "cycleDays");

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    SocialPayloads.parse(payloadJson); // 坏载荷照样在判目标时抛（fail-closed），只是没有目标可给
    return List.of();
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    JsonNode payload;
    try {
      payload = SocialPayloads.parse(payloadJson);
    } catch (IllegalArgumentException e) {
      // 解析都没过 ⇒ 谈不上"批还是单条"，拒因事件与单条同一条（老行为逐字保留）。
      return rejectSingle(e);
    }
    return hasEntries(payload) ? handleBatch(base, payload) : handleSingle(base, payload);
  }

  /** {@code entries} 在场（且不是 JSON null）⇒ 批载荷；缺席/null ⇒ 老的单条载荷（逐字走老路径）。 */
  private static boolean hasEntries(JsonNode payload) {
    JsonNode entries = payload.get(ENTRIES_FIELD);
    return entries != null && !entries.isNull();
  }

  /** ★ <b>老的单条路径</b>（Batch 4 的实现，语义与日志一字未改）。 */
  private HandlerOutcome handleSingle(SocialData base, JsonNode payload) {
    try {
      HouseholdId householdId = SocialPayloads.optionalHouseholdId(payload, "householdId");
      AgeBracket ageBracket = SocialPayloads.requireAgeBracket(payload, "ageBracket");
      Sex sex = SocialPayloads.requireSex(payload, "sex");
      CommodityId commodity = SocialPayloads.requireCommodity(payload, "commodity");
      Long amountMilli = SocialPayloads.optionalLong(payload, "amountMilli");
      DemandPeriod period = SocialPayloads.optionalDemandPeriod(payload, "period");
      Long cycleDays = SocialPayloads.optionalLong(payload, "cycleDays");
      String reason = SocialPayloads.requireReason(payload);
      if (amountMilli == null) {
        if (period != null || cycleDays != null) {
          throw new IllegalArgumentException(TYPE + "：删除家户覆盖键时不接受 period/cycleDays（没有系数可构造）");
        }
        SocialData next =
            SocialProvisioningEdits.clearDemand(base, householdId, ageBracket, sex, commodity);
        DemandCoefficient removed =
            base.provisioning()
                .householdDemandOverride(householdId, ageBracket, sex, commodity)
                .orElse(null);
        EventLog.channel(SocialLog.provisioning())
            .info(
                LogEvent.of(
                    "SOCIAL_DEMAND_COEFFICIENT_CLEARED",
                    SocialLogSource.SOCIAL_PROVISIONING,
                    "scope",
                    "household",
                    "household",
                    householdId,
                    "ageBracket",
                    ageBracket.key(),
                    "sex",
                    sex,
                    "commodity",
                    commodity,
                    "amountMilli",
                    removed == null ? "none" : removed.amountMilli(),
                    "reason",
                    reason));
        return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
      }
      SocialData next =
          SocialProvisioningEdits.setDemand(
              base, householdId, ageBracket, sex, commodity, amountMilli, period, cycleDays);
      DemandCoefficient effective =
          householdId == null
              ? next.provisioning().globalDemand(ageBracket, sex, commodity).orElse(null)
              : next.provisioning()
                  .householdDemandOverride(householdId, ageBracket, sex, commodity)
                  .orElse(null);
      EventLog.channel(SocialLog.provisioning())
          .info(
              LogEvent.of(
                  "SOCIAL_DEMAND_COEFFICIENT_SET",
                  SocialLogSource.SOCIAL_PROVISIONING,
                  "scope",
                  householdId == null ? "global" : "household",
                  "household",
                  householdId == null ? "-" : householdId,
                  "ageBracket",
                  ageBracket.key(),
                  "sex",
                  sex,
                  "commodity",
                  commodity,
                  "amountMilli",
                  amountMilli,
                  "period",
                  effective == null ? period : effective.period(),
                  "cycleDays",
                  effective == null ? cycleDays : effective.cycleDays(),
                  "reason",
                  reason));
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return rejectSingle(e);
    }
  }

  /**
   * ★★ <b>批路径</b>（D 批）：{@code {"reason":…,"entries":[…]} ⇒ 一条 revision}。
   *
   * <p>日志（AGENTS §一.9）：成功 INFO 一条带"几条 upsert / 几条删除 / 涉及键数 / 涉及家户数"；DEBUG 另记逐条目明细； 失败 WARN
   * 一条具名拒因（{@code SOCIAL_DEMAND_COEFFICIENT_BATCH_REJECTED}）+ DEBUG 一条"为什么"（含第几条与目标键）。
   */
  private HandlerOutcome handleBatch(SocialData base, JsonNode payload) {
    try {
      String reason = SocialPayloads.requireReason(payload);
      for (String field : SINGLE_FIELDS) {
        JsonNode value = payload.get(field);
        if (value != null && !value.isNull()) {
          throw new IllegalArgumentException(
              TYPE + "：批载荷（" + ENTRIES_FIELD + "）与单条字段 " + field + " 互斥（批用 entries 逐条给键，单条用顶层字段）");
        }
      }
      JsonNode entries = payload.get(ENTRIES_FIELD);
      if (!entries.isArray()) {
        throw new IllegalArgumentException(TYPE + "：字段 " + ENTRIES_FIELD + " 必须是数组");
      }
      List<DemandEdit> edits = new ArrayList<>(entries.size());
      List<String> entryReasons = new ArrayList<>(entries.size());
      for (int index = 0; index < entries.size(); index++) {
        JsonNode entry = entries.get(index);
        if (entry == null || !entry.isObject()) {
          throw new IllegalArgumentException(
              TYPE + "：" + ENTRIES_FIELD + "[" + index + "] 必须是 JSON 对象");
        }
        edits.add(parseEdit(entry, index));
        // ★ 条目自带的 reason 是可选补充信息（只进 DEBUG 明细）；这条 revision 的审计理由 = 批级 reason。
        entryReasons.add(SocialPayloads.optionalNonBlankText(entry, "reason"));
      }
      SocialData next = SocialProvisioningEdits.editDemands(base, edits);
      int upserts = 0;
      int deletes = 0;
      int globalRows = 0;
      Map<HouseholdId, Integer> households = new LinkedHashMap<>();
      for (DemandEdit edit : edits) {
        if (edit.amountMilli() == null) {
          deletes++;
        } else {
          upserts++;
        }
        if (edit.householdId() == null) {
          globalRows++;
        } else {
          households.merge(edit.householdId(), 1, Integer::sum);
        }
      }
      EventLog.channel(SocialLog.provisioning())
          .info(
              LogEvent.of(
                  "SOCIAL_DEMAND_COEFFICIENT_BATCH_APPLIED",
                  SocialLogSource.SOCIAL_PROVISIONING,
                  "entries",
                  edits.size(),
                  "upserts",
                  upserts,
                  "deletes",
                  deletes,
                  "globalRows",
                  globalRows,
                  "households",
                  households.size(),
                  "keys",
                  edits.size(),
                  "reason",
                  reason));
      if (SocialLog.provisioning().isDebugEnabled()) {
        List<String> detail = new ArrayList<>(edits.size());
        for (int index = 0; index < edits.size(); index++) {
          DemandEdit edit = edits.get(index);
          detail.add(
              index
                  + ":"
                  + (edit.householdId() == null ? "global" : edit.householdId().value())
                  + "/"
                  + edit.ageBracket().key()
                  + "/"
                  + edit.sex()
                  + "/"
                  + edit.commodity().value()
                  + "/"
                  + (edit.amountMilli() == null ? "delete" : edit.amountMilli())
                  + (entryReasons.get(index) == null ? "" : "/" + entryReasons.get(index)));
        }
        EventLog.channel(SocialLog.provisioning())
            .debug(
                LogEvent.of(
                    "SOCIAL_DEMAND_COEFFICIENT_BATCH_ENTRIES",
                    SocialLogSource.SOCIAL_PROVISIONING,
                    "entries",
                    detail,
                    "reason",
                    reason));
      }
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(SocialLog.provisioning())
          .warn(
              LogEvent.of(
                  "SOCIAL_DEMAND_COEFFICIENT_BATCH_REJECTED",
                  SocialLogSource.SOCIAL_PROVISIONING,
                  "reason",
                  SocialPayloads.logReason(e.getMessage())));
      if (SocialLog.provisioning().isDebugEnabled()) {
        // ★ AGENTS §一.9：具名拒在 DEBUG 写清"为什么"（第几条 / 哪个目标键 / 内层原因），仍是日志安全的截断文本。
        EventLog.channel(SocialLog.provisioning())
            .debug(
                LogEvent.of(
                    "SOCIAL_DEMAND_COEFFICIENT_BATCH_REJECTED_DETAIL",
                    SocialLogSource.SOCIAL_PROVISIONING,
                    "detail",
                    SocialPayloads.logReason(e.getMessage())));
      }
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 逐条目解析（与单条同一批 {@link SocialPayloads} 解析器、同一份字段语义）；坏条目 ⇒ 带序号的具名拒。 */
  private static DemandEdit parseEdit(JsonNode entry, int index) {
    try {
      return new DemandEdit(
          SocialPayloads.optionalHouseholdId(entry, "householdId"),
          SocialPayloads.requireAgeBracket(entry, "ageBracket"),
          SocialPayloads.requireSex(entry, "sex"),
          SocialPayloads.requireCommodity(entry, "commodity"),
          SocialPayloads.optionalLong(entry, "amountMilli"),
          SocialPayloads.optionalDemandPeriod(entry, "period"),
          SocialPayloads.optionalLong(entry, "cycleDays"));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          TYPE + "：" + ENTRIES_FIELD + "[" + index + "] 不合法 ⇒ " + e.getMessage(), e);
    }
  }

  /** 单条路径的具名拒（老行为逐字保留：WARN + {@code Rejected} 带原消息）。 */
  private static HandlerOutcome rejectSingle(IllegalArgumentException e) {
    EventLog.channel(SocialLog.provisioning())
        .warn(
            LogEvent.of(
                "SOCIAL_DEMAND_COEFFICIENT_REJECTED",
                SocialLogSource.SOCIAL_PROVISIONING,
                "reason",
                SocialPayloads.logReason(e.getMessage())));
    return new HandlerOutcome.Rejected(e.getMessage());
  }
}
