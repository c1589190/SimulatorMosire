package io.mosire.simos.util.log;

/**
 * ★★ <b>通用日志级别</b>（2026-10-09 用户裁定：单纯 event 机制放 util）。
 *
 * <p>只有五个值，与 SLF4J 无编译期耦合；映射到具体 logger 的级别判断/发射在 {@link EventLog} 一处完成。
 * 它不携带任何领域语义，也不负责解析配置——级别开关仍由各模块的 {@code log4j2.xml} 按 logger 名控制。
 */
public enum LogLevel {
  TRACE,
  DEBUG,
  INFO,
  WARN,
  ERROR
}
