package io.mosire.simos.unit.move;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.Unit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** A*（R8）：绕行最优性、Dijkstra 对拍、决定论（含对称夹具）、边界与不可达。 */
class PathFinderTest {

  /** {@code h ≡ 0} 的包装 ⇒ 同一份搜索退化成 Dijkstra，用来对拍"启发没有破坏最优性"。 */
  private record NoHeuristic(MovementCost delegate) implements MovementCost {
    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      return delegate.costMillis(from, to, unit, map);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return 0L;
    }
  }

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final HexCoord H20 = new HexCoord(2, 0);
  private static final HexCoord H11 = new HexCoord(1, 1);

  /**
   * ★ R-9-a 取代：计划夹具的 {@code H21 = (2,1)} 由本格取代——计划的绕行 {@code H00→H11(1,1)→H21→H20} 第一步就不相邻（{@code
   * distanceTo((0,0),(1,1)) = 2}），几何不成立；最小校正后 绕行 = {@code H00→H01(0,1)→H11(1,1)→H20}，三步全部 {@code
   * distanceTo == 1}（已核），走廊仍是 5 格。
   */
  private static final HexCoord H01 = new HexCoord(0, 1);

  /**
   * ★ R-9-a 数字取代说明：{@code MoveFixture.unit()} 的 {@code mobilityPerMille = 500}（Task 8 冻结夹具，
   * 本测试不改它）⇒ {@code costMillis = moveCost × 500}（平坦 500、山 5000）。计划写的"11000 / 3000"按 ‰1000
   * 推的，错位。从冻结规则重算：
   *
   * <ul>
   *   <li>直线 {@code H00→H10(山)→H20 = 5000 + 500 = 5500} 毫；
   *   <li>绕行 {@code H00→H01→H11→H20}（三格平地）{@code = 3 × 500 = 1500} 毫。
   * </ul>
   *
   * A* 必须选绕行。
   */
  private static GameMap detourMap() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H00, new HexCell("flat", 0.5));
    hexes.put(H10, new HexCell("hill", 0.5));
    hexes.put(H20, new HexCell("flat", 0.5));
    hexes.put(H11, new HexCell("flat", 0.5));
    hexes.put(H01, new HexCell("flat", 0.5));
    return new GameMap(
        hexes,
        Map.of(),
        Map.of(),
        detourTerrainTypes(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static Map<String, TerrainType> detourTerrainTypes() {
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put("flat", new TerrainType("flat", "平地", "#336699", 0.0, 1.0, 0, 0, 0, 1, "夹具"));
    terrainTypes.put("hill", new TerrainType("hill", "丘陵", "#4477AA", 0.0, 1.0, 0, 0, 0, 10, "夹具"));
    return terrainTypes;
  }

  /**
   * 对称夹具（R-9-c / 计划 m2）：从 {@code (0,0)} 到 {@code (2,-1)} 有**两条等成本路径**——经 {@code (1,-1)} 与经 {@code
   * (1,0)}，都是两格平地（{@code 2 × 500 = 1000} 毫）。中途两格的 {@code f}、{@code h}、{@code q} 全相等，只有 {@code r}
   * 能分出先后（升序 ⇒ {@code -1} 先弹）⇒ 全序 {@code (f,h,q,r)} 把输出钉成 {@code [(0,0), (1,-1), (2,-1)]}。
   */
  private static GameMap symmetricMap() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H00, new HexCell("flat", 0.5));
    hexes.put(new HexCoord(1, 0), new HexCell("flat", 0.5));
    hexes.put(new HexCoord(1, -1), new HexCell("flat", 0.5));
    hexes.put(new HexCoord(2, -1), new HexCell("flat", 0.5));
    return new GameMap(
        hexes,
        Map.of(),
        Map.of(),
        detourTerrainTypes(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static long pathCost(MovementCost cost, GameMap map, Unit unit, List<HexCoord> path) {
    long total = 0;
    for (int i = 0; i + 1 < path.size(); i++) {
      total += cost.costMillis(path.get(i), path.get(i + 1), unit, map).orElseThrow();
    }
    return total;
  }

  @Test
  void aStarDetoursAroundExpensiveTerrain() {
    GameMap map = detourMap();
    Optional<List<HexCoord>> path =
        PathFinder.findPath(map, H00, H20, MoveFixture.unit(), TerrainMovementCost.INSTANCE);
    assertThat(path).isPresent();
    assertThat(pathCost(TerrainMovementCost.INSTANCE, map, MoveFixture.unit(), path.get()))
        .as("绕行三格平地：1500 毫（直线要 5500）")
        .isEqualTo(1500L);
    assertThat(path.get()).as("首尾钉住，且走的是平地绕行而非山口").containsExactly(H00, H01, H11, H20);
  }

  @Test
  void matchesDijkstraOnCost() {
    GameMap map = detourMap();
    Unit unit = MoveFixture.unit();
    MovementCost astar = TerrainMovementCost.INSTANCE;

    List<HexCoord> a = PathFinder.findPath(map, H00, H20, unit, astar).orElseThrow();
    List<HexCoord> d =
        PathFinder.findPath(map, H00, H20, unit, new NoHeuristic(astar)).orElseThrow();

    assertThat(pathCost(astar, map, unit, a))
        .as("启发函数不得破坏最优性：两条路径的成本必须相等")
        .isEqualTo(pathCost(astar, map, unit, d));
  }

  @Test
  void sameInputTwiceGivesTheSamePath() {
    GameMap map = detourMap();
    Unit unit = MoveFixture.unit();
    List<HexCoord> first =
        PathFinder.findPath(map, H00, H20, unit, TerrainMovementCost.INSTANCE).orElseThrow();
    for (int i = 0; i < 20; i++) {
      assertThat(
              PathFinder.findPath(map, H00, H20, unit, TerrainMovementCost.INSTANCE).orElseThrow())
          .as("第 %d 次重跑", i)
          .isEqualTo(first);
    }

    // 对称夹具：等成本平局必须被 (f,h,q,r) 全序钉死成同一条路径（r 升序 ⇒ (1,-1) 先弹）。
    GameMap sym = symmetricMap();
    HexCoord goal = new HexCoord(2, -1);
    List<HexCoord> symFirst =
        PathFinder.findPath(sym, H00, goal, unit, TerrainMovementCost.INSTANCE).orElseThrow();
    assertThat(symFirst)
        .as("对称夹具的全序输出（决定论不只靠堆实现的稳定性，还靠平局项）")
        .containsExactly(H00, new HexCoord(1, -1), goal);
    for (int i = 0; i < 20; i++) {
      assertThat(
              PathFinder.findPath(sym, H00, goal, unit, TerrainMovementCost.INSTANCE).orElseThrow())
          .as("对称夹具第 %d 次重跑", i)
          .isEqualTo(symFirst);
    }
  }

  @Test
  void startEqualsGoalIsTheSingleHexPath() {
    assertThat(
            PathFinder.findPath(
                detourMap(), H00, H00, MoveFixture.unit(), TerrainMovementCost.INSTANCE))
        .contains(List.of(H00));
  }

  @Test
  void hexesOutsideTheMapAreEmpty() {
    assertThat(
            PathFinder.findPath(
                detourMap(),
                new HexCoord(9, 9),
                H20,
                MoveFixture.unit(),
                TerrainMovementCost.INSTANCE))
        .isEmpty();
    assertThat(
            PathFinder.findPath(
                detourMap(),
                H00,
                new HexCoord(9, 9),
                MoveFixture.unit(),
                TerrainMovementCost.INSTANCE))
        .isEmpty();
  }

  @Test
  void unreachableGoalIsEmpty() {
    // ★ 比计划原形更强：两格都在图上、互不相邻（distanceTo = 2）⇒ 真正走"搜索穷尽"的不可达路径。
    //   计划的"只留 H00"会让 goal 缺席图，退化成"goal 不在图"的前置检查，根本不进搜索循环。
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H00, new HexCell("flat", 0.5));
    hexes.put(H20, new HexCell("flat", 0.5));
    GameMap islands =
        new GameMap(
            hexes,
            Map.of(),
            Map.of(),
            detourTerrainTypes(),
            Map.of(),
            Map.of(),
            Map.of(),
            GenerationSpec.defaults(0L));
    assertThat(
            PathFinder.findPath(
                islands, H00, H20, MoveFixture.unit(), TerrainMovementCost.INSTANCE))
        .isEmpty();
  }
}
