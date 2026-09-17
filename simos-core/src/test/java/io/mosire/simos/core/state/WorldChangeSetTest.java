package io.mosire.simos.core.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.state.ChangeSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link WorldChangeSet} 的行为用例：防御性拷贝、保序、构造期守卫、空集、ChangeSet 身份。
 *
 * <p>★ ChangeSet 是标记接口（0 个抽象方法）⇒ **不是函数式接口，lambda 不合法**——夹具一律匿名类 （M4 计划 Task 2 Step 7
 * 的同源坑，写在夹具里而不是留给执行者再踩一次）。
 */
class WorldChangeSetTest {

  private static ChangeSet marker() {
    return new ChangeSet() {};
  }

  /** 防御性拷贝：构造后改传入的 map 不得影响已构造的变更集；返回的 map 不可变。 */
  @Test
  void copiesModulesDefensively() {
    Map<String, ChangeSet> source = new LinkedHashMap<>();
    source.put("map", marker());
    WorldChangeSet world = new WorldChangeSet(source);

    source.put("social", marker());

    assertThat(world.modules()).containsOnlyKeys("map");
    assertThatThrownBy(() -> world.modules().put("unit", marker()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * 保序：迭代序 = 传入序。★ 5 个乱序键——{@code Map.copyOf} 的迭代序是散列槽位序，3 键实测有 7%~40% 恰好落回 插入序（M2 Task 5，30 次独立
   * JVM），键太少这条用例会假绿；4~6 键实测 0/30，故取 5。
   */
  @Test
  void preservesIterationOrder() {
    LinkedHashMap<String, ChangeSet> source = new LinkedHashMap<>();
    for (String namespace : new String[] {"zulu", "mike", "alpha", "whiskey", "bravo"}) {
      source.put(namespace, marker());
    }

    assertThat(new WorldChangeSet(source).modules().keySet())
        .containsExactly("zulu", "mike", "alpha", "whiskey", "bravo");
  }

  /** null 的键/值在构造期就炸，消息恰是守卫文案（删守卫后 null 会一路静默活进落盘路径才炸）。 */
  @Test
  void rejectsNullKeyAndValueAtConstruction() {
    Map<String, ChangeSet> nullKey = new LinkedHashMap<>();
    nullKey.put(null, marker());
    assertThatThrownBy(() -> new WorldChangeSet(nullKey))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("modules 的键");

    Map<String, ChangeSet> nullValue = new LinkedHashMap<>();
    nullValue.put("map", null);
    assertThatThrownBy(() -> new WorldChangeSet(nullValue))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("modules 的值");
  }

  /** 空集：分岔 revision 的变更集（C13）——空、不可变。 */
  @Test
  void emptyIsEmptyAndImmutable() {
    WorldChangeSet empty = WorldChangeSet.empty();

    assertThat(empty.modules()).isEmpty();
    assertThatThrownBy(() -> empty.modules().put("map", marker()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** 契约不空转：WorldChangeSet 有 ChangeSet 身份（spec §十 第 2 条，util 的首个 main 源码消费者）。 */
  @Test
  void isAChangeSet() {
    assertThat(WorldChangeSet.empty()).isInstanceOf(ChangeSet.class);
  }
}
