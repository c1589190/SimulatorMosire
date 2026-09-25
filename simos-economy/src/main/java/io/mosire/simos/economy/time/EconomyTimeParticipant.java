package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ **economy 侧的时间推进参与者**（R3a/R4a，聚合式经济重设计 §四）：每次 {@code AdvanceTime} 按 {@code range} **从 {@code
 * from + 1} 逐日结算到 {@code to}**（消费 / 缺口借粮 / 进度 / 劳动投入；周期末追加产出 / 生产消耗 / 制度分配），最终只产出一份 {@code
 * EconomyChangeSet}（§十一：一次推进 N 天、只落一条 revision）。
 *
 * <p>★ **纯函数**：拿 {@link SimulationState} 交提案，**不写状态**（{@link EconomySettlement#settle}
 * 是纯的）。每个参与者拿到的都是同一份 base（C25）⇒ 调用顺序不影响结果。
 *
 * <p>★ **多切片提案形态**：本参与者只改 {@code economy} 一个模块，但按 2026-09-24 的通用契约实现 {@link #simulateWorld}、交
 * {@link WorldTimeProposal}（模块键 = {@code "economy"}）——与单切片参与者对 Core **完全同形**。{@link #namespace()}
 * 是参与者身份（{@code "economy"}）。
 *
 * <p>★★ **读写集是 canonical 地址**（{@link Address} AST 构造）：
 *
 * <ul>
 *   <li>{@code economy:<mapId>:industry.<industryId>}
 *   <li>{@code economy:<mapId>:class.<industryId>.<slotId>}（点分写法，与 {@code EconomyResolver} 一致）
 *   <li>{@code economy:<mapId>:flow.<industryId>.<slotId>}、{@code
 *       economy:<mapId>:debt.<debtId>}（写集）
 * </ul>
 *
 * <p>★ **能力边界**：只写 {@code economy}；不碰 social/market/government（那些是各自切片的事，跨切片编排在后续增量）。
 *
 * <p>★ **两个边界**（照 {@code UnitTimeParticipant}/{@code SdTimeParticipant}）：
 *
 * <ul>
 *   <li>{@code range.to} 缺省（无上界推进）⇒ **零变更提案、不抛**（该推进随后必被 Core 的 Validate 拒绝）。
 *   <li>状态里没有 economy 切片 ⇒ **装配故障当场炸**（§6.6：日推进要求切片在场，静默兜底会把装配错误伪装成"这一天无事"）。
 * </ul>
 *
 * <p>★ **未激活**（{@code meta} 空）：交一份"不变变更集"的提案（经济还没播种，这一天没有公式要跑）。
 */
public final class EconomyTimeParticipant implements TimeParticipant {

  private static final String NAMESPACE = "economy";

  private final String mapId;

  public EconomyTimeParticipant(String mapId) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public WorldTimeProposal simulateWorld(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    EconomyData base = dataOf(state);

    Set<String> reads = new LinkedHashSet<>();
    Set<String> writes = new LinkedHashSet<>();
    reads.add(rootAddress());
    writes.add(rootAddress());
    for (IndustryId id : base.industries().keySet()) {
      reads.add(industryAddress(id));
      writes.add(industryAddress(id));
    }
    for (ClassKey key : base.classes().keySet()) {
      reads.add(classAddress(key));
      writes.add(classAddress(key));
    }

    Optional<SimosTimestamp> to = range.to();
    EconomyData target;
    if (to.isEmpty()) {
      target = base; // 无上界推进：没有可结算的日，交不变提案
    } else {
      // ★ §十一：一次推进 N 天，结算**在参与者内部逐日**（from+1 .. to），最终只产出一份变更集。
      target = EconomySettlement.settle(base, range.from().tick(), to.get().tick());
    }

    for (ClassKey key : target.flows().keySet()) {
      writes.add(flowAddress(key));
    }
    for (DebtId id : target.debts().keySet()) {
      writes.add(debtAddress(id));
    }
    return new WorldTimeProposal(
        NAMESPACE, Map.of(NAMESPACE, EconomyChangeSet.between(base, target)), reads, writes);
  }

  private static EconomyData dataOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(NAMESPACE)
            .orElseThrow(
                () -> new IllegalStateException("state 里没有 economy 切片（装配故障，§6.6：日推进要求切片在场）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  // ── canonical 地址（一律由 Address AST 构造后调 canonical()：加引规则不在这里重实现）──────────

  private String rootAddress() {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of(mapId))).canonical();
  }

  private String industryAddress(IndustryId id) {
    return entityAddress("industry", id.value());
  }

  private String classAddress(ClassKey key) {
    return entityAddress("class", key.industry().value() + "." + key.slot().value());
  }

  private String flowAddress(ClassKey key) {
    return entityAddress("flow", key.industry().value() + "." + key.slot().value());
  }

  private String debtAddress(DebtId id) {
    return entityAddress("debt", id.value());
  }

  private String entityAddress(String kind, String localId) {
    return new Address(
            List.of(new Namespace(NAMESPACE), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }
}
