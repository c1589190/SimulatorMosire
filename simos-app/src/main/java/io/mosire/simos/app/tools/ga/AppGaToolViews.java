package io.mosire.simos.app.tools.ga;

import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.ga.tools.GaToolViews;
import io.mosire.simos.ga.tools.GmToolUsage;
import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>{@link GaToolViews} 的 app 实现</b>（2026-10-12 B3 第七域）：**逐条转调既有的唯一视图实现** {@link ApiViews} ——
 * 一条都不重拼、不改字段名、不改顺序。
 *
 * <p>★ <b>为什么视图不跟着工具进 ga</b>：这些视图函数与 GUI 端点**共用同一份**（形状只许有一处），而 {@code ApiViews} 会用 {@code
 * map}/{@code unit} 等 ga 看不见的切片（{@code combats} 同时读 sd 与 unit）⇒ 只能住 app。
 */
public final class AppGaToolViews implements GaToolViews {

  @Override
  public List<Map<String, Object>> combats(SimulationState state) {
    return ApiViews.combats(
        ApiViews.sdState(state), ApiViews.unitState(state), state.meta().timestamp());
  }

  @Override
  public List<Map<String, Object>> diplomaticRelations(SdState sd, NationId nation) {
    return ApiViews.diplomaticRelations(sd.diplomaticRelations(), nation);
  }

  @Override
  public List<Map<String, Object>> diplomaticEvents(
      Map<DiplomaticEventId, DiplomaticEvent> events) {
    return ApiViews.diplomaticEvents(events);
  }

  @Override
  public List<Map<String, Object>> gmToolUsage(List<GmToolUsage.Entry> entries) {
    return ApiViews.gmToolUsage(entries);
  }
}
