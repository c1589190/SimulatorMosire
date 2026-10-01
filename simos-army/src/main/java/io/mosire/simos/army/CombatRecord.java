package io.mosire.simos.army;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单 tick 单场交战的记录（阶段 D1 / 用户设计 D-012，2026-10-02）：**不可变历史**——已存在的 id 不再覆盖（由命令层拒）。
 *
 * <p>★ <b>字段</b>（D-012 待补细节的最小集）：{@code id}（稳定身份）、{@code tick}（世界日）、{@code hex}（交战格）、{@code
 * participants}（参与单位 id，保序、至少一个、不重复）、{@code text}（自然语言过程/结局，非空白）、{@code losses}（可选损失， {@code 自然语义键
 * → 非负数量}；缺省空表）。
 *
 * <p>★★ <b>为什么 losses 是 {@code Map<String,Long>} 而不是类型化的伤亡结构</b>：D-006 的人力/装备表改造排在后续阶段，本阶段若在这里引入
 * {@code 单位 → 资源目录} 的类型化依赖，会把"记录型切片"过早绑到尚未裁决的键空间上。自然语义键（如 {@code "u-1:人员"}）是**有意的最保守形态**：
 * 不新增模块依赖、不做跨表引用完整性（见下）。
 *
 * <p>★★ <b>不做跨表引用完整性</b>（照 {@code ActorData} 的"表与表之间没有引用完整性约束"）：{@code participants} 里的单位
 * **可以**不在当前 unit 切片里——记录写的是**历史**（单位可能已被解散/改 id），拿"现在必须存在"当判据会把合法历史挡在门外。存在性校验留给需要它的读侧/调用方。
 *
 * <p>★ <b>保序不可变</b>：{@code participants} 走 {@code List.copyOf}（保序、拒 null）、{@code losses} 走 {@code
 * LinkedHashMap} + 赋值处 {@code Collections.unmodifiableMap}（**绝不用 {@code
 * Map.copyOf}**：迭代序不是内容的纯函数，字节级往返会漂）。
 *
 * <p>★ {@code tick ≥ 0} 在这里判；"不得记在未来"是**命令期**判据（要世界当前 tick，域类型看不见它，见 {@code RecordCombatHandler}）。
 */
public record CombatRecord(
    CombatRecordId id,
    long tick,
    HexCoord hex,
    List<UnitId> participants,
    String text,
    Map<String, Long> losses) {

  public CombatRecord {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
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
    Set<UnitId> seen = new LinkedHashSet<>();
    for (UnitId unit : participants) {
      if (unit == null) {
        throw new IllegalArgumentException("participants 不得含 null");
      }
      if (!seen.add(unit)) {
        throw new IllegalArgumentException("participants 不得重复: " + unit);
      }
    }
    if (seen.isEmpty()) {
      throw new IllegalArgumentException("participants 至少要有 1 个单位（D-009：单方入场也可判交战）");
    }
    participants = List.copyOf(participants); // ★ 冻在赋值处（保序；null 已逐项查过）
    if (losses == null) {
      losses = Map.of(); // ★ 可选损失：缺省/缺键 = 空表
    }
    Map<String, Long> lossesCopy = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : losses.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("losses 的键（自然语义标签）不得空白");
      }
      if (entry.getValue() == null || entry.getValue() < 0L) {
        throw new IllegalArgumentException("losses 的值（损失量）必须 ≥ 0: " + entry.getKey());
      }
      lossesCopy.put(entry.getKey(), entry.getValue());
    }
    losses = Collections.unmodifiableMap(lossesCopy); // ★ 冻在赋值处
  }
}
