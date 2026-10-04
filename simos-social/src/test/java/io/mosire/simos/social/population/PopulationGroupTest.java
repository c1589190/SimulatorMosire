package io.mosire.simos.social.population;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import org.junit.jupiter.api.Test;

/** {@link PopulationGroup}：构造期不变量 + **年龄是派生纯函数**（R1 的 T2/T6 的判据）。 */
class PopulationGroupTest {

  private static final PeopleLotId ID = PeopleLotId.parse("rural:0_0:MALE");
  private static PopulationGroup group(long count, long ageDays, long anchorTick) {
    return new PopulationGroup(ID, Sex.MALE, count, ageDays, anchorTick);
  }

  @Test
  void rejectsNullsAndNegativeNumbers() {
    assertThatThrownBy(() -> new PopulationGroup(null, Sex.MALE, 1L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PopulationGroup(ID, null, 1L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PopulationGroup(ID, null, 1L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PopulationGroup(ID, Sex.MALE, -1L, 0L, 0L))
        .as("count 为负")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PopulationGroup(ID, Sex.MALE, 1L, -1L, 0L))
        .as("ageAtAnchorDays 为负")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PopulationGroup(ID, Sex.MALE, 1L, 0L, -1L))
        .as("anchorTick 为负")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroCountIsLegal() {
    assertThat(group(0L, 0L, 0L).count()).as("空批是合法状态（人迁走/死绝后身份还在）").isZero();
  }

  /**
   * ★★ **年龄是"锚点 + 时间差"的现算值，不是每天改的字段**（T6 的全部内容：推进里"变老"不写任何状态）。
   *
   * <p>★ 判别力（**逐条核过**）：把 {@code ageDaysAt} 写成"恒返回 {@code ageAtAnchorDays}"（忘了加时间差）⇒ **第二、三条**
   * 红（第一条恰在锚点上，两种写法同值 —— 它守的是"查询点 == 锚点"这个端点，不是时间差）；写成 {@code ageAtAnchorDays + nowTick}（忘了减锚点）⇒
   * 第二、三条同样红。
   */
  @Test
  void ageIsDerivedFromTheAnchorAndTheQueryTick() {
    PopulationGroup atAnchor = group(100L, 10_000L, 40L);

    assertThat(atAnchor.ageDaysAt(40L)).as("查询点 == 锚点 ⇒ 就是锚点年龄").isEqualTo(10_000L);
    assertThat(atAnchor.ageDaysAt(41L)).as("+1 天").isEqualTo(10_001L);
    assertThat(atAnchor.ageDaysAt(400L)).as("+360 天").isEqualTo(10_360L);
    assertThat(group(100L, 10_000L, 0L).ageDaysAt(400L))
        .as("锚点在第 0 天时与上式同值（锚点不是常量 0）")
        .isEqualTo(10_400L);
  }

  @Test
  void ageBeforeTheAnchorIsTheLinearExtensionNotAnError() {
    assertThat(group(100L, 10_000L, 40L).ageDaysAt(30L))
        .as("回溯查询（重放/分支比较）按同一公式往回推，不抛")
        .isEqualTo(9_990L);
  }
}
