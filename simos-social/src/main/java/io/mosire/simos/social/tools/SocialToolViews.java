package io.mosire.simos.social.tools;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;

/**
 * social 工具的**视图口**：逐格人口读数——它的**唯一实现**是宿主的 {@code ApiViews.population} （GUI 的 {@code GET
 * /api/social/population} 与工具面**同一个函数**），故由宿主**构造期注入**。
 *
 * <p>★★ <b>为什么必须注入、不能在域内抄一份</b>：该视图同时读 social（批次现算读数 / 序列）与 economy（T4 的劳动块）两片， 还要世界历法（{@code
 * CalendarService}：{@code at} 的日期与 tick→历法年换算同源）——历法服务的装配在宿主；更要紧的是 AGENT.md §8.3 的"视图只有一份"：抄一份 =
 * 同一资源的两个形状（与 B3 试点 {@code MapToolViews} / 第二域 {@code ArmyToolViews} / 第三域 {@code EconomyToolViews}
 * 同一条处置）。
 *
 * <p>★ <b>签名里只有 map/util 类型</b>（{@code HexCoord} / {@code SimulationState} / {@code Map}）⇒
 * 依赖倒置到域自己的包， 不进 {@code ga-spi}（契约层不许出现域专属形状）。
 */
public interface SocialToolViews {

  /** 逐格人口读数（宿主转调 {@code ApiViews.population}，逐字节同形）。 */
  Map<String, Object> population(HexCoord coord, SimulationState state);
}
