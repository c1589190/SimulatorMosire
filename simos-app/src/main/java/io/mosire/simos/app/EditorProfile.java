package io.mosire.simos.app;

import java.util.Locale;

/**
 * 运行 profile（D-036）：{@code full} / {@code editor}。
 *
 * <p>★ editor = “文游地图编辑器 + 数据保存处理器”：economy / production / culture 不再挂 handler、
 * resolver、validator 与工具；时间推进不再跑人口—经济/土地参与者。**codec 暂时保留**以便读取含旧切片的
 * store（replay 不因缺 codec 崩），但没有任何编辑器路径会写这三片。
 *
 * <p>★ 缺省（直接调用 {@link Shell#start}）仍是 {@code full}，以免既有测试/嵌入方行为突变；
 * {@code ShellMain} 在无显式 {@code --profile} 时选择 {@code editor}，让产品入口默认进入编辑器。
 */
public final class EditorProfile {

  public static final String PROPERTY = "simos.profile";
  public static final String FULL = "full";
  public static final String EDITOR = "editor";

  private EditorProfile() {}

  /** 当前是否 editor profile；未设置 ⇒ full。 */
  public static boolean enabled() {
    return EDITOR.equals(value());
  }

  /** 当前 profile 文本（小写）。 */
  public static String value() {
    String raw = System.getProperty(PROPERTY, FULL);
    return raw == null ? FULL : raw.strip().toLowerCase(Locale.ROOT);
  }

  /** 该命令是否属于 editor profile 暂停的领域。 */
  public static boolean pausedCommand(String commandType) {
    if (commandType == null) {
      return false;
    }
    return commandType.startsWith("economy.")
        || commandType.startsWith("production.")
        || commandType.startsWith("culture.");
  }

  /** 该工具是否属于 editor profile 暂停的领域（按工具名前缀；工具名与命令名不总同字面）。 */
  public static boolean pausedTool(String toolName) {
    if (toolName == null) {
      return false;
    }
    return toolName.startsWith("simos.economy.")
        || toolName.startsWith("simos.production.")
        || toolName.startsWith("simos.culture.")
        // 少数宿主工具名字不带 economy 前缀，但写的是 economy 面。
        || toolName.equals("simos.gm.periodicAdjustment")
        || toolName.equals("simos.gm.govMarketMandate");
  }

  /**
   * 产品入口的 profile 选择：显式 {@code --profile full|editor} 优先；否则缺省 editor。
   * 只应由 {@code ShellMain.main} 调用，测试/嵌入方直接设置系统属性。
   */
  public static void configureFromArgs(String[] args) {
    String selected = null;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if ("--profile".equals(arg)) {
        selected = i + 1 < args.length ? args[i + 1] : null;
      } else if (arg.startsWith("--profile=")) {
        selected = arg.substring("--profile=".length());
      }
    }
    System.setProperty(PROPERTY, selected == null || selected.isBlank() ? EDITOR : selected);
  }
}
