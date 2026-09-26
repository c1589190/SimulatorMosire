package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *     <td>实物工资（{@code OUTPUT_SHARE × LABOR_AMOUNT} 600‰，布）给四个阶层 cohort + <b>货币工资档</b>（只定义、不结算，I5.3）+ 自留</td>
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
 * <p>★★ <b>R7/R8：受方是 cohort，"劳动者" = 该格的四个阶层各一条规则</b>。{@code CohortKey} 的粒度是 {@code (格, 阶层)}（spec
 * §2.6），而劳动者是一个集合 ⇒ 四个阶层各一条；人口为 0 的那些 cohort <b>自然解析不到行</b>（{@code population > 0} 是硬条件）⇒ 该笔留在
 * operator。这也是 {@code weave@hex|*} 四行（人口恒为 0）拿不到布、而<b>真正织布的人</b>（{@code
 * farm@hex|<阶层>}）拿到布的原因（I4.3/V3）。
 *
 * <p>★ <b>地点取自产业 id</b>（{@link IndustryHexKeys#hexKeyOf} 是唯一拼写点）：{@link CohortKey#residence()} =
 * 该产业所在的格。★ <b>拿不到格键 ⇒ 抛</b>（理由：关系必须有地点）—— 静默拿 {@code (0,0)} 顶替会把所有格的关系 都指到原点那一格上。
 *
 * <p>★★ <b>出厂值（判断结果；真值由 GM 按真档观察后调，信条十二）</b>：本类的四个数值常量都是**出厂值**， 注释里写明约束算式。★ <b>商品也只能是出厂值</b>：关系表按
 * {@code regime} 推导，**看不见该产业的 {@code outputPerUnit}** ⇒ 只能按本档的典型产品取（粮档 = 农业/租佃、布档 = 家庭纺织/作坊）；
 * 逐产业的差异由载荷里的显式 {@code relation} 表达（GM 的落点）。
 *
 * <p>★ <b>次序是数据</b>：{@code priority} 小的先付。本表里给养/分成在前（10）、租与货币工资在后（20）—— ★ <b>如实记</b>：这四个档的两组 {@code
 * basis}（{@code LABOR_AMOUNT} / {@code GROSS_OUTPUT}）**都不读"已付"**， 故在当前公式表下<b>次序无数值后果</b>；它是给 {@code
 * OPERATOR_SURPLUS} 那类规则留的数据位（spec §2.4 的"次序 = 数据"）。
 *
 * <p>★ <b>本类无状态</b>：一张表 + 两个纯函数。装配（谁在什么时候缺省）在<b>载荷边缘</b>（{@code EconomyPayloads}），不在本类。
 */
public final class RegimeRelations {

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
   * {@code handicraft} 的<b>货币工资</b>（毫钱 / 周期 / 受方）：★ <b>占位出厂值</b> —— S1 只定义、不结算（I5.3），
   * 故它唯一的职责是"让这条规则<b>看得见</b>"（读口能读到一条待 S2 的货币规则；0 会与"没有这条规则"无法区分）。 真值随 S2 的货币口径定。
   */
  private static final long HANDICRAFT_MONEY_WAGE_MILLI = 1_000L;

  /**
   * {@code tenant} 的<b>固定实物租</b>（毫粮 / 周期）：{@code 20,000,000} = 20,000 粮/周期。
   *
   * <p>★ 量级依据：一格一周期的毛产 ≈ {@code 207,700} 粮（{@code EconomySeeder} 的 {@code MU_PER_HEX} 3,100 亩 × 67
   * 粮/亩 —— 本模块<b>看不见</b>那个数，故只作为取值依据记在这里）⇒ 本值 ≈ 毛产的 {@code 9.6%}。 ★ 固定租<b>与产出无关</b>是本档的定义（{@code
   * FIXED_AMOUNT} 那一档 basis 的意义）⇒ 它天生不随格的地力变； 逐格差异由载荷里的显式 {@code relation} 表达。
   */
  private static final long TENANT_RENT_MILLI_GRAIN = 20_000_000L;

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
   * @throws IllegalArgumentException 任一参数为 null、{@code regime} <b>未登记</b>（fail-closed，消息列出四档）、 或产业
   *     id 里没有格键（关系必须有地点）
   */
  public static ProductionRelation defaultRelation(
      RegimeId regime, IndustryId industry, ActorRef operator) {
    if (regime == null) {
      throw new IllegalArgumentException("regime 不得为 null");
    }
    if (industry == null) {
      throw new IllegalArgumentException("industry 不得为 null");
    }
    if (operator == null) {
      throw new IllegalArgumentException("operator 不得为 null");
    }
    List<RuleSpec> specs = BY_REGIME.get(regime.value());
    if (specs == null) {
      throw new IllegalArgumentException(
          "未登记的制度，无法推导默认生产关系：" + regime.value() + "；已登记的档: " + BY_REGIME.keySet());
    }
    HexCoord hex = hexOf(industry);
    List<CompensationRule> rules = new ArrayList<>(specs.size());
    for (RuleSpec spec : specs) {
      rules.add(toRule(spec, hex));
    }
    return new ProductionRelation(industry, operator, rules, operator);
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
   * <p>★ 次序：给养 10 / 地租 20 —— 见类注（两组 {@code basis} 都不读"已付"，故次序在当前公式表下无 数值后果）。
   */
  private static List<RuleSpec> feudalRules() {
    List<RuleSpec> rules =
        new ArrayList<>(
            laborCohorts(
                RuleType.FIXED_IN_KIND_PER_LABOR,
                Basis.LABOR_AMOUNT,
                0,
                FEUDAL_SUBSISTENCE_MILLI_PER_LABOR,
                Optional.of(GRAIN),
                10));
    rules.add(
        new RuleSpec(
            RuleType.OUTPUT_SHARE,
            SocialClassId.LANDLORD,
            Basis.GROSS_OUTPUT,
            FEUDAL_RENT_PER_MILLE,
            0L,
            Optional.of(GRAIN),
            20));
    // ★★ **本档的第二个典型产品：农田的副产纤维**（S1 阶段 4+5 Task 4 的实测收口）。
    //   ★ 为什么它必须在这里：产出自本阶段起**不再写进阶层行**（R5 ②），行里的实物只能经关系规则回来 ——
    //     而"田里的纤维 → 同格织机上"是**既有能力**（R4 的 T0：{@code transferIntraHexInputs} 从**行**取材，
    //     织机因此每个周期都拿得到料）。少了这一条，纤维会留在 {@code operator} 的账上，织机**第 2 个周期起停工**
    //     （{@code EconomyRealScaleClothTest} / {@code PopulationR4Test} / {@code
    // WorldgenInitializeToolTest} 三条
    //     端到端用例当场红——实现时实测到的）。★ 规则本身只**加了一条数据**：不改任何算式的形状。
    //   ★ 分成率 1000‰ × 四个阶层 cohort（付款上限逐条咬合 ⇒ Σ实付 == 净产，按**劳动量**分给劳动者）：
    //     副产是"田里长出来的"，留在种地的人手里（地租那一条只对**粮**收，见上）。
    rules.addAll(
        laborCohorts(
            RuleType.OUTPUT_SHARE,
            Basis.NET_AFTER_INPUTS,
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
        Basis.LABOR_AMOUNT,
        HOUSEHOLD_LABOR_SHARE_PER_MILLE,
        0L,
        Optional.of(CLOTH),
        10);
  }

  /** {@code handicraft}（雇佣作坊）：实物工资（布 600‰ × 劳动量）+ 货币工资（只定义、不结算，I5.3）。 */
  private static List<RuleSpec> handicraftRules() {
    List<RuleSpec> rules =
        new ArrayList<>(
            laborCohorts(
                RuleType.OUTPUT_SHARE,
                Basis.LABOR_AMOUNT,
                HANDICRAFT_LABOR_SHARE_PER_MILLE,
                0L,
                Optional.of(CLOTH),
                10));
    rules.addAll(
        laborCohorts(
            RuleType.FIXED_MONEY_WAGE,
            Basis.FIXED_AMOUNT,
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
                Basis.FIXED_AMOUNT,
                0,
                TENANT_RENT_MILLI_GRAIN,
                Optional.of(GRAIN),
                10)));
  }

  /** 该格**四个阶层** cohort 各一条同类规则（R7/R8：受方是 cohort，劳动者是一个集合）。 */
  private static List<RuleSpec> laborCohorts(
      RuleType type,
      Basis basis,
      int ratePerMille,
      long fixedAmount,
      Optional<String> commodity,
      int priority) {
    List<RuleSpec> rules = new ArrayList<>(SocialClassId.all().size());
    for (SocialClassId stratum : SocialClassId.all()) {
      rules.add(new RuleSpec(type, stratum, basis, ratePerMille, fixedAmount, commodity, priority));
    }
    return Collections.unmodifiableList(rules);
  }

  /** 一条模板 → 一条真规则（受方的居住格在此绑定）。 */
  private static CompensationRule toRule(RuleSpec spec, HexCoord hex) {
    return new CompensationRule(
        spec.type(),
        new Recipient.ToCohort(new CohortKey(hex, spec.stratum())),
        spec.basis(),
        spec.ratePerMille(),
        spec.fixedAmount(),
        spec.commodity().map(CommodityId::new),
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
   * 一条规则模板：<b>只差"绑定到哪一格"</b>（受方的 {@code stratum} 已定，{@code residence} 在 {@link #defaultRelation}
   * 里补上）。
   *
   * <p>★ {@code commodity} 为空 = 货币档（与 {@link CompensationRule} 的"二选一是类型事实"同源：空与有值 各自对应 {@code
   * type.money()} 的一侧）。
   */
  private record RuleSpec(
      RuleType type,
      SocialClassId stratum,
      Basis basis,
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
