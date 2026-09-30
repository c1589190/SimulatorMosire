package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.AssetKind;
import io.mosire.simos.economy.classfirst.ClassFirstSettlement;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.ClassPool;
import io.mosire.simos.economy.classfirst.HouseholdProductionAccount;
import io.mosire.simos.economy.classfirst.PilotConfig;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationDynamics;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.ArrayList;
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
 * ★★ <b>R2c：class-first 生产路径上唯一的人口—经济时间参与者</b>（namespace 仍是 {@code "population"}）。
 *
 * <p>它取代旧 {@link PopulationEconomyTimeParticipant} 在 {@code Shell} 里的注册位：<b>只调</b> {@link
 * ClassFirstSettlement#settleOneDay(ClassFirstState, ClassFirstSettlement.Inputs)}，<b>不</b>构造
 * {@code EconomyDayStepper}、<b>不</b>调 {@code EconomySettlement}（旧两个类仍可编译，但已无生产注册点，R3 删）。
 *
 * <p>★★ <b>每次 {@code simulateWorld} = 一个 proposal、一条 revision、内部逐日</b>：
 *
 * <pre>
 * ① 由 economy.classFirst + actor 家户账 + social 人口构造 settleOneDay 的输入（非空态里 households/config 只作契约形状，
 *    引擎以状态内 config 为权威）；
 * ② settleOneDay(base, inputs) → 新的 ClassFirstState（池 + 家户生产账户人口/劳动）+ AccountDelta + PopulationDelta + 日审计；
 * ③ AccountDelta 经 {@link ClassFirstActorWriteback} 折进 actor 账本（池级 → 家户级唯一一次展开；土地/农具无 actor 维度 ⇒ 具名 gap）；
 * ④ 家户人口差分经 {@link ClassFirstSocialWriteback} 同步 social 批次（具名 gap）+ 用当日口粮/布读数更新生理压力；
 * ⑤ 每 30 天调 {@code PopulationDynamics.monthly}，出生/死亡经 {@link ClassFirstPopulationWriteback} 接回
 *    classfirst 池/家户账户（in-place 同步 social 的批次人数）；
 * ⑥ classes 只读投影经 {@link ClassFirstClassProjection} 与家户账户对齐（键集/地址不变）；
 * ⑦ 循环结束把最终 ClassFirstState 经 {@link EconomyChangeSet#between}、social/actor 经各自变更集交回（全部新对象，不改运行状态）。
 * </pre>
 *
 * <p>★★ <b>R2c 的世界级聚合</b>：三国的 {@code economy.Seed} 由 {@code ClassFirstState.merge} 把同键池/放贷账户按加法合并成
 * <b>世界级 4 池</b>（不先做 region 维，区域地理后置）；本参与者因此一次推进整个世界，池内的阶层迁移/借贷/消费在世界范围内发生。
 *
 * <p>★★ <b>本轮如实边界</b>（计划允许，但必须点名）：
 *
 * <ul>
 *   <li><b>人口回写按 {@code (格, 居住类型)} 组聚合</b>：classfirst 家户没有年龄/性别维，出生/死亡先按组求和、再按家户人口权重最大余数法摊
 *       （近似在"组内怎么分"，见 {@link ClassFirstPopulationWriteback}）；逐日生理压力仍是聚合满足率口径（见 {@link
 *       ClassFirstSocialWriteback} 类注）；
 *   <li><b>actor 账的土地/农具维度不存在</b>：{@code ownedLand}/{@code tools} 的 AccountDelta 只落 classfirst
 *       池，落账时记具名 gap 并写日志；
 *   <li><b>economy.classes 只是投影</b>：classfirst 池/家户生产账户才是人口/资产真源；{@link ClassRow} 的
 *       population/labor/money 每次推进后从家户账户重算（只读，不再被任何生产路径当权威）。
 * </ul>
 *
 * <p>★ <b>未激活/无上界</b>：{@code range.to} 缺省、或 {@code economy.classFirst} 为空（含未播种与旧 legacy 世界）⇒
 * 三片都交<b>不变</b>变更集（不是空提案），且 <b>绝不回退</b>旧结算；后者写一条 WARN 具名说明。
 */
public final class ClassFirstPopulationEconomyTimeParticipant implements TimeParticipant {

  private static final Logger LOG =
      LoggerFactory.getLogger(ClassFirstPopulationEconomyTimeParticipant.class);

  /** 参与者身份（**不是模块名**：它同时写 {@code economy} / {@code social} / {@code actor} 三片）。 */
  public static final String NAMESPACE = "population";

  private static final String ECONOMY = "economy";
  private static final String SOCIAL = "social";
  private static final String ACTOR = "actor";

  private final String mapId;

  public ClassFirstPopulationEconomyTimeParticipant(String mapId) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
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
    ActorData actor = actorOf(state);

    LinkedHashSet<String> reads = new LinkedHashSet<>();
    LinkedHashSet<String> writes = new LinkedHashSet<>();
    reads.add(economyAddressRoot());
    writes.add(economyAddressRoot());
    for (HouseholdId key : economy.classes().keySet()) {
      reads.add(economyAddress("class", key.toString()));
      writes.add(economyAddress("class", key.toString()));
    }
    reads.add(socialAddressRoot());
    writes.add(socialAddressRoot());
    for (PeopleLotId lot : social.groups().keySet()) {
      reads.add(socialAddress("group", lot.value()));
      writes.add(socialAddress("group", lot.value()));
    }
    reads.add(actorAddressRoot());
    writes.add(actorAddressRoot());
    for (GoodsAccountKey key : actor.accounts().keySet()) {
      reads.add(accountAddress(key));
      writes.add(accountAddress(key));
    }

    Optional<SimosTimestamp> to = range.to();
    ClassFirstState classFirst = economy.classFirst();
    if (to.isEmpty() || classFirst.isEmpty()) {
      if (to.isPresent()) {
        LOG.warn(
            "classFirst 未播种（economy.classFirst 为空）⇒ population 参与者交不变变更集，不回退旧结算：mapId={}", mapId);
      }
      return unchanged(economy, social, actor, reads, writes);
    }

    PilotConfig config = classFirst.meta().config();
    if (config == null) {
      throw new IllegalStateException("classFirst 非空但 meta.config 为空（状态损坏）：mapId=" + mapId);
    }

    ClassFirstState current = classFirst;
    // ★★ R2c：旧 R2b 档可能带"旧档迁移器补出的合成 memberships"（class-first 不读它，但会让 classes 投影的
    //    S1 守恒守卫误红）⇒ 推进前先剥掉这一旧口径影子（classFirst 是权威，memberships 不是）。
    EconomyData currentEconomy =
        economy.memberships().isEmpty() ? economy : economy.withMemberships(Map.of());
    ActorData currentActor = actor;
    SocialData currentSocial = social;
    LinkedHashSet<String> unmappedActorDimensions = new LinkedHashSet<>();
    boolean socialGapsLogged = false;
    for (long day = range.from().tick() + 1L; day <= to.get().tick(); day++) {
      ClassFirstSettlement.Inputs inputs = inputsFor(current, currentEconomy, currentActor, day);
      ClassFirstSettlement.Result result = ClassFirstSettlement.settleOneDay(current, inputs);
      ClassFirstActorWriteback.Applied applied =
          ClassFirstActorWriteback.apply(currentActor, currentEconomy, current, result);
      currentActor = applied.data();
      unmappedActorDimensions.addAll(applied.unmappedDimensions());
      ClassFirstSocialWriteback.AppliedSocial socialApplied =
          ClassFirstSocialWriteback.apply(currentSocial, currentEconomy, current, result);
      currentSocial = socialApplied.data();
      if (!socialApplied.gaps().isEmpty() && !socialGapsLogged) {
        socialGapsLogged = true;
        LOG.warn(
            "classFirst → social 落账有具名缺口（本轮只报一次）：mapId={} gaps={}", mapId, socialApplied.gaps());
      }
      current = result.state();

      // ★★ R2c：每 30 天（与旧协调器同一窗口）结算出生/死亡，并把 LotChange 接回 classfirst 池/家户账户。
      //   次序与旧协调器一致：先跑完这一天的经济结算与两条写回，再做月度人口学；出生/死亡不产生商品/货币/土地/债务条目。
      if (day % PopulationDynamics.SETTLEMENT_DAYS == 0L) {
        PopulationDynamics.Outcome outcome = PopulationDynamics.monthly(currentSocial, day);
        currentSocial = outcome.data();
        if (!outcome.isEmpty()) {
          ClassFirstPopulationWriteback.Applied populationApplied =
              ClassFirstPopulationWriteback.apply(current, currentEconomy, outcome.changeList());
          current = populationApplied.state();
          LOG.info(
              "classFirst 月度人口学接回：mapId={} day={} births={} deaths={} net={} classFirstPopulation={} socialPopulation={}",
              mapId,
              day,
              populationApplied.births(),
              populationApplied.deaths(),
              populationApplied.births() - populationApplied.deaths(),
              classFirstPopulation(current),
              socialPopulation(currentSocial));
        }
      }

      // ★★ R2c：每次推进后把只读 classes 投影刷新到与 classfirst 家户账户一致（键集/地址不变）。
      currentEconomy = ClassFirstClassProjection.project(currentEconomy, current, currentActor);
    }
    if (!unmappedActorDimensions.isEmpty()) {
      LOG.info(
          "classFirst → actor 落账：以下资产维度在 actor 账本无对应维度，只落 classFirst 池（具名 gap）：mapId={} dimensions={}",
          mapId,
          unmappedActorDimensions);
    }

    EconomyData currentEconomyFinal = currentEconomy.withClassFirst(current);
    return new WorldTimeProposal(
        NAMESPACE,
        Map.of(
            ECONOMY, EconomyChangeSet.between(economy, currentEconomyFinal),
            SOCIAL, SocialChangeSet.between(social, currentSocial),
            ACTOR, ActorChangeSet.between(actor, currentActor)),
        reads,
        writes);
  }

  private static long classFirstPopulation(ClassFirstState state) {
    long total = 0L;
    for (ClassPool pool : state.classPools().values()) {
      total += pool.population();
    }
    return total;
  }

  private static long socialPopulation(SocialData social) {
    long total = 0L;
    for (var group : social.groups().values()) {
      total += group.count();
    }
    return total;
  }

  // ── 输入构造（经济状态 + actor 家户账 + social 人口）──────────────────────────────────

  /**
   * ★ 构造 {@link ClassFirstSettlement.Inputs}：家户 = classfirst 家户生产账户（人口/劳动/份额）+ actor 家户账（粮/布/货币），放贷
   * = classFirst.lenders。
   *
   * <p>★ <b>非空态里 {@code households}/{@code config} 被 {@code settleOneDay} 忽略</b>（它以状态内 config 为权威）⇒
   * 这里构造是<b>契约形状</b>，人口为 0 的家户被跳过（{@link PilotModel.Household} 的守卫要求 ≥ 1）—— 权威的人口/劳动仍在 {@code
   * ClassFirstState.householdAccounts} 里逐日推进。social 批次不重复进本输入（旧口径的映射在 {@link
   * ClassFirstSocialWriteback} 里单独做）。
   */
  private static ClassFirstSettlement.Inputs inputsFor(
      ClassFirstState state, EconomyData economy, ActorData actor, long day) {
    List<PilotModel.Household> households = new ArrayList<>();
    for (Map.Entry<ClassPoolId, ClassPool> poolEntry : state.classPools().entrySet()) {
      ClassPool pool = poolEntry.getValue();
      List<HouseholdProductionAccount> members = new ArrayList<>();
      for (HouseholdProductionAccount account : state.householdAccounts().values()) {
        if (poolEntry.getKey().equals(account.poolId())) {
          members.add(account);
        }
      }
      if (members.isEmpty()) {
        continue;
      }
      long[] weights = new long[members.size()];
      for (int i = 0; i < members.size(); i++) {
        weights[i] = members.get(i).population();
      }
      long[] landShares =
          ClassFirstDistribution.largestRemainder(pool.stock(AssetKind.OWNED_LAND), weights);
      long[] toolShares =
          ClassFirstDistribution.largestRemainder(pool.stock(AssetKind.TOOLS), weights);
      for (int i = 0; i < members.size(); i++) {
        HouseholdProductionAccount member = members.get(i);
        if (member.population() < 1L) {
          continue; // PilotModel.Household 的守卫；这些家户的人口仍由 classFirstState 权威承载
        }
        HouseholdId householdId = HouseholdId.parse(member.householdId());
        ClassRow row = economy.classes().get(householdId);
        Map<String, Long> goods = new LinkedHashMap<>();
        long money = 0L;
        if (row != null) {
          GoodsAccount book =
              actor
                  .accounts()
                  .get(new GoodsAccountKey(HouseholdActors.of(householdId), row.view().hex()));
          if (book != null) {
            long grain = book.balances().getOrDefault(new CommodityId(PilotModel.GRAIN), 0L);
            long cloth = book.balances().getOrDefault(new CommodityId(PilotModel.CLOTH), 0L);
            if (grain > 0L) {
              goods.put(PilotModel.GRAIN, grain);
            }
            if (cloth > 0L) {
              goods.put(PilotModel.CLOTH, cloth);
            }
            for (long value : book.money().values()) {
              money = Math.addExact(money, value);
            }
          }
        }
        households.add(
            new PilotModel.Household(
                member.householdId(),
                member.name(),
                pool.classPositionId(),
                member.population(),
                member.laborPerCapita(),
                goods,
                money,
                landShares[i],
                toolShares[i],
                member.participationSharePerMille()));
      }
    }
    return new ClassFirstSettlement.Inputs(
        day, null, households, new ArrayList<>(state.lenders().values()));
  }

  // ── 未激活：不变变更集 ──────────────────────────────────────────────────────────────

  private static WorldTimeProposal unchanged(
      EconomyData economy,
      SocialData social,
      ActorData actor,
      Set<String> reads,
      Set<String> writes) {
    return new WorldTimeProposal(
        NAMESPACE,
        Map.of(
            ECONOMY, EconomyChangeSet.between(economy, economy),
            SOCIAL, SocialChangeSet.between(social, social),
            ACTOR, ActorChangeSet.between(actor, actor)),
        reads,
        writes);
  }

  // ── 切片读取与地址 ────────────────────────────────────────────────────────────────────

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

  /**
   * 一本产权账的地址：{@code actor:<mapId>:goods.<key>} —— ★ 形制照 {@code ActorResolver}（它的第三段 kind 就是 {@code
   * goods}）。{@code <key>} 是 {@link GoodsAccountKey#toString()} 的产物，<b>本类不复述那个格式</b>。
   */
  private String accountAddress(GoodsAccountKey key) {
    return new Address(
            List.of(new Namespace(ACTOR), Entity.of(mapId), Entity.of("goods", key.toString())))
        .canonical();
  }
}
