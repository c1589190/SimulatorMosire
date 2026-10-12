package io.mosire.simos.ga.spi;

import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.spi.DirectiveLookup;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ★★ <b>{@link DirectiveLookup} 的唯一实现</b>（2026-10-12 批次 3b′）：sd 侧的读口由本模块兑现——{@code ga → sd} 是
 * <b>既有</b>方向（{@code GaState} 的四个组件值类型都住 sd），不新增模块边。
 *
 * <p>★ <b>只读、无状态</b>（铁律 3）：每次调用现从 {@link SimulationState} 取 ga 切片（{@link GaSnapshots#state}）， 不
 * cache、不改写。ga 切片缺席 / 类型不对 ⇒ {@code GaSnapshots} 当场抛（装配故障，<b>不</b>折成"不存在"）。
 *
 * <p>★ <b>{@code referencing} 按 id 自然序</b>（{@code DirectiveId.value()} 的字典序），与 sd 侧原来的排序口径逐字相同
 * （{@code DeleteDecisionMakerHandler} 原实现用 {@code ids.sort(String::compareTo)}），故拒因文本与条数不变。
 */
public final class GaDirectiveLookup implements DirectiveLookup {

  @Override
  public boolean contains(SimulationState state, DirectiveId id) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    return GaSnapshots.state(state).directives().containsKey(id);
  }

  @Override
  public List<DirectiveId> referencing(SimulationState state, DecisionMakerId id) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    List<DirectiveId> ids = new ArrayList<>();
    for (Directive directive : GaSnapshots.state(state).directives().values()) {
      if (directive.decisionMakerId().equals(id)) {
        ids.add(directive.id());
      }
    }
    ids.sort((left, right) -> left.value().compareTo(right.value()));
    return List.copyOf(ids);
  }
}
