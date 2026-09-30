package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassFirstAccountId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.ClassFirstAccount;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.PilotModel;
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
 * ★★ <b>{@code economy.UnitBorrow}（辖区 · 税/地方债阶段 7 第一段）：地方债借入原语</b>。载荷：
 *
 * <pre>{@code
 * {"unitId":"u-…","lenderId":"…","unit":"money"|"grain","principal":5000,
 *  "interestRatePerMille":20,"nextDueTick":400,"terms":"unit-debt"?}
 * }</pre>
 *
 * <p>★★ <b>账本 = class-first 双边账户（计划 §4 用户裁定）</b>：借款腿 {@code owner = unitId / counterparty =
 * lenderId / cumulativeNet = -principal}，镜像腿 {@code owner = lenderId / counterparty = unitId /
 * cumulativeNet = +principal}；两条同批落，身份由 {@link ClassFirstAccountId#idOf} 纯函数派生。 放贷方余额同步减 {@code
 * principal}（money 减 {@code lender.money()}；grain 从 {@code lender.goods()} 拷贝上减，0 保留、不归一）。引擎的
 * {@code rollAccounts} 会对负净额腿自动滚动计息、到 {@code nextDueTick} 标 {@code DUE}；催收循环只收 owner 是阶层池的账 ⇒
 * 单位债不会被自动催收（催收后置正是这个原因，本阶段不做）。
 *
 * <p>★★ <b>校验（命令期逐条 fail-closed，拒因不静默）</b>：{@code classFirst} 非空（否则具名拒"只在 class-first 世界可用"）；{@code
 * lenderId} 必须在 {@code classFirst.lenders()} 里（拒因列出现有 id）；{@code unit} 只认 {@link PilotModel#MONEY}
 * / {@link PilotModel#GRAIN}（不做别名/大小写归一）；{@code principal ≥ 1}；{@code interestRatePerMille ≥
 * 0}；{@code nextDueTick > 当前 tick}（{@code state.meta().timestamp().tick()}）；{@code principal}
 * 超过放贷方可贷量 ⇒ 具名拒（带数字，不截断）；同一 {@code (unitId, lenderId, unit)} 已有未结清腿 ⇒ 具名拒并指路 {@code
 * unit.repayDebt}（"本批一次一笔"）；已结清（净额 0，SETTLED）身份可复用重开（覆盖 terms/rate/due/net/status）。★ 镜像腿存在且净额非 0
 * 而借款腿可开 ⇒ {@link IllegalStateException}（双边账户 Σ=0 被破坏是状态损坏，**不得**被 {@code catch
 * (IllegalArgumentException)} 折成 Rejected）。
 *
 * <p>★★ <b>写面</b>：只换 {@code classFirst} 的 {@code accounts} 与 {@code lenders} 两张表（其余组件逐值不变）， 交回
 * {@link EconomyChangeSet#between}；<b>不碰 actor</b>。工具批（{@code economy.UnitBorrow} + {@code
 * actor.AdjustAccounts}）才是唯一受支持的完整调用面 —— 单提本命令会造成"放贷方已扣、国库未收"的悬空。
 *
 * <p>★★ <b>GM-only</b>：实现 {@link GmOnlyCommand} —— 仍注册到 Core、仍进 {@code commandTargets}、GM 的 {@code
 * simos.command.submit} 可提交；但组合根构造 {@code DirectiveWhitelist} / {@code RegisterEffect} 白名单 /
 * 决策人工具目录时排除它，普通 GOV Agent 无法把它写进令里执行。
 *
 * <p>★ <b>{@link CommandTargets} 的诚实边界</b>：{@code targetPaths(mapId, payloadJson)} 的签名拿不到状态；本命令的语义
 * 对象（放贷方 / 单位-放贷方双边账户）不是 economy 命名空间里的格键路径，且本命令 GM-only、不进入决策人令 ⇒ 有意返回空列表
 * （"没有可声明的目标"）。合法载荷返回空列表，坏载荷仍抛具名 {@link IllegalArgumentException}（先做形状校验）。
 */
public final class UnitBorrowHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点：工具、catalog 提示与 Shell 注册都从这里取/对齐）。 */
  public static final String TYPE = "economy.UnitBorrow";

  /** {@code terms} 缺省值（唯一拼写点）。 */
  public static final String TERMS_UNIT_DEBT = "unit-debt";

  @Override
  public String type() {
    return TYPE;
  }

  /**
   * ★ 本命令没有可声明的资源目标（见类注）：放贷方与双边账户都不是 economy 命名空间里的格键路径，且本命令 GM-only、不进入决策人令。
   * 这里只做载荷形状校验（必填/类型/范围；坏载荷抛具名 {@link IllegalArgumentException}），合法载荷返回空列表；引用存在性 （classFirst /
   * lender / 已有腿）在 {@link #handle} 里判、由 catch 折成 {@link HandlerOutcome.Rejected}。
   */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(EconomyCommandPayloads.parseObject(TYPE, payloadJson));
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      return new HandlerOutcome.Applied(project(base, payload, state.meta().timestamp().tick()));
    } catch (IllegalArgumentException e) {
      // ★ 只折"形状/语义问题"；镜像腿不一致等状态损坏抛 IllegalStateException，必须当场炸而不是 Rejected。
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 纯函数项目：解析 → 校验 → 只换 accounts/lenders 两张表 → 变更集。 */
  private static EconomyChangeSet project(EconomyData base, JsonNode payload, long tick) {
    Request request = parse(payload);
    ClassFirstState state = base.classFirst();
    if (state.isEmpty()) {
      throw new IllegalArgumentException(TYPE + " 只在 class-first 世界可用（classFirst 为空/未播种）");
    }
    ExternalLenderId lenderKey = ExternalLenderId.of(request.lenderId());
    PilotModel.Lender lender = state.lenders().get(lenderKey);
    if (lender == null) {
      throw new IllegalArgumentException(
          TYPE + " 放贷方不存在: " + request.lenderId() + "；现有放贷方: " + existingLenderIds(state));
    }
    if (request.principal() < 1L) {
      throw new IllegalArgumentException(TYPE + " 的 principal 必须 >= 1: " + request.principal());
    }
    if (request.interestRatePerMille() < 0L) {
      throw new IllegalArgumentException(
          TYPE + " 的 interestRatePerMille 必须 >= 0: " + request.interestRatePerMille());
    }
    if (request.nextDueTick() <= tick) {
      throw new IllegalArgumentException(
          TYPE
              + " 的 nextDueTick 必须大于当前 tick: nextDueTick="
              + request.nextDueTick()
              + "，当前 tick="
              + tick);
    }

    long available =
        PilotModel.MONEY.equals(request.unit())
            ? lender.money()
            : lender.goods().getOrDefault(PilotModel.GRAIN, 0L);
    if (request.principal() > available) {
      throw new IllegalArgumentException(
          TYPE
              + " 放贷方可贷 "
              + request.unit()
              + " 不足（不截断）：principal="
              + request.principal()
              + "，available="
              + available);
    }

    ClassFirstAccountId debtId =
        ClassFirstAccountId.idOf(request.unitId(), request.lenderId(), request.unit());
    ClassFirstAccountId mirrorId =
        ClassFirstAccountId.idOf(request.lenderId(), request.unitId(), request.unit());
    ClassFirstAccount existingDebt = state.accounts().get(debtId);
    if (existingDebt != null && existingDebt.cumulativeNet() != 0L) {
      throw new IllegalArgumentException(
          TYPE
              + " 已存在未结清的地方债（先 unit.repayDebt 清账；本批一次一笔）: "
              + debtId.value()
              + " 净额="
              + existingDebt.cumulativeNet());
    }
    ClassFirstAccount existingMirror = state.accounts().get(mirrorId);
    if (existingMirror != null && existingMirror.cumulativeNet() != 0L) {
      // ★ 状态损坏：一条腿已结清/不存在而镜像腿仍有净额，双边账户 Σ=0 的同批不变式被破坏。
      throw new IllegalStateException(
          TYPE
              + " 状态损坏：镜像腿 "
              + mirrorId.value()
              + " 的 cumulativeNet="
              + existingMirror.cumulativeNet()
              + " 非 0（借款腿 "
              + debtId.value()
              + (existingDebt == null ? " 不存在" : " 已结清")
              + "；双边账户必须同批落、Σ=0）");
    }

    PilotModel.Lender nextLender = debitLender(lender, request);
    ClassFirstAccount debtLeg =
        new ClassFirstAccount(
            debtId,
            request.unitId(),
            request.lenderId(),
            request.unit(),
            request.terms(),
            request.interestRatePerMille(),
            request.nextDueTick(),
            Math.negateExact(request.principal()),
            0L,
            PilotModel.AccountStatus.ACTIVE);
    ClassFirstAccount mirrorLeg =
        new ClassFirstAccount(
            mirrorId,
            request.lenderId(),
            request.unitId(),
            request.unit(),
            request.terms(),
            request.interestRatePerMille(),
            request.nextDueTick(),
            request.principal(),
            0L,
            PilotModel.AccountStatus.ACTIVE);

    LinkedHashMap<ClassFirstAccountId, ClassFirstAccount> accounts =
        new LinkedHashMap<>(state.accounts());
    accounts.put(debtId, debtLeg);
    accounts.put(mirrorId, mirrorLeg);
    LinkedHashMap<ExternalLenderId, PilotModel.Lender> lenders =
        new LinkedHashMap<>(state.lenders());
    lenders.put(lenderKey, nextLender);
    return EconomyChangeSet.between(
        base, base.withClassFirst(withDebtTables(state, accounts, lenders)));
  }

  /** 只换 {@code accounts}/{@code lenders} 两张表（其余八个组件原引用传入 canonical 构造器统一冻结）。 */
  private static ClassFirstState withDebtTables(
      ClassFirstState state,
      Map<ClassFirstAccountId, ClassFirstAccount> accounts,
      Map<ExternalLenderId, PilotModel.Lender> lenders) {
    // ★ 为什么不是 ClassFirstState.withAccounts/withLenders：两个 wither 都只接受替换**既有键**，而本命令要新增
    //   两条借款腿（借款腿/镜像腿可能本不存在）；直接走 canonical 构造器，语义上仍只换这两张表。
    return new ClassFirstState(
        state.modeParticipations(),
        state.classPools(),
        state.householdAccounts(),
        state.assetStateSchemas(),
        state.classBounds(),
        state.mobilityPolicies(),
        state.classFlowEvents(),
        accounts,
        lenders,
        state.meta());
  }

  /** 放贷方扣本金：money 直接减；grain 从 goods 拷贝上减（0 保留，不归一）。 */
  private static PilotModel.Lender debitLender(PilotModel.Lender lender, Request request) {
    if (PilotModel.MONEY.equals(request.unit())) {
      return new PilotModel.Lender(
          lender.id(),
          Math.subtractExact(lender.money(), request.principal()),
          lender.goods(),
          lender.interestRatePerMille(),
          lender.nextDueTick(),
          lender.collectionPower());
    }
    LinkedHashMap<String, Long> goods = new LinkedHashMap<>(lender.goods());
    goods.put(
        PilotModel.GRAIN,
        Math.subtractExact(goods.getOrDefault(PilotModel.GRAIN, 0L), request.principal()));
    return new PilotModel.Lender(
        lender.id(),
        lender.money(),
        goods,
        lender.interestRatePerMille(),
        lender.nextDueTick(),
        lender.collectionPower());
  }

  /** 载荷形状/范围校验（handler 与 targetPaths 共用；只做静态可判的部分，不动状态）。 */
  private static Request parse(JsonNode payload) {
    String unitId = EconomyCommandPayloads.requireText(TYPE, payload, "unitId");
    String lenderId = EconomyCommandPayloads.requireText(TYPE, payload, "lenderId");
    String unit = EconomyCommandPayloads.requireText(TYPE, payload, "unit");
    requireKnownUnit(unit);
    long principal = EconomyCommandPayloads.requireLong(TYPE, payload, "principal");
    if (principal < 1L) {
      throw new IllegalArgumentException(TYPE + " 的 principal 必须 >= 1: " + principal);
    }
    long interestRatePerMille =
        EconomyCommandPayloads.requireLong(TYPE, payload, "interestRatePerMille");
    if (interestRatePerMille < 0L) {
      throw new IllegalArgumentException(
          TYPE + " 的 interestRatePerMille 必须 >= 0: " + interestRatePerMille);
    }
    long nextDueTick = EconomyCommandPayloads.requireLong(TYPE, payload, "nextDueTick");
    String terms = EconomyCommandPayloads.optionalText(TYPE, payload, "terms", TERMS_UNIT_DEBT);
    return new Request(unitId, lenderId, unit, principal, interestRatePerMille, nextDueTick, terms);
  }

  /** unit 词表：只认 money/grain 两个字面量（不做别名、不做大小写归一）。 */
  private static void requireKnownUnit(String unit) {
    if (!PilotModel.MONEY.equals(unit) && !PilotModel.GRAIN.equals(unit)) {
      throw new IllegalArgumentException(
          TYPE
              + " 的 unit 只认 \""
              + PilotModel.MONEY
              + "\"/\""
              + PilotModel.GRAIN
              + "\"（不做别名/大小写归一）: "
              + unit);
    }
  }

  /** 现有放贷方 id 清单（拒因要点名现状；保序取自 state.lenders() 的 LinkedHashMap 序）。 */
  private static List<String> existingLenderIds(ClassFirstState state) {
    return state.lenders().values().stream().map(PilotModel.Lender::id).toList();
  }

  /** 借入请求的已校验形状。 */
  private record Request(
      String unitId,
      String lenderId,
      String unit,
      long principal,
      long interestRatePerMille,
      long nextDueTick,
      String terms) {}
}
