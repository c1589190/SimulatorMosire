package io.mosire.simos.army;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.army.testing.ArmyFixtures;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ArmyData} 的构造期不变量与"缺键 = 空"的兼容口径（T2a / D-012）。
 *
 * <p>★ 判据来源：{@code ArmyData} 类注（键必须等于值内 id / 缺键收空 / 保序不可变）—— 本类逐条钉住，不做"抛了就算过"的宽松断言。
 */
class ArmyDataTest {

  @Test
  void treatsAMissingTableAsEmpty() {
    ArmyData data = new ArmyData(null);

    assertThat(data.combats()).as("旧档缺 combats 键 ⇒ 空表（fail-closed，不抛）").isEmpty();
    assertThat(data).as("与显式空表逐字相等").isEqualTo(ArmyData.empty());
  }

  @Test
  void rejectsNullKeysAndValues() {
    Map<CombatRecordId, CombatRecord> nullKey = new LinkedHashMap<>();
    nullKey.put(null, ArmyFixtures.duelRecord(ArmyFixtures.C1, 12L));
    Map<CombatRecordId, CombatRecord> nullValue = new LinkedHashMap<>();
    nullValue.put(ArmyFixtures.C1, null);

    assertThatThrownBy(() -> new ArmyData(nullKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");
    assertThatThrownBy(() -> new ArmyData(nullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为 null");
  }

  @Test
  void rejectsAKeyThatDisagreesWithTheValueId() {
    Map<CombatRecordId, CombatRecord> wrongKey = new LinkedHashMap<>();
    wrongKey.put(ArmyFixtures.C2, ArmyFixtures.duelRecord(ArmyFixtures.C1, 12L));

    assertThatThrownBy(() -> new ArmyData(wrongKey))
        .as("★ 同一个聚合键有第二个拼写点正是这条守卫要消灭的")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("键必须与")
        .hasMessageContaining("c-2")
        .hasMessageContaining("c-1");
  }

  /**
   * ★ 保序：**正反两个插入序都要逐位对得上**。
   *
   * <p>判别力（当场量过）：这 5 个键用 {@code Map.copyOf} 实测迭代序恒为 {@code [c-1,c-2,c-3,c-4,c-5]}，与这里的正序、反序 插入序都不同
   * ⇒ 把构造器换成"不承诺顺序的容器"时，本用例至少有这一条会红，不是概率事件。
   */
  @Test
  void preservesTheInsertionOrderInBothDirections() {
    CombatRecord[] records = {
      ArmyFixtures.duelRecord(ArmyFixtures.C1, 12L),
      ArmyFixtures.duelRecord(ArmyFixtures.C2, 12L),
      ArmyFixtures.duelRecord(ArmyFixtures.C3, 12L),
      ArmyFixtures.duelRecord(ArmyFixtures.C4, 12L),
      ArmyFixtures.duelRecord(ArmyFixtures.C5, 12L)
    };
    int[] forward = {2, 0, 4, 1, 3};

    Map<CombatRecordId, CombatRecord> forwardMap = new LinkedHashMap<>();
    Map<CombatRecordId, CombatRecord> backwardMap = new LinkedHashMap<>();
    for (int index : forward) {
      forwardMap.put(records[index].id(), records[index]);
    }
    for (int index = forward.length - 1; index >= 0; index--) {
      backwardMap.put(records[forward[index]].id(), records[forward[index]]);
    }

    ArmyData forwardData = new ArmyData(forwardMap);
    ArmyData backwardData = new ArmyData(backwardMap);

    assertThat(forwardData.combats().keySet())
        .as("插入序 [c-3,c-1,c-5,c-2,c-4] 逐位保留")
        .containsExactly(
            ArmyFixtures.C3, ArmyFixtures.C1, ArmyFixtures.C5, ArmyFixtures.C2, ArmyFixtures.C4);
    assertThat(backwardData.combats().keySet())
        .as("反序插入也逐位保留（同一个容器的两个方向 ⇒ 顺序是内容的纯函数）")
        .containsExactly(
            ArmyFixtures.C4, ArmyFixtures.C2, ArmyFixtures.C5, ArmyFixtures.C1, ArmyFixtures.C3);
  }

  @Test
  void freezesTheTableAndCopiesTheInput() {
    Map<CombatRecordId, CombatRecord> source = new LinkedHashMap<>();
    source.put(ArmyFixtures.C1, ArmyFixtures.duelRecord(ArmyFixtures.C1, 12L));
    ArmyData data = new ArmyData(source);

    assertThatThrownBy(
            () ->
                data.combats().put(ArmyFixtures.C2, ArmyFixtures.duelRecord(ArmyFixtures.C2, 12L)))
        .as("★ 冻在赋值处：外部拿到的是一张不可变表")
        .isInstanceOf(UnsupportedOperationException.class);

    source.put(ArmyFixtures.C2, ArmyFixtures.duelRecord(ArmyFixtures.C2, 12L));
    assertThat(data.combats()).as("构造后改源表不得穿透（防御性拷贝）").hasSize(1);
    assertThat(data.combats()).containsOnlyKeys(ArmyFixtures.C1);
  }

  @Test
  void withCombatDerivesTheKeyFromTheValueAndUpsertsInPlace() {
    ArmyData base =
        ArmyData.empty()
            .withCombat(ArmyFixtures.duelRecord(ArmyFixtures.C1, 12L))
            .withCombat(ArmyFixtures.duelRecord(ArmyFixtures.C2, 13L));
    CombatRecord replacement = ArmyFixtures.singleOutcomeRecord(ArmyFixtures.C1, 99L);

    ArmyData next = base.withCombat(replacement);

    assertThat(next.combats().get(ArmyFixtures.C1)).as("同 id 后写覆盖前写").isEqualTo(replacement);
    assertThat(next.combats().keySet())
        .as("覆盖不改变键的插入位置（LinkedHashMap.put 的既有位置保留）")
        .containsExactly(ArmyFixtures.C1, ArmyFixtures.C2);
    assertThat(base.combats().get(ArmyFixtures.C1))
        .as("原切片不受影响（值对象语义）")
        .isEqualTo(ArmyFixtures.duelRecord(ArmyFixtures.C1, 12L));
  }

  @Test
  void withCombatRejectsNull() {
    assertThatThrownBy(() -> ArmyData.empty().withCombat(null))
        .isInstanceOf(NullPointerException.class);
  }
}
