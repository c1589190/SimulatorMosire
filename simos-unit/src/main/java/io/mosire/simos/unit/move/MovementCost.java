package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import java.util.OptionalLong;

/**
 * 移动成本的**唯一抽象**（M3 spec §4.3）：从 {@code from} 踏入 {@code to} 要付多少毫 MP。
 *
 * <p>★ **格式/河流修正的扩展点就是本接口**：v1 只有 {@code TerrainMovementCost}。成本公式的顺序（地形 → 机动性 → 道路 → 河流）已在 spec C6
 * 冻结，**v1 的后两步是恒等，不为恒等写空操作代码**。
 *
 * <p>★ {@link #minStepCostMillis} 是 A\* 启发函数的下界来源 —— **启发与成本出自同一实现**（总纲的关键约束）： 任一单步成本 ≥ 下界 ⇒
 * 启发可采纳且一致，首次弹出即最优。
 */
public interface MovementCost {

  /** 从 {@code from} 踏入 {@code to} 的毫 MP 成本；不可通行（或 {@code to} 不在图上）⇒ 空。 */
  OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map);

  /** 本实现给出的**单步成本下界**（毫 MP）。无法给出下界时返回 0（退化为 Dijkstra，仍正确）。 */
  long minStepCostMillis(Unit unit, GameMap map);
}
