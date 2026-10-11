package io.mosire.simos.gov.tools;

import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 政府工具共用的几件小事：**查询坐标**（载荷 → 值参）与**允许面切片取用**（gov / unit / map / social）。
 *
 * <p>★ <b>为什么坐标是值参而不是宿主的查询对象</b>：宿主（app）的 {@code QueryTarget} 是 app 类型，进不了 {@code simos-gov} ⇒
 * 工具只把载荷里的 {@code branch}/{@code revision} 解析成 util 类型，交给注入的 {@link ToolReadPort}——head
 * 解析、创世等待、读缓存都在宿主实现里 （口径逐条不变）。与 B3 试点 map / 第二域 army / 第五域 unit 同一条处置。
 *
 * <p>★★ <b>为什么切片取用写在域内、而不注入</b>：gov 域**结构上就够得着** gov/unit/map/social 四片（依赖面
 * util/map/unit/social/economy-api，enforcer 强制），"从 state 里按 namespace 取快照"不是跨模块契约、也不是第二份业务规则
 * ——它是**机械提取**。
 *
 * <p>★★ <b>两套文案（如实记，不是笔误）</b>：迁入前这些提取有**两个**来源，异常文案不同：
 *
 * <ul>
 *   <li>{@code ToolSupport.unitState/gameMap/socialData} —— 绝大多数工具用它；本类的 {@link #unitState} /
 *       {@link #gameMap} / {@link #socialData} 逐字照抄这一套；
 *   <li>{@code ApiViews.unitState/gameMap} —— 只有 {@code GovDismissPlan} 与 {@code
 *       ProvinceDivideTool} 用它们， 文案多一句 {@code ，不是"没有候选"}；本类的 {@link #apiUnitState} / {@link
 *       #apiGameMap} 逐字照抄那一套， 以免把这两条**装配故障路径**的对外文本改掉（I-G1）。
 * </ul>
 */
public final class GovToolReads {

  private GovToolReads() {}

  /** 载荷里的分支名（缺省 {@link ToolResources#DEFAULT_BRANCH}，与宿主同口径）。 */
  public static BranchId branch(Map<String, Object> args) {
    return new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
  }

  /** 载荷里的版本号；缺省（{@code null}）= 该分支 head（{@link ToolReadPort#stateAt} 的同一条约定）。 */
  public static RevisionId revision(Map<String, Object> args) {
    Long revision = ToolSchema.optionalLong(args, "revision");
    return revision == null ? null : new RevisionId(revision);
  }

  /** 按载荷坐标取状态（{@code branch}/{@code revision} 的解析都在这里）。 */
  public static SimulationState state(ToolReadPort reads, Map<String, Object> args) {
    return reads.stateAt(branch(args), revision(args));
  }

  /** 状态里的 gov 切片（缺切片/类型不符 = 装配故障，不当成"没有政府读数"）。 */
  public static GovState govState(SimulationState state) {
    Snapshot snapshot =
        state.module("gov").orElseThrow(() -> new IllegalStateException("state 里没有 gov 切片（装配故障）"));
    if (snapshot instanceof GovSnapshot govSnapshot) {
      return govSnapshot.state();
    }
    throw new IllegalStateException(
        "state 的 gov 切片不是 GovSnapshot: " + snapshot.getClass().getName());
  }

  /** 状态里的 unit 切片（{@code ToolSupport.unitState} 的逐字口径：缺席或类型不对是**装配故障**，当场抛、 不当成"没有单位"）。 */
  public static UnitState unitState(SimulationState state) {
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 unit 模块切片——装配故障"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalArgumentException(
          "unit 模块切片不是 UnitSnapshot：" + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  /** 同 {@link #unitState}，但文案逐字照抄 {@code ApiViews.unitState}（只有 {@code GovDismissPlan} 用它）。 */
  public static UnitState apiUnitState(SimulationState state) {
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 unit 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalArgumentException(
          "unit 模块切片不是 UnitSnapshot：" + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  /** 状态里的 map 切片（{@code ToolSupport.gameMap} 的逐字口径）。 */
  public static GameMap gameMap(SimulationState state) {
    Snapshot snapshot =
        state.module("map").orElseThrow(() -> new IllegalArgumentException("状态里没有 map 模块切片——装配故障"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalArgumentException("map 模块切片不是 MapSnapshot：" + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  /** 同 {@link #gameMap}，但文案逐字照抄 {@code ApiViews.gameMap}（只有 {@code ProvinceDivideTool} 用它）。 */
  public static GameMap apiGameMap(SimulationState state) {
    Snapshot snapshot =
        state
            .module("map")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 map 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalArgumentException("map 模块切片不是 MapSnapshot：" + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  /** 状态里的 social 切片（{@code ToolSupport.socialData} 的逐字口径）。 */
  public static SocialData socialData(SimulationState state) {
    Snapshot snapshot =
        state
            .module("social")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 social 模块切片——装配故障"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalArgumentException(
          "social 模块切片不是 SocialSnapshot：" + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  /**
   * {@code {"q":…,"r":…}} 的只读视图（{@code ToolSupport.hexCoord} 的逐字拷贝：字段名与顺序都相同）。
   *
   * <p>★ 它是**纯函数**且只吃 gov 允许面内的 {@link HexCoord} ⇒ 按试点口径随迁域内（不是第二份业务规则）。
   */
  public static Map<String, Object> hexCoord(HexCoord coord) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("q", coord.q());
    view.put("r", coord.r());
    return view;
  }
}
