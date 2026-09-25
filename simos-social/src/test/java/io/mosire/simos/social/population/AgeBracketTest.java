package io.mosire.simos.social.population;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link AgeBracket}：**年龄档边界的唯一定义处**（R1.5）。
 *
 * <p>★ 判别力：这几条把"全仓只有一套边"钉成事实 —— 经济侧的创世折算（{@code EconomySeeder.ageBracketOf}）与读口（年龄结构）都从这里取边，
 * 改本枚举的上界**必须**同时让本类与 {@code PopulationSeederTest}（代表年龄落在自己那一档）一起红，而不是一边悄悄改、另一边照旧。
 */
class AgeBracketTest {

  private static final long DAYS_PER_YEAR = 365L;

  @Test
  void vocabularyIsTheThreeD4BracketsInOrder() {
    assertThat(AgeBracket.values())
        .as("词表序 = 0-14 → 15-59 → 60+（与经济侧的占比表、代表年龄表三处同序）")
        .containsExactly(AgeBracket.CHILD, AgeBracket.ADULT, AgeBracket.ELDER);
    assertThat(AgeBracket.CHILD.key()).isEqualTo("0-14");
    assertThat(AgeBracket.ADULT.key()).isEqualTo("15-59");
    assertThat(AgeBracket.ELDER.key()).isEqualTo("60+");
  }

  /**
   * ★ 边界的**四侧各钉一次**（{@code 15×365 − 1} 与 {@code 15×365}、{@code 60×365 − 1} 与 {@code 60×365}）：把
   * {@code <} 写成 {@code ≤}、或把某一档少算一天，都会有一条红。
   */
  @Test
  void ofUsesThe15And60YearEdgesExclusivelyAtTheUpperBound() {
    assertThat(AgeBracket.of(0L)).isEqualTo(AgeBracket.CHILD);
    assertThat(AgeBracket.of(15L * DAYS_PER_YEAR - 1L))
        .as("14 岁零 364 天 ⇒ 0-14")
        .isEqualTo(AgeBracket.CHILD);
    assertThat(AgeBracket.of(15L * DAYS_PER_YEAR)).as("15 岁当天 ⇒ 15-59").isEqualTo(AgeBracket.ADULT);
    assertThat(AgeBracket.of(60L * DAYS_PER_YEAR - 1L))
        .as("59 岁零 364 天 ⇒ 15-59")
        .isEqualTo(AgeBracket.ADULT);
    assertThat(AgeBracket.of(60L * DAYS_PER_YEAR)).as("60 岁当天 ⇒ 60+").isEqualTo(AgeBracket.ELDER);
    assertThat(AgeBracket.of(Long.MAX_VALUE)).as("年龄没有上界 ⇒ 末档兜底（不抛）").isEqualTo(AgeBracket.ELDER);
  }

  @Test
  void ofRejectsNegativeAges() {
    assertThatThrownBy(() -> AgeBracket.of(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ageDays 不得为负");
  }

  /**
   * ★ {@code boundedMaxExclusiveDays()} 就是**经济侧那张表**（{@code
   * EconomySeeder.AGE_BRACKET_MAX_EXCLUSIVE_DAYS} 是它的投影）：逐值钉住 15 岁 / 60 岁，且**每次返回新数组**（共享可变数组 =
   * 对外开一个改参数的后门，SpotBugs 实测报过同族问题）。
   */
  @Test
  void boundedMaxExclusiveDaysProjectsTheEdgesWithoutSharingTheArray() {
    long[] edges = AgeBracket.boundedMaxExclusiveDays();
    assertThat(edges).containsExactly(15L * DAYS_PER_YEAR, 60L * DAYS_PER_YEAR);
    assertThat(edges).as("末档无上界 ⇒ 表长 = 词表长 − 1").hasSize(AgeBracket.values().length - 1);

    edges[0] = -1L; // 改坏拿到的副本
    assertThat(AgeBracket.boundedMaxExclusiveDays())
        .as("拿到的数组是副本：改它不许影响下一次调用")
        .containsExactly(15L * DAYS_PER_YEAR, 60L * DAYS_PER_YEAR);
  }

  /** 三档的键互异且非空（读口按它发 JSON 的键：重名会让两条不同的档合成一条）。 */
  @Test
  void keysAreNonBlankAndDistinct() {
    assertThat(AgeBracket.values())
        .extracting(AgeBracket::key)
        .doesNotContainNull()
        .doesNotHaveDuplicates();
  }
}
