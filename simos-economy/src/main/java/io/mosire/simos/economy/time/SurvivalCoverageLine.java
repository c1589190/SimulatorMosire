package io.mosire.simos.economy.time;

/** D3 §2.5.1.1：逐需求行来源拆分；covered 必须等于四项来源之和且 ≤ required。 */
public record SurvivalCoverageLine(
    long requiredQuantityMilli,
    long fromInventoryMilli,
    long fromExpectedInKindMilli,
    long fromExpectedProductShareMilli,
    long fromMoneyMilli,
    long coveredQuantityMilli) {

  public SurvivalCoverageLine {
    if (requiredQuantityMilli < 0L
        || fromInventoryMilli < 0L
        || fromExpectedInKindMilli < 0L
        || fromExpectedProductShareMilli < 0L
        || fromMoneyMilli < 0L
        || coveredQuantityMilli < 0L) {
      throw new IllegalArgumentException("SurvivalCoverageLine 的各项不得为负");
    }
    long sum =
        Math.addExact(
            Math.addExact(fromInventoryMilli, fromExpectedInKindMilli),
            Math.addExact(fromExpectedProductShareMilli, fromMoneyMilli));
    if (coveredQuantityMilli != sum) {
      throw new IllegalArgumentException(
          "survival-input-invalid: SurvivalCoverageLine.coveredQuantityMilli 必须等于四项来源之和: covered="
              + coveredQuantityMilli
              + " sum="
              + sum);
    }
    if (coveredQuantityMilli > requiredQuantityMilli) {
      throw new IllegalArgumentException(
          "survival-input-invalid: SurvivalCoverageLine.coveredQuantityMilli 不得大于 requiredQuantityMilli: covered="
              + coveredQuantityMilli
              + " required="
              + requiredQuantityMilli);
    }
  }
}
