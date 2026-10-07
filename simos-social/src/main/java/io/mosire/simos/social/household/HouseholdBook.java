package io.mosire.simos.social.household;

import io.mosire.simos.calendar.CalendarAge;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.SocialLogSource;
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
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.SocialVitalRemainder;
import io.mosire.simos.social.population.SocialVitalRemainders;
import io.mosire.simos.social.population.VitalKind;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>家户生命周期服务</b>（2026-10-09 家户/人口架构 §5）：纯函数式——进 {@link SocialData}、出**新的</b> {@link
 * SocialData}，不改入参。家户创建/位置/画像/成员增删/转移/率设置/GM 直调/事件落账/逐日生死结算都在这里收口。
 *
 * <p>★★ <b>唯一落账口</b>：人口事件表（{@code SocialData.populationEvents}）只由本类的 {@link #applyEvent} / {@link
 * #applyEvents}（单事件与批量）与 {@link #transferMembers}（原子双腿转移：事件形状里没有"对手方"，两条腿必须
 * 同时落，否则中间态会出现"人凭空消失/两个家户引用同一批次"）写入。{@link #applyEvent} 的重复 id 一律拒。
 *
 * <p>★★ <b>守恒检查</b>：每个写方法出口都调 {@link #requireConservation(SocialData)} —— 逐 lot 有且只有一个家户、
 * 成员批次都存在、无负人数；失败 ⇒ 具名 {@link IllegalArgumentException} + ERROR 日志（DEBUG 级记通过时的读数）。
 *
 * <p>★★ <b>日志</b>（架构 §6 的事件名与级别语义）：生命周期/汇总走 INFO（事件名见各方法）， 对账/守恒走 DEBUG，逐批次明细走 TRACE；logger 一律从
 * {@link SocialLog} 取，调用方不得自拼 logger 名。
 *
 * <p>★ <b>时间口径</b>：{@link #settleVitalEvents(SocialData, long)} 显式接世界日；手工命令类方法 （{@link
 * #removeMembers} / {@link #transferMembers} / {@link #setVitalRates} / {@link #adjustPopulation}）
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
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "HOUSEHOLD_CREATED",
                SocialLogSource.SOCIAL_COMMAND,
                "id",
                id,
                "location",
                location,
                "name",
                profile.name(),
                "members",
                0));
    if (SocialLog.trace().isTraceEnabled()) {
      EventLog.channel(SocialLog.trace())
          .trace(
              LogEvent.of(
                  "HOUSEHOLD_CREATED_DETAIL",
                  SocialLogSource.SOCIAL_COMMAND,
                  "id",
                  id,
                  "profile",
                  profile));
    }
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
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "HOUSEHOLD_LOCATION_SET",
                SocialLogSource.SOCIAL_COMMAND,
                "id",
                id,
                "from",
                previous,
                "to",
                location,
                "reason",
                reason));
    return requireConservation(result);
  }

  /** 改画像（reason 只进日志/审计）。 */
  public static SocialData setProfile(
      SocialData base, HouseholdId id, HouseholdProfile profile, String reason) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(profile, "profile");
    requireReason(reason);
    Household household = base.requireHousehold(id);
    SocialData result = base.withHouseholds(replaceHousehold(base, household.withProfile(profile)));
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "HOUSEHOLD_PROFILE_SET",
                SocialLogSource.SOCIAL_COMMAND,
                "id",
                id,
                "name",
                profile.name(),
                "reason",
                reason));
    return requireConservation(result);
  }

  /** 新建一个成员批次并挂进家户（GM/创世/命令路径）：{@code lotId} 若已存在 ⇒ 拒（不做静默合并——合并是把两批属性不同的人 并成一批，必须由调用方决定 id）。 */
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
    PopulationGroup group = new PopulationGroup(lotId, sex, count, ageAtAnchorDays, anchorTick);
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
        new SocialData(
            base.populations(),
            base.cities(),
            groups,
            households,
            events,
            base.provisioning(),
            base.vitalRates(),
            base.vitalRemainders(),
            base.satietyPerMille(),
            base.fleeStates());
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "HOUSEHOLD_MEMBER_ADD",
                SocialLogSource.SOCIAL_COMMAND,
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
    EventLog.channel(SocialLog.event())
        .debug(
            LogEvent.of(
                "GM_POPULATION_ADJUST",
                SocialLogSource.SOCIAL_COMMAND,
                "id",
                event.id(),
                "household",
                id,
                "lot",
                lotId,
                "delta",
                count,
                "day",
                anchorTick));
    return requireConservation(result);
  }

  /**
   * 从家户移除 {@code count} 个人；{@code count} 到 0 时删批次与 group（架构 §5 的原文）。 ★ 走 {@link #applyEvent} 的负向
   * {@code GM_ADJUST}（带 lotId）⇒ 可回放且是唯一落账口。
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
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "HOUSEHOLD_MEMBER_REMOVE",
                SocialLogSource.SOCIAL_COMMAND,
                "id",
                id,
                "lot",
                lotId,
                "count",
                count,
                "remaining",
                group.count() - count,
                "reason",
                reason));
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
        new SocialData(
            base.populations(),
            base.cities(),
            groups,
            households,
            events,
            base.provisioning(),
            base.vitalRates(),
            base.vitalRemainders(),
            base.satietyPerMille(),
            base.fleeStates());
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "HOUSEHOLD_MEMBER_TRANSFER",
                SocialLogSource.SOCIAL_COMMAND,
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
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "POPULATION_TRANSFER_OUT",
                SocialLogSource.SOCIAL_COMMAND,
                "from",
                fromHousehold,
                "lot",
                lotId,
                "count",
                count,
                "reason",
                reason));
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "POPULATION_TRANSFER_IN",
                SocialLogSource.SOCIAL_COMMAND,
                "to",
                toHousehold,
                "lot",
                movedLot,
                "count",
                count,
                "reason",
                reason));
    if (SocialLog.trace().isTraceEnabled()) {
      EventLog.channel(SocialLog.trace())
          .trace(
              LogEvent.of(
                  "POPULATION_TRANSFER_DETAIL",
                  SocialLogSource.SOCIAL_COMMAND,
                  "from",
                  fromHousehold,
                  "to",
                  toHousehold,
                  "lot",
                  lotId,
                  "movedLot",
                  movedLot,
                  "count",
                  count));
    }
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
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "HOUSEHOLD_RATE_SET",
                SocialLogSource.SOCIAL_COMMAND,
                "id",
                id,
                "rates",
                vitalRates.rates().size(),
                "reason",
                reason));
    if (SocialLog.trace().isTraceEnabled()) {
      EventLog.channel(SocialLog.trace())
          .trace(
              LogEvent.of(
                  "HOUSEHOLD_RATE_SET_DETAIL",
                  SocialLogSource.SOCIAL_COMMAND,
                  "id",
                  id,
                  "rates",
                  vitalRates.rates()));
    }
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
   * ★★ <b>GM 直调人口</b>：{@code delta} 可正可负（负不得使 count &lt; 0）；按 {@code ageBracketId} 找/建 group （架构
   * §5）。走 {@link #applyEvent} 的 {@code GM_ADJUST} ⇒ 唯一落账口。
   */
  public static SocialData adjustPopulation(
      SocialData base, HouseholdId id, Sex sex, String ageBracketId, long delta, String reason) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(sex, "sex");
    requireReason(reason);
    if (ageBracketId == null || ageBracketId.isBlank()) {
      throw new IllegalArgumentException("ageBracketId 不得为空白");
    }
    base.requireHousehold(id);
    if (delta == 0L) {
      EventLog.channel(SocialLog.command())
          .debug(
              LogEvent.of(
                  "GM_POPULATION_ADJUST",
                  SocialLogSource.SOCIAL_COMMAND,
                  "id",
                  id,
                  "delta",
                  0L,
                  "noop",
                  true));
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
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "GM_POPULATION_ADJUST",
                SocialLogSource.SOCIAL_COMMAND,
                "id",
                id,
                "sex",
                sex,
                "ageBracket",
                ageBracketId,
                "delta",
                delta,
                "reason",
                reason));
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
  public static SocialData applyEvents(SocialData base, List<HouseholdPopulationEvent> events) {
    return applyEvents(base, events, CalendarClock.julianDefault());
  }

  /**
   * ★★ <b>批量事件落账</b>：同一条 revision 里多条事件只在**出口**构造一次 {@link SocialData}（中间态不经过 跨组件校验），事件 id
   * 与既有表/批内都不得重复。
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
        throw new IllegalArgumentException(
            "事件 " + event.id() + " 指向不存在的家户: " + event.householdId());
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
        case WORK_ORDER -> {
          // ★ 工单受理标记（social.SubmitHouseholdWorkOrder 的幂等键载体）：只进事件表（可查 orderId/reason/source），
          //   不改任何人口状态；配套日志由 HouseholdWorkOrderBook 汇总，这里不重复记。
        }
      }
      eventsTable.put(event.id(), event);
      applied.add(event);
      logEvent(event);
    }
    SocialData result =
        new SocialData(
            base.populations(),
            base.cities(),
            groups,
            households,
            eventsTable,
            base.provisioning(),
            base.vitalRates(),
            base.vitalRemainders(),
            base.satietyPerMille(),
            base.fleeStates());
    for (HouseholdPopulationEvent event : applied) {
      EventLog.channel(SocialLog.event())
          .debug(
              LogEvent.of(
                  "POPULATION_EVENT_APPLIED",
                  settlementOrigin(event),
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

  // ── 每 tick 生死结算 ─────────────────────────────────────────────────────────────────

  /** 兼容旧调用：逐日生命事件结算（缺省儒略历；生产路径应传绑定时钟，见三参重载）。 */
  public static SocialData settleVitalEvents(SocialData base, long day) {
    return settleVitalEventsResult(base, day, CalendarClock.julianDefault()).data();
  }

  /** 兼容旧调用：逐日生命事件结算并只返回新状态（结果型入口见 {@link #settleVitalEventsResult}）。 */
  public static SocialData settleVitalEvents(SocialData base, long day, CalendarClock clock) {
    return settleVitalEventsResult(base, day, clock).data();
  }

  /**
   * ★★ <b>每 tick 生死结算（新引擎，计划 §3）</b>：逐家户、逐成员批次，按 ppm/tick 率与余数累加器算 {@code DEATH} / {@code BIRTH}
   * 事件，一次 {@link #applyEvents} 落账，并清理无主余数。
   *
   * <pre>
   * 死亡：numerator = 旧余数 + 份额 × deathRatePerMillionPerTick
   *       deaths    = numerator / 1_000_000；新余数 = numerator % 1_000_000
   * 出生：仅 FEMALE 且精确年龄 15 ≤ ageYears &lt; 45（率取 (15-59, FEMALE) 键）；
   *       numerator = 旧余数 + 份额 × birthRatePerMillionPerTick
   *       births    = numerator / 1_000_000；新余数 = numerator % 1_000_000
   * </pre>
   *
   * <p>★★ <b>首次见到某余数键时用稳定哈希给 [0, 999_999] 的初相位</b>（{@link #initialRemainder}）， <b>不是
   * 0</b>：零初值会让每个小批次的首个事件被推迟到 {@code 1_000_000 ÷ (份额 × 率)} 个 tick 之后—— 2026-10-09 的 360 tick smoke
   * 实测死亡 20 人，而率表连续期望 156 人（≈136 人的差额全冻在 450 个批次各自的 余数里，Python 逐步模拟复现
   * 20）。哈希相位让有限窗口内的事件数期望等于连续期望；余数仍跨 tick 累加、 长期速率不变，且同一状态重放逐字节相同（哈希只用稳定 id 与 kind 名，不用 {@code
   * Object.hashCode}/枚举身份）。
   *
   * <p>★ 结算先按<b>结算前</b>的批次算齐全部事件，再一次性 {@link #applyEvents} ⇒ 同一天内死亡与出生互不干扰、 结果对同一输入确定。事件 id =
   * {@code (household, lot, day, kind, sex)}，同日重复调用由事件 id 幂等守卫拒绝。
   *
   * <p>★ 余数键语义 = {@code (HouseholdId, PeopleLotId, kind)}；批次/家户份额消失后，其余数在本次结算出口清理， 不留无主余数（{@code
   * vitalRemainders} 组件本身只管范围与键唯一）。
   *
   * @param base 结算前状态；不得为 null
   * @param day 世界日（tick）；不得为负
   * @param clock 历法时钟：tick→JDN 与年龄年数换算唯一的入口；不得为 null
   * @throws IllegalArgumentException 参数坏、批次年龄为负、率表缺键、事件重复等具名拒绝
   */
  public static VitalSettlementResult settleVitalEventsResult(
      SocialData base, long day, CalendarClock clock) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(clock, "clock");
    if (day < 0L) {
      throw vitalReject(day, "settleVitalEvents 的 day 不得为负: " + day);
    }
    long currentDayNumber = clock.dayNumberOfTick(day);
    EventLog.channel(SocialLog.settle())
        .info(
            LogEvent.of(
                "POPULATION_SETTLE_START",
                SocialLogSource.SOCIAL_SETTLE,
                "day",
                day,
                "households",
                base.households().size(),
                "groups",
                base.groups().size(),
                "remainders",
                base.vitalRemainders().entries().size()));

    // ① 载入有效余数；旧余数里批次已不存在的键直接清理（不参与本 tick 计算）。
    Map<RemainderKey, Long> remainders = new LinkedHashMap<>();
    int cleanedRemainders = 0;
    for (SocialVitalRemainder entry : base.vitalRemainders().entries()) {
      RemainderKey key = new RemainderKey(entry.householdId(), entry.lotId(), entry.kind());
      if (hasLiveShare(base, key)) {
        remainders.put(key, entry.numerator());
      } else {
        cleanedRemainders++;
      }
    }
    if (SocialLog.settle().isDebugEnabled()) {
      int householdRateOverrides = 0;
      for (Household household : base.households().values()) {
        if (!household.vitalRates().rates().isEmpty()) {
          householdRateOverrides++;
        }
      }
      EventLog.channel(SocialLog.settle())
          .debug(
              LogEvent.of(
                  "POPULATION_SETTLE_POOL",
                  SocialLogSource.SOCIAL_SETTLE,
                  "day",
                  day,
                  "households",
                  base.households().size(),
                  "groups",
                  base.groups().size(),
                  "rateRows",
                  base.vitalRates().globalDefaults().rates().size(),
                  "householdRateOverrides",
                  householdRateOverrides,
                  "loadedRemainders",
                  remainders.size(),
                  "cleanedRemainders",
                  cleanedRemainders));
    }

    List<HouseholdPopulationEvent> events = new ArrayList<>();
    boolean traceEnabled = SocialLog.trace().isTraceEnabled();
    Map<HouseholdId, Long> populationDeltas = new LinkedHashMap<>();
    long totalBirths = 0L;
    long totalDeaths = 0L;

    for (Household household : base.households().values()) {
      long householdBirths = 0L;
      long householdDeaths = 0L;
      for (Map.Entry<PeopleLotId, Long> member : household.members().entrySet()) {
        PeopleLotId lot = member.getKey();
        long share = member.getValue();
        if (share <= 0L) {
          continue; // 显式 0 份额：不贡献生死，也不会留下余数（出口清理）。
        }
        PopulationGroup group = base.groups().get(lot);
        if (group == null) {
          throw vitalReject(day, "家户 " + household.id() + " 的成员批次不在 groups 里（坏数据）: " + lot);
        }
        long ageDays = group.ageDaysAt(day);
        if (ageDays < 0L) {
          throw vitalReject(
              day,
              "settleVitalEvents：批次年龄为负（day 早于锚点） household="
                  + household.id()
                  + " lot="
                  + lot
                  + " day="
                  + day
                  + " anchorTick="
                  + group.anchorTick());
        }
        AgeBracket bracket = AgeBracket.of(clock.system(), currentDayNumber, ageDays);
        long ageYears =
            CalendarAge.ageInYears(
                clock.system(), Math.subtractExact(currentDayNumber, ageDays), currentDayNumber);

        // ② 死亡：份额 × 死亡率（ppm/tick），余数跨 tick 累加。
        HouseholdVitalRate deathRate = base.findVitalRate(household.id(), bracket, group.sex());
        RemainderKey deathKey = new RemainderKey(household.id(), lot, VitalKind.DEATH);
        long deathNumerator =
            Math.addExact(
                remainderFor(remainders, deathKey, household.id(), lot, VitalKind.DEATH),
                Math.multiplyExact(share, deathRate.deathRatePerMillionPerTick()));
        long deaths = deathNumerator / 1_000_000L;
        putRemainder(remainders, deathKey, deathNumerator % 1_000_000L);
        if (deaths > 0L) {
          events.add(
              new HouseholdPopulationEvent(
                  vitalEventId(VitalKind.DEATH, household.id(), lot, day, group.sex()),
                  household.id(),
                  PopulationEventType.DEATH,
                  group.sex(),
                  bracket.key(),
                  deaths,
                  day,
                  "ratePpm=" + deathRate.deathRatePerMillionPerTick(),
                  "SETTLEMENT",
                  lot));
          householdDeaths = Math.addExact(householdDeaths, deaths);
        }

        // ③ 出生：仅精确育龄窗口（15 ≤ ageYears < 45）的女性；率取 (15-59, FEMALE) 键。
        long births = 0L;
        if (group.sex() == Sex.FEMALE && ageYears >= 15L && ageYears < 45L) {
          HouseholdVitalRate birthRate =
              base.findVitalRate(household.id(), AgeBracket.ADULT, Sex.FEMALE);
          RemainderKey birthKey = new RemainderKey(household.id(), lot, VitalKind.BIRTH);
          long birthNumerator =
              Math.addExact(
                  remainderFor(remainders, birthKey, household.id(), lot, VitalKind.BIRTH),
                  Math.multiplyExact(share, birthRate.birthRatePerMillionPerTick()));
          births = birthNumerator / 1_000_000L;
          putRemainder(remainders, birthKey, birthNumerator % 1_000_000L);
          if (births > 0L) {
            long male = (births + 1L) / 2L;
            long female = births - male;
            for (int index = 0; index < Sex.values().length; index++) {
              Sex childSex = Sex.values()[index];
              long count = index == 0 ? male : female;
              if (count <= 0L) {
                continue;
              }
              PeopleLotId bornLot = birthLotId(household.id(), group, childSex, day);
              events.add(
                  new HouseholdPopulationEvent(
                      vitalEventId(VitalKind.BIRTH, household.id(), lot, day, childSex),
                      household.id(),
                      PopulationEventType.BIRTH,
                      childSex,
                      AgeBracket.CHILD.key(),
                      count,
                      day,
                      "motherLot=" + lot + " ratePpm=" + birthRate.birthRatePerMillionPerTick(),
                      "SETTLEMENT",
                      bornLot));
            }
            householdBirths = Math.addExact(householdBirths, births);
          }
        }
        if (traceEnabled) {
          EventLog.channel(SocialLog.trace())
              .trace(
                  LogEvent.of(
                      "POPULATION_SETTLE_LOT",
                      SocialLogSource.SOCIAL_SETTLE,
                      "day",
                      day,
                      "household",
                      household.id(),
                      "lot",
                      lot,
                      "sex",
                      group.sex(),
                      "share",
                      share,
                      "ageYears",
                      ageYears,
                      "deaths",
                      deaths,
                      "births",
                      births));
        }
      }
      long householdDelta = Math.subtractExact(householdBirths, householdDeaths);
      if (householdDelta != 0L) {
        populationDeltas.put(household.id(), householdDelta);
      }
      totalBirths = Math.addExact(totalBirths, householdBirths);
      totalDeaths = Math.addExact(totalDeaths, householdDeaths);
    }

    // ④ 一次性落账；没有事件时也必须把余数表写回（余数本身就是状态）。
    SocialData settled = events.isEmpty() ? base : applyEvents(base, events, clock);

    // ⑤ 出口清理：死绝/迁走/不存在的主人一律不留余数键。
    Map<RemainderKey, Long> finalRemainders = new LinkedHashMap<>();
    for (Map.Entry<RemainderKey, Long> entry : remainders.entrySet()) {
      if (hasLiveShare(settled, entry.getKey()) && entry.getValue() != 0L) {
        finalRemainders.put(entry.getKey(), entry.getValue());
      } else if (entry.getValue() != 0L) {
        cleanedRemainders++;
      }
    }
    SocialData data = settled.withVitalRemainders(toRemainders(finalRemainders));

    EventLog.channel(SocialLog.settle())
        .info(
            LogEvent.of(
                "POPULATION_SETTLE",
                SocialLogSource.SOCIAL_SETTLE,
                "day",
                day,
                "households",
                base.households().size(),
                "births",
                totalBirths,
                "deaths",
                totalDeaths,
                "events",
                events.size(),
                "deltaHouseholds",
                populationDeltas.size(),
                "remainders",
                finalRemainders.size(),
                "cleanedRemainders",
                cleanedRemainders));
    if (cleanedRemainders > 0) {
      EventLog.channel(SocialLog.settle())
          .warn(
              LogEvent.of(
                  "POPULATION_SETTLE_REMAINDERS_CLEANED",
                  SocialLogSource.SOCIAL_SETTLE,
                  "day",
                  day,
                  "count",
                  cleanedRemainders));
    }
    return new VitalSettlementResult(data, populationDeltas, totalBirths, totalDeaths, day, events);
  }

  /**
   * 计划 §3.1 的 App 侧调用名：与 {@link #settleVitalEventsResult} 同一条每 tick 引擎（旧 {@code
   * settleVitalEvents(...)} 签名保留为只返回 {@link SocialData} 的兼容口）。
   */
  public static VitalSettlementResult settleOneTick(
      SocialData base, long day, CalendarClock clock) {
    return settleVitalEventsResult(base, day, clock);
  }

  /** 余数键：{@code (HouseholdId, PeopleLotId, VitalKind)}（计划 §3.4 的键语义）。 */
  private record RemainderKey(HouseholdId householdId, PeopleLotId lotId, VitalKind kind) {}

  /** 该 {@code (家户, 批次)} 在状态里仍有正份额且批次仍存在（余数只允许挂在这种主上）。 */
  private static boolean hasLiveShare(SocialData data, RemainderKey key) {
    if (!data.groups().containsKey(key.lotId())) {
      return false;
    }
    Household household = data.households().get(key.householdId());
    return household != null && household.memberCount(key.lotId()) > 0L;
  }

  /** 0 ⇒ 删键（"没有余数不落键"）；非 0 ⇒ 覆盖/追加。 */
  private static void putRemainder(
      Map<RemainderKey, Long> remainders, RemainderKey key, long value) {
    if (value == 0L) {
      remainders.remove(key);
    } else {
      remainders.put(key, value);
    }
  }

  /**
   * 取某键的当前余数；<b>首次见键</b>用 {@link #initialRemainder} 落稳定哈希初相位并写进工作副本。
   *
   * <p>★ 为什么不是 0：见 {@link #settleVitalEventsResult} 的类注——零初值会把小批次首事件推迟数年， 使有限窗口内的实际生死数系统性低于率表期望。
   */
  private static long remainderFor(
      Map<RemainderKey, Long> remainders,
      RemainderKey key,
      HouseholdId householdId,
      PeopleLotId lotId,
      VitalKind kind) {
    Long existing = remainders.get(key);
    if (existing != null) {
      return existing;
    }
    long phase = initialRemainder(householdId, lotId, kind);
    remainders.put(key, phase);
    return phase;
  }

  /**
   * 稳定哈希初相位 ∈ {@code [0, 999_999]}：FNV-1a 64 位（只用稳定 id 与 {@link VitalKind#name()}， 不用 {@code
   * Object.hashCode}/枚举身份）⇒ 同一状态重放逐字节相同，不同批次/家户的相位互不相同。
   *
   * <p>★ 它是"余数不为 0"的一次性初值，不是每 tick 加的噪声；之后完全由 {@code numerator % 1_000_000} 推进。
   */
  private static long initialRemainder(HouseholdId householdId, PeopleLotId lotId, VitalKind kind) {
    String key = householdId.value() + "|" + lotId.value() + "|" + kind.name();
    long hash = 0xcbf29ce484222325L; // FNV-1a 64 offset basis
    for (int index = 0; index < key.length(); index++) {
      hash ^= key.charAt(index);
      hash *= 0x100000001b3L; // FNV-1a 64 prime（long 溢出回绕是算法的一部分）
    }
    return Math.floorMod(hash, 1_000_000L);
  }

  /** 内部 map → 保序组件（迭代序 = 载入/写入序）。 */
  private static SocialVitalRemainders toRemainders(Map<RemainderKey, Long> remainders) {
    List<SocialVitalRemainder> entries = new ArrayList<>(remainders.size());
    for (Map.Entry<RemainderKey, Long> entry : remainders.entrySet()) {
      entries.add(
          new SocialVitalRemainder(
              entry.getKey().householdId(),
              entry.getKey().lotId(),
              entry.getKey().kind(),
              entry.getValue()));
    }
    return new SocialVitalRemainders(entries);
  }

  /**
   * 同日幂等的事件 id（计划 §3.3）：{@code (household, lot, day, kind, sex)} 的稳定拼写。
   *
   * <p>★ 它不含任何计数/序号 —— 同一批人在同一天只能落一次账；重复调用会命中 {@link #applyEvents} 的"事件 id 已存在"守卫。
   */
  private static String vitalEventId(
      VitalKind kind, HouseholdId householdId, PeopleLotId lotId, long day, Sex sex) {
    return "vital:" + kind.name() + ":" + householdId + ":" + lotId + ":" + day + ":" + sex;
  }

  /**
   * 新生批次的 id：优先走 {@link PopulationLots#born} 保住 {@code rural:/urban:} 前缀（经济侧按前缀分池）； 母亲批次 id
   * 不符合标准形状（命令造的自定义批次）时退化为 {@code born:<家户 hex>:<母亲>:<day>:<性别>}， 保证同日同户不撞 id、且不因一个自定义 lot 让整次结算失败。
   */
  private static PeopleLotId birthLotId(
      HouseholdId householdId, PopulationGroup mother, Sex childSex, long day) {
    String householdToken =
        HexFormat.of().formatHex(householdId.value().getBytes(StandardCharsets.UTF_8));
    String cohort = "b" + day + "-" + householdToken;
    try {
      return PopulationLots.born(mother, childSex, cohort);
    } catch (IllegalArgumentException malformedMotherLot) {
      EventLog.channel(SocialLog.settle())
          .warn(
              LogEvent.of(
                  "POPULATION_BIRTH_LOT_ID_FALLBACK",
                  SocialLogSource.SOCIAL_SETTLE,
                  "household",
                  householdId,
                  "motherLot",
                  mother.id(),
                  "day",
                  day,
                  "sex",
                  childSex,
                  "reason",
                  malformedMotherLot.getMessage()));
      return PeopleLotId.parse(
          "born:" + householdToken + ":" + mother.id().value() + ":" + day + ":" + childSex.name());
    }
  }

  /** 结算路径的具名拒绝出口：ERROR 日志（带 day）+ {@link IllegalArgumentException}（与 provisioning 同制）。 */
  private static IllegalArgumentException vitalReject(long day, String message) {
    EventLog.channel(SocialLog.settle())
        .error(
            LogEvent.of(
                "POPULATION_SETTLE_REJECTED",
                SocialLogSource.SOCIAL_SETTLE,
                "day",
                day,
                "reason",
                message));
    return new IllegalArgumentException(message);
  }

  // ── 守恒检查 ─────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>守恒检查</b>（架构 §5/§7 的判据）：逐 lot 有且只有一个家户、成员批次都存在、无负人数、键 == id。 失败 ⇒ ERROR 日志 + 具名 {@link
   * IllegalArgumentException}；通过 ⇒ DEBUG 对账读数。
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
            throw new IllegalArgumentException(
                "家户 " + household.id() + " 的成员批次不存在: " + member.getKey());
          }
          if (group.count() < 0L) {
            throw new IllegalArgumentException(
                "批次人数为负: " + member.getKey() + " count=" + group.count());
          }
          if (member.getValue() < 0L) {
            throw new IllegalArgumentException(
                "家户份额为负: household="
                    + household.id()
                    + " lot="
                    + member.getKey()
                    + " share="
                    + member.getValue());
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
      if (SocialLog.settle().isDebugEnabled()) {
        EventLog.channel(SocialLog.settle())
            .debug(
                LogEvent.of(
                    "POPULATION_CONSERVATION_CHECK",
                    SocialLogSource.SOCIAL_SETTLE,
                    "day",
                    latestDay(data),
                    "ok",
                    true,
                    "households",
                    data.households().size(),
                    "lots",
                    data.groups().size(),
                    "population",
                    total));
      }
    } catch (IllegalArgumentException failure) {
      EventLog.channel(SocialLog.settle())
          .error(
              LogEvent.of(
                  "POPULATION_CONSERVATION_CHECK",
                  SocialLogSource.SOCIAL_SETTLE,
                  "day",
                  latestDay(data),
                  "ok",
                  false,
                  "households",
                  data.households().size(),
                  "lots",
                  data.groups().size(),
                  "error",
                  failure.getMessage()));
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
        groups.put(group.id(), group.withCount(group.count() + delta));
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
      groups.put(group.id(), group.withCount(group.count() + delta));
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
        groups.put(group.id(), group.withCount(next));
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
        groups.put(group.id(), group.withCount(next));
      }
    }
    if (remaining != 0L) {
      throw new IllegalArgumentException(
          "事件 "
              + event.id()
              + " 扣减不足: 家户="
              + household.id()
              + " 性别="
              + event.sex()
              + " 档="
              + event.ageBracketId()
              + " 缺口="
              + remaining);
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
    return new PopulationGroup(id, event.sex(), Math.abs(event.count()), ageDays, event.day());
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
          "事件 "
              + event.id()
              + " 的性别 "
              + event.sex()
              + " 与批次 "
              + group.id()
              + " 的性别 "
              + group.sex()
              + " 不符");
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
   * 状态内已知的最大日：全部事件 {@code day} 与批次 {@code anchorTick} 的最大值；空状态 ⇒ 0。 S2 的手工接口没有 {@code day}
   * 入参，事件落账用它在"没有时间参与者"的窗口里保持单调、可复现。
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

  /** 按事件类型取来源：BIRTH/DEATH 是结算算法产物 ⇒ {@code SOCIAL_SETTLE}；其余写口事件 ⇒ {@code SOCIAL_COMMAND}。 */
  private static SocialLogSource settlementOrigin(HouseholdPopulationEvent event) {
    return switch (event.type()) {
      case BIRTH, DEATH -> SocialLogSource.SOCIAL_SETTLE;
      default -> SocialLogSource.SOCIAL_COMMAND;
    };
  }

  private static void logEvent(HouseholdPopulationEvent event) {
    SocialLogSource origin = settlementOrigin(event);
    switch (event.type()) {
      case BIRTH ->
          EventLog.channel(SocialLog.population())
              .debug(
                  LogEvent.of(
                      "POPULATION_BIRTH",
                      origin,
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
          EventLog.channel(SocialLog.population())
              .debug(
                  LogEvent.of(
                      "POPULATION_DEATH",
                      origin,
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
          EventLog.channel(SocialLog.population())
              .info(
                  LogEvent.of(
                      "POPULATION_TRANSFER_IN",
                      origin,
                      "to",
                      event.householdId(),
                      "lot",
                      event.lotId(),
                      "count",
                      event.count(),
                      "day",
                      event.day()));
      case TRANSFER_OUT ->
          EventLog.channel(SocialLog.population())
              .info(
                  LogEvent.of(
                      "POPULATION_TRANSFER_OUT",
                      origin,
                      "from",
                      event.householdId(),
                      "lot",
                      event.lotId(),
                      "count",
                      event.count(),
                      "day",
                      event.day()));
      case GM_ADJUST ->
          EventLog.channel(SocialLog.population())
              .info(
                  LogEvent.of(
                      "GM_POPULATION_ADJUST",
                      origin,
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
          EventLog.channel(SocialLog.population())
              .debug(
                  LogEvent.of(
                      "POPULATION_RATE_SET",
                      origin,
                      "household",
                      event.householdId(),
                      "day",
                      event.day()));
      case WORK_ORDER ->
          EventLog.channel(SocialLog.event())
              .debug(
                  LogEvent.of(
                      "POPULATION_WORK_ORDER_MARKER",
                      origin,
                      "id",
                      event.id(),
                      "household",
                      event.householdId(),
                      "day",
                      event.day()));
      default -> throw new IllegalStateException("未知 PopulationEventType: " + event.type());
    }
    EventLog.channel(SocialLog.event())
        .debug(
            LogEvent.of(
                "POPULATION_EVENT",
                origin,
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
