package io.mosire.simos.economy.api.cohort;

import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Optional;

/**
 * ★★ <b>经济侧的家户 id 拼写点</b>（2026-10-09 家户/人口架构 §3.1 的落地形态）。
 *
 * <p>稳定身份 {@link HouseholdId} 已迁入契约层 {@code simos-social-api}（只依赖 map、无 Jackson）， 而 {@code
 * social-api} <b>不许</b>依赖 {@code economy-api} ⇒ 旧 {@code HouseholdId} 里那些"需要 {@link CohortKey} /
 * {@link ResidenceKind} / {@link SocialClassId} 才能拼"的工厂与旧档语义， 只能留在经济契约层。 本类就是它们的唯一落点：
 *
 * <ul>
 *   <li>旧档视图 → 稳定身份（{@link #ofLegacy(CohortKey)}）与反向还原（{@link #legacyView(HouseholdId)}）；
 *   <li>创世家户 id 的规范拼写（{@link #ofSeed} / {@link #ofSeedRole}）；
 *   <li>旧档迁移占位（{@link #pendingLegacy(String)}）与它的判别（{@link #isPending(HouseholdId)}）。
 * </ul>
 *
 * <p>★ 这些方法与常量原来住在 {@code economy-api} 的旧 {@code HouseholdId} 上；迁移时数值/字符串行为<b>逐字保留</b>，
 * 只换了调用点。运行期新代码仍不得从旧视图反推身份（{@link #ofLegacy} 只许旧档迁移调用）。
 */
public final class HouseholdIds {

  /** 旧档迁移前缀（{@link #ofLegacy(CohortKey)} 的唯一落点）。 */
  public static final String LEGACY_PREFIX = "legacy-";

  /**
   * 旧档临时占位前缀（只由 {@code EconomyCodec} 的反序列化器在"旧 HouseholdLaborCommitment 缺 household"时造）：
   * 迁移器必须在返回状态前把它换成真实家户。运行期不得使用（{@link #isPending(HouseholdId)} 可判）。
   */
  public static final String PENDING_LEGACY_PREFIX = "legacy-pending-";

  private HouseholdIds() {}

  /**
   * ★★ <b>旧档迁移：视图 → 稳定身份</b>（S1.5 明文；运行期不得调用）。
   *
   * <p>确定性：同一个 {@link CohortKey} 恒得同一个 id；可逆性：{@link #legacyView(HouseholdId)} 能还原旧视图（供迁移器对账）。
   */
  public static HouseholdId ofLegacy(CohortKey view) {
    if (view == null) {
      throw new IllegalArgumentException("HouseholdIds.ofLegacy 的 view 不得为 null");
    }
    return new HouseholdId(LEGACY_PREFIX + view);
  }

  /** ★ 创世家户 id（{@code hh-<q>_<r>-<residence>-<stratum>}）：新档运行期的唯一拼写点。 */
  public static HouseholdId ofSeed(HexCoord hex, ResidenceKind residence, SocialClassId stratum) {
    if (hex == null) {
      throw new IllegalArgumentException("HouseholdIds.ofSeed 的 hex 不得为 null");
    }
    if (residence == null) {
      throw new IllegalArgumentException("HouseholdIds.ofSeed 的 residence 不得为 null");
    }
    if (stratum == null) {
      throw new IllegalArgumentException("HouseholdIds.ofSeed 的 stratum 不得为 null");
    }
    return new HouseholdId("hh-" + hex + "-" + residence.value() + "-" + stratum.value());
  }

  /**
   * ★★ <b>创世家户 id 的带角色后缀变体</b>（格式 {@code hh-<q>_<r>-<residence>-<stratum>-<roleSuffix>}）： 只服务
   * seeder 里"同一 (格, 居住类型, 阶层) 需要第二个确定性家户"的情形（如从最贫一档切出的 {@code displaced} 户）。
   *
   * <p>★ 参数校验与 {@link #ofSeed} 同款；{@code roleSuffix} 另外必须<b>非空白且不含 {@code '.'}</b> —— 点号是地址 {@code
   * economy:<mapId>:class.<id>} 的切段符，家户 id 不许把它带进地址。
   */
  public static HouseholdId ofSeedRole(
      HexCoord hex, ResidenceKind residence, SocialClassId stratum, String roleSuffix) {
    if (hex == null) {
      throw new IllegalArgumentException("HouseholdIds.ofSeedRole 的 hex 不得为 null");
    }
    if (residence == null) {
      throw new IllegalArgumentException("HouseholdIds.ofSeedRole 的 residence 不得为 null");
    }
    if (stratum == null) {
      throw new IllegalArgumentException("HouseholdIds.ofSeedRole 的 stratum 不得为 null");
    }
    if (roleSuffix == null || roleSuffix.isBlank()) {
      throw new IllegalArgumentException("HouseholdIds.ofSeedRole 的 roleSuffix 不得为空白");
    }
    if (roleSuffix.indexOf('.') >= 0) {
      throw new IllegalArgumentException(
          "HouseholdIds.ofSeedRole 的 roleSuffix 不得含 '.'（地址会被第一个点截断）: " + roleSuffix);
    }
    return new HouseholdId(
        "hh-" + hex + "-" + residence.value() + "-" + stratum.value() + "-" + roleSuffix);
  }

  /** 迁移期临时占位（见 {@link #PENDING_LEGACY_PREFIX}）；{@code oldAllocationKey} 用于让占位可追溯、不撞车。 */
  public static HouseholdId pendingLegacy(String oldAllocationKey) {
    if (oldAllocationKey == null || oldAllocationKey.isBlank()) {
      throw new IllegalArgumentException("pendingLegacy 的 oldAllocationKey 不得为空白");
    }
    return new HouseholdId(PENDING_LEGACY_PREFIX + oldAllocationKey);
  }

  /** 是否是迁移期占位（迁移器消费它；运行期守卫见 {@code EconomyData}）。 */
  public static boolean isPending(HouseholdId household) {
    if (household == null) {
      throw new IllegalArgumentException("HouseholdIds.isPending 的 household 不得为 null");
    }
    return household.value().startsWith(PENDING_LEGACY_PREFIX);
  }

  /** 是否由 {@link #ofLegacy(CohortKey)} 生成。 */
  public static boolean isLegacy(HouseholdId household) {
    if (household == null) {
      throw new IllegalArgumentException("HouseholdIds.isLegacy 的 household 不得为 null");
    }
    return household.value().startsWith(LEGACY_PREFIX);
  }

  /**
   * ★ 旧视图还原（仅旧档迁移/兼容读）：{@code legacy-<q>_<r>|<residence>|<stratum>} ⇒ 该 {@link CohortKey}； 非旧档 id ⇒
   * {@link Optional#empty()}（不猜）。
   */
  public static Optional<CohortKey> legacyView(HouseholdId household) {
    if (household == null) {
      throw new IllegalArgumentException("HouseholdIds.legacyView 的 household 不得为 null");
    }
    if (!isLegacy(household)) {
      return Optional.empty();
    }
    return Optional.of(CohortKey.parse(household.value().substring(LEGACY_PREFIX.length())));
  }
}
