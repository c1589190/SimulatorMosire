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
 * ★★ <b>{@code economy.UnitRepay}（辖区 · 税/地方债阶段 7 第一段）：地方债还款原语</b>。载荷：
 *
 * <pre>{@code
 * {"unitId":"u-…","lenderId":"…","unit":"money"|"grain","amount":1200}
 * }</pre>
 *
 * <p>★★ <b>账本 = class-first 双边账户（计划 §4 用户裁定）</b>：只处理 {@code owner = unitId / counterparty =
 * lenderId} 的借款腿与它的镜像腿（身份由 {@link ClassFirstAccountId#idOf} 纯函数派生）。放贷方余额同步加 {@code amount}（money 加
 * {@code lender.money()}；grain 从 {@code lender.goods()} 拷贝上加）。两条腿各减 {@code amount}（借款腿 {@code +=
 * amount} 趋 0、镜像腿 {@code -= amount}）；清 0 ⇒ 双腿 {@code SETTLED}，否则按 {@code tick >= nextDueTick ? DUE
 * : ACTIVE} （镜像同状态）并**原样保留**各自 `terms/rate/nextDueTick/interestAccrued`。
 *
 * <p>★★ <b>校验（命令期逐条 fail-closed，拒因不静默）</b>：{@code classFirst} 非空（否则具名拒"只在 class-first 世界可用"）；{@code
 * lenderId} 必须在 {@code classFirst.lenders()} 里（拒因列出现有 id）；{@code unit} 只认 {@link PilotModel#MONEY}
 * / {@link PilotModel#GRAIN}（不做别名/大小写归一）；{@code amount ≥ 1}；借款腿必须存在且净额 &lt; 0
 * （否则具名拒"没有未结清的地方债"）；{@code amount > 未结清负债} ⇒ 具名拒（不超付、不找零）。★ 镜像腿必须存在且净额 = 借款腿净额的相反数（Σ=0），不成立 ⇒
 * {@link IllegalStateException}（状态损坏，**不得**被 {@code catch (IllegalArgumentException)} 折成 Rejected）。
 *
 * <p>★★ <b>写面</b>：只换 {@code classFirst} 的 {@code accounts} 与 {@code lenders} 两张表（其余组件逐值不变）， 交回
 * {@link EconomyChangeSet#between}；<b>不碰 actor</b>。工具批（{@code actor.AdjustAccounts} + {@code
 * economy.UnitRepay}）才是唯一受支持的完整调用面 —— 单提本命令会造成"国库已扣、放贷方未收"的悬空。
 *
 * <p>★★ <b>GM-only</b>：实现 {@link GmOnlyCommand} —— 仍注册到 Core、仍进 {@code commandTargets}、GM 的 {@code
 * simos.command.submit} 可提交；但组合根构造 {@code DirectiveWhitelist} / {@code RegisterEffect} 白名单 /
 * 决策人工具目录时排除它，普通 GOV Agent 无法把它写进令里执行。
 *
 * <p>★ <b>{@link CommandTargets} 的诚实边界</b>：{@code targetPaths(mapId, payloadJson)} 的签名拿不到状态；本命令的语义
 * 对象（放贷方 / 单位-放贷方双边账户）不是 economy 命名空间里的格键路径，且本命令 GM-only、不进入决策人令 ⇒ 有意返回空列表
 * （"没有可声明的目标"）。合法载荷返回空列表，坏载荷仍抛具名 {@link IllegalArgumentException}（先做形状校验）。
 */
public final class UnitRepayHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点：工具、catalog 提示与 Shell 注册都从这里取/对齐）。 */
  public static final String TYPE = "economy.UnitRepay";

  @Override
  public String type() {
    return TYPE;
  }

  /**
   * ★ 本命令没有可声明的资源目标（见类注）：放贷方与双边账户都不是 economy 命名空间里的格键路径，且本命令 GM-only、不进入决策人令。
   * 这里只做载荷形状校验（必填/类型/范围；坏载荷抛具名 {@link IllegalArgumentException}），合法载荷返回空列表；引用存在性 （classFirst /
   * lender / 借款腿 / 镜像腿）在 {@link #handle} 里判、由 catch 折成 {@link HandlerOutcome.Rejected}。
   */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(payloadJson);
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
      // ★ 只折"形状/语义问题"；镜像腿缺失/不对称等状态损坏抛 IllegalStateException，必须当场炸而不是 Rejected。
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

    ClassFirstAccountId debtId =
        ClassFirstAccountId.idOf(request.unitId(), request.lenderId(), request.unit());
    ClassFirstAccountId mirrorId =
        ClassFirstAccountId.idOf(request.lenderId(), request.unitId(), request.unit());
    ClassFirstAccount debt = state.accounts().get(debtId);
    if (debt == null || debt.cumulativeNet() >= 0L) {
      throw new IllegalArgumentException(TYPE + " 没有未结清的地方债: " + debtId.value());
    }
    long outstanding = Math.negateExact(debt.cumulativeNet());
    if (request.amount() > outstanding) {
      throw new IllegalArgumentException(
          TYPE + " 的 amount 超过未结清负债（不超付、不找零）：amount=" + request.amount() + "，负债=" + outstanding);
    }

    ClassFirstAccount mirror = state.accounts().get(mirrorId);
    if (mirror == null) {
      // ★ 状态损坏：借款腿存在（净额 < 0）而镜像腿缺失，双边账户 Σ=0 的同批不变式被破坏。
      throw new IllegalStateException(
          TYPE
              + " 状态损坏：镜像腿 "
              + mirrorId.value()
              + " 不存在（借款腿 "
              + debtId.value()
              + " 净额="
              + debt.cumulativeNet()
              + "；双边账户必须同批落、Σ=0）");
    }
    long expectedMirror = Math.negateExact(debt.cumulativeNet());
    if (mirror.cumulativeNet() != expectedMirror) {
      // ★ 状态损坏：两腿净额不互为相反数。
      throw new IllegalStateException(
          TYPE
              + " 状态损坏：镜像腿 "
              + mirrorId.value()
              + " 的 cumulativeNet="
              + mirror.cumulativeNet()
              + " ≠ 借款腿净额取反 "
              + expectedMirror
              + "（双边账户必须同批落、Σ=0）");
    }

    long debtAfter = debt.cumulativeNet() + request.amount();
    long mirrorAfter = mirror.cumulativeNet() - request.amount();
    PilotModel.AccountStatus status =
        debtAfter == 0L
            ? PilotModel.AccountStatus.SETTLED
            : (tick >= debt.nextDueTick()
                ? PilotModel.AccountStatus.DUE
                : PilotModel.AccountStatus.ACTIVE);
    ClassFirstAccount debtLeg =
        new ClassFirstAccount(
            debtId,
            request.unitId(),
            request.lenderId(),
            request.unit(),
            debt.terms(),
            debt.interestRatePerMille(),
            debt.nextDueTick(),
            debtAfter,
            debt.interestAccrued(),
            status);
    ClassFirstAccount mirrorLeg =
        new ClassFirstAccount(
            mirrorId,
            request.lenderId(),
            request.unitId(),
            request.unit(),
            mirror.terms(),
            mirror.interestRatePerMille(),
            mirror.nextDueTick(),
            mirrorAfter,
            mirror.interestAccrued(),
            status);

    PilotModel.Lender nextLender = creditLender(lender, request);
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
    // ★ 为什么不是 ClassFirstState.withAccounts/withLenders：两个 wither 都只接受替换**既有键**，而本命令要 upsert
    //   借款腿与镜像腿两条身份；直接走 canonical 构造器，语义上仍只换这两张表。
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

  /** 放贷方收回款项：money 直接加；grain 从 goods 拷贝上加（0 保留，不归一）。 */
  private static PilotModel.Lender creditLender(PilotModel.Lender lender, Request request) {
    if (PilotModel.MONEY.equals(request.unit())) {
      return new PilotModel.Lender(
          lender.id(),
          Math.addExact(lender.money(), request.amount()),
          lender.goods(),
          lender.interestRatePerMille(),
          lender.nextDueTick(),
          lender.collectionPower());
    }
    LinkedHashMap<String, Long> goods = new LinkedHashMap<>(lender.goods());
    goods.put(
        PilotModel.GRAIN,
        Math.addExact(goods.getOrDefault(PilotModel.GRAIN, 0L), request.amount()));
    return new PilotModel.Lender(
        lender.id(),
        lender.money(),
        goods,
        lender.interestRatePerMille(),
        lender.nextDueTick(),
        lender.collectionPower());
  }

  /** 载荷解析（targetPaths 直接吃 JSON 文本；handle 先 parseObject 再复用）。 */
  private static Request parse(String payloadJson) {
    return parse(EconomyCommandPayloads.parseObject(TYPE, payloadJson));
  }

  /** 载荷形状/范围校验（handler 与 targetPaths 共用；只做静态可判的部分，不动状态）。 */
  private static Request parse(JsonNode payload) {
    String unitId = EconomyCommandPayloads.requireText(TYPE, payload, "unitId");
    String lenderId = EconomyCommandPayloads.requireText(TYPE, payload, "lenderId");
    String unit = EconomyCommandPayloads.requireText(TYPE, payload, "unit");
    requireKnownUnit(unit);
    long amount = EconomyCommandPayloads.requireLong(TYPE, payload, "amount");
    if (amount < 1L) {
      throw new IllegalArgumentException(TYPE + " 的 amount 必须 >= 1: " + amount);
    }
    return new Request(unitId, lenderId, unit, amount);
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

  /** 还款请求的已校验形状。 */
  private record Request(String unitId, String lenderId, String unit, long amount) {}
}
