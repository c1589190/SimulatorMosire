package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.SocialLogSource;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code social.SeedGroups} 命令的处理器（R1 的 T3）：**一次落 N 个人口批次**（{@link PopulationGroup}）——
 * 它是"人口实体"唯一的落盘入口；没有它，批次只能活在测试里。
 *
 * <pre>{@code
 * {"entries":[{"id":"rural:0_0:MALE","q":0,"r":0,"sex":"MALE","count":6000,"ageDays":13505,"stress":0},
 *             {"id":"urban:c-0_0:FEMALE","q":0,"r":0,"sex":"FEMALE","count":4000,"ageDays":13505,"stress":0}],
 *  "anchorTick":0?, "households":[{id,q,r,name?,description?}?]}
 * }</pre>
 *
 * <p>★★ <b>S2：家户是位置的唯一来源</b>（架构 §4.2：{@code PopulationGroup.residence} 已删）。本 handler 落批次的**同时**
 * 把它们挂进家户的 {@code memberLots}：
 *
 * <ul>
 *   <li>条目带 {@code household} 字段 ⇒ 挂进该家户（{@code households[]} 里声明的新家户，或已存在的家户）；
 *   <li>条目不带 ⇒ 若该批次已在某个家户里（整组覆盖路径）保持原归属；否则按 {@code {q,r}} 归位——该格恰有一个家户 就并进去，否则复用/新建 {@code
 *       hh:hex:<q>_<r>}（零人口格也落家户，见 {@code PopulationSeeder}）；
 *   <li>同一批次被声明的家户与既有家户**不一致** ⇒ 拒（不做静默迁移；迁移是 {@code HouseholdBook.transferMembers} 的事）。
 * </ul>
 *
 * <p>★ **一条命令 = 一条 revision**：全部 entries 由同一个 {@link SocialChangeSet} 承载（{@link
 * SocialChangeSet#between} 逐组件比一次），批内不存在"落了一半"的中间态。
 *
 * <p>★ **覆盖语义**：同一 id 已在状态里 ⇒ **整条替换**批次的人数/年龄/压力（成员关系不变）； {@code households[]} 里声明的家户若已存在 ⇒
 * 更新位置/画像（成员表保持并集，不因本次载荷丢人）。
 *
 * <p>★ **坏载荷与域规则违反都折成 {@code Rejected}**（照本模块惯例，见 {@link SocialPayloads}）：空 entries、 {@code sex}
 * 不在词表里、id 空白各抛 {@link IllegalArgumentException}；跨组件归位冲突由 {@code SocialData} 构造期校验拒。
 */
public final class SeedGroupsHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = SocialPayloads.parse(payloadJson);
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    for (HexCoord at : SocialPayloads.requireGroupEntries(payload, 0L).locations().values()) {
      paths.add(ResourcePaths.social(at.q(), at.r()));
    }
    return List.copyOf(paths);
  }

  @Override
  public String type() {
    return "social.SeedGroups";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      long nowTick = state.meta().timestamp().tick();
      SocialPayloads.GroupEntries entries = SocialPayloads.requireGroupEntries(payload, nowTick);
      Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>(base.groups());
      groups.putAll(entries.groups()); // ★ 同 id 覆盖（见类注的覆盖语义）
      Map<HouseholdId, Household> households = new LinkedHashMap<>(base.households());
      int updatedHouseholds = 0;
      int overwrittenLots = 0;
      int assignedLots = 0;
      int autoAssignedLots = 0;

      // ① 声明家户：新建 / 更新位置与画像（成员表并集保留）。
      //   ★ 只有带画像的条目才是"声明"（SocialPayloads：引用既有家户的条目 profile=null）——引用不覆盖位置，
      //     位置由 ② 的 requireLocationMatches 校验；否则引用一个 Unit 家户会被静默搬回 hex。
      for (SocialPayloads.HouseholdDraft draft : entries.households().values()) {
        Household existing = households.get(draft.id());
        HouseholdLocation location = new HouseholdLocation.Hex(draft.hex());
        if (existing == null) {
          households.put(
              draft.id(),
              new Household(
                  draft.id(),
                  location,
                  draft.profile() == null
                      ? new HouseholdProfile(draft.id().value(), null, Map.of())
                      : draft.profile(),
                  Map.of(),
                  new HouseholdVitalRates(List.of())));
          EventLog.channel(SocialLog.command())
              .info(
                  LogEvent.of(
                      "HOUSEHOLD_CREATED",
                      SocialLogSource.SOCIAL_COMMAND,
                      "id",
                      draft.id(),
                      "location",
                      location,
                      "source",
                      "social.SeedGroups"));
        } else if (draft.profile() != null) {
          Household replaced = existing.withLocation(location).withProfile(draft.profile());
          households.put(draft.id(), replaced);
          updatedHouseholds++;
        }
      }

      // ② 归位：每个批次挂一个家户；P2-A 起份额 = 批次人数（多户拆分走 HouseholdBook.transferMembers）。
      for (Map.Entry<PeopleLotId, PopulationGroup> entry : entries.groups().entrySet()) {
        PeopleLotId lot = entry.getKey();
        HexCoord at = entries.locations().get(lot);
        HouseholdId declared = entries.householdOfLot().get(lot);
        List<HouseholdId> existingOwners = ownersOf(base.households(), lot);
        if (!existingOwners.isEmpty()) {
          // ★ 覆盖语义：批次已在状态里 ⇒ 份额跟着新 count 走（旧档/旧载荷是单户；多户份额不在本路径覆盖）。
          if (existingOwners.size() != 1) {
            throw new IllegalArgumentException(
                "批次 " + lot + " 已被多个家户按份额持有，SeedGroups 的整条覆盖不支持多户拆分: " + existingOwners);
          }
          HouseholdId existingOwner = existingOwners.get(0);
          if (declared != null && !declared.equals(existingOwner)) {
            throw new IllegalArgumentException(
                "批次 "
                    + lot
                    + " 已在家户 "
                    + existingOwner
                    + "，不能改挂到 "
                    + declared
                    + "（迁移请走 transferMembers）");
          }
          Household household = households.get(existingOwner);
          requireLocationMatches(household, at, lot);
          households.put(existingOwner, household.withMember(lot, entry.getValue().count()));
          overwrittenLots++;
          continue;
        }
        HouseholdId target = declared == null ? autoHouseholdId(households, at) : declared;
        assignedLots++;
        if (declared == null) {
          autoAssignedLots++;
        }
        Household household = households.get(target);
        if (household == null) {
          throw new IllegalArgumentException("批次 " + lot + " 指向不存在的家户: " + target);
        }
        requireLocationMatches(household, at, lot);
        households.put(target, household.withMember(lot, entry.getValue().count()));
      }

      if (SocialLog.command().isDebugEnabled()) {
        EventLog.channel(SocialLog.command())
            .debug(
                LogEvent.of(
                    "SOCIAL_SEED_GROUPS_PLAN",
                    SocialLogSource.SOCIAL_COMMAND,
                    "declaredHouseholds",
                    entries.households().size(),
                    "createdHouseholds",
                    households.size() - base.households().size(),
                    "updatedHouseholds",
                    updatedHouseholds,
                    "overwrittenLots",
                    overwrittenLots,
                    "assignedLots",
                    assignedLots,
                    "autoAssignedLots",
                    autoAssignedLots,
                    "source",
                    "social.SeedGroups"));
      }
      SocialData next =
          new SocialData(
              base.populations(),
              base.cities(),
              groups,
              households,
              base.populationEvents(),
              base.provisioning(),
              base.vitalRates(),
              base.vitalRemainders(),
              base.satietyPerMille(),
              base.fleeStates());
      EventLog.channel(SocialLog.command())
          .info(
              LogEvent.of(
                  "SOCIAL_SEED_GROUPS_APPLIED",
                  SocialLogSource.SOCIAL_COMMAND,
                  "households",
                  households.size(),
                  "created",
                  households.size() - base.households().size(),
                  "updated",
                  updatedHouseholds,
                  "groups",
                  groups.size(),
                  "source",
                  "social.SeedGroups"));
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(SocialLog.command())
          .info(
              LogEvent.of(
                  "SOCIAL_SEED_GROUPS_REJECTED",
                  SocialLogSource.SOCIAL_COMMAND,
                  "reason",
                  SocialPayloads.logReason(e.getMessage()),
                  "source",
                  "social.SeedGroups"));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 该批次的既有家户（保序；无 ⇒ 空表）。 */
  private static List<HouseholdId> ownersOf(
      Map<HouseholdId, Household> households, PeopleLotId lot) {
    List<HouseholdId> owners = new ArrayList<>();
    for (Household household : households.values()) {
      if (household.hasMember(lot)) {
        owners.add(household.id());
      }
    }
    return owners;
  }

  /**
   * 无 {@code household} 字段时的自动归位：该格恰有一个家户 ⇒ 并入；否则复用/新建 {@code hh:hex:<q>_<r>}。 （创世主路径始终显式给 {@code
   * household}；这里只服务旧载荷/单条命令的向后兼容。）
   */
  private static HouseholdId autoHouseholdId(Map<HouseholdId, Household> households, HexCoord at) {
    HouseholdId synthetic = HouseholdId.parse("hh:hex:" + at.q() + "_" + at.r());
    List<Household> atHex = new ArrayList<>();
    for (Household household : households.values()) {
      if (household.location() instanceof HouseholdLocation.Hex hex && hex.hex().equals(at)) {
        atHex.add(household);
      }
    }
    if (atHex.size() == 1) {
      return atHex.get(0).id();
    }
    if (households.get(synthetic) != null) {
      // 同格的第二个及以后的批次汇进同一个合成家户（桶 = 格）。
      return synthetic;
    }
    households.put(
        synthetic,
        new Household(
            synthetic,
            new HouseholdLocation.Hex(at),
            new HouseholdProfile(synthetic.value(), null, Map.of()),
            Map.of(),
            new HouseholdVitalRates(List.of())));
    EventLog.channel(SocialLog.command())
        .info(
            LogEvent.of(
                "HOUSEHOLD_CREATED",
                SocialLogSource.SOCIAL_COMMAND,
                "id",
                synthetic,
                "location",
                "HEX:" + at,
                "source",
                "social.SeedGroups(auto)"));
    return synthetic;
  }

  /** 家户位置与条目落点必须一致（HEX 家户）；UNIT 家户不接受 SeedGroups 的 {q,r}。 */
  private static void requireLocationMatches(Household household, HexCoord at, PeopleLotId lot) {
    if (household == null) {
      throw new IllegalArgumentException("批次 " + lot + " 的目标家户不存在");
    }
    if (!(household.location() instanceof HouseholdLocation.Hex hex)) {
      throw new IllegalArgumentException(
          "批次 " + lot + " 的目标家户 " + household.id() + " 不在 HEX 上（SeedGroups 的 {q,r} 只落 hex 家户）");
    }
    if (!hex.hex().equals(at)) {
      throw new IllegalArgumentException(
          "批次 " + lot + " 的落点 " + at + " 与家户 " + household.id() + " 的位置 " + hex.hex() + " 不符");
    }
  }
}
