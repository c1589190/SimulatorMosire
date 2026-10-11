package io.mosire.simos.ga.tools;

import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>ga 工具面的视图口</b>（2026-10-12 B3 第 7 域）：sd / 决策面的只读视图形状（{@code ApiViews} 的那几段）。
 *
 * <p>★ <b>为什么是端口而不是把视图搬进来</b>：视图函数住在 app 的 {@code ApiViews}（它与 GUI 端点**共用同一份**：形状只许有 一处），而 {@code
 * ApiViews} 会用 {@code map}/{@code unit}/{@code economy} 等**本模块看不见**的切片（{@code combats} 就同时读 sd 与
 * unit）⇒ 签名里出现领域类型 ⇒ 按 I-G2 只能住 app。<b>接口在 ga、实现在 app</b>，实现逐条转调既有唯一实现。
 *
 * <p>★ <b>形状零改动</b>：实现必须**逐条转调** {@code ApiViews.*}（不重拼 payload），调用方只做 {@code put("combats", …)}
 * 这一层装配 ⇒ 字段名/顺序/取值与迁入前逐字相同。
 *
 * <p>★ <b>入参用 util 类型 + 本模块看得见的 sd 类型</b>：需要 unit 快照的视图（如 {@code combats}）收 {@link SimulationState}
 * 整体，由实现自己去取它要的切片 —— 这样接口不必认识 unit。
 */
public interface GaToolViews {

  /** 交战记录清单（{@code ApiViews.combats}：同时读 sd 与 unit 两片，故收整个 state）。 */
  List<Map<String, Object>> combats(SimulationState state);

  /** 外交关系边（{@code ApiViews.diplomaticRelations}）；{@code nation} 为 null = 不过滤。 */
  List<Map<String, Object>> diplomaticRelations(SdState sd, NationId nation);

  /** 外交事件（{@code ApiViews.diplomaticEvents}）；入参已是筛过的有序表（顺序原样保留）。 */
  List<Map<String, Object>> diplomaticEvents(Map<DiplomaticEventId, DiplomaticEvent> events);

  /** GM 工具使用记录（{@code ApiViews.gmToolUsage}；入参是"最新在前"的有界快照）。 */
  List<Map<String, Object>> gmToolUsage(List<GmToolUsage.Entry> entries);
}
