package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code economy.RegisterGovernment}（P2-C §13.7）：<b>把一个 GOV 单位登记成"该单位恰一份政府 + 恰一个政府家户"</b> ——
 * 一条命令同时写三张表，三个身份由同一个 {@code govUnitId} 派生，任何"先到者胜/任意配对"都在命令边界被排除。
 *
 * <pre>{@code
 * {"govUnitId":"u-central",
 *  "governmentId"?:  "gov-unit-u-central",   // 缺省 = 派生值；给了必须逐字等于派生值
 *  "household"?:     "hh-gov-u-central",     // 缺省 = 派生值；给了必须逐字等于派生值
 *  "nationRef":"u-central",                  // 必填非空白（辖区引用；本命令不解释国家语义）
 *  "q":0,"r":0,                              // 必填：政府家户的 economy 落点（HouseholdEconomy.view.hex）
 *  "residence"?: "urban",                    // 缺省 urban；既有行缺席 = 保持既有视图
 *  "stratum"?:   "official",                 // 缺省 official（SocialClassId.OFFICIAL）；既有行缺席 = 保持
 *  "population"?: 0, "laborMilli"?: 0, "participationPerMille"?: 0,  // 缺省 = 新建时 0 / 既有行保留
 *  "classPosition"?: "…",                    // 可选的阶层归属（必须已存在）；不给 = 不写 HouseholdClassMembership
 *  "issuable"?: ["silver"],                  // 缺省空集（= 纯财政主体，不铸币）；铸币算法后置
 *  "seignioragePerCycle"?: 0, "debtIssuePerCycle"?: 0,
 *  "reason"?: "gm:…"}
 * }</pre>
 *
 * <p>★★ <b>写什么（三张表，一次 revision）</b>：
 *
 * <ol>
 *   <li>{@code classes}：给政府家户建/补一条 {@link HouseholdEconomy}（人口层/劳动预算/参与率）。缺行 ⇒ 新建（默认 0 人口、 0 劳动、0
 *       参与率、无债务/需求）；有行 ⇒ <b>只更新显式给的字段</b>，其余逐值保留；行内落点与载荷 {@code q/r} 不一致 ⇒ 具名拒（要搬家请走 {@code
 *       economy.MigrateHousehold}，本命令不静默挪行）；
 *   <li>{@code classStandings}：给了 {@code classPosition} 才写（位置必须已存在；当前位置 = 原所属 = 该位置，其余字段保留）； 不给 ⇒
 *       保持既有归属（没有就没有 —— "配置 Class"仍走 {@code economy.SetHouseholdClass} / {@code
 *       SetHouseholdParticipation}）；
 *   <li>{@code governments}：按派生 {@link GovernmentId} upsert，国库 = <b>该政府家户的账户</b> （{@link
 *       HouseholdActors#of(HouseholdId)}）。重复登记同一单位 = 幂等 upsert，不是第二个政府。
 * </ol>
 *
 * <p>★★ <b>身份的唯一拼写点</b>：家户 = {@link GovernmentHouseholds#of(String)}（{@code hh-gov-<govUnitId>}），政府
 * = {@link GovernmentIds#ofUnit(String)}（{@code gov-unit-<govUnitId>}）。{@code EconomyData}
 * 构造期再判一遍"单位政府的国库必须 是它自己的政府家户"，坏数据进不了世界。
 *
 * <p>★★ <b>账户不在这里建（诚实边界）</b>：账户归 {@code actor} 切片，经济域看不到它。政府家户的零余额账户由同批 {@code
 * actor.EnsureHouseholdAccount}（GM-only；组合工具 {@code simos.gov.createOffice} / {@code
 * simos.map.province.apply} 已固定成对写入）或既有 {@code actor.AdjustAccounts}（纯正增量）创建。缺账户时推进会在装载账本处
 * fail-closed，不会把"没有账户"当成"余额 0"。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand} —— 它写的是"谁是一级政府、国库在哪"的结构身份，不是日常家户经济配置； 仍注册到 Core、仍可被
 * GM 的 {@code simos.command.submit} 直接调用，但排除出决策令白名单。政府家户的日常配置 （Class/劳动/参与率/需求）由 P2-B 的四条非 GmOnly
 * 命令与 {@code economy.GmAdjust} 负责。
 *
 * <p>★ <b>目标声明</b>：本命令不声明资源路径（政府/国库不是 {@code economy} 命名空间的格键路径，且 GM-only 不进决策令）； 只做载荷形状校验。
 */
public final class EconomyRegisterGovernmentHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.RegisterGovernment";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    // 形状校验（缺 govUnitId/q/r/nationRef ⇒ 抛具名载荷错）；本命令没有可声明的格资源目标。
    parse(TYPE, payloadJson);
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      Registration registration = parse(TYPE, payloadJson);
      if (base.meta().isEmpty()) {
        // ★ 未激活的 economy 是"还没播种"：首条 economy.Seed 会整份覆写该切片（EconomySeedHandler 的首次分支），
        //   先登记的政府/HouseholdEconomy 会被静默抹掉 ⇒ 这里具名拒，要求先激活（先 economy.Seed 再登记 GOV）。
        return new HandlerOutcome.Rejected(
            TYPE + " 要求 economy 切片已激活（先 economy.Seed 播种再登记 GOV；未激活时登记会被首次播种覆写）");
      }
      long day = state.meta().timestamp().tick();
      String reason = registration.reason() == null ? "gm:" + TYPE : registration.reason();

      // ── ① classes：政府家户的人口层/劳动层行（缺 ⇒ 新建；有 ⇒ 只改显式字段）──────────────
      HouseholdEconomy existingHouseholdEconomy = base.classes().get(registration.household());
      HouseholdEconomy householdEconomy;
      if (existingHouseholdEconomy == null) {
        householdEconomy =
            new HouseholdEconomy(
                registration.household(),
                new CohortKey(registration.hex(), registration.residence(), registration.stratum()),
                registration.population(),
                registration.laborMilli(),
                registration.participationPerMille(),
                0L,
                List.of(),
                Map.of(),
                Map.of(),
                0L);
      } else {
        if (!existingHouseholdEconomy.view().hex().equals(registration.hex())) {
          return new HandlerOutcome.Rejected(
              "政府家户 "
                  + registration.household().value()
                  + " 的 economy 落点已在 "
                  + existingHouseholdEconomy.view().hex()
                  + "，与载荷 q/r="
                  + registration.hex()
                  + " 不一致；要搬家请走 economy.MigrateHousehold（本命令不静默挪行）");
        }
        // ★ residence/stratum：给了就必须与既有视图一致；缺席 = 保持既有（不把"补登记"变成视图重置）。
        if (registration.residenceSpecified()
            && registration.residence() != existingHouseholdEconomy.view().residence()) {
          return new HandlerOutcome.Rejected(
              "政府家户 "
                  + registration.household().value()
                  + " 的居住视图已在 "
                  + existingHouseholdEconomy.view().residence()
                  + "，与载荷 residence="
                  + registration.residence()
                  + " 不一致（视图是身份之外的现状，请显式迁移/另行配置）");
        }
        if (registration.stratumSpecified()
            && !registration.stratum().equals(existingHouseholdEconomy.view().stratum())) {
          return new HandlerOutcome.Rejected(
              "政府家户 "
                  + registration.household().value()
                  + " 的阶层视图已在 "
                  + existingHouseholdEconomy.view().stratum()
                  + "，与载荷 stratum="
                  + registration.stratum()
                  + " 不一致（视图是身份之外的现状，请显式迁移/另行配置）");
        }
        long population =
            registration.populationSpecified()
                ? registration.population()
                : existingHouseholdEconomy.population();
        long laborMilli =
            registration.laborMilliSpecified()
                ? registration.laborMilli()
                : existingHouseholdEconomy.laborMilli();
        int participation =
            registration.participationSpecified()
                ? registration.participationPerMille()
                : existingHouseholdEconomy.participationPerMille();
        householdEconomy =
            new HouseholdEconomy(
                existingHouseholdEconomy.id(),
                existingHouseholdEconomy.view(),
                population,
                laborMilli,
                participation,
                existingHouseholdEconomy.money(),
                existingHouseholdEconomy.debts(),
                existingHouseholdEconomy.naturalNeeds(),
                existingHouseholdEconomy.effectiveDemand(),
                existingHouseholdEconomy.cycleNaturalNeedMilli());
      }
      Map<HouseholdId, HouseholdEconomy> householdEconomies = new LinkedHashMap<>(base.classes());
      householdEconomies.put(registration.household(), householdEconomy);

      // ── ② classStandings：给了 productionRole 才写（位置引用必须存在）──────────────
      Map<HouseholdId, HouseholdClassMembership> classMemberships =
          new LinkedHashMap<>(base.classStandings());
      if (registration.classPositionId() != null) {
        ClassPositionId positionId = registration.classPositionId();
        ProductionRole position = base.classPositions().get(positionId);
        if (position == null) {
          return new HandlerOutcome.Rejected(
              TYPE
                  + " 的 classPosition 不存在（先 economy.GmAdjust.upsertClassPosition）: "
                  + positionId.value());
        }
        HouseholdClassMembership previousClassMembership =
            base.classStandings().get(registration.household());
        classMemberships.put(
            registration.household(),
            new HouseholdClassMembership(
                registration.household(),
                positionId,
                positionId,
                previousClassMembership == null
                    ? Set.of()
                    : previousClassMembership.participatingPositionIds(),
                previousClassMembership == null
                    ? Map.of()
                    : previousClassMembership.retainedShares(),
                previousClassMembership == null
                    ? 0L
                    : previousClassMembership.consecutiveDebtStressCycles(),
                day,
                reason));
      }

      // ── ③ governments：按派生 id upsert；国库 = 政府家户账户 ─────────────────────────
      GovernmentId governmentId = registration.governmentId();
      Government existingGovernment = base.governments().get(governmentId);
      if (existingGovernment != null
          && !existingGovernment.treasury().equals(HouseholdActors.of(registration.household()))) {
        return new HandlerOutcome.Rejected(
            "政府 "
                + governmentId.value()
                + " 已存在且国库不是它的政府家户（拒绝覆盖）: "
                + existingGovernment.treasury());
      }
      // ★ 重复登记 = 幂等 upsert：缺省字段在既有政府上**逐值保留**（不给 = 不改），不是静默清零。
      //   财政旋钮/可发行币种因此不会被一次"补登记"打回出厂值。
      Set<CurrencyId> issuable =
          registration.issuableSpecified()
              ? registration.issuable()
              : (existingGovernment == null ? Set.of() : existingGovernment.issuable());
      long seignioragePerCycle =
          registration.seigniorageSpecified()
              ? registration.seignioragePerCycle()
              : (existingGovernment == null ? 0L : existingGovernment.seignioragePerCycle());
      long debtIssuePerCycle =
          registration.debtIssueSpecified()
              ? registration.debtIssuePerCycle()
              : (existingGovernment == null ? 0L : existingGovernment.debtIssuePerCycle());
      for (Map.Entry<GovernmentId, Government> entry : base.governments().entrySet()) {
        if (entry.getKey().equals(governmentId)) {
          continue;
        }
        if (entry.getValue().treasury().equals(HouseholdActors.of(registration.household()))) {
          return new HandlerOutcome.Rejected(
              "政府家户 "
                  + registration.household().value()
                  + " 已经是政府 "
                  + entry.getKey().value()
                  + " 的国库；同一家户不能同时是两届政府的国库");
        }
      }
      Government government =
          new Government(
              governmentId,
              registration.nationRef(),
              HouseholdActors.of(registration.household()),
              issuable,
              seignioragePerCycle,
              debtIssuePerCycle);
      Map<GovernmentId, Government> governments = new LinkedHashMap<>(base.governments());
      governments.put(governmentId, government);

      EconomyData projected =
          base.withHouseholdEconomies(householdEconomies)
              .withClassMemberships(classMemberships)
              .withGovernments(governments);
      EventLog.channel(EconomyLog.enterprise())
          .info(
              LogEvent.of(
                  "GOVERNMENT_REGISTERED",
                  EconomyLogSource.ECONOMY_COMMAND,
                  "government",
                  governmentId.value(),
                  "govUnit",
                  registration.govUnitId(),
                  "household",
                  registration.household().value(),
                  "population",
                  householdEconomy.population(),
                  "laborMilli",
                  householdEconomy.laborMilli(),
                  "participationPerMille",
                  householdEconomy.participationPerMille(),
                  "issuable",
                  issuable,
                  "seignioragePerCycle",
                  seignioragePerCycle,
                  "debtIssuePerCycle",
                  debtIssuePerCycle,
                  "reasonLength",
                  reason == null ? 0 : reason.length()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /**
   * 已解析的一条登记：三个身份（政府/家户/单位）都由 {@code govUnitId} 派生，其余是配置字段。
   *
   * <p>构造期判完"派生一致性"与数值边界；{@code population}/{@code laborMilli}/{@code participationPerMille}
   * 的"给没给"用 {@code specified} 标记（缺省 = 保留既有，新建 = 0）。
   */
  private record Registration(
      String govUnitId,
      GovernmentId governmentId,
      HouseholdId household,
      String nationRef,
      HexCoord hex,
      ResidenceKind residence,
      boolean residenceSpecified,
      SocialClassId stratum,
      boolean stratumSpecified,
      long population,
      boolean populationSpecified,
      long laborMilli,
      boolean laborMilliSpecified,
      int participationPerMille,
      boolean participationSpecified,
      ClassPositionId classPositionId,
      Set<CurrencyId> issuable,
      boolean issuableSpecified,
      long seignioragePerCycle,
      boolean seigniorageSpecified,
      long debtIssuePerCycle,
      boolean debtIssueSpecified,
      String reason) {

    Registration {
      Objects.requireNonNull(govUnitId, "govUnitId");
      Objects.requireNonNull(governmentId, "governmentId");
      Objects.requireNonNull(household, "household");
      if (nationRef == null || nationRef.isBlank()) {
        throw new IllegalArgumentException("nationRef 不得为空白");
      }
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(residence, "residence");
      Objects.requireNonNull(stratum, "stratum");
      Objects.requireNonNull(issuable, "issuable");
      if (population < 0L) {
        throw new IllegalArgumentException("population 不得为负: " + population);
      }
      if (laborMilli < 0L) {
        throw new IllegalArgumentException("laborMilli 不得为负: " + laborMilli);
      }
      if (participationPerMille < 0 || participationPerMille > 1000) {
        throw new IllegalArgumentException(
            "participationPerMille 必须 ∈ [0,1000]: " + participationPerMille);
      }
      if (seignioragePerCycle < 0L) {
        throw new IllegalArgumentException("seignioragePerCycle 不得为负: " + seignioragePerCycle);
      }
      if (debtIssuePerCycle < 0L) {
        throw new IllegalArgumentException("debtIssuePerCycle 不得为负: " + debtIssuePerCycle);
      }
    }
  }

  /** 形状/边界解析（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  private static Registration parse(String command, String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(command, payloadJson);
    String govUnitId = EconomyCommandPayloads.requireText(command, payload, "govUnitId");
    GovernmentId derivedGovernment = GovernmentIds.ofUnit(govUnitId);
    HouseholdId derivedHousehold = GovernmentHouseholds.of(govUnitId);
    if (payload.hasNonNull("governmentId")) {
      GovernmentId given =
          GovernmentId.parse(EconomyCommandPayloads.requireText(command, payload, "governmentId"));
      if (!given.equals(derivedGovernment)) {
        throw new IllegalArgumentException(
            command
                + " 的 governmentId 必须逐字等于 govUnitId 的派生值 "
                + derivedGovernment.value()
                + "（一个 GOV 单位恰一份政府记录，不接受任意 id）: "
                + given.value());
      }
    }
    if (payload.hasNonNull("household")) {
      HouseholdId given =
          HouseholdId.parse(EconomyCommandPayloads.requireText(command, payload, "household"));
      if (!given.equals(derivedHousehold)) {
        throw new IllegalArgumentException(
            command
                + " 的 household 必须逐字等于 govUnitId 的派生值 "
                + derivedHousehold.value()
                + "（一个 GOV 单位恰一个政府家户，不接受任意配对）: "
                + given.value());
      }
    }
    String nationRef = EconomyCommandPayloads.requireText(command, payload, "nationRef");
    HexCoord hex =
        new HexCoord(
            EconomyCommandPayloads.requireInt(command, payload, "q"),
            EconomyCommandPayloads.requireInt(command, payload, "r"));
    boolean residenceSpecified = payload.hasNonNull("residence");
    ResidenceKind residence =
        residenceSpecified
            ? ResidenceKind.parse(EconomyCommandPayloads.requireText(command, payload, "residence"))
            : ResidenceKind.URBAN;
    boolean stratumSpecified = payload.hasNonNull("stratum");
    SocialClassId stratum =
        stratumSpecified
            ? SocialClassId.parse(EconomyCommandPayloads.requireText(command, payload, "stratum"))
            : SocialClassId.OFFICIAL;
    boolean populationSpecified = payload.hasNonNull("population");
    long population = EconomyCommandPayloads.optionalLong(command, payload, "population", 0L);
    boolean laborSpecified = payload.hasNonNull("laborMilli");
    long laborMilli = EconomyCommandPayloads.optionalLong(command, payload, "laborMilli", 0L);
    boolean participationSpecified = payload.hasNonNull("participationPerMille");
    int participation =
        EconomyCommandPayloads.optionalInt(command, payload, "participationPerMille", 0);
    ClassPositionId productionRole =
        payload.hasNonNull("classPosition")
            ? ClassPositionId.parse(
                EconomyCommandPayloads.requireText(command, payload, "classPosition"))
            : null;
    boolean issuableSpecified = payload.hasNonNull("issuable");
    Set<CurrencyId> issuable = parseIssuable(command, payload);
    boolean seigniorageSpecified = payload.hasNonNull("seignioragePerCycle");
    long seigniorage =
        EconomyCommandPayloads.optionalLong(command, payload, "seignioragePerCycle", 0L);
    boolean debtIssueSpecified = payload.hasNonNull("debtIssuePerCycle");
    long debtIssue = EconomyCommandPayloads.optionalLong(command, payload, "debtIssuePerCycle", 0L);
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(command, payload, "reason")
            : null;
    return new Registration(
        govUnitId,
        derivedGovernment,
        derivedHousehold,
        nationRef,
        hex,
        residence,
        residenceSpecified,
        stratum,
        stratumSpecified,
        population,
        populationSpecified,
        laborMilli,
        laborSpecified,
        participation,
        participationSpecified,
        productionRole,
        issuable,
        issuableSpecified,
        seigniorage,
        seigniorageSpecified,
        debtIssue,
        debtIssueSpecified,
        reason);
  }

  /** 可发行币种数组（缺省空集 = 非发行人；元素必须是非空白文本）。 */
  private static Set<CurrencyId> parseIssuable(String command, JsonNode payload) {
    JsonNode node = payload.get("issuable");
    if (node == null || node.isNull()) {
      return Set.of();
    }
    if (!node.isArray()) {
      throw new IllegalArgumentException(command + " 的 issuable 必须是字符串数组: " + node);
    }
    Set<CurrencyId> currencies = new LinkedHashSet<>();
    for (JsonNode element : node) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException(command + " 的 issuable 元素必须是非空白文本: " + element);
      }
      currencies.add(CurrencyId.parse(element.asText()));
    }
    return currencies;
  }

  /** 只读：本命令派生的政府 id（组合工具/测试共用同一份拼写点，避免第二处字面量）。 */
  public static String governmentIdOf(String govUnitId) {
    return GovernmentIds.ofUnit(govUnitId).value();
  }

  /** 只读：本命令派生的政府家户 id（组合工具/测试共用同一份拼写点）。 */
  public static String governmentHouseholdIdOf(String govUnitId) {
    return GovernmentHouseholds.of(govUnitId).value();
  }

  /** 只读：本命令的默认 reason 形态（未给 reason 时写进 HouseholdClassMembership 的审计文本）。 */
  public static String defaultReason() {
    return "gm:" + TYPE;
  }
}
