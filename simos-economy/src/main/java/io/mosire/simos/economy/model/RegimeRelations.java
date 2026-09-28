package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.SubsistenceObligation;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ **{@code regime} → 默认生产关系的唯一拼写点**（S1 阶段 4+5 Task 2；spec §六 + 计划 R7/R8 + 裁定 E2/E4/E9）。
 *
 * <p>★ **制度只负责初始化、不负责持续约束**（spec §2.4 原文「同一个 {@code feudal} 可以有 A 格地租 30% / B 格五五分成 / C 格领主直营」）⇒
 * 本表只回答**一个**问题：「载荷缺 {@code relation} 键时该推出哪一组规则」。运行期的真值是 {@code
 * EconomyData.relations}（表可以逐产业覆写）。形制**逐字照** {@link RegimeOperators}：{@code final class} + 私有构造 +
 * 静态块 + {@code LinkedHashMap} + {@code Collections.unmodifiableMap}（★ **绝不用 {@code Map.of}** ——
 * 错误消息要列出档位， 迭代序必须是内容的纯函数）。
 *
 * <p>★★ <b>四档的规则（R8 表；★ 两处对 spec §六 的修订已在下面就地注明）</b>：
 *
 * <table border="1">
 *   <caption>档位 → 默认规则</caption>
 *   <tr><th>{@code regime}</th><th>默认规则（按表序 = {@code priority} 序）</th></tr>
 *   <tr>
 *     <td>{@link RegimeOperators#FEUDAL}</td>
 *     <td>实物给养（{@code FIXED_IN_KIND_PER_LABOR} × 劳动量，粮）给该格<b>四个阶层</b> cohort 各一条 +
 *         <b>地租</b>（{@code OUTPUT_SHARE × GROSS_OUTPUT} 300‰，粮）<b>显式</b>给 {@code (hex, landlord)} cohort + 自留</td>
 *   </tr>
 *   <tr>
 *     <td>{@link RegimeOperators#HOUSEHOLD}</td>
 *     <td>★ <b>修订 spec §六</b>（裁定 E2）：实物分成（{@code OUTPUT_SHARE × LABOR_AMOUNT} 700‰，<b>布</b>）给四个阶层 cohort + 自留</td>
 *   </tr>
 *   <tr>
 *     <td>{@link RegimeOperators#HANDICRAFT}</td>
 *     <td>实物工资（{@code OUTPUT_SHARE × LABOR_AMOUNT} 600‰，布）给四个阶层 cohort + <b>货币工资档</b>（{@code FIXED_MONEY_WAGE}
 *       —— ★ H4 起**真的结算**：付方可见货币为 0 时实付 0、欠额进读数）+ 自留</td>
 *   </tr>
 *   <tr>
 *     <td>{@link RegimeOperators#TENANT}</td>
 *     <td><b>固定实物租</b>（{@code FIXED_IN_KIND_RENT}，粮）给 {@code (hex, landlord)} cohort + 佃农家户自留</td>
 *   </tr>
 * </table>
 *
 * <p>★★ <b>裁定 E2（修订 spec §六 的 {@code household → SELF_RETENTION}）</b>：spec §七 V3 要「<b>织布的人拿到布</b>」，
 * 而 {@code operator} 是 <b>actor</b>（{@code HOUSEHOLD:weave@0_0}）、织布的人是 <b>cohort</b>（人口住在 {@code
 * farm@hex|*} 行里）。 纯自留 ⇒ 布全留在 actor 账上、劳动者一条也拿不到 ⇒ V3/I6.3 永远开不了账。故本档表达成 「<b>实物分成给劳动者 + 自留</b>」（分成率
 * 700‰ ⇒ 余下 300‰ 留在 operator）。
 *
 * <p>★★ <b>裁定 E4（地租显式给 {@code (hex, landlord)} cohort）</b>：地主<b>既不在劳动账里</b>（不是劳动者）， <b>也不是
 * actor</b>（经营者是庄园/佃农家户）⇒ 不显式写一条 {@code ToCohort((hex, LANDLORD))}， <b>地主 cohort 的粮源会凭空消失</b>（spec
 * §六 第 5 条的病）。两档的租都写给 {@link SocialClassId#LANDLORD} 这个 cohort。
 *
 * <p>★★ <b>裁定 E9（"自留"不写成规则）</b>：四档的"自留"都由 {@code residualOwner = operator} 表达， <b>不写</b> {@code
 * SELF_RETENTION} 规则。理由两条：① {@code SELF_RETENTION} 属<b>实物档</b> ⇒ 按 {@link CompensationRule}
 * 的二选一守卫<b>必须带 {@code commodity}</b>（T1 的契约不动），而"自留"在语义上是 <b>对全部商品</b>的自留 —— 挑一个商品反而把话说小了；② T3
 * 的公式表里它的数量恒为 0 ⇒「不写这条规则」是 <b>等价路径</b>（空表 ⇒ 全归 {@code residualOwner}）。★ 于是"改回一条 {@code
 * SELF_RETENTION}"这种改动会在 {@code RegimeRelationsTest} 上当场红。
 *
 * <p>★★ <b>H3（2026-09-27，裁定 C3 + operator=C）：四档的"投入由谁出"默认值</b> —— {@code feudal} = <b>经营者（庄园）出</b>
 * · {@code tenant} = <b>佃农家户出</b> · {@code household} = <b>家户自出</b> · {@code handicraft} =
 * <b>作坊主出</b>（唯一拼写点是 {@link #defaultInputSupplier}）。
 *
 * <p>★★ <b>如实记：这四档默认<b>同值</b> —— 都落在该档的 {@code operator} 上</b>（因为 operator 就是那个主体：庄园 / 佃农家户 / 织布的家户
 * / 作坊主）。⇒ <b>这一栏的价值不是"改默认值"</b>，而是把"谁出料"从<b>按人口猜</b>（改前的 {@code
 * EconomySettlement.rowSharesOf}：该产业各行人口占比 + 逐行向下取整）变成<b>制度明说</b>，并让 GM 能配（载荷里一条显式 {@code relation}
 * 就能写"<b>地主出种</b>"这种制度 —— spec §2.4"同一个制度可以 A 格这样、B 格那样"的落点）。详见 {@code ProductionRelation} 的类注。
 *
 * <p>★ <b>为什么仍然做成一张"制度 → 值"的表（而不是直接在别处写 {@code ToActor(operator)}）</b>：① 四条默认是
 * <b>制度事实</b>（"佃农出种、庄园出种"各是一句话），它们的归属地就是本类（"制度 → 默认关系"的唯一拼写点）； ② <b>未登记的制度照样
 * fail-closed</b>（与本表其余入口同口径：新制度 = 新生产关系 ⇒ 必须显式说清谁出料）； ③ 将来某一档真的要改（例如 {@code tenant}
 * 改成"地主出种"），改的是<b>这一处</b>，不是散在各处的调用点。
 *
 * <p>★★ <b>R7/R8：受方是 cohort，"劳动者" = 该格的四个阶层各一条规则</b>。{@code CohortKey} 的粒度是 {@code (格, 居住类型,
 * 阶层)}（spec §2.6 + H0 的 R-N1-A），而劳动者是一个集合 ⇒ 四个阶层各一条；人口为 0 的那些 cohort <b>自然解析不到行</b>（{@code
 * population > 0} 是硬条件）⇒ 该笔留在 operator。
 *
 * <p>★★ <b>H0（2026-09-27，裁定 R-N1-A）起"居住类型"是受方身份的一维 —— 而本类看不见它</b>：家户 = {@code (格, 居住类型, 阶层)}，
 * 而"这个产业的这批人住农村还是城镇"的<b>唯一事实来源是劳动配额表</b>（{@code ResidenceKind.ofLot(allocation.group())}，
 * 批次前缀的唯一拼写点在 {@link ResidenceKind}）。⇒ 本类的 {@code defaultRelation} 多收一个 {@code Set<ResidenceKind>}
 * （{@link #defaultRelation(RegimeId, IndustryId, ActorRef, Set)}），由**唯一同时看得见产业与配额的地方**——载荷边缘 {@code
 * EconomyPayloads}——算好传进来。★ 本类**不猜**（不按产业种类、也不按制度推居住类型：制度与居住是两件事， 同一个 {@code feudal} 完全可以在城里）。
 *
 * <p>★ <b>为什么"每个 spec × 每个居住类型"展开是对的（劳动加权那两族）</b>：{@code OUTPUT_SHARE × LABOR_AMOUNT} 与 {@code
 * FIXED_IN_KIND_PER_LABOR} 的量都乘"本受方劳动 ÷ Σ劳动"（{@code ProductionSettlement} 的公式表）⇒ 没有人（或没有劳动）的 cohort
 * 逐值拿 0，两个居住类型的规则**不会互相多吃**。<b>反之，不按受方劳动加权的那几档（{@code GROSS_OUTPUT} / {@code NET_AFTER_INPUTS} /
 * {@code FIXED_AMOUNT}）各请求整份池</b> ⇒ 展开会让同一笔被要两次（第二条也会拿到它的率），
 * 故<b>多居住类型即抛</b>（fail-closed：真档里每个产业只由一种居住类型的家户供给，这一形态本表表达不了，不猜）。 ★ 空集合 ⇒ 该产业一条 cohort 规则都不产生（没有家户
 * ⇒ 没有劳动者；产出全归 {@code residualOwner}）。
 *
 * <p>★ <b>补注（裁定 E24，2026-09-26；H0
 * 起由身份维直接表达）</b>：那四条规则的<b>受方行</b>不是"同格同阶层的全部行"，而是"<b>真出了这份劳动的那批人住的行</b>"。 H0 之前靠"受方产业集"过滤（{@code
 * EconomySettlement.classRowsOfCohort}，已删）；H0 起 <b>居住维进了身份</b> （{@code rural} / {@code urban}）⇒ 受方
 * = 键与规则里的 cohort 逐字相等的**那一行**，歧义消失（E24 与 E28 一并收口）。 于是城市格上：家庭纺织那一份只落 <b>农村家户行</b>（农村批次供农业 +
 * 纺织），作坊那一份只落<b>城镇家户行</b> （城镇批次只供作坊）；本类只多了"居住类型"这一维。
 *
 * <p>★ <b>地点取自产业 id</b>（{@link IndustryHexKeys#hexKeyOf} 是唯一拼写点）：{@link CohortKey#hex()} = 该产业所在的格；
 * 居住类型那一维由调用方按配额表给（见上）。★ <b>拿不到格键 ⇒ 抛</b>（理由：关系必须有地点）—— 静默拿 {@code (0,0)} 顶替会把所有格的关系 都指到原点那一格上。
 *
 * <p>★★ <b>出厂值（判断结果；真值由 GM 按真档观察后调，信条十二）</b>：本类的四个数值常量都是**出厂值**， 注释里写明约束算式。★ <b>商品也只能是出厂值</b>：关系表按
 * {@code regime} 推导，**看不见该产业的 {@code outputPerUnit}** ⇒ 只能按本档的典型产品取（粮档 = 农业/租佃、布档 = 家庭纺织/作坊）；
 * 逐产业的差异由载荷里的显式 {@code relation} 表达（GM 的落点）。
 *
 * <p>★ <b>次序是数据</b>：{@code priority} 小的先付。本表里给养/分成在前（10）、租与货币工资在后（20）—— ★ <b>如实记</b>：这四个档的两组 {@code
 * pool}（{@code NET_AFTER_INPUTS+LABOR_AMOUNT} / {@code GROSS_OUTPUT+NONE}）**都不读"已付"**，
 * 故在当前公式表下<b>次序无数值后果</b>；它是给 {@code OPERATOR_SURPLUS} 那类规则留的数据位（spec §2.4 的"次序 = 数据"）。
 *
 * <p>★ <b>本类无状态</b>：一张表 + 两个纯函数。装配（谁在什么时候缺省）在<b>载荷边缘</b>（{@code EconomyPayloads}），不在本类。
 */
public final class RegimeRelations {

  /**
   * ★★ <b>出厂货币（H2 的币种位）：唯一拼写点</b>——"1000 毫钱"从今天起是"1000 毫<b>银</b>"。
   *
   * <p>★★ <b>它为什么必须有一个值</b>：{@code CompensationRule} 的构造期守卫判死"货币档必须有币种"（二选一）， 而出厂的四档规则表里 {@code
   * handicraft} 就带一条货币工资 ⇒ 不给值，那一条当场构造不出来。
   *
   * <p>★ <b>它只是出厂值</b>（判断结果，V7 参数目录 + S2 货币口径落地后由 GM 调；届时它迁入参数表， 本常量只作默认值）。★ 载荷边缘（{@code
   * EconomyPayloads}）读旧档缺 {@code currency} 键时也引用<b>这一个</b>拼写点 —— 同一个"出厂货币"两处各写一份，会在旧档与新档之间静默漂开。
   *
   * <p>★ <b>追加（2026-09-27，M1.1）</b>：{@code "silver"} <b>字面量</b>的唯一拼写点已移到世界级货币词表 {@link
   * MoneyVocabulary}（那里同时给出 {@code CurrencyDef} 与 {@code SPECIE} 工具；唯一性由 {@code
   * EconomyVocabularyGuardTest} 的源扫描钉住），本常量改为<b>引用</b>它 —— 本类不再是字面量的家，"出厂货币是哪种"这个语义仍在这里。
   */
  public static final CurrencyId DEFAULT_CURRENCY = MoneyVocabulary.SILVER_CURRENCY;

  /** 粮的商品 id（{@link EconomyVocabulary} 是唯一拼写点，本类只引用）。 */
  private static final String GRAIN = EconomyVocabulary.GRAIN_COMMODITY_ID;

  /** 布的商品 id（同上）。 */
  private static final String CLOTH = EconomyVocabulary.CLOTH_COMMODITY_ID;

  /** 纤维的商品 id（同上）：农业的**副产**（{@link #feudalRules()} 的最后一条规则用它）。 */
  private static final String FIBER = EconomyVocabulary.FIBER_COMMODITY_ID;

  /**
   * {@code feudal} 的<b>地租率</b>（千分）：毛产的 {@code 300‰} —— spec §2.4 的"地租 30%"原例（{@code GROSS_OUTPUT}）。
   *
   * <p>★ 约束（分成类规则的通用边界）：{@code Σ 各档分成率 ≤ 1000‰}，否则 R6 的"付款上限 = 本周期产出"开始咬合、 实付被截断 ⇒ 制度表达失真。
   */
  private static final int FEUDAL_RENT_PER_MILLE = 300;

  /**
   * {@code feudal} 的**副产纤维**分成率（千分，{@code OUTPUT_SHARE × NET_AFTER_INPUTS} 给四个阶层 cohort）：{@code
   * 1000}。
   *
   * <p>★ 取 1000 的**后果**（可读、也是取值依据）：四条规则的付款上限逐条咬合（{@code available = 净产 − 已付}）⇒ <b>Σ实付 ==
   * 净产</b>，且按**劳动量**分给劳动者 —— 副产全留在种地的人手里，"地租"那条只对**粮**收（见 {@link #feudalRules()}）。
   */
  private static final int FEUDAL_BYPRODUCT_SHARE_PER_MILLE = 1000;

  /**
   * {@code feudal} 的<b>给养</b>（毫粮 / 1000 千分劳动）。
   *
   * <p>★ 约束算式（出厂值的<b>量级依据</b>，不是精确等式）：{@code 144 = ⌈10,000 毫粮 ÷ 69.6⌉}，其中 {@code 10,000 毫粮} =
   * 一人的周期口粮（{@link EconomyVocabulary#RATION_MILLI_PER_PERSON}）、 {@code 69.6} = 一人的周期劳动（每人 {@code
   * 580‰} × {@code 120} 天 ÷ 1000）。向上取整是<b>有意</b>的： 取整不该把给养压到口粮线以下（两处取整合计仍留 ≈0.6% 的缺口，GM 按真档观察后调）。
   */
  private static final long FEUDAL_SUBSISTENCE_MILLI_PER_LABOR = 144L;

  /**
   * {@code household} 的<b>实物分成率</b>（千分，{@code OUTPUT_SHARE × LABOR_AMOUNT}）。
   *
   * <p>★ 值取 {@code 700}：与 {@code EconomySeeder} 写进家庭纺织载荷的劳动权重（{@code laborWeightPerMille = 700}） 同值
   * —— 同一个制度的两个面不该各写一个数。余下 {@code 300‰} 留在 operator（= 自留，裁定 E2）。
   */
  private static final int HOUSEHOLD_LABOR_SHARE_PER_MILLE = 700;

  /** {@code handicraft} 的<b>实物工资率</b>（千分）：{@code 600}，与 {@code EconomySeeder} 写进作坊载荷的劳动权重同值（同上）。 */
  private static final int HANDICRAFT_LABOR_SHARE_PER_MILLE = 600;

  /**
   * {@code handicraft} 的<b>货币工资</b>（毫钱 / 周期 / 受方）：★ <b>占位出厂值</b>（1,000 毫银/周期/受方）。
   *
   * <p>★★ <b>H4 起它真的结算</b>（I5.3 的「只定义、不结算」结束）：结算按"付方**本期可用货币**"付款 —— 而真档作坊的 operator
   * 是**聚合主体**（{@code WORKSHOP:craft@<格>}），它的钱住在 actor 切片上、economy 看不见 ⇒ 上限 0 ⇒ **实付 0、 欠额进 {@code
   * RuleSettlement} 读数**（如实报，不是静默付 0）。★ 真值随"经营者自己持账"（H5）与货币口径定。
   */
  private static final long HANDICRAFT_MONEY_WAGE_MILLI = 1_000L;

  /**
   * {@code tenant} 的<b>固定实物租</b>（毫粮 / 周期）：{@code 20,000,000} = 20,000 粮/周期。
   *
   * <p>★ 量级依据：一格一周期的毛产 ≈ {@code 207,700} 粮（{@code EconomySeeder} 的 {@code MU_PER_HEX} 3,100 亩 × 67
   * 粮/亩 —— 本模块<b>看不见</b>那个数，故只作为取值依据记在这里）⇒ 本值 ≈ 毛产的 {@code 9.6%}。 ★ 固定租<b>与产出无关</b>是本档的定义（{@code
   * FIXED_AMOUNT} 那一档池的意义）⇒ 它天生不随格的地力变； 逐格差异由载荷里的显式 {@code relation} 表达。
   */
  private static final long TENANT_RENT_MILLI_GRAIN = 20_000_000L;

  /**
   * ★★ <b>默认关系只覆盖传统四档</b>（贫农/中农/富农/地主）—— 不能读 {@code SocialClassId.all()}： S3 追加的 {@code
   * landless_laborer}/{@code artisan}/{@code official} <b>没有</b>创世行（播种器仍按传统四档建行）， 若默认规则点名它们，收获时的
   * {@code requireCohortRows} 会因"受方行不存在"fail-closed ⇒ 旧世界开不了账。 新档位由 S2/S3
   * 的显式候选/关系数据点名，不由默认模板生成（不改变旧四档行为）。
   *
   * <p>★★ <b>声明位置是初始化顺序的一部分</b>：本字段必须在 {@link #BY_REGIME} 的静态初始化块之前声明 —— 那个块（静态初始化期）调用 {@link
   * #feudalRules()} 等模板构造器，而它们经 {@link #laborCohorts} 读本字段； 若声明在后，类初始化时读到 {@code null} ⇒ {@code
   * ExceptionInInitializerError}/{@code NullPointerException} （{@code NoClassDefFoundError}
   * 只是后续表现）。
   */
  private static final List<SocialClassId> TRADITIONAL_STRATA =
      List.of(
          SocialClassId.POOR_PEASANT,
          SocialClassId.MIDDLE_PEASANT,
          SocialClassId.RICH_PEASANT,
          SocialClassId.LANDLORD);

  /**
   * 登记表：{@code regime 字面量 → 该档的规则模板（保序）}。
   *
   * <p>★★ <b>静态初始化块 + {@code LinkedHashMap} + {@code Collections.unmodifiableMap}</b>：顺序 = spec §六
   * 的表序， 而<b>错误消息要列出档位</b> ⇒ 迭代序必须是<b>内容的纯函数</b>（{@code Map.of} 的迭代序<b>不是</b>）。 ★ 每个档至少一条规则（{@link
   * #registered()} 取第一条作标志性类型）。
   */
  private static final Map<String, List<RuleSpec>> BY_REGIME;

  static {
    Map<String, List<RuleSpec>> byRegime = new LinkedHashMap<>();
    byRegime.put(RegimeOperators.FEUDAL, feudalRules());
    byRegime.put(RegimeOperators.HOUSEHOLD, householdRules());
    byRegime.put(RegimeOperators.HANDICRAFT, handicraftRules());
    byRegime.put(RegimeOperators.TENANT, tenantRules());
    BY_REGIME = Collections.unmodifiableMap(byRegime);
  }

  private RegimeRelations() {}

  /**
   * 缺 {@code relation} 时的默认生产关系。
   *
   * <p>★ <b>活动 = 传进来的产业</b>（铁律 1：身份 = 它结算的那个 activity，不另造 id）；{@code residualOwner} = {@code
   * operator}（自留，见类注 E9）。
   *
   * <p>★ <b>三个参数各自判 null 即抛</b>（同 {@code RegimeOperators} 的守卫口径：不猜）；字面量<b>大小写敏感</b> （{@code
   * "Feudal"} 是未登记，本表<b>不做归一</b> —— 归一是"猜"）。
   *
   * @param regime 生产制度；不得为 null
   * @param industry 产业（身份 + 地点都取自它）；不得为 null，且 id 里必须带格键
   * @param operator 经营主体；不得为 null
   * @param residences <b>供给这个产业的那些家户的居住类型</b>（H0/R-N1-A；唯一来源 = 劳动配额表的批次前缀， {@code
   *     ResidenceKind.ofLot}）；不得为 null，可为空（= 没有任何批次供给它 ⇒ 不产生 cohort 规则）。 ★
   *     <b>多于一种即抛</b>（见类注：非劳动加权的规则会重复计费，本表表达不了那一形态）
   * @throws IllegalArgumentException 任一参数为 null、{@code regime} <b>未登记</b>（fail-closed，消息列出四档）、产业 id
   *     里没有格键（关系必须有地点）、或 {@code residences} 多于一种居住类型
   */
  public static ProductionRelation defaultRelation(
      RegimeId regime, IndustryId industry, ActorRef operator, Set<ResidenceKind> residences) {
    if (regime == null) {
      throw new IllegalArgumentException("regime 不得为 null");
    }
    if (industry == null) {
      throw new IllegalArgumentException("industry 不得为 null");
    }
    if (operator == null) {
      throw new IllegalArgumentException("operator 不得为 null");
    }
    if (residences == null) {
      throw new IllegalArgumentException("residences 不得为 null（没有任何批次供给它请给空集；居住类型的唯一来源是劳动配额表）");
    }
    for (ResidenceKind residence : residences) {
      if (residence == null) {
        throw new IllegalArgumentException("residences 不得含 null");
      }
    }
    List<RuleSpec> specs = BY_REGIME.get(regime.value());
    if (specs == null) {
      throw new IllegalArgumentException(
          "未登记的制度，无法推导默认生产关系：" + regime.value() + "；已登记的档: " + BY_REGIME.keySet());
    }
    // ★★ 多居住类型 ⇒ 只有"按受方劳动加权"的那几档能展开（见类注）：其余各请求整份池，展开会重复计费 ⇒ 当场抛（不猜）。
    if (residences.size() > 1) {
      for (RuleSpec spec : specs) {
        if (spec.weight() != Weight.LABOR_AMOUNT) {
          throw new IllegalArgumentException(
              "默认关系表表达不了'一个产业由多种居住类型的家户供给'（"
                  + residences
                  + "）：规则 "
                  + spec.type()
                  + "×"
                  + spec.pool()
                  + "+"
                  + spec.weight()
                  + " 不按受方劳动加权 ⇒ 按居住类型展开会重复请求同一份产出。请改用载荷里的显式 relation（逐条写清 cohort）");
        }
      }
    }
    HexCoord hex = hexOf(industry);
    List<CompensationRule> rules = new ArrayList<>(specs.size() * Math.max(1, residences.size()));
    for (RuleSpec spec : specs) {
      // ★ 居住类型按 ResidenceKind.all() 的保序取（**不按 Set 的迭代序**：那会让规则表的次序成为调用方容器的函数）。
      for (ResidenceKind residence : ResidenceKind.all()) {
        if (residences.contains(residence)) {
          rules.add(toRule(spec, hex, residence));
        }
      }
    }
    // ★★ H3/C3：投入的提供者由**制度**说（见 defaultInputSupplier 的类注）—— 四档默认同值（都落在 operator 上），
    //   但"谁出料"从此是**本表的一行**，不再是结算里按人口算出来的一个比例。
    return new ProductionRelation(
        industry,
        operator,
        defaultInputSupplier(regime, operator),
        rules,
        operator,
        laborSourceFor(regime));
  }

  /**
   * ★★ <b>S1：四档默认的 {@link LaborSource}</b>（唯一拼写点）—— {@code feudal=SERF}、{@code tenant=TENANT}、
   * {@code household=FAMILY}、{@code handicraft=WAGE}。
   *
   * <p>★ 旧档没有这一维时 {@code ProductionRelation} 的构造期兜底是 {@code SELF}；本方法服务"按制度推导"的新路径。 未登记 ⇒
   * 抛（与其余入口同口径）。
   */
  public static LaborSource laborSourceFor(RegimeId regime) {
    if (regime == null) {
      throw new IllegalArgumentException("regime 不得为 null");
    }
    String value = regime.value();
    if (RegimeOperators.FEUDAL.equals(value)) {
      return LaborSource.SERF;
    }
    if (RegimeOperators.TENANT.equals(value)) {
      return LaborSource.TENANT;
    }
    if (RegimeOperators.HOUSEHOLD.equals(value)) {
      return LaborSource.FAMILY;
    }
    if (RegimeOperators.HANDICRAFT.equals(value)) {
      return LaborSource.WAGE;
    }
    throw new IllegalArgumentException(
        "未登记的制度，无法推导默认劳动来源：" + value + "；已登记的档: " + BY_REGIME.keySet());
  }

  /**
   * ★★ <b>R3：给养口径的对外读口</b>（毫粮 / 1000 千分劳动）—— {@link #FEUDAL_SUBSISTENCE_MILLI_PER_LABOR}
   * 是四档制度里唯一有实物给养的那一档的标定值；{@code ProducerCostBook} 算"劳动成本估计"时读它。
   *
   * <p>★ 为什么要有这个读口而不是让成本簿自己写一个数：给养与劳动成本必须是<b>同一个数</b>——两处各写一份会漂开，
   * 而"制度按规定发多少"与"成本估计按多少算"正是同一件事的两个面（唯一拼写点纪律）。
   */
  public static long subsistenceMilliPerLabor() {
    return FEUDAL_SUBSISTENCE_MILLI_PER_LABOR;
  }

  /**
   * ★★ <b>M1.7：默认关系里的"实物给养义务"展开</b> —— 由 {@link #defaultRelation(RegimeId, IndustryId, ActorRef,
   * Set)} 先推出该档的默认关系，再按 {@link SubsistenceObligation#of(ProductionRelation, Map)} 展开成具名义务。
   *
   * <p>★ <b>为什么需要它</b>：本类的四档里只有 {@code feudal} 有给养那一档（{@code FIXED_IN_KIND_PER_LABOR}）—— 其余三档展开
   * 出<b>空表</b>（不是抛、也不是 0：那三档的制度里没有这项义务）。读口/验收要问"某经营者每周期应交付多少、给谁"时，若状态里还没有显式关系
   * （如手搭夹具），走这个入口得到的就是"制度会展开成什么"。
   *
   * <p>★ <b>它是派生视图、不是第二份规则表</b>：义务量由 {@code defaultRelation} 的规则 + 实际劳动量现算 ⇒ 与 {@code
   * EconomyPayloads} 在载荷边缘展开的关系<b>不可能漂开</b>（两处都只走 {@code defaultRelation}）。
   *
   * @param regime 生产制度；不得为 null（未登记 ⇒ 抛，同 {@code defaultRelation}）
   * @param industry 产业（身份 + 地点都取自它）；不得为 null
   * @param operator 经营主体；不得为 null
   * @param residences 供给这个产业的那些家户的居住类型（见 {@code defaultRelation}）；不得为 null
   * @param laborOfCohort 本周期各 cohort 的劳动量（缺键 ⇒ 0 劳动 ⇒ 应付 0）；不得为 null
   */
  public static List<SubsistenceObligation> defaultSubsistenceObligations(
      RegimeId regime,
      IndustryId industry,
      ActorRef operator,
      Set<ResidenceKind> residences,
      Map<HouseholdId, Long> laborOfHousehold) {
    return SubsistenceObligation.of(
        defaultRelation(regime, industry, operator, residences), laborOfHousehold);
  }

  /**
   * ★★ <b>四档的"投入由谁出"默认值</b>（H3/C3 的唯一拼写点）：{@code feudal} = 经营者（庄园）出 · {@code tenant} = 佃农家户出 ·
   * {@code household} = 家户自出 · {@code handicraft} = 作坊主出。
   *
   * <pre>
   * 四档 <b>同值</b>：都返回 {@code ToActor(operator)} —— 因为 operator 就是那个主体（庄园 / 佃农家户 / 织布的家户 / 作坊主）。
   * </pre>
   *
   * <p>★★ <b>为什么同值也要有这个方法</b>（读者会问"那不就是把 operator 抄一遍"）：① 这一栏的语义是<b>制度事实</b>
   * （"佃农出种、庄园出种"各是一句话），它的归属地是"制度 → 默认关系"的唯一拼写点（本类）；② <b>未登记的档 fail-closed</b> —— 与本类其余入口同口径（新制度 =
   * 新生产关系 ⇒ 必须显式说清谁出料），而"直接把 operator 包一层"就没有这道判； ③ 将来某一档真的要改（例如 {@code tenant}
   * 改成"地主出种"），改的是<b>这一处</b>。
   *
   * <p>★ <b>另有一条更根本的理由</b>（改前口径的病）：H3 之前"谁出料"是<b>算</b>出来的 —— {@code
   * EconomySettlement.drawCycleInputs} 把投入需求按该产业各行的人口占比摊下去、逐行向下取整。那条口径的后果实测得到（小夹具 6 座作坊只开 4 座、 50
   * 台织机只开 48 台）。本方法把"谁出"变成<b>表里的一行</b>：默认是 operator，GM 要"地主出种"就写一条显式 {@code relation}。
   *
   * @param regime 生产制度；不得为 null
   * @param operator 该产业的经营主体（H3 的四档默认都落在它身上）；不得为 null
   * @throws IllegalArgumentException 任一参数为 null，或 {@code regime} <b>未登记</b>（fail-closed，消息列出四档）
   */
  public static Recipient defaultInputSupplier(RegimeId regime, ActorRef operator) {
    if (regime == null) {
      throw new IllegalArgumentException("regime 不得为 null");
    }
    if (operator == null) {
      throw new IllegalArgumentException("operator 不得为 null（投入的默认提供者 = 经营主体）");
    }
    if (!BY_REGIME.containsKey(regime.value())) {
      throw new IllegalArgumentException(
          "未登记的制度，无法推导默认投入提供者：" + regime.value() + "；已登记的档: " + BY_REGIME.keySet());
    }
    // ★ 四档同值（见方法注释）：投入由**经营主体自己**出。⇒ 与 ProductionRelation 的构造期缺省同值，
    //   两处不可能漂开（那一条是"缺键时"的补，本方法是"按制度推导时"的答，值域相同）。
    return new Recipient.ToActor(operator);
  }

  /**
   * 登记面（<b>保序</b>：spec §六 表序）：{@code regime 字面量 → 该档第一条规则的规则类型}。
   *
   * <p>★ <b>派生视图、不是第二份规则表</b>：它每次从 {@link #BY_REGIME} 算出来 ⇒ 与规则表<b>不可能漂开</b> （改成"两处各写一份"会立刻产生"表说
   * A、标志类型说 B"的病）。返回的 Map 不可变。
   */
  public static Map<String, RuleType> registered() {
    Map<String, RuleType> registered = new LinkedHashMap<>();
    for (Map.Entry<String, List<RuleSpec>> entry : BY_REGIME.entrySet()) {
      registered.put(entry.getKey(), entry.getValue().get(0).type());
    }
    return Collections.unmodifiableMap(registered);
  }

  // ── 四档的规则表（每档一个构造器；规则里的 cohort 在 defaultRelation 里才绑定到格）──────────

  /**
   * {@code feudal}（领主自营庄园）：给养（按劳动量的实物）给四个阶层 + 地租（毛产 300‰）给地主 cohort。
   *
   * <p>★ 次序：给养 10 / 地租 20 —— 见类注（两组 {@code pool} 都不读"已付"，故次序在当前公式表下无 数值后果）。
   */
  private static List<RuleSpec> feudalRules() {
    List<RuleSpec> rules =
        new ArrayList<>(
            laborCohorts(
                RuleType.FIXED_IN_KIND_PER_LABOR,
                Pool.NET_AFTER_INPUTS,
                Weight.LABOR_AMOUNT,
                0,
                FEUDAL_SUBSISTENCE_MILLI_PER_LABOR,
                Optional.of(GRAIN),
                10));
    rules.add(
        new RuleSpec(
            RuleType.OUTPUT_SHARE,
            SocialClassId.LANDLORD,
            Pool.GROSS_OUTPUT,
            Weight.NONE,
            FEUDAL_RENT_PER_MILLE,
            0L,
            Optional.of(GRAIN),
            20));
    // ★★ **本档的第二个典型产品：农田的副产纤维**（S1 阶段 4+5 Task 4 的实测收口）。
    //   ★ 为什么它必须在这里：产出自本阶段起**不再写进阶层行**（R5 ②），行里的实物只能经关系规则回来 ——
    //     而"田里的纤维 → 同格织机上"是**既有能力**（R4 的 T0 曾用 {@code transferIntraHexInputs} 从**行**取材 ——
    //     ★ H3 把那条通道删了：现在织机取的是**它自己名下那些农村家户**的账，而纤维副产正落在那些行上，见 drawCycleInputs，
    //     织机因此每个周期都拿得到料）。少了这一条，纤维会留在 {@code operator} 的账上，织机**第 2 个周期起停工**
    //     （{@code EconomyRealScaleClothTest} / {@code PopulationR4Test} / {@code
    // WorldgenInitializeToolTest} 三条
    //     端到端用例当场红——实现时实测到的）。★ 规则本身只**加了一条数据**：不改任何算式的形状。
    //   ★ 分成率 1000‰ × 四个阶层 cohort（付款上限逐条咬合 ⇒ Σ实付 == 净产）：
    //     副产是"田里长出来的"，留在种地的人手里（地租那一条只对**粮**收，见上）。
    //   ★★ **本注的一处更正（S1 阶段 4+5 Task 7 实测，2026-09-26）**：原注在此写"按**劳动量**分给劳动者"，
    //     **那是错的** —— {@code NET_AFTER_INPUTS} 那一档的公式是 {@code net_j × rate ÷ 1000}（{@link
    //     io.mosire.simos.economy.time.ProductionSettlement} 的公式表），**没有除劳动量那一步**
    //     （要按劳动量分得用 {@code LABOR_AMOUNT} 那一档）。⇒ 四条规则的表序里 {@code poor_peasant} 在首，
    //     它一条就拿到 {@code 1000‰ × 净产 == 净产}、把 R6 的上限**用满**，后三条逐值 0。
    //     真档实测（一格 14,806 人 + 1,777 人的城，第 120 天）：农田纤维净产 18,030,360 **整份落
    //     {@code farm@0_0|poor_peasant}**、另三行 0。★ 它对判据无影响（这一条的用途是"让同格织机取得到料"，
    //     任何一行持有都满足取材），故本参数**一字未改**；读数与算式见
    //     {@code
    // .superpowers/sdd/2026-09-26-s1-stage45-ownership-and-relations/stage45-readings.md}。
    rules.addAll(
        laborCohorts(
            RuleType.OUTPUT_SHARE,
            Pool.NET_AFTER_INPUTS,
            Weight.NONE,
            FEUDAL_BYPRODUCT_SHARE_PER_MILLE,
            0L,
            Optional.of(FIBER),
            30));
    return Collections.unmodifiableList(rules);
  }

  /** {@code household}（家户自给）：实物分成（布 700‰ × 劳动量）给四个阶层 —— 裁定 E2 的落点（见类注）。 */
  private static List<RuleSpec> householdRules() {
    return laborCohorts(
        RuleType.OUTPUT_SHARE,
        Pool.NET_AFTER_INPUTS,
        Weight.LABOR_AMOUNT,
        HOUSEHOLD_LABOR_SHARE_PER_MILLE,
        0L,
        Optional.of(CLOTH),
        10);
  }

  /** {@code handicraft}（雇佣作坊）：实物工资（布 600‰ × 劳动量）+ 货币工资（H4 起真的结算；付方可见货币为 0 ⇒ 实付 0）。 */
  private static List<RuleSpec> handicraftRules() {
    List<RuleSpec> rules =
        new ArrayList<>(
            laborCohorts(
                RuleType.OUTPUT_SHARE,
                Pool.NET_AFTER_INPUTS,
                Weight.LABOR_AMOUNT,
                HANDICRAFT_LABOR_SHARE_PER_MILLE,
                0L,
                Optional.of(CLOTH),
                10));
    rules.addAll(
        laborCohorts(
            RuleType.FIXED_MONEY_WAGE,
            Pool.FIXED_AMOUNT,
            Weight.NONE,
            0,
            HANDICRAFT_MONEY_WAGE_MILLI,
            Optional.empty(),
            20));
    return Collections.unmodifiableList(rules);
  }

  /** {@code tenant}（土地出租）：固定实物租给地主 cohort（E4）+ 佃农家户自留（{@code residualOwner}）。 */
  private static List<RuleSpec> tenantRules() {
    return Collections.unmodifiableList(
        List.of(
            new RuleSpec(
                RuleType.FIXED_IN_KIND_RENT,
                SocialClassId.LANDLORD,
                Pool.FIXED_AMOUNT,
                Weight.NONE,
                0,
                TENANT_RENT_MILLI_GRAIN,
                Optional.of(GRAIN),
                10)));
  }

  /** 该格**四个传统阶层** cohort 各一条同类规则（R7/R8：受方是 cohort，劳动者是一个集合）。 */
  private static List<RuleSpec> laborCohorts(
      RuleType type,
      Pool pool,
      Weight weight,
      int ratePerMille,
      long fixedAmount,
      Optional<String> commodity,
      int priority) {
    List<RuleSpec> rules = new ArrayList<>(TRADITIONAL_STRATA.size());
    for (SocialClassId stratum : TRADITIONAL_STRATA) {
      rules.add(
          new RuleSpec(
              type, stratum, pool, weight, ratePerMille, fixedAmount, commodity, priority));
    }
    return Collections.unmodifiableList(rules);
  }

  /** 一条模板 → 一条真规则（受方的**居住格与居住类型**在此绑定；两者都由调用方给，本类不猜）。 */
  private static CompensationRule toRule(RuleSpec spec, HexCoord hex, ResidenceKind residence) {
    return new CompensationRule(
        spec.type(),
        new Recipient.ToCohort(new CohortKey(hex, residence, spec.stratum())),
        spec.pool(),
        spec.weight(),
        spec.ratePerMille(),
        spec.fixedAmount(),
        spec.commodity().map(CommodityId::new),
        // ★★ H2：货币档的币种**不是缺省**（构造期守卫判死"货币档必须有值"）—— 出厂值取自本类的唯一拼写点。
        spec.type().money() ? Optional.of(DEFAULT_CURRENCY) : Optional.empty(),
        spec.priority());
  }

  /** 产业 id 里的格键 → 坐标（{@link IndustryHexKeys} 是唯一拼写点）；拿不到 ⇒ 抛（关系必须有地点）。 */
  private static HexCoord hexOf(IndustryId industry) {
    return HexCoord.parse(
        IndustryHexKeys.hexKeyOf(industry)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "产业 id 里没有格键，无法推导默认生产关系（关系必须有地点，不许拿 (0,0) 顶替）: " + industry)));
  }

  /**
   * 一条规则模板：<b>只差"绑定到哪一格、哪种居住类型"</b>（受方的 {@code stratum} 已定，{@code hex} 与 {@code residence} 在 {@link
   * #defaultRelation(RegimeId, IndustryId, ActorRef, Set)} 里补上）。
   *
   * <p>★ {@code commodity} 为空 = 货币档（与 {@link CompensationRule} 的"二选一是类型事实"同源：空与有值 各自对应 {@code
   * type.money()} 的一侧）。
   */
  private record RuleSpec(
      RuleType type,
      SocialClassId stratum,
      Pool pool,
      Weight weight,
      int ratePerMille,
      long fixedAmount,
      Optional<String> commodity,
      int priority) {

    private RuleSpec {
      if (commodity == null) {
        throw new IllegalArgumentException("RuleSpec.commodity 不得为 null（空要用 Optional.empty()）");
      }
    }
  }
}
