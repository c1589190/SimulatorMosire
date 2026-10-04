package io.mosire.simos.social;

import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.population.AgeBracketView;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationHeadline;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.PopulationSource;
import io.mosire.simos.social.population.UrbanRural;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 社会状态（M3 spec §3.1 + 城市节点 + 第三阶段设计稿 §三的**人口实体** + 2026-10-09 家户架构 §4）。
 *
 * <p>★★ <b>2026-10-09 家户架构：位置的真值源是 {@code households}</b>。{@code PopulationGroup} 已删除
 * {@code residence}；"某批次在哪一格/哪个 unit"只能从 {@link #locationOfLot(PeopleLotId)} /
 * {@link #householdOfLot(PeopleLotId)} 得到。hex/unit 人口由家户成员<b>现算</b>（{@link #populationAt(HexCoord)} /
 * {@link #unitPopulation(String)}），不落盘。
 *
 * <p>★★ <b>跨组件不变量（构造期判，坏数据当场抛）</b>：
 *
 * <ul>
 *   <li>{@code households} 的键 == 行 id（防"键与本体两个身份"）；
 *   <li>{@code household.memberLots} 里每个批次都必须存在于 {@code groups}；
 *   <li><b>每个 {@code PopulationGroup} 必须且只能被一个家户的 memberLots 引用</b> —— 无主批次与双主批次都被拒
 *       （"家户在哪"因此是唯一的位置账）；
 *   <li>位置冲突：同一批次被两个家户引用由上一行覆盖（同一个 {@code PeopleLotId} 不可能有两个位置）。
 * </ul>
 *
 * <p>★★ <b>旧字段 {@code populations}/{@code cities} 保留</b>（旧世界不迁移、新主路径不再读它们当位置真值）：
 * {@code populations} 仍是"该格农村序列"的旧账，{@code cities} 仍是城市节点表；新主路径的"有多少人"走家户汇总。
 *
 * <p>★ {@code populationEvents} 以 {@code event.id()} 为键（架构 §4.3：事件进持久表、可回放；重复 id 由
 * {@code HouseholdBook.applyEvent} 拒绝）。
 *
 * <p>★ <b>五个 map 都保序不可变</b>：{@code LinkedHashMap} + {@code unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——它的迭代序不是内容的纯函数（M2 实测），字节级往返因此不成立。
 */
public record SocialData(
    Map<HexCoord, PopulationSeries> populations,
    Map<CityId, SocialCity> cities,
    Map<PeopleLotId, PopulationGroup> groups,
    Map<HouseholdId, Household> households,
    Map<String, HouseholdPopulationEvent> populationEvents) {

  public SocialData {
    if (populations == null) {
      throw new IllegalArgumentException("populations 不得为 null");
    }
    // ★ **老档兼容**（旧字节没有这个键，如 `worlds/v17levant.json` 与升级前落盘的每条 social revision）：
    //   缺省 = 空表，**此处不抛** —— 抛了等于"整个世界打不开"（先例：SdInfoEntry 的 affiliations/adjudicationStatus）。
    //   方向是 fail-closed：旧档里没有城市，读回来就是没有城市。
    if (cities == null) {
      cities = Map.of();
    }
    // ★ **R1 唯一保留的那一行兼容**：缺省 = 空表。
    //   方向同样是 fail-closed：旧档里没有批次，读回来就是没有批次。
    if (groups == null) {
      groups = Map.of();
    }
    // ★ S2：homeholds / populationEvents 同样 null ⇒ 空表（旧快照没有这两个键；见 SocialChangeSet 的同款兼容）。
    if (households == null) {
      households = Map.of();
    }
    if (populationEvents == null) {
      populationEvents = Map.of();
    }
    Map<HexCoord, PopulationSeries> populationsCopy = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, PopulationSeries> entry : populations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("populations 的键与值都不得为 null");
      }
      populationsCopy.put(entry.getKey(), entry.getValue());
    }
    populations = Collections.unmodifiableMap(populationsCopy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
    Map<CityId, SocialCity> citiesCopy = new LinkedHashMap<>();
    for (Map.Entry<CityId, SocialCity> entry : cities.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("cities 的键与值都不得为 null");
      }
      citiesCopy.put(entry.getKey(), entry.getValue());
    }
    cities = Collections.unmodifiableMap(citiesCopy);
    Map<PeopleLotId, PopulationGroup> groupsCopy = new LinkedHashMap<>();
    for (Map.Entry<PeopleLotId, PopulationGroup> entry : groups.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("groups 的键与值都不得为 null");
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "groups 的键必须等于批次 id: key=" + entry.getKey() + " id=" + entry.getValue().id());
      }
      groupsCopy.put(entry.getKey(), entry.getValue());
    }
    groups = Collections.unmodifiableMap(groupsCopy);
    Map<HouseholdId, Household> householdsCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Household> entry : households.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("households 的键与值都不得为 null");
      }
      Household household = entry.getValue();
      if (!entry.getKey().equals(household.id())) {
        throw new IllegalArgumentException(
            "households 的键必须等于家户 id: key=" + entry.getKey() + " id=" + household.id());
      }
      for (PeopleLotId lot : household.memberLots()) {
        if (!groupsCopy.containsKey(lot)) {
          throw new IllegalArgumentException(
              "家户 " + household.id() + " 的成员批次不在 groups 里: " + lot);
        }
      }
      householdsCopy.put(entry.getKey(), household);
    }
    // ★★ 每个批次必须且只能被一个家户引用（架构 §4.1 的"memberLots 是唯一成员关系"）：
    //   少一个 ⇒ 那批人没有位置（读口看不见、算不进任何 hex/unit）；多一个 ⇒ 同一个批次有两个位置。
    Map<PeopleLotId, HouseholdId> ownerByLot = new LinkedHashMap<>();
    for (Household household : householdsCopy.values()) {
      for (PeopleLotId lot : household.memberLots()) {
        HouseholdId previous = ownerByLot.put(lot, household.id());
        if (previous != null) {
          throw new IllegalArgumentException(
              "批次 " + lot + " 同时被家户 " + previous + " 与 " + household.id() + " 引用（每个批次只能有一个家户）");
        }
      }
    }
    for (PeopleLotId lot : groupsCopy.keySet()) {
      if (!ownerByLot.containsKey(lot)) {
        throw new IllegalArgumentException(
            "批次 " + lot + " 没有被任何家户引用（位置只能由家户给出；拒绝无主批次）");
      }
    }
    households = Collections.unmodifiableMap(householdsCopy);
    Map<String, HouseholdPopulationEvent> eventsCopy = new LinkedHashMap<>();
    for (Map.Entry<String, HouseholdPopulationEvent> entry : populationEvents.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("populationEvents 的键与值都不得为 null");
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "populationEvents 的键必须等于事件 id: key=" + entry.getKey() + " id=" + entry.getValue().id());
      }
      eventsCopy.put(entry.getKey(), entry.getValue());
    }
    populationEvents = Collections.unmodifiableMap(eventsCopy);
  }

  /**
   * ★ 旧 3 参构造的便捷形态（{@code households}/{@code populationEvents} 为空）：只服务"只有旧账"的装配点
   * （如 {@code CorridorWorld}）。★ 只要 {@code groups} 非空就会在构造期被跨组件校验拒——新世界必须同时给家户。
   */
  public SocialData(
      Map<HexCoord, PopulationSeries> populations,
      Map<CityId, SocialCity> cities,
      Map<PeopleLotId, PopulationGroup> groups) {
    this(populations, cities, groups, Map.of(), Map.of());
  }

  /** 往返用例的起点。 */
  public static SocialData empty() {
    return new SocialData(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  /** 一个组件一个 with（照 M2 的形制）。 */
  public SocialData withPopulations(Map<HexCoord, PopulationSeries> value) {
    return new SocialData(value, cities, groups, households, populationEvents);
  }

  /** 一个组件一个 with（照 M2 的形制）。 */
  public SocialData withCities(Map<CityId, SocialCity> value) {
    return new SocialData(populations, value, groups, households, populationEvents);
  }

  /**
   * 只换批次表的形态：**成员关系一字不动** ⇒ 批次人数/压力可以在原位更新（月度结算、逐日压力）。
   * ★ 新增批次（新生批次）不能用它——那会造出无主批次；请用 {@link #withGroupsAndHouseholds}。
   */
  public SocialData withGroups(Map<PeopleLotId, PopulationGroup> value) {
    return new SocialData(populations, cities, value, households, populationEvents);
  }

  /** 只换家户表（成员关系变了就要求 {@code groups} 同步：见 {@link #withGroupsAndHouseholds}）。 */
  public SocialData withHouseholds(Map<HouseholdId, Household> value) {
    return new SocialData(populations, cities, groups, value, populationEvents);
  }

  /** 批次与家户**一起**换（新增/删除成员、跨家户转移的唯一安全写口：中间态不经过构造期校验）。 */
  public SocialData withGroupsAndHouseholds(
      Map<PeopleLotId, PopulationGroup> newGroups, Map<HouseholdId, Household> newHouseholds) {
    return new SocialData(populations, cities, newGroups, newHouseholds, populationEvents);
  }

  /** 只换事件表。 */
  public SocialData withPopulationEvents(Map<String, HouseholdPopulationEvent> value) {
    return new SocialData(populations, cities, groups, households, value);
  }

  // ── 家户/位置派生量（★ 一律现算，别找地方存 —— 见类注）────────────────────────────────

  /**
   * 某批次所属的家户（架构 §5 的只读查询）：批次必须且只能属于一个家户 ⇒ 查不到是坏状态（构造期已拒），
   * 但本方法保持 {@link Optional} 形态（旧档/迁移期的读口防御）。
   */
  public Optional<Household> householdOfLot(PeopleLotId lotId) {
    if (lotId == null) {
      throw new IllegalArgumentException("lotId 不得为 null");
    }
    for (Household household : households.values()) {
      if (household.hasMember(lotId)) {
        return Optional.of(household);
      }
    }
    return Optional.empty();
  }

  /** 某批次的位置（HEX / UNIT）：位置只能来自所属家户（架构 §4.2）；查不到 ⇒ 空。 */
  public Optional<HouseholdLocation> locationOfLot(PeopleLotId lotId) {
    return householdOfLot(lotId).map(Household::location);
  }

  /**
   * 某批次所在的格（仅当所属家户挂在 {@code HEX} 上）：{@code UNIT} 家户的批次没有格，返回空。
   * ★ 它是 app 侧经济/读口从"批次 → 格"的唯一桥（不许再解析批次 id 的位置），unit 消费方属 S3。
   */
  public Optional<HexCoord> hexOfLot(PeopleLotId lotId) {
    return locationOfLot(lotId)
        .filter(HouseholdLocation.Hex.class::isInstance)
        .map(location -> ((HouseholdLocation.Hex) location).hex());
  }

  /** 某批次所在的 unit id（仅当所属家户挂在 {@code UNIT} 上）。 */
  public Optional<String> unitOfLot(PeopleLotId lotId) {
    return locationOfLot(lotId)
        .filter(HouseholdLocation.Unit.class::isInstance)
        .map(location -> ((HouseholdLocation.Unit) location).unitId());
  }

  /** 某个家户的本体；不存在 ⇒ 抛（fail-closed，不静默造空壳）。 */
  public Household requireHousehold(HouseholdId householdId) {
    if (householdId == null) {
      throw new IllegalArgumentException("householdId 不得为 null");
    }
    Household household = households.get(householdId);
    if (household == null) {
      throw new IllegalArgumentException("家户不存在: " + householdId);
    }
    return household;
  }

  /** 挂在某一格上的全部家户（保序：households 的插入序）。 */
  public List<Household> householdsAt(HexCoord hex) {
    if (hex == null) {
      throw new IllegalArgumentException("hex 不得为 null");
    }
    List<Household> out = new ArrayList<>();
    for (Household household : households.values()) {
      if (household.location() instanceof HouseholdLocation.Hex at && at.hex().equals(hex)) {
        out.add(household);
      }
    }
    return List.copyOf(out);
  }

  /** 挂在某个 unit 上的全部家户（保序：households 的插入序）。 */
  public List<Household> householdsInUnit(String unitId) {
    if (unitId == null || unitId.isBlank()) {
      throw new IllegalArgumentException("unitId 不得为空白");
    }
    List<Household> out = new ArrayList<>();
    for (Household household : households.values()) {
      if (household.location() instanceof HouseholdLocation.Unit at
          && at.unitId().equals(unitId)) {
        out.add(household);
      }
    }
    return List.copyOf(out);
  }

  /** 某个家户的人数（Σ 成员批次 count）；家户不存在 ⇒ 0（SPI 口径）。 */
  public long householdPopulation(HouseholdId householdId) {
    if (householdId == null) {
      throw new IllegalArgumentException("householdId 不得为 null");
    }
    Household household = households.get(householdId);
    if (household == null) {
      return 0L;
    }
    long total = 0L;
    for (PeopleLotId lot : household.memberLots()) {
      PopulationGroup group = groups.get(lot);
      if (group != null) {
        total += group.count();
      }
    }
    return total;
  }

  /**
   * 该格的**人口总量**（现算）：Σ 挂在该格的家户的成员批次 count —— 农村 + 城镇都在内（一处算法，架构 §5）。
   * ★ 旧 {@code populations} 序列不再是新主路径的位置真值；旧世界未迁移（S2 范围）。
   */
  public long populationAt(HexCoord hex) {
    if (hex == null) {
      throw new IllegalArgumentException("hex 不得为 null");
    }
    long total = 0L;
    for (Household household : householdsAt(hex)) {
      total += householdPopulation(household.id());
    }
    return total;
  }

  /** 某个 unit 的人口（Σ 挂在该 unit 的家户成员 count）。 */
  public long unitPopulation(String unitId) {
    if (unitId == null || unitId.isBlank()) {
      throw new IllegalArgumentException("unitId 不得为空白");
    }
    long total = 0L;
    for (Household household : householdsInUnit(unitId)) {
      total += householdPopulation(household.id());
    }
    return total;
  }

  /**
   * 某个家户的年龄档视图（**锚点口径**：用各成员批次自己的 {@code anchorTick} 上记的 {@code ageAtAnchorDays}
   * 现算档位；不随世界时钟推进）。
   *
   * <p>★ S2 的该重载服务"没有 now 的只读装配点"（{@code HouseholdView} 等）；推进后的真实年龄结构请走
   * {@link #ageBrackets(HouseholdId, long, CalendarClock)}。两者都只在查询期聚合，不落任何字段（设计稿 §三）。
   */
  public List<AgeBracketView> ageBrackets(HouseholdId householdId) {
    return ageBrackets(householdId, CalendarClock.julianDefault(), -1L);
  }

  /** 某个家户在 {@code nowTick} 的年龄档视图（真实口径：{@code ageDaysAt(nowTick)} 现算；键恒为 3 档 × 2 性别）。 */
  public List<AgeBracketView> ageBrackets(
      HouseholdId householdId, long nowTick, CalendarClock clock) {
    if (clock == null) {
      throw new IllegalArgumentException("clock 不得为 null");
    }
    if (nowTick < 0L) {
      throw new IllegalArgumentException("nowTick 不得为负: " + nowTick);
    }
    return ageBrackets(householdId, clock, nowTick);
  }

  /** 内部：{@code nowTick < 0} = 按各批次自己的锚点现算（{@link #ageBrackets(HouseholdId)} 的形态）。 */
  private List<AgeBracketView> ageBrackets(HouseholdId householdId, CalendarClock clock, long nowTick) {
    if (householdId == null) {
      throw new IllegalArgumentException("householdId 不得为 null");
    }
    Household household = households.get(householdId);
    if (household == null) {
      return List.of();
    }
    Map<AgeBracket, Map<Sex, Long>> counts = new LinkedHashMap<>();
    for (AgeBracket bracket : AgeBracket.values()) {
      Map<Sex, Long> bySex = new LinkedHashMap<>();
      for (Sex sex : Sex.values()) {
        bySex.put(sex, 0L);
      }
      counts.put(bracket, bySex);
    }
    for (PeopleLotId lot : household.memberLots()) {
      PopulationGroup group = groups.get(lot);
      if (group == null) {
        continue;
      }
      long atTick = nowTick < 0L ? group.anchorTick() : nowTick;
      long ageDays = group.ageDaysAt(atTick);
      AgeBracket bracket = AgeBracket.of(clock.system(), clock.dayNumberOfTick(atTick), ageDays);
      counts.get(bracket).put(group.sex(), counts.get(bracket).get(group.sex()) + group.count());
    }
    List<AgeBracketView> out = new ArrayList<>(AgeBracket.values().length * Sex.values().length);
    long[] minDays = {0L, 15L * 365L, 60L * 365L};
    long[] maxDays = {15L * 365L - 1L, 60L * 365L - 1L, Long.MAX_VALUE};
    for (AgeBracket bracket : AgeBracket.values()) {
      for (Sex sex : Sex.values()) {
        long deathRate = household.vitalRates().find(bracket.key(), sex)
            .map(rate -> rate.deathRatePerMillePerTick())
            .orElse(0L);
        long increaseRate = bracket == AgeBracket.CHILD
            ? household.vitalRates().find(bracket.key(), sex)
                .map(rate -> rate.birthRatePerMillePerTick())
                .orElse(0L)
            : 0L;
        out.add(
            new AgeBracketView(
                bracket.key(),
                minDays[bracket.ordinal()],
                maxDays[bracket.ordinal()],
                sex,
                counts.get(bracket).get(sex),
                increaseRate,
                deathRate));
      }
    }
    return List.copyOf(out);
  }

  /** 该格的家户成员批次（保序：与 {@link #groups()} 的插入序同序——创世落盘序是确定性的）。 */
  public List<PopulationGroup> groupsAt(HexCoord hex) {
    if (hex == null) {
      throw new IllegalArgumentException("hex 不得为 null");
    }
    // ★ 一顿预聚合（逐格读口在 GUI/危机监控里是热路径）：lot → 所属 HEX 家户的格；UNIT 家户的批次没有格。
    Map<PeopleLotId, HexCoord> hexByLot = new LinkedHashMap<>();
    for (Household household : households.values()) {
      if (household.location() instanceof HouseholdLocation.Hex at) {
        for (PeopleLotId lot : household.memberLots()) {
          hexByLot.put(lot, at.hex());
        }
      }
    }
    List<PopulationGroup> out = new ArrayList<>();
    for (PopulationGroup group : groups.values()) {
      if (hex.equals(hexByLot.get(group.id()))) {
        out.add(group);
      }
    }
    return List.copyOf(out);
  }

  /**
   * ★★ **该格有没有批次**（R2 的 T0：读口口径的判据）：不是"人数是否为 0" —— 创世给**零人口的格**也落 {@code count=0} 的批次
   * （见 {@code PopulationSeeder} 的类注），那是"有批次、且为 0"，读口该报 {@code 0} 而**不是**回退旧序列。
   *
   * <p>★ S2 起"有批次"的判据是"该格有家户、且家户成员批次非空"（位置已归家户）；旧序列仍按 R2 回退口径使用。
   */
  public boolean hasGroupsAt(HexCoord hex) {
    if (hex == null) {
      throw new IllegalArgumentException("hex 不得为 null");
    }
    for (Household household : householdsAt(hex)) {
      if (!household.memberLots().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★★ **该格人口的读口口径**（R2 的 T0，控制器已裁定）：**有批次 ⇒ 家户成员求和（真值源）；无批次 ⇒ 回退旧序列**。
   *
   * <p>★★ **为什么不是"一律用批次"**：随包的 bootstrap 世界（{@code worlds/v17levant.json}）**只有旧序列** ——
   * 一律读批次会让"世界还没初始化"看起来像"这一格没人"（0 与"没有数据"在界面上长得一模一样）。 故回退是**口径的一部分**，而"用的是哪一个"必须**读得出来**
   * （{@link PopulationHeadline#source()}）。
   *
   * @param series 该格的农村人口序列（**回退**时读它）；不得为 null
   * @param at 回退时的取值时刻
   */
  public PopulationHeadline headlinePopulationAt(
      HexCoord residence, PopulationSeries series, SimosTimestamp at) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    if (series == null) {
      throw new IllegalArgumentException("series 不得为 null（回退旧序列时要读它）");
    }
    if (at == null) {
      throw new IllegalArgumentException("at 不得为 null");
    }
    if (hasGroupsAt(residence)) {
      return new PopulationHeadline(populationAt(residence), PopulationSource.BATCHES);
    }
    return new PopulationHeadline(series.valueAt(at), PopulationSource.LEGACY_SERIES);
  }

  /**
   * 某城的**城镇人口**（现算）：该城各批次的 {@code count} 之和 —— {@code SocialCity} 上**不再有这个字段**
   * （R1 的 T5：降级为派生量）。
   *
   * <p>★ 归属由 {@link PopulationLots#urbanPrefix(CityId)} 的**前缀**给出（{@code urban:&lt;cityId&gt;:}）——
   * 有多少个性别/年龄细分都不影响；故本方法**不需要**读 {@code cities} 的落点、也不需要 {@code tick}。
   *
   * <p>★ **城必须在本表里**：查一个不存在的城 ⇒ 抛（fail-closed）。静默返回 0 会让"id 拼错"看起来像"这座城没人"。
   */
  public long urbanPopulationAt(CityId city) {
    if (city == null) {
      throw new IllegalArgumentException("city 不得为 null");
    }
    if (!cities.containsKey(city)) {
      throw new IllegalArgumentException("城市不存在: " + city + "（拒绝静默返回 0）");
    }
    String prefix = PopulationLots.urbanPrefix(city);
    long total = 0L;
    for (Map.Entry<PeopleLotId, PopulationGroup> entry : groups.entrySet()) {
      if (entry.getKey().value().startsWith(prefix)) {
        total += entry.getValue().count();
      }
    }
    return total;
  }

  // ── R1.5：三个"按格切一刀"的派生量（★ 同样是现算，且**不加任何字段**）──────────────────

  /**
   * 该格的**年龄结构**（现算）：{@link AgeBracket} 三档的人数，**键恒为全部三档**（没有人也是 0，不是缺键）。
   *
   * <p>★★ **人数取自批次，档位取自"锚点 + 时间差按历法现算"**（{@link PopulationGroup#ageDaysAt(long)} ⇒ {@link
   * AgeBracket#of}）：本方法**不读**、也不许有任何"当前档位"字段。
   *
   * @param nowTick 查询时刻（世界日）；档位由它现算
   * @param clock 历法时钟：tick→JDN 的唯一换算点（C4b 起必传，social 内部不造默认时钟）
   */
  public Map<AgeBracket, Long> ageStructureAt(
      HexCoord residence, long nowTick, CalendarClock clock) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    if (clock == null) {
      throw new IllegalArgumentException("clock 不得为 null");
    }
    long currentDayNumber = clock.dayNumberOfTick(nowTick);
    Map<AgeBracket, Long> structure = new LinkedHashMap<>();
    for (AgeBracket bracket : AgeBracket.values()) {
      structure.put(bracket, 0L);
    }
    for (PopulationGroup group : groupsAt(residence)) {
      AgeBracket bracket =
          AgeBracket.of(clock.system(), currentDayNumber, group.ageDaysAt(nowTick));
      structure.put(bracket, structure.get(bracket) + group.count());
    }
    return Collections.unmodifiableMap(structure);
  }

  /** 该格的**性别构成**（现算）：{@code MALE} / {@code FEMALE} 的人数，**键恒为两个性别**。 */
  public Map<Sex, Long> sexRatioAt(HexCoord residence) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    Map<Sex, Long> ratio = new LinkedHashMap<>();
    for (Sex sex : Sex.values()) {
      ratio.put(sex, 0L);
    }
    for (PopulationGroup group : groupsAt(residence)) {
      ratio.put(group.sex(), ratio.get(group.sex()) + group.count());
    }
    return Collections.unmodifiableMap(ratio);
  }

  /**
   * 该格的**城乡构成**（现算）：城镇 / 农村的人数（{@link UrbanRural}）。
   *
   * <p>★ 城乡**由 lot id 的前缀判**（{@link PopulationLots#isUrban(PopulationGroup)} 的**唯一**拼写点）。
   */
  public UrbanRural urbanRuralAt(HexCoord residence) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    long urban = 0L;
    long rural = 0L;
    for (PopulationGroup group : groupsAt(residence)) {
      if (PopulationLots.isUrban(group)) {
        urban += group.count();
      } else {
        rural += group.count();
      }
    }
    return new UrbanRural(urban, rural);
  }
}
