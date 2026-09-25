package io.mosire.simos.economy.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 铁律 5 的机械化落地（照 {@code LedgerRoundTripTest}）：反射枚举 {@link EconomyData} 的 record 组件，逐组件造 差异，三条断言
 * ——新增状态组件若忘了进变更集，本测试自动红。
 *
 * <p>★ **它也是本轮"变异自证"的落点**：把 {@code EconomyChangeSet.between} 里任一分量改成恒 {@code Unchanged}，
 * "该组件参与"那条断言当场红。
 */
class EconomyRoundTripTest {

  private static final IndustryId FARM = new IndustryId("farm");
  private static final ClassSlotId PEASANT = new ClassSlotId("peasant");
  private static final ClassSlotId LANDLORD = new ClassSlotId("landlord");
  private static final ClassKey KEY = new ClassKey(FARM, PEASANT);
  private static final ClassKey OTHER_KEY = new ClassKey(FARM, LANDLORD);
  private static final DebtId D1 = new DebtId("debt-1");
  private static final CommodityId GRAIN = new CommodityId("grain");

  /** ★ 唯一的豁免集合：v1 的 EconomyData 没有"不进变更集"的组件 ⇒ 必须是空集，且被单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  @Test
  void everyEconomyDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : EconomyData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      EconomyData base = EconomyData.empty();
      EconomyData target = mutate(base, name);
      EconomyChangeSet cs = EconomyChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 变更集整个为空）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(EconomyChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("EconomyData 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  @Test
  void changeSetHasExactlyFiveComponents() {
    assertThat(EconomyChangeSet.class.getRecordComponents()).hasSize(5);
    assertThat(componentNames(EconomyChangeSet.class))
        .as("变更集的每个组件都必须在 EconomyData 里有同名的 record 组件")
        .isSubsetOf(componentNames(EconomyData.class));
    assertThat(componentNames(EconomyData.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(EconomyChangeSet.class));
  }

  @Test
  void snapshotNamespaceIsEconomy() {
    EconomySnapshot snapshot =
        new EconomySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(5),
            EconomyData.empty());
    assertThat(snapshot.namespace()).isEqualTo("economy");
  }

  private static EconomyData mutate(EconomyData base, String name) {
    return switch (name) {
      case "meta" -> base.withMeta(Optional.of(meta()));
      case "industries" -> base.withIndustries(Map.of(FARM, industry(FARM, 0L)));
      case "classes" ->
          base.withIndustries(Map.of(FARM, industry(FARM, 0L)))
              .withClasses(Map.of(KEY, classRow(KEY)));
      case "debts" ->
          // ★ 债务的两端必须在 classes 里（v2 spec §八.2）⇒ 这个变异体必须**自带支撑的 classes**：
          //   从 EconomyData.empty() 只改 debts 的旧形态在新不变量下无法自洽（本用例只断言
          //   "目标组件进了变更集 + 往返相等"，多带支撑组件不破坏任何断言）。
          base.withIndustries(Map.of(FARM, industry(FARM, 0L)))
              .withClasses(Map.of(KEY, classRow(KEY), OTHER_KEY, classRow(OTHER_KEY)))
              .withDebts(Map.of(D1, debt()));
      case "flows" ->
          base.withIndustries(Map.of(FARM, industry(FARM, 0L))).withFlows(Map.of(KEY, flowRow()));
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(EconomyChangeSet cs, String name) {
    return switch (name) {
      case "meta" -> cs.meta().changed();
      case "industries" -> cs.industries().changed();
      case "classes" -> cs.classes().changed();
      case "debts" -> cs.debts().changed();
      case "flows" -> cs.flows().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  // ── 夹具（本类自足；codec 测试自带一套，照 ledger 两测试各自展开的先例） ──

  static EconomyMeta meta() {
    return new EconomyMeta("m1", 0L, OptionalLong.empty(), "rules-r1", Optional.empty());
  }

  /**
   * 一个合规矩的产业：两个槽位各持**劳动投入率上限**（R1.1 起不再是"人口占比"，故**不必合计 1000‰**）。 上限须 ≥ 行里的 {@code
   * participationPerMille}（此处 800）= v2 spec §八.1 的不变量。
   */
  static Industry industry(IndustryId id, long progress) {
    List<ClassSlot> slots =
        List.of(new ClassSlot(PEASANT, "贫农", 950), new ClassSlot(LANDLORD, "地主", 900));
    return new Industry(
        id,
        "农业",
        new RegimeId("tenant"),
        120L,
        progress,
        Map.of(AssetKind.CATTLE, 1L),
        500L,
        Map.of(GRAIN, 7L),
        Map.of(AssetKind.CATTLE, 1L), // ★ 必须非空：空 map 与"字段没进变更集"在值层面不可区分
        slots,
        new AllocationRule.Split(700, 300),
        0L,
        0L);
  }

  static ClassKey otherKey() {
    return OTHER_KEY;
  }

  /**
   * ★ 无债务的阶层行：**债务引用完整性**（v2 spec §八.2）要求行内 {@code debts} 的每个 id 都在债务表里， 故"只变异 classes
   * 一个组件"的用例只能用不引用债务的行。
   */
  static ClassRow classRow(ClassKey key) {
    return new ClassRow(
        key,
        120L,
        60000L,
        800,
        Map.of(AssetKind.LAND, 2700L),
        Map.of(GRAIN, 300L),
        50L,
        List.of(),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  static Debt debt() {
    return new Debt(D1, KEY, OTHER_KEY, Optional.of(GRAIN), 100L, 20, 3L, false);
  }

  static FlowRow flowRow() {
    return new FlowRow(KEY, 200L, Map.of(GRAIN, 120L), 10L, 5L, 0L, 0L, 65L, 7L, 3L);
  }
}
