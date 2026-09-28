package io.mosire.simos.economy.api.id;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Optional;

/**
 * ★★ <b>家户的稳定身份</b>（S1 方案 A；裁定 2026-09-28：身份与视图分离）。
 *
 * <p>★★ <b>为什么需要它</b>：{@link CohortKey} 是"某一格 + 某种居住类型 + 某个阶层"的<b>视图</b>，而家户的经济主体身份
 * 必须能在"人迁走、阶层变了、同一视图出现第二个家户"之后保持不变（铁律 1：地址是定位方式，ID 是身份）。旧口径把 {@code CohortKey} 同时当身份用 ⇒
 * 每次迁移都要改债权/账户/劳动/关系的全部键，漏一处就静默断链。
 *
 * <p>★ <b>规范串（{@link #toString()} / {@link #parse(String)} 互逆）</b>：本类型是 opaque 值对象，规范串就是 {@link
 * #value()} 本身。它<b>不含 {@code "."}</b>（地址 {@code economy:<mapId>:class.<id>} 在第一个点处切段）。 ★ 旧档迁移生成的 id
 * 形如 {@code legacy-0_0|rural|poor_peasant}（含 {@code |}）——<b>它只进状态表与地址，不进 actor id</b>：{@code
 * HouseholdActors.idOf(HouseholdId)} 会把 {@code |} 换成 {@code :}，避开 {@code GoodsAccountKey} 的接缝约定（见
 * {@code HouseholdActors} 的 K9 注释）。
 *
 * <p>★★ <b>{@link #ofLegacy(CohortKey)} 只许旧档迁移调用</b>（S1.5）：运行期不得从视图再造身份。本类<b>不提供</b>从 {@link
 * CohortKey} 直接构造的公开路径；{@code ofLegacy} 的名字就是把这条限制写在调用点上。
 *
 * <p>★ <b>新档 id 的拼写点在这里</b>（{@link #ofSeed(HexCoord, ResidenceKind, SocialClassId)}）：创世家户的 id
 * 由它一次性给出，不在 seeder 里内联拼串。格式 {@code hh-<q>_<r>-<residence>-<stratum>}（无 {@code |}/{@code .}）。
 *
 * @param value 非空白规范串
 */
public record HouseholdId(String value) {

  /** 旧档迁移前缀（{@link #ofLegacy(CohortKey)} 的唯一落点）；{@link #legacyView()} 按它判"能否还原旧视图"。 */
  public static final String LEGACY_PREFIX = "legacy-";

  /**
   * ★ <b>旧档临时占位前缀</b>（只由 {@code EconomyCodec} 的反序列化器在"旧 LaborAllocation 缺 household"时造）：
   * 迁移器必须在返回状态前把它换成真实家户。运行期不得使用（{@link #isPending()} 可判）。
   */
  public static final String PENDING_LEGACY_PREFIX = "legacy-pending-";

  public HouseholdId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("HouseholdId 不得为空白");
    }
  }

  @Override
  @JsonValue
  public String toString() {
    return value;
  }

  /** 只校验非空白（同其余 opaque id；格式的权威是 {@link #ofLegacy}/{@link #ofSeed} 两处拼写点）。 */
  @JsonCreator
  public static HouseholdId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("HouseholdId 不得为空白: " + text);
    }
    return new HouseholdId(text);
  }

  /**
   * ★★ <b>旧档迁移：视图 → 稳定身份</b>（S1.5 明文；运行期不得调用）。
   *
   * <p>确定性：同一个 {@link CohortKey} 恒得同一个 id；可逆性：{@link #legacyView()} 能还原旧视图（供迁移器对账）。
   */
  public static HouseholdId ofLegacy(CohortKey view) {
    if (view == null) {
      throw new IllegalArgumentException("HouseholdId.ofLegacy 的 view 不得为 null");
    }
    return new HouseholdId(LEGACY_PREFIX + view);
  }

  /** ★ 创世家户 id（{@code hh-<q>_<r>-<residence>-<stratum>}）：新档运行期的唯一拼写点，不经过任何旧视图反推。 */
  public static HouseholdId ofSeed(HexCoord hex, ResidenceKind residence, SocialClassId stratum) {
    if (hex == null) {
      throw new IllegalArgumentException("HouseholdId.ofSeed 的 hex 不得为 null");
    }
    if (residence == null) {
      throw new IllegalArgumentException("HouseholdId.ofSeed 的 residence 不得为 null");
    }
    if (stratum == null) {
      throw new IllegalArgumentException("HouseholdId.ofSeed 的 stratum 不得为 null");
    }
    return new HouseholdId("hh-" + hex + "-" + residence.value() + "-" + stratum.value());
  }

  /** 迁移期临时占位（见 {@link #PENDING_LEGACY_PREFIX}）；{@code oldAllocationKey} 用于让占位可追溯、不撞车。 */
  public static HouseholdId pendingLegacy(String oldAllocationKey) {
    if (oldAllocationKey == null || oldAllocationKey.isBlank()) {
      throw new IllegalArgumentException("pendingLegacy 的 oldAllocationKey 不得为空白");
    }
    return new HouseholdId(PENDING_LEGACY_PREFIX + oldAllocationKey);
  }

  /** 是否是迁移期占位（迁移器消费它；运行期守卫见 {@code EconomyData}）。 */
  public boolean isPending() {
    return value.startsWith(PENDING_LEGACY_PREFIX);
  }

  /** 是否由 {@link #ofLegacy(CohortKey)} 生成。 */
  public boolean isLegacy() {
    return value.startsWith(LEGACY_PREFIX);
  }

  /**
   * ★ 旧视图还原（仅旧档迁移/兼容读）：{@code legacy-<q>_<r>|<residence>|<stratum>} ⇒ 该 {@link CohortKey}； 非旧档 id ⇒
   * {@link Optional#empty()}（不猜）。
   */
  public Optional<CohortKey> legacyView() {
    if (!isLegacy()) {
      return Optional.empty();
    }
    return Optional.of(CohortKey.parse(value.substring(LEGACY_PREFIX.length())));
  }
}
