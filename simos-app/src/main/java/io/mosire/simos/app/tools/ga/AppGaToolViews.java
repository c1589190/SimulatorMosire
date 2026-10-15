package io.mosire.simos.app.tools.ga;

import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.ga.tools.GaToolViews;
import io.mosire.simos.ga.tools.GmToolUsage;
import io.mosire.simos.gov.nation.DiplomaticEvent;
import io.mosire.simos.gov.nation.DiplomaticEventId;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>{@link GaToolViews} 的 app 实现</b>（2026-10-12 B3 第七域）：**逐条转调既有的唯一视图实现** {@link ApiViews} ——
 * 一条都不重拼、不改字段名、不改顺序。
 *
 * <p>★ <b>为什么视图不跟着工具进 ga</b>：这些视图函数与 GUI 端点**共用同一份**（形状只许有一处），而 {@code ApiViews} 会用 {@code
 * map}/{@code unit}/{@code gov} 等 ga 看不见的切片（{@code diplomaticEvents} 就读 gov 的事件表）⇒ 只能住 app。
 *
 * <p>★★ <b>2026-10-12 批次 3c：两条外交视图改"自己取 gov 切片 + 自己筛"</b>——外交三表与 {@code NationId} 搬进 {@code
 * simos-gov}，ga 看不见它们（enforcer 禁列）⇒ 接口只剩 {@code state + 不透明过滤串}，取表/筛序/排序都落在这里（app 同时看得见 gov 与
 * util）。★ <b>输出字节与迁入前逐字相同</b>：仍转调同一个 {@link ApiViews} 函数，筛法逐条照抄原 {@code DiplomaticEventsTool}
 * 的循环（tick 命中 + participants 含该国家 + 保事件表插入序），而 {@code ApiViews.diplomaticEvents} 自己按 id 排序。
 */
public final class AppGaToolViews implements GaToolViews {

  @Override
  public List<Map<String, Object>> diplomaticRelations(SimulationState state, String nation) {
    // ★ nation 为 null = 不过滤；非 null 时按"逐字解释成 NationId"校验形状（与迁移前的 NationId.parse 同一条拒因文案）。
    NationId filter = nation == null ? null : NationId.parse(nation);
    return ApiViews.diplomaticRelations(ApiViews.govState(state).diplomaticRelations(), filter);
  }

  @Override
  public List<Map<String, Object>> diplomaticEvents(
      SimulationState state, Long tick, String participant) {
    NationId filter = participant == null ? null : NationId.parse(participant);
    Map<DiplomaticEventId, DiplomaticEvent> selected = new LinkedHashMap<>();
    for (Map.Entry<DiplomaticEventId, DiplomaticEvent> entry :
        ApiViews.govState(state).diplomaticEvents().entrySet()) {
      DiplomaticEvent event = entry.getValue();
      if (tick != null && event.tick() != tick) {
        continue;
      }
      if (filter != null && !event.participants().contains(filter)) {
        continue;
      }
      selected.put(entry.getKey(), event);
    }
    return ApiViews.diplomaticEvents(selected);
  }

  @Override
  public List<Map<String, Object>> gmToolUsage(List<GmToolUsage.Entry> entries) {
    return ApiViews.gmToolUsage(entries);
  }
}
