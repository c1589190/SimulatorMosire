package io.mosire.simos.util.state;

/** 分支标识（总纲 §4.1）：`RevisionId` 只在分支内有意义。 */
public record BranchId(String value) {

  public BranchId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("BranchId.value 不得为空白");
    }
  }
}
