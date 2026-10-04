package io.mosire.simos.social.population;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import org.junit.jupiter.api.Test;

/**
 * {@link PopulationLots}：**命名约定**的守门人。
 *
 * <p>★ 判别力为什么必须在这里：{@code SocialData.urbanPopulationAt(city)} 与 {@code EconomySeeder} 的
 * "农村/城镇两池"都按这份约定**反查**批次，而约定本身**不在任何数据里** —— 它错了不会有编译错，只会让"城的人口变成 0"
 * 这种看似正常的错误跑完全程。故这里把拼写钉死；真档的端到端数字（`Σ 城人口 == 计划里的城市人口`）是第二道。
 */
class PopulationLotsTest {

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final CityId C1 = new CityId("c-0_0");

  @Test
  void ruralLotIsSpeltRuralColonHexColonSexColonCohort() {
    assertThat(PopulationLots.rural(H00, Sex.MALE, "1").value()).isEqualTo("rural:0_0:MALE:1");
    assertThat(PopulationLots.rural(new HexCoord(-39, -71), Sex.FEMALE, "1").value())
        .isEqualTo("rural:-39_-71:FEMALE:1");
  }

  @Test
  void urbanLotIsSpeltUrbanColonCityIdColonSexColonCohort() {
    assertThat(PopulationLots.urban(C1, Sex.FEMALE, "2").value()).isEqualTo("urban:c-0_0:FEMALE:2");
  }

  /** ★ 前缀判城乡：它没有"猜"的余地（两个前缀互不为前缀），且不依赖坐标/城名恰好长什么样。 */
  @Test
  void isUrbanJudgesByPrefixOnly() {
    assertThat(PopulationLots.isUrban(group(PopulationLots.urban(C1, Sex.MALE, "1")))).isTrue();
    assertThat(PopulationLots.isUrban(group(PopulationLots.rural(H00, Sex.MALE, "1")))).isFalse();
  }

  /**
   * ★★ **城的前缀带结尾分隔符**：{@code urban:c-1:} 不是 {@code urban:c-12:...} 的前缀 ⇒ 城 {@code c-1} 不会吞掉城 {@code
   * c-12} 的批次（短 id 吞长 id 是前缀归属的经典坑）。
   */
  @Test
  void urbanPrefixIsTerminatedSoShortCityIdsDoNotSwallowLongOnes() {
    assertThat(PopulationLots.urbanPrefix(new CityId("c-1"))).isEqualTo("urban:c-1:");
    assertThat(PopulationLots.urbanPrefix(new CityId("c-12")))
        .as("c-12 的批次不以 urban:c-1: 起头")
        .doesNotStartWith(PopulationLots.urbanPrefix(new CityId("c-1")));
  }

  /** 城 id 含分隔符 ⇒ 归属有歧义 ⇒ fail-closed 抛（生成器产出的 id 形如 {@code c-<q>_<r>}，不会踩到）。 */
  @Test
  void cityIdWithTheSeparatorIsRejected() {
    assertThatThrownBy(() -> PopulationLots.urbanPrefix(new CityId("a:b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得含 ':'");
  }

  /** 细分短名同样不得含分隔符（否则 id 的段数不定、前缀归属会串味）。 */
  @Test
  void cohortMustBeNonBlankAndSeparatorFree() {
    assertThatThrownBy(() -> PopulationLots.rural(H00, Sex.MALE, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PopulationLots.rural(H00, Sex.MALE, "a:b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 两个方向都拒 null：命名是构造批次 id 的入口，静默放过 null 会造出 "rural:null:MALE" 这种假身份。 */
  @Test
  void rejectsNullsInBothDirections() {
    assertThatThrownBy(() -> PopulationLots.rural(null, Sex.MALE, "1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PopulationLots.rural(H00, null, "1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PopulationLots.urban(null, Sex.MALE, "1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PopulationLots.urban(C1, null, "1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PopulationLots.isUrban(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 批次本体不是本用例关心的（只借它的 id），故落点固定 H00。 */
  private static PopulationGroup group(PeopleLotId id) {
    return new PopulationGroup(id, Sex.MALE, 1L, 0L, 0L);
  }
}
