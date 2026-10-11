package io.mosire.simos.unit.tools;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 单位工具面的**分摊口**：两座"瀑布"的唯一实现在 app，这里只声明域内够得着的入口。
 *
 * <p>★★ <b>为什么必须注入、不许在 unit 里重写</b>：
 *
 * <ul>
 *   <li><b>家户账瀑布</b>（{@code app.tools.write.RegionAllocations.allocateAccounts}）遍历的是 {@code
 *       ActorData.accounts()} 的 {@code (HouseholdAccountKey → HouseholdInventory)}，减法走 {@code
 *       actor.model.AvailableStock}——这两个类型住在 **{@code simos-actor} 实现**里，而 unit 的允许面只有
 *       util/map/social-api/social（enforcer 禁列）⇒ unit **结构上看不见那本账**（"家户账本切片 = 家户位置派生" 这条口径也由它持有）；
 *   <li><b>选人瀑布</b>（{@code app.tools.write.HouseholdManpowerAllocator}）是**全仓唯一的 share-aware 选人层**：
 *       {@code levyRegion} / {@code raiseUnit} / 征兵 / 考选 / 吸收 / 战斗解算六条链路共用它，且它要一台 {@code
 *       CalendarClock} （历法装配在组合根）。army 批已用同一条处置（{@code ArmyManpowerPort}）⇒ 这里照抄，**不抄第二份**；
 *   <li>两座瀑布的既有类注都明写"必须只有这一份"（{@code RegionAllocations}：两处各写一遍排序与扣减，就会在某个版本里悄悄漂开）； 故本端口的方法体在 app
 *       侧**逐条转调**，不做任何重排、重算、四舍五入。
 * </ul>
 *
 * <p>★★ <b>结果类型为什么由 unit 自己定义</b>：{@code RegionAllocations.AccountAllocation} 的字段里有 {@code
 * ActorRef}（actor-api，**可见**）与 {@code HexCoord}（map，可见），但它整类住在 app；{@code
 * HouseholdManpowerAllocator.Allocation} 同理。端口把两份结果**逐字段映射**成域内记录（字段名与顺序逐字相同，不增不减），调用方因此不必认识 app。 ★
 * 映射是**值搬运**、不是第二份算法：排序键、 瀑布序、守恒校验都仍在 app 的唯一实现里。
 *
 * <p>★ 本接口只能住在 {@code simos-unit}：签名里有 {@link Region} / {@link SocialData} / {@link CommodityId} /
 * {@link CurrencyId}——契约层 {@code simos-ga-spi} 不许依赖任何领域模块（I-G2）。
 */
public interface UnitToolAllocations {

  /**
   * 一个维度的**家户账**分摊（粮 / 钱 / 布共用同一段瀑布，只有"可用量怎么算"不同）： region 各 hex 上 HOUSEHOLD 账的可支配量 → 过滤 ≤ 0 →
   * 可用量降序、同量按账键升序 → 总量不足整条拒 → 逐户扣满。
   *
   * @param state 读数所在的状态（调用方与 preview / apply 取**同一坐标**）
   * @param region 来源区域（调用方已确认它存在）
   * @param label 维度名（{@code 粮}/{@code 钱}/{@code 布}），只进拒因
   * @param requested 请求量（0 = 本维度整段跳过，实现按既有口径回 {@link AccountAllocation#skipped()}）
   * @param commodity 商品维度（粮 / 布）
   */
  AccountAllocation allocateCommodityAccounts(
      SimulationState state, Region region, String label, long requested, CommodityId commodity);

  /**
   * 同 {@link #allocateCommodityAccounts}，但可用量走**货币**那一本（{@code AvailableStock.available(inventory,
   * currency)}）。
   */
  AccountAllocation allocateMoneyAccounts(
      SimulationState state, Region region, String label, long requested, CurrencyId currency);

  /**
   * **人力**维度的分摊：region 内 MALE + 成年档的 Social 家户份额（share-aware；同一 lot 多户持有各自成候选）， 按全序瀑布逐份抽满。
   *
   * <p>★ 历法时钟由 app 侧实现持有（{@code CalendarService.clock()}，与迁入前逐字同源）——calendar 不在 unit
   * 的允许面里，故它不进本签名的参数表。
   *
   * @param social 该坐标上的 social 切片
   * @param region 来源区域（只取 {@code hexes()} 的格集）
   * @param requested 请求量（0 时调用方整段跳过，不调本方法）
   * @param tick 现算年龄用的当前世界日
   * @param excluded 排除的家户（自我转移会被 Social 域层拒）
   */
  ManpowerAllocation allocateMalesOfAdult(
      SocialData social, Region region, long requested, long tick, Set<HouseholdId> excluded);

  /**
   * 一个家户账维度的分摊结果（{@code RegionAllocations.AccountAllocation} 的逐字段投影）。
   *
   * @param requested 请求量（0 = 本维度整段跳过）
   * @param available 来源总量（requested = 0 时为 0 = 未求值）
   * @param sources 实际扣减的来源（瀑布序；requested = 0 时为空）
   */
  record AccountAllocation(long requested, long available, List<AccountSource> sources) {

    public AccountAllocation {
      if (requested < 0L) {
        throw new IllegalArgumentException("requested 不得为负: " + requested);
      }
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      // ★ 保序冻结在赋值处（不用 List.copyOf：迭代序不是内容的纯函数，且 SpotBugs 认不出它的不可变性）。
      sources =
          Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(sources, "sources")));
    }

    /** 本维度整段跳过（requested = 0）。 */
    public static AccountAllocation skipped() {
      return new AccountAllocation(0L, 0L, List.of());
    }
  }

  /** 一个家户账来源：{@code (owner, 格)} 与从它扣走的数量（&gt; 0）。 */
  record AccountSource(ActorRef owner, HexCoord at, long amount) {

    public AccountSource {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(at, "at");
      if (amount <= 0L) {
        throw new IllegalArgumentException("amount 必须 > 0: " + amount);
      }
    }
  }

  /**
   * 人力一个维度的分摊结果（{@code HouseholdManpowerAllocator.Allocation} 的逐字段投影）。
   *
   * @param requested 请求量（0 = 本维度整段跳过）
   * @param available 全部合格家户份额的人数之和（requested = 0 时为 0 = 未求值）
   * @param shares 实际抽到的家户份额（瀑布序；Σ {@code taken == requested}）
   */
  record ManpowerAllocation(long requested, long available, List<ManpowerShare> shares) {

    public ManpowerAllocation {
      if (requested < 0L) {
        throw new IllegalArgumentException("requested 不得为负: " + requested);
      }
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      shares =
          Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(shares, "shares")));
    }

    /** 本维度整段跳过（requested = 0）。 */
    public static ManpowerAllocation skipped() {
      return new ManpowerAllocation(0L, 0L, List.of());
    }
  }

  /**
   * 一条家户份额（{@code HouseholdManpowerAllocator.ManpowerShare} 的逐字段投影）。
   *
   * @param householdId 来源家户
   * @param lotId 来源批次 id（保持原 id，不派生新 id）
   * @param hex 来源格；{@code UNIT} 家户（如 {@code hh-gov-<unitId>}）没有格 ⇒ <b>null</b>（合法值）
   * @param taken 从该份额抽走的人数（&gt; 0）
   */
  record ManpowerShare(HouseholdId householdId, PeopleLotId lotId, HexCoord hex, long taken) {

    public ManpowerShare {
      Objects.requireNonNull(householdId, "householdId");
      Objects.requireNonNull(lotId, "lotId");
      if (taken <= 0L) {
        throw new IllegalArgumentException("taken 必须 > 0: " + taken);
      }
    }
  }
}
