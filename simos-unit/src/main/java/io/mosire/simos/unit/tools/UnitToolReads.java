package io.mosire.simos.unit.tools;

import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Map;

/**
 * 单位工具共用的几件小事：**查询坐标**（载荷 → 值参）、**unit 切片取用**、**允许面切片取用**（map / social）、 **两个稳定拼写点**（人口家户 id、单位
 * canonical 地址）。
 *
 * <p>★ <b>为什么坐标是值参而不是宿主的查询对象</b>：宿主（app）的 {@code QueryTarget} 是 app 类型，进不了 {@code simos-unit} ⇒
 * 工具只把载荷里的 {@code branch}/{@code revision} 解析成 util 类型，交给注入的 {@link ToolReadPort}——head
 * 解析、创世等待、读缓存都在宿主实现里（口径逐条不变）。
 *
 * <p>★★ <b>为什么切片取用写在域内、而不注入</b>：unit 域**结构上就够得着** unit/map/social 三片（依赖面
 * util/map/social-api/social，enforcer 强制），"从 state 里按 namespace 取快照"不是跨模块契约、也不是第二份业务规则
 * ——它是**机械提取**。与 B3 试点 {@code simos-map} 的 {@code MapToolReads.gameMap}、第二域 army 的 {@code
 * ArmyToolReads} 同一条处置。 ★ 但**异常类型与文案逐字照抄**既有唯一实现（{@code
 * ToolSupport.unitState/gameMap/socialData}）：这些提取只在**装配故障** （状态里没有该切片 /
 * 类型不对）时发出，而那三句话今天已经出现在工具结果与测试里 ⇒ 搬迁不许改一个字（I-G1）。
 *
 * <p>★★ <b>本类为什么是 public（不是包内可见）</b>：unit 的读工具在 {@code unit.tools.read}、写工具在 {@code
 * unit.tools.write}，两者都要用同一份提取；且 {@link #householdIdFor} / {@link #unitAddress} 是**跨批次共享的稳定拼写点**
 * （仍在 app 的 gov 族工具与 army 的 {@code FormatUnitTool} 适配器都要调它）——包内可见会迫使第二份出现。
 */
public final class UnitToolReads {

  /** 单位人口家户 id 的前缀（{@link #householdIdFor} 是它唯一的拼写点）。 */
  public static final String HOUSEHOLD_ID_PREFIX = "hh-unit:";

  private UnitToolReads() {}

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

  /** 状态里的 map 切片（{@code ToolSupport.gameMap} 的逐字口径）。 */
  public static GameMap gameMap(SimulationState state) {
    Snapshot snapshot =
        state.module("map").orElseThrow(() -> new IllegalArgumentException("状态里没有 map 模块切片——装配故障"));
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
   * 单位人口家户的稳定 id（唯一拼写点）：{@code hh-unit:<unitId>}。
   *
   * <p>★★ <b>它为什么是 public 且住在域内</b>：这条形状被 {@code raiseUnit} / {@code spawnArmy} / {@code
   * GovWorldBootstrap} / 创世播种三件套共同使用（迁移前它住在 app 的 {@code RaiseUnitPlan} 里，包内可见）；下放后**唯一拼写点随语义一起进
   * unit**， 仍在 app 的调用方改为调本方法 ⇒ 全仓仍然只有一处拼法（多拼一处会静默指错家户，没有任何异常）。
   */
  public static String householdIdFor(String unitId) {
    return HOUSEHOLD_ID_PREFIX + unitId;
  }

  /**
   * 单位 canonical 地址（{@code unit:<unitId>}）：只经 {@link Address#parse} → {@link Address#canonical()}（与
   * {@code RejectDirectiveTool} 同款）——行动记录地址的唯一拼写点，**不手拼第二套格式**。
   */
  public static String unitAddress(String unitId) {
    if (unitId == null) {
      throw new NullPointerException("unitId");
    }
    return Address.parse(ToolResources.UNIT_NAMESPACE + ":" + unitId).canonical();
  }

  /** 格的可读文本（拒因与行动记录共用；格式不与任何资源路径语法绑定）。 */
  public static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }
}
