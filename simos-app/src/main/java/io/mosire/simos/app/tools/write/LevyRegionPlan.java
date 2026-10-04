package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * ★★ {@code simos.unit.levyRegion} 的<b>纯推导</b>（辖区阶段 6 / 计划 §6.0；阶段 11b 补 cloth）：从一份 {@link
 * SimulationState} 与四项请求量算出 {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、不碰
 * {@code CoreSimos}</b>，preview 与 apply 因此共用同一份语义（工具只负责读态、组批、折叠结局）。
 *
 * <p>★★ <b>推导口径（逐条对应计划 §6.0；cloth 是阶段 11b 的追加）</b>：
 *
 * <ol>
 *   <li><b>单位 / 管辖 / 区域</b>：单位必须存在；{@code jurisdiction} 必须存在且 {@code regionId} 在其 key 集；区域必须存在于
 *       {@code GameMap.regions()}。任一不成立 ⇒ 具名 {@link IllegalArgumentException}（带 {@code
 *       unit.SetJurisdiction} 指路）；
 *   <li><b>单命令上限</b>：粮 / 钱 / 人每项 {@code requested ≤ levy*CapPerCommand}（0 = 该类无额度 ⇒ requested &gt;
 *       0 即拒），拒因带 requested / cap 与字段名；★ <b>cloth 本批只受可用量约束；上限字段留后续</b>——{@code Jurisdiction}
 *       只有粮/钱/人三条 上限，本类不为布发明第四条；
 *   <li><b>国库落点</b>：单位<b>当刻有效位置</b>（{@link UnitState#effectivePosition(UnitId, SimosTimestamp)}）；
 *       无位置 ⇒ 具名拒（指路 {@code unit.PlaceAt}）；
 *   <li><b>粮 / 钱 / 布来源</b>：region 各 hex 上 {@link
 *       io.mosire.simos.actor.api.actor.ActorKind#HOUSEHOLD} 的 actor 账，可用量 = {@link
 *       AvailableStock#available(GoodsAccount, CommodityId)} / {@link
 *       AvailableStock#available(GoodsAccount,
 *       io.mosire.simos.economy.api.id.CurrencyId)}（<b>唯一算法</b>，本类不重写减法）； 总量不足 ⇒ <b>整条拒</b>（带
 *       requested / available / 缺口，不部分、不截断）；
 *   <li><b>分摊 = 瀑布</b>：三个账维度共用 {@link RegionAllocations#allocateAccounts}（可用量降序、同量按账键 {@link
 *       io.mosire.simos.actor.model.GoodsAccountKey#toString()} 升序，逐户扣满为止）；
 *   <li><b>人力来源</b>：{@code social.groups()} 里 residence 在 region 各 hex、{@link
 *       io.mosire.simos.social.api.population.Sex#MALE}、且 {@code
 *       io.mosire.simos.social.population.AgeBracket.of(clock.system(),
 *       clock.dayNumberOfTick(tick), ageDaysAt(tick))} == {@link
 *       io.mosire.simos.social.population.AgeBracket#ADULT} 的批次（年龄按<b>当前 tick + 历法现算</b>，15/60
 *       整历法年、阈值不在本类另写）； 同一瀑布（count 降序、id 升序），不足 ⇒ 整条拒；
 *   <li><b>四项独立</b>：requested = 0 的维度整段跳过（不扫描、不产生来源条目、不建账）。
 * </ol>
 *
 * <p>★ <b>确定性</b>：本类是状态的纯函数——同一状态 + 同一参数 ⇒ 逐字段相同的 {@link Plan}（来源表是显式 {@link List}，排序键是内容的全序；没有遍历
 * {@code Map} 迭代序的余地）。Plan 里没有随机量、也没有墙钟时间：{@code tick} 是状态 meta 的函数。
 *
 * <p>★ <b>保序不可变</b>：Plan 与它的来源表都以 {@link List#copyOf} 冻住，不暴露可变集合；来源表本身按推导顺序 （瀑布序）排列，正是工具组批时要用的顺序。
 */
final class LevyRegionPlan {

  /** 粮的商品 id（{@link PilotModel#GRAIN} 的<b>唯一</b>字面量来源；本类不另写 {@code "grain"}）。 */
  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);

  /**
   * 布的商品 id：取 {@link PilotModel#CLOTH} 这个<b>既有常量</b>作为唯一拼写点（不另写 {@code "cloth"}）。
   *
   * <p>★ 为什么不取 {@code EconomyVocabulary.CLOTH_COMMODITY_ID}：本类的粮已经用 {@link
   * PilotModel#GRAIN}，粮/布同批必须同源； 而 {@code GovDaily} 的行政物资走 {@code EconomyVocabulary} 是 gov
   * 模块自己的边界，不在本类复述第二份词表。两个常量都只是 {@code "cloth"} 字符串，谁都没有另造值。
   */
  private static final CommodityId CLOTH = new CommodityId(PilotModel.CLOTH);

  private LevyRegionPlan() {}

  /**
   * 纯推导入口（见类注的七条口径）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 抽取主体（军事单位，国库的 owner）
   * @param regionId 抽取区域（必须在该单位的管辖 key 集里、且存在于地图）
   * @param grain 粮请求量（&ge; 0；0 = 本维度整段跳过）
   * @param money 钱请求量（&ge; 0；0 = 本维度整段跳过）
   * @param cloth 布请求量（&ge; 0；0 = 本维度整段跳过；★ 本批无单命令上限，只受可用量约束）
   * @param manpower 人力请求量（&ge; 0；0 = 本维度整段跳过）
   * @throws IllegalArgumentException 任一具名拒（工具折成 {@code BAD_REQUEST}）
   */
  // ★ 测试/旧路径：全缺省儒略历时钟；生产路径由 CalendarService.clock() 传入。
  static Plan plan(
      SimulationState state,
      String unitId,
      String regionId,
      long grain,
      long money,
      long cloth,
      long manpower) {
    return plan(
        state, unitId, regionId, grain, money, cloth, manpower, CalendarClock.julianDefault());
  }

  /**
   * 生产入口：历法时钟由调用方传入（本类的人力年龄档判定只认这台钟）。
   *
   * @param clock 历法时钟（非空；生产路径 = CalendarService.clock()）
   */
  static Plan plan(
      SimulationState state,
      String unitId,
      String regionId,
      long grain,
      long money,
      long cloth,
      long manpower,
      CalendarClock clock) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(clock, "clock");
    requireNonNegative(grain, "grain");
    requireNonNegative(money, "money");
    requireNonNegative(cloth, "cloth");
    requireNonNegative(manpower, "manpower");
    if (grain == 0L && money == 0L && cloth == 0L && manpower == 0L) {
      throw new IllegalArgumentException("grain/money/cloth/manpower 四项全为 0，没有任何抽取；至少给一项 > 0");
    }
    UnitId id = UnitId.parse(unitId);
    RegionId regionKey = RegionId.parse(regionId);
    UnitState units = ToolSupport.unitState(state);
    Unit unit = units.units().get(id);
    if (unit == null) {
      throw new IllegalArgumentException("单位不存在: " + unitId);
    }
    Jurisdiction jurisdiction =
        unit.jurisdiction()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "单位 "
                            + unitId
                            + " 没有 jurisdiction（无管辖区域）；先 unit.SetJurisdiction 设管辖与三个"
                            + " levy*CapPerCommand"));
    if (!jurisdiction.taxRatePerMilleByRegion().containsKey(regionKey)) {
      throw new IllegalArgumentException(
          "单位 " + unitId + " 的管辖不含区域 " + regionId + "；先 unit.SetJurisdiction 把它纳入管辖");
    }
    GameMap map = ToolSupport.gameMap(state);
    Region region = map.regions().get(regionKey);
    if (region == null) {
      throw new IllegalArgumentException(
          "地图里没有区域: " + regionId + "（先 map.CreateRegion，或用 unit.SetJurisdiction 改指到既有区域）");
    }
    requireWithinCap("粮", "levyGrainCapPerCommand", grain, jurisdiction.levyGrainCapPerCommand());
    requireWithinCap("钱", "levyMoneyCapPerCommand", money, jurisdiction.levyMoneyCapPerCommand());
    // ★ cloth 本批不设单命令上限：Jurisdiction 只有粮/钱/人三条上限，本类不为布发明第四条（上限字段留后续）。
    requireWithinCap(
        "人力", "levyManpowerCapPerCommand", manpower, jurisdiction.levyManpowerCapPerCommand());
    SimosTimestamp at = state.meta().timestamp();
    long tick = at.tick();
    HexCoord treasuryLocation =
        units
            .effectivePosition(id, at)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "单位 " + unitId + " 当刻没有有效位置，国库落点无法确定；先 unit.PlaceAt"));
    // ★ requested = 0 的维度整段跳过：不扫描来源、不进 Plan 的来源表（available 记 0 = "未求值"）。
    Dimension grainDimension =
        grain == 0L
            ? Dimension.skipped()
            : accountDimension(
                state, region, "粮", grain, account -> AvailableStock.available(account, GRAIN));
    Dimension moneyDimension =
        money == 0L
            ? Dimension.skipped()
            : accountDimension(
                state,
                region,
                "钱",
                money,
                account -> AvailableStock.available(account, MoneyVocabulary.SILVER_CURRENCY));
    Dimension clothDimension =
        cloth == 0L
            ? Dimension.skipped()
            : accountDimension(
                state, region, "布", cloth, account -> AvailableStock.available(account, CLOTH));
    Manpower manpowerDimension =
        manpower == 0L
            ? Manpower.skipped()
            : manpowerDimension(state, region, tick, manpower, clock);
    return new Plan(
        unitId,
        regionId,
        tick,
        treasuryLocation,
        grainDimension,
        moneyDimension,
        clothDimension,
        manpowerDimension);
  }

  // ── 粮 / 钱：HOUSEHOLD 账的瀑布（商品与货币共用同一段分摊）─────────────────────────────

  /**
   * 一个维度的分摊：委托给 {@link RegionAllocations#allocateAccounts}（<b>全仓唯一一份家户账瀑布</b>；本类只做结果类型转换， 保证 {@link
   * Dimension}/{@link AccountSource} 的对外形状逐字不变）。
   *
   * @param availableOf 可用量的唯一算法（由调用方传 {@link AvailableStock} 的对应重载——本类不写减法）
   */
  private static Dimension accountDimension(
      SimulationState state,
      Region region,
      String label,
      long requested,
      ToLongFunction<GoodsAccount> availableOf) {
    RegionAllocations.AccountAllocation allocation =
        RegionAllocations.allocateAccounts(
            ApiViews.actorData(state), region, label, requested, availableOf);
    List<AccountSource> sources = new ArrayList<>(allocation.sources().size());
    for (RegionAllocations.AccountSource source : allocation.sources()) {
      sources.add(new AccountSource(source.owner(), source.at(), source.amount()));
    }
    return new Dimension(allocation.requested(), allocation.available(), List.copyOf(sources));
  }

  // ── 人力：social 批次的瀑布 ──────────────────────────────────────────────────────────

  /**
   * 人力维度的分摊：委托给 {@link RegionAllocations#allocateManpower}（<b>全仓唯一一份人力瀑布</b>；口径见类注第 6 条）。
   * 本类只做结果类型转换，保证 {@link Manpower}/{@link GroupSource} 的对外形状逐字不变。
   */
  private static Manpower manpowerDimension(
      SimulationState state, Region region, long tick, long requested, CalendarClock clock) {
    RegionAllocations.ManpowerAllocation allocation =
        RegionAllocations.allocateManpower(
            ToolSupport.socialData(state), region, tick, requested, clock);
    List<GroupSource> sources = new ArrayList<>(allocation.sources().size());
    for (RegionAllocations.GroupSource source : allocation.sources()) {
      sources.add(new GroupSource(source.group(), source.taken()));
    }
    return new Manpower(allocation.requested(), allocation.available(), List.copyOf(sources));
  }

  // ── 校验小件 ────────────────────────────────────────────────────────────────────────

  private static void requireNonNegative(long value, String name) {
    if (value < 0L) {
      throw new IllegalArgumentException(name + " 不得为负: " + value);
    }
  }

  /** 单命令上限：requested &le; cap（0 = 无额度）；拒因带维度、requested、cap 与字段名。 */
  private static void requireWithinCap(String label, String capField, long requested, long cap) {
    if (requested > cap) {
      throw new IllegalArgumentException(
          label
              + " requested="
              + requested
              + " 超过 "
              + capField
              + "="
              + cap
              + "（上限语义 = 一条抽取命令；0 = 该类无额度；先 unit.SetJurisdiction 调上限）");
    }
  }

  // ── Plan 与来源表（全部保序不可变）────────────────────────────────────────────────────

  /**
   * 一份推导结果：坐标 + 国库落点 + 四个维度。
   *
   * @param unitId 抽取主体
   * @param regionId 抽取区域
   * @param tick 推导时的世界日（人力的现算年龄与行动记录的 tick 都用它）
   * @param treasuryLocation 国库落点 = 单位当刻有效位置
   * @param cloth 布维度（阶段 11b；★ 无单命令上限，只受可用量约束）
   */
  record Plan(
      String unitId,
      String regionId,
      long tick,
      HexCoord treasuryLocation,
      Dimension grain,
      Dimension money,
      Dimension cloth,
      Manpower manpower) {

    Plan {
      if (unitId == null || unitId.isBlank()) {
        throw new IllegalArgumentException("unitId 不得为空白");
      }
      if (regionId == null || regionId.isBlank()) {
        throw new IllegalArgumentException("regionId 不得为空白");
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      Objects.requireNonNull(treasuryLocation, "treasuryLocation");
      Objects.requireNonNull(grain, "grain");
      Objects.requireNonNull(money, "money");
      Objects.requireNonNull(cloth, "cloth");
      Objects.requireNonNull(manpower, "manpower");
    }

    /** 是否需要落 {@code actor.AdjustAccounts}（粮 / 钱 / 布任一 > 0）。 */
    boolean hasAccountMovements() {
      return grain.requested() > 0L || money.requested() > 0L || cloth.requested() > 0L;
    }

    /** 是否需要落 {@code social.SeedGroups}（人力 > 0）。 */
    boolean hasManpower() {
      return manpower.requested() > 0L;
    }
  }

  /**
   * 粮 / 钱 / 布一个维度的推导结果。
   *
   * @param requested 请求量（0 = 本维度整段跳过）
   * @param available 来源总量（requested = 0 时为 0 = 未求值；有请求时 = 全部合格来源的可支配量之和）
   * @param sources 实际扣减的来源（瀑布序；requested = 0 时为空）
   */
  record Dimension(long requested, long available, List<AccountSource> sources) {

    Dimension {
      requireNonNegative(requested, "requested");
      requireNonNegative(available, "available");
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    static Dimension skipped() {
      return new Dimension(0L, 0L, List.of());
    }
  }

  /** 一个家户账来源：{@code (owner, 格)} 与从它扣走的数量（> 0）。 */
  record AccountSource(ActorRef owner, HexCoord at, long amount) {

    AccountSource {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(at, "at");
      if (amount <= 0L) {
        throw new IllegalArgumentException("amount 必须 > 0: " + amount);
      }
    }
  }

  /**
   * 人力一个维度的推导结果。
   *
   * @param requested 请求量（0 = 本维度整段跳过）
   * @param available 全部合格批次的人数之和（requested = 0 时为 0 = 未求值）
   * @param sources 实际抽人的批次（瀑布序）
   */
  record Manpower(long requested, long available, List<GroupSource> sources) {

    Manpower {
      requireNonNegative(requested, "requested");
      requireNonNegative(available, "available");
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    static Manpower skipped() {
      return new Manpower(0L, 0L, List.of());
    }
  }

  /** 一个被动批次：整条覆盖用的必要字段 + 抽走的人数；{@code countAfter} 可为 0（合法空批）。 */
  record GroupSource(PopulationGroup group, long taken) {

    GroupSource {
      Objects.requireNonNull(group, "group");
      if (taken <= 0L) {
        throw new IllegalArgumentException("taken 必须 > 0: " + taken);
      }
      if (taken > group.count()) {
        throw new IllegalArgumentException(
            "taken 不得超过批次人数: taken=" + taken + "，count=" + group.count());
      }
    }

    long countAfter() {
      return group.count() - taken;
    }
  }
}
