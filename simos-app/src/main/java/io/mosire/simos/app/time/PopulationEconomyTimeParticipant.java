package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.population.LotMigration;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.MigrationPolicy;
import io.mosire.simos.economy.time.AccountPartitionKey;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.EconomyDayStepper;
import io.mosire.simos.economy.time.EconomyParallelism;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionLedger.ActorEntry;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationDynamics;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.TimeRange;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ **人口—经济协调器**（R4）：**唯一同时看得见 {@code social} 与 {@code economy} 的推进参与者** —— 于是"人"第一次真的随时间变：**年龄推进
 * + 逐日生理压力 + 月度出生/死亡**，而且**死亡会同时反映到经济侧的阶层行与劳动配额上**。
 *
 * <p>★★ **为什么必须有它**（不是"图省事"，是结构上只能如此）：
 *
 * <ol>
 *   <li>**出生/死亡只能算在人口那一侧**（`年龄 × 性别 × 基础死亡率 × 生理压力` —— 年龄与性别是 {@link PopulationGroup}
 *       的属性），**而"吃得饱不饱"只算在经济那一侧**（需求与实得都在 {@code FlowRow} 里）⇒ 判定需要两侧同时在场；
 *   <li>**§十一 等价性**（一次推 N 天 == N 次单日）要求"逐日"这条语义落在**同一个参与者内部** —— 若让两个参与者各自读对方的**基态**，一次推 365
 *       天时社会侧只能看到第 0 天的经济状态， 而 365 次单日推进每天都能看到前一天的 ⇒ **两条路径必然不等价**；
 *   <li>跨切片写要求"同一模块只能有一个写者"（{@code TimeProposalResolver} 的写-写检查）⇒ 同时写这两片的参与者**只能有一份**。
 * </ol>
 *
 * <p>★ 它住在 {@code simos-app}：设计稿 §8.2 的原文——"跨 {@code social}+{@code economy} 的协调器**必须住 {@code
 * simos-app}** （唯一认识所有模块的地方）"。★ 它**内联调用** {@link EconomySettlement} 的包内可见日结算入口， 从而与 {@code
 * EconomyTimeParticipant} 共用同一个结算实现（**只有一条真相**，不是两份公式）。
 *
 * <p>★★ **一天的次序**（缺一不可）：
 *
 * <pre>
 * ① 经济结算一天（消费/借粮/进度/劳动/周期末收获）      —— 行人口仍是"上个月末"的
 * ② 生理压力：读**当天**的发生额（需求与实得）⇒ 逐批次 stressAfter
 * ③ 每 30 天：月度结算（出生/死亡）⇒ 改社会侧的 count，并把同一份账回写经济侧（行人口/配额/流水）
 * </pre>
 *
 * <p>★★ <b>H4：两份副本（商品 + 货币）按同一顺序收尾</b>：<b>载入</b>（{@link OwnershipBooks#loadHouseholdGoods} / {@link
 * OwnershipBooks#loadHouseholdMoney}）→ step（两者都由 {@code EconomyDayStepper} 就地更新）→ 条目落账 （{@link
 * OwnershipBooks#apply}）→ **两份副本按绝对值落回**（先商品、后货币；顺序不能反，因为它们写的是同一本 {@code GoodsAccount} 的两个余额表）。
 *
 * <p>★★ **它是"人口守恒"的落点**：出生与死亡在这一处算出来、在两侧各落一次账（社会侧改 {@code count}、 经济侧改行人口与 {@code
 * FlowRow.births/deaths}）⇒ {@code Σ新人口 == Σ旧人口 + 出生 − 死亡} 逐值可核。
 *
 * <p>★ **未激活/无上界**：经济未激活（{@code meta} 空）⇒ **两侧都交不变变更集**（没有生活资料信号 ⇒ 人口不动， 这正是"世界还没播种"该有的样子）；{@code
 * range.to} 缺省 ⇒ 同样交不变变更集、不抛（该推进随后必被 Core 拒）。
 */
public final class PopulationEconomyTimeParticipant implements TimeParticipant {

  private static final Logger LOG = LoggerFactory.getLogger(PopulationEconomyTimeParticipant.class);

  /** 参与者身份（**不是模块名**：它同时写 {@code social} 与 {@code economy} 两个模块）。 */
  public static final String NAMESPACE = "population";

  private static final String ECONOMY = "economy";
  private static final String SOCIAL = "social";

  /** ★ T5 的第三片（产权落账）：actor 切片必须在场（缺席 ⇒ 抛 —— 产出没有地方落）。 */
  private static final String ACTOR = "actor";

  private final String mapId;

  /**
   * ★★ <b>经济日结算的 worker 数</b>（R2）：由组合根从 {@code ShellConfig.economyWorkerCount()} 传入；缺省 1 = 单线程退化路径。
   *
   * <p>★ 本参与者在每次 {@code simulateWorld} 里构造一次 {@link EconomyParallelism#of(int)}（自建池），并由 {@link
   * EconomyDayStepper#finish()}/{@link EconomyDayStepper#close()} 关池；异常路径由 {@code try/finally} 收口
   * ——推进失败不留下活着的结算线程池。
   */
  private final int economyWorkerCount;

  /** 旧调用点（测试/夹具）兼容：并行度取缺省 {@link ShellConfig#DEFAULT_ECONOMY_WORKER_COUNT}（单线程退化路径）。 */
  public PopulationEconomyTimeParticipant(String mapId) {
    this(mapId, 1);
  }

  /** ★ R2 组合根入口：{@code economyWorkerCount} 必须 ≥ 1（1 = 单线程退化路径，≥ 2 才真的并行）。 */
  public PopulationEconomyTimeParticipant(String mapId, int economyWorkerCount) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    if (economyWorkerCount < 1) {
      throw new IllegalArgumentException(
          "economyWorkerCount 必须 ≥ 1（1 = 单线程退化路径，≥ 2 才真的并行）: " + economyWorkerCount);
    }
    this.economyWorkerCount = economyWorkerCount;
  }

  /** ★ R2：本参与者配置的经济结算 worker 数（只读；服务装配日志/诊断）。 */
  public int economyWorkerCount() {
    return economyWorkerCount;
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public WorldTimeProposal simulateWorld(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    EconomyData economy = economyOf(state);
    SocialData social = socialOf(state);
    // ★★ S1 阶段 4+5 Task 5：**第三片 actor** —— 产权落账只可能发生在同时看得见 economy 与 actor 的地方。
    ActorData actor = actorOf(state);

    LinkedHashSet<String> reads = new LinkedHashSet<>();
    LinkedHashSet<String> writes = new LinkedHashSet<>();
    for (IndustryId id : economy.industries().keySet()) {
      reads.add(economyAddress("industry", id.value()));
      writes.add(economyAddress("industry", id.value()));
    }
    // ★★ H0.2：class/flow 的地址局部名 = {@link CohortKey#toString()} 的**规范串**（{@code
    // 0_0|rural|poor_peasant}）。
    //   键里已经没有产业 ⇒ 旧版内联拼的 {@code <industryId>.<slotId>} 既拼不出来、也不该再拼（那是**第二处拼写点**）。
    //   ★ 与 {@code EconomyResolver} 的 class/flow 地址**必须逐字同串**：读写集的冲突检测全靠它。
    for (HouseholdId key : economy.classes().keySet()) {
      reads.add(economyAddress("class", key.toString()));
      writes.add(economyAddress("class", key.toString()));
    }
    for (HouseholdId key : economy.flows().keySet()) {
      writes.add(economyAddress("flow", key.toString()));
    }
    reads.add(economyAddressRoot());
    writes.add(economyAddressRoot());
    reads.add(socialAddressRoot());
    writes.add(socialAddressRoot());
    for (PeopleLotId lot : social.groups().keySet()) {
      reads.add(socialAddress("group", lot.value()));
      writes.add(socialAddress("group", lot.value()));
    }
    // ★ T5：第三片 actor —— 读写集是 {@code actor:<mapId>:goods.<key>}（形制照 ActorResolver）。
    reads.add(actorAddressRoot());
    if (state.module("map").isPresent()) {
      // ★ M2.3：区域拓扑读地图（城市/地形）—— 只读声明，避免与地图写者同轮冲突时静默。
      reads.add(mapAddressRoot());
    }
    writes.add(actorAddressRoot());
    for (GoodsAccountKey key : actor.accounts().keySet()) {
      reads.add(accountAddress(key));
      writes.add(accountAddress(key));
    }

    Optional<io.mosire.simos.util.time.SimosTimestamp> to = range.to();
    if (to.isEmpty() || economy.meta().isEmpty()) {
      // 无上界推进 / 经济未激活 ⇒ 三片都不动（但**交的是不变变更集，不是空提案**：契约原文）。
      return new WorldTimeProposal(
          NAMESPACE,
          Map.of(
              ECONOMY, EconomyChangeSet.between(economy, economy),
              SOCIAL, SocialChangeSet.between(social, social),
              ACTOR, ActorChangeSet.between(actor, actor)),
          reads,
          writes);
    }

    // ★★ S1：唯一账户会话 —— 家户（商品/货币/冻结）+ 经营者（商品/货币/冻结）一次装载；
    //   键 = (ActorRef, HexCoord)，家户 actor id 由 HouseholdId 唯一派生（不再从 CohortKey 拼）。
    //   ★ S1.5 旧档：先把旧三段 actor id 上的账搬到新身份键（移动，不是复制 —— 否则一笔粮变两本账）。
    ActorData migratedBooks = OwnershipBooks.migrateLegacyHouseholdAccounts(actor, economy);
    // ★ S1.5：搬迁后的新账户键也要进读写集（否则第二次推进的冲突检测看不见它们）。
    for (GoodsAccountKey key : migratedBooks.accounts().keySet()) {
      reads.add(accountAddress(key));
      writes.add(accountAddress(key));
    }
    AccountSession session = OwnershipBooks.loadAccountSession(economy, migratedBooks);
    // ★★ S3 缺陷修复：会话登记过的账户当天终值由 landAccountSession 的**绝对值**落回覆盖；
    //   这些账户上的 ledger 条目不能再对 actor 基准叠一遍（否则跨区到货等"只写会话"的当天流入会让付方被误判透支）。
    //   非会话账户（承运人、未播种经营者等）仍按 ledger 条目逐笔折入 actor 账。
    Set<AccountPartitionKey> sessionAccounts = new LinkedHashSet<>(session.accounts().keySet());
    // ★★ R2：并行度进构造器；workerCount == 1 时 EconomyParallelism.of 走单线程退化路径（不建池）。
    //   池的生命周期：finish()/close() 关闭；下面的 try/finally 保证日循环抛异常也不泄漏结算线程池。
    EconomyParallelism parallelism = EconomyParallelism.of(economyWorkerCount);
    try {
      EconomyDayStepper stepper =
          new EconomyDayStepper(
              economy,
              session,
              // ★ M2.3：区域拓扑由组合根从地图/城市现算（Map + SocialCity/City）；不得让 economy 反查 social。
              MarketTopologyBook.from(state),
              EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
              EconomySettlement.FAMINE_MORTALITY_PER_MILLE,
              parallelism);
      try {
        // ★ 装配行可读：实际 workerCount 与"是否真的并行"都在这里（1 = 单线程退化路径）。
        LOG.info(
            "人口—经济推进并行入口: workerCount={} parallelism={}",
            economyWorkerCount,
            stepper.parallelism());
        // ★★ S1.4 后置不变量（app 侧，唯一看得见 social 的地方）：
        //   · 旧档（meta.rulesVersion = pre-modern-v1）的迁移器只能按行人口反推近似份额 / 合成 lot ⇒ 第一次推进前按
        //     social 真实批次重建一次（片区总量对不上 ⇒ fail-closed）；
        //   · 新档 ⇒ 逐 lot 严格校验（不等当场抛，不静默均摊）。
        if (isLegacyMigration(economy)) {
          if (!MembershipWriteback.isConsistent(
              stepper.memberships(), stepper.classRows(), social)) {
            MembershipWriteback.rebuildLegacy(stepper.classRows(), stepper.memberships(), social);
          }
        } else {
          MembershipWriteback.requireConsistent(stepper.memberships(), stepper.classRows(), social);
        }
        ActorData currentBooks = migratedBooks;
        SocialData currentSocial = social;
        for (long day = range.from().tick() + 1L; day <= to.get().tick(); day++) {
          LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetBefore = unmetOf(stepper.flows());
          // ★★ T5：日循环里同一处落账 —— step 交回**当天**的账，条目逐日落到 actor 账本上（不重不漏）。
          //   ★★ M2 守恒收口：**市场成交（MARKET_TRADE）不折**（理由见 {@link OwnershipBooks#REASONS_NOT_FOLDED}）——
          //   市场双方都必须是本轮参与者：落在账户上的那一份已由下面的会话副本绝对值落回覆盖，在途那一份由
          //   ShipmentBatch 承载；再折一遍会在异地键上造幽灵账。
          ProductionLedger ledger = stepper.step(day);
          // ★★ M2.7：把"最近一轮市场报告"投递给读口（进程内、不落盘、只在同一 tick 内可信；见 MarketReportFeed 的类注）。
          MarketReportFeed.publish(mapId, stepper.lastMarketReport(), day);
          // ★★ S3：把"当日结账账本"投递给读口（租/工资欠款与逐规则欠额的唯一进程内来源；同款边界）。
          EconomyDayFeed.publish(mapId, Optional.of(ledger), day);
          List<ActorEntry> entries = OwnershipBooks.fold(ledger, OwnershipBooks.REASONS_NOT_FOLDED);
          if (!entries.isEmpty()) {
            currentBooks = OwnershipBooks.apply(currentBooks, entries, sessionAccounts);
            for (GoodsAccountKey key : currentBooks.accounts().keySet()) {
              writes.add(accountAddress(key));
            }
          }
          // ★★ H1：家户账**按绝对值**落回 actor 切片（不是"再叠加一遍条目"，见 OwnershipBooks 的类注）——
          //   日耗 / 投入 / 同格取材只写副本（它们不是产权条目），而关系实付既进条目、也已计进副本
          //   ⇒ 这一步是它们唯一共同的落点。★ 副本是**活的**（step 就地更新）⇒ 每天重新读访问器，不缓存引用。
          // ★★ S1：全部账户（家户 + 经营者；商品 + 货币 + 冻结）按会话绝对值一次落回。
          currentBooks = OwnershipBooks.landAccountSession(currentBooks, stepper.accounts());
          // ② 逐日生理压力（读**当天**的需求与实得 —— 两者都在刚结算完的账上）。
          currentSocial =
              applyDailyStress(stepper.classRows(), currentSocial, stepper.flows(), unmetBefore);
          // ③ 月度结算：出生/死亡 → 先改人口（真值源），再按同一份账回写经济侧。
          if (day % PopulationDynamics.SETTLEMENT_DAYS == 0L) {
            PopulationDynamics.Outcome outcome =
                PopulationDynamics.monthly(currentSocial, day, CalendarClock.julianDefault());
            currentSocial = outcome.data();
            if (!outcome.isEmpty()) {
              stepper.applyPopulationChange(outcome.changeList());
              // ★★ S1.4.1 的跨切片收口：出生落在**新的 born lot**（社会侧），存量 lot 只减死亡 ⇒ 在这里按行人口权重
              //   为新批次补建份额，然后逐 lot 硬校验。顺序必须在 landAccountSession 之前（份额不进账户，但它与行人口
              //   同属 economy 工作副本，先收口再构造终态）。
              MembershipWriteback.reconcile(
                  stepper.classRows(), stepper.memberships(), currentSocial);
              // ★ 月末**重新对齐副本**（照 flows 的既有先例：那份实现会带出自己的流水副本 ⇒ 累加器要重新读一遍）。
              //   ★ 放在月度回写之后、且**在条目落账之后**：全部账户以会话的绝对值收尾（顺序反了会把条目加两遍）。
              currentBooks = OwnershipBooks.landAccountSession(currentBooks, stepper.accounts());
            }
            // ★★ P8 迁移接线位（**默认 no-op**）：`planMigrations` 目前传空读数表 ⇒ 规划器恒返回空表 ⇒
            //   不改任何状态、不动任何数值。P9 启用时的顺序必须在这里（月度人口回写 + 份额对账之后、
            //   `landAccountSession`/`stepper.finish()` 之前）：
            //     ① 读数：从 MarketReportFeed / CityLandBook / social.urbanPopulationAt 现算
            //        List<CityMigrationReading>（本参与者已在 reads 里声明 map/social 根地址）；
            //     ② social 侧：按 planned 拆/合 PopulationGroup（换 residence，id 不变），得到新的 SocialData；
            //     ③ 经济侧：stepper.applyMigrations(planned, day)（行人口/劳动/债务；唯一写口见 LotMigrationBook，
            //        它有意不碰 Membership —— 份额由下一步的 reconcile 按行人口重建/削平）；
            //     ④ 对账：MembershipWriteback.reconcile(stepper.classRows(), stepper.memberships(), 新
            // SocialData)
            //        逐 lot 硬校验；⑤ 再落 actor 账户（若迁移不碰账户可省略，但顺序必须早于 finish()）。
            List<LotMigration> plannedMigrations = planMigrations(currentSocial, day);
            if (!plannedMigrations.isEmpty()) {
              throw new IllegalStateException(
                  "P8 迁移执行尚未接线：规划器返回了 "
                      + plannedMigrations.size()
                      + " 笔迁移，但 social/economy 双写与对账顺序未实现（默认空读数表 ⇒ 正常不会到达这里）");
            }
          }
        }
        EconomyData currentEconomy = stepper.finish();
        return new WorldTimeProposal(
            NAMESPACE,
            Map.of(
                ECONOMY, EconomyChangeSet.between(economy, currentEconomy),
                SOCIAL, SocialChangeSet.between(social, currentSocial),
                ACTOR, ActorChangeSet.between(actor, currentBooks)),
            reads,
            writes);
      } finally {
        stepper.close();
      }
    } catch (RuntimeException | Error failure) {
      // stepper 构造失败 ⇒ 池从未交给 finish()/close()；这里补关一次（已关时幂等）。
      parallelism.close();
      throw failure;
    }
  }

  // ── P8 迁移规划接线位（默认 no-op）──────────────────────────────────────────────────────────

  /**
   * ★★ <b>P8 人口迁移规划器调用位（默认 no-op）</b>：当前显式传空读数表 ⇒ {@link PopulationMigrationPlanner#plan} 恒返回
   * {@code List.of()}，调用点 {@code if (!plannedMigrations.isEmpty())} 不进入 ⇒ <b>不改任何状态、不改任何数值</b>。
   *
   * <p>★★ <b>为什么必须住这里</b>：迁移计划同时看得见 social（批次）与 economy（城市/市场读数），而只有组合根同时认识 两片；plan 本身仍是纯函数（见
   * {@link PopulationMigrationPlanner}）。
   *
   * <p>★★ <b>P9 接线点（本方法就是那个唯一入口）</b>：把空表替换成按城现算的 {@link CityMigrationReading} 列表—— 数据源 = {@link
   * MarketReportFeed}（本轮交易/利润读数）、P6 {@code CityLandBook}（承载/拥挤）与 {@code
   * SocialData.urbanPopulationAt(cityId)}（人口分母）；然后把调用点改造成上面的 ①②③④⑤ 顺序。
   */
  private static List<LotMigration> planMigrations(SocialData social, long day) {
    // ★ 默认空读数表：不是“还没写”，是 P8 明确只做契约/纯规划器/接线点；P9 统一接读数与测试。
    return PopulationMigrationPlanner.plan(social, List.of(), MigrationPolicy.defaults(), day);
  }

  // ── 逐日生理压力（社会侧唯一的日常写点）────────────────────────────────────────────────

  /**
   * ★★ **把当天的生活资料满足情况折成每个批次的压力**（spec §七："短期缺粮加一些、恢复供给后逐渐消退"）。
   *
   * <pre>
   * 逐家户：粮/布的"当日需求"  = Σ该家户各行 {@code naturalNeeds[商品]}（结算当天写回的那一份 ⇒ 与结算同源）
   *          粮/布的"当日实得"  = 需求 − 当日新记进 {@code FlowRow.unmetNeed[商品]} 的那一笔
   * 逐批次：取**它住的那一格、它那一种居住类型**的家户（四行求和）⇒ 满足率‰ ⇒ {@link PopulationDynamics#stressAfter}
   * </pre>
   *
   * <p>★★ **H0.2 起批次 ↔ 家户的对应不再经产业**：批次身上有<b>落点格</b>（{@code group.residence()}）与 <b>居住类型</b>（批次 id
   * 的前缀 ⇒ {@link ResidenceKind#ofLot}，唯一拼写点），而家户行的键正是 {@code (格, 居住类型, 阶层)}（{@code CohortKey}） ⇒
   * 两维直接对上，**不需要中间映射表**。旧版要经"批次供给哪些产业"（{@code LaborAllocation}）再回退到"该格的产业"，
   * 那一步在"一格既有农村又有城镇"时会把两池并起来算 —— 正是 R-N1 要堵的"农村余粮喂城市缺口"。
   *
   * <p>★ **没有配额的批次照样吃饭**（0-14 档与全部新生儿）：它们的居住类型与落点格本来就在批次上 ⇒ 这条兜底现在是**结构上白拿的**（旧版要为它单独查一次"该格的产业"）。 ★
   * **没有需求的批次不动**（{@code 需求 == 0} ⇒ 满足率按 1000‰ 计，压力照常消退）："这一天没记账"不等于"饿了一天"。
   *
   * @param unmetBefore 当日结算**之前**的 {@code FlowRow.unmetNeed} 快照（用于取"当天新增的那一笔"）
   */
  private static SocialData applyDailyStress(
      Map<HouseholdId, ClassRow> classes,
      SocialData social,
      Map<HouseholdId, FlowRow> flows,
      Map<HouseholdId, Map<CommodityId, Long>> unmetBefore) {
    if (social.groups().isEmpty() || classes.isEmpty()) {
      return social; // 没有批次/没有经济 ⇒ 没有可算的人
    }
    Map<HouseholdRef, long[]> byHousehold = dailyProvisioning(classes, flows, unmetBefore);
    Map<PeopleLotId, PopulationGroup> next = new LinkedHashMap<>(social.groups());
    for (PopulationGroup group : social.groups().values()) {
      // ★ 批次 → 家户：**落点格 + 居住类型**（前缀的唯一判定在 {@link ResidenceKind#ofLot}）。
      long[] row =
          byHousehold.get(new HouseholdRef(group.residence(), ResidenceKind.ofLot(group.id())));
      if (row == null) {
        continue; // 该格没有这一组家户（世界还没播种到这里，或该池在这格没有人）⇒ 没有可算的满足率
      }
      long stress =
          PopulationDynamics.stressAfter(
              group.physiologicalStress(),
              satisfactionPerMille(row[1], row[0]),
              satisfactionPerMille(row[3], row[2]));
      if (stress != group.physiologicalStress()) {
        next.put(group.id(), group.withPhysiologicalStress(stress));
      }
    }
    return social.withGroups(next);
  }

  /** 一格 + 一种居住类型 = **一组家户**（该格那一组的四行合并读；H0.2 的对接口径）。 */
  private record HouseholdRef(HexCoord hex, ResidenceKind residence) {}

  /** 逐家户组的当日 {@code [粮需求, 粮实得, 布需求, 布实得]}（毫单位）—— 该格该居住类型的**四行求和**。 */
  private static Map<HouseholdRef, long[]> dailyProvisioning(
      Map<HouseholdId, ClassRow> classes,
      Map<HouseholdId, FlowRow> flows,
      Map<HouseholdId, Map<CommodityId, Long>> unmetBefore) {
    Map<HouseholdRef, long[]> byHousehold = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : classes.entrySet()) {
      HouseholdId key = entry.getKey();
      ClassRow classRow = entry.getValue();
      long[] row =
          byHousehold.computeIfAbsent(
              new HouseholdRef(classRow.view().hex(), classRow.view().residence()),
              ignored -> new long[4]);
      long grainNeed = entry.getValue().naturalNeeds().getOrDefault(EconomySettlement.GRAIN, 0L);
      long clothNeed = entry.getValue().naturalNeeds().getOrDefault(EconomySettlement.CLOTH, 0L);
      row[0] += grainNeed;
      row[2] += clothNeed;
      row[1] += grainNeed - dayUnmet(flows, unmetBefore, key, EconomySettlement.GRAIN);
      row[3] += clothNeed - dayUnmet(flows, unmetBefore, key, EconomySettlement.CLOTH);
    }
    return byHousehold;
  }

  /** 某行某商品**当天新增**的未满足需求（= 结算后 − 结算前）。 */
  private static long dayUnmet(
      Map<HouseholdId, FlowRow> flows,
      Map<HouseholdId, Map<CommodityId, Long>> unmetBefore,
      HouseholdId key,
      CommodityId commodity) {
    FlowRow after = flows.get(key);
    long now = after == null ? 0L : after.unmetNeed().getOrDefault(commodity, 0L);
    Map<CommodityId, Long> before = unmetBefore.get(key);
    long was = before == null ? 0L : before.getOrDefault(commodity, 0L);
    return Math.max(0L, now - was);
  }

  /** 满足率（‰）：{@code 需求 == 0 ⇒ 1000}（"这一天没记账"不等于"饿了一天"）；否则 {@code 实得 × 1000 ÷ 需求}，封顶 1000。 */
  static long satisfactionPerMille(long got, long need) {
    if (need <= 0L) {
      return 1000L;
    }
    return Math.min(1000L, Math.max(0L, got) * 1000L / need);
  }

  /** 各行的 {@code unmetNeed} 快照（当日结算前）——只读一份，供"当天新增"的差分用。 */
  private static LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetOf(
      Map<HouseholdId, FlowRow> flows) {
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, FlowRow> entry : flows.entrySet()) {
      copy.put(entry.getKey(), entry.getValue().unmetNeed());
    }
    return copy;
  }

  // ── 切片读取与地址 ────────────────────────────────────────────────────────────────────

  /** ★ 是否旧档迁移态（只有旧档需要按 social 重建份额；新档逐 lot 严格校验）。 */
  private static boolean isLegacyMigration(EconomyData economy) {
    return economy
        .meta()
        .map(meta -> EconomyMeta.RULES_VERSION_PRE_MODERN_V1.equals(meta.rulesVersion()))
        .orElse(false);
  }

  private static EconomyData economyOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ECONOMY)
            .orElseThrow(() -> new IllegalStateException("state 里没有 economy 切片（装配故障：日推进要求切片在场）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  private static SocialData socialOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(SOCIAL)
            .orElseThrow(() -> new IllegalStateException("state 里没有 social 切片（装配故障：日推进要求切片在场）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "state 的 social 切片不是 SocialSnapshot: " + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  private String economyAddressRoot() {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId))).canonical();
  }

  private String socialAddressRoot() {
    return new Address(List.of(new Namespace(SOCIAL), Entity.of(mapId))).canonical();
  }

  private String economyAddress(String kind, String localId) {
    return new Address(List.of(new Namespace(ECONOMY), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }

  private String socialAddress(String kind, String localId) {
    return new Address(List.of(new Namespace(SOCIAL), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }

  private String actorAddressRoot() {
    return new Address(List.of(new Namespace(ACTOR), Entity.of(mapId))).canonical();
  }

  private String mapAddressRoot() {
    return new Address(List.of(new Namespace("map"), Entity.of(mapId))).canonical();
  }

  /**
   * 一本产权账的地址：{@code actor:<mapId>:goods.<key>} —— ★ 形制照 {@code ActorResolver}（它的第三段 kind 就是 {@code
   * goods}）。{@code <key>} 是 {@link GoodsAccountKey#toString()} 的产物，<b>本类不复述那个格式</b>。
   */
  private String accountAddress(GoodsAccountKey key) {
    return new Address(
            List.of(new Namespace(ACTOR), Entity.of(mapId), Entity.of("goods", key.toString())))
        .canonical();
  }

  /** actor 切片只能从 actor 模块拿（铁律 3/4）；缺席或类型不对都是装配故障（同 {@link #economyOf} 的口径）。 */
  private static ActorData actorOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(ACTOR)
            .orElseThrow(() -> new IllegalStateException("state 里没有 actor 切片（装配故障：产权落账口要求切片在场）"));
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalStateException(
          "state 的 actor 切片不是 ActorSnapshot: " + snapshot.getClass().getName());
    }
    return actorSnapshot.data();
  }
}
