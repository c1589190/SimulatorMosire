package io.mosire.simos.economy.api.inventory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.util.state.ChangeSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一次库存转移的纯规划结果：域级 mutation + 对应 {@link ChangeSet} + 读写集 + 回执。
 *
 * <p>计划不修改传入的 actor/map/economy 基础状态；由 app 在后续阶段把 {@link #moduleChanges()} 原子提交。
 *
 * @param transferId 转移身份
 * @param sourceLotId 源 lot
 * @param movedQuantityMilli 规划转移数量
 * @param mutations 域级动作描述
 * @param moduleChanges v1 只允许 {@value #ACTOR_MODULE_KEY} / {@value #MAP_MODULE_KEY} 两个键
 * @param reads canonical 读地址（保序）
 * @param writes canonical 写地址（保序）
 * @param receipt 回执
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "字段已在 compact 构造器/helper 冻结为 unmodifiable；项目同类 record 的既有豁免口径")
public record InventoryTransferPlan(
    InventoryTransferId transferId,
    InventoryLotId sourceLotId,
    long movedQuantityMilli,
    List<InventoryLotMutation> mutations,
    Map<String, ChangeSet> moduleChanges,
    Set<String> reads,
    Set<String> writes,
    InventoryTransferReceipt receipt) {

  public static final String ACTOR_MODULE_KEY = "actor";
  public static final String MAP_MODULE_KEY = "map";

  public InventoryTransferPlan {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(sourceLotId, "sourceLotId");
    Objects.requireNonNull(mutations, "mutations");
    Objects.requireNonNull(reads, "reads");
    Objects.requireNonNull(writes, "writes");
    Objects.requireNonNull(receipt, "receipt");
    if (movedQuantityMilli <= 0L) {
      throw new IllegalArgumentException(
          "InventoryTransferPlan.movedQuantityMilli 必须大于 0: " + movedQuantityMilli);
    }

    List<InventoryLotMutation> mutationCopy = new ArrayList<>(mutations.size());
    for (InventoryLotMutation mutation : mutations) {
      mutationCopy.add(Objects.requireNonNull(mutation, "mutations 不得含 null"));
    }
    mutations = Collections.unmodifiableList(mutationCopy);

    if (moduleChanges == null || moduleChanges.isEmpty()) {
      throw new IllegalArgumentException(InventoryTransferPort.REASON_EMPTY_MODULE_CHANGES);
    }
    Map<String, ChangeSet> changesCopy = new LinkedHashMap<>();
    for (Map.Entry<String, ChangeSet> entry : moduleChanges.entrySet()) {
      String key = entry.getKey();
      if (key == null || (!ACTOR_MODULE_KEY.equals(key) && !MAP_MODULE_KEY.equals(key))) {
        throw new IllegalArgumentException(
            "inventory-transfer-unsupported-module-change-key: " + key);
      }
      ChangeSet value = Objects.requireNonNull(entry.getValue(), "moduleChanges 不得含 null 值");
      changesCopy.put(key, value);
    }
    moduleChanges = Collections.unmodifiableMap(changesCopy); // 保序不可变

    reads = immutableOrderedSet(reads, "reads");
    writes = immutableOrderedSet(writes, "writes");

    if (!transferId.equals(receipt.transferId())
        || !sourceLotId.equals(receipt.sourceLotId())
        || movedQuantityMilli != receipt.movedQuantityMilli()) {
      throw new IllegalArgumentException(
          "inventory-transfer-receipt-mismatch: plan="
              + transferId
              + "/"
              + sourceLotId
              + "/"
              + movedQuantityMilli
              + " receipt="
              + receipt.transferId()
              + "/"
              + receipt.sourceLotId()
              + "/"
              + receipt.movedQuantityMilli());
    }
  }

  private static Set<String> immutableOrderedSet(Set<String> source, String name) {
    Objects.requireNonNull(source, name);
    Set<String> copy = new LinkedHashSet<>();
    for (String value : source) {
      copy.add(Objects.requireNonNull(value, name + " 不得含 null"));
    }
    return Collections.unmodifiableSet(copy);
  }
}
