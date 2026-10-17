package io.mosire.simos.economy.model;

import java.util.Arrays;

/** D-032 D4 资产占用授予状态（D4 §4.3）：ACTIVE 计入占用，RELEASED 保留审计/回填依据。 */
public enum AssetOccupancyStatus {
  ACTIVE,
  RELEASED;

  /** 按词表解析；未知值 fail-closed（不默认、不归一）。 */
  public static AssetOccupancyStatus parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetOccupancyStatus 不得为空白: " + text);
    }
    try {
      return valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 AssetOccupancyStatus: " + text + "；合法值: " + Arrays.toString(values()), e);
    }
  }
}
