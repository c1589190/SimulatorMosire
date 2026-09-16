package io.mosire.simos.util.time;

/** 事件施加方式：`ADD` 需调用方注入加法，`SET` 直接覆盖（spec §七）。 */
public enum EventMode {
  ADD,
  SET
}
