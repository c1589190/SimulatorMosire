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
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code social.MovePopulationLots} 命令的处理器（P1.2 后端行政）：<b>按居住格/家户批量迁移人口批次</b>。
 *
 * <pre>{@code
 * // 把 hh:hex:33_-55 的全部批次搬到 (35,-60) 的那个家户（不存在则新建 hh:hex:35_-60）
 * {"fromHouseholdId":"hh:hex:33_-55","to":{"q":35,"r":-60},"reason":"旧都人口随迁"}
 *
 * // 只搬指定的两个批次（id 从 simos.social.population 的 groups.lots 读）
 * {"from":{"q":33,"r":-55},"toHouseholdId":"hh:hex:35_-60",
 *  "lots":["urban:c-33_-55:MALE:1","urban:c-33_-55:FEMALE:1"],"reason":"旧都人口随迁"}
 * }</pre>
 *
 * <p>★★ <b>源选择</b>：{@code fromHouseholdId} 与 {@code from} 二选一。<b>目标选择</b>：{@code toHouseholdId} 与
 * {@code to} 二选一。{@code lots} 可缺省——缺省 = 源范围的全部批次；显式给则必须是源范围当前持有的批次子集。
 *
 * <p>★★ <b>批次身份不变、位置随家户走</b>（2026-10-09 家户架构 §4.2）：底层用 {@link
 * HouseholdBook#transferMembers}，整个批次迁移时保留原 {@link PeopleLotId}；家户位置是唯一位置真值。 城籍（{@code
 * urban:&lt;cityId&gt;:} 前缀）<b>不因物理迁移而变</b>——若要与 {@code social.MoveCity} 配合做"城与人一起迁"， 请在 app
 * 组合根同批提交两条命令。
 *
 * <p>★ <b>目标落点未给家户时</b>：{@code to} 指向的格上有且仅有一个家户 ⇒ 并入它；一个都没有 ⇒ 新建合成家户 {@code
 * hh:hex:&lt;q&gt;_&lt;r&gt;}；多于一个 ⇒ 拒（不猜哪一个，"选错家户"是不可见的静默错误）。
 *
 * <p>★ <b>失败语义</b>：整条命令纯函数推导，任一校验失败都抛 {@link IllegalArgumentException}，由命令边界折成 {@code Rejected}、不留
 * revision、不会出现"搬了一半"。源家户搬空后仍保留为空壳（家户生命周期另有删除语义， 本命令不做隐式删除）。
 *
 * <p>★ <b>GM-only</b>：批量人口迁移是行政/迁移原语，不开放给决策令直调。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：2026-10-20 起走跨命名空间 {@link #targetResources}——源/目标**各自**
 * 可能给家户 id 或格：家户 ⇒ 该家户 SocialData 现值（social / unit）；格 ⇒ {@code social:<q>_<r>}。
 * 同一侧两个字段都给时两条都列出（更严，不静默漏判）；一侧都没给 ⇒ 该侧无目标；整条都没有可寻址目标 ⇒ 空列表（调用方 fail-closed 拒，与升级前同方向）。
 */
public final class MovePopulationLotsHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "social.MovePopulationLots";

  @Override
  public String type() {
    return TYPE;
  }

  /** 源 + 目标的跨命名空间目标：家户引用查 SocialData 现值，hex 引用走 social 路径；查无家户 ⇒ 具名拒。 */
  @Override
  public List<CommandTarget> targetResources(
      String commandType, SimulationState state, String mapId, String payloadJson) {
    JsonNode payload = SocialPayloads.parse(payloadJson);
    Set<CommandTarget> targets = new LinkedHashSet<>();
    String fromHouseholdText = SocialPayloads.optionalText(payload, "fromHouseholdId");
    HexCoord fromHex = SocialPayloads.optionalHex(payload, "from");
    if (fromHouseholdText != null) {
      targets.add(
          HouseholdCommandTargets.currentTarget(state, HouseholdId.parse(fromHouseholdText)));
    }
    if (fromHex != null) {
      targets.add(HouseholdCommandTargets.hexTarget(fromHex));
    }
    String toHouseholdText = SocialPayloads.optionalText(payload, "toHouseholdId");
    HexCoord toHex = SocialPayloads.optionalHex(payload, "to");
    if (toHouseholdText != null) {
      targets.add(HouseholdCommandTargets.currentTarget(state, HouseholdId.parse(toHouseholdText)));
    }
    if (toHex != null) {
      targets.add(HouseholdCommandTargets.hexTarget(toHex));
    }
    return List.copyOf(targets);
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = SocialPayloads.parse(payloadJson);
    Set<String> paths = new LinkedHashSet<>();
    HexCoord from = SocialPayloads.optionalHex(payload, "from");
    HexCoord to = SocialPayloads.optionalHex(payload, "to");
    if (from != null) {
      paths.add(ResourcePaths.social(from.q(), from.r()));
    }
    if (to != null) {
      paths.add(ResourcePaths.social(to.q(), to.r()));
    }
    return List.copyOf(paths);
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data();
    String fromForLog = null;
    String toForLog = null;
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      String reason = SocialPayloads.requireReason(payload);
      String fromHouseholdText = SocialPayloads.optionalText(payload, "fromHouseholdId");
      HexCoord fromHex = SocialPayloads.optionalHex(payload, "from");
      fromForLog =
          fromHouseholdText != null
              ? fromHouseholdText
              : (fromHex == null ? "-" : fromHex.toString());
      requireExactlyOneSource(fromHouseholdText, fromHex);
      String toHouseholdText = SocialPayloads.optionalText(payload, "toHouseholdId");
      HexCoord toHex = SocialPayloads.optionalHex(payload, "to");
      toForLog =
          toHouseholdText != null ? toHouseholdText : (toHex == null ? "-" : toHex.toString());
      requireExactlyOneTarget(toHouseholdText, toHex);
      List<PeopleLotId> requested = optionalLots(payload);

      List<Household> sourceHouseholds = resolveSource(base, fromHouseholdText, fromHex);
      if (sourceHouseholds.isEmpty()) {
        throw new IllegalArgumentException(
            "源范围里没有任何家户: " + (fromHouseholdText == null ? fromHex : fromHouseholdText));
      }
      Set<HouseholdId> sourceIds = new LinkedHashSet<>();
      for (Household household : sourceHouseholds) {
        sourceIds.add(household.id());
      }
      List<PeopleLotId> lots = resolveLots(base, sourceIds, requested);

      Target target = resolveTarget(base, toHouseholdText, toHex);
      if (sourceIds.contains(target.id())) {
        throw new IllegalArgumentException(
            "目标家户 " + target.id() + " 在源范围内；把批次搬进它自己的家户是无操作。请把要与它合并的批次/家户分开指定");
      }
      for (PeopleLotId lot : lots) {
        Household owner = requireOwner(base, lot);
        if (!sourceIds.contains(owner.id())) {
          throw new IllegalArgumentException("批次 " + lot + " 不在源范围内: " + owner.id());
        }
      }

      if (SocialLog.command().isDebugEnabled()) {
        EventLog.channel(SocialLog.command())
            .debug(
                LogEvent.of(
                    "SOCIAL_MOVE_POPULATION_LOTS_PLAN",
                    SocialLogSource.SOCIAL_COMMAND,
                    "from",
                    fromForLog,
                    "to",
                    toForLog,
                    "sourceHouseholds",
                    sourceIds.size(),
                    "lots",
                    lots.size(),
                    "targetHousehold",
                    target.id().value(),
                    "createdTarget",
                    target.created()));
      }
      SocialData working = target.created() ? target.workingState() : base;
      for (PeopleLotId lot : lots) {
        Household owner = requireOwner(working, lot);
        PopulationGroup group = requireGroup(working, lot);
        working =
            HouseholdBook.transferMembers(
                working, owner.id(), target.id(), lot, group.count(), reason);
      }
      if (working.equals(base)) {
        throw new IllegalArgumentException("social.MovePopulationLots 没有造成任何变化");
      }
      long movedPopulation = 0L;
      for (PeopleLotId lot : lots) {
        movedPopulation += requireGroup(base, lot).count();
      }
      EventLog.channel(SocialLog.command())
          .info(
              LogEvent.of(
                  "SOCIAL_MOVE_POPULATION_LOTS_APPLIED",
                  SocialLogSource.SOCIAL_COMMAND,
                  "from",
                  fromForLog,
                  "to",
                  toForLog,
                  "lots",
                  lots.size(),
                  "population",
                  movedPopulation,
                  "targetHousehold",
                  target.id().value(),
                  "createdTarget",
                  target.created()));
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, working));
    } catch (IllegalArgumentException e) {
      EventLog.channel(SocialLog.command())
          .info(
              LogEvent.of(
                  "SOCIAL_MOVE_POPULATION_LOTS_REJECTED",
                  SocialLogSource.SOCIAL_COMMAND,
                  "reason",
                  SocialPayloads.logReason(e.getMessage()),
                  "from",
                  fromForLog == null ? "-" : fromForLog,
                  "to",
                  toForLog == null ? "-" : toForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 源选择必须二选一（都不给 / 都给都拒，避免"看起来指定了、其实没指定"）。 */
  private static void requireExactlyOneSource(String householdText, HexCoord hex) {
    if ((householdText == null) == (hex == null)) {
      throw new IllegalArgumentException("必须且只能给 fromHouseholdId 与 from 之一（前者按家户，后者按居住格）");
    }
  }

  /** 目标选择必须二选一。 */
  private static void requireExactlyOneTarget(String householdText, HexCoord hex) {
    if ((householdText == null) == (hex == null)) {
      throw new IllegalArgumentException("必须且只能给 toHouseholdId 与 to 之一（前者按家户，后者按目标居住格）");
    }
  }

  /** 源家户：给 id ⇒ 只此一个；给格 ⇒ 该格全部 HEX 家户（含暂时空壳）。 */
  private static List<Household> resolveSource(
      SocialData base, String householdText, HexCoord fromHex) {
    if (householdText != null) {
      HouseholdId id = HouseholdId.parse(householdText);
      return List.of(base.requireHousehold(id));
    }
    return base.householdsAt(fromHex);
  }

  /** 待迁批次：缺省 = 源家户全部成员；显式给 ⇒ 逐个校验存在且确属源范围；重复/空表 ⇒ 拒。 */
  private static List<PeopleLotId> resolveLots(
      SocialData base, Set<HouseholdId> sourceIds, List<PeopleLotId> requested) {
    if (requested == null) {
      List<PeopleLotId> all = new ArrayList<>();
      for (HouseholdId sourceId : sourceIds) {
        Household household = base.requireHousehold(sourceId);
        for (PeopleLotId lot : household.memberLots()) {
          if (!all.contains(lot)) {
            all.add(lot);
          }
        }
      }
      if (all.isEmpty()) {
        throw new IllegalArgumentException("源范围内的家户都没有成员批次，没有可迁移的人口");
      }
      return List.copyOf(all);
    }
    for (PeopleLotId lot : requested) {
      if (!base.groups().containsKey(lot)) {
        throw new IllegalArgumentException("人口批次不存在: " + lot);
      }
      Household owner = requireOwner(base, lot);
      if (!sourceIds.contains(owner.id())) {
        throw new IllegalArgumentException("人口批次 " + lot + " 不在源范围内（当前家户 " + owner.id() + "）");
      }
    }
    return requested;
  }

  /** 目标家户：给 id ⇒ 必须存在；给格 ⇒ 恰一个家户则并入，没有则新建合成家户，多于一个 ⇒ 拒。 */
  private static Target resolveTarget(SocialData base, String householdText, HexCoord toHex) {
    if (householdText != null) {
      HouseholdId id = HouseholdId.parse(householdText);
      base.requireHousehold(id);
      return new Target(id, false, base);
    }
    List<Household> atHex = base.householdsAt(toHex);
    if (atHex.size() == 1) {
      return new Target(atHex.get(0).id(), false, base);
    }
    if (atHex.size() > 1) {
      throw new IllegalArgumentException(
          "目标格 " + toHex + " 上有 " + atHex.size() + " 个家户，无法判断并入哪一个；请显式给 toHouseholdId");
    }
    HouseholdId synthetic = HouseholdId.parse("hh:hex:" + toHex.q() + "_" + toHex.r());
    if (base.households().containsKey(synthetic)) {
      throw new IllegalArgumentException(
          "目标格 " + toHex + " 没有 HEX 家户，但合成 id " + synthetic + " 已被别处占用；请显式给 toHouseholdId");
    }
    SocialData created =
        HouseholdBook.create(
            base,
            synthetic,
            new HouseholdLocation.Hex(toHex),
            new HouseholdProfile(synthetic.value(), "movePopulationLots 自动新建", Map.of()),
            new HouseholdVitalRates(List.of()));
    return new Target(synthetic, true, created);
  }

  /** {@code lots} 可选数组：缺省/null ⇒ null（全量）；空数组 / 非数组 / 空元素 / 重复 ⇒ 拒。 */
  private static List<PeopleLotId> optionalLots(JsonNode payload) {
    JsonNode value = payload.get("lots");
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 lots 必须是 [批次 id…] 数组: " + payload);
    }
    if (value.isEmpty()) {
      throw new IllegalArgumentException("字段 lots 不得为空数组；要全量迁移请省掉该字段");
    }
    LinkedHashSet<PeopleLotId> lots = new LinkedHashSet<>();
    for (JsonNode element : value) {
      if (!element.isTextual()) {
        throw new IllegalArgumentException("字段 lots 的元素必须是批次 id 字符串: " + element);
      }
      PeopleLotId lot = PeopleLotId.parse(element.asText());
      if (!lots.add(lot)) {
        throw new IllegalArgumentException("字段 lots 不得含重复批次: " + lot);
      }
    }
    return List.copyOf(lots);
  }

  private static Household requireOwner(SocialData base, PeopleLotId lot) {
    return base.householdOfLot(lot)
        .orElseThrow(() -> new IllegalArgumentException("人口批次没有所属家户（坏状态）: " + lot));
  }

  private static PopulationGroup requireGroup(SocialData base, PeopleLotId lot) {
    PopulationGroup group = base.groups().get(lot);
    if (group == null) {
      throw new IllegalArgumentException("人口批次不存在: " + lot);
    }
    return group;
  }

  /** 目标家户 + 目标落点是否为本次新建（新建时 workingState 已含它）。 */
  private record Target(HouseholdId id, boolean created, SocialData workingState) {}
}
