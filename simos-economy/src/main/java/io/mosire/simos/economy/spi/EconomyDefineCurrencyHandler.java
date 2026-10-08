package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.money.CurrencyDef;
import io.mosire.simos.economy.api.money.InstrumentKind;
import io.mosire.simos.economy.api.money.MoneyInstrument;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
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
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code economy.DefineCurrency}（A1 2026-10-08；约束设计书 §3.1-3）：<b>定义一种新币种，并把它登记为某个 GOV 发行的钱</b> ——
 * 一次写三处：币种表（{@code currencies}）+ 工具表（{@code moneyInstruments}）+ 该政府的 {@code issuable}，一条命令 = 一条
 * revision（铁律 2）。
 *
 * <pre>{@code
 * {"govUnitId":"u-central",   // 必填：发行这种钱的 GOV 单位（决策人侧由身份派生，GM 侧显式给）
 *  "currencyId":"copper",     // 必填：币种 id（= 不可变身份；建立之后只能用 RenameCurrency 改显示名）
 *  "scale":3,                 // 必填：最小单位精度（≥ 0）
 *  "displayName":"铜",        // 必填：显示名（非空白、不要求唯一）
 *  "reason"?: "…"}            // 可选：审计文本（只进日志，不进状态）
 * }</pre>
 *
 * <p>★★ <b>为什么"定义币种"必须同时登记发行人</b>：{@code MoneyIssuance.requireIssuerOf} 的判据是"某个已登记的 {@code
 * MoneyAuthority} 声称发行它"，而本仓的发行人就是 {@code Government.issuable}（E3 起）。若本命令只写词表、不登记发行人，
 * 新币种就<b>永远发不出来</b>（{@code economy.RecordMoneyIssuance} 会具名拒）—— 世界会得到一个"有名字、没人能发的钱"。
 * 故两件事在<b>同一条命令</b>里做完，没有中间态。**代价如实记**：这一步等于把"发行权"授予该 GOV —— 所以它由 GM 审批链把关 （窄工具 {@code
 * simos.gov.defineCurrency}），且同一条命令<b>不</b>能改别人已发行的币种（"一币一发行人"）。
 *
 * <p>★★ <b>币种身份一旦建立不可改</b>（铁律 1 / I16）：{@code currencyId} 已存在 ⇒ <b>具名拒</b> （{@code
 * currency-already-defined}，要改显示名走 {@code economy.RenameCurrency}）；该币种已被别的政府发行 ⇒ 具名拒（{@code
 * currency-already-issued}）。
 *
 * <p>★ <b>工具（instrument）自动成对建立</b>：币种 id {@code X} ⇒ 工具 id {@code X-specie}（{@link
 * InstrumentKind#SPECIE}，无发行人、无兑现人 —— 与 {@code silver-specie} 逐字同形）。本批<b>不</b>接受工具档参数： {@code
 * STATE_NOTE} / {@code BANK_DEPOSIT} 的价值是对发行人的索取（必须有发行人），那属后续批次 —— 本批只做金属币这一档，不做"为了让词表好看而编造制度"。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand}（排除出令白名单 / {@code RegisterEffect} / 决策人命令目录三条路；
 * 命令总线本身仍可被窄工具提交）。决策人侧的受控入口是窄工具 {@code simos.gov.defineCurrency}（身份派生 + 只能自己的 GOV + GM 审批链），与
 * {@code gov.SetAdministrationPlan} 的既有形制同源。★ 不声明格资源（世界级词表没有格）⇒ {@link CommandTargets#targetPaths}
 * 恒空。
 */
public final class EconomyDefineCurrencyHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.DefineCurrency";

  private static final LogChannel LOG = EventLog.channel(EconomyLog.command());

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(payloadJson); // 形状校验（缺字段 ⇒ 抛具名载荷错）；本命令没有格资源目标。
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    Definition definition;
    try {
      definition = parse(payloadJson);
    } catch (IllegalArgumentException e) {
      return rejected("bad-payload", null, EconomyCommandPayloads.logReason(e.getMessage()));
    }
    try {
      if (base.meta().isEmpty()) {
        return rejected("economy-not-activated", definition, "economy 切片尚未激活（先 economy.Seed 播种）");
      }
      GovernmentId governmentId = GovernmentIds.ofUnit(definition.govUnitId());
      Government government = base.governments().get(governmentId);
      if (government == null) {
        return rejected(
            "government-not-registered",
            definition,
            "GOV 单位未登记为政府（先 economy.RegisterGovernment）: " + governmentId.value());
      }
      CurrencyId currencyId = new CurrencyId(definition.currencyId());
      CurrencyDef existing = base.currencies().get(currencyId);
      if (existing != null) {
        return rejected(
            "currency-already-defined",
            definition,
            "币种 "
                + currencyId.value()
                + " 已存在（现有显示名="
                + existing.displayName()
                + "；改显示名请走 "
                + EconomyRenameCurrencyHandler.TYPE
                + "）");
      }
      for (Map.Entry<GovernmentId, Government> entry : base.governments().entrySet()) {
        if (entry.getValue().issuable().contains(currencyId)) {
          return rejected(
              "currency-already-issued",
              definition,
              "币种 " + currencyId.value() + " 已由另一个政府发行: " + entry.getKey().value());
        }
      }
      InstrumentId instrumentId = specieInstrumentId(currencyId);
      if (base.moneyInstruments().containsKey(instrumentId)) {
        return rejected("instrument-id-taken", definition, "工具 id 已被占用: " + instrumentId.value());
      }

      CurrencyDef def =
          new CurrencyDef(currencyId.value(), definition.scale(), definition.displayName());
      MoneyInstrument instrument =
          new MoneyInstrument(
              instrumentId, currencyId, InstrumentKind.SPECIE, Optional.empty(), Optional.empty());
      Map<CurrencyId, CurrencyDef> currencies = new LinkedHashMap<>(base.currencies());
      currencies.put(currencyId, def);
      Map<InstrumentId, MoneyInstrument> instruments = new LinkedHashMap<>(base.moneyInstruments());
      instruments.put(instrumentId, instrument);
      Map<GovernmentId, Government> governments = new LinkedHashMap<>(base.governments());
      Set<CurrencyId> issuable = new LinkedHashSet<>(government.issuable());
      issuable.add(currencyId);
      // ★★ A2a：只换发行权集合 —— 走 withIssuable 而不是手抄 6 个字段：后者在政府新增组件（本批的
      //   officialRates）时会把那个组件静默抹掉，而"定义币种"与"官方汇率"看起来毫无关系（本仓最贵的那类 bug）。
      governments.put(governmentId, government.withIssuable(issuable));

      EconomyData projected =
          base.withCurrencies(currencies)
              .withMoneyInstruments(instruments)
              .withGovernments(governments);
      LOG.info(
          LogEvent.of(
              "CURRENCY_DEFINED",
              EconomyLogSource.ECONOMY_MONEY,
              "currency",
              currencyId.value(),
              "scale",
              def.scale(),
              "displayName",
              def.displayName(),
              "instrument",
              instrumentId.value(),
              "instrumentKind",
              InstrumentKind.SPECIE.name(),
              "government",
              governmentId.value(),
              "govUnit",
              definition.govUnitId(),
              "issuableSize",
              issuable.size(),
              "reasonLength",
              definition.reason() == null ? 0 : definition.reason().length()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return rejected(
          "contract-violation", definition, EconomyCommandPayloads.logReason(e.getMessage()));
    }
  }

  /**
   * 具名拒（业务拒绝 = INFO 一条"发生了什么 + 具名原因"，字段级细节 = DEBUG 一条"为什么"，见 §一.9 的级别约定）。
   *
   * @param reason 稳定拒因短名（进日志、便于 grep）
   * @param definition 已解析的定义（载荷坏到解析不出时传 null）
   * @param message 面向模型/人的一句话
   */
  private static HandlerOutcome.Rejected rejected(
      String reason, Definition definition, String message) {
    LOG.info(
        LogEvent.of(
            "CURRENCY_DEFINE_REJECTED",
            EconomyLogSource.ECONOMY_MONEY,
            "reason",
            reason,
            "currency",
            definition == null ? "" : definition.currencyId(),
            "govUnit",
            definition == null ? "" : definition.govUnitId()));
    LOG.debug(
        LogEvent.of(
            "CURRENCY_DEFINE_REJECTED_DETAIL",
            EconomyLogSource.ECONOMY_MONEY,
            "reason",
            reason,
            "scale",
            definition == null ? -1 : definition.scale(),
            "displayName",
            definition == null ? "" : definition.displayName(),
            "message",
            message));
    return new HandlerOutcome.Rejected(TYPE + " 具名拒（" + reason + "）: " + message);
  }

  /** 币种 ⇒ 它的金属币工具 id（唯一拼写点；与 {@code silver-specie} 同形）。 */
  public static InstrumentId specieInstrumentId(CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    return new InstrumentId(currency.value() + "-specie");
  }

  /** 形状/边界解析（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  private static Definition parse(String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    String govUnitId = EconomyCommandPayloads.requireText(TYPE, payload, "govUnitId");
    String currencyId = EconomyCommandPayloads.requireText(TYPE, payload, "currencyId");
    int scale = EconomyCommandPayloads.requireInt(TYPE, payload, "scale");
    String displayName = EconomyCommandPayloads.requireText(TYPE, payload, "displayName");
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
            : null;
    return new Definition(govUnitId, currencyId, scale, displayName, reason);
  }

  /** 一条定义：三个业务字段 + 可选审计文本（构造期判完边界 —— 与 {@link CurrencyDef} 的守卫同一口径）。 */
  private record Definition(
      String govUnitId, String currencyId, int scale, String displayName, String reason) {

    Definition {
      Objects.requireNonNull(govUnitId, "govUnitId");
      Objects.requireNonNull(currencyId, "currencyId");
      Objects.requireNonNull(displayName, "displayName");
      if (govUnitId.isBlank()) {
        throw new IllegalArgumentException("govUnitId 不得为空白");
      }
      if (currencyId.isBlank()) {
        throw new IllegalArgumentException("currencyId 不得为空白");
      }
      if (displayName.isBlank()) {
        throw new IllegalArgumentException("displayName 不得为空白（说不出名字就给 id 同字）");
      }
      if (scale < 0) {
        throw new IllegalArgumentException("scale 是最小单位精度、不得为负: " + scale);
      }
      if (reason != null && reason.isBlank()) {
        throw new IllegalArgumentException("reason 给了就必须非空白");
      }
    }
  }
}
