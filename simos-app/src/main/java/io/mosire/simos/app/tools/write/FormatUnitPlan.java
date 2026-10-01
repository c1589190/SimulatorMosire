package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.SetCompositionHandler;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * ★★ {@code simos.army.formatUnit} 的<b>纯推导</b>（阶段 D3a，2026-10-02 / D-010 + 补裁 R2 的<b>最小环</b>）： 从一份
 * {@link SimulationState} 与目标格式算出新的有序人力/装备表——<b>不碰 {@code ToolContext} / {@code CoreSimos}</b>，
 * preview 与 apply 因此共用同一份语义（工具只负责读态、组批、折叠结局）。
 *
 * <p>★★ <b>职责边界（D-010/R2）</b>：Unit 只提供"状态与变动原语"；本类属于 <b>Army 侧的整表复写工具链</b>——按给定格式 （每条 = {@code
 * type} + 基准量 + 可选随机化幅度/上下限）算出目标表，再经 {@code unit.SetComposition} **整表复写**。 战后结算 / 概率表 / 投骰 /
 * 阶段编排是这条链的更大形态，归后续阶段；本阶段只落"整表复写 + 数量随机化"这一最小环。
 *
 * <p>★★ <b>随机化必须可复现</b>（任务书硬要求）：
 *
 * <ul>
 *   <li>调用方给了 {@code seed} ⇒ 用它；没给 ⇒ 从 {@code unitId + baseRevision + tick + 目标格式（按序）} 做 FNV-1a
 *       派生一个**确定**种子（同状态同参数 ⇒ 同种子）；
 *   <li>随机源是 {@link Random}，种子显式给出 ⇒ 与 JVM 的协议一致、同种子同序；**不用 {@code Math.random()}**；
 *   <li>抽取顺序固定：先按 {@code manpower} 规格表序，再按 {@code equipment} 规格表序；{@code jitterPerMille == 0} 或幅度算出
 *       0 的条目不消耗随机数（口径写在这里，测试阶段照此钉值）；
 *   <li>生效种子（以及是否显式提供）进 {@code sd.PutInfo.value} 的审计记录，落在同一批的 revision 里。
 * </ul>
 *
 * <p>★ <b>确定性边界</b>：随机化只发生在 <b>apply 之前</b>的推导里，且推导是状态+参数的纯函数；命令载荷里落的是**算好的绝对值**， 重放同一份 revision
 * 不再掷骰（回放逐值相等）。
 *
 * <p>★ <b>本阶段不做</b>：概率表、按结局选分支、战后多命令编排、跨单位批量——那些是 D-010/R2 的后续阶段。
 */
final class FormatUnitPlan {

  /** 本工具提交的第一条命令类型（引用 handler 常量，不在本类另抄字面量）。 */
  static final String SET_COMPOSITION_TYPE = SetCompositionHandler.TYPE;

  /** 本工具提交的第二条命令类型（行动记录；与 {@code PutInfoHandler.type()} 同字面）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 单位 canonical，一次格式化一条）。 */
  static final String INFO_KEY = "formatUnit";

  private FormatUnitPlan() {}

  /**
   * 一条目标格式（人力/装备通用形状）：
   *
   * @param type 类型（自然语义文本，非空白）
   * @param baseAmount 基准量（≥ 0；随机化围绕它抖动）
   * @param jitterPerMille 随机化幅度（≥ 0，千分比：{@code floor(baseAmount × jitterPerMille / 1000)} 是上下抖动界）
   * @param minAmount 下界（≥ 0；缺省 0）
   * @param maxAmount 上界（≥ {@code minAmount}；缺省 {@link Long#MAX_VALUE}）
   */
  record EntrySpec(
      String type, long baseAmount, long jitterPerMille, long minAmount, long maxAmount) {

    EntrySpec {
      if (type == null || type.isBlank()) {
        throw new IllegalArgumentException("type 不得为空白");
      }
      if (baseAmount < 0L) {
        throw new IllegalArgumentException("baseAmount 必须 ≥ 0: " + baseAmount);
      }
      if (jitterPerMille < 0L) {
        throw new IllegalArgumentException("jitterPerMille 必须 ≥ 0: " + jitterPerMille);
      }
      if (minAmount < 0L) {
        throw new IllegalArgumentException("min 必须 ≥ 0: " + minAmount);
      }
      if (maxAmount < minAmount) {
        throw new IllegalArgumentException("max 必须 ≥ min: max=" + maxAmount + " min=" + minAmount);
      }
    }
  }

  /**
   * 纯推导入口（见类注的随机化口径）。
   *
   * @param state 读数所在状态（preview/apply 共用同一坐标）
   * @param unitId 目标单位 id（必须存在）
   * @param manpowerSpecs 人力目标格式（有序；同表 type 不得重复）
   * @param equipmentSpecs 装备目标格式（有序；同表 type 不得重复）
   * @param seed 显式种子（缺省 = 确定性派生）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan derive(
      SimulationState state,
      String unitId,
      List<EntrySpec> manpowerSpecs,
      List<EntrySpec> equipmentSpecs,
      Optional<Long> seed) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(manpowerSpecs, "manpowerSpecs");
    Objects.requireNonNull(equipmentSpecs, "equipmentSpecs");
    if (unitId == null || unitId.isBlank()) {
      throw new IllegalArgumentException("unitId 必须是非空文本");
    }
    requireNoDuplicateSpecTypes(manpowerSpecs, "manpower");
    requireNoDuplicateSpecTypes(equipmentSpecs, "equipment");
    UnitState units = ToolSupport.unitState(state);
    Unit current = units.units().get(UnitId.parse(unitId));
    if (current == null) {
      throw new IllegalArgumentException("单位不存在: " + unitId);
    }
    long tick = state.meta().timestamp().tick();
    long baseRevision = state.meta().ref().revision().value();
    boolean seedProvided = seed.isPresent();
    long effectiveSeed =
        seedProvided
            ? seed.get()
            : deriveSeed(unitId, manpowerSpecs, equipmentSpecs, baseRevision, tick);
    Random random = new Random(effectiveSeed);
    List<CompositionEntry> manpower = format(manpowerSpecs, random);
    List<CompositionEntry> equipment = format(equipmentSpecs, random);
    return new Plan(
        unitId,
        effectiveSeed,
        seedProvided,
        manpower,
        equipment,
        current.manpower(),
        current.equipment(),
        tick);
  }

  /** 从"单位 + 基态 revision + tick + 目标格式"派生确定种子（FNV-1a 64 位）：同状态同参数 ⇒ 同种子，无墙钟、无系统随机源。 */
  private static long deriveSeed(
      String unitId,
      List<EntrySpec> manpowerSpecs,
      List<EntrySpec> equipmentSpecs,
      long baseRevision,
      long tick) {
    StringBuilder text = new StringBuilder();
    text.append(unitId).append('|').append(baseRevision).append('|').append(tick).append('|');
    appendSpecs(text, "M", manpowerSpecs);
    appendSpecs(text, "E", equipmentSpecs);
    long hash = 0xcbf29ce484222325L;
    for (int i = 0; i < text.length(); i++) {
      hash ^= text.charAt(i);
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  private static void appendSpecs(StringBuilder text, String tag, List<EntrySpec> specs) {
    text.append(tag).append('[');
    for (EntrySpec spec : specs) {
      text.append(spec.type())
          .append(':')
          .append(spec.baseAmount())
          .append(':')
          .append(spec.jitterPerMille())
          .append(':')
          .append(spec.minAmount())
          .append(':')
          .append(spec.maxAmount())
          .append(';');
    }
    text.append(']');
  }

  /** 按规格表序逐条算目标量（同表 type 已由调用方判重）。 */
  private static List<CompositionEntry> format(List<EntrySpec> specs, Random random) {
    List<CompositionEntry> out = new ArrayList<>(specs.size());
    for (EntrySpec spec : specs) {
      long amount = formatAmount(spec, random);
      out.add(new CompositionEntry(spec.type(), amount));
    }
    return List.copyOf(out);
  }

  /**
   * 单条量的算法（三条口径写死，供测试阶段逐值钉）：
   *
   * <ol>
   *   <li>{@code jitterPerMille == 0} ⇒ 不掷骰，取 {@code clamp(baseAmount)};
   *   <li>{@code amplitude = floor(baseAmount × jitterPerMille / 1000)}；{@code amplitude == 0} ⇒
   *       不掷骰（"幅度太小"不消耗随机数）；
   *   <li>否则在 {@code [-amplitude, +amplitude]} 上均匀取整（{@code Random.nextLong(2×amplitude+1) −
   *       amplitude}），加上基准量后按 {@code [min, max]} 截断。
   * </ol>
   */
  private static long formatAmount(EntrySpec spec, Random random) {
    if (spec.jitterPerMille() == 0L) {
      return clamp(spec.baseAmount(), spec.minAmount(), spec.maxAmount());
    }
    long product;
    try {
      product = Math.multiplyExact(spec.baseAmount(), spec.jitterPerMille());
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "随机化幅度计算溢出 long: baseAmount="
              + spec.baseAmount()
              + " × jitterPerMille="
              + spec.jitterPerMille(),
          e);
    }
    long amplitude = product / 1000L;
    if (amplitude == 0L) {
      return clamp(spec.baseAmount(), spec.minAmount(), spec.maxAmount());
    }
    if (amplitude > (Long.MAX_VALUE - 1L) / 2L) {
      throw new IllegalArgumentException("随机化幅度过大，无法形成均匀区间: amplitude=" + amplitude);
    }
    long span = 2L * amplitude + 1L;
    long delta = random.nextLong(span) - amplitude;
    long desired;
    if (delta > 0L && spec.baseAmount() > Long.MAX_VALUE - delta) {
      desired = Long.MAX_VALUE; // 上溢时先截到 long 上界，再按 max 截断
    } else {
      desired = spec.baseAmount() + delta;
    }
    return clamp(desired, spec.minAmount(), spec.maxAmount());
  }

  private static long clamp(long value, long min, long max) {
    return Math.max(min, Math.min(max, value));
  }

  private static void requireNoDuplicateSpecTypes(List<EntrySpec> specs, String field) {
    Set<String> seen = new LinkedHashSet<>();
    for (EntrySpec spec : specs) {
      if (!seen.add(spec.type())) {
        throw new IllegalArgumentException(field + " 不得有重复 type: " + spec.type());
      }
    }
  }

  private static void requireNoDuplicateEntryTypes(List<CompositionEntry> entries, String field) {
    Set<String> seen = new LinkedHashSet<>();
    for (CompositionEntry entry : entries) {
      if (!seen.add(entry.type())) {
        throw new IllegalArgumentException(field + " 不得有重复 type: " + entry.type());
      }
    }
  }

  /**
   * 一份格式化计划（全部字段是状态与参数的纯函数；两张目标表在构造期冻结）。
   *
   * @param unitId 目标单位 id
   * @param seed 生效种子（显式或派生）
   * @param seedProvided {@code true} = 调用方显式给了 {@code seed}；{@code false} = 确定性派生
   * @param manpower 目标人力表（有序）
   * @param equipment 目标装备表（有序）
   * @param beforeManpower 复写前的人力表（视图用，不参与计算）
   * @param beforeEquipment 复写前的装备表（视图用，不参与计算）
   * @param tick 推导时的世界日（行动记录用）
   */
  record Plan(
      String unitId,
      long seed,
      boolean seedProvided,
      List<CompositionEntry> manpower,
      List<CompositionEntry> equipment,
      List<CompositionEntry> beforeManpower,
      List<CompositionEntry> beforeEquipment,
      long tick) {

    Plan {
      if (unitId == null || unitId.isBlank()) {
        throw new IllegalArgumentException("unitId 必须是非空文本");
      }
      manpower = List.copyOf(Objects.requireNonNull(manpower, "manpower"));
      equipment = List.copyOf(Objects.requireNonNull(equipment, "equipment"));
      beforeManpower = List.copyOf(Objects.requireNonNull(beforeManpower, "beforeManpower"));
      beforeEquipment = List.copyOf(Objects.requireNonNull(beforeEquipment, "beforeEquipment"));
      requireNoDuplicateEntryTypes(manpower, "manpower");
      requireNoDuplicateEntryTypes(equipment, "equipment");
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
    }

    /** 本工具提交的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      return List.of(SET_COMPOSITION_TYPE, PUT_INFO_TYPE);
    }

    /** {@code unit.SetComposition} 载荷：两张算好的目标表整表复写。 */
    String setCompositionPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", unitId);
      payload.put("manpower", ToolSupport.compositionView(manpower));
      payload.put("equipment", ToolSupport.compositionView(equipment));
      return ToolSupport.json(payload);
    }

    /**
     * {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>）：把生效种子、是否显式、前后两张表与 reason 写进同一批的
     * revision——这是"随机化可复现"的**审计证据路径**（命令记录里带种子）。
     */
    String infoValueJson(String reason) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", unitId);
      value.put("seed", seed);
      value.put("seedProvided", seedProvided);
      value.put("tick", tick);
      value.put("manpower", ToolSupport.compositionView(manpower));
      value.put("equipment", ToolSupport.compositionView(equipment));
      value.put("beforeManpower", ToolSupport.compositionView(beforeManpower));
      value.put("beforeEquipment", ToolSupport.compositionView(beforeEquipment));
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      return "按格式复写单位 "
          + unitId
          + " 的人力/装备（tick "
          + tick
          + "，seed="
          + seed
          + (seedProvided ? "（显式）" : "（派生）")
          + "）：manpower="
          + manpower
          + "，equipment="
          + equipment
          + "；reason="
          + reason;
    }
  }
}
