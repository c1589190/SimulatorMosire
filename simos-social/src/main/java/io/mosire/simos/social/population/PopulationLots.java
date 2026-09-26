package io.mosire.simos.social.population;

import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ **人口批次的命名约定**（R1）：{@link PopulationGroup#id()} 由**调用方**给短名（{@link PeopleLotId} 的自述原文： "调用方给的短名
 * tag"），本类就是本仓当前那一份调用方命名 —— 它让"这批人是谁"在**没有**额外字段的前提下可判定。
 *
 * <pre>
 * 农村（村镇）批次：  rural:&lt;q&gt;_&lt;r&gt;:&lt;SEX&gt;:&lt;CHORT&gt;     例：{@code rural:0_0:MALE:1}
 * 城镇（城市）批次：  urban:&lt;cityId&gt;:&lt;SEX&gt;:&lt;CHORT&gt;    例：{@code urban:c-0_0:FEMALE:1}
 * </pre>
 *
 * <p>★ {@code <CHORT>} 是调用方给的**同格同性别内部的细分短名**（本仓当前用它区分**年龄段**： {@code PopulationGroup} 的 map
 * 键必须互异，而"同一格的男性"会按年龄分成多批）。它是**调用方的词**， social 不解释它——故 {@link
 * io.mosire.simos.social.SocialData#urbanPopulationAt(io.mosire.simos.map.CityId)} 按**前缀**（{@code
 * urban:<cityId>:}，见 {@link #urbanPrefix}）归属，与有多少个细分无关。
 *
 * <p>★★ **为什么命名而不是加字段**：{@code PopulationGroup} 的形状由设计稿 §三 定死（id / residence / sex / count / age /
 * anchor），**不许多一个"城乡"或"属于哪座城"的字段** —— 那正是把 {@code ClassRow} 的老毛病（拿标签当主键）换个地方重演。 而"城里的人"这条关系在 R2+ 会由
 * {@code Relation}/{@code LaborAllocation} **显式**表达（人 ↔ 主体 ↔ 活动）， 届时本类 只是**创世期的命名**，不再是判据的唯一来源。
 *
 * <p>★★ **一份拼写点**：lot id 只在本类里拼。{@link io.mosire.simos.social.SocialData#urbanPopulationAt(CityId)}
 * 按同一份 规则**反查**城的批次，故"城的城镇人口"完全从 {@code groups} 派生（R1 的 T5：{@code SocialCity.population} 不再是字段）。
 * 拼歪了不会静默——它会让"Σ 城的人口 == 计划里的城市人口"当场对不上（{@code WorldgenInitializeToolTest} 逐值断言）。
 *
 * <p>★ **id 是身份，不是给人看的名字**：它是 {@code FieldDelta} 的 String key、要进 JSON、要跨 revision 稳定。 故分隔符用不可能出现在
 * {@link HexCoord#toString()}（{@code q_r}）与 {@link CityId}（裸值）里的 {@code ':'} 之外还要靠**前缀**判城乡 ——
 * {@code "rural:"} 与 {@code "urban:"} 互不为前缀，判定无歧义。
 */
public final class PopulationLots {

  /**
   * 农村（村镇）批次的前缀。
   *
   * <p>★★ <b>2026-09-27（H0.2）：前缀的唯一拼写点已搬到 {@link ResidenceKind#lotPrefix()}</b> ——
   * 因为 {@code simos-economy} <b>看不见 {@code simos-social}</b>（铁律 3），却必须从劳动配额表的批次 id
   * 推出"这批人住哪种居住类型"（家户身份键带居住维，裁定 R-N1-A）。两处各拼一份前缀 =
   * 同一个格式的两处拼写点，本仓明令禁止。本常量保留为<b>转发</b>（调用点不必改，字的来源只有一个）。
   */
  public static final String RURAL_PREFIX = ResidenceKind.RURAL.lotPrefix();

  /** 城镇（城市）批次的前缀。★ 同 {@link #RURAL_PREFIX}：唯一拼写点在 {@link ResidenceKind#lotPrefix()}。 */
  public static final String URBAN_PREFIX = ResidenceKind.URBAN.lotPrefix();

  private PopulationLots() {}

  /** 某格某性别某细分的**农村**批次 id。 */
  public static PeopleLotId rural(HexCoord hex, Sex sex, String cohort) {
    if (hex == null) {
      throw new IllegalArgumentException("hex 不得为 null");
    }
    if (sex == null) {
      throw new IllegalArgumentException("sex 不得为 null");
    }
    return PeopleLotId.parse(RURAL_PREFIX + hex + ":" + sex.name() + ":" + requireCohort(cohort));
  }

  /** 某城某性别某细分的**城镇**批次 id（城 = {@link CityId} 这个稳定身份，不是它的坐标）。 */
  public static PeopleLotId urban(CityId city, Sex sex, String cohort) {
    if (city == null) {
      throw new IllegalArgumentException("city 不得为 null");
    }
    if (sex == null) {
      throw new IllegalArgumentException("sex 不得为 null");
    }
    return PeopleLotId.parse(urbanPrefix(city) + sex.name() + ":" + requireCohort(cohort));
  }

  /**
   * ★★ **生育结算月**的细分短名（R4）：{@code b<结算月序号>}（{@code b} = born）。
   *
   * <p>★ 它让"当月出生的人"各自成批（同性别、同年龄 0 天、同锚点 ⇒ 属性确实完全相同），从而**年龄结构随推进演化**； 而**同一批母亲**在该月生的孩子汇进同一条批次（见
   * {@code PopulationDynamics.appendBirths}）。
   */
  public static String bornCohort(long nowTick, long settlementDays) {
    if (nowTick < 0L || settlementDays < 1L) {
      throw new IllegalArgumentException(
          "结算日必须 ≥ 0、结算周期必须 ≥ 1: " + nowTick + " / " + settlementDays);
    }
    return "b" + (nowTick / settlementDays);
  }

  /**
   * ★★ **一个新生批次的 id**（R4）：{@code <母亲批次 id 的前缀><性别>:b<结算月序号>}。
   *
   * <pre>
   * rural:0_0:FEMALE:1  ──(MALE, 第 360 天)──→  rural:0_0:MALE:b12
   * urban:c-0_0:FEMALE:1 ─(FEMALE, 第 360 天)─→  urban:c-0_0:FEMALE:b12
   * </pre>
   *
   * <p>★★ **为什么按"母亲的前缀"而不是另起一套命名**：{@link #isUrban} 与 {@code SocialData#urbanPopulationAt(CityId)}
   * 都按 id 的**前缀**归属 —— 另起一套会让"城里生的人"在城乡归属上凭空变成农村人（而那条归属正是经济侧分池的依据）。 前缀 = 母亲 id
   * 去掉最后一段（细分），故"住在哪、属于哪座城"自动继承，**不需要第二个字段**。
   */
  public static PeopleLotId born(PopulationGroup mother, Sex sex, String cohort) {
    if (mother == null) {
      throw new IllegalArgumentException("mother 不得为 null");
    }
    if (sex == null) {
      throw new IllegalArgumentException("sex 不得为 null");
    }
    String id = mother.id().value();
    int lastSeparator = id.lastIndexOf(':');
    if (lastSeparator < 0) {
      throw new IllegalArgumentException("母亲批次的 id 必须形如 <前缀>:<性别>:<细分>（R4 的新生批次按前缀继承城乡归属）: " + id);
    }
    return PeopleLotId.parse(
        id.substring(0, lastSeparator + 1) + sex.name() + ":" + requireCohort(cohort));
  }

  /**
   * 某城的城镇批次 id 的**公共前缀**：{@code urban:<cityId>:}。{@link
   * io.mosire.simos.social.SocialData#urbanPopulationAt(CityId)} 按它归属（前缀含结尾分隔符 ⇒ {@code c-1} 不会吞掉
   * {@code c-12} 的批次）。
   *
   * <p>★ **{@code ':'} 不得出现在城 id 里**：它同时是分隔符 ⇒ 含它的 id 会让前缀归属**有歧义**（{@code urban:a:b:} 既可能是城 {@code
   * a:b} 的、也可能是城 {@code a} 下细分 {@code b} 的）。本方法对这种 id fail-closed 抛 —— 生成器产出的城 id 形如 {@code
   * c-<q>_<r>}，不含 {@code ':'}，故这条从不挡正常路径。
   */
  public static String urbanPrefix(CityId city) {
    if (city == null) {
      throw new IllegalArgumentException("city 不得为 null");
    }
    if (city.value().indexOf(':') >= 0) {
      throw new IllegalArgumentException("城 id 不得含 ':'（它是批次 id 的分隔符，含它会让归属有歧义）: " + city);
    }
    return URBAN_PREFIX + city + ":";
  }

  /** 细分短名：非空、不含分隔符（同上，含它会让 id 的段数不定）。 */
  private static String requireCohort(String cohort) {
    if (cohort == null || cohort.isBlank()) {
      throw new IllegalArgumentException("cohort 不得为空白");
    }
    if (cohort.indexOf(':') >= 0) {
      throw new IllegalArgumentException("cohort 不得含 ':'（它是批次 id 的分隔符）: " + cohort);
    }
    return cohort;
  }

  /**
   * 这条批次是不是**城镇**批次（按 id 前缀判）。
   *
   * <p>★ 判据只有这一处：{@code EconomySeeder} 用它把同一格的 {@code Σ count} 切成农业（农村）与手工业（城镇）两池 ——
   * 与创世时同一个命名约定，故两侧不可能各说一套。
   */
  public static boolean isUrban(PopulationGroup group) {
    if (group == null) {
      throw new IllegalArgumentException("group 不得为 null");
    }
    return group.id().value().startsWith(URBAN_PREFIX);
  }
}
