package io.mosire.simos.social.household;

import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.PopulationEventType;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>家户生命周期服务</b>（2026-10-09 家户/人口架构 §5）：纯函数式——进 {@link SocialData}、出**新的</b> {@link
 * SocialData}，不改入参。家户创建/位置/画像/成员增删/转移/率设置/GM 直调/事件落账/逐日生死结算都在这里收口。
 *
 * <p>★★ <b>唯一落账口</b>：人口事件表（{@code SocialData.populationEvents}）只由本类的 {@link #applyEvent} /
 * {@link #applyEvents}（单事件与批量）与 {@link #transferMembers}（原子双腿转移：事件形状里没有"对手方"，两条腿必须
 * 同时落，否则中间态会出现"人凭空消失/两个家户引用同一批次"）写入。{@link #applyEvent} 的重复 id 一律拒。
 *
 * <p>★★ <b>守恒检查</b>：每个写方法出口都调 {@link #requireConservation(SocialData)} —— 逐 lot 有且只有一个家户、
 * 成员批次都存在、无负人数；失败 ⇒ 具名 {@link IllegalArgumentException} + ERROR 日志（DEBUG 级记通过时的读数）。
 *
 * <p>★★ <b>日志</b>（架构 §6 的事件名与级别语义）：生命周期/汇总走 INFO（事件名见各方法），
 * 对账/守恒走 DEBUG，逐批次明细走 TRACE；logger 一律从 {@link SocialLog} 取，调用方不得自拼 logger 名。
 *
 * <p>★ <b>时间口径</b>：{@link #settleVitalEvents(SocialData, long)} 显式接世界日；手工命令类方法
 * （{@link #removeMembers} / {@link #transferMembers} / {@link #setVitalRates} / {@link #adjustPopulation}）
 * 没有 {@code day} 入参 ⇒ 事件 {@code day} 取"状态内已知的最大日"（{@link #latestDay(SocialData)}），
 * 时间参与者接入（S3+）后由调用方改用带 {@code day}/{@link CalendarClock} 的重载。
 */
public final class HouseholdBook {

  /** 年龄档代表性年龄的"天"折算（仅用于"事件只给档 id、没给逐日年龄"时新建批次；不是档界判定）。 */
  private static final long DAYS_PER_REPRESENTATIVE_YEAR = 365L;

  private HouseholdBook() {}

  // ── 生命周期 ─────────────────────────────────────────────────────────────────────────

  /**
   * 新建家户（位置 / 画像 / 率表都由调用方给；成员表为空）。
   *
   * @throws IllegalArgumentException id 已存在、或 location/profile/vitalRates 为 null
   */
  public static SocialData create(
      SocialData base,
      HouseholdId id,
      HouseholdLocation location,
      HouseholdProfile profile,
      HouseholdVitalRates vitalRates) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(vitalRates, "vitalRates");
    if (base.households().containsKey(id)) {
      throw new IllegalArgumentException("家户已存在，拒绝重复创建: " + id);
    }
    Household household = new Household(id, location, profile, Map.of(), vitalRates);
    Map<HouseholdId, Household> next = new LinkedHashMap<>(base.households());
    next.put(id, household);
    SocialData result = base.withHouseholds(next);
    SocialLog.household()
        .info(
            "event=HOUSEHOLD_CREATED "
                + SocialLog.kv("id", id, "location", location, "name", profile.name(), "members", 0));
    SocialLog.trace()
        .trace("event=HOUSEHOLD_CREATED_DETAIL " + SocialLog.kv("id", id, "profile", profile));
    return requireConservation(result);
  }

  /** 改位置（HEX ↔ UNIT 都合法；reason 只进日志/审计，事件形状里没有位置事件类型）。 */
  public static SocialData setLocation(
      SocialData base, HouseholdId id, HouseholdLocation location, String reason) {
    Objects.requireNonNull(base, "base");
    requireReason(reason);
    Household household = base.requireHousehold(id);
    HouseholdLocation previous = household.location();
    SocialData result =
        base.withHouseholds(replaceHousehold(base, household.withLocation(location)));
    SocialLog.household()
        .info(
            "event=HOUSEHOLD_LOCATION_SET "
                + SocialLog.kv("id", id, "from", previous, "to", location, "reason", reason));
    return requireConservation(result);
  }

  /** 改画像（reason 只进日志/审计）。 */
  public static SocialData setProfile(
      SocialData base, HouseholdId id, HouseholdProfile profile, String reason) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(profile, "profile");
    requireReason(reason);
    Household household = base.requireHousehold(id);
    SocialData result =
        base.withHouseholds(replaceHousehold(base, household.withProfile(profile)));
    SocialLog.household()
        .info(
            "event=HOUSEHOLD_PROFILE_SET "
                + SocialLog.kv("id", id, "name", profile.name(), "reason", reason));
    return requireConservation(result);
  }

  /**
   * 新建一个成员批次并挂进家户（GM/创世/命令路径）：{@code lotId} 若已存在 ⇒ 拒（不做静默合并——合并是把两批属性不同的人
   * 并成一批，必须由调用方决定 id）。
   */
  public static SocialData addMembers(
      SocialData base,
      HouseholdId id,
      PeopleLotId lotId,
      Sex sex,
      long count,
      long ageAtAnchorDays,
      long anchorTick,
      String reason) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(lotId, "lotId");
    Objects.requireNonNull(sex, "sex");
    requireReason(reason);
    if (count <= 0L) {
      throw new IllegalArgumentException("addMembers 的 count 必须 > 0: " + count);
    }
    if (base.groups().containsKey(lotId)) {
      throw new IllegalArgumentException("批次已存在，拒绝 addMembers（id 冲突）: " + lotId);
    }
    Household household = base.requireHousehold(id);
    PopulationGroup group =
        new PopulationGroup(lotId, sex, count, ageAtAnchorDays, anchorTick);
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>(base.groups());
    groups.put(lotId, group);
    Map<HouseholdId, Household> households = new LinkedHashMap<>(base.households());
    households.put(id, addMemberShare(household, lotId, count));
    // ★ 事件账：addMembers 带逐日年龄锚点，而事件形状只有年龄档 id ⇒ 事件记录为可审计的 GM_ADJUST（带 lotId），
    //   状态落账由本方法精确完成；回放口径见 applyEvent（按档 id 的代表年龄重建）。
    HouseholdPopulationEvent event =
        new HouseholdPopulationEvent(
            "gm-add:" + id + ":" + lotId + ":" + base.populationEvents().size(),
            id,
            PopulationEventType.GM_ADJUST,
            sex,
            bracketIdAt(group, anchorTick, CalendarClock.julianDefault()),
            count,
            anchorTick,
            reason,
            "HouseholdBook.addMembers",
            lotId);
    Map<String, HouseholdPopulationEvent> events = new LinkedHashMap<>(base.populationEvents());
    if (events.containsKey(event.id())) {
      throw new IllegalArgumentException("事件 id 已存在（拒绝重复落账）: " + event.id());
    }
    events.put(event.id(), event);
    SocialData result =
        new SocialData(base.populations(), base.cities(), groups, households, events);
    SocialLog.household()
        .info(
            "event=HOUSEHOLD_MEMBER_ADD "
                + SocialLog.kv(
                    "id",
                    id,
                    "lot",
                    lotId,
                    "count",
                    count,
                    "sex",
                    sex,
                    "ageAnchor",
                    ageAtAnchorDays,
                    "anchorTick",
                    anchorTick,
                    "reason",
                    reason));
    SocialLog.event()
        .debug(
            "event=GM_POPULATION_ADJUST "
                + SocialLog.kv("id", event.id(), "household", id, "lot", lotId, "delta", count, "day", anchorTick));
    return requireConservation(result);
  }

  /**
   * 从家户移除 {@code count} 个人；{@code count} 到 0 时删批次与 group（架构 §5 的原文）。
   * ★ 走 {@link #applyEvent} 的负向 {@code GM_ADJUST}（带 lotId）⇒ 可回放且是唯一落账口。
   */
  public static SocialData removeMembers(
      SocialData base, HouseholdId id, PeopleLotId lotId, long count, String reason) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(lotId, "lotId");
    requireReason(reason);
    if (count <= 0L) {
      throw new IllegalArgumentException("removeMembers 的 count 必须 > 0: " + count);
    }
    Household household = base.requireHousehold(id);
    requireMember(household, lotId);
    PopulationGroup group = base.groups().get(lotId);
    if (group.count() < count) {
      throw new IllegalArgumentException(
          "removeMembers 超出批次人数: lot=" + lotId + " count=" + group.count() + " remove=" + count);
    }
    long day = latestDay(base);
    HouseholdPopulationEvent event =
        new HouseholdPopulationEvent(
            "gm-remove:" + id + ":" + lotId + ":" + base.populationEvents().size(),
            id,
            PopulationEventType.GM_ADJUST,
            group.sex(),
            bracketIdAt(group, day, CalendarClock.julianDefault()),
            -count,
            day,
            reason,
            "HouseholdBook.removeMembers",
            lotId);
    SocialLog.household()
        .info(
            "event=HOUSEHOLD_MEMBER_REMOVE "
                + SocialLog.kv(
                    "id", id, "lot", lotId, "count", count, "remaining", group.count() - count, "reason", reason));
    return applyEvent(base, event);
  }

  /**
   * ★★ <b>跨家户转移成员</b>（架构 §5 + P2-A §13.2）：把源家户对某批次的一部分份额（count）转给目标家户。
   *
   * <p>★★ <b>P2-A 的口径变化（如实记）</b>：改前"拆分"会在目标家户落一个派生批次 id（{@code <lotId>@<toHousehold>}）；
   * 现在批次是<b>份额制</b> ⇒ 同一批次 id 同时被两个家户按 count 持有，<b>批次人数一字不动</b>（转移改的是份额，不是总人数）。
   *
   * <p>两条腿的事件（{@code TRANSFER_OUT}/{@code TRANSFER_IN}）带 {@code lotId}，由本方法**原子**写入
   * （事件形状没有"对手方"字段，逐条回放会在中间态看到无主批次；原子写是唯一安全口径）。
   */
  public static SocialData transferMembers(
      SocialData base,
      HouseholdId fromHousehold,
      HouseholdId toHousehold,
      PeopleLotId lotId,
      long count,
      String reason) {
    Objects.requireNonNull(base, "base");
    requireReason(reason);
    if (count <= 0L) {
      throw new IllegalArgumentException("transferMembers 的 count 必须 > 0: " + count);
    }
    if (fromHousehold.equals(toHousehold)) {
      throw new IllegalArgumentException("transferMembers 的源家户与目标家户相同: " + fromHousehold);
    }
    Household from = base.requireHousehold(fromHousehold);
    Household to = base.requireHousehold(toHousehold);
    requireMember(from, lotId);
    PopulationGroup source = base.groups().get(lotId);
    long fromShare = from.memberCount(lotId);
    if (fromShare < count) {
      throw new IllegalArgumentException(
          "transferMembers 超出该家户持有的份额: lot="
              + lotId
              + " household="
              + fromHousehold
              + " share="
              + fromShare
              + " transfer="
              + count);
    }
    long day = latestDay(base);
    String bracketId = bracketIdAt(source, day, CalendarClock.julianDefault());

    // ★★ P2-A：份额转移**保持批次 id 不变**（一个批次可按 count 拆给多个家户）—— 不再造派生批次 id。
    //   批次人数（group.count）一字不动：转移改的是"这一批人里多少归哪个家户"，不是总人数。
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>(base.groups());
    Map<HouseholdId, Household> households = new LinkedHashMap<>(base.households());
    PeopleLotId movedLot = lotId;
    Household nextFrom = removeMemberShare(from, lotId, count);
    households.put(fromHousehold, nextFrom);
    Household nextTo = addMemberShare(to, lotId, count);
    households.put(toHousehold, nextTo);

    long seq = base.populationEvents().size();
    String outId = "transfer-out:" + fromHousehold + ":" + lotId + ":" + seq;
    String inId = "transfer-in:" + toHousehold + ":" + movedLot + ":" + (seq + 1L);
    Map<String, HouseholdPopulationEvent> events = new LinkedHashMap<>(base.populationEvents());
    requireEventIdFree(events, outId);
    requireEventIdFree(events, inId);
    events.put(
        outId,
        new HouseholdPopulationEvent(
            outId,
            fromHousehold,
            PopulationEventType.TRANSFER_OUT,
            source.sex(),
            bracketId,
            count,
            day,
            reason,
            "HouseholdBook.transferMembers",
            lotId));
    events.put(
        inId,
        new HouseholdPopulationEvent(
            inId,
            toHousehold,
            PopulationEventType.TRANSFER_IN,
            source.sex(),
            bracketId,
            count,
            day,
            reason,
            "HouseholdBook.transferMembers",
            movedLot));

    SocialData result =
        new SocialData(base.populations(), base.cities(), groups, households, events);
    SocialLog.household()
        .info(
            "event=HOUSEHOLD_MEMBER_TRANSFER "
                + SocialLog.kv(
                    "from",
                    fromHousehold,
                    "to",
                    toHousehold,
                    "lot",
                    lotId,
                    "movedLot",
                    movedLot,
                    "count",
                    count,
                    "mode",
                    fromShare == count ? "WHOLE_HOUSEHOLD_SHARE" : "PARTIAL_SHARE",
                    "reason",
                    reason));
    SocialLog.population()
        .info(
            "event=POPULATION_TRANSFER_OUT "
                + SocialLog.kv("from", fromHousehold, "lot", lotId, "count", count, "reason", reason));
    SocialLog.population()
        .info(
            "event=POPULATION_TRANSFER_IN "
                + SocialLog.kv("to", toHousehold, "lot", movedLot, "count", count, "reason", reason));
    SocialLog.trace()
        .trace(
            "event=POPULATION_TRANSFER_DETAIL "
                + SocialLog.kv("from", fromHousehold, "to", toHousehold, "lot", lotId, "movedLot", movedLot, "count", count));
    return requireConservation(result);
  }

  /** 换率表；落一条 {@code RATE_SET} 标记事件（率表本体已在状态里，事件记录"谁在什么时候改的"）。 */
  public static SocialData setVitalRates(
      SocialData base, HouseholdId id, HouseholdVitalRates vitalRates, String reason) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(vitalRates, "vitalRates");
    requireReason(reason);
    Household household = base.requireHousehold(id);
    SocialData withRates =
        base.withHouseholds(replaceHousehold(base, household.withVitalRates(vitalRates)));
    SocialLog.household()
        .info(
            "event=HOUSEHOLD_RATE_SET "
                + SocialLog.kv("id", id, "rates", vitalRates.rates().size(), "reason", reason));
    SocialLog.trace()
        .trace("event=HOUSEHOLD_RATE_SET_DETAIL " + SocialLog.kv("id", id, "rates", vitalRates.rates()));
    long day = latestDay(withRates);
    HouseholdPopulationEvent event =
        new HouseholdPopulationEvent(
            "rate-set:" + id + ":" + base.populationEvents().size(),
            id,
            PopulationEventType.RATE_SET,
            Sex.MALE,
            AgeBracket.CHILD.key(),
            0L,
            day,
            reason,
            "HouseholdBook.setVitalRates",
            null);
    return applyEvent(withRates, event);
  }

  /**
   * ★★ <b>GM 直调人口</b>：{@code delta} 可正可负（负不得使 count &lt; 0）；按 {@code ageBracketId} 找/建 group
   * （架构 §5）。走 {@link #applyEvent} 的 {@code GM_ADJUST} ⇒ 唯一落账口。
   */
  public static SocialData adjustPopulation(
      SocialData base,
      HouseholdId id,
      Sex sex,
      String ageBracketId,
      long delta,
      String reason) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(sex, "sex");
    requireReason(reason);
    if (ageBracketId == null || ageBracketId.isBlank()) {
      throw new IllegalArgumentException("ageBracketId 不得为空白");
    }
    base.requireHousehold(id);
    if (delta == 0L) {
      SocialLog.population()
          .debug("event=GM_POPULATION_ADJUST " + SocialLog.kv("id", id, "delta", 0L, "noop", true));
      return base;
    }
    long day = latestDay(base);
    HouseholdPopulationEvent event =
        new HouseholdPopulationEvent(
            "gm-adjust:"
                + id
                + ":"
                + sex
                + ":"
                + ageBracketId
                + ":"
                + delta
                + ":"
                + base.populationEvents().size(),
            id,
            PopulationEventType.GM_ADJUST,
            sex,
            ageBracketId,
            delta,
            day,
            reason,
            "GM",
            null);
    SocialLog.population()
        .info(
            "event=GM_POPULATION_ADJUST "
                + SocialLog.kv(
                    "id", id, "sex", sex, "ageBracket", ageBracketId, "delta", delta, "reason", reason));
    return applyEvent(base, event);
  }

  // ── 事件落账（唯一写口）──────────────────────────────────────────────────────────────

  /** 单事件落账（缺省儒略历：S2 的便捷口径；绑定时钟的调用方走三参重载）。 */
  public static SocialData applyEvent(SocialData base, HouseholdPopulationEvent event) {
    return applyEvents(base, List.of(event), CalendarClock.julianDefault());
  }

  /** 单事件落账（显式历法时钟：tick→JDN 的唯一换算点）。 */
  public static SocialData applyEvent(
      SocialData base, HouseholdPopulationEvent event, CalendarClock clock) {
    return applyEvents(base, List.of(event), clock);
  }

  /** 批量事件落账（缺省儒略历）。 */
  public static SocialData applyEvents(
      SocialData base, List<HouseholdPopulationEvent> events) {
    return applyEvents(base, events, CalendarClock.julianDefault());
  }

  /**
   * ★★ <b>批量事件落账</b>：同一条 revision 里多条事件只在**出口**构造一次 {@link SocialData}（中间态不经过
   * 跨组件校验），事件 id 与既有表/批内都不得重复。
   */
  public static SocialData applyEvents(
      SocialData base, List<HouseholdPopulationEvent> events, CalendarClock clock) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(clock, "clock");
    if (events.isEmpty()) {
      return base;
    }
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>(base.groups());
    Map<HouseholdId, Household> households = new LinkedHashMap<>(base.households());
    Map<String, HouseholdPopulationEvent> eventsTable =
        new LinkedHashMap<>(base.populationEvents());
    Set<String> batchIds = new LinkedHashSet<>();
    List<HouseholdPopulationEvent> applied = new ArrayList<>(events.size());
    for (HouseholdPopulationEvent event : events) {
      Objects.requireNonNull(event, "events 不得含 null 元素");
      if (!batchIds.add(event.id())) {
        throw new IllegalArgumentException("事件 id 在批内重复: " + event.id());
      }
      requireEventIdFree(eventsTable, event.id());
      Household household = households.get(event.householdId());
      if (household == null) {
        throw new IllegalArgumentException("事件 " + event.id() + " 指向不存在的家户: " + event.householdId());
      }
      switch (event.type()) {
        case BIRTH, TRANSFER_IN -> increase(groups, households, household, event, clock);
        case DEATH, TRANSFER_OUT -> decrease(groups, households, household, event, clock);
        case GM_ADJUST -> {
          if (event.count() >= 0L) {
            increase(groups, households, household, event, clock);
          } else {
            decrease(groups, households, household, event, clock);
          }
        }
        case RATE_SET -> {
          // 率表本体由 setVitalRates 先落进 household；本事件是审计标记，不重复改任何状态。
        }
      }
      eventsTable.put(event.id(), event);
      applied.add(event);
      logEvent(event);
    }
    SocialData result =
        new SocialData(base.populations(), base.cities(), groups, households, eventsTable);
    for (HouseholdPopulationEvent event : applied) {
      SocialLog.event()
          .debug(
              "event=POPULATION_EVENT_APPLIED "
                  + SocialLog.kv(
                      "id",
                      event.id(),
                      "household",
                      event.householdId(),
                      "type",
                      event.type(),
                      "sex",
                      event.sex(),
                      "ageBracket",
                      event.ageBracketId(),
                      "count",
                      event.count(),
                      "day",
                      event.day()));
    }
    return requireConservation(result);
  }

  // ── 逐日生死结算 ─────────────────────────────────────────────────────────────────────

  /** 逐日生命事件结算（缺省儒略历；生产路径应传绑定时钟，见三参重载）。 */
  public static SocialData settleVitalEvents(SocialData base, long day) {
    return settleVitalEvents(base, day, CalendarClock.julianDefault());
  }

  /**
   * ★★ <b>逐日生命事件结算</b>（S2 的简单实现）：逐家户、逐成员批次
   *
   * <ol>
   *   <li><b>死亡</b>：按 {@code (该批次在 day 的年龄档, 性别)} 查家户率表，{@code deaths = count × 死亡率 ÷ 1000}
   *       （死亡率封顶 1000‰）；生成 {@code DEATH} 事件（带 lotId 精确落账）；
   *   <li><b>出生</b>：育龄段 = 率表里 {@code (档, FEMALE)} 的出生率 &gt; 0 的档；{@code births = 女性人数 × 出生率 ÷ 1000}；
   *       按性别各半拆成 {@code BIRTH} 事件（残差归男性，与创世性别切分同口径），落进<b>最小/0 岁档</b>（不存在则新建
   *       {@link PopulationGroup}，锚点 = {@code day}、年龄 0）。
   * </ol>
   *
   * <p>★ 结算先按<b>结算前</b>的批次算齐全部事件，再一次性 {@link #applyEvents} 落账 ⇒ 同一天内死亡与出生互不干扰、
   * 结果对同一输入确定。同一天重复调用会因事件 id 重复而拒（幂等守卫，不是静默重复出生）。
   */
  public static SocialData settleVitalEvents(SocialData base, long day, CalendarClock clock) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(clock, "clock");
    if (day < 0L) {
      throw new IllegalArgumentException("settleVitalEvents 的 day 不得为负: " + day);
    }
    List<HouseholdPopulationEvent> events = new ArrayList<>();
    for (Household household : base.households().values()) {
      for (Map.Entry<PeopleLotId, Long> member : household.members().entrySet()) {
        PeopleLotId lot = member.getKey();
        long share = member.getValue();
        PopulationGroup group = base.groups().get(lot);
        if (group == null || share <= 0L) {
          continue; // 构造期不变式下不会发生；防御性跳过。
        }
        String bracketId = bracketIdAt(group, day, clock);
        // ① 死亡（★ P2-A：按**家户份额**算，同一批次被多户持有时各户各算自己那一份）
        Optional<HouseholdVitalRate> deathRate = household.vitalRates().find(bracketId, group.sex());
        if (deathRate.isPresent() && deathRate.get().deathRatePerMillePerTick() > 0L) {
          long rate = Math.min(1000L, deathRate.get().deathRatePerMillePerTick());
          long deaths = share * rate / 1000L;
          if (deaths > 0L) {
            events.add(
                new HouseholdPopulationEvent(
                    "death:" + household.id() + ":" + day + ":" + lot,
                    household.id(),
                    PopulationEventType.DEATH,
                    group.sex(),
                    bracketId,
                    deaths,
                    day,
                    "vital=" + rate + "perMille",
                    "SETTLEMENT",
                    lot));
          }
        }
        // ② 出生：只有育龄段女性承载（率表里的 (档, FEMALE) 出生率 > 0）
        if (group.sex() == Sex.FEMALE && share > 0L) {
          Optional<HouseholdVitalRate> birthRate = household.vitalRates().find(bracketId, Sex.FEMALE);
          if (birthRate.isPresent() && birthRate.get().birthRatePerMillePerTick() > 0L) {
            long births = share * birthRate.get().birthRatePerMillePerTick() / 1000L;
            if (births > 0L) {
              long male = (births + 1L) / 2L;
              long female = births - male;
              for (int i = 0; i < Sex.values().length; i++) {
                Sex sex = Sex.values()[i];
                long count = i == 0 ? male : female;
                if (count <= 0L) {
                  continue;
                }
                events.add(
                    new HouseholdPopulationEvent(
                        "birth:" + household.id() + ":" + day + ":" + lot + ":" + sex,
                        household.id(),
                        PopulationEventType.BIRTH,
                        sex,
                        AgeBracket.CHILD.key(),
                        count,
                        day,
                        "fertileMother=" + lot,
                        "SETTLEMENT",
                        null));
              }
            }
          }
        }
      }
    }
    if (events.isEmpty()) {
      SocialLog.population()
          .debug("event=POPULATION_SETTLE " + SocialLog.kv("day", day, "households", base.households().size(), "events", 0));
      return base;
    }
    SocialLog.population()
        .info(
            "event=POPULATION_SETTLE "
                + SocialLog.kv("day", day, "households", base.households().size(), "events", events.size()));
    return applyEvents(base, events, clock);
  }

  // ── 守恒检查 ─────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>守恒检查</b>（架构 §5/§7 的判据）：逐 lot 有且只有一个家户、成员批次都存在、无负人数、键 == id。
   * 失败 ⇒ ERROR 日志 + 具名 {@link IllegalArgumentException}；通过 ⇒ DEBUG 对账读数。
   *
   * @return 原样返回 {@code data}（便于写方法 `return requireConservation(result)` 链式收口）
   */
  public static SocialData requireConservation(SocialData data) {
    Objects.requireNonNull(data, "data");
    try {
      // ★ P2-A：逐 lot 的跨家户份额守恒（Σ share == group.count）；同一批次可被多户持有。
      Map<PeopleLotId, Long> sharedByLot = new LinkedHashMap<>();
      for (Household household : data.households().values()) {
        if (!household.id().equals(data.households().get(household.id()).id())) {
          throw new IllegalArgumentException("家户键与 id 不一致: " + household.id());
        }
        for (Map.Entry<PeopleLotId, Long> member : household.members().entrySet()) {
          PopulationGroup group = data.groups().get(member.getKey());
          if (group == null) {
            throw new IllegalArgumentException("家户 " + household.id() + " 的成员批次不存在: " + member.getKey());
          }
          if (group.count() < 0L) {
            throw new IllegalArgumentException("批次人数为负: " + member.getKey() + " count=" + group.count());
          }
          if (member.getValue() < 0L) {
            throw new IllegalArgumentException(
                "家户份额为负: household=" + household.id() + " lot=" + member.getKey() + " share=" + member.getValue());
          }
          sharedByLot.merge(member.getKey(), member.getValue(), Math::addExact);
        }
      }
      for (Map.Entry<PeopleLotId, PopulationGroup> entry : data.groups().entrySet()) {
        Long shared = sharedByLot.get(entry.getKey());
        if (shared == null) {
          throw new IllegalArgumentException("批次没有家户（无主批次）: " + entry.getKey());
        }
        if (shared.longValue() != entry.getValue().count()) {
          throw new IllegalArgumentException(
              "批次 "
                  + entry.getKey()
                  + " 的家户份额之和必须等于批次人数: Σshare="
                  + shared
                  + " count="
                  + entry.getValue().count());
        }
      }
      long total = 0L;
      for (PopulationGroup group : data.groups().values()) {
        total += group.count();
      }
      SocialLog.population()
          .debug(
              "event=POPULATION_CONSERVATION_CHECK "
                  + SocialLog.kv(
                      "ok",
                      true,
                      "households",
                      data.households().size(),
                      "lots",
                      data.groups().size(),
                      "population",
                      total));
    } catch (IllegalArgumentException failure) {
      SocialLog.population()
          .error(
              "event=POPULATION_CONSERVATION_CHECK "
                  + SocialLog.kv("ok", false, "households", data.households().size(), "lots", data.groups().size(), "error", failure.getMessage()));
      throw failure;
    }
    return data;
  }

  // ── 内部：事件施加 ───────────────────────────────────────────────────────────────────

  /** 增加人数：带 lotId 精确落该批次（不存在则按档 id 建）；不带 lotId 找同 {@code (性别, 档)} 批次、否则新建。 */
  private static void increase(
      Map<PeopleLotId, PopulationGroup> groups,
      Map<HouseholdId, Household> households,
      Household household,
      HouseholdPopulationEvent event,
      CalendarClock clock) {
    long delta = Math.abs(event.count());
    Household current = households.get(household.id());
    if (event.lotId() != null) {
      PopulationGroup group = groups.get(event.lotId());
      if (group != null) {
        requireSex(group, event);
        groups.put(
            group.id(),
            group.withCountAndStress(group.count() + delta, group.physiologicalStress()));
        // 批次若已存在但本家户没有份额（回放中间态 / 跨户拆分的另一半），给它加份额。
        households.put(household.id(), addMemberShare(current, event.lotId(), delta));
        return;
      }
      PopulationGroup created = newGroup(event.lotId(), event, clock);
      groups.put(created.id(), created);
      households.put(household.id(), addMemberShare(current, created.id(), created.count()));
      return;
    }
    MemberSlot bucket = findBucket(current, groups, event, clock);
    if (bucket != null) {
      PopulationGroup group = bucket.group();
      groups.put(
          group.id(),
          group.withCountAndStress(group.count() + delta, group.physiologicalStress()));
      households.put(household.id(), addMemberShare(current, group.id(), delta));
      return;
    }
    PeopleLotId createdId = PeopleLotId.parse("evt:" + event.id());
    PopulationGroup created = newGroup(createdId, event, clock);
    groups.put(created.id(), created);
    households.put(household.id(), addMemberShare(current, created.id(), created.count()));
  }

  /** 减少人数：带 lotId 精确扣；不带 lotId 按同 {@code (性别, 档)} 批次瀑布扣（不足 ⇒ 拒）。 */
  private static void decrease(
      Map<PeopleLotId, PopulationGroup> groups,
      Map<HouseholdId, Household> households,
      Household household,
      HouseholdPopulationEvent event,
      CalendarClock clock) {
    long amount = Math.abs(event.count());
    Household current = households.get(household.id());
    if (event.lotId() != null) {
      PopulationGroup group = groups.get(event.lotId());
      if (group == null) {
        throw new IllegalArgumentException("事件 " + event.id() + " 的批次不存在: " + event.lotId());
      }
      requireMember(current, event.lotId());
      requireSex(group, event);
      long share = current.memberCount(event.lotId());
      if (share < amount || group.count() < amount) {
        throw new IllegalArgumentException(
            "事件 "
                + event.id()
                + " 扣减超量: lot="
                + event.lotId()
                + " household="
                + household.id()
                + " share="
                + share
                + " count="
                + group.count()
                + " delta="
                + amount);
      }
      long next = group.count() - amount;
      if (next == 0L) {
        groups.remove(event.lotId());
      } else {
        groups.put(group.id(), group.withCountAndStress(next, group.physiologicalStress()));
      }
      households.put(household.id(), removeMemberShare(current, event.lotId(), amount));
      return;
    }
    List<MemberSlot> candidates = matchingBuckets(current, groups, event, clock);
    long remaining = amount;
    Household nextHousehold = current;
    for (MemberSlot candidate : candidates) {
      if (remaining == 0L) {
        break;
      }
      long take = Math.min(remaining, candidate.share());
      if (take <= 0L) {
        continue;
      }
      PopulationGroup group = candidate.group();
      long next = group.count() - take;
      remaining -= take;
      nextHousehold = removeMemberShare(nextHousehold, candidate.lot(), take);
      if (next == 0L) {
        groups.remove(candidate.lot());
      } else {
        groups.put(group.id(), group.withCountAndStress(next, group.physiologicalStress()));
      }
    }
    if (remaining != 0L) {
      throw new IllegalArgumentException(
          "事件 " + event.id() + " 扣减不足: 家户=" + household.id() + " 性别=" + event.sex() + " 档=" + event.ageBracketId() + " 缺口=" + remaining);
    }
    households.put(household.id(), nextHousehold);
  }

  /** 家户份额表里的一条候选（瀑布按份额降序 / id 升序）。 */
  private record MemberSlot(PeopleLotId lot, long share, PopulationGroup group) {}

  /** 同 {@code (性别, 该 day 所在年龄档)} 的成员批次（按家户**份额**降序 / id 升序，瀑布的确定序）。 */
  private static List<MemberSlot> matchingBuckets(
      Household household,
      Map<PeopleLotId, PopulationGroup> groups,
      HouseholdPopulationEvent event,
      CalendarClock clock) {
    List<MemberSlot> out = new ArrayList<>();
    for (Map.Entry<PeopleLotId, Long> member : household.members().entrySet()) {
      PopulationGroup group = groups.get(member.getKey());
      if (group == null || group.sex() != event.sex()) {
        continue;
      }
      if (!bracketIdAt(group, event.day(), clock).equals(event.ageBracketId())) {
        continue;
      }
      out.add(new MemberSlot(member.getKey(), member.getValue(), group));
    }
    out.sort(
        Comparator.comparingLong(MemberSlot::share)
            .reversed()
            .thenComparing(slot -> slot.lot().value()));
    return out;
  }

  /** 找同 {@code (性别, 档)} 的既有批次（最小 id）；看不到 ⇒ null。 */
  private static MemberSlot findBucket(
      Household household,
      Map<PeopleLotId, PopulationGroup> groups,
      HouseholdPopulationEvent event,
      CalendarClock clock) {
    List<MemberSlot> candidates = new ArrayList<>();
    for (Map.Entry<PeopleLotId, Long> member : household.members().entrySet()) {
      PopulationGroup group = groups.get(member.getKey());
      if (group == null || group.sex() != event.sex()) {
        continue;
      }
      if (bracketIdAt(group, event.day(), clock).equals(event.ageBracketId())) {
        candidates.add(new MemberSlot(member.getKey(), member.getValue(), group));
      }
    }
    if (candidates.isEmpty()) {
      return null;
    }
    candidates.sort(Comparator.comparing(slot -> slot.lot().value()));
    return candidates.get(0);
  }

  /** 事件缺逐日年龄时按年龄档 id 建批次：代表年龄 = 档下界（天）；未知档 id ⇒ 拒。 */
  private static PopulationGroup newGroup(
      PeopleLotId id, HouseholdPopulationEvent event, CalendarClock clock) {
    long ageDays = representativeAgeDays(event.ageBracketId());
    return new PopulationGroup(id, event.sex(), Math.abs(event.count()), ageDays, event.day(), 0L);
  }

  /** 年龄档 id → 下界天数（CHILD=0 / ADULT=15×365 / ELDER=60×365）；未知 ⇒ 拒（不猜自定义档）。 */
  private static long representativeAgeDays(String bracketId) {
    for (AgeBracket bracket : AgeBracket.values()) {
      if (bracket.key().equals(bracketId)) {
        return switch (bracket) {
          case CHILD -> 0L;
          case ADULT -> 15L * DAYS_PER_REPRESENTATIVE_YEAR;
          case ELDER -> 60L * DAYS_PER_REPRESENTATIVE_YEAR;
        };
      }
    }
    throw new IllegalArgumentException("未知年龄档 id（拒绝按档建批次）: " + bracketId);
  }

  /** 批次在 {@code day} 的年龄档 id（历法现算；tick→JDN 只经 clock）。 */
  static String bracketIdAt(PopulationGroup group, long day, CalendarClock clock) {
    AgeBracket bracket =
        AgeBracket.of(clock.system(), clock.dayNumberOfTick(day), group.ageDaysAt(day));
    return bracket.key();
  }

  // ── 内部：状态小件 ───────────────────────────────────────────────────────────────────

  private static Map<HouseholdId, Household> replaceHousehold(SocialData base, Household replaced) {
    Map<HouseholdId, Household> next = new LinkedHashMap<>(base.households());
    next.put(replaced.id(), replaced);
    return next;
  }

  /** 给家户的某批次加份额（不存在则新建条目；count 必须 > 0）。 */
  private static Household addMemberShare(Household household, PeopleLotId lot, long count) {
    if (count < 0L) {
      throw new IllegalArgumentException("addMemberShare 的 count 不得为负: " + count);
    }
    return household.withMember(lot, Math.addExact(household.memberCount(lot), count));
  }

  /** 从家户的某批次份额里扣 count；份额归 0 ⇒ 删条目。 */
  private static Household removeMemberShare(Household household, PeopleLotId lot, long count) {
    requireMember(household, lot);
    long share = household.memberCount(lot);
    if (share < count) {
      throw new IllegalArgumentException(
          "家户 " + household.id() + " 对批次 " + lot + " 的份额不足: share=" + share + " remove=" + count);
    }
    if (share == count) {
      return household.withoutMember(lot);
    }
    return household.withMember(lot, share - count);
  }

  private static void requireMember(Household household, PeopleLotId lot) {
    if (!household.hasMember(lot)) {
      throw new IllegalArgumentException("家户 " + household.id() + " 不含成员批次: " + lot);
    }
  }

  private static void requireSex(PopulationGroup group, HouseholdPopulationEvent event) {
    if (group.sex() != event.sex()) {
      throw new IllegalArgumentException(
          "事件 " + event.id() + " 的性别 " + event.sex() + " 与批次 " + group.id() + " 的性别 " + group.sex() + " 不符");
    }
  }

  private static void requireEventIdFree(
      Map<String, HouseholdPopulationEvent> events, String eventId) {
    if (events.containsKey(eventId)) {
      throw new IllegalArgumentException("事件 id 已存在（拒绝重复落账）: " + eventId);
    }
  }

  private static void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason 不得为空白（事件/日志要能回答为什么）");
    }
  }

  /**
   * 状态内已知的最大日：全部事件 {@code day} 与批次 {@code anchorTick} 的最大值；空状态 ⇒ 0。
   * S2 的手工接口没有 {@code day} 入参，事件落账用它在"没有时间参与者"的窗口里保持单调、可复现。
   */
  static long latestDay(SocialData base) {
    long day = 0L;
    for (HouseholdPopulationEvent event : base.populationEvents().values()) {
      day = Math.max(day, event.day());
    }
    for (PopulationGroup group : base.groups().values()) {
      day = Math.max(day, group.anchorTick());
    }
    return day;
  }

  private static void logEvent(HouseholdPopulationEvent event) {
    switch (event.type()) {
      case BIRTH ->
          SocialLog.population()
              .info(
                  "event=POPULATION_BIRTH "
                      + SocialLog.kv(
                          "household",
                          event.householdId(),
                          "sex",
                          event.sex(),
                          "ageBracket",
                          event.ageBracketId(),
                          "count",
                          event.count(),
                          "day",
                          event.day()));
      case DEATH ->
          SocialLog.population()
              .info(
                  "event=POPULATION_DEATH "
                      + SocialLog.kv(
                          "household",
                          event.householdId(),
                          "sex",
                          event.sex(),
                          "ageBracket",
                          event.ageBracketId(),
                          "count",
                          event.count(),
                          "day",
                          event.day()));
      case TRANSFER_IN ->
          SocialLog.population()
              .info(
                  "event=POPULATION_TRANSFER_IN "
                      + SocialLog.kv(
                          "to",
                          event.householdId(),
                          "lot",
                          event.lotId(),
                          "count",
                          event.count(),
                          "day",
                          event.day()));
      case TRANSFER_OUT ->
          SocialLog.population()
              .info(
                  "event=POPULATION_TRANSFER_OUT "
                      + SocialLog.kv(
                          "from",
                          event.householdId(),
                          "lot",
                          event.lotId(),
                          "count",
                          event.count(),
                          "day",
                          event.day()));
      case GM_ADJUST ->
          SocialLog.population()
              .info(
                  "event=GM_POPULATION_ADJUST "
                      + SocialLog.kv(
                          "household",
                          event.householdId(),
                          "sex",
                          event.sex(),
                          "ageBracket",
                          event.ageBracketId(),
                          "delta",
                          event.count(),
                          "day",
                          event.day()));
      case RATE_SET ->
          SocialLog.population()
              .debug(
                  "event=POPULATION_RATE_SET "
                      + SocialLog.kv("household", event.householdId(), "day", event.day()));
    }
    SocialLog.event()
        .debug(
            "event=POPULATION_EVENT "
                + SocialLog.kv(
                    "id",
                    event.id(),
                    "household",
                    event.householdId(),
                    "type",
                    event.type(),
                    "lot",
                    event.lotId(),
                    "count",
                    event.count(),
                    "day",
                    event.day(),
                    "reason",
                    event.reason(),
                    "source",
                    event.source()));
  }
}
