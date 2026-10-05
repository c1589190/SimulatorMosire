package io.mosire.simos.economy.api.relation;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>一次生产活动的结算规则</b>（spec §2.4 的 {@code ProductionRules}）：关账以后，产出如何在<b>经营者</b>、
 * <b>劳动提供者</b>、<b>资产所有者</b>之间分掉（裁定 E4 的单列表形态）。
 *
 * <p>★★ <b>它不另造 id</b>：身份 = 它结算的那个 {@link #activity()}（铁律 1 —— id 是身份，不在切片里另造同义 ID）。★ 关系表因此是 {@code
 * EconomyData} 的<b>第 8 个组件</b>、键 = {@code ProductionUnitId}（R3B.2 起），并与 {@code
 * ProductionProcess.operator} 有<b>跨表守卫</b> （两处拼写必须一致；守卫住 {@code EconomyData}，不在这里 ——
 * 本类型看得见自己，看不见那张生产单元表）。
 *
 * <p>★★ <b>单列表 + {@code priority}（裁定 E4，取代 spec §2.4 的 labor/asset 两张表）</b>：
 *
 * <ul>
 *   <li>「这一条规则是给劳动者的还是给资产所有者的」<b>已经由</b> {@code recipient}（sealed：actor | cohort）<b>与</b> {@code
 *       basis}（{@code LABOR_AMOUNT} / {@code ASSET_QUANTITY}）<b>表达</b> ⇒ 再切两张表就是<b>第二拼写点</b>：
 *       同一批规则能被分成两处、次序也被切成两段；
 *   <li>★ 更要紧的是：<b>两张表之间没有次序</b> ⇒「先给养、后算地租（地租按剩下的算）」这种<b>真实制度</b>无从表达。一张表 + {@code priority}
 *       把它变成<b>数据</b>（改次序 = 改一个整数，不改代码）；
 *   <li>★ 于是「租给 {@code (hex, landlord)} cohort」就是<b>再一条规则</b>：{@code recipient = ToCohort(new
 *       CohortKey(hexOf(activity), SocialClassId.LANDLORD))}。★ <b>必须显式给</b> —— 地主既不是劳动者（不在劳动账里），
 *       也不是 actor（经营者），不显式给，<b>地主 cohort 的粮源会凭空消失</b>。
 * </ul>
 *
 * <p>★★ <b>构造期守卫</b>：{@code activity} / {@code operator} / {@code residualOwner} 非 null；{@code
 * rules} 非 null、<b>逐项非 null</b>、<b>保序不可变</b>（冻结写在赋值处，外部之后改那张表不影响已建的关系）。★ <b>空表合法</b>：一条规则都没有 ⇒
 * 产出全部归 {@link #residualOwner()}（自留是<b>缺省</b>，不是坏数据）。
 *
 * <p>★ <b>本阶段（S1 阶段 4+5）的过渡口径如实记</b>：{@code AssetHolding} <b>未在真档种入</b> ⇒ {@code ASSET_QUANTITY}
 * 这一档只有夹具覆盖（未达成项，落点见台账）。★ 另：{@code SELF_RETENTION} 属<b>实物</b>档 ⇒ 它也要带 {@code commodity} （{@link
 * CompensationRule} 的二选一守卫）；若某一档只想要「余额归 residualOwner」，把它写成<b>不出现这条规则</b>即可（空表 ⇒ 全归 residualOwner）。
 *
 * <p>★★ <b>追加标注（2026-09-27，上文一字不改）</b>：
 *
 * <ul>
 *   <li><b>H0.5 / 裁定 S3</b>：{@code ASSET_QUANTITY} 随 {@code AssetHolding} <b>整块退役</b>
 *       （实测事实：该表在真档<b>零写入者</b>、{@code harvest} 更是硬编码空表 ⇒ 收益为 0）。 ⇒ 上文把它当"资产所有者那一档"的举例、以及第 33
 *       行那段"未在真档种入"的过渡口径， <b>随之作废</b>；资产（土地 / 工具 / 牲畜）推迟到真需要时再加，届时以<b>产业产能</b> （{@code
 *       Industry.capacity}）表达"用多少"，以本表的一条规则表达"谁拿收益"。
 *   <li><b>operator 的归属（2026-09-27 裁定）</b>：{@link #operator()} 将在 <b>H3</b> 起由本表
 *       <b>指名</b>（与"投入由谁出"合并成同一栏，裁定 C3），{@code Industry.operator} 的去留届时一并裁。
 * </ul>
 *
 * <p>★★ <b>H3（2026-09-27，裁定 C3 + operator=C）：本表多一栏 {@link #inputSupplier()}</b> ——
 * 「这些投入由谁出」（周期第一天要扣的种子 / 纤维 / 铁从<b>谁的账</b>上划给这个产业）。
 *
 * <p>★★ <b>为什么单独立一栏（读者一定会问：四档的默认不都是 operator 吗？）</b>—— 问得对， {@code RegimeRelations} 的四档默认（feudal =
 * 经营者（庄园）出 · tenant = 佃农家户出 · household = 家户自出 · handicraft = 作坊主出）<b>确实都落在 {@link
 * #operator()}</b>（因为 operator 就是那个主体）。⇒ 这一栏的价值 <b>不是"改默认值"</b>，而是两条：
 *
 * <ol>
 *   <li>★★ <b>把"谁出料"从"按人口猜"变成"制度明说"</b>：改前 {@code 旧结算引擎（R3a 已删除）.drawCycleInputs} 按 {@code
 *       rowSharesOf}（该产业各行的人口占比 + 逐行向下取整）把投入需求摊给"供给该产业的家户" —— 那条口径的
 *       <b>分摊比例是算出来的，不是谁说出来的</b>（实测后果：小夹具 6 座作坊只开 4 座、50 台织机只开 48 台、 真档年末剩 24,001,080
 *       毫纤维）。现在"谁出"写在数据里：{@code inputSupplier} 指名的那一个主体，从<b>它自己的账</b>出；
 *   <li>★ <b>让 GM 能配</b>：载荷里写一条显式 {@code relation} 就能表达"<b>地主出种</b>"这种制度（feudal 的默认是庄园出， 但同一个
 *       {@code feudal} 完全可以是地主出 —— 那正是 spec §2.4"同一个制度可以有 A 格这样、B 格那样"的落点）。
 * </ol>
 *
 * <p>★ <b>为什么是单一 {@link Payee} 而不是 {@code Map<CommodityId, Payee>}</b>（逐商品覆盖）： ①
 * 现在没有任何一种制度需要它（"种子归地主、纤维归作坊"这种话今天无人说） —— 提前造一维就是<b>造一个永远为空的维度</b>； ②
 * 逐商品覆盖会让"谁出料"从<b>一个</b>事实变成<b>一张表</b>，而读它的人（结算）要先把表摊平才能回答"这个产业谁出料"； ③
 * 真要那种制度时，加这一维是<b>纯追加</b>（多一个组件 / 载荷多一个键），不会推翻今天的形状。 ⇒ 等真有制度需要它再加（本仓的一般口径：不为假想的需要造形状）。
 *
 * <p>★★ <b>缺省 = {@link #operator()}（本记录的构造期缺省，不是"第二个拼写点"）</b>：{@code inputSupplier == null} ⇒ 取
 * {@code ToActor(operator)}。三个理由：① 它与四档默认<b>同值</b>（见上），故"缺省"只有这一处落点； ② <b>旧档兼容</b>：H3 之前的 relation
 * JSON 没有这个键，Jackson 会传 null 进来 —— 在构造期补成 operator，旧档照常打开 （"旧档读不回来"不是兼容，是事故）；③ 载荷边缘（{@code
 * EconomyPayloads}）因此<b>不必</b>再写一遍缺省。
 *
 * <p>★ <b>"从该主体自己的账出"的实现边界</b>（如实记，见 旧结算引擎的 drawCycleInputs（R3a 已删除））：economy
 * 切片只看得见<b>家户账</b>（会话工作副本）；若指名的供方是<b>聚合主体</b>（{@code ESTATE} / {@code WORKSHOP} / 产业型 {@code
 * HOUSEHOLD} —— 它们的账住在 actor 切片），economy 读不到那本账 ⇒ 由**该产业名下的家户账**代理（"这个主体的 缸"=
 * 它名下那些家户的缸），逐户按持仓量等比例、按最大余数法分派，取不满则规模缩（不凭空造）。
 *
 * @param activity 这条关系结算的那个活动（身份 = 它，不另造 id）
 * @param operator 经营主体（必须与 {@code Industry.operator} 一致，跨表守卫在 {@code EconomyData}）
 * @param inputSupplier ★★ <b>投入由谁出</b>（H3/C3）：周期第一天要扣的投入从<b>它的账</b>上划给该产业； <b>缺省（null）⇒ {@code
 *     ToActor(operator)}</b>；四档的默认见 {@code RegimeRelations}
 * @param rules 补偿规则（**一张表、保序、不可变**；空表 = 全部自留）
 * @param residualOwner 余额归谁（一般是 {@code operator}；不产生任何条目）
 * @param laborSource ★★ <b>这份生产的劳动来源</b>（S1；SELF/FAMILY/TENANT/SERF/WAGE）。旧档缺该键 ⇒ {@link
 *     LaborSource#SELF}（见构造期兜底）；显式档位由 {@code RegimeRelations} 与载荷给出。
 */
public record ProductionRules(
    ProductionUnitId activity,
    ActorRef operator,
    Payee inputSupplier,
    List<CompensationRule> rules,
    ActorRef residualOwner,
    LaborSource laborSource) {

  /**
   * ★ 旧形状的便捷构造（{@code laborSource} 缺省 {@link LaborSource#SELF}）：新代码请显式给那一档；
   * 本重载只服务"这一步与劳动来源无关"的调用点与旧档迁移（缺键 ⇒ SELF，见 {@link #laborSource()}）。
   */
  public ProductionRules(
      ProductionUnitId activity,
      ActorRef operator,
      Payee inputSupplier,
      List<CompensationRule> rules,
      ActorRef residualOwner) {
    this(activity, operator, inputSupplier, rules, residualOwner, LaborSource.SELF);
  }

  public ProductionRules {
    if (activity == null) {
      throw new IllegalArgumentException("ProductionRules.activity 不得为 null");
    }
    if (operator == null) {
      throw new IllegalArgumentException("ProductionRules.operator 不得为 null");
    }
    if (inputSupplier == null) {
      // ★ 缺省 = 经营者（H3/C3 的默认；四档默认同值 ⇒ 这一处就是"缺省"的唯一落点，见类注）。
      inputSupplier = new Payee.ToActor(operator);
    }
    if (laborSource == null) {
      // ★ 旧档（S1 之前）没有这一维 ⇒ 读成"经营者自营"（该口径下最保守、且不改旧结算结果的映射）。
      laborSource = LaborSource.SELF;
    }
    if (rules == null) {
      throw new IllegalArgumentException("ProductionRules.rules 不得为 null（无规则请给空表）");
    }
    if (residualOwner == null) {
      throw new IllegalArgumentException("ProductionRules.residualOwner 不得为 null");
    }
    List<CompensationRule> rulesCopy = new ArrayList<>(rules.size());
    for (CompensationRule rule : rules) {
      if (rule == null) {
        throw new IllegalArgumentException(
            "ProductionRules.rules 不得含 null 项（第 " + rulesCopy.size() + " 项）");
      }
      rulesCopy.add(rule);
    }
    rules = List.copyOf(rulesCopy); // ★ 冻在赋值处（含防御性拷贝，且保序）
  }

  /**
   * ★★ <b>换身份（activity）</b>：迁移器把旧 {@code industryId} 串解析成 {@code ProductionUnitId} 后，用本方法把值内 {@code
   * activity} 一并对齐到新键（其余字段逐值带过）。★ 这不是第二份状态——它就是"同一件事实的键与值同时改"。
   */
  public ProductionRules withActivity(ProductionUnitId newActivity) {
    if (newActivity == null) {
      throw new IllegalArgumentException("ProductionRules.withActivity 的 newActivity 不得为 null");
    }
    return new ProductionRules(
        newActivity, operator, inputSupplier, rules, residualOwner, laborSource);
  }
}
