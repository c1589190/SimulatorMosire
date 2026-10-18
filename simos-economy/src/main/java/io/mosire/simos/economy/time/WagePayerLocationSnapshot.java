package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.production.contract.ProductionLocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ★★★ <b>W1/W2b/W3 共享的付款人地点快照</b>（D-032 D3 §6.9.1）。
 *
 * <p>快照必须覆盖"payer 当前 revision 全部已有 unit/roster 地点 ∪ 本次候选/操作地点"。调用方负责从 economy 当前生产状态
 * 汇总；本类型只做纯值聚合：去重、稳定排序、构造期冻结。
 *
 * <p>★ <b>不读账户</b>：本类型与 {@link WagePayerLocationPrecheck} 都不访问 {@code
 * AccountSession}/库存/市场，只回答地点集合的基数。
 *
 * @param payer 付款人 actor；不得为 null
 * @param existingSettlementLocations 当前 revision 已有全部地点；不得为 null
 * @param candidateSettlementLocations 本次候选/操作地点；不得为 null
 */
public record WagePayerLocationSnapshot(
    ActorRef payer,
    Set<ProductionLocation> existingSettlementLocations,
    Set<ProductionLocation> candidateSettlementLocations) {

  public WagePayerLocationSnapshot {
    Objects.requireNonNull(payer, "WagePayerLocationSnapshot.payer 不得为 null");
    existingSettlementLocations =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(
                freeze(existingSettlementLocations, "existingSettlementLocations")));
    candidateSettlementLocations =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(
                freeze(candidateSettlementLocations, "candidateSettlementLocations")));
  }

  /**
   * {@code existing ∪ candidate} 的去重稳定并集（按 {@link ProductionLocation#value()} 升序、不可变）。
   *
   * <p>★ 排序是内容的纯函数：不看 {@link Set} 的插入史，保证 W1/W2b/W3 的拒绝/读数确定。
   */
  public Set<ProductionLocation> allSettlementLocations() {
    LinkedHashSet<ProductionLocation> union = new LinkedHashSet<>(existingSettlementLocations);
    union.addAll(candidateSettlementLocations);
    List<ProductionLocation> sorted = new ArrayList<>(union);
    sorted.sort(Comparator.comparing(ProductionLocation::value));
    return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
  }

  private static Set<ProductionLocation> freeze(Set<ProductionLocation> locations, String name) {
    Objects.requireNonNull(locations, name + " 不得为 null");
    LinkedHashSet<ProductionLocation> copy = new LinkedHashSet<>();
    for (ProductionLocation location : locations) {
      if (location == null) {
        throw new IllegalArgumentException(name + " 不得含 null");
      }
      copy.add(location);
    }
    List<ProductionLocation> sorted = new ArrayList<>(copy);
    sorted.sort(Comparator.comparing(ProductionLocation::value));
    return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
  }
}
