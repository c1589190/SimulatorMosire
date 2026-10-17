package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★★ <b>批量驱动口：一个生产日的结果</b>（D-032 约束设计书 §13.2 第 1 条④；复核裁决 §2.3）。
 *
 * <p>★★ <b>两类事实必须分列</b>（这是失败语义的落形）：
 *
 * <ul>
 *   <li><b>业务拒绝</b>（{@link #rejections()}）：逐项具名、INFO，世界照常推进；被拒的那一步
 *       <b>零残留</b>——没有新实例、没有进度、没有经验、没有原料消耗、没有效率行；它释放出来的资源 如何重分配仍归 economy（复核裁决 §2.3 第 1 条）。
 *   <li><b>契约故障 / 公式错误 / 重复推进 / 跨域不一致</b>：<b>不在这里</b>——它们由 {@link ProductionContractFault} 冒出
 *       {@code executeDay}，终止本次候选计算、拒绝整个相关 Advance 提交（禁止在污染态上继续收尾；§13.7 第 2 条）。
 * </ul>
 *
 * <p>★★ <b>为什么"业务拒绝"要单独成表而不是丢进回执</b>：economy 要按它做两件不同的事—— ① 把释放掉的劳动/原料<b>重新分配</b>；② 记一条 INFO
 * 说明哪个候选今天没进去。混在回执里会让 "实际产出了什么"与"谁没被允许生产"两件事共用一条通道。
 *
 * <p>★ <b>{@link #closedUnitScales()} 为什么必须有</b>：周期关账的<b>产出规模</b>只有 production 算得出来 （{@code
 * finalScale} 由 {@code ProductionEfficiency} 这一个入口给出）。economy 侧的铸币阶段要按 "同一个规模"兑现候选产出（I12：技术产出率归
 * production，发行资格/上限/记录/入账留 economy）⇒ 它必须从这份结果里读，而<b>不</b>能自己再算一遍（那是第二份口径）。
 *
 * @param receipts 每一步的执行回执（<b>按 {@link ProductionDayRequest#steps()} 的序</b>；被拒的步不在其中）
 * @param rejections 被拒的步（<b>按步序</b>；key = 实例 ID，value = 具名理由）
 * @param closedUnitScales 本日<b>关账</b>的实例 → 产出规模（{@code finalScale}）；未关账的不在表里
 * @param closedGrossOutput 本日关账实例的实毛产（实例 → 商品 → 毫单位）——铸币/收获阶段的只读事实
 * @param closedMintCandidates 本日关账实例的铸币<b>候选</b>数量（实例 → 币种 → 毫货币）；I12：只是候选
 */
public record ProductionDayResult(
    List<ProductionReceipt> receipts,
    Map<ProductionUnitId, String> rejections,
    Map<ProductionUnitId, Long> closedUnitScales,
    Map<ProductionUnitId, Map<CommodityId, Long>> closedGrossOutput,
    Map<ProductionUnitId, Map<CurrencyId, Long>> closedMintCandidates) {

  public ProductionDayResult {
    if (receipts == null) {
      throw new IllegalArgumentException("ProductionDayResult.receipts 不得为 null（没有执行给空表）");
    }
    for (ProductionReceipt receipt : receipts) {
      Objects.requireNonNull(receipt, "ProductionDayResult.receipts 的元素不得为 null");
    }
    receipts = List.copyOf(receipts);

    if (rejections == null) {
      throw new IllegalArgumentException("ProductionDayResult.rejections 不得为 null（没有拒绝给空表）");
    }
    Map<ProductionUnitId, String> rejectionCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, String> entry : rejections.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "ProductionDayResult.rejections 的键不得为 null");
      Objects.requireNonNull(entry.getValue(), "ProductionDayResult.rejections 的值不得为 null");
      rejectionCopy.put(entry.getKey(), entry.getValue());
    }
    rejections = Collections.unmodifiableMap(rejectionCopy);

    if (closedUnitScales == null) {
      throw new IllegalArgumentException("ProductionDayResult.closedUnitScales 不得为 null（没有关账给空表）");
    }
    Map<ProductionUnitId, Long> scaleCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, Long> entry : closedUnitScales.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "ProductionDayResult.closedUnitScales 的键不得为 null");
      Objects.requireNonNull(entry.getValue(), "ProductionDayResult.closedUnitScales 的值不得为 null");
      scaleCopy.put(entry.getKey(), entry.getValue());
    }
    closedUnitScales = Collections.unmodifiableMap(scaleCopy);

    // ★★ B11：两张嵌套表**就地**逐字展开冻结（照 MarketTable.hexes / EconomyMeta 政策表 / ProductionData 的形制）。
    //   ★ 为什么必须写在本构造器里、不能抽私有 helper：SpotBugs 的 EI_EXPOSE_REP **不做跨过程分析**，只认它在这
    //     一个方法里看得见的 Collections.unmodifiableMap ⇒ 抽成 helper 会让生成的 record 访问器被报成"暴露内部
    //     表示"（本类 2 条）。★ 语义与抽 helper 时**逐字节相同**：同一"外层保序拷贝 + 内层各自冻结"、
    //     同一批拒绝消息。
    if (closedGrossOutput == null) {
      throw new IllegalArgumentException("ProductionDayResult.closedGrossOutput 不得为 null");
    }
    Map<ProductionUnitId, Map<CommodityId, Long>> grossCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, Map<CommodityId, Long>> entry : closedGrossOutput.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionDayResult.closedGrossOutput 的键与值都不得为 null");
      }
      Map<CommodityId, Long> inner = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> line : entry.getValue().entrySet()) {
        if (line.getKey() == null || line.getValue() == null) {
          throw new IllegalArgumentException(
              "ProductionDayResult.closedGrossOutput 的内层键与值都不得为 null");
        }
        inner.put(line.getKey(), line.getValue());
      }
      grossCopy.put(entry.getKey(), Collections.unmodifiableMap(inner));
    }
    closedGrossOutput = Collections.unmodifiableMap(grossCopy); // ★ 冻在赋值处

    if (closedMintCandidates == null) {
      throw new IllegalArgumentException("ProductionDayResult.closedMintCandidates 不得为 null");
    }
    Map<ProductionUnitId, Map<CurrencyId, Long>> mintCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, Map<CurrencyId, Long>> entry :
        closedMintCandidates.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionDayResult.closedMintCandidates 的键与值都不得为 null");
      }
      Map<CurrencyId, Long> inner = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> line : entry.getValue().entrySet()) {
        if (line.getKey() == null || line.getValue() == null) {
          throw new IllegalArgumentException(
              "ProductionDayResult.closedMintCandidates 的内层键与值都不得为 null");
        }
        inner.put(line.getKey(), line.getValue());
      }
      mintCopy.put(entry.getKey(), Collections.unmodifiableMap(inner));
    }
    closedMintCandidates = Collections.unmodifiableMap(mintCopy); // ★ 冻在赋值处
  }

  /** ★ 空日的结果（没有任何实例要推进；合法状态，世界照常推进）。 */
  public static ProductionDayResult empty() {
    return new ProductionDayResult(List.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  /** ★ 本日是否什么都没发生（没有回执、没有拒绝）——调用方据此跳过整个生产段。 */
  public boolean isEmpty() {
    return receipts.isEmpty() && rejections.isEmpty();
  }

  /** ★ 业务拒绝（具名 INFO）的条目数。 */
  public int rejectionCount() {
    return rejections.size();
  }

  /** ★ 按实例取回执（拒绝或未推进 ⇒ {@link Optional#empty()}）。 */
  public Optional<ProductionReceipt> receiptOf(ProductionUnitId unit) {
    for (ProductionReceipt receipt : receipts) {
      if (receipt.unit().equals(unit)) {
        return Optional.of(receipt);
      }
    }
    return Optional.empty();
  }
}
