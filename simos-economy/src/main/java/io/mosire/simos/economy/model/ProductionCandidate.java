package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CandidateId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.relation.LaborSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>候选生产方式（预设）</b>（R4-E2）：GM/事件登记的一条"可以这样生产"的技术-制度预设。本片只负责登记与读口； 进入采用（可行性、试产规模、建 unit）留 E2b。
 *
 * <p>★★ <b>版本与 modeKey</b>：{@code (id, version)} 是版本键；{@link #modeKeyOf(CandidateId, int)} 是 {@code
 * id@version} 的**唯一拼写点**（不许在命令/结算/读口各处各拼一遍）。修订 = 同一个 id 登记新 version： 表中该 id 的行换成新版本，但<b>已存在的 {@code
 * ProductionProcess.modeKey} 一字不动</b> —— 运行中的旧 unit 不会悄悄改口径。
 *
 * <p>★★ <b>字段语义（本片只登记，不解释成算式）</b>：
 *
 * <ul>
 *   <li>{@code outputPerUnit}：主产出 {@code output} 与副产物，单位规模产出量（毫单位；逐值 &gt; 0）；非空且必须包含 {@code
 *       output}；
 *   <li>{@code inputPerUnit}：单位规模投入（毫单位；逐值 ≥ 0，允许留 0 值表示显式"这项不要料"）；
 *   <li>{@code requiredAssets}：单位规模需要的实物资产（{@link AssetKind} 键；逐值 ≥ 0）；E2b 由它判可行性；
 *   <li>{@code laborPerUnit}：单位规模劳动（千分劳动·周期）；≥ 0；
 *   <li>{@code buildDays}/{@code cycleDays}：建设期与生产周期（天）；{@code buildDays ≥ 0}、{@code cycleDays ≥
 *       1}；
 *   <li>{@code regime}/{@code laborSource}：采用后写进 relation 的制度与劳动来源；
 *   <li>{@code acceptedRightKinds}：可采用的权利性质（OWNED/TENANCY/COMMUNAL）；空集 = 不接受任何权利（登记合法、E2b 判不可行）；
 *   <li>{@code assetSource}：闲置份额的来源主体（可选）；空 = 只认自有/既有关系，不借外部份额。
 * </ul>
 *
 * <p>★ <b>不可变与保序</b>：三张表先拷进 {@code LinkedHashMap} 再包 {@code unmodifiableMap}（同 {@code
 * ProductionProcess} 的先例；绝不用 {@code Map.copyOf} —— 它的迭代序不是内容的纯函数）。
 *
 * @param id 稳定身份；不得为 null
 * @param version 版本号；必须 ≥ 1
 * @param output 主产出商品；必须出现在 {@code outputPerUnit} 的键里
 * @param outputPerUnit 单位规模产出（商品 → 毫单位）；非空、逐值 &gt; 0
 * @param inputPerUnit 单位规模投入（商品 → 毫单位）；逐值 ≥ 0；不得为 null（没有投入给空表）
 * @param requiredAssets 单位规模所需实物资产（资产种类 → 数量）；逐值 ≥ 0；不得为 null
 * @param laborPerUnit 单位规模劳动（千分劳动·周期）；≥ 0
 * @param buildDays 建设期（天）；≥ 0
 * @param cycleDays 生产周期（天）；≥ 1
 * @param regime 采用后的生产制度；不得为 null
 * @param laborSource 采用后的劳动来源；不得为 null
 * @param acceptedRightKinds 可接受的资产权利性质；不得为 null（空集合法）
 * @param assetSource 闲置份额来源主体；不得为 null（没有给 {@code Optional.empty()}）
 * @param name 展示名；不得为空白
 */
public record ProductionCandidate(
    CandidateId id,
    int version,
    CommodityId output,
    Map<CommodityId, Long> outputPerUnit,
    Map<CommodityId, Long> inputPerUnit,
    Map<AssetKind, Long> requiredAssets,
    long laborPerUnit,
    long buildDays,
    long cycleDays,
    RegimeId regime,
    LaborSource laborSource,
    Set<OwnershipStake.RightKind> acceptedRightKinds,
    Optional<ActorRef> assetSource,
    String name) {

  public ProductionCandidate {
    Objects.requireNonNull(id, "ProductionCandidate.id 不得为 null");
    Objects.requireNonNull(output, "ProductionCandidate.output 不得为 null");
    Objects.requireNonNull(outputPerUnit, "ProductionCandidate.outputPerUnit 不得为 null");
    Objects.requireNonNull(inputPerUnit, "ProductionCandidate.inputPerUnit 不得为 null");
    Objects.requireNonNull(requiredAssets, "ProductionCandidate.requiredAssets 不得为 null");
    Objects.requireNonNull(regime, "ProductionCandidate.regime 不得为 null");
    Objects.requireNonNull(laborSource, "ProductionCandidate.laborSource 不得为 null");
    Objects.requireNonNull(acceptedRightKinds, "ProductionCandidate.acceptedRightKinds 不得为 null");
    Objects.requireNonNull(
        assetSource, "ProductionCandidate.assetSource 不得为 null（没有请用 Optional.empty()）");
    Objects.requireNonNull(name, "ProductionCandidate.name 不得为 null");
    if (version < 1) {
      throw new IllegalArgumentException("ProductionCandidate.version 必须 ≥ 1: " + version);
    }
    if (laborPerUnit < 0L) {
      throw new IllegalArgumentException("ProductionCandidate.laborPerUnit 不得为负: " + laborPerUnit);
    }
    if (buildDays < 0L) {
      throw new IllegalArgumentException("ProductionCandidate.buildDays 不得为负: " + buildDays);
    }
    if (cycleDays < 1L) {
      throw new IllegalArgumentException("ProductionCandidate.cycleDays 必须 ≥ 1: " + cycleDays);
    }
    if (name.isBlank()) {
      throw new IllegalArgumentException("ProductionCandidate.name 不得为空白");
    }
    Map<CommodityId, Long> outputs = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : outputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionCandidate.outputPerUnit 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionCandidate.outputPerUnit 的产出量必须 > 0："
                + entry.getKey().value()
                + " = "
                + entry.getValue());
      }
      outputs.put(entry.getKey(), entry.getValue());
    }
    if (outputs.isEmpty()) {
      throw new IllegalArgumentException("ProductionCandidate.outputPerUnit 不得为空");
    }
    if (!outputs.containsKey(output)) {
      throw new IllegalArgumentException(
          "ProductionCandidate.output 必须是 outputPerUnit 的键（主产出不许另写一份）: " + output.value());
    }
    outputPerUnit = Collections.unmodifiableMap(outputs); // ★ 冻在赋值处
    Map<CommodityId, Long> inputs = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : inputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionCandidate.inputPerUnit 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionCandidate.inputPerUnit 不得为负："
                + entry.getKey().value()
                + " = "
                + entry.getValue());
      }
      inputs.put(entry.getKey(), entry.getValue());
    }
    inputPerUnit = Collections.unmodifiableMap(inputs); // ★ 冻在赋值处
    Map<AssetKind, Long> assets = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : requiredAssets.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionCandidate.requiredAssets 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionCandidate.requiredAssets 不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      assets.put(entry.getKey(), entry.getValue());
    }
    requiredAssets = Collections.unmodifiableMap(assets); // ★ 冻在赋值处
    Set<OwnershipStake.RightKind> rights = new LinkedHashSet<>();
    for (OwnershipStake.RightKind right : acceptedRightKinds) {
      if (right == null) {
        throw new IllegalArgumentException("ProductionCandidate.acceptedRightKinds 不得含 null");
      }
      rights.add(right);
    }
    acceptedRightKinds = Collections.unmodifiableSet(rights); // ★ 冻在赋值处
  }

  /**
   * ★★ <b>{@code modeKey} 的唯一拼写点</b>：{@code candidateId + "@" + version}。
   *
   * <p>已建 unit 的 modeKey 是**历史事实**：登记修订（新 version）不重写任何既有 unit 的 modeKey —— 因此旧 unit 仍指向它当初采用的
   * {@code id@version}，不会悄悄跟随新版本。
   */
  public static String modeKeyOf(CandidateId id, int version) {
    Objects.requireNonNull(id, "ProductionCandidate.modeKeyOf 的 id 不得为 null");
    if (version < 1) {
      throw new IllegalArgumentException(
          "ProductionCandidate.modeKeyOf 的 version 必须 ≥ 1: " + version);
    }
    return id.value() + "@" + version;
  }

  /** 本条候选（当前 version）的 modeKey。 */
  public String modeKey() {
    return modeKeyOf(id, version);
  }
}
