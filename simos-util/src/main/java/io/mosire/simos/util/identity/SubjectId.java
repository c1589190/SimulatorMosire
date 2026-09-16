package io.mosire.simos.util.identity;

/**
 * 稳定身份：`namespace` + `localId`（铁律 1——地址是定位方式，ID 是身份）。
 *
 * <p>`namespace` 取 `<模块>` 或 `<模块>.<类型>`（`map.hex` / `unit.equipment`），层级由各模块自定，Util 不校验； `localId`
 * 的生成规则（前缀、长度、随机源）同样属于各领域模块。
 */
public record SubjectId(String namespace, String localId) {

  public SubjectId {
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("SubjectId.namespace 不得为空白");
    }
    if (localId == null || localId.isBlank()) {
      throw new IllegalArgumentException("SubjectId.localId 不得为空白");
    }
  }
}
