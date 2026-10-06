package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code economy.RegisterHousehold}（P3 2026-10-12）：<b>给任意 Social 家户补一条 {@link HouseholdEconomy}
 * 经济行</b>——经济侧的人口/劳动/需求物化视图落点。新 UNIT 家户由 {@code simos.unit.raiseUnit} 在同批内登记，
 * 否则次日推进会在 {@code CLASSROW_POPULATION_PROJECTION_UNRESOLVED} 处 fail-closed。
 *
 * <pre>{@code
 * {"household":"hh-unit:u-1",
 *  "q":0,"r":0,                              // 必填：经济行视图落点（HouseholdEconomy.view.hex）
 *  "residence"?: "urban|rural",              // 缺省 urban；词表外即拒（大小写敏感）
 *  "stratum"?:   "landless_laborer",         // 缺省 landless_laborer（词表内）
 *  "participationPerMille"?: 0,              // 缺省 0；仅"行已存在"时按显式给的更新
 *  "reason"?:    "raise-unit:u-1"}
 * }</pre>
 *
 * <p>★★ <b>行为（一条命令只写 {@code classes} 一张表）</b>：
 *
 * <ol>
 *   <li>行不存在 ⇒ 新建 {@link HouseholdEconomy}：{@code view=(q,r|residence|stratum)}、{@code population=0}、
 *       {@code laborMilli=0}、{@code money=0}、空债务/空需求/空周期累计；{@code participationPerMille} 取载荷值；
 *   <li>行已存在 ⇒ <b>幂等 + 视图守卫</b>：{@code q/r/residence/stratum}（缺省已归一）与既有 view 不一致 ⇒ 具名拒
 *       （要搬迁走 {@code economy.MigrateHousehold}，本命令不静默挪行）；一致 ⇒ 只按<b>显式</b>
 *       {@code participationPerMille} 更新，其余字段逐值保留；
 *   <li><b>不创建</b> {@code FlowRow}、{@code HouseholdClassMembership}、政府、账户——账户归 actor 切片，由同批
 *       {@code actor.EnsureHouseholdAccount} 补；成员真值始终在 Social。
 * </ol>
 *
 * <p>★ <b>{@code q/r} 不要求该格有产业</b>：{@code EconomyData} 只在 flows 行存在时要求 view 有产业；本命令建的是零人口登记行，
 * 没有 flow。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand} —— 它写的是经济身份注册（结构写口），排除出决策令白名单 / RegisterEffect /
 * 决策人目录；仍注册到 Core、GM 的 {@code simos.command.submit} 与 app 组合工具（{@code simos.unit.raiseUnit}）在 GM
 * 授权上下文中可直接调用。
 *
 * <p>★ <b>目标声明</b>：本命令是 GM-only 身份原语，与 {@code economy.RegisterGovernment} 同款只做载荷形状校验，返回空列表。
 */
public final class EconomyRegisterHouseholdHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.RegisterHousehold";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    // 形状校验（缺 household/q/r ⇒ 抛具名载荷错）；本命令没有可声明的格资源目标。
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

      HouseholdEconomy existing = base.classes().get(registration.household());
      HouseholdEconomy registered;
      if (existing == null) {
        registered =
            new HouseholdEconomy(
                registration.household(),
                new CohortKey(registration.hex(), registration.residence(), registration.stratum()),
                0L,
                0L,
                registration.participationPerMille(),
                0L,
                List.of(),
                Map.of(),
                Map.of(),
                0L);
      } else {
        if (!existing.view().hex().equals(registration.hex())) {
          return new HandlerOutcome.Rejected(
              "家户 "
                  + registration.household().value()
                  + " 的 economy 落点已在 "
                  + existing.view().hex()
                  + "，与载荷 q/r="
                  + registration.hex()
                  + " 不一致；要搬家请走 economy.MigrateHousehold（本命令不静默挪行）");
        }
        if (registration.residence() != existing.view().residence()) {
          return new HandlerOutcome.Rejected(
              "家户 "
                  + registration.household().value()
                  + " 的居住视图已在 "
                  + existing.view().residence()
                  + "，与载荷（缺省或显式）residence="
                  + registration.residence()
                  + " 不一致（视图是身份之外的现状，请显式迁移/另行配置）");
        }
        if (!registration.stratum().equals(existing.view().stratum())) {
          return new HandlerOutcome.Rejected(
              "家户 "
                  + registration.household().value()
                  + " 的阶层视图已在 "
                  + existing.view().stratum()
                  + "，与载荷（缺省或显式）stratum="
                  + registration.stratum()
                  + " 不一致（视图是身份之外的现状，请显式迁移/另行配置）");
        }
        int participation =
            registration.participationSpecified()
                ? registration.participationPerMille()
                : existing.participationPerMille();
        // ★ 逐字段构造只改 participationPerMille；其余字段原样带过（HouseholdEconomy 目前没有
        //   "只改参与率"的 with 方法，且本批禁止改它的持久形状）。
        registered =
            new HouseholdEconomy(
                existing.id(),
                existing.view(),
                existing.population(),
                existing.laborMilli(),
                participation,
                existing.money(),
                existing.debts(),
                existing.naturalNeeds(),
                existing.effectiveDemand(),
                existing.cycleNaturalNeedMilli());
      }

      Map<HouseholdId, HouseholdEconomy> householdEconomies = new LinkedHashMap<>(base.classes());
      householdEconomies.put(registration.household(), registered);
      EconomyData projected = base.withHouseholdEconomies(householdEconomies);
      EconomyLog.enterprise()
          .info(
              "event=HOUSEHOLD_REGISTERED household={} q={} r={} residence={} stratum={}"
                  + " participationPerMille={} created={} reason={}",
              registration.household().value(),
              registration.hex().q(),
              registration.hex().r(),
              registered.view().residence().value(),
              registered.view().stratum().value(),
              registered.participationPerMille(),
              existing == null,
              registration.reason());
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /**
   * 已解析的一条家户登记：{@code q/r/residence/stratum} 是"归一后的视图"（缺省在此补 urban / landless_laborer），
   * {@code participationSpecified} 标记"载荷是否显式给了参与率"（缺省 = 新建取 0、既有限有值保留）。
   */
  private record Registration(
      HouseholdId household,
      HexCoord hex,
      ResidenceKind residence,
      SocialClassId stratum,
      int participationPerMille,
      boolean participationSpecified,
      String reason) {

    Registration {
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(residence, "residence");
      Objects.requireNonNull(stratum, "stratum");
      if (participationPerMille < 0 || participationPerMille > 1000) {
        throw new IllegalArgumentException(
            "participationPerMille 必须 ∈ [0,1000]: " + participationPerMille);
      }
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason 必须是非空白文本");
      }
    }
  }

  /** 形状/边界解析（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  private static Registration parse(String command, String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(command, payloadJson);
    HouseholdId household =
        HouseholdId.parse(EconomyCommandPayloads.requireText(command, payload, "household"));
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
            : SocialClassId.LANDLESS_LABORER;
    boolean participationSpecified = payload.hasNonNull("participationPerMille");
    int participation =
        EconomyCommandPayloads.optionalInt(command, payload, "participationPerMille", 0);
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(command, payload, "reason")
            : defaultReason();
    return new Registration(
        household, hex, residence, stratum, participation, participationSpecified, reason);
  }

  /** 本命令的默认 reason 形态（未给 reason 时写进审计日志的文本）。 */
  public static String defaultReason() {
    return "gm:" + TYPE;
  }
}
