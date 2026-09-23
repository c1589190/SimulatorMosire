package io.mosire.simos.util.spi;

import java.util.Objects;

/**
 * 资源路径语法（spec §3.3）的**唯一拼写点**（共享层）。
 *
 * <p>★★ **为什么必须在共享层、且只有一处**：这些字符串是**权限围栏**的输入（{@code ResourceScope} 的段边界前缀匹配）， 拼错一段（多一条 {@code
 * /}、少一个段、把 {@code _} 写成 {@code /}）**不会抛任何异常**，只会让"该看见的看不见 / 不该看见的看得见"。
 *
 * <p>它低于 app 与各领域模块：读侧的围栏前缀（{@code NationScope}/{@code ArmyScope}）、写侧的资源断言（{@code ToolSupport}）、
 * 以及**命令自己的目标声明**（{@link CommandTargets}）三者必须拼出**同一串**，而它们的调用点分居三个模块 ⇒ 语法只能有一个来源。 本类之前的唯一实现住在 app
 * 层的 {@code ToolSupport}，map 侧要用就得复制一份（正是要消灭的形态）⇒ 上提到此。
 *
 * <p>★ **只产字符串、不引 AgentLib**：各域模块（simos-unit / simos-map）不认识 AgentLib 的 {@code ResourceId}； 由 app
 * 侧（{@code ToolSupport}）把本类的路径包成 {@code ResourceId}。
 *
 * <p>★ **命名空间内路径不带命名空间名**：{@code unit:u-1} 的路径是 {@code u-1}、{@code map:Map1/hex/1_1} 的路径是 {@code
 * Map1/hex/1_1}——命名空间由调用方（{@code ResourceId.of(namespace, path)}）给出。
 */
public final class ResourcePaths {

  /** 区域的固定段（{@code <mapId>/region/<regionId>}）。 */
  private static final String REGION_SEGMENT = "/region/";

  /** 单个格的固定段（{@code <mapId>/hex/<q>_<r>}）。 */
  private static final String HEX_SEGMENT = "/hex/";

  /** 坐标分隔符（**下划线**，与 canonical 地址的 {@code hex.<q>_<r>} 同源）。 */
  private static final String COORD_SEPARATOR = "_";

  private ResourcePaths() {}

  /** 区域：{@code <mapId>/region/<regionId>}。 */
  public static String region(String mapId, String regionId) {
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(regionId, "regionId");
    return mapId + REGION_SEGMENT + regionId;
  }

  /**
   * 单个格：{@code <mapId>/hex/<q>_<r>}。
   *
   * <p>★ 坐标**照写**：负号原样带（真档的 hex 大量落在负坐标），分隔符是下划线。
   */
  public static String hex(String mapId, int q, int r) {
    Objects.requireNonNull(mapId, "mapId");
    return mapId + HEX_SEGMENT + q + COORD_SEPARATOR + r;
  }

  /** 单位：**裸单位 id**（无子路径——单位本身就是资源）。 */
  public static String unit(String unitId) {
    Objects.requireNonNull(unitId, "unitId");
    return unitId;
  }

  /** 人口：{@code <q>_<r>}（**不带 mapId**——人口按格取，地图只有一张）。 */
  public static String social(int q, int r) {
    return q + COORD_SEPARATOR + r;
  }

  /** sd 域：{@code <kind>/<id>}（kind ∈ decision-maker / nation / army / combat，spec §3.3）。 */
  public static String sd(String kind, String id) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
    return kind + "/" + id;
  }
}
