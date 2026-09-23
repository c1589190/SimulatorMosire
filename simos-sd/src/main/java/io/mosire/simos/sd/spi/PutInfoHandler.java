package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.SdInfoId;
import io.mosire.simos.sd.model.AdjudicationStatus;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.SdInfoIds;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.spi.CommandHandler;
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
 * {@code sd.PutInfo} 命令的处理器（spec §六，R14）：sd 自有 INFO 覆盖层写路径。
 *
 * <pre>{@code
 * {"address":"map:Map1:region.r1","key":"brief","value":"……","note":"可选",
 *  "id":"可选：显式 id（同类型内唯一）","tags":["可选：决策人 id…"],"tick":"可选：该条目所属 tick",
 *  "affiliations":[{"kind":"nation","id":"shanghai"}]}
 * }</pre>
 *
 * <p>★ **不复用全局 {@code InfoSystem}**（spec §六）：写进 {@code SdChangeSet.info} → 进 revision，可重放、受铁律 5
 * 往返守卫。 **写的是感知层**（供 UI / AAR 展示），ground truth 仍由领域模块持有。
 *
 * <p>★★ **决策结果三件套（第 3 波第 1 步）**：本命令写的就是**决策结果本身**（INFO 条目），故三条新字段在这里落地—— {@code id}/{@code
 * tick}/{@code tags}。{@code id} 与 {@code tags} 是**可选载荷**（向后兼容：旧调用不带这两个键照常成立）：
 *
 * <ul>
 *   <li>{@code id} 缺席 ⇒ {@link SdInfoIds#synthesize}（canonical 地址 + 该地址下追加序号，注入）；给了 ⇒ 用它，并在**命令期**
 *       **重复即拒**（见下）；
 *   <li>{@code tags} 缺席 ⇒ 空集（无主）；给了 ⇒ 逐字解析成 {@link DecisionMakerId}（**不**在此校验决策人是否存在——
 *       那是后续步骤的语义，本命令只认形状）；
 *   <li>{@code affiliations} 缺席 ⇒ 空集（不按归属发）；给了 ⇒ 逐项解析成 {@link Affiliation}（形状同 {@code
 *       sd.CreateDecisionMaker} 的 {@code affiliation}：{@code {"kind":"nation"|"army","id":"…"}}）。
 *       它与 {@code tags} 是**并集**关系，判定不在本层（见 {@code RedactingQueryService#docs}）；
 *   <li>{@code tick} 缺席 ⇒ **世界当前 tick**（第 3 波第 1 步的既有行为，向后兼容）；给了 ⇒ 用它，但 **不得记在未来** （{@code > 世界
 *       tick} ⇒ 拒，与 {@code IssueDirectiveHandler} 同口径）。**过去合法**（补记/滞后）。
 * </ul>
 *
 * <p>★ 诚实边界（spec §六 + M4 裁定 38 同口径）：{@code SdInfoEntry.value} 是**裸 {@code Object}**，结构化值的 {@code
 * equals} 往返不满足 ⇒ 判据只覆盖标量值；结构化值列为挂起项。
 *
 * <p>拒绝：地址非法（{@link Address#parse} 抛）；{@code key} 空白；{@code value} 缺失；{@code id} 空白或与既有条目**撞 id**；
 * {@code tags}/{@code affiliations} 元素非法；{@code tick} 记在未来。
 */
public final class PutInfoHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.PutInfo";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      Address address = SdPayloads.requireAddress(payload, "address");
      String key = SdPayloads.requireText(payload, "key");
      Object value = SdPayloads.requireValue(payload, "value");
      Optional<String> note = SdPayloads.optionalText(payload, "note");
      Set<DecisionMakerId> tags = parseTags(payload);
      Set<Affiliation> affiliations = SdPayloads.optionalAffiliationSet(payload, "affiliations");
      Optional<AdjudicationStatus> adjudicationStatus = parseAdjudicationStatus(payload);
      Optional<SdInfoId> explicitId = SdPayloads.optionalText(payload, "id").map(SdInfoId::parse);
      RevisionId at = state.meta().ref().revision();
      long worldTick = state.meta().timestamp().tick();
      // ★ 可选 tick（第 3 波第 2 步）：缺省 = 世界当前 tick（**向后兼容**，旧调用一字不变）；显式给 = 用它。
      //   ★ 为什么允许**过去**的 tick：`IssueDirectiveHandler` 明确允许"补记/滞后"的令（只拒未来）⇒ 若这里
      //   只收当前 tick，那些令的**决策结果**将永远归不到正确的 tick 上（制造孤儿）。语义是：命令的**效果**仍落在
      //   当下（世界在 current tick），只是**条目带该 tick**（归属正确）。★ 未来 tick 照旧拒（与"令不得记在未来"同口径）。
      long tick = SdPayloads.optionalLong(payload, "tick", worldTick);
      if (tick > worldTick) {
        return new HandlerOutcome.Rejected(
            "INFO 不得记在未来：载荷 tick " + tick + " > 世界 tick " + worldTick);
      }
      String mapKey = address.canonical();
      Map<String, List<SdInfoEntry>> next = new LinkedHashMap<>(base.info());
      List<SdInfoEntry> entries = new ArrayList<>(next.getOrDefault(mapKey, List.of()));
      SdInfoId id = explicitId.orElseGet(() -> SdInfoIds.synthesize(mapKey, entries.size()));
      if (containsInfoId(base.info(), id)) {
        // ★ 合成 id 按构造注入；只有**载荷显式给的** id 可能与既有条目撞车（连别人合成出来的也算）。
        //   无论哪种，命令期响亮拒绝（不留 revision）——不让"唯一 id"在数据模型里被静默破坏。
        return new HandlerOutcome.Rejected("INFO 条目 id 已存在: " + id.value());
      }
      SdInfoEntry entry =
          new SdInfoEntry(
              id, tick, tags, affiliations, key, value, note, at, Optional.empty(), adjudicationStatus);
      entries.add(entry);
      next.put(mapKey, List.copyOf(entries));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withInfo(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /**
   * {@code adjudicationStatus} 可选载荷（2026-09-23）：缺席 ⇒ 空（**普通 INFO 条目不参与裁决状态**，老档也走这条）；
   * 给了 ⇒ 必须恰好是 {@code EFFECTIVE|VOIDED}（**不收自由字符串**——状态要能被读面与不变量断言，不接受拼写漂移）。
   */
  private static Optional<AdjudicationStatus> parseAdjudicationStatus(JsonNode payload) {
    Optional<String> text = SdPayloads.optionalText(payload, "adjudicationStatus");
    if (text.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(AdjudicationStatus.valueOf(text.get().trim()));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "adjudicationStatus 只允许 EFFECTIVE|VOIDED: " + text.get());
    }
  }

  /** {@code tags} 可选载荷：缺席/空数组 ⇒ 空集（无主）；元素必须是决策人 id。顺序按载荷给定（保序集合）。 */  private static Set<DecisionMakerId> parseTags(JsonNode payload) {
    Set<DecisionMakerId> out = new LinkedHashSet<>();
    for (String tag : SdPayloads.optionalTextSet(payload, "tags")) {
      out.add(DecisionMakerId.parse(tag));
    }
    return out;
  }

  private static boolean containsInfoId(Map<String, List<SdInfoEntry>> info, SdInfoId id) {
    for (List<SdInfoEntry> entries : info.values()) {
      for (SdInfoEntry entry : entries) {
        if (entry.id().equals(id)) {
          return true;
        }
      }
    }
    return false;
  }
}
