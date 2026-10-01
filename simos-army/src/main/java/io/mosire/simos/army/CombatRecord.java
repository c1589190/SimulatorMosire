package io.mosire.simos.army;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单 tick 单场交战的记录（阶段 D1 落地、阶段 D4 升级 / 用户设计 D-009 补裁 + D-010 + D-012，2026-10-02）： {@code id / kind /
 * tick / hex / participants / text / stages}。
 *
 * <p>★★ <b>{@code kind} = 自定义交战状态（自由文本）</b>：D-009 明文"轰炸城池也是特殊的交战状态，记在 Unit 通用交战关系状态里，不要额外开攻城命令"——
 * 所以"轰城"只是 {@code kind="轰城"} 的一条普通交战记录，没有第二条攻城命令族。{@code text} 是记录级的自然语言概述；每个阶段的自然语言过程与 概率表在 {@link
 * CombatStage#text()} / {@link CombatStage#outcomes()}。
 *
 * <p>★★ <b>不可变口径（D4 选定：阶段可追加/判定，记录 id 不新建覆盖）</b>：D-009 要求"1 tick 交战记录里记录不同阶段"，故**记录内容不是一次写死的**：
 *
 * <ul>
 *   <li>记录 id 仍然是一次性身份——{@code army.RecordCombat} 对已存在的 id **具名拒**，不会静默改写历史；
 *   <li>阶段追加走显式命令 {@code army.AppendCombatStage}、判定走 {@code army.ResolveCombatStage}；两者产出的新记录（同
 *       id、新 stages）由 {@code ArmyChangeSet.between} 落成**新 revision**，旧 revision 与它的阶段原样留在历史里；
 *   <li>即"记录不可被一条新记录覆盖，但可被**具名的阶段命令**演进"——历史不丢，语义不含糊。要更正已写下的阶段内容，本阶段不提供改写命令（清理/修订另批）。
 * </ul>
 *
 * <p>★ <b>本类自身的不变量</b>（全部构造期判）：{@code id}/{@code hex} 非 null；{@code kind}/{@code text} 非空白；{@code
 * tick ≥ 0}；{@code participants} 非空、不重复；{@code stages} 非空、阶段 id 不重复。{@code outcomes} 的表内不变量、{@code
 * selectedOutcomeId} 的引用完整性都由 {@link CombatStage} 判（列表级不变量各自在拥有它的类型上）。
 *
 * <p>★ <b>"不得记在未来"是命令期判据</b>（要世界当前 tick，域类型看不见它，见 {@code RecordCombatHandler}）。
 *
 * <p>★ <b>不做跨表引用完整性</b>（照 {@code ActorData}）：{@code participants} / 损失里的单位**可以**不在当前 unit
 * 切片里——记录写的是历史， 单位可能已被解散/改 id。存在性校验留给需要它的读侧/调用方（工具层的结算会按需判）。
 *
 * <p>★ <b>保序不可变</b>：{@code participants}/{@code stages} 走 {@code List.copyOf}（保序、拒 null），**绝不用
 * {@code Map.copyOf} /{@code Set.copyOf}**：迭代序不是内容的纯函数，字节级往返会漂。
 *
 * <p>★ <b>不背旧档</b>（D-011 / R4）：阶段 D1 的 {@code losses: Map<String,Long>}
 * 字段已删除，且没有兼容构造器——旧字节读不出就让它读不出（世界替换在 D6），不留"双轨"。
 */
public record CombatRecord(
    CombatRecordId id,
    String kind,
    long tick,
    HexCoord hex,
    List<UnitId> participants,
    String text,
    List<CombatStage> stages) {

  public CombatRecord {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("kind 不得为空白（自定义交战状态，例如\"野战\"/\"轰城\"）");
    }
    if (tick < 0) {
      throw new IllegalArgumentException("tick 必须 ≥ 0: " + tick);
    }
    if (hex == null) {
      throw new IllegalArgumentException("hex 不得为 null");
    }
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text 不得为空白（交战过程/结局必须写出来）");
    }
    if (participants == null) {
      throw new IllegalArgumentException("participants 不得为 null");
    }
    Set<UnitId> seenParticipants = new LinkedHashSet<>();
    for (UnitId unit : participants) {
      if (unit == null) {
        throw new IllegalArgumentException("participants 不得含 null");
      }
      if (!seenParticipants.add(unit)) {
        throw new IllegalArgumentException("participants 不得重复: " + unit.value());
      }
    }
    if (seenParticipants.isEmpty()) {
      throw new IllegalArgumentException("participants 至少要有 1 个单位（D-009：单方入场也可判交战）");
    }
    participants = List.copyOf(participants); // ★ 冻在赋值处（保序；null 已逐项查过）
    if (stages == null) {
      throw new IllegalArgumentException("stages 不得为 null（至少要有初始阶段）");
    }
    Set<CombatStageId> stageIds = new LinkedHashSet<>();
    for (CombatStage stage : stages) {
      if (stage == null) {
        throw new IllegalArgumentException("stages 不得含 null");
      }
      if (!stageIds.add(stage.id())) {
        throw new IllegalArgumentException("stages 不得重复阶段 id: " + stage.id().value());
      }
    }
    if (stageIds.isEmpty()) {
      throw new IllegalArgumentException("stages 至少要有 1 个阶段（RecordCombat 会落初始阶段）");
    }
    stages = List.copyOf(stages);
  }

  /** 追加一个阶段：阶段 id 必须尚未出现（重复 ⇒ 具名拒，不静默覆盖历史阶段）。 */
  public CombatRecord withAppendedStage(CombatStage stage) {
    if (stage == null) {
      throw new IllegalArgumentException("stage 不得为 null");
    }
    for (CombatStage existing : stages) {
      if (existing.id().equals(stage.id())) {
        throw new IllegalArgumentException("阶段 id 已存在（阶段按 id 不可变，不覆盖）: " + stage.id().value());
      }
    }
    List<CombatStage> next = new ArrayList<>(stages);
    next.add(stage);
    return new CombatRecord(id, kind, tick, hex, participants, text, next);
  }

  /**
   * 用同 id 的新阶段**整条替换**（判定命令用）：阶段 id 必须已存在；这是"记录演进"的内部写口，只由 {@code army.ResolveCombatStage} 的
   * handler 调用。
   */
  public CombatRecord withReplacedStage(CombatStage stage) {
    if (stage == null) {
      throw new IllegalArgumentException("stage 不得为 null");
    }
    List<CombatStage> next = new ArrayList<>(stages);
    for (int i = 0; i < next.size(); i++) {
      if (next.get(i).id().equals(stage.id())) {
        next.set(i, stage);
        return new CombatRecord(id, kind, tick, hex, participants, text, next);
      }
    }
    throw new IllegalArgumentException("阶段不存在: " + stage.id().value());
  }

  /** 是否所有阶段都已判定（结算工具据此决定"结束/清除状态链接"）。 */
  public boolean allStagesResolved() {
    for (CombatStage stage : stages) {
      if (!stage.resolved()) {
        return false;
      }
    }
    return true;
  }
}
