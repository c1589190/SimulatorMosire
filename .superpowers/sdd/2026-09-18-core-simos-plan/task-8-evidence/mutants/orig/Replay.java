package io.mosire.simos.core.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.info.InfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 重放（spec §6.4）：从**最近的可用 checkpoint** 出发，按父链把中间的 revision 逐个施加，重建出任一坐标的 {@link SimulationState}。
 *
 * <p>★ **它为什么是 checkpoint 纯优化（C18）的兑现处**：checkpoint 只是把"重放到 (b,r) 的结果"提前存下来，
 * **改变不了结果**。因此本类有一对必须成立的等式，也是它的两条核心护栏（R4 / R5）：
 *
 * <ul>
 *   <li>**R4**：从最近 checkpoint 重放 == 从创世全量重放（相等按 {@code equals}，见 {@code StateValueEqualityTest}）；
 *   <li>**R5**：任取一个 target，施加次数 ≤ N（C19 保证父链上每 N 步必有一个可用 checkpoint；**分岔点强制一次**， 这一条才是跨分支也成立的原因）。
 * </ul>
 *
 * <p>★ **codec 表必须注入**（ADR-1 §六）：{@code simos-core} 的 main scope **看不见** {@code MapCodec}/{@code
 * SocialCodec}/{@code UnitCodec}——它们在 test scope（{@code bannedDependencies}
 * 构建期强制）。故本类**不认识任何领域类型**，只按 {@code namespace} 字符串把载荷路由回调用方给的 {@link ModuleCodec}。这与裁定 34 给 {@code
 * CommandBus} 注入 {@code StateLoader} 是同一手法（**injection for testability/separation，不是解环**）。
 *
 * <p>★ **Core 不 cast、不 parse 模块载荷**（C26）：{@code decodeSnapshot} 与 {@code apply} 的 cast 都在**模块自己的
 * codec 实现里**。本类碰模块状态只有两种方式——把文本交给 codec、把 codec 的结果放进 map。
 *
 * <p>★ **与 spec §6.4 伪码的两处实现期校正**（取代说明，均记入 {@code task-8-report}）：
 *
 * <ol>
 *   <li>伪码的循环条件是 {@code while !hasCheckpoint(cur)}，即只看「**应当**有 checkpoint」（C19 的纯函数判定）。而 C18 明说
 *       checkpoint **缺失不回退失败**、要「回退到更早的 checkpoint，最坏从创世重放」。二者合起来要求循环判的是 「**这一坐标的 checkpoint
 *       文件此刻读得出来吗**」⇒ 本实现按**文件可用性**回退，不是按「应当有」回退。
 *   <li>伪码写的是 {@code decodeEnvelope(rev.changeset_json)}，而实际调用是 {@link Timeline#readChangeSet(String)}。
 *       ★ **这不是"列名与内容名不同"的措辞问题，是 Task 6 的一处真偏离**（Task 8 实测后补记）：spec §3.2 把该列定义成
 *       「**信封（C26），模块载荷是其中的一段文本**」——即 Core 只搬**不透明文本**；而 Task 6 落成了 {@code WorldChangeSet}
 *       的整体 JSON + {@code Id.CLASS} 多态类型信息，**Core 于是内省了模块类型**（C26 的原意被破）。
 *       偏离的具体后果在 Task 8 当场炸出来：Core 那台 mapper 不知道模块 codec 把 {@code isEmpty()} 摘出了线格式 ⇒
 *       写出来的档自己读不回（裁定 39 与 {@code TimelineTest.changeSetJsonRoundTripsRealModuleChangeSetsNotJustStandIns}）。
 *       ⇒ **伪码的 {@code decodeEnvelope} 在方向上是对的**，是实现没照它做；本类只是照现状调用，**不在此裁决该列的最终形态**。
 *       那条裁定见台账裁定 39（`带裁定的遗留条目`：载荷改不透明文本会改 {@code WorldChangeSet} 的类型，牵动 Task 4/6/9 三个已关账任务）。
 * </ol>
 */
public final class Replay {

  /** 只服务 {@code info} 段——它是 util 的类型（裁定 38 在 util 装配点上补了 {@code Address} 的键绑定）。 */
  private static final ObjectMapper INFO_MAPPER = SimosObjectMapper.create();

  private final Timeline timeline;
  private final CheckpointStore checkpoints;

  /** {@code namespace → codec}，构造期校验键唯一且非空（同裁定 37 对 {@code CommandRegistry} 的处置）。 */
  private final Map<String, ModuleCodec> codecs;

  /** ★ R4 的差分开关（见四参构造）。生产装配恒为 {@code false}。 */
  private final boolean fromGenesis;

  /**
   * 生产装配形态：**总是**从最近的可用 checkpoint 重放。
   *
   * @param timeline 时间线（唯一真相来源：行、父链、checkpoint「应当有」的判定）
   * @param checkpoints checkpoint 的文件读写（C18：缺失 ⇒ 回退，不失败）
   * @param codecs 各领域模块的状态 codec，必须覆盖信封与变更集里出现的**全部** namespace
   */
  public Replay(Timeline timeline, CheckpointStore checkpoints, Collection<ModuleCodec> codecs) {
    this(timeline, checkpoints, codecs, false);
  }

  /**
   * ★ **四参构造仅供 R4 的差分用例**（Task 8 Step 3），生产装配一律用三参构造。
   *
   * <p>{@code fromGenesis = true} 时**跳过所有中途 checkpoint**，只在父链的根（创世）取 checkpoint——于是 R4 可以拿它 与"从最近
   * checkpoint 重放"对拍，**而不必去改 {@code hasCheckpoint} 的生产判定**（计划 Step 3 明说：改生产代码的判定 会给 R3 的「{@code
   * hasCheckpoint} 与磁盘文件逐条一致」埋雷）。
   *
   * <p>★ 有意做成**构造参数而非公开方法**：它是装配期属性，不该在运行期被逐次选择；把它放在构造上， 调用点必须显式写出意图，而不是在某次调用里悄悄传个 {@code true}。
   *
   * @throws NullPointerException 任一参数为 null
   * @throws IllegalArgumentException {@code codecs} 里有 null、{@code namespace()} 为空、或 namespace 重复
   */
  public Replay(
      Timeline timeline,
      CheckpointStore checkpoints,
      Collection<ModuleCodec> codecs,
      boolean fromGenesis) {
    this.timeline = Objects.requireNonNull(timeline, "timeline");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    Objects.requireNonNull(codecs, "codecs");
    Map<String, ModuleCodec> table = new LinkedHashMap<>();
    for (ModuleCodec codec : codecs) {
      Objects.requireNonNull(codec, "codecs 的元素");
      String namespace = Objects.requireNonNull(codec.namespace(), "codec.namespace()");
      if (namespace.isEmpty()) {
        throw new IllegalArgumentException("codec 的 namespace 不得为空");
      }
      ModuleCodec previous = table.put(namespace, codec);
      if (previous != null) {
        // ★ 静默覆盖会让"路由到哪个 codec"取决于集合迭代序 —— 同一个 target 重放两次可能得到不同结果
        throw new IllegalArgumentException("namespace 重复（路由将失去确定性）: " + namespace);
      }
    }
    this.codecs = Map.copyOf(table);
    this.fromGenesis = fromGenesis;
  }

  /**
   * 重放到 {@code target}：从父链上最近的**可读** checkpoint 出发，把中间的变更集按「祖先 → 后代」逐个施加。
   *
   * @param target 目标坐标；其行**必须**存在（不存在 ⇒ {@link IllegalArgumentException}，不是"重放出空状态"）
   * @return 目标坐标的状态 + **实际施加的变更集个数**（R5 的计数，见 {@link ReplayResult#applyCount()}）
   * @throws IllegalArgumentException {@code target} 的行不在 {@code revisions} 表里
   * @throws IllegalStateException 父链中断、或回退到创世仍无可用 checkpoint（C19 第③项保证创世应当有——真缺了就没有更早的可退）
   * @throws IllegalArgumentException 信封或变更集读不出（C26 的载荷格式问题、裁定 38 的键绑定缺失等）
   */
  public ReplayResult replay(StateRef target) {
    Objects.requireNonNull(target, "target");

    // 目标行先读：它是最终 meta 的来源，也让"坐标根本不存在"与"父链断了"两种错有不同的消息
    RevisionRow targetRow =
        timeline
            .row(target)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "重放目标的坐标不在时间线上（revisions 表没有这一行）: " + describe(target)));

    // ── ① 沿父链往创世方向找到第一个可用 checkpoint ──
    // path 收集"要从 checkpoint 之上逐个施加回去"的坐标，近端在前：[target, parent(target), …]
    List<StateRef> path = new ArrayList<>();
    String envelopeJson;
    StateRef cur = target;
    while (true) {
      Optional<RevisionRow> rowHere = timeline.row(cur);
      if (rowHere.isEmpty()) {
        // 起点不存在或父链中断 —— 两者都是库被绕开 Timeline 写坏，当场炸而不是静默当创世
        throw new IllegalStateException("重放无法继续：行不在库中（起点不存在或父链中断）: " + describe(cur));
      }
      RevisionRow row = rowHere.get();
      boolean isRoot = row.parent().isEmpty();

      // ★ 校正 ①（类注）：判的是「文件此刻读得出来吗」，不是「应当有吗」
      boolean candidate = fromGenesis ? isRoot : timeline.hasCheckpoint(cur);
      if (candidate) {
        Optional<String> json = checkpoints.read(cur);
        if (json.isPresent()) {
          envelopeJson = json.get();
          break;
        }
        // C18：文件缺失 ⇒ 不失败，继续往创世方向回退（checkpoints.read 已记 WARNING）
      }

      path.add(cur);
      if (isRoot) {
        throw new IllegalStateException(
            "重放回退到父链的根仍无可用 checkpoint（C19 第③项保证 (main, 1) 应当有；"
                + "文件缺失且无更早者可退，C18 的回退到此为止）: "
                + describe(cur));
      }
      cur = row.parent().get();
    }

    // ── ② 从 checkpoint 出发，把 path 里的变更集按「祖先 → 后代」施加 ──
    // path 是近端在前，故施加序是它的**逆序**：path 末尾（最靠近 checkpoint 的后代）先施加，path[0]（target）最后
    SimulationState state = decodeCheckpoint(envelopeJson);
    int applyCount = 0;
    for (int i = path.size() - 1; i >= 0; i--) {
      StateRef ref = path.get(i);
      RevisionRow row =
          timeline
              .row(ref)
              .orElseThrow(() -> new IllegalStateException("父链在收集与施加之间被改动（行消失）: " + describe(ref)));
      WorldChangeSet changeset = Timeline.readChangeSet(row.changesetJson());
      state = applyWorld(state, changeset, new StateMeta(ref, row.timestamp()));
      applyCount++;
    }

    // ── ③ 外层 meta 一律以 revisions 表为准 ──
    // path 为空（target 自身就有 checkpoint）时，信封里的 meta **理论上**已等于它；仍然覆盖，是为了让"外层坐标"
    // 只有一个来源（真值表），而不是"来自 checkpoint 文件"——后者会让 checkpoint 成为第二真相来源（违反 C18）。
    StateMeta meta = new StateMeta(target, targetRow.timestamp());
    return new ReplayResult(new SimulationState(meta, state.modules(), state.info()), applyCount);
  }

  /**
   * 把一条 {@link WorldChangeSet} 施加到状态上：**逐模块**路由回各自的 codec（Core 不 cast，C26）。
   *
   * <p>★ 变更集里**没提到**的模块**保持原样**（不重置、不推进坐标）——只有世界真的变了，那个模块的快照坐标才动。 这是"施加是增量的"这一语义的直接表达；也让 R4
   * 与全量重放等价成为可验证的性质。
   */
  private SimulationState applyWorld(
      SimulationState base, WorldChangeSet changeset, StateMeta newMeta) {
    Map<String, Snapshot> modules = new LinkedHashMap<>(base.modules());
    for (Map.Entry<String, ChangeSet> entry : changeset.modules().entrySet()) {
      String namespace = entry.getKey();
      ModuleCodec codec = codecs.get(namespace);
      if (codec == null) {
        throw new IllegalStateException("变更集里的 namespace 没有对应 codec（装配缺项）: " + namespace);
      }
      Snapshot slice =
          base.module(namespace)
              .orElseThrow(
                  () -> new IllegalStateException("变更集要改的模块不在当前状态里（状态与变更集不同源）: " + namespace));
      modules.put(namespace, codec.apply(entry.getValue(), slice, newMeta));
    }
    return new SimulationState(base.meta(), modules, base.info());
  }

  /** 信封 JSON → 状态：模块载荷交回各自 codec，{@code info} 段由 Core 自己解（它是 util 的类型，spec §6.3）。 */
  private SimulationState decodeCheckpoint(String envelopeJson) {
    Envelope.Decoded decoded = Envelope.decode(envelopeJson);
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : decoded.modules().entrySet()) {
      ModuleCodec codec = codecs.get(entry.getKey());
      if (codec == null) {
        throw new IllegalStateException(
            "checkpoint 信封里有未装配 codec 的模块（信封与本实例的 codec 表不同源）: " + entry.getKey());
      }
      modules.put(entry.getKey(), codec.decodeSnapshot(entry.getValue()));
    }
    return new SimulationState(decoded.meta(), modules, readInfo(decoded.infoJson()));
  }

  /**
   * {@code info} 段 → {@link InfoSystem}。
   *
   * <p>★ 落到具体类型 {@link InMemoryInfoSystem} 是**当前唯一的实现**（util 里就它一个）。{@code InfoSystem} 是接口且 util
   * 没有给它的 SPI——真出现第二个实现时，这里要跟着长出编解码口子（或者 Core 干脆不碰它）。**这是接缝，不是终局**， 记在报告的"我未能核实的"里。
   */
  private static InfoSystem readInfo(String infoJson) {
    try {
      return INFO_MAPPER.readValue(infoJson, InMemoryInfoSystem.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "checkpoint 的 info 段读不出（info 是 util 的类型，其 JSON 装配在 SimosObjectMapper；"
              + "Address 作键的绑定见裁定 38）",
          e);
    }
  }

  private static String describe(StateRef ref) {
    return ref.branch().value() + "@" + ref.revision().value();
  }

  /**
   * 一次重放的结果。
   *
   * <p>★ **为什么是 record 而不是实例字段上的 {@code lastReplayApplyCount()}**（取代计划 Produces 行的
   * `lastReplayApplyCount()`；计划 Step 2 给了二选一，这里选前者）：
   *
   * <ul>
   *   <li>实例字段把 {@code Replay} 变成**有状态**的——两次并发重放会互相覆盖计数，而 R5 的用例读到的可能不是自己那次的值。 {@code Replay} 在
   *       Task 13 的装配里是**长生命周期单件**，Task 15 又要上真实并发 ⇒ 这条不是洁癖；
   *   <li>"计数"与"状态"是同一次计算的**两个产物**，绑在一起返回就不存在"忘了先调 replay 再读计数"的用法错误；
   *   <li>M3 Task 12 的教训是**可变静态状态**（计划 Step 2 原话），实例字段只是把它缩小到实例级，形态相同。
   * </ul>
   *
   * @param state 目标坐标的状态
   * @param applyCount 实际施加的变更集个数（= 从 checkpoint 到 target 之间被重放的 revision 数）。 ★ **不是** {@link
   *     ModuleCodec#apply} 的调用次数——一条变更集可能改动多个模块。R5 判的是"重放了多少步"，取的就是这个数。
   */
  public record ReplayResult(SimulationState state, int applyCount) {

    public ReplayResult {
      Objects.requireNonNull(state, "state");
      if (applyCount < 0) {
        throw new IllegalArgumentException("applyCount 不得为负: " + applyCount);
      }
    }
  }
}
