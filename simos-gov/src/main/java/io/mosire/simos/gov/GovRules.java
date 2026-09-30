package io.mosire.simos.gov;

/**
 * 行政公式的**唯一常量拼写点**（阶段 10a 的四需求常量；阶段 11a 补覆盖率/加成三常量；计划 §2.2 / §3）：治安/文书需求、 覆盖率上限与超编加成公式里出现的常量只此一处；阶段
 * 11 的 {@code GovDemand}/{@code GovEfficiency}/{@code GovDaily} **只引用这里**，不在别处手抄数字。
 *
 * <p>★ <b>三组常量的分工</b>：
 *
 * <ul>
 *   <li><b>需求</b>（{@link #SECURITY_PER_OFFICER} / {@link #PAPERWORK_PER_SCRIBE} / {@link
 *       #CITY_SECURITY_WEIGHT} / {@link #CITY_PAPERWORK_WEIGHT}）：逐格 {@code ceil(population / PER) +
 *       cityWeight}；
 *   <li><b>覆盖</b>（{@link #COVERAGE_FULL_PER_MILLE} = 1000‰）：需求 0 维记全额，有需求维取 {@code min(1000,
 *       supply×1000/demand)}；
 *   <li><b>加成</b>（{@link #BONUS_SATURATION} = 10、{@link #MAX_BONUS_PER_MILLE} = 100）：仅两维覆盖都满时，
 *       {@code bonus‰ = 100·s/(s+10)}（{@code s} = 超支百分数，整数向下取整）。{@code s=10}（超编 +10%）⇒ 50‰；{@code
 *       s=20} ⇒ 66‰（精确值 66.7‰，向下取整）；{@code s→∞} 渐近 100‰。
 * </ul>
 *
 * <p>★★ <b>默认值来源（四个需求常量都是"暂定值"）</b>：本仓此前没有任何"每人配多少治安/书吏"的常量可复用（既有的 {@code
 * EconomyVocabulary.RATION_MILLI_PER_PERSON} 等是口粮/布料量纲，与编制人数不同维），故本阶段按 <b>可玩量级</b> 取整值；计划 §3
 * 已把常量位置钉在 {@code GovRules}，但未给具体数字 ⇒ 这里是<b>待校准值</b>， <b>阶段 15
 * 的长跑校准</b>（紧凑三国一年期）负责把它们调到实际手感合适，届时只改本文件。★ 覆盖率/加成三常量是计划 §3 已钉死的公式常量，不是暂定值。
 *
 * <p>★★ <b>量级判据（控制方口径）</b>：紧凑三国格的 1.2 万–9 万人口，应产生<b>几十到几百</b>的编制需求，而不是几千—— 所以"每编制管多少人"取 500/1000
 * 档、城市权重取几十档，而不是取 10/20 那种会让每个村都需要上百编制的值。
 *
 * <p>★★ <b>10 万人口格的手算例</b>（公式见计划 §3：{@code ceil(population / PER) + (city ? WEIGHT : 0)}）：
 *
 * <ul>
 *   <li>非城市格：治安 = ceil(100000 / 500) = <b>200</b> 名 {@code YAMEN}；文书 = ceil(100000 / 1000) =
 *       <b>100</b> 名 {@code SCRIBE + POST}；
 *   <li>同格若是城市（有 {@code City.at()}）：治安 = 200 + 40 = <b>240</b>；文书 = 100 + 60 = <b>160</b>。
 * </ul>
 *
 * ⇒ 12,000 人口：治安 24（城市 64）、文书 12（城市 72）；90,000 人口：治安 180（城市 220）、文书 90（城市 150）—— 全落在"几十到几百"。
 */
public final class GovRules {

  private GovRules() {}

  /**
   * 治安编制（{@code YAMEN}）人均覆盖人口：<b>500 人/名</b>。
   *
   * <p>★ <b>量纲</b>：人/编制人员（分母的倒数就是每名治安覆盖多少人）——{@code ceil(population / 本常量)} 的单位是 <b>名治安</b>。★
   * <b>默认值来源</b>：暂定值，无可复用的既有常量，阶段 15 长跑校准（见类注释手算例）。
   */
  public static final long SECURITY_PER_OFFICER = 500L;

  /**
   * 文书编制（{@code SCRIBE + POST}）人均覆盖人口：<b>1000 人/名</b>。
   *
   * <p>★ <b>量纲</b>：人/编制人员——{@code ceil(population / 本常量)} 的单位是<b>名书吏（含驿传）</b>。
   * 「文书比治安管得宽」是刻意的：一个书吏处理的人口/档案量高于一名治安的管片量，也让 GOV 的初始编制能先立起来。 ★ <b>默认值来源</b>：暂定值，阶段 15
   * 长跑校准（见类注释手算例）。
   */
  public static final long PAPERWORK_PER_SCRIBE = 1000L;

  /**
   * 城市格治安需求的固定加项：<b>+40 名治安</b>。
   *
   * <p>★ <b>量纲</b>：编制人员/城市（直接加在 {@code ceil(population / SECURITY_PER_OFFICER)} 上，不是比例）。 ★
   * <b>为什么不是比例</b>：城市的人口密度会自然进入人口项，固定加项表达"有衙门/钱粮/流民集散的行政节点，即便人口不高也 要额外治安"这一质性事实。★
   * <b>默认值来源</b>：暂定值，阶段 15 长跑校准（见类注释手算例）。
   */
  public static final long CITY_SECURITY_WEIGHT = 40L;

  /**
   * 城市格文书需求的固定加项：<b>+60 名书吏</b>。
   *
   * <p>★ <b>量纲</b>：编制人员/城市（直接加在 {@code ceil(population / PAPERWORK_PER_SCRIBE)} 上，不是比例）。 ★
   * 比治安加项更高，理由同 {@link #PAPERWORK_PER_SCRIBE}（城市的档案/税册/公文量增长快于人口项）。 ★ <b>默认值来源</b>：暂定值，阶段 15
   * 长跑校准（见类注释手算例）。
   */
  public static final long CITY_PAPERWORK_WEIGHT = 60L;

  /**
   * 覆盖率/行政效率的<b>满值</b>：<b>1000‰</b>（= 1.0）。
   *
   * <p>★ <b>两个用途</b>：① 需求为 0 的维度记 {@code coverage = 本常量}（"无需求 = 全额覆盖"）； ② {@code bonus‰ =
   * 100·s/(s+10)} 的分母 1000 来自 {@code coverage × (1000 + bonus) / 本常量}——它是 per-mille 与"1 倍"之间的
   * 唯一换算点。★ <b>不是暂定值</b>：千分制本身是计划 §3 钉死的口径。
   */
  public static final long COVERAGE_FULL_PER_MILLE = 1000L;

  /**
   * 超编加成的<b>饱和参数</b>：公式分母里的 <b>10</b>（即 {@code bonus‰ = 100·s/(s+10)}）。
   *
   * <p>★ <b>量纲</b>：与 {@code s} 同维（{@code s} = 超支百分数，如 +10% ⇒ s=10）。{@code s = 本常量} 时加成恰为
   * 上限的一半（50‰），随后边际递减。★ <b>不是暂定值</b>：计划 §3 的精确拟合（+10%→50‰、+20%→66.7‰）由它决定。
   */
  public static final long BONUS_SATURATION = 10L;

  /**
   * 超编加成的<b>上限</b>：<b>100‰</b>（= +10%）。
   *
   * <p>★ <b>两个用途</b>：① 公式分子 {@code 100·s}；② 行政效率上限 {@code 1000 + 100 = 1100‰}。★ 对有限的 {@code s}，
   * {@code floor(100·s/(s+10))} 实际取不到 100（只在 {@code s→∞} 渐近），本常量仍是写下来的硬上限（防未来改公式/改类型时越界）。 ★
   * <b>不是暂定值</b>：上限 +10% 是计划 §3 的裁定。
   */
  public static final long MAX_BONUS_PER_MILLE = 100L;
}
