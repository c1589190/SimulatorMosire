package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.ga.id.DecisionInfoId;
import io.mosire.simos.ga.id.DirectiveId;
import io.mosire.simos.ga.model.AdjudicationStatus;
import io.mosire.simos.ga.model.DecisionInfoEntry;
import io.mosire.simos.ga.model.DecisionInfoIds;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code ga.PutInfo} 命令的处理器（spec §六，R14；<b>★ 2026-10-12 批次 3b-2 自 {@code simos-sd/sd.spi} 迁入并 改名
 * {@code sd.* → ga.*}，语义一字未改</b>）：决策族的 INFO 覆盖层写路径（该覆盖层本批起住 {@code GaState.info}）。
 *
 * <pre>{@code
 * {"address":"map:Map1:region.r1","key":"brief","value":"……","note":"可选",
 *  "id":"可选：显式 id（同类型内唯一）","tags":["可选：决策人 id…"],"tick":"可选：该条目所属 tick",
 *  "affiliations":["nation:shanghai"]}
 * }</pre>
 *
 * <p>★ **不复用全局 {@code InfoSystem}**（spec §六）：写进 {@code GaChangeSet.info} → 进 revision，可重放、受铁律 5
 * 往返守卫。 **写的是感知层**（供 UI / AAR 展示），ground truth 仍由领域模块持有。
 *
 * <p>★★ **决策结果三件套（第 3 波第 1 步）**：本命令写的就是**决策结果本身**（INFO 条目），故三条新字段在这里落地—— {@code id}/{@code
 * tick}/{@code tags}。{@code id} 与 {@code tags} 是**可选载荷**（向后兼容：旧调用不带这两个键照常成立）：
 *
 * <ul>
 *   <li>{@code id} 缺席 ⇒ {@link DecisionInfoIds#synthesize}（canonical 地址 + 该地址下追加序号，注入）；给了 ⇒
 *       用它，并在**命令期** **重复即拒**（见下）；
 *   <li>{@code tags} 缺席 ⇒ 空集（无主）；给了 ⇒ 逐字解析成 {@link DecisionMakerId}（**不**在此校验决策人是否存在——
 *       那是后续步骤的语义，本命令只认形状）；
 *   <li>{@code affiliations} <b>不透明的受众标签集</b>（2026-10-12 乙收尾改造）：缺席/null ⇒ 空集（不按归属发）；给了 ⇒
 *       必须是<b>非空白字符串数组</b>（形如 {@code "nation:n1"} / {@code "army:a1"} / {@code "gov:g1"}），
 *       <b>本层只做"非空白"这一条形状检查，不解析、不枚举种类</b>——用户口径「<b>归属肯定归模块啊，后面新加模块 ga
 *       还得显式地重新添加？不可能的</b>」。标签由**写入方自报**，它与 {@code tags} 是**并集**关系，解释与判定<b>只</b>在 app 的 {@code
 *       RedactingQueryService#docs}（决策人自己的标签由各模块自报的归属现拼）；
 *   <li>{@code tick} 缺席 ⇒ **世界当前 tick**（第 3 波第 1 步的既有行为，向后兼容）；给了 ⇒ 用它，但 **不得记在未来** （{@code > 世界
 *       tick} ⇒ 拒，与 {@code ga.IssueDirective} 同口径）。**过去合法**（补记/滞后）；
 *   <li>{@code sourceDirective}（2026-10-12 批次 3b-1 新增的**可选**字段）缺席 ⇒ {@link
 *       DecisionInfoEntry#sourceDirective()} 为 空（旧调用一字不变）；给了 ⇒ 解析成 {@link DirectiveId} 写进去。★★
 *       <b>3b-2 起它不再是出令执行原文的载体</b>：{@code info} 表搬进 ga 后，{@code ga.IssueDirective} <b>一条命令**自己**写令
 *       + 执行原文</b>（见该类注），配套的 {@code DirectiveIntentInfo} 与 companion 机制已删除。 本字段作为**合法载荷口**保留（它是
 *       {@link DecisionInfoEntry} 的既有字段，读侧 {@code DecisionQueryService} / {@code
 *       VoidAdjudicationTool} 仍按它索引）——删它等于缩载荷契约，与"行为等价"冲突。★ 本命令**不**校验该令是否存在 （它只认形状，与 {@code tags}
 *       同口径）。
 * </ul>
 *
 * <p>★ 诚实边界（spec §六 + M4 裁定 38 同口径）：{@code DecisionInfoEntry.value} 是**裸 {@code Object}**，结构化值的
 * {@code equals} 往返不满足 ⇒ 判据只覆盖标量值；结构化值列为挂起项。
 *
 * <p>拒绝：地址非法（{@link Address#parse} 抛）；{@code key} 空白；{@code value} 缺失；{@code id} 空白或与既有条目**撞 id**；
 * {@code tags}/{@code affiliations} 元素非法；{@code tick} 记在未来。
 */
public final class PutInfoHandler implements CommandHandler {

  /** 命令类型（信封上的 {@code type}；P1.5 起 Plan 类直接引用本常量，不再另抄字面量）。 */
  public static final String TYPE = "ga.PutInfo";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GaState base = GaSnapshots.state(state);
    String addressForLog = null;
    String keyForLog = null;
    String infoIdForLog = null;
    Long tickForLog = null;
    try {
      JsonNode payload = GaPayloads.parse(payloadJson);
      Address address = GaPayloads.requireAddress(payload, "address");
      addressForLog = address.canonical();
      String key = GaPayloads.requireText(payload, "key");
      keyForLog = key;
      Object value = GaPayloads.requireValue(payload, "value");
      Optional<String> note = GaPayloads.optionalText(payload, "note");
      Set<DecisionMakerId> tags = parseTags(payload);
      // ★★ 乙收尾：affiliations 是**不透明受众标签集**（写入方自报的串，形如 nation:n1 / army:a1 / gov:g1）——
      //   与 tags 同族"缺省即空集"，但**不解析任何形状**（旧形状 {"kind","id"} 的 kind switch 正是"新加模块要改 ga"
      //   的根源，本批删掉）。解释与可见性判定只在 app 的 RedactingQueryService。
      Set<String> affiliations = GaPayloads.optionalTextSet(payload, "affiliations");
      Optional<AdjudicationStatus> adjudicationStatus = parseAdjudicationStatus(payload);
      Optional<DecisionInfoId> explicitId =
          GaPayloads.optionalText(payload, "id").map(DecisionInfoId::parse);
      // ★ 可选来源引用（批次 3b′）：出令执行原文条目靠它挂回那条令（见类注）。
      Optional<DirectiveId> sourceDirective =
          GaPayloads.optionalText(payload, "sourceDirective").map(DirectiveId::parse);
      RevisionId at = state.meta().ref().revision();
      long worldTick = state.meta().timestamp().tick();
      // ★ 可选 tick（第 3 波第 2 步）：缺省 = 世界当前 tick（**向后兼容**，旧调用一字不变）；显式给 = 用它。
      //   ★ 为什么允许**过去**的 tick：`IssueDirectiveHandler` 明确允许"补记/滞后"的令（只拒未来）⇒ 若这里
      //   只收当前 tick，那些令的**决策结果**将永远归不到正确的 tick 上（制造孤儿）。语义是：命令的**效果**仍落在
      //   当下（世界在 current tick），只是**条目带该 tick**（归属正确）。★ 未来 tick 照旧拒（与"令不得记在未来"同口径）。
      long tick = GaPayloads.optionalLong(payload, "tick", worldTick);
      tickForLog = tick;
      if (tick > worldTick) {
        return rejected(
            "INFO 不得记在未来：载荷 tick " + tick + " > 世界 tick " + worldTick,
            "address",
            addressForLog,
            "key",
            keyForLog,
            "tick",
            tickForLog);
      }
      String mapKey = address.canonical();
      Map<String, List<DecisionInfoEntry>> next = new LinkedHashMap<>(base.info());
      List<DecisionInfoEntry> entries = new ArrayList<>(next.getOrDefault(mapKey, List.of()));
      DecisionInfoId id =
          explicitId.orElseGet(() -> DecisionInfoIds.synthesize(mapKey, entries.size()));
      infoIdForLog = id.value();
      if (containsInfoId(base.info(), id)) {
        // ★ 合成 id 按构造注入；只有**载荷显式给的** id 可能与既有条目撞车（连别人合成出来的也算）。
        //   无论哪种，命令期响亮拒绝（不留 revision）——不让"唯一 id"在数据模型里被静默破坏。
        return rejected(
            "INFO 条目 id 已存在: " + id.value(),
            "id",
            infoIdForLog,
            "address",
            addressForLog,
            "key",
            keyForLog);
      }
      DecisionInfoEntry entry =
          new DecisionInfoEntry(
              id,
              tick,
              tags,
              affiliations,
              key,
              value,
              note,
              at,
              sourceDirective,
              adjudicationStatus);
      entries.add(entry);
      next.put(mapKey, List.copyOf(entries));
      EventLog.channel(GaLog.decision())
          .info(
              LogEvent.of(
                  "SD_PUT_INFO_APPLIED",
                  GaLogSource.GA_DECISION,
                  "id",
                  id.value(),
                  "address",
                  mapKey,
                  "key",
                  key,
                  "tick",
                  tick,
                  "tags",
                  tags.size(),
                  "affiliations",
                  affiliations.size(),
                  "notePresent",
                  note.isPresent(),
                  "entries",
                  entries.size(),
                  "infoAddresses",
                  next.size()));
      return new HandlerOutcome.Applied(GaChangeSet.between(base, base.withInfo(next)));
    } catch (IllegalArgumentException e) {
      return rejected(
          e.getMessage(),
          "id",
          infoIdForLog,
          "address",
          addressForLog,
          "key",
          keyForLog,
          "tick",
          tickForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * GaPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    GaPayloads.logRejected(
        GaLog.decision(), GaLogSource.GA_DECISION, "SD_PUT_INFO_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  /**
   * {@code adjudicationStatus} 可选载荷（2026-09-23）：缺席 ⇒ 空（**普通 INFO 条目不参与裁决状态**，老档也走这条）； 给了 ⇒ 必须恰好是
   * {@code EFFECTIVE|VOIDED}（**不收自由字符串**——状态要能被读面与不变量断言，不接受拼写漂移）。
   */
  private static Optional<AdjudicationStatus> parseAdjudicationStatus(JsonNode payload) {
    Optional<String> text = GaPayloads.optionalText(payload, "adjudicationStatus");
    if (text.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(AdjudicationStatus.valueOf(text.get().trim()));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("adjudicationStatus 只允许 EFFECTIVE|VOIDED: " + text.get());
    }
  }

  /** {@code tags} 可选载荷：缺席/空数组 ⇒ 空集（无主）；元素必须是决策人 id。顺序按载荷给定（保序集合）。 */
  private static Set<DecisionMakerId> parseTags(JsonNode payload) {
    Set<DecisionMakerId> out = new LinkedHashSet<>();
    for (String tag : GaPayloads.optionalTextSet(payload, "tags")) {
      out.add(DecisionMakerId.parse(tag));
    }
    return out;
  }

  private static boolean containsInfoId(
      Map<String, List<DecisionInfoEntry>> info, DecisionInfoId id) {
    for (List<DecisionInfoEntry> entries : info.values()) {
      for (DecisionInfoEntry entry : entries) {
        if (entry.id().equals(id)) {
          return true;
        }
      }
    }
    return false;
  }
}
