package io.mosire.simos.economy.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.EconomyPolicyKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W10-fix（2026-10-11）第二处的回归</b>：{@code EconomyMeta.economyPolicies}（政策预设表）必须在 {@link
 * EconomyData} 的**构造/写口**上逐键逐值逐序保留 —— 任何"构造期顺手重建 meta"的改动都不得把它清空。
 *
 * <p>★★ <b>它为什么值得留一条判据</b>：政策表是"世界级单值组件"里的**可写**面（GM 经 {@code economy.GmAdjust} /
 * 政策预设写进去）。一旦某个写口/构造路径在没有显式带过它的情况下重建 {@link EconomyMeta}，值会被静默清空，而表面上看"这套写口刚写过" —— 这正是 W10-fix
 * 当时抓到的形态。
 *
 * <p>★★★ <b>D-032 T5（本文件的载体变更，逐条依据、非"为绿放宽"）</b>：本文件原先还断言"<b>构造期自动旧档迁移</b> 真的跑过"——观察点是 ① {@code
 * rulesVersion} 被迁移器改写成 {@code pre-modern-v1}、② {@code migrationSource} 被写上、③ 旧键 relations 被对齐到既有
 * unit。这三条**随被测能力一起退役**：
 *
 * <ol>
 *   <li>被测类型 {@code io.mosire.simos.economy.migrate.LegacyHouseholdMigration}（及其 {@code
 *       canonicalizeRelations}）在 {@code src/main} 里**已不存在**（核验：{@code ls
 *       simos-economy/src/main/java/io/mosire/simos/economy/migrate/} ⇒ 只剩 {@code
 *       DebtReferenceReconciler} / {@code LegacyClassStructure} / {@code ProductionRoleResolver}）；
 *   <li>它的能力（把旧档升到新规则版本）按 **Alpha 不兼容旧档**（设计书 I16：不写兼容位、不读旧档、不迁移旧格式） **整体退役** ⇒ "旧档读不回来"不是缺陷、不许记
 *       bug；
 *   <li>仍然成立的判据（政策表逐键逐值逐序保留 / 未配键走具名缺省 / 直创世界不发明迁移来源）**没有丢**，就是下面的用例。
 * </ol>
 *
 * <p>★ 同款处置在同文件的两条方法（{@code migratePassesThePolicyTableThroughUnchanged} / {@code
 * migrationDoesNotInventPolicyRowsForALegacyArchive}）上已由前任 owner 做过（依据同上）。
 */
class EconomyPolicyMigrationTest {

  @Test
  @DisplayName("W10-fix（当前形态）：EconomyData 构造与读取不得清空或重排 EconomyMeta.economyPolicies")
  void constructionKeepsTheEconomyPolicyTableValueForValue() {
    Map<EconomyPolicyKey, Long> configured = new LinkedHashMap<>();
    configured.put(EconomyPolicyKey.PROFIT_GAP_SPEED_SLOPE_PER_MILLE, 250L);
    configured.put(EconomyPolicyKey.SLIDE_ROUND_DAYS, 9L);
    EconomyMeta before =
        new EconomyMeta(
            "m1",
            0L,
            OptionalLong.empty(),
            EconomyMeta.RULES_VERSION_PRE_MODERN_V1, // ★ 直接创世：本版本标签不靠任何迁移器改写
            Optional.empty(), // ★ 直接创世 = 没有迁移来源
            configured,
            Map.of(), // ★ D-032 第 7 组件 planAssetSources（缺键 = 只认自有份额，逐值 == 旧缺省）
            Map.of()); // ★ D-032 第 8 组件 planLaborSources（缺键 = SELF，逐值 == 旧缺省）

    EconomyData data = EconomyData.empty().withMeta(Optional.of(before));
    EconomyMeta after = data.meta().orElseThrow();

    // ── 正题：政策表逐键逐值逐序带过（缺任一条 ⇒ W10-fix 那条真损失路径会复现）─────────────
    assertThat(after.economyPolicies())
        .as("★★ W10-fix：构造/写口必须原样带过政策表（少这一行 ⇒ 写一次就被清空）")
        .isEqualTo(configured);
    assertThat(after.economyPolicies().keySet())
        .as("顺序也必须是规范序（枚举声明序），不是被顺手换过的序")
        .containsExactly(
            EconomyPolicyKey.SLIDE_ROUND_DAYS, EconomyPolicyKey.PROFIT_GAP_SPEED_SLOPE_PER_MILLE);
    assertThat(after.policyOf(EconomyPolicyKey.SLIDE_ROUND_DAYS))
        .as("读口按表里的值走（不是回落到缺省 5）")
        .isEqualTo(9L)
        .isNotEqualTo(EconomyPolicyKey.SLIDE_ROUND_DAYS.defaultValue());
    assertThat(after.policyOf(EconomyPolicyKey.SLIDE_COST_PER_LEVEL))
        .as("没配过的键仍是具名缺省")
        .isEqualTo(EconomyPolicyKey.SLIDE_COST_PER_LEVEL.defaultValue());

    // ── 同一把尺子的负向面：不许凭空发明"迁移来源"（直创世界）──────────────────────────
    assertThat(after.migrationSource()).as("直接创世 ⇒ 不得写上迁移来源").isEmpty();
    assertThat(after.rulesVersion())
        .as("直创世界的版本标签原样（没有任何迁移器来改写它）")
        .isEqualTo(EconomyMeta.RULES_VERSION_PRE_MODERN_V1);
  }
}
