package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.region.RegionId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 单位侧管辖富结构 {@link Jurisdiction}（辖区阶段 5，2026-09-30）：构造期校验、税率表的**保序**与**不可变**。
 *
 * <p>★ 三条判据各自钉住生产类注里的一条硬口径：
 *
 * <ul>
 *   <li><b>构造期拒</b>：null map、键/值 null、税率 ∉[0,1000]、三个 {@code levy*CapPerCommand} 为负、{@code
 *       administrationPerMille} ∉[0,1000] 一律当场 {@link IllegalArgumentException}，**不静默钳制**；边界 0/1000
 *       合法。
 *   <li><b>保序</b>：税率表是 {@code LinkedHashMap} 拷贝 ⇒ 迭代序 = 插入序（**不用** {@code Map.copyOf}——它会打乱序，
 *       而展示/结算迭代依赖稳定序）。★ 夹具用 **5 键**：本仓实测小 map 落回哈希序有假绿，键数少不足以判别。
 *   <li><b>不可变</b>：构造后改**入参 map** 不影响已建对象（拷贝）；{@code taxRatePerMilleByRegion()} 直接 put/remove ⇒
 *       {@link UnsupportedOperationException}（{@code Collections.unmodifiableMap} 冻在赋值处）。
 * </ul>
 */
class JurisdictionTest {

  private static final RegionId R1 = new RegionId("r-1");
  private static final RegionId R2 = new RegionId("r-2");
  private static final RegionId R3 = new RegionId("r-3");

  // ── 构造期拒（类注的四条校验） ──────────────────────────────────

  @Test
  void constructorRejectsNullRegionMap() {
    assertThatThrownBy(() -> new Jurisdiction(null, 0L, 0L, 0L, 0))
        .as("null map 是编程错误；无管辖必须显式给空 map")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("taxRatePerMilleByRegion");
  }

  @Test
  void constructorRejectsNullRegionKeyOrNullRate() {
    Map<RegionId, Long> nullKey = new LinkedHashMap<>();
    nullKey.put(null, 100L);
    assertThatThrownBy(() -> new Jurisdiction(nullKey, 0L, 0L, 0L, 0))
        .as("键不得为 null")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("键与值都不得为 null");

    Map<RegionId, Long> nullValue = new LinkedHashMap<>();
    nullValue.put(R1, null);
    assertThatThrownBy(() -> new Jurisdiction(nullValue, 0L, 0L, 0L, 0))
        .as("值不得为 null")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("键与值都不得为 null");
  }

  @Test
  void constructorRejectsTaxRateBelowZero() {
    assertThatThrownBy(() -> new Jurisdiction(Map.of(R1, -1L), 0L, 0L, 0L, 0))
        .as("负税率必须当场拒、不钳制到 0")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("税率")
        .hasMessageContaining("r-1=-1");
  }

  @Test
  void constructorRejectsTaxRateAboveThousand() {
    assertThatThrownBy(() -> new Jurisdiction(Map.of(R1, 1001L), 0L, 0L, 0L, 0))
        .as("1001‰ 越界（上界 1000）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("税率")
        .hasMessageContaining("r-1=1001");
  }

  @Test
  void constructorRejectsNegativeLevyCaps() {
    assertThatThrownBy(() -> new Jurisdiction(Map.of(), -1L, 0L, 0L, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("levyGrainCapPerCommand")
        .hasMessageContaining("-1");
    assertThatThrownBy(() -> new Jurisdiction(Map.of(), 0L, -1L, 0L, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("levyMoneyCapPerCommand")
        .hasMessageContaining("-1");
    assertThatThrownBy(() -> new Jurisdiction(Map.of(), 0L, 0L, -1L, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("levyManpowerCapPerCommand")
        .hasMessageContaining("-1");
  }

  @Test
  void constructorRejectsAdministrationOutsideRange() {
    assertThatThrownBy(() -> new Jurisdiction(Map.of(), 0L, 0L, 0L, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("administrationPerMille")
        .hasMessageContaining("-1");
    assertThatThrownBy(() -> new Jurisdiction(Map.of(), 0L, 0L, 0L, 1001))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("administrationPerMille")
        .hasMessageContaining("1001");
  }

  /** ★ 边界：税率 0/1000、三个 cap 0、行政 0/1000 都是**合法**值（"拒越界"不能误伤边界）。 */
  @Test
  void boundaryValuesZeroAndThousandAreLegal() {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(R1, 0L);
    rates.put(R2, 1000L);
    Jurisdiction jurisdiction = new Jurisdiction(rates, 0L, 0L, 0L, 0);
    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .containsEntry(R1, 0L)
        .containsEntry(R2, 1000L);
    assertThat(jurisdiction.levyGrainCapPerCommand()).isZero();
    assertThat(jurisdiction.levyMoneyCapPerCommand()).isZero();
    assertThat(jurisdiction.levyManpowerCapPerCommand()).isZero();
    assertThat(jurisdiction.administrationPerMille()).isZero();

    Jurisdiction administrationFull = new Jurisdiction(Map.of(), 0L, 0L, 0L, 1000);
    assertThat(administrationFull.administrationPerMille())
        .as("1000‰ 是行政能力的上界、不是越界")
        .isEqualTo(1000);
  }

  // ── 保序：迭代序 = 插入序（不是哈希序） ──────────────────────────

  /**
   * ★ **5 键的保序判据**：插入序刻意选成哈希序会散开的序列。若实现改用 {@code Map.copyOf}，键序当场变、 本用例红（本仓纪律：3
   * 键的小夹具可能恰好落回插入序而假绿，故键数取 5）。
   */
  @Test
  void iterationOrderIsInsertionOrderNotHashOrder() {
    List<RegionId> insertionOrder =
        List.of(
            new RegionId("r-9"),
            new RegionId("r-1"),
            new RegionId("r-5"),
            new RegionId("r-3"),
            new RegionId("r-7"));
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    long rate = 100L;
    for (RegionId region : insertionOrder) {
      rates.put(region, rate);
      rate += 100L;
    }

    Jurisdiction jurisdiction = new Jurisdiction(rates, 0L, 0L, 0L, 0);

    assertThat(new ArrayList<>(jurisdiction.taxRatePerMilleByRegion().keySet()))
        .as("LinkedHashMap 拷贝必须保持插入序（Map.copyOf 会打散）")
        .containsExactlyElementsOf(insertionOrder);
    assertThat(new ArrayList<>(jurisdiction.taxRatePerMilleByRegion().values()))
        .as("值序同样按插入序")
        .containsExactly(100L, 200L, 300L, 400L, 500L);
    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .containsExactly(
            Map.entry(insertionOrder.get(0), 100L),
            Map.entry(insertionOrder.get(1), 200L),
            Map.entry(insertionOrder.get(2), 300L),
            Map.entry(insertionOrder.get(3), 400L),
            Map.entry(insertionOrder.get(4), 500L));
  }

  // ── 不可变：拷贝入参 + 只读视图 ────────────────────────────────

  @Test
  void constructorCopiesTheInputMapSoLaterMutationsDoNotLeak() {
    Map<RegionId, Long> input = new LinkedHashMap<>();
    input.put(R1, 100L);
    input.put(R2, 200L);

    Jurisdiction jurisdiction = new Jurisdiction(input, 1L, 2L, 3L, 4);

    input.put(R3, 300L); // 新增不得进已建对象
    input.put(R1, 999L); // 改值不得进已建对象
    input.remove(R2); // 删除不得进已建对象

    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .as("构造期做的是拷贝，不是持有入参引用")
        .containsOnlyKeys(R1, R2)
        .containsEntry(R1, 100L)
        .containsEntry(R2, 200L);
  }

  @Test
  void returnedRegionMapIsUnmodifiable() {
    Jurisdiction jurisdiction = new Jurisdiction(Map.of(R1, 100L), 0L, 0L, 0L, 0);
    Map<RegionId, Long> view = jurisdiction.taxRatePerMilleByRegion();

    assertThatThrownBy(() -> view.put(R2, 1L))
        .as("直接 put 必须抛")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> view.remove(R1))
        .as("直接 remove 必须抛")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(view).as("抛完之后原值不动").containsEntry(R1, 100L);
  }
}
