package io.mosire.simos.ga.spi;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.util.spi.ResourcePaths;
import java.util.List;
import java.util.Map;

/**
 * 工具面的**资源助手**：命名空间常量 + 资源路径 + 声明清单 + 写前断言。纯函数、零状态，只依赖 {@code simos-util}（{@link ResourcePaths}
 * 的路径语法）与 {@code agentlib-mosire} （{@code ResourceId} / {@code ResourceManifest} / {@code
 * ToolContext}）。
 *
 * <p>★ 迁自 {@code io.mosire.simos.app.tools.ToolSupport}（2026-10-12 B1，设计书 §4.2 的 "ToolResource /
 * ToolSchema（资源与 schema 助手）"一行）：只挑**不依赖 app / 领域 / core** 的那部分。 旧的 {@code ToolSupport}
 * **一行未改**（本批纯新增；B3 的 ToolSupport 三分再收口）。
 *
 * <p>★★ <b>为什么路径必须有唯一出处</b>：这些字符串是**权限围栏**的输入（{@code ResourceScope} 的段边界前缀 匹配），拼错一段（多一条 {@code
 * /}、少一个段、把 {@code _} 写成 {@code /}）**不会抛任何异常**，只会让 "该看见的看不见 / 不该看见的看得见"。故调用点一律**引用这些助手**，不各自拼串。
 *
 * <p>★ <b>可见性谓词不在这里</b>（{@code hexVisible} / {@code unitVisible} / {@code regionVisible} / {@code
 * populationVisible} / {@code subjectVisible}）：它们依赖 app 的 {@code QueryService} / {@code ApiViews} /
 * 各领域切片类型，属 app（设计书 §9-3 未核，B1 不碰）。
 */
public final class ToolResources {

  private ToolResources() {}

  /** 查询/写入的缺省分支（spec §8.2 的 GUI 同款；工具入参可覆盖）。 */
  public static final String DEFAULT_BRANCH = "main";

  /** 地图/区域/单格资源所属的命名空间（{@code ToolResources} 的路径助手与范围函数共用这一处定义）。 */
  public static final String MAP_NAMESPACE = "map";

  /** 人口资源所属的命名空间（与 {@link #SOCIAL_READ} 的声明同源）。 */
  public static final String SOCIAL_NAMESPACE = "social";

  /** 单位资源所属的命名空间（与 {@link #UNIT_READ} 的声明同源）。 */
  public static final String UNIT_NAMESPACE = "unit";

  /**
   * ga 域资源所属的命名空间（{@code ga:<kind>/<id>}；2026-10-14 批次 F 由 {@code sd} 改名 —— 用户裁定「但凡和被取缔的 sd
   * 模块有关的就改」，改后与地址族 {@code ga:} 同名、一处一致）。
   *
   * <p>★ 它存在的理由是**权限表态**：GM 组的可达面按命名空间逐条表态（app 侧 {@code Shell#gmPermissionSet}）—— 新命名空间不加进那份表态，GM
   * 面就会回落成"未表态"分支。
   */
  public static final String GA_NAMESPACE = "ga";

  /**
   * 经济资源所属的命名空间（{@code economy:<q>_<r>}，与 {@code ResourcePaths#economy} 同源）。
   *
   * <p>★ 它同时出现在两处表态里：{@code simos.economy.hex} 的读声明与 GM 组的逐命名空间范围—— 新命名空间不加进后者，GM 面就会回落成"未表态"分支。
   */
  public static final String ECONOMY_NAMESPACE = "economy";

  /**
   * production 资源所属的命名空间（{@code production}；D-031 P1：定义权威迁入 production）。
   *
   * <p>★ 与 {@link #ECONOMY_NAMESPACE} 同款：它出现在 GM 组的逐命名空间范围与工具自己的资源声明（如 {@code
   * simos.economy.upsertIndustry} 现提交 {@code production.UpsertIndustry}）；新命名空间不加进前者，GM
   * 面就会回落成"未表态"分支。
   */
  public static final String PRODUCTION_NAMESPACE = "production";

  /**
   * actor 资源所属的命名空间（{@code actor:<mapId>[:actor.<KIND>.<id> | :holding.<key> | :goods.<key>]}）。
   *
   * <p>★★ <b>字面量 {@code "actor"} 全仓三处同字面</b>：这里、{@code ActorSnapshot.namespace()}、 {@code
   * ActorCodec.namespace()}——{@code SimulationState} 构造期校验"modules 的键 == snapshot.namespace()"，
   * 写歪当场抛（不是静默 miss）。
   *
   * <p>★ 与 {@link #ECONOMY_NAMESPACE} 同款：它同时出现在两处表态里——GM 组的逐命名空间范围与 actor 自己的工具资源声明；新命名空间不加进前者，GM
   * 面就会回落成"未表态"分支。
   */
  public static final String ACTOR_NAMESPACE = "actor";

  /**
   * army 资源所属的命名空间（阶段 D1 / D-012，2026-10-02 起：{@code army:combat.<id>}）。
   *
   * <p>★★ <b>字面量 {@code "army"} 全仓三处同字面</b>：这里、{@code ArmySnapshot.namespace()}、 {@code
   * ArmyCodec.namespace()}——{@code SimulationState} 构造期校验"modules 的键 == snapshot.namespace()"，
   * 写歪当场抛（不是静默 miss）。
   *
   * <p>★ <b>它今天只用于写侧表态</b>（GM 的 {@code army.*} 写资源断言与逐命名空间范围）；
   * <b>读侧的可见性不落在这个命名空间上</b>——交战记录没有自己的资源路径，按记录所在格判 （与 {@code map.city} 同款，见 app 侧 ToolSupport
   * 的可见性谓词）。新命名空间不加进 GM 组的表态， GM 面就会回落成"未表态"分支。
   */
  public static final String ARMY_NAMESPACE = "army";

  /**
   * ★★ <b>GM 的稳定身份 id</b>（2026-10-14 GM⇄DM 双向信件，约束设计书 §9-A 裁定）：{@code "gm"}——<b>全仓唯一拼写点</b>，
   * 它出现在三处，全部引用本常量、不抄字面量：
   *
   * <ol>
   *   <li><b>信件地址段</b>：{@code ga:message.gm.<dm>.<tick>.<n>}（GM 发出的信；DM 发出的信是 {@code
   *       ga:message.<dm>.gm.<tick>.<n>}）；
   *   <li><b>信件的 {@code tags}</b>：{@code {gm, <对方>}}（与既有的"tags = 收发双方"同形，不新开可见性机制）；
   *   <li><b>{@code peer} 前缀表</b>：{@code peer:gm} 进**每个**决策人的基础可达面（见 {@link #PEER_NAMESPACE} 第 2
   *       条）。
   * </ol>
   *
   * <p>★ <b>为什么必须有它</b>：{@code ga.SetDecisionMakerPeer} 要求**两个 id 都是已存在的决策人**，而 GM 不是决策人 ⇒ 没有这个稳定
   * id 就写不出"GM 收件人"这一段地址，DM 的疑问无处可寄（设计书 §2.2 缺口 G1/G11）。
   *
   * <p>★ <b>它不是决策人 id</b>：{@code ToolResources.GM_ID} 只作地址段与 tags 的字符串；{@code
   * GaState.decisionMakers()} 里**没有**它（GM 不是决策人 ⇒ 不参与决策包/裁决），任何"按决策人遍历"的读口（如 {@code
   * simos.gm.round}）都不该把它算进去。
   */
  public static final String GM_ID = "gm";

  /**
   * ★★ <b>peer 资源所属的命名空间</b>（2026-10-14 决策人互致消息，设计书 §4.1）：目标资源 = {@code peer:<决策人id>}。
   *
   * <p>★ 它是"GM 一次授权 ⇒ 两决策人双向互致消息"的**唯一闸门**（零新机制）：授权把对方的 id 写进本命名空间的 {@code accessLimit} 前缀表，工具在
   * {@code execute} 里按**参数**（收件人）断言 {@code peer:<to>}。
   *
   * <p>★ <b>它同时出现在三处表态里</b>（新命名空间必然的代价，逐条实测过）：
   *
   * <ol>
   *   <li><b>工具声明</b>（{@link #PEER_WRITE}）——★ <b>策略必须是 {@code DENY}</b>，见那个常量的注释（选错 = fail-open）；
   *   <li><b>决策人基础可达面</b>：{@code peer:<自己>} <b>∪ {@code peer:gm}</b>——两段都是**人人皆有**的基础面（第一段 = 自己，第二段
   *       = GM，见 {@link #GM_ID}）。★ 补 {@code peer:<自己>} 的理由：不补则 {@code inbox} 被自己拒、自指断言永远判否；★ 补
   *       {@code peer:gm} 的理由：DM→GM 的疑问**不需要 GM 事先授权**（他写的是自己的疑问，不读也不写别人的数据，不构成越权，见设计书 §3.1），
   *       故它必须落在**基础可达面**上而不是靠 {@code accessLimit.peer} 配出来。★ 两段都加在 {@code narrowTo(accessLimit)}
   *       <b>之后</b>（{@code DecisionCallerFactory.withPeerSelfFace}）——放在之前或改成覆盖式写，GM 配的授权前缀会被求交/抹掉。
   *   <li><b>GM 组</b>：{@code Shell#gmPermissionSet} 的逐命名空间表态。★ <b>2026-10-14 现状（回代码核过）</b>：GM
   *       组**没有** {@code peer} 这一维的表态 ⇒ 凡 GM 面的工具若把 {@code peer} 写进自己的 {@code ResourceManifest}，会在
   *       {@code ResourceAuthorizer.denial()} 的**执行前闸**上被整调拒掉（{@code DENY} 缺省策略 + 调用者未表态 =
   *       "一个都够不着"）。⇒ GM 侧的两条信件工具（{@code simos.gm.inbox} / {@code simos.gm.message.send}）**不声明**
   *       {@code peer}（取 {@link io.mosire.agentlib.permission.ResourceManifest#NONE} / 既有 {@code
   *       sd} 读声明）， 它们的收件人判据是**地址事实**（{@code value.to == } {@link #GM_ID} / 决策人表存在性），不是资源断言。
   * </ol>
   */
  public static final String PEER_NAMESPACE = "peer";

  /**
   * ★★ 决策人互致消息的工具声明（{@code peer} 域）。
   *
   * <p>★★ <b>策略必须取 {@code DENY}（不是 {@code UNRESTRICTED}）</b>——这是 2026-10-14 实测出来的 <b>fail-open
   * 陷阱</b>：调用者**没有**在本命名空间表态（{@code declaredScope} 为 null）时， {@code ResourceAuthorizer} 回落**声明策略**：
   *
   * <pre>
   * manifest = UNRESTRICTED ⇒ 未授权 DM 的 peer:任何人 = ✓✓✓（**未授权也能发信给任何人** ⇒ I2 整条失效）
   * manifest = DENY         ⇒ 未授权 DM = 网关具名拒；补了自指域之后 = 只许 peer:自己
   * </pre>
   *
   * ⇒ 取 {@code DENY}：**没被 GM 授权就够不着别人**，而自指域由决策人基础可达面单独给（见 {@link #PEER_NAMESPACE} 第 2 条）。原始实测见
   * {@code impl-ledger.md}。
   */
  public static final ResourceManifest PEER_WRITE =
      ResourceManifest.of(PEER_NAMESPACE, ResourcePolicy.DENY);

  /**
   * 读工具的资源声明（spec §7.1：map + social + unit READ_ONLY）。
   *
   * <p>★★ <b>actor 一维也在这里，但缺省策略是 {@code DENY}</b>（Task 10 补）。不声明它时， {@code ResourceAuthorizer} 对
   * {@code actor:…} 一律判否（"未声明即拒"⇒ 工具里那条 actor 分支**恒为假**）—— 实测：只加判定分支、不加这条声明，MCP 的 {@code
   * simos.state.resolve} 对 {@code actor:Map1} **仍是空候选**。声明的意义就是让那条表达式有牙。
   *
   * <p>★ <b>为什么不照 map/social/unit 抄 {@code READ_ONLY}</b>：两条路都是**静默放宽**——
   *
   * <ol>
   *   <li>未表态的调用者会**整片放行** actor 面（spec §5.2 第 3 条：空 = 不表态 = 放行 ⇒ 那正是失效方向 朝"放宽"的一侧）；
   *   <li>更要命的是 {@code ResourceAuthorizer#denial()} 那道**声明式前置闸**："只要**一个**声明过的命名空间 原则上够得着"就放行整个调用，而
   *       {@code READ_ONLY} 对**任何**调用者都算够得着 ⇒ 用本声明的九条读工具， 前置闸会**永远通过**（那是"够不着就连审批都不问"的那道闸）。
   * </ol>
   *
   * ⇒ 取 {@code DENY}：**只有显式表过态的调用者**（GM 组的 {@code ResourceScope.unlimited()}）看得见 actor 面；
   * 未表态者的**判定结果**与"没声明"完全相同——都是拒（fail-closed，不悄悄放宽），只有拒因文案不同。
   */
  public static final ResourceManifest ALL_READ =
      ResourceManifest.of(
          Map.of(
              MAP_NAMESPACE, ResourcePolicy.READ_ONLY,
              SOCIAL_NAMESPACE, ResourcePolicy.READ_ONLY,
              UNIT_NAMESPACE, ResourcePolicy.READ_ONLY,
              ACTOR_NAMESPACE, ResourcePolicy.DENY));

  /** 单命名空间读声明（map.overview / map.hex）。 */
  public static final ResourceManifest MAP_READ =
      ResourceManifest.of(MAP_NAMESPACE, ResourcePolicy.READ_ONLY);

  /** 单命名空间读声明（unit.list / unit.get）。 */
  public static final ResourceManifest UNIT_READ =
      ResourceManifest.of(UNIT_NAMESPACE, ResourcePolicy.READ_ONLY);

  /** 单命名空间读声明（social.population）。 */
  public static final ResourceManifest SOCIAL_READ =
      ResourceManifest.of(SOCIAL_NAMESPACE, ResourcePolicy.READ_ONLY);

  /** 写工具的资源声明（spec §7.1：三命名空间 UNRESTRICTED）。 */
  public static final ResourceManifest ALL_WRITE =
      ResourceManifest.of(
          Map.of(
              MAP_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  // ── 资源路径语法（spec §3.3；AgentLib 对领域实体一无所知，故 AgentLib 侧只认 (ns, path)）──
  //
  // ★ **为什么路径规划成"区域级"而不是"逐 hex"**：`ResourceScope` 的前缀是**段边界**匹配，
  // 且真档有 59223 个 hex ⇒ 逐格前缀不可行；国家决策人的范围因此按**区域**组织（几条到几十条）。
  // ★ 串本身住在共享层 {@link ResourcePaths}（各域模块的命令要声明自己的目标资源，而它们看不见 AgentLib
  //   ⇒ 语法必须有**一个**三方都够得着的出处）。本类只剩"把路径包成 ResourceId"这一件事（输出逐字不变）。

  /** 区域资源：{@code map:<mapId>/region/<regionId>}。 */
  public static ResourceId resourceRegion(String mapId, String regionId) {
    return ResourceId.of(MAP_NAMESPACE, ResourcePaths.region(mapId, regionId));
  }

  /** 单格资源：{@code map:<mapId>/hex/<q>_<r>}（坐标分隔符是**下划线**）。 */
  public static ResourceId resourceHex(String mapId, int q, int r) {
    return ResourceId.of(MAP_NAMESPACE, ResourcePaths.hex(mapId, q, r));
  }

  /** 单位资源：{@code unit:<unitId>}（无子路径——单位本身就是资源）。 */
  public static ResourceId resourceUnit(String unitId) {
    return ResourceId.of(UNIT_NAMESPACE, ResourcePaths.unit(unitId));
  }

  /**
   * 人口资源：{@code social:<q>_<r>}（spec §3.3 的 social 路径语法，**不带 mapId**——人口按格取， 地图只有一张）。
   *
   * <p>★ 与 {@link #resourceHex} 是**同一个格**的两种货币：地图维的格用于"这块地形/这些 facet 你看不看得见"，
   * 人口维的格用于"这格的人口读数你看不看得见"。二者的可见集由范围函数从**同一组格**派生（spec §3.2）， 故"同格口径"在两侧同时成立。
   */
  public static ResourceId resourceSocial(int q, int r) {
    return ResourceId.of(SOCIAL_NAMESPACE, ResourcePaths.social(q, r));
  }

  /** ga 资源：{@code ga:<kind>/<id>}（kind ∈ decision-maker / nation / army / combat，spec §3.3）。 */
  public static ResourceId resourceGa(String kind, String id) {
    return ResourceId.of(GA_NAMESPACE, ResourcePaths.ga(kind, id));
  }

  /**
   * peer 资源：{@code peer:<决策人id>}（★ **无子路径**——决策人 id 本身就是资源，与 {@link #resourceUnit} 同形）。
   *
   * <p>★ 拼串只此一处：{@code ResourceScope} 是段边界前缀匹配，多一个 {@code /} 或少一个段**不会抛任何异常**， 只会让"该通的通不了 /
   * 不该通的通了"。
   */
  public static ResourceId resourcePeer(String decisionMakerId) {
    return ResourceId.of(PEER_NAMESPACE, decisionMakerId);
  }

  // ── 资源断言（AgentTool 契约：真正读写前调 require，工具只调不判）──────────────────────
  //
  // ★ **T10 起不再有"粗断言"**：`map:<mapId>` / `unit:"*"` / `social:"*"` 这三种写法在**受限调用者**身上
  // 一律判否（`ResourceScope` 是段边界前缀匹配 ⇒ `Map1/region/701` 不含 `Map1`），于是"粗断言 + 细围栏 =
  // **整调被拒**"（spec §5.2 第 2 条）——决策人曾因此**连一条读工具都过不去**。
  // ⇒ 读侧一律改成：**逐项 `allows` 筛**（部分可见）；要"整调拒"的地方用 `require` + **细粒度**资源。

  /** 通用写（三个命名空间的粗断言）：{@code simos.command.submit} / {@code simos.advance} 与窄写基类的缺省声明。 */
  public static List<ResourceId> allWriteResources(String mapId) {
    return List.of(
        ResourceId.of(MAP_NAMESPACE, mapId),
        ResourceId.of(UNIT_NAMESPACE, "*"),
        ResourceId.of(SOCIAL_NAMESPACE, "*"));
  }

  /** 三个命名空间的通用写断言（{@link #allWriteResources} 的逐条 require）。 */
  public static void requireAllWrite(ToolContext context, String mapId) {
    requireAll(context, Operation.WRITE, allWriteResources(mapId));
  }

  /** 逐条断言（细粒度资源的统一入口；`allows`/`require` 只在这一处成对出现）。 */
  public static void requireAll(
      ToolContext context, Operation operation, List<ResourceId> resources) {
    for (ResourceId id : resources) {
      context.resources().require(operation, id);
    }
  }
}
