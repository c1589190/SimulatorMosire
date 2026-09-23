package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.SdInfoId;
import io.mosire.simos.util.state.RevisionId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * sd 侧 INFO 条目（spec §六，R14）：**自造**、**不复用**全局 {@code InfoEntry}——避免拖入 {@code TimeRange} / {@code
 * SubjectId} 的时态语义（spec §〇.3 / §六）。
 *
 * <p>★ **写的是感知层**（spec §六倾向）：供 UI / AAR 展示；ground truth 仍由 map/unit 等领域模块持有。★ 影响领域计算的字段**不许** 走
 * INFO（兑现 {@code InfoEntry} 的告诫）。
 *
 * <p>★ 诚实边界（spec §六 + M4 裁定 38 同口径）：{@code value} 是**裸 {@code Object}**——结构化值（Map/List）的 {@code
 * equals} 往返**不满足**，故往返判据只覆盖标量值（A5 明记，不假装覆盖）。
 *
 * <p>★★ **决策结果三件套（第 3 波第 1 步）**：INFO 条目**本身就是决策结果**，不另起平行实体。三个新字段：
 *
 * <ul>
 *   <li>{@link #id}（{@link SdInfoId}）——**同类型内唯一**。写路径的合成/去重见 {@link SdInfoIds}；
 *   <li>{@link #tick}——该条目所属 tick（不是 {@link #at} 那个 revision：二者互不换算）；
 *   <li>{@link #tags}——挂 {@link DecisionMakerId}（**与既有权限模型同构**，**不开**自由字符串标签）。**可为空集 = 无主**，
 *       天然成立，不用 {@code Optional} 包一层；
 *   <li>{@link #affiliations}（**决策文档可见性的第二轴**）——挂 {@link Affiliation}。语义是"这条 INFO 记的是**谁**的事" ⇒ 同一
 *       nation/army 的决策人**自动**看得到，不必逐个指派。与 {@link #tags} 是**并集**关系（二者命中其一即可见）， 判定**只此一处**（{@code
 *       RedactingQueryService#docs}）。**可为空集 = 不按归属发**，同样不用 {@code Optional}。
 *   <li>{@link #adjudicationStatus}（**裁决的生效 / 作废**，2026-09-23 用户原话「只有生效裁决和作废裁决」）——只对 {@code
 *       sd:adjudication.*} 条目有意义；缺省 {@code empty()} = 老档（那时都生效）。★ 一个 tick 可以留**多条**裁决记录，但**至多一条
 *       {@link AdjudicationStatus#EFFECTIVE}**；作废时那条记录**不删**，只换状态（与令的 {@code SUPERSEDED} 同族留痕）。
 * </ul>
 *
 * <p>★★ **老档兼容**（fail-closed 缺省）：本字段出现**之前**落盘的字节里没有 {@code id}/{@code tick}/{@code tags}/{@code
 * affiliations} 键。读回来**不得炸**——{@code tick} 由 Jackson 对缺失原语给 0；其余三个由本构造器补缺省：
 *
 * <ul>
 *   <li>{@code tags} 缺失 ⇒ **空集**（无主）。无主 ⇒ 不参与任何按决策人/按 tick 的归属裁决 ⇒ **fail-closed**（与台账 T1/T9
 *       的判据同源：缺省必须落在"安全的那一侧"）；旧条目本就未经打标签，空集是**语义为真**的缺省；
 *   <li>{@code affiliations} 缺失 ⇒ **空集**（不按归属发）。同理落在 fail-closed 那一侧：老条目**不会**因为
 *       新增这一轴而突然对某个国家/军队可见；
 *   <li>{@code id} 缺失 ⇒ 内容派生 {@code legacy:<key>@<at>}（不是随机、不是自增，纯函数可重放）。它只是一个**引用标签**，
 *       不授予任何权限；重复也无害（老条目全是无主，不进裁决）。
 * </ul>
 */
public record SdInfoEntry(
    SdInfoId id,
    long tick,
    Set<DecisionMakerId> tags,
    Set<Affiliation> affiliations,
    String key,
    Object value,
    Optional<String> note,
    RevisionId at,
    Optional<DirectiveId> sourceDirective,
    Optional<AdjudicationStatus> adjudicationStatus) {

  /** 老档迁移 id 的前缀（见类注：区分"内容派生的旧 id"与写路径合成的 {@code 地址#序号}）。 */
  public static final String LEGACY_ID_PREFIX = "legacy:";

  public SdInfoEntry {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key 不得为空白");
    }
    if (value == null) {
      throw new IllegalArgumentException("value 不得为 null");
    }
    if (note == null) {
      throw new IllegalArgumentException("note 不得为 null（无备注用 Optional.empty()）");
    }
    if (at == null) {
      throw new IllegalArgumentException("at 不得为 null");
    }
    if (sourceDirective == null) {
      throw new IllegalArgumentException("sourceDirective 不得为 null（无来源用 Optional.empty()）");
    }
    // ★ 老档兼容（旧字节没有这个键）：缺省 = 生效（见 AdjudicationStatus 的类注：那时的裁决都是生效的）。
    //   普通 INFO 条目也走这条缺省 ⇒ 它恒为 Optional.empty()，本字段只对 sd:adjudication.* 条目有意义。
    if (adjudicationStatus == null) {
      adjudicationStatus = Optional.empty();
    }
    if (tick < 0) {
      throw new IllegalArgumentException("tick 必须 ≥ 0: " + tick);
    }
    // ★ 老档兼容（旧字节没有这两个键）——见类注的 fail-closed 缺省。此处**不抛**：抛了等于"整个世界打不开"。
    if (id == null) {
      id = SdInfoId.parse(LEGACY_ID_PREFIX + key + "@" + at.value());
    }
    if (tags == null) {
      tags = Set.of();
    }
    Set<DecisionMakerId> frozen = new LinkedHashSet<>();
    for (DecisionMakerId tag : tags) {
      if (tag == null) {
        throw new IllegalArgumentException("tags 不得含 null");
      }
      frozen.add(tag);
    }
    tags = Collections.unmodifiableSet(frozen); // ★ 冻在赋值处
    // ★ 老档兼容（旧字节没有这个键）——见类注的 fail-closed 缺省。此处**不抛**：抛了等于"整个世界打不开"。
    if (affiliations == null) {
      affiliations = Set.of();
    }
    Set<Affiliation> frozenAffiliations = new LinkedHashSet<>();
    for (Affiliation affiliation : affiliations) {
      if (affiliation == null) {
        throw new IllegalArgumentException("affiliations 不得含 null");
      }
      frozenAffiliations.add(affiliation);
    }
    affiliations = Collections.unmodifiableSet(frozenAffiliations); // ★ 冻在赋值处
  }

  /**
   * **这条裁决是不是生效的**（2026-09-23）：{@link #adjudicationStatus} 缺席 = **生效**（老档那批当时都生效，语义为真），
   * 显式 {@link AdjudicationStatus#VOIDED} = 不生效。
   *
   * <p>★ 判据放在**类型上**（不是散在读面与工具里各写一遍）：作废工具要"找该 tick 的生效条目"、读面要"作废的不算结果"，
   * 两处问的是同一件事；各写一遍时**两边都不会报错**，只会漂。
   *
   * <p>★ **只对 {@code sd:adjudication.*} 条目有意义**：普通 INFO 条目恒缺省 ⇒ 恒"生效"。拿它筛别的条目等于恒真。
   *
   * <p>★★ **它必须是 {@code static}、且必须收条目作参数**（不是 {@code entry.isEffective()}）：record 上任何
   * {@code isXxx()} 形状的实例方法都会被 Jackson 当成**属性 getter** ⇒ 序列化时多写一个 {@code "effective"} 字段，
   * 而 `SdInfoEntry` 的编解码**不接受**这个字段（"Unrecognized field"）⇒ 整条变更集 JSON 非法、replay 当场挂
   * （真踩过：一次全量测试 13 条红）。static 方法不是属性，绕开这条。
   */
  public static boolean isEffective(SdInfoEntry entry) {
    Objects.requireNonNull(entry, "entry");
    return entry.adjudicationStatus().orElse(AdjudicationStatus.EFFECTIVE)
        == AdjudicationStatus.EFFECTIVE;
  }
}
