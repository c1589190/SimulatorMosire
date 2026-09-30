package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>hex 危机信号</b>（理想架构 §2.8/§7.3；E5a 地基）：某格在某一天出现某种危机的<b>可持久化读数</b>。它是信号/警告，
 * <b>不是</b>起义或自动强制——"起义不在当前范围"是设计的原话。
 *
 * <pre>
 * HexCrisisSignal(
 *   id,            // 键 == CrisisSignalId.idOf(hex, kind().name())；同 hex 同 kind 只保留最新一条
 *   hex, kind,     // 身份两段；键由它们确定性派生
 *   severity,      // ≥ 1；分级由写口给（E5b 才产生信号）
 *   day,           // ≥ 0；发生/最近更新日
 *   evidence,      // 逐项原始触发量（见下）；键非空白，值可正可负
 *   households,    // 涉及的家户（可空表；非空时按"对侧已提供"判存在）
 *   classes,       // 涉及的社会阶层（词表身份；可空表）
 *   reason)        // 不得空白
 * </pre>
 *
 * <p>★★ <b>{@code evidence} 的值允许为负，且必须按"原始量"读</b>：设计稿把 evidence 定义为"触发量：债务/产出、利息/F、
 * 口粮缺口、投入缺口"。这些原始量本身有方向 —— 净余量、盈余率、负缺口在真实读数里就是负数。负值表示"这一项原始量在这个方向上为负"， <b>不等于"没有证据"</b>，更不得在读口折算成
 * 0（本仓最反对的"看起来在记"）。信号是否成立由写口（E5b）按各自的阈值判， 本类型只冻结读数不改写它。
 *
 * <p>★★ <b>同 hex 同 kind 只保留最新一条</b>：身份 = {@code (hex, kind)} ⇒ {@code EconomyData.crisisSignals}
 * 的键由 {@link CrisisSignalId#idOf(HexCoord, String)} 派生；写口对新读数直接 {@code put}（覆盖即更新），旧读数不追加历史
 * ——若将来要回溯逐条历史，那是独立的审计表，不在本组件里冒充"最新一条"。
 *
 * <p>★ <b>E5a 不产生任何信号</b>：本类只落形状、Codec/变更集/读口与跨表守卫；{@code FOOD/DEBT_EXPLOSION/ CLASS_DECLINE}
 * 等的触发阈值与生成路径全在 E5b，避免"地基阶段顺手造出没人验证的信号"。
 *
 * <p>★ <b>不可变</b>：{@code evidence}/{@code households}/{@code classes} 在构造期冻结为保序不可变副本； 缺 null
 * 一律抛（没有就是空表，不用 null 冒充）。
 *
 * @param id 稳定身份；不得为 null（EconomyData 判"键 == 值内 id 且 == 由 hex/kind 派生的 id"）
 * @param hex 危机发生的格；不得为 null
 * @param kind 危机种类；不得为 null
 * @param severity 严重度；必须 ≥ 1（0/负不是一种分级）
 * @param day 发生或最近更新日；不得为负
 * @param evidence 逐项原始触发量；不得为 null、键不得空白、值不得为 null（值可正可负，见类注），保序不可变
 * @param households 涉及家户；不得为 null、不得含 null，保序不可变
 * @param classes 涉及社会阶层；不得为 null、不得含 null，保序不可变
 * @param reason 信号原因；不得空白
 */
public record HexCrisisSignal(
    CrisisSignalId id,
    HexCoord hex,
    Kind kind,
    int severity,
    long day,
    Map<String, Long> evidence,
    List<HouseholdId> households,
    List<SocialClassId> classes,
    String reason) {

  /** 危机种类（设计稿 §7.3 全表；E5a 只定义，不生成）。 */
  public enum Kind {
    /** 口粮危机。 */
    FOOD,
    /** 衣被/取暖缺口。 */
    CLOTH,
    /** 死亡率异常。 */
    MORTALITY,
    /** 债务爆炸（底层债务累积到阈值；E5b 才判阈值）。 */
    DEBT_EXPLOSION,
    /** 阶层下滑（随清算/阶层写回产生；E5b 才生成）。 */
    CLASS_DECLINE,
    /** 下一轮投入短缺。 */
    INPUT_SHORTFALL,
    /** 劳动负担异常。 */
    LABOR_BURDEN,
    /** 行政治安不足（GOV 辖区治安覆盖率 &lt; 1000‰；阶段 11b，只发信号，不自动扣市场）。 */
    ADMIN_SECURITY,
    /** 行政文书不足（GOV 辖区书吏+驿传覆盖率 &lt; 1000‰；阶段 11b，只发信号）。 */
    ADMIN_PAPERWORK,
    /** 行政物资/俸禄不足（当日 grain/cloth/silver 任一实付 &lt; 评估；阶段 11b，只发信号）。 */
    ADMIN_SUPPLY
  }

  public HexCrisisSignal {
    Objects.requireNonNull(id, "HexCrisisSignal.id 不得为 null");
    Objects.requireNonNull(hex, "HexCrisisSignal.hex 不得为 null");
    Objects.requireNonNull(kind, "HexCrisisSignal.kind 不得为 null");
    if (severity < 1) {
      throw new IllegalArgumentException("HexCrisisSignal.severity 必须 ≥ 1: " + severity);
    }
    if (day < 0L) {
      throw new IllegalArgumentException("HexCrisisSignal.day 不得为负: " + day);
    }
    if (evidence == null) {
      throw new IllegalArgumentException("HexCrisisSignal.evidence 不得为 null（没有给空表）");
    }
    Map<String, Long> evidenceCopy = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : evidence.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("HexCrisisSignal.evidence 的键不得空白");
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException(
            "HexCrisisSignal.evidence 的值不得为 null（原始量可以是任意 long，但不是缺值）: " + entry.getKey());
      }
      evidenceCopy.put(entry.getKey(), entry.getValue());
    }
    evidence = Collections.unmodifiableMap(evidenceCopy); // ★ 冻在赋值处（保序；SpotBugs 只认看见的包装）
    if (households == null) {
      throw new IllegalArgumentException("HexCrisisSignal.households 不得为 null（没有给空表）");
    }
    if (classes == null) {
      throw new IllegalArgumentException("HexCrisisSignal.classes 不得为 null（没有给空表）");
    }
    List<HouseholdId> householdCopy = new ArrayList<>(households.size());
    for (HouseholdId household : households) {
      if (household == null) {
        throw new IllegalArgumentException("HexCrisisSignal.households 不得含 null");
      }
      householdCopy.add(household);
    }
    List<SocialClassId> classCopy = new ArrayList<>(classes.size());
    for (SocialClassId socialClass : classes) {
      if (socialClass == null) {
        throw new IllegalArgumentException("HexCrisisSignal.classes 不得含 null");
      }
      classCopy.add(socialClass);
    }
    households = Collections.unmodifiableList(householdCopy); // ★ 冻在赋值处（保序）
    classes = Collections.unmodifiableList(classCopy); // ★ 冻在赋值处（保序）
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("HexCrisisSignal.reason 不得空白");
    }
  }

  /** ★ 键是否与本信号的 {@code (hex, kind)} 身份一致（EconomyData 构造期守卫用；kind 名是稳定文本段）。 */
  public boolean idMatchesIdentity() {
    return id.equals(CrisisSignalId.idOf(hex, kind.name()));
  }
}
