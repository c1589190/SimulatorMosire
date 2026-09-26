package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.time.EconomyDayStepper;
import io.mosire.simos.economy.time.ProductionLedger;
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

/**
 * ★★ <b>经济 × 产权的协调器</b>（S1 阶段 4+5 Task 5）：<b>唯一同时看得见 {@code economy} 与 {@code actor} 的推进参与者</b> ——
 * 于是"产出离开 {@code ClassRow} 之后落到谁的账上"第一次真的发生。
 *
 * <p>★★ <b>它存在的理由（裁定 E7 / 计划 R4）</b>：产出自本阶段起<b>不再写进阶层行</b> —— 它变成产权条目 （{@code +净产 → operator}
 * 与关系规则的转出/收入）。产权条目由 <b>economy</b> 算（{@link ProductionLedger}）， 而账本住在 <b>{@code actor}</b>
 * 切片；两个切片<b>互不认识</b>（铁律 3），Core 也看不见任何领域类型（铁律 4） ⇒ 会合点只能在<b>组合根</b>。★
 * 少了它，产出<b>在账上静默消失</b>（行里那份还在，operator 那份没了）。
 *
 * <p>★★ <b>它服务"有 economy、没有 social"的世界</b>（既有夹具正是这一形态）：
 *
 * <ul>
 *   <li>{@code PopulationEconomyTimeParticipant}（有 social 的真档）—— <b>加第三片 {@code
 *       actor}</b>，两者<b>从不同时注册</b> （同时注册 ⇒ Core 的写-写检查当场拒，响亮）；
 *   <li>本类（只有 economy + actor）—— 与上面那个<b>共用</b>同一个 {@link OwnershipBooks}。
 * </ul>
 *
 * <p>★ <b>一天的次序</b>（与人口那个协调器同款）：
 *
 * <pre>
 * 经济结算一天（{@code EconomyDayStepper.step(day)}）⇒ 它交回当天的 ProductionLedger
 * 产权落账（{@link OwnershipBooks#apply}）⇒ actor 账本 += 当天的条目
 * </pre>
 *
 * <p>★ <b>逐日而不是一次算完</b>：{@code ProductionLedger} 是<b>一天一本</b>的（见它的类注）⇒ 逐日落账才不重不漏； §十一 的"一次 N 天 == N
 * 次单日"因此也落在同一个循环里。
 *
 * <p>★ <b>读写集是 canonical 地址</b>（{@link Address} AST 构造）：{@code economy:<mapId>:...} 照 {@code
 * PopulationEconomyTimeParticipant} 的形制；{@code actor:<mapId>:goods.<key>} 照 {@code ActorResolver}
 * 的形制（★ 本切片只写"库存"这一类 actor 数据 —— 主体/产权本阶段都不动）。
 *
 * <p>★ <b>两个边界</b>（照既有参与者）：
 *
 * <ul>
 *   <li>{@code range.to} 缺省（无上界推进）⇒ <b>零变更提案、不抛</b>（该推进随后必被 Core 的 Validate 拒绝）；
 *   <li>状态里没有 economy / actor 切片 ⇒ <b>装配故障当场炸</b>（静默兜底会把装配错误伪装成"这一天无事"）。
 * </ul>
 *
 * <p>★ <b>未激活</b>（{@code meta} 空）：交一份"两边都不变"的提案（经济还没播种，这一天没有公式要跑）。
 */
public final class EconomyOwnershipTimeParticipant implements TimeParticipant {

  /** 参与者身份（**不是模块名**：它同时写 {@code economy} 与 {@code actor} 两个模块）。 */
  public static final String NAMESPACE = "ownership";

  private static final String ECONOMY = "economy";
  private static final String ACTOR = "actor";

  private final String mapId;

  public EconomyOwnershipTimeParticipant(String mapId) {
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
    EconomyData economy = economyOf(state);
    ActorData actor = actorOf(state);

    LinkedHashSet<String> reads = new LinkedHashSet<>();
    LinkedHashSet<String> writes = new LinkedHashSet<>();
    reads.add(economyAddressRoot());
    writes.add(economyAddressRoot());
    for (IndustryId id : economy.industries().keySet()) {
      reads.add(economyAddress("industry", id.value()));
      writes.add(economyAddress("industry", id.value()));
    }
    // ★★ H0.2：class/flow 的地址局部名 = {@link CohortKey#toString()} 的**规范串**（{@code 0_0|rural|poor_peasant}）。
    //   行键里已经没有产业，旧版内联拼的 {@code <industryId>.<slotId>} 是同一格式的第二处拼写点（已删）。
    //   ★ 必须与 {@code EconomyResolver} 的 class/flow 地址逐字同串。
    for (CohortKey key : economy.classes().keySet()) {
      reads.add(economyAddress("class", key.toString()));
      writes.add(economyAddress("class", key.toString()));
    }
    for (CohortKey key : economy.flows().keySet()) {
      writes.add(economyAddress("flow", key.toString()));
    }
    reads.add(actorAddressRoot());
    writes.add(actorAddressRoot());
    for (GoodsAccountKey key : actor.accounts().keySet()) {
      reads.add(accountAddress(key));
      writes.add(accountAddress(key));
    }

    Optional<SimosTimestamp> to = range.to();
    if (to.isEmpty() || economy.meta().isEmpty()) {
      // 无上界推进 / 经济未激活 ⇒ 两边都不动（交的是**不变变更集**，不是空提案：契约原文）。
      return new WorldTimeProposal(
          NAMESPACE,
          Map.of(
              ECONOMY, EconomyChangeSet.between(economy, economy),
              ACTOR, ActorChangeSet.between(actor, actor)),
          reads,
          writes);
    }

    EconomyDayStepper stepper = new EconomyDayStepper(economy);
    ActorData books = actor;
    for (long day = range.from().tick() + 1L; day <= to.get().tick(); day++) {
      ProductionLedger ledger = stepper.step(day);
      // ★★ 落账：当天的条目只落一次（ledger 是**一天一本**的）；落出来的新账户也要进写集。
      if (ledger.actorEntries().isEmpty()) {
        continue;
      }
      books = OwnershipBooks.apply(books, ledger.actorEntries());
      for (GoodsAccountKey key : books.accounts().keySet()) {
        writes.add(accountAddress(key));
      }
    }
    EconomyData currentEconomy = stepper.finish();
    return new WorldTimeProposal(
        NAMESPACE,
        Map.of(
            ECONOMY, EconomyChangeSet.between(economy, currentEconomy),
            ACTOR, ActorChangeSet.between(actor, books)),
        reads,
        writes);
  }

  // ── 切片读取与地址 ────────────────────────────────────────────────────────────────────

  private static EconomyData economyOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ECONOMY)
            .orElseThrow(() -> new IllegalStateException("state 里没有 economy 切片（装配故障：日推进要求切片在场）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  private static ActorData actorOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ACTOR)
            .orElseThrow(() -> new IllegalStateException("state 里没有 actor 切片（装配故障：产权落账口要求切片在场）"));
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalStateException(
          "state 的 actor 切片不是 ActorSnapshot: " + snapshot.getClass().getName());
    }
    return actorSnapshot.data();
  }

  private String economyAddressRoot() {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId))).canonical();
  }

  private String actorAddressRoot() {
    return new Address(List.of(new Namespace(ACTOR), Entity.of(mapId))).canonical();
  }

  private String economyAddress(String kind, String localId) {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }

  /**
   * 一本产权账的地址：{@code actor:<mapId>:goods.<key>} —— ★ 形制照 {@code ActorResolver}（它的第三段 kind 就是 {@code
   * goods}）。本类**只委托、不复述格式**：{@code <key>} 是 {@link GoodsAccountKey#toString()} 的产物。
   */
  private String accountAddress(GoodsAccountKey key) {
    return new Address(
            List.of(new Namespace(ACTOR), Entity.of(mapId), Entity.of("goods", key.toString())))
        .canonical();
  }
}
