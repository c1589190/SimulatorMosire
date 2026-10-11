package io.mosire.simos.economy.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.economy.model.Industry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>W10-fix（2026-10-11）第二处的回归</b>：{@code LegacyHouseholdMigration.migrateMeta} 在改写 {@code
 * EconomyMeta} 时必须<b>原样带过政策表</b>（{@code economyPolicies}）。
 *
 * <p>★★ <b>它为什么是真损失路径（不是理论风险）</b>：这个迁移由 {@link EconomyData} 的<b>构造期自动触发</b> —— {@code
 * LegacyHouseholdMigration.needed(...)} 只看 units/relations/conditions 的对齐，<b>不看</b> meta（见其类注与
 * {@code needed} 的第一条/第二条判据）。⇒ 一个"units 空而 relations 非空"的合法中间态会让<b>每一次构造</b>都重跑迁移；缺 {@code
 * value.economyPolicies()} 这一行 ⇒ GM 写进政策表的值在下一次 {@code EconomyData} 构造时被静默清空，
 * 而政策表本身还"看起来"是这套写口刚写过的样子。
 *
 * <p>★ <b>两个层次都测</b>：① <b>构造期自动迁移</b>（{@link #constructionTimeMigrationKeepsTheEconomyPolicyTable}）
 * —— 走 {@code EconomyData} 的真路径，夹具是"units 空 + relations 非空 + 该关系的旧产业存在"⇒ 迁移器按既有口径
 * <b>丢弃</b>那条旧关系（{@code canonicalizeRelations} 的 {@code legacyIndustryExists} 分支），于是迁移本身不引入别的
 * 状态差异，本用例量到的就只剩 meta；② <b>迁移器直调</b>（{@link #migratePassesThePolicyTableThroughUnchanged}）—— 直接钉
 * {@code migrate(...)} 返回的 {@code Result.meta()}。
 *
 * <p>★ <b>缺省中性同条验</b>：旧档（政策表本来就是空）过一遍迁移后仍是空表 —— 不凭空补出"等于缺省的冗余行" （{@link
 * #migrationDoesNotInventPolicyRowsForALegacyArchive}）。
 */
class EconomyPolicyMigrationTest {

  private static final IndustryId FARM = new IndustryId("farm");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final ActorRef OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, "house-7");

  /**
   * 触发构造期迁移的"旧键"：它的 {@code value} 刻意等于 {@link #FARM} 的串 ⇒ {@code canonicalizeRelations}
   * 走"旧产业确实存在、但一个 unit 都没有"分支，把这条关系<b>丢弃</b>（行为等价）， 于是夹具只制造"迁移被跑过"这一件事，不额外改状态。
   */
  private static final ProductionUnitId LEGACY_UNIT = new ProductionUnitId("farm");

  @Test
  @DisplayName("W10-fix：构造期自动旧档迁移不得清空 EconomyMeta.economyPolicies")
  void constructionTimeMigrationKeepsTheEconomyPolicyTable() {
    Map<EconomyPolicyKey, Long> configured = new LinkedHashMap<>();
    configured.put(EconomyPolicyKey.PROFIT_GAP_SPEED_SLOPE_PER_MILLE, 250L);
    configured.put(EconomyPolicyKey.SLIDE_ROUND_DAYS, 9L);
    EconomyMeta before =
        new EconomyMeta(
            "m1",
            0L,
            OptionalLong.empty(),
            // ★ 刻意用**旧档版本标签**（迁移器会把它改写成 pre-modern-v1）—— 这是"迁移真的跑了"的观察点。
            "aggregate-v1",
            Optional.empty(),
            configured);

    EconomyData data =
        EconomyData.empty()
            .withMeta(Optional.of(before))
            .withIndustries(Map.of(FARM, industry(FARM)))
            // ★★ 触发条件：units 空 + relations 非空（`needed` 的第二条判据）⇒ 本次构造里迁移器一定会跑。
            .withRelations(Map.of(LEGACY_UNIT, relation(LEGACY_UNIT)));

    EconomyMeta after = data.meta().orElseThrow();

    // ── 前置：迁移**真的**跑了（否则下面每条断言都是对着"没迁移"的世界变绿）──────────────
    assertThat(after.rulesVersion())
        .as("迁移器必须把 rulesVersion 改写成 pre-modern-v1（本用例的'迁移跑过了'观察点）")
        .isEqualTo(EconomyMeta.RULES_VERSION_PRE_MODERN_V1)
        .isNotEqualTo(before.rulesVersion());
    assertThat(after.migrationSource()).as("旧档迁移来源必须被记上（同一个 migrateMeta 里写的另一个字段）").isPresent();
    assertThat(data.relations()).as("那条旧关系按既有口径被丢弃（units 空 + 旧产业存在）⇒ 迁移确实处理过 relations").isEmpty();

    // ── 正题：政策表逐键逐值逐序带过 ────────────────────────────────────────────────
    assertThat(after.economyPolicies())
        .as("★★ W10-fix：migrateMeta 必须原样带过政策表（缺这一行 ⇒ 构造一次就被清空）")
        .isEqualTo(configured);
    assertThat(after.economyPolicies().keySet())
        .as("顺序也必须是规范序（枚举声明序），不是被迁移顺手换过的序")
        .containsExactly(
            EconomyPolicyKey.SLIDE_ROUND_DAYS, EconomyPolicyKey.PROFIT_GAP_SPEED_SLOPE_PER_MILLE);
    assertThat(after.policyOf(EconomyPolicyKey.SLIDE_ROUND_DAYS))
        .as("读口按表里的值走（不是回落到缺省 5）")
        .isEqualTo(9L)
        .isNotEqualTo(EconomyPolicyKey.SLIDE_ROUND_DAYS.defaultValue());
    assertThat(after.policyOf(EconomyPolicyKey.SLIDE_COST_PER_LEVEL))
        .as("没配过的键仍是具名缺省")
        .isEqualTo(EconomyPolicyKey.SLIDE_COST_PER_LEVEL.defaultValue());
  }

  @Test
  @DisplayName("W10-fix：migrate(...) 返回的 Result.meta() 逐字段带过政策表")
  void migratePassesThePolicyTableThroughUnchanged() {
    Map<EconomyPolicyKey, Long> configured =
        Map.of(
            EconomyPolicyKey.FAMINE_MAX_PPM_PER_TICK, 7_000L,
            EconomyPolicyKey.SLIDE_ROUND_DAYS, 3L);
    EconomyMeta before =
        new EconomyMeta(
            "m9", 4L, OptionalLong.of(1L), "aggregate-v1", Optional.empty(), configured);

    LegacyHouseholdMigration.Result result =
        LegacyHouseholdMigration.migrate(
            Map.of(FARM, industry(FARM)),
            Map.of(), // units：旧档没有这个组件
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(LEGACY_UNIT, relation(LEGACY_UNIT)),
            Map.of(),
            Optional.of(before));

    EconomyMeta after = result.meta().orElseThrow();
    assertThat(after.rulesVersion())
        .as("前置：迁移确实改写了版本标签")
        .isEqualTo(EconomyMeta.RULES_VERSION_PRE_MODERN_V1);
    assertThat(after.mapId()).as("migrateMeta 只改两个字段，其余逐字段带过").isEqualTo("m9");
    assertThat(after.activatedDay()).isEqualTo(4L);
    assertThat(after.lastClosedCycle()).isEqualTo(OptionalLong.of(1L));
    assertThat(after.economyPolicies())
        .as("★★ 政策表逐键逐值带过（migrateMeta 手工重建 record 时最容易漏的那一个组件）")
        .isEqualTo(configured);
    assertThat(after.policyOf(EconomyPolicyKey.SLIDE_ROUND_DAYS)).isEqualTo(3L);
    assertThat(after.policyOf(EconomyPolicyKey.MINT_MILLI_PER_FURNACE_CYCLE))
        .as("缺键在迁移后仍读具名缺省")
        .isEqualTo(EconomyPolicyKey.MINT_MILLI_PER_FURNACE_CYCLE.defaultValue());
  }

  @Test
  @DisplayName("W10-fix：旧档（空政策表）迁移后仍是空表 —— 不凭空补冗余行")
  void migrationDoesNotInventPolicyRowsForALegacyArchive() {
    // 旧档没有第 6 组件 ⇒ 5 参便捷构造器 = 空表（缺省中性）。
    EconomyMeta legacy =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    assertThat(legacy.economyPolicies()).as("前置：旧档就是空表").isEmpty();

    LegacyHouseholdMigration.Result result =
        LegacyHouseholdMigration.migrate(
            Map.of(FARM, industry(FARM)),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(LEGACY_UNIT, relation(LEGACY_UNIT)),
            Map.of(),
            Optional.of(legacy));

    EconomyMeta after = result.meta().orElseThrow();
    assertThat(after.rulesVersion()).isEqualTo(EconomyMeta.RULES_VERSION_PRE_MODERN_V1);
    assertThat(after.economyPolicies()).as("★ 空表进 ⇒ 空表出（迁移不得补出'等于缺省的冗余行'——那会让字节与缺省世界不同）").isEmpty();
    assertThat(after.policyOf(EconomyPolicyKey.SLIDE_ROUND_DAYS))
        .as("空表读口 = 全部走具名缺省（旧档逐值等于改动前）")
        .isEqualTo(EconomyPolicyKey.SLIDE_ROUND_DAYS.defaultValue());
  }

  // ── 夹具（本类自足）────────────────────────────────────────────────────────────────

  /**
   * 一个合规矩的产业（12 参便捷构造：operator/进度/产能/劳动累计等取中性值）。★ 只有它的**存在性**是夹具要的 —— {@code canonicalizeRelations}
   * 靠"旧产业是否存在"决定丢弃还是留下，本夹具走丢弃分支。
   */
  private static Industry industry(IndustryId id) {
    List<ClassSlot> slots = List.of(new ClassSlot(PEASANT, "贫农", 950));
    return new Industry(
        id,
        "农业",
        new RegimeId("tenant"),
        120L,
        Map.of(AssetKind.CATTLE, 1L),
        Map.of(AssetKind.CATTLE, Map.of(GRAIN, 1L)),
        500L,
        7L,
        Map.of(GRAIN, 7L),
        Map.of(AssetKind.CATTLE, Map.of(GRAIN, 1L)),
        slots,
        new AllocationRule.Split(700, 300));
  }

  /** 一条"旧键"生产关系（规则表为空 —— 本用例只关心它的**存在**触发了迁移）。 */
  private static ProductionRules relation(ProductionUnitId activity) {
    return new ProductionRules(activity, OPERATOR, null, List.of(), OPERATOR);
  }
}
