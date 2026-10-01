package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyAddresses;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.spi.RecordCombatHandler;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.SetStateDescriptionHandler;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code simos.army.startCombat} 的<b>纯推导</b>（阶段 D4 / 用户设计 D-009 补裁 + D-010 +
 * D-012，2026-10-02）：从一份 {@link SimulationState} 与参数算出 {@link Plan}——<b>不碰 {@code ToolContext} /
 * {@code CoreSimos}</b>，preview 与 apply 因此共用同一份语义（工具只负责读态、组批、折叠结局）。
 *
 * <p>★★ <b>职责边界（D-010/D-012）</b>：Unit 是状态与数据本体（"进入交战"= 一条 {@code unit.SetStateDescription}
 * 状态链接）；Army 是编排层（建一条 {@code army.RecordCombat} 记录，把参与单位的状态链到 {@code
 * army:combat.<id>}）。两条命令写两个命名空间，单条命令做不到，故这是一个 **app 组合工具**：同 batchId/branch/expectedRevision ⇒ 一批
 * = 一条 revision（原子）。
 *
 * <p>★★ <b>状态键口径（推荐值，本类唯一拼写点）</b>：{@code state="combat"}、{@code
 * address="army:combat.<id>"}；"自定义交战状态"的**自由文本** 放在记录的 {@code kind} 里（"轰城" = {@code kind="轰城"}
 * 的普通交战记录，D-009 明文不另开攻城命令）。
 *
 * <p>★ <b>初始阶段口径</b>：本工具不显式发 {@code initialStage}，由 {@link RecordCombatHandler} 合成 {@code
 * id="start"} / {@code name="初始阶段"} / 参与单位 = 记录级 participants / text = 记录级 text / outcomes
 * 空表。概率表随后用 {@code army.AppendCombatStage} 追加。
 *
 * <p>★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：{@code combatId}/{@code
 * kind}/{@code text} 非空白；{@code hex} 非 null；{@code participants} 至少一个、不重复；记录 id 不得已存在（记录 id
 * 一次性）；每个参与单位必须在 unit 切片里存在（因为要写 {@code unit.SetStateDescription}——不存在单位的链接命令必被单位层拒，这里把它折成前置具名拒）。
 *
 * <p>★ <b>确定性</b>：不碰墙钟（tick 是 base state 的函数）、不用随机量；participants 的输入序原样保留。
 */
final class StartCombatPlan {

  /** 第一条命令类型（建记录；引用 handler 常量，不另抄字面量）。 */
  static final String RECORD_COMBAT_TYPE = RecordCombatHandler.TYPE;

  /** 第二条起的命令类型（逐参与单位链状态）。 */
  static final String SET_STATE_DESCRIPTION_TYPE = SetStateDescriptionHandler.TYPE;

  /**
   * Unit 侧"进入交战"的状态键（固定口径）。
   *
   * <p>★ 为什么不让 kind 当状态键：{@code kind} 是**记录**上的自定义交战状态（自由文本、可任意写），{@code state} 是 Unit 侧**稳定的链接键**
   * ——固定成 {@code "combat"} 才能让"查所有在交战中的单位"只有一个键；kind 仍在记录里逐字保留。若将来要给别的自定义状态开链接，那是新的键、新的批次。
   */
  static final String STATE_KEY = "combat";

  private StartCombatPlan() {}

  /**
   * 推导入口（校验清单见类注）。
   *
   * @param state 读数所在状态（preview/apply 共用同一坐标）
   * @param combatId 交战记录 id（调用方给的短名；不得已存在）
   * @param hex 交战格
   * @param kind 自定义交战状态（自由文本，如"野战"/"轰城"）
   * @param participantIds 参与单位 id（输入序保留；至少一个、不重复、必须存在）
   * @param text 自然语言过程（记录级概述；同时作为初始阶段文本）
   */
  static Plan derive(
      SimulationState state,
      String combatId,
      HexCoord hex,
      String kind,
      List<String> participantIds,
      String text) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(hex, "hex");
    requireNonBlank(combatId, "combatId");
    requireNonBlank(kind, "kind");
    requireNonBlank(text, "text");
    if (participantIds == null) {
      throw new IllegalArgumentException("participants 必填且为 [unitId...] 数组");
    }
    List<UnitId> participants = new ArrayList<>();
    Set<UnitId> seen = new LinkedHashSet<>();
    for (String raw : participantIds) {
      if (raw == null || raw.isBlank()) {
        throw new IllegalArgumentException("participants 的元素必须是非空白 unitId");
      }
      UnitId unit = UnitId.parse(raw);
      if (!seen.add(unit)) {
        throw new IllegalArgumentException("participants 不得重复: " + unit.value());
      }
      participants.add(unit);
    }
    if (participants.isEmpty()) {
      throw new IllegalArgumentException("participants 至少要有 1 个单位（D-009：单方入场也可判交战）");
    }
    CombatRecordId id = CombatRecordId.parse(combatId);
    ArmyData army = ApiViews.armyData(state);
    if (army.combats().containsKey(id)) {
      throw new IllegalArgumentException("交战记录 id 已存在（记录 id 是一次性身份，不覆盖）: " + combatId);
    }
    UnitState units = ToolSupport.unitState(state);
    for (UnitId unit : participants) {
      if (!units.units().containsKey(unit)) {
        throw new IllegalArgumentException("参与单位不存在: " + unit.value());
      }
    }
    return new Plan(
        combatId,
        kind,
        hex,
        participants,
        text,
        state.meta().timestamp().tick(),
        ArmyAddresses.combatCanonical(id));
  }

  /**
   * 一份开战计划（全部字段是状态与参数的纯函数；participants 在构造期冻结）。
   *
   * @param combatId 交战记录 id
   * @param kind 自定义交战状态（自由文本）
   * @param hex 交战格
   * @param participants 参与单位（保序）
   * @param text 自然语言过程
   * @param tick 推导时的世界日
   * @param combatAddress canonical 地址 {@code army:combat.<id>}（Unit 状态链接的目标）
   */
  record Plan(
      String combatId,
      String kind,
      HexCoord hex,
      List<UnitId> participants,
      String text,
      long tick,
      String combatAddress) {

    Plan {
      requireNonBlank(combatId, "combatId");
      requireNonBlank(kind, "kind");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(participants, "participants");
      requireNonBlank(text, "text");
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      requireNonBlank(combatAddress, "combatAddress");
      if (participants.isEmpty()) {
        throw new IllegalArgumentException("participants 至少要有 1 个单位");
      }
      Set<UnitId> seen = new LinkedHashSet<>();
      for (UnitId unit : participants) {
        if (unit == null) {
          throw new IllegalArgumentException("participants 不得含 null");
        }
        if (!seen.add(unit)) {
          throw new IllegalArgumentException("participants 不得重复: " + unit.value());
        }
      }
      participants = List.copyOf(participants);
    }

    /** 参与单位 id 的保序文本表。 */
    List<String> participantValues() {
      List<String> values = new ArrayList<>(participants.size());
      for (UnitId unit : participants) {
        values.add(unit.value());
      }
      return List.copyOf(values);
    }

    /** {@code army.RecordCombat} 载荷（不发 initialStage ⇒ handler 合成 {@code start} 阶段）。 */
    String recordCombatPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", combatId);
      payload.put("kind", kind);
      payload.put("tick", tick);
      payload.put("hex", ToolSupport.hexCoord(hex));
      payload.put("participants", participantValues());
      payload.put("text", text);
      return ToolSupport.json(payload);
    }

    /**
     * 逐参与单位的 {@code unit.SetStateDescription} 载荷（{@code state="combat"} → {@code
     * army:combat.<id>}）。
     */
    List<String> stateDescriptionPayloadsJson() {
      List<String> payloads = new ArrayList<>(participants.size());
      for (UnitId unit : participants) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", unit.value());
        payload.put("state", STATE_KEY);
        payload.put("address", combatAddress);
        payloads.add(ToolSupport.json(payload));
      }
      return List.copyOf(payloads);
    }

    /** 命令类型 + 条数（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<Map<String, Object>> commandCounts() {
      List<Map<String, Object>> counts = new ArrayList<>(2);
      counts.add(commandCount(RECORD_COMBAT_TYPE, 1));
      counts.add(commandCount(SET_STATE_DESCRIPTION_TYPE, participants.size()));
      return List.copyOf(counts);
    }

    private static Map<String, Object> commandCount(String type, int count) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", type);
      row.put("count", count);
      return row;
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }
}
