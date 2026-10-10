package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>计划 §5 判据 ④/N3/T9（政府采购优先级）</b>：政府要求管控 ⇒ 其挂单在<b>行政力池余量内</b>被移到簿最前，
 * <b>每超越一户消耗一份</b>，<b>见底硬停</b>（fail-closed，<b>不许先超后欠</b>），且<b>只改顺序、不改价格与量</b>。
 *
 * <p>★ 直测唯一拼写点 {@link ProcurementPriorityOrder#advance}（不含市场轮）：槽位用一个带"价格"的小记录，
 * 好让"只改顺序不改价格"这条判据有可判别的东西（价格被顺手改写会被当场抓到）。
 */
class ProcurementPriorityAcceptanceTest {

  /** 一只槽位（价与量都是"不许被置顶改写"的载荷）。 */
  private record Slot(HouseholdId owner, long priceMilli, long quantityMilli, String label) {}

  private static final HouseholdId H1 = HouseholdId.parse("hh-1");
  private static final HouseholdId H2 = HouseholdId.parse("hh-2");
  private static final HouseholdId H3 = HouseholdId.parse("hh-3");
  private static final HouseholdId GOV = HouseholdId.parse("hh-gov-g1");

  private static Slot slot(HouseholdId owner, long price, String label) {
    return new Slot(owner, price, 100L, label);
  }

  /** 一张簿：三家普通家户在前、政府的单在最后（= 最差的服务序）。 */
  private static List<Slot> book() {
    List<Slot> slots = new ArrayList<>();
    slots.add(slot(H1, 10L, "h1"));
    slots.add(slot(H2, 20L, "h2"));
    slots.add(slot(H3, 30L, "h3"));
    slots.add(slot(GOV, 40L, "gov"));
    return slots;
  }

  private static ProcurementPriorityInput controlsGov(long units) {
    return new ProcurementPriorityInput(Map.of(GOV, units));
  }

  /** 跑一次 {@code advance}，返回"名次 → 槽位"的规范序表（名次必须是 0..n−1 的无缺口全序）。 */
  private static Map<Long, Slot> advance(
      List<Slot> slots,
      ProcurementPriorityInput input,
      Map<HouseholdId, Long> poolLeft,
      List<Boolean> overtook) {
    Map<Long, Slot> byRank = new LinkedHashMap<>();
    ProcurementPriorityOrder.advance(
        slots,
        Slot::owner,
        (s, rank) -> {
          assertThat(byRank.putIfAbsent(rank, s)).as("名次 %s 不许被写两次", rank).isNull();
          return;
        },
        (s, flag) -> overtook.add(flag),
        input,
        poolLeft,
        5L);
    return byRank;
  }

  // ── 正向：置顶 + 每超越一户消耗一份 ──────────────────────────────────────────────────

  /** ★ T9：政府单被移到最前；消耗 = 被超越的<b>户数</b>（不是槽位数）；池子按实数扣。 */
  @Test
  void governmentSlotIsPromotedToFrontAndEachOvertakenHouseholdCostsOneUnit() {
    List<Slot> slots = book();
    Map<HouseholdId, Long> poolLeft = new LinkedHashMap<>(Map.of(GOV, 10L));
    List<Boolean> overtook = new ArrayList<>();

    Map<Long, Slot> byRank = advance(slots, controlsGov(10L), poolLeft, overtook);

    assertThat(byRank.get(0L)).as("★ 政府单排在最前（最先卖/最先买）").isEqualTo(slot(GOV, 40L, "gov"));
    assertThat(byRank.get(1L)).isEqualTo(slot(H1, 10L, "h1"));
    assertThat(byRank.get(2L)).isEqualTo(slot(H2, 20L, "h2"));
    assertThat(byRank.get(3L)).isEqualTo(slot(H3, 30L, "h3"));
    assertThat(byRank).as("★ 名次是无缺口的全序（0..n−1，每只槽位恰好一次）").hasSize(slots.size());
    assertThat(poolLeft.get(GOV)).as("★ 超越 3 户 ⇒ 消耗 3 份").isEqualTo(7L);
    assertThat(overtook).as("每只被移动过的槽位都收到 overtook 标记").containsExactly(true);
  }

  /** ★ T9 的第三条：池子见底 ⇒ <b>后续不再置顶</b>（不是"垫付"、不是"欠着"）。 */
  @Test
  void anEmptyPoolStopsPromotionEntirelyAndNeverGoesNegative() {
    List<Slot> slots = book();
    Map<HouseholdId, Long> poolLeft = new LinkedHashMap<>(Map.of(GOV, 0L));

    Map<Long, Slot> byRank = advance(slots, controlsGov(0L), poolLeft, new ArrayList<>());

    assertThat(poolLeft.get(GOV)).as("★ 见底硬停：池子不许被扣成负数（不许先超后欠）").isZero();
    assertThat(byRank.get(0L)).as("池空 ⇒ 政府单一步都不许动").isEqualTo(slot(H1, 10L, "h1"));
    assertThat(byRank.get(3L)).isEqualTo(slot(GOV, 40L, "gov"));
  }

  /**
   * ★★ <b>N3（fail-closed 的精确定义）</b>：池里只剩 2 份、要超越 3 户 ⇒ 越过<b>付得起的那 2 户</b>后 <b>就地停住</b>（落点 = 第 3
   * 户之后），消耗恰好 2、池子恰好归零、并具名记一次硬停 —— 绝不"先超后欠"。
   */
  @Test
  void partialPoolStopsAtTheLastPaidOvertakeAndLeavesNoDebt() {
    List<Slot> slots = book();
    Map<HouseholdId, Long> poolLeft = new LinkedHashMap<>(Map.of(GOV, 2L));

    Map<Long, Slot> byRank = advance(slots, controlsGov(2L), poolLeft, new ArrayList<>());

    assertThat(poolLeft.get(GOV)).as("★ 消耗恰好 = 池子原有份数（付得起几户就超几户）").isZero();
    assertThat(byRank.get(0L)).as("h1 没被超越（它的那一份付不起）").isEqualTo(slot(H1, 10L, "h1"));
    assertThat(byRank.get(1L))
        .as("★ 政府单停在付得起的位置上（越过 h3/h2，停在 h1 之后）")
        .isEqualTo(slot(GOV, 40L, "gov"));
    assertThat(byRank.get(2L)).isEqualTo(slot(H2, 20L, "h2"));
    assertThat(byRank.get(3L)).isEqualTo(slot(H3, 30L, "h3"));
  }

  // ── 只改顺序、不改价格与量 ───────────────────────────────────────────────────────────

  /** ★ 冻结口径：置顶**只改撮合顺序**，槽位载荷（价/量）一个字节都不许变。 */
  @Test
  void promotionReordersSlotsWithoutTouchingPricesOrQuantities() {
    List<Slot> slots = book();
    List<Slot> before = List.copyOf(slots);
    Map<HouseholdId, Long> poolLeft = new LinkedHashMap<>(Map.of(GOV, 10L));

    Map<Long, Slot> byRank = advance(slots, controlsGov(10L), poolLeft, new ArrayList<>());

    assertThat(new ArrayList<>(byRank.values()))
        .as("★ 置顶前后是同一批槽位（顺序变、集合不变）")
        .containsExactlyInAnyOrderElementsOf(before);
    for (Slot slot : byRank.values()) {
      assertThat(slot.priceMilli()).as("★ 单价不许被置顶改写（政府不得改价）").isIn(10L, 20L, 30L, 40L);
      assertThat(slot.quantityMilli()).as("★ 挂单量不许被置顶改写").isEqualTo(100L);
    }
    assertThat(byRank.get(0L).priceMilli()).as("★ 被置顶的那一单带的是它自己的价（40），不是别人的价").isEqualTo(40L);
  }

  // ── 缺省中性（I-C2）：没人要求管控 ⇒ 逐值不变 ────────────────────────────────────────

  /** ★ 没有政府要求管控（或表为空）⇒ 名次 = 服务序，一份行政力都不消耗（旧世界逐值不变）。 */
  @Test
  void noMarketControlMeansServiceOrderAndZeroConsumption() {
    for (ProcurementPriorityInput input :
        List.of(ProcurementPriorityInput.none(), new ProcurementPriorityInput(Map.of()))) {
      List<Slot> slots = book();
      Map<HouseholdId, Long> poolLeft = new LinkedHashMap<>(Map.of(GOV, 10L));

      Map<Long, Slot> byRank = advance(slots, input, poolLeft, new ArrayList<>());

      assertThat(byRank.get(0L)).isEqualTo(slot(H1, 10L, "h1"));
      assertThat(byRank.get(3L)).isEqualTo(slot(GOV, 40L, "gov"));
      assertThat(poolLeft.get(GOV)).as("★ 没要求管控 ⇒ 一份都不消耗").isEqualTo(10L);
    }
  }

  /** ★ 池子跨簿共享（"每 tick 一份编制劳动力"）：两张簿各扣各的，合起来不超过池子。 */
  @Test
  void thePoolIsSharedAcrossBooksAndNeverOverdrawn() {
    Map<HouseholdId, Long> poolLeft = new LinkedHashMap<>(Map.of(GOV, 3L));

    // 第一张簿：超越 3 户 ⇒ 池子归零。
    advance(book(), controlsGov(3L), poolLeft, new ArrayList<>());
    assertThat(poolLeft.get(GOV)).isZero();

    // 第二张簿：池子已空 ⇒ 一步都不许再动（两张簿共享同一个池）。
    List<Slot> second = book();
    Map<Long, Slot> byRank = advance(second, controlsGov(3L), poolLeft, new ArrayList<>());
    assertThat(byRank.get(0L)).as("★ 池子跨簿共享：第一张簿吃光后第二张簿不再置顶").isEqualTo(slot(H1, 10L, "h1"));
    assertThat(poolLeft.get(GOV)).isZero();
  }

  /** ★ 一户只付一次：同一户有两只槽位时，超越它只消耗一份行政力（"每超越一户消耗一份"）。 */
  @Test
  void overtakingTheSameHouseholdTwiceCostsOnlyOneUnit() {
    List<Slot> slots = new ArrayList<>();
    slots.add(slot(H1, 10L, "h1-a"));
    slots.add(slot(H1, 11L, "h1-b"));
    slots.add(slot(GOV, 40L, "gov"));
    Map<HouseholdId, Long> poolLeft = new LinkedHashMap<>(Map.of(GOV, 5L));

    Map<Long, Slot> byRank = advance(slots, controlsGov(5L), poolLeft, new ArrayList<>());

    assertThat(byRank.get(0L).label()).as("政府单到了最前").isEqualTo("gov");
    assertThat(poolLeft.get(GOV)).as("★ 同一户的两只槽位只算一户 ⇒ 只消耗 1 份").isEqualTo(4L);
    assertThat(byRank.get(1L).label()).as("同一户的两只槽位保持相对序（canonical 序不许反转）").isEqualTo("h1-a");
    assertThat(byRank.get(2L).label()).isEqualTo("h1-b");
  }
}
