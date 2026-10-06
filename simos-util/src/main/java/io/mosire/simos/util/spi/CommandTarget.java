package io.mosire.simos.util.spi;

/**
 * **命令目标资源**（2026-10-20 用户裁定，跨命名空间家户权限）：一个目标 =（命名空间，命名空间内路径）。
 *
 * <p>★★ **为什么从"单命名空间路径"升级成本类型**：家户命令的目标可能同时落在两个命名空间——家户挂在某个 hex ⇒ 目标是 {@code social:<q>_<r>}；
 * 家户挂在某个单位上 ⇒ 目标是 {@code unit:<unitId>}。旧 {@link CommandTargets#targetPaths} 只能给一个命名空间（由命令类型第一段推导），
 * 表达不了"一条命令既动 social 又动 unit"（{@code social.SetHouseholdLocation} 在 HEX ↔ UNIT 间移动时尤其明显：
 * 旧位置与新位置可能跨命名空间）。本类型把命名空间**逐目标**显式带出来，调用方按各自命名空间判越权。
 *
 * <p>★ **命名空间约定与 {@code CommandBus} 同源**：命令类型形如 {@code <namespace>.<Command>}，命名空间 = 第一个 {@code '.'} 之前那段
 * （{@link #namespaceOf(String)} 是这个切法的**唯一工具**，写歪不会抛、只会静默判错）。
 *
 * <p>★ **路径是命名空间内路径**（{@link ResourcePaths} 的形态，不含命名空间名）：{@code unit:u-1} 的 path 是 {@code u-1}，
 * {@code social:1_2} 的 path 是 {@code 1_2}。调用方用 {@code ResourceId.of(namespace, path)} 组装。
 *
 * @param namespace 命名空间（非空白；如 {@code social} / {@code unit} / {@code map}）
 * @param path 命名空间内路径（非空白；如 {@code 1_2} / {@code u-1} / {@code Map1/hex/1_2}）
 */
public record CommandTarget(String namespace, String path) {

  public CommandTarget {
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("CommandTarget.namespace 不得为空白");
    }
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("CommandTarget.path 不得为空白");
    }
  }

  /**
   * 命令类型的命名空间：{@code type} 第一个 {@code '.'} 之前那段（与 {@code CommandBus} 的既有约定同源）。
   *
   * <p>★ 形状校验同 {@code CommandRegistry.requireNamespacedType}（{@code <namespace>.<Command>}）：切点不存在、在开头或结尾都
   * **具名拒**——这里的调用方要拿它当围栏命名空间，切成空串会让判定整体失真。
   *
   * @throws IllegalArgumentException 命令类型为空白或不是 {@code <namespace>.<Command>} 形态
   */
  public static String namespaceOf(String commandType) {
    if (commandType == null || commandType.isBlank()) {
      throw new IllegalArgumentException("命令类型不得为空白");
    }
    int dot = commandType.indexOf('.');
    if (dot <= 0 || dot == commandType.length() - 1) {
      throw new IllegalArgumentException(
          "命令类型必须形如 <namespace>.<Command>: " + commandType);
    }
    return commandType.substring(0, dot);
  }

  /** 人类可读的规范短串（{@code namespace:path}），供拒因/日志核对用。 */
  @Override
  public String toString() {
    return namespace + ":" + path;
  }
}
