package io.mosire.simos.economy.api.id;

/**
 * 劳动分配 ID（第三阶段设计稿 §四）：一次 {@code HouseholdLaborCommitment}（"某批人把多少劳动供给某个主体"）的稳定身份，归 {@code economy}
 * 切片的劳动分配表。
 *
 * <p>★ **id 由调用方给短名**（与 {@link DebtId} 之外的其余 id 同款）：本类型只校验非空白，**不校验格式**——格式的权威是产出方那唯一一处拼写。
 *
 * <p>★★ **它必须不含 {@code "."}**（与 {@link DebtId} 同款的理由）：地址 {@code economy:<mapId>:allocation.<id>} 由
 * {@code AddressParser} 在**第一个 {@code "."}** 处拆开，id 里带点会把名字**截断**成另一个名字，该分配的地址从此解析不到。 本仓的产者（{@code
 * EconomySeeder}）用 {@code alloc-<批次数>-<产业>} 一类的连字符拼法，故正常路径不挡。
 *
 * <p>裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）：它是 {@code FieldDelta} 的 String key，要进
 * JSON、 要跨 revision 稳定。
 */
public record LaborAllocationId(String value) {

  public LaborAllocationId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("LaborAllocationId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static LaborAllocationId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("LaborAllocationId 不得为空白: " + text);
    }
    return new LaborAllocationId(text);
  }
}
