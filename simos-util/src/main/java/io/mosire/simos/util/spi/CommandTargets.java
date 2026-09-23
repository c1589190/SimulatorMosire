package io.mosire.simos.util.spi;

import java.util.List;

/**
 * **命令自己声明它的目标资源**（第 3 波第 2 步）：一条领域命令要改哪些资源，只有**写它的那个模块**知道。
 *
 * <p>★★ **为什么需要这个契约**：GM 要"代执行"决策人令里的命令，而"GM 代执行**也不能越权**"⇒ 落盘前必须拿**出令决策人自己的
 * 可达面**去判这条命令的目标。写侧此前**没有任何目标解析点**：资源声明只有每工具一条粗粒度（{@code map:<mapId>} / {@code unit:"*"} / {@code
 * social:"*"}，见 {@code ToolSupport#allWriteResources}），照套会把受限决策人的**每一条令**都拒掉。 本契约把"目标是谁"交回**与
 * handler 同处一地**的那份知识（同一模块、与 {@code UnitPayloads}/{@code MapPayloads} 共用载荷解析） ⇒ **不造第二份真相**。
 *
 * <p>★ **实现者与注册面同源**：各域 {@link CommandHandler} 自己实现本接口 ⇒ 调用方（simos-app）可以直接从**已注册的 handler 清单**派生出
 * {@code type → CommandTargets} 表，不需要另立一张会漂移的注册表。
 *
 * <p>★★ **未实现该契约的命令类型 ⇒ 调用方必须 fail-closed 拒绝**（"没有目标声明" = "判不了越权" ≠ "随便动"）。今天有意不实现的有四条 （{@code
 * unit.CreateCommandChain} / {@code unit.UpdateCommandChain} / {@code map.SetEdge} / {@code
 * map.RegisterPathwayGroup}）：它们的语义对象（命令链 / 边 / 连通性组）**今天不是资源命名空间**， 故没有可寻址目标。缺口由构建期清单测试钉住，不静默。
 *
 * <p>★ **返回值语义**：
 *
 * <ul>
 *   <li>**命名空间内的路径**（{@link ResourcePaths} 的形态），**不含命名空间名**——命名空间由命令类型推导（{@code type} 的第一个 {@code
 *       '.'} 之前那段，与 {@code CommandBus} 的既有约定同源）。调用方据此包成 {@code ResourceId.of(namespace, path)}。
 *   <li>**多目标逐条返回**（{@code hexes[]} / {@code subUnitIds[]} / {@code id+parent} 都要，不是只第一条）——
 *       调用方**逐条**判越权。
 *   <li>**空列表 = 本命令没有可寻址目标**（与"未实现本契约"同一条 fail-closed 路径）。
 *   <li>**创建型**（{@code unit.CreateUnit.id} / {@code map.CreateRegion.regionId}）按**载荷里点名的新 id** 判：
 *       命令生效后该资源就会存在，按"将要成为的目标"判是唯一可判的口径。
 * </ul>
 *
 * <p>★ **诚实边界（不假装覆盖）**：只声明**载荷里点名的**目标。命令的**级联影响**不在声明里——{@code unit.DisbandUnit} 会解散整棵子树、{@code
 * unit.ReparentSubtree} 会迁移全部后代，而载荷只给根 id。要覆盖它们得让每个 handler
 * 复算级联（把领域算法的另一半搬到这里），那是**另一个决定**，不在此默默扩大。
 *
 * <p>★ **坏载荷以 {@link IllegalArgumentException} 面世**（与 {@code UnitPayloads}/{@code MapPayloads} 同口径，
 * 带可读中文原因）：调用方把它折成**该条命令的拒因**——判不出目标就不得放行。
 */
@FunctionalInterface
public interface CommandTargets {

  /**
   * 本命令要写哪些资源（命名空间内路径）。
   *
   * @param mapId 地图称谓（{@code map} 命名空间路径的首段，spec §3.3）；{@code unit}/{@code social} 类命令用不到它
   * @param payloadJson 命令载荷 JSON 文本（**逐字节**转交，不 trim、不重排）
   * @return 目标路径清单（保序、可重复；空列表 = 本命令没有可寻址目标）
   * @throws IllegalArgumentException 载荷不是合法 JSON / 形状不对（判不出目标）
   */
  List<String> targetPaths(String mapId, String payloadJson);
}
