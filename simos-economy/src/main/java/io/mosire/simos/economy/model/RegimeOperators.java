package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.RegimeId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ **{@code regime} → 默认经营主体（{@code operator}）的唯一拼写点**（S1 阶段 3 Task 1；spec §六 + 裁定 R1/R3）。
 *
 * <p>★ **制度只负责初始化、不负责持续约束**（spec §2.4 原文「同一个 {@code feudal} 可以有 A 格地租 30% / B 格五五分成 / C 格领主直营 ——
 * 制度可以渐变而不用先改产业类型」）⇒ 本表只回答**一个**问题：「载荷缺 {@code operator} 键时该填谁」； **没有**反向查询（"这个 operator 配不配这个
 * regime"不在本类，且按裁定 R4 **不许**加那种一致性守卫）。
 *
 * <p>★★ **未登记的制度即抛**（裁定 R1，fail-closed）：否则"缺 {@code operator} 就按 regime 推"会退化成"随便哪个制度都能 凭空得到一个经营主体"
 * —— 那正是"operator 是标签"以 **fail-open** 的形态复活。**新制度 = 新生产关系 ⇒ 必须显式说清谁经营。**
 *
 * <p>★ **id 取产业 id、不取裸 hex**（裁定 R3）：劳动侧（{@code EconomySeeder.appendAllocation}）写下的 actor id 本就是产业
 * id（{@code farm@0_0}）⇒ 两侧**同字面**，同一个庄园不会拿到两个 {@link ActorRef}。spec §六 的 {@code ESTATE@hex}
 * 记为**简写**：每格每制度恰有一个产业，二者 1:1。
 *
 * <p>★ **本类无状态、无公式**：一张表 + 两个纯函数。装配（谁在什么时候缺省）在**载荷边缘**（{@code EconomyPayloads}）， 不在本类。
 */
public final class RegimeOperators {

  /**
   * 领主**自营**庄园（spec §六 的 {@code feudal} 档）。
   *
   * <p>★ **不是**"封建租佃" —— 租佃（佃农家户）另立 {@link #TENANT} 档；同词两义已在 S1 阶段 3 的裁定 D7 就地消除。
   */
  public static final String FEUDAL = "feudal";

  /** 家户自给（农闲织布一类；spec §六）。 */
  public static final String HOUSEHOLD = "household";

  /** 雇佣作坊（货币工资档 H4 起真的结算；spec §六）。 */
  public static final String HANDICRAFT = "handicraft";

  /**
   * ★★ 本阶段**新加的租佃档**（spec §六 的"土地出租"）：佃农家户经营。
   *
   * <p>它是"**资产所有者 ≠ 经营主体**"的唯一载体（spec §六 末条）—— 没有它就证明不了 §2.1 那条判据。
   */
  public static final String TENANT = "tenant";

  /**
   * ★★ <b>P11.7 / D-024：商人 regime 的唯一拼写点</b> —— 该制度下的产业是贸易/承运（如 seeder 的 {@code trade}）。
   *
   * <p>★ <b>本批只落 regime 字面量</b>：{@code BY_REGIME} 的"默认经营主体"表<b>不</b>登记它（贸易产业的 operator 必须由载荷
   * 显式给出；未给 ⇒ 既有的 fail-closed），{@code RegimeRelations} 的四档默认关系表也仍不含它（那两个表不在本阶段文件所有权内）。
   * 它服务的是"按 mode 选产业模板"这条映射（{@link #defaultRegimeForMode(ProductionModeId)}）。
   */
  public static final String MERCHANT = "merchant";

  /**
   * 登记表：{@code regime 字面量 → 默认经营主体种类}。
   *
   * <p>★★ **静态初始化块 + {@code LinkedHashMap} + {@code Collections.unmodifiableMap}**：顺序 = spec §六
   * 的表序， 而**错误消息要列出档位** ⇒ 迭代序必须是**内容的纯函数**（{@code Map.of} 的迭代序**不是** —— 故本类**绝不用**它）。
   */
  private static final Map<String, ActorKind> BY_REGIME;

  static {
    Map<String, ActorKind> byRegime = new LinkedHashMap<>();
    byRegime.put(FEUDAL, ActorKind.ORGANIZATION);
    byRegime.put(HOUSEHOLD, ActorKind.HOUSEHOLD);
    byRegime.put(HANDICRAFT, ActorKind.ORGANIZATION);
    byRegime.put(TENANT, ActorKind.HOUSEHOLD);
    BY_REGIME = Collections.unmodifiableMap(byRegime);
  }

  private RegimeOperators() {}

  /**
   * 缺 {@code operator} 时的默认经营主体。
   *
   * <p>★ **两个参数各自判 null 即抛**（同 {@code Industry} 一族的构造期守卫口径：不猜）；字面量**大小写敏感** （{@code "Feudal"}
   * 是未登记，本表**不做归一** —— 归一是"猜"，同 R1）。
   *
   * @param regime 生产制度；不得为 null
   * @param industry 产业；不得为 null（返回的 id 取它的值，见类注 R3）
   * @throws IllegalArgumentException 任一参数为 null，或 {@code regime} **未登记**（fail-closed，消息列出四档）
   */
  public static ActorRef defaultOperator(RegimeId regime, IndustryId industry) {
    if (regime == null) {
      throw new IllegalArgumentException("regime 不得为 null");
    }
    if (industry == null) {
      throw new IllegalArgumentException("industry 不得为 null");
    }
    ActorKind kind = BY_REGIME.get(regime.value());
    if (kind == null) {
      throw new IllegalArgumentException(
          "未登记的制度，无法推导默认经营主体：" + regime.value() + "；已登记的档: " + BY_REGIME.keySet());
    }
    return new ActorRef(kind, industry.value());
  }

  /** 登记面（**保序**：spec §六 表序）；返回的 Map 不可变。 */
  public static Map<String, ActorKind> registered() {
    return BY_REGIME;
  }

  /**
   * ★★ <b>生产方式 → 它默认使用的生产制度（唯一映射点）</b>：{@code tenancy_* → tenant}、{@code wage_farm → feudal}、
   * {@code handicraft_workshop → handicraft}、{@code family_farm → household}、{@code merchant → merchant}；
   * 其余（{@code displaced}/GM 自定义 mode）⇒ 空（调用方按自己的 fail-closed 回退）。
   *
   * <p>★ 它是"按 mode 选产业模板"的唯一拼写点：{@code EconomyEnterpriseSettlement.selectIndustry}、
   * {@code ModeMigrationPolicy.industriesForMode}、{@code ModeMigrationSettlement} 都读它，免得三处各写一遍 if 链。
   */
  public static Optional<String> defaultRegimeForMode(ProductionModeId modeId) {
    if (modeId == null) {
      throw new IllegalArgumentException("defaultRegimeForMode 的 modeId 不得为 null");
    }
    if (DefaultProductionModes.TENANCY_FIXED_KIND.equals(modeId)
        || DefaultProductionModes.TENANCY_SHARE.equals(modeId)
        || DefaultProductionModes.TENANCY_CASH.equals(modeId)) {
      return Optional.of(TENANT);
    }
    if (DefaultProductionModes.WAGE_FARM.equals(modeId)) {
      return Optional.of(FEUDAL);
    }
    if (DefaultProductionModes.HANDICRAFT_WORKSHOP.equals(modeId)) {
      return Optional.of(HANDICRAFT);
    }
    if (DefaultProductionModes.FAMILY_FARM.equals(modeId)) {
      return Optional.of(HOUSEHOLD);
    }
    if (DefaultProductionModes.MERCHANT.equals(modeId)) {
      return Optional.of(MERCHANT);
    }
    return Optional.empty();
  }
}
