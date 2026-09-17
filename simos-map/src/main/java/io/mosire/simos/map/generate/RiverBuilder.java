package io.mosire.simos.map.generate;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.Pathway;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.util.state.FieldDelta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * 自动河流：按海拔从高到低生成水系，产出 {@link MapChangeSet}（**不是新 GameMap** —— 编辑走铁律 2 的 Command → ChangeSet →
 * Revision 路径）。
 *
 * <p>★ GSimulator 没有这个功能（"河"只是边上的一个标签，只读不生成）。本实现复用 §五 的连通性系统：一条河就是一条 {@link Pathway}（{@code groupId
 * = "river"}），**单条可寻址**（{@link PathwayId}），**分支是独立的线**（分支点即端点，spec §5.2）。
 *
 * <p>算法两步：
 *
 * <ol>
 *   <li>**流网络**：每个**陆地**格（terrain 不是 {@code "ocean"} —— ★ 判水按地形、不按高度带，Task 10 实测水体高度可 ≥
 *       0.30）至多一条出边，目标从**严格更低**的**在图纸**邻格里选（{@link HexCoord#neighbors} 不过滤图纸，须逐格查 {@code
 *       map.hexes()}）；无候选 ⇒ 该格是终点（入海 / 图缘 / 内陆洼地）；多候选 ⇒ 先按 {@link HexCoord} 自然序排序、再由 {@code (seed,
 *       本格)} 派生的 {@link Random} 选一个（算法有规范 ⇒ 跨 JVM 可复现）。海洋格**永不**有出边 —— 水是终点，不"流"。出边必然降高度 ⇒ 无环 ⇒
 *       全图是森林（每个分量是一棵以终点为根的入树）。
 *   <li>**切链**：把森林的边集切成极大简单链，每条链是一个 {@link Pathway}。**边的顺序 = 流向（上游 → 下游）** ⇒ {@link
 *       Pathway#start()} 是上游端、{@link Pathway#end()} 是下游端（长度 1 的链例外：{@code chainHead()}
 *       取规范序较小者，一条边无所谓方向）。链只在"过路格"（恰一条入边 + 一条出边）内部延伸；**源**（零入）、**汇**（零出）、 **分支点**（≥ 2 入，度 ≥ 3）都是端点。
 * </ol>
 *
 * <p>★ **度恰为 2 的汇点也切开**（两入零出）：按无向度数它该是链的内部格，但两条入边在那里**相向汇合**，链无法按流向定向 —— 与"边的顺序 =
 * 流向"矛盾。切成两条各自以它为下游端点的链，每条链仍是严格降高度的极大有向路径，边集划分不受影响。
 *
 * <p>★ **两条 Pathway 不共用任何一条边**（链是边集的划分），每条链非空。
 *
 * <p>★ {@link PathwayId} = {@code "river-" + seed + "-" + n}，{@code n} 是链的发现序（spec §5.2「生成期由
 * (generationSeed, 序号) 确定性派生」）：有边的格按 {@link HexCoord} 自然序、每格的未用边按对端格自然序，沿链走到另一端后依次编号 ⇒ 同种子同图同 ID。
 *
 * <p>★ 变更集形态：7 个组件里**只有 {@code pathways} 与 {@code edges} 是 {@link FieldDelta.Upsert}**，其余 5 个
 * {@code Unchanged}；无河（全平图 / 空图 / 无陆地）时 7 个全 {@code Unchanged}（{@code Upsert} 构造期拒空）。{@code edges}
 * 的 key 是 {@link EdgeRef#toString()}，值 = 只以该 PathwayId 为键的 {@link EdgeTags}（props 留空 —— ★ **别**把
 * {@code groupId} 塞进 props：spec §5.4 拆的就是组定义 / 线的实例 / 边上标注这三层）。
 *
 * <p>★ **不 upsert {@code PathwayGroup("river")}**：组定义由 Command / 上层供给（M2 台账挂起项），"有河才注册组"是规格里没有的
 * 条件规则。
 *
 * <p>★ **无阈值**：不做"汇流量 ≥ 阈值才算河"的过滤（规格未给阈值，不发明魔数）⇒ 每个有出边的陆地格都排入某条链 ⇒ 生成图上水系是**密集**的。将来若要稀疏化，阈值应作为
 * {@link GenerationSpec} 的参数出现。
 */
public final class RiverBuilder {

  /** 水体地形的 key。判水按地形、不按高度带（同 {@code MapGenerator.OCEAN} 的形制与理由）。 */
  private static final String OCEAN = "ocean";

  /** 水系组的 id。 */
  private static final String RIVER_GROUP = "river";

  /**
   * 随机源的 seed 混入乘子（大奇数，splitmix64 的黄金比乘子）。奇数 ⇒ 不同 seed 不折叠到同一 stream；偶数会把 seed
   * 的最低位整个乘没。任意常数均可（换一个只是换一张同样合法的河网），故不进 {@link GenerationSpec}。
   */
  private static final long SEED_SALT = 0x9E3779B97F4A7C15L;

  /** q 轴的混入乘子（大奇数，xxhash 的 64 位质数）。与 {@link #SEED_SALT} 的取舍同理。 */
  private static final long Q_SALT = 0xBF58476D1CE4E5B9L;

  private RiverBuilder() {}

  /**
   * 从 map 的海拔生成水系，返回新的 {@link MapChangeSet}。
   *
   * <p>★ 确定性：每格的出边只由 {@code (seed, 本格)} 决定（**不是**每条河一个 RNG 沿途抽 —— 否则同一格在不同河里会有不同出边， 网络就不是良定义的函数图）⇒
   * **同 seed 同图 ⇒ 同变更集**。{@code seed} 同时进 {@link PathwayId}（见类注释）。
   *
   * <p>★ 不改入参：只读 {@code map}，产出全部走变更集。
   *
   * @param map 现图（只读；高度与地形取自 {@code hexes}）
   * @param seed 水系种子（随机源与 ID 各用一次，见类注释）
   * @return 只有 {@code pathways} 与 {@code edges} 可能非 {@code Unchanged} 的变更集
   */
  public static MapChangeSet build(GameMap map, long seed) {
    Objects.requireNonNull(map, "map");
    Map<HexCoord, HexCoord> out = new LinkedHashMap<>(); // 流向：每陆地格至多一条出边
    Map<HexCoord, List<HexCoord>> in = new LinkedHashMap<>(); // 汇入：目标格 → 上游格（按自然序追加）
    // ★ 逐格按自然序处理：out/in 的遍历序因此是确定的，发现序（ID 的 n）不依赖哈希序（Task 5 的教训）。
    for (HexCoord at : map.hexes().keySet().stream().sorted().toList()) {
      if (map.hexes().get(at).terrain().equals(OCEAN)) {
        continue; // 海洋格永不流
      }
      HexCoord to = strictlyLowerNeighbor(map, at, seed);
      if (to == null) {
        continue; // 终点：入海 / 图缘 / 内陆洼地
      }
      out.put(at, to);
      in.computeIfAbsent(to, k -> new ArrayList<>()).add(at);
    }

    Map<String, Pathway> pathwaysUp = new LinkedHashMap<>(); // 按 ID 发现序
    Map<String, EdgeTags> edgesUp = new LinkedHashMap<>(); // 按发现序（链内按流向）
    Set<EdgeRef> unused = new LinkedHashSet<>();
    for (Map.Entry<HexCoord, HexCoord> flow : out.entrySet()) {
      unused.add(new EdgeRef(flow.getKey(), flow.getValue()));
    }
    int n = 0;
    for (HexCoord h : map.hexes().keySet().stream().sorted().toList()) {
      for (EdgeRef e : incident(h, out, in)) {
        if (!unused.contains(e)) {
          continue; // 已随早先的链消费
        }
        HexCoord other = e.a().equals(h) ? e.b() : e.a();
        // 触发边定向：h 的出边恰指向对端 ⇒ 从 h 起；否则对端流入 h，从对端起。
        HexCoord from = out.get(h) != null && out.get(h).equals(other) ? h : other;
        n++;
        emitChain(from, out, in, unused, seed, n, pathwaysUp, edgesUp);
      }
    }
    return new MapChangeSet(
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        new FieldDelta.Unchanged<>(),
        upsertOrUnchanged(pathwaysUp),
        new FieldDelta.Unchanged<>(),
        upsertOrUnchanged(edgesUp));
  }

  /**
   * 一个陆地格的出边：在图纸六邻里挑严格更低者；无则 {@code null}（终点）。
   *
   * <p>★ 候选先按 {@link HexCoord} 自然序排序再 {@code nextInt} —— 否则结果依赖 {@code neighbors()} 之外的迭代序（Task 5
   * 的哈希序教训）。海洋格是合法的落水目标（水往低处流，海更低就入海）。
   */
  private static HexCoord strictlyLowerNeighbor(GameMap map, HexCoord at, long seed) {
    double height = map.hexes().get(at).height();
    List<HexCoord> candidates = new ArrayList<>();
    for (HexCoord nb : at.neighbors()) {
      HexCell cell = map.hexes().get(nb);
      if (cell != null && cell.height() < height) {
        candidates.add(nb);
      }
    }
    if (candidates.isEmpty()) {
      return null;
    }
    candidates.sort(HexCoord::compareTo);
    return candidates.get(rngFor(seed, at).nextInt(candidates.size()));
  }

  /**
   * 每格的专属随机源：{@code (seed, 本格)} 的纯函数（见类注释——不是每条河一个 RNG）。
   *
   * <p>{@link Random} 的 LCG 算法由 Java 规范钉死 ⇒ 跨 JVM 同种子同序列（不用 {@code ThreadLocalRandom} / {@code
   * SecureRandom}——前者无 seed、后者非确定用途）。
   */
  private static Random rngFor(long seed, HexCoord at) {
    return new Random(seed * SEED_SALT + at.q() * Q_SALT + at.r());
  }

  /** h 上的未定向邻边清单：出边（若有）+ 全部入边，按**对端格自然序**排序（发现序的次级键）。 */
  private static List<EdgeRef> incident(
      HexCoord h, Map<HexCoord, HexCoord> out, Map<HexCoord, List<HexCoord>> in) {
    List<EdgeRef> edges = new ArrayList<>();
    if (out.get(h) != null) {
      edges.add(new EdgeRef(h, out.get(h)));
    }
    for (HexCoord upstream : in.getOrDefault(h, List.of())) {
      edges.add(new EdgeRef(upstream, h));
    }
    edges.sort(
        (x, y) -> (x.a().equals(h) ? x.b() : x.a()).compareTo(y.a().equals(h) ? y.b() : y.a()));
    return edges;
  }

  /**
   * 发出一条链：从触发边回溯到上游端（源或分支点），再沿出边走到下游端（汇或分支点），沿途消费边、记 ID。
   *
   * <p>★ 链只在过路格（一入一出）内部延伸；分支点（≥ 2 入）是下游端点（它的出边属于**下一条**链）——"分支点即端点"。 高度沿出边严格下降 ⇒ 两个方向都必然终止。
   */
  private static void emitChain(
      HexCoord trigger,
      Map<HexCoord, HexCoord> out,
      Map<HexCoord, List<HexCoord>> in,
      Set<EdgeRef> unused,
      long seed,
      int n,
      Map<String, Pathway> pathwaysUp,
      Map<String, EdgeTags> edgesUp) {
    HexCoord start = trigger;
    while (inDegree(in, start) == 1) { // 过路格：入边与出边同链，继续上溯
      start = in.get(start).get(0);
    }
    List<EdgeRef> chain = new ArrayList<>();
    HexCoord cur = start;
    while (true) {
      HexCoord next = out.get(cur);
      if (next == null) {
        break; // 汇点：入海 / 图缘 / 内陆洼地
      }
      chain.add(new EdgeRef(cur, next));
      if (inDegree(in, next) >= 2) {
        break; // 分支点即端点：它的出边开新链
      }
      cur = next;
    }
    PathwayId id = new PathwayId("river-" + seed + "-" + n);
    pathwaysUp.put(id.value(), new Pathway(id, null, RIVER_GROUP, chain, Map.of()));
    for (EdgeRef e : chain) {
      unused.remove(e);
      edgesUp.put(e.toString(), new EdgeTags(Map.of(id.value(), Map.of())));
    }
  }

  private static int inDegree(Map<HexCoord, List<HexCoord>> in, HexCoord at) {
    return in.getOrDefault(at, List.of()).size();
  }

  /** 空 ⇒ {@code Unchanged}（{@code Upsert} 构造期拒空）；非空 ⇒ 按插入序的 {@code Upsert}。 */
  private static <T> FieldDelta<T> upsertOrUnchanged(Map<String, T> entries) {
    return entries.isEmpty() ? new FieldDelta.Unchanged<>() : new FieldDelta.Upsert<>(entries);
  }
}
