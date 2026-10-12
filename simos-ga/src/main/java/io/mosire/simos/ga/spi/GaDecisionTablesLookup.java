package io.mosire.simos.ga.spi;

import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.spi.DecisionTablesLookup;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>{@link DecisionTablesLookup} 的唯一实现</b>（2026-10-12 批次 3b-2；前身 {@code GaDirectiveLookup} 只兑现
 * {@code directives} 一支）：sd 侧的读写口由本模块兑现——{@code ga → sd} 是<b>既有</b>方向（{@code GaState} 的组件值类型都住
 * sd），不新增模块边、零 pom 改动。
 *
 * <p>★ <b>只读三支无状态</b>（铁律 3）：每次调用现从 {@link SimulationState} 取 ga 切片（{@link GaSnapshots#state}）， 不
 * cache、不改写。ga 切片缺席 / 类型不对 ⇒ {@code GaSnapshots} 当场抛（装配故障，<b>不</b>折成"不存在"）。
 *
 * <p>★ <b>{@link #replaceInfo} 是唯一的写支</b>：它只<b>算</b>差异（{@link GaChangeSet#between}）并把变更集交给调用方，
 * <b>自己不改任何切片</b>——真正的落盘由 {@code TimeAdvance} 的提案解算与 codec 施加完成（铁律 2：不存在绕过 {@code Command →
 * ChangeSet → Revision} 的写入口；时间推进的多切片提案正是那条路径的一部分）。
 *
 * <p>★ <b>返回的是那一份表，不是副本</b>：调用方（{@code SdResolver}）只读；{@code SdTimeParticipant} 拿到 {@code info}
 * 后建的是自己的 {@code LinkedHashMap} 副本再改（与搬迁前逐字同形）。
 *
 * <p>★★ <b>2026-10-12 批次 3c：{@code gov.DeleteNation} 不再是本口的调用方</b>——那条命令随外交三表搬进 {@code simos-gov}，而
 * gov <b>既禁 sd 也禁 ga</b> ⇒ 它改问 gov 自己的窄口 {@code
 * io.mosire.simos.gov.spi.NationAffiliationLookup}（端口在 gov、实现在 app）。本口今天的调用方 = {@code SdResolver} 与
 * {@code SdTimeParticipant}。
 */
public final class GaDecisionTablesLookup implements DecisionTablesLookup {

  @Override
  public boolean containsDirective(SimulationState state, DirectiveId id) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    return GaSnapshots.state(state).directives().containsKey(id);
  }

  @Override
  public Map<DecisionMakerId, DecisionMaker> decisionMakers(SimulationState state) {
    Objects.requireNonNull(state, "state");
    return GaSnapshots.state(state).decisionMakers();
  }

  @Override
  public Map<String, List<SdInfoEntry>> info(SimulationState state) {
    Objects.requireNonNull(state, "state");
    return GaSnapshots.state(state).info();
  }

  @Override
  public ChangeSet replaceInfo(SimulationState state, Map<String, List<SdInfoEntry>> info) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(info, "info");
    GaState base = GaSnapshots.state(state);
    return GaChangeSet.between(base, base.withInfo(info));
  }
}
