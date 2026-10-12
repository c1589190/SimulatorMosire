package io.mosire.simos.ga.tools;

import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>ga 工具面的视图口</b>（2026-10-12 B3 第 7 域）：sd / 决策面的只读视图形状（{@code ApiViews} 的那几段）。
 *
 * <p>★ <b>为什么是端口而不是把视图搬进来</b>：视图函数住在 app 的 {@code ApiViews}（它与 GUI 端点**共用同一份**：形状只许有 一处），而 {@code
 * ApiViews} 会用 {@code map}/{@code unit}/{@code gov}/{@code economy} 等**本模块看不见**的切片 ⇒ 签名里会出现领域类型 ⇒ 按
 * I-G2 只能住 app。<b>接口在 ga、实现在 app</b>，实现逐条转调既有唯一实现。
 *
 * <p>★ <b>形状零改动</b>：实现必须**逐条转调** {@code ApiViews.*}（不重拼 payload），调用方只做 {@code put("…", …)} 这一层装配 ⇒
 * 字段名/顺序/取值与迁入前逐字相同。
 *
 * <p>★ <b>入参用 util 类型 + 整个 {@link SimulationState}</b>：需要其它模块切片的视图一律收 state 整体，由实现自己去取它要的切片 ——
 * 这样接口不必认识那些模块。
 *
 * <p>★★ <b>2026-10-12 批次 3d-2：删掉 {@code combats(SimulationState)}（如实记）</b>——它的唯一调用者是 {@code
 * SdCombatsTool}（{@code simos.sd.combats}），而它的数据源（sd 的 {@code combats}/{@code combatStates}）随旧战斗族
 * <b>整族退役</b>；交战记录的读面在 army 自己的模块里（{@code simos-ga} 不依赖 army ⇒ 这条视图也不该留在本接口）。 ★ 同批删掉的还有 {@code
 * AppGaToolViews} 的对应实现与 {@code ApiViews.combats}。
 *
 * <p>★★ <b>2026-10-12 批次 3c：两条外交视图的入参由"领域类型"改为"整个 state + 不透明过滤串"</b>——外交三表与 {@code NationId} 搬进
 * {@code simos-gov}，而 {@code simos-ga} <b>不得依赖 {@code gov}</b>（enforcer 禁列）⇒ 接口里不能再出现 {@code
 * SdState}/{@code NationId}/{@code DiplomaticEvent} 这些类型。★ 于是：
 *
 * <ul>
 *   <li>{@code diplomaticRelations} 收 {@code state} + {@code nation} <b>不透明串</b>（{@code null} =
 *       不过滤）——解析成 {@code NationId} 与读 gov 切片都在实现（app）里；
 *   <li>{@code diplomaticEvents} 同样收 {@code state} + 过滤参数（{@code tick}/{@code
 *       participant}）——<b>筛序与排序搬进 实现</b>（那里才是"形状只有一处"的地方），ga 侧只做参数形状校验。
 * </ul>
 *
 * ★ 对外返回的视图字节与迁入前<b>逐字相同</b>（实现仍是同一个 {@code ApiViews} 函数）。
 */
public interface GaToolViews {

  /**
   * 外交关系边（{@code ApiViews.diplomaticRelations}）：读 gov 切片的关系边表，按 {@code nation}（不透明国家引用；{@code null}
   * = 不过滤，非空白串由实现解析）过滤。
   */
  List<Map<String, Object>> diplomaticRelations(SimulationState state, String nation);

  /**
   * 外交事件（{@code ApiViews.diplomaticEvents}）：读 gov 切片的事件表，按 {@code tick}（{@code null} = 不限）与 {@code
   * participant}（不透明国家引用；{@code null} = 不限）过滤；**有序输出**（按事件 id）。
   */
  List<Map<String, Object>> diplomaticEvents(SimulationState state, Long tick, String participant);

  /** GM 工具使用记录（{@code ApiViews.gmToolUsage}；入参是"最新在前"的有界快照）。 */
  List<Map<String, Object>> gmToolUsage(List<GmToolUsage.Entry> entries);
}
