package io.mosire.simos.ga.spi;

/**
 * sd 用到的跨模块命令名常量（N9）。
 *
 * <p>★ {@link #SIMOS_COMMAND_SUBMIT} 是**通用写**（Core 的信封提交面）：决策人的窄工具白名单**不得含它**（N9）—— {@code
 * ga.CreateDecisionMaker} 在命令期据此校验。
 */
public final class GaCommandNames {

  public static final String SIMOS_COMMAND_SUBMIT = "simos.command.submit";

  private GaCommandNames() {}
}
