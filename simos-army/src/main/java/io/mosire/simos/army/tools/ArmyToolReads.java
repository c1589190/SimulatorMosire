package io.mosire.simos.army.tools;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Map;

/**
 * 军队工具共用的四件小事：**查询坐标**（载荷 → 值参）、**army 切片取用**、**允许面切片取用**（unit / social / map）。
 *
 * <p>★ <b>为什么坐标是值参而不是宿主的查询对象</b>：宿主（app）的 {@code QueryTarget} 是 app 类型，进不了 {@code simos-army} ⇒
 * 工具只把载荷里的 {@code branch}/{@code revision} 解析成 util 类型，交给注入的 {@link ToolReadPort}——head
 * 解析、创世等待、读缓存都在宿主实现里（口径逐条不变）。
 *
 * <p>★★ <b>为什么切片取用写在域内、而不注入</b>：army 域**结构上就够得着** arm/unit/social/map 四片 （依赖面
 * util/map/unit/social，enforcer 强制），"从 state 里按 namespace 取快照"不是跨模块契约、也不是第二份业务规则 ——它是**机械提取**。与 B3
 * 试点 {@code simos-map} 的 {@code MapToolReads.gameMap} 同一条处置（台账 D2）。 ★
 * 但**异常类型与文案逐字照抄**既有唯一实现：这些提取只在**装配故障**（状态里没有该切片 / 类型不对）时发出，而 那两句话今天已经出现在工具结果与测试里 ⇒ 搬迁不许改一个字（I-G1）。
 *
 * <p>★ <b>本类为什么是 public（不是包内可见）</b>：army 的读工具在 {@code army.tools.read}、写工具在 {@code
 * army.tools.write}，两者都要用同一份提取；包内可见会迫使第二份出现。
 */
public final class ArmyToolReads {

  private ArmyToolReads() {}

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

  /** 状态里的 army 切片（{@code ApiViews.armyData} 的逐字口径：缺席或类型不对是**装配故障**，当场抛、 不当成"没有候选"）。 */
  public static ArmyData armyData(SimulationState state) {
    Snapshot snapshot =
        state
            .module("army")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 army 模块切片——装配故障"));
    if (!(snapshot instanceof ArmySnapshot armySnapshot)) {
      throw new IllegalArgumentException(
          "army 模块切片不是 ArmySnapshot：" + snapshot.getClass().getName());
    }
    return armySnapshot.data();
  }

  /** 状态里的 unit 切片（{@code ToolSupport.unitState} 的逐字口径）。 */
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

  /** 状态里的 map 切片（{@code ToolSupport.gameMap} 的逐字口径）。 */
  public static GameMap gameMap(SimulationState state) {
    Snapshot snapshot =
        state.module("map").orElseThrow(() -> new IllegalArgumentException("状态里没有 map 模块切片——装配故障"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalArgumentException("map 模块切片不是 MapSnapshot：" + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }
}
