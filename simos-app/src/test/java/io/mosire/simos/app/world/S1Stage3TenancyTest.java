package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetClassKey;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.AssetHolding;
import io.mosire.simos.actor.model.AssetHoldingKey;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ **I3.2：租佃档 + `AssetOwner ≠ Operator`**（S1 阶段 3 Task 5；spec §2.1「四者可以大量重合，但模型<b>不预设</b>它们必然相同。
 * 佃制（`AssetOwner ≠ Operator`）是第一个实例」、§2.3 末段、§六 末行）。
 *
 * <p>★ <b>为什么本用例只能住 {@code simos-app}</b>：产权住在 `simos-actor`（{@link AssetHolding}），经营主体住在
 * `simos-economy`（{@link Industry#operator()}）—— 两个切片<b>互不依赖</b>（enforcer 把守），<b>只有 app 同时认识两边</b>。
 *
 * <p>★★ <b>佃制 = 两条互不牵连的记录</b>（spec §2.3 的原文形状，本文件的夹具逐字落实）：
 *
 * <pre>
 * ActorData.holdings()   ESTATE:farm@0_0 在第 0_0 格持有 B 等地 10,000（产权）
 * Industry.operator()    HOUSEHOLD:house-7（佃农家户）组织生产（经营）
 * </pre>
 *
 * <p>⇒ 本文件的断言<b>不是</b>"二者相等"，而是：<b>两条记录同时成立、且模型里没有任何东西把二者绑起来</b>。故两个方向各断言一次 ——
 * 产权侧（这些地只有庄园一个主人）与经营侧（经营者名下一条产权都没有）。
 *
 * <p>★★ <b>夹具是"非派生"的</b>（D9 / D11 / D13 的教训：<b>判别力来自夹具，不来自断言</b>）：`tenant` 档的推导值是
 * `HOUSEHOLD:farm@0_0`，而 {@link #TENANT_HOUSEHOLD} 是<b>显式</b>写进载荷的 `HOUSEHOLD:house-7` ——
 * <b>与推导值不同</b>（前置断言自证），且与资产所有者<b>种类、id 都不同</b>。若夹具取那个推导值，"显式绑定的 operator 被重新推导覆盖"
 * 这类变异体会<b>存活</b>；若夹具让两者同主体，"两条记录互不牵连"这半句会退化成恒真。
 */
class S1Stage3TenancyTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));
  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final HexCoord HEX = new HexCoord(0, 0);

  /**
   * <b>资产所有者</b>：庄园。★ id 取<b>产业 id</b>（{@code RegimeOperators} 类注 R3：播种器写下的劳动侧 actor id 本就是产业 id，
   * 两侧同字面）—— 这正是"领主自营"那一档的拼法，故它也代表了"把 operator 当成资产所有者"的变异体会给出的那个值。
   */
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, FARM.value());

  /**
   * ★★ <b>经营主体</b>：佃农家户。它与 {@link #ESTATE} <b>种类不同、id 也不同</b>，且<b>不等于</b> `tenant` 档的推导值
   * （`HOUSEHOLD:farm@0_0`，见 {@link #theAssetOwnerIsNotTheOperator} 的前置断言）。
   */
  private static final ActorRef TENANT_HOUSEHOLD = new ActorRef(ActorKind.HOUSEHOLD, "house-7");

  /**
   * 载荷里<b>整段</b>可选键 {@code operator}（含前导逗号）。
   *
   * <p>★ <b>从 {@link #TENANT_HOUSEHOLD} 现拼</b>，不手抄一份字面量：否则"载荷里写的"与"断言期望的"成了两个拼写点， 日后改一处就漂一处（同 D14
   * 的理由）。
   */
  private static final String OPERATOR_FIELD =
      ",\"operator\":{\"kind\":\""
          + TENANT_HOUSEHOLD.kind()
          + "\",\"id\":\""
          + TENANT_HOUSEHOLD.id()
          + "\"}";

  /** spec §2.3 点名的那个资产类：{@code LAND(arable=true, quality=B)}。 */
  private static final AssetClassKey ARABLE_B =
      AssetClassKey.land(Map.of("arable", "true", "quality", "B"));

  /** ★★ I3.2：租佃档<b>存在</b>（登记在推导表里），且默认经营主体是<b>佃农家户</b>、不是地主。 */
  @Test
  void theTenancyRegimeIsRegisteredAndDefaultsToTheTenantHousehold() {
    Industry industry = seededIndustry(RegimeOperators.TENANT);

    assertThat(industry.operator().kind())
        .as("★ 佃农家户经营（spec §六 第四行）—— 不是 ESTATE")
        .isEqualTo(ActorKind.HOUSEHOLD);
    assertThat(industry.operator()).isNotEqualTo(ESTATE);
  }

  /**
   * ★★ I3.2 的核心：`AssetOwner ≠ Operator` 不只是"可表达"，在本用例里<b>已经成立</b>。
   *
   * <p>两个正交事实各写各的：产权在 {@code ActorData.holdings}（主体 = 庄园），经营在 {@code Industry.operator} （主体 =
   * 佃农家户）。<b>没有任何一处把二者绑起来</b>。
   */
  @Test
  void theAssetOwnerIsNotTheOperator() {
    Industry industry = seededIndustry(RegimeOperators.TENANT, OPERATOR_FIELD);
    ActorData data =
        ActorData.empty()
            .withActor(new Actor(ESTATE, "庄园"))
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 10_000L));

    // ── 前提：两件事各自真的成立（否则下面的断言测的是别的东西）──────────────────────────
    assertThat(industry.regime().value())
        .as("前置：这一格是租佃档（spec §六 第四行）")
        .isEqualTo(RegimeOperators.TENANT);
    assertThat(TENANT_HOUSEHOLD)
        .as("★ 前置：夹具必须**非派生** —— operator 不得等于该档的推导值（否则「显式绑定被重新推导覆盖」的变异体存活）")
        .isNotEqualTo(RegimeOperators.defaultOperator(new RegimeId(RegimeOperators.TENANT), FARM));
    assertThat(industry.operator())
        .as("★ 前置：载荷里显式写的那个 operator 逐值活到 Industry（没有被边缘换成别的）")
        .isEqualTo(TENANT_HOUSEHOLD);
    assertThat(data.holdings())
        .as("★ 前置：这条产权真的在模型里 —— 否则下面的 allMatch / noneMatch 在空集上恒真，空断言不是证据")
        .containsKey(new AssetHoldingKey(ESTATE, HEX, ARABLE_B));

    // ── 两个方向：产权那条记录 ────────────────────────────────────────────────────────
    assertThat(data.holdings().keySet())
        .as("★ 这块地归庄园 —— 「谁的地」只有一个答案")
        .allMatch(key -> key.owner().equals(ESTATE));

    // ── 两个方向：经营那条记录（**反向**）──────────────────────────────────────────────
    assertThat(data.holdings().keySet())
        .as("★★ 反向：经营者名下**一条产权都没有** ⇒ 两个事实互不牵连")
        .noneMatch(key -> key.owner().equals(industry.operator()));
    assertThat(industry.operator())
        .as("★★ 同一格：地是庄园的、活是佃农家户干的（spec §2.3 的原文形状）")
        .isNotEqualTo(ESTATE);
  }

  // ── 夹具：真载荷 → 真 handler → 变更集重建（照 EconomyRealScaleSeedBottleneckTest 的 REF / snapshots 写法）──

  /** 真载荷，<b>不带</b> {@code operator} 键 ⇒ 走载荷边缘的 regime 推导（{@code RegimeOperators}）。 */
  private static Industry seededIndustry(String regime) {
    return seededIndustry(regime, "");
  }

  /**
   * 真载荷（{@code entries[0].industries[0]}）+ 真 {@link EconomySeedHandler}，再从变更集重建出该产业。
   *
   * <p>★ {@code classes} / {@code outputPerUnit} / {@code cycleInputPerUnit} 在本载荷里<b>都省略</b>（{@code
   * optionalArray} / {@code optionalObject}）—— 本判据只关心 {@code regime} 与 {@code
   * operator}，多填的行会把别的面的校验也拉进来。
   *
   * @param operatorField {@code industries[]} 里的整段可选键（含前导逗号）；空串 = 该键<b>整段缺席</b>
   */
  private static Industry seededIndustry(String regime, String operatorField) {
    String payload =
        "{\"mapId\":\"tenancy\",\"rulesVersion\":\"aggregate-v1\",\"entries\":[{\"q\":0,\"r\":0,"
            + "\"industries\":[{\"id\":\""
            + FARM.value()
            + "\",\"name\":\"农业\",\"regime\":\""
            + regime
            + "\""
            + operatorField
            + ",\"cycleDays\":120,\"capacityPerUnit\":{\"LAND\":1000},\"laborPerUnit\":143,"
            + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":700,\"laborWeightPerMille\":300},"
            + "\"slots\":[{\"id\":\"poor_peasant\",\"name\":\"贫农\",\"laborParticipationPerMille\":950}]}]}]}";
    SimulationState empty =
        new SimulationState(
            new StateMeta(REF, SimosTimestamp.of(0)),
            snapshots(EconomyData.empty()),
            InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(empty, payload);
    assertThat(outcome)
        .as("真载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    return EconomyChangeSet.apply(
            (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), EconomyData.empty())
        .industries()
        .get(FARM);
  }

  private static Map<String, Snapshot> snapshots(EconomyData data) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("economy", new EconomySnapshot(REF, SimosTimestamp.of(0), data));
    return modules;
  }
}
