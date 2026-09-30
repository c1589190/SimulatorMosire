package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassFirstAccountId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.ClassFirstAccount;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.spi.UnitBorrowHandler;
import io.mosire.simos.economy.spi.UnitRepayHandler;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code simos.unit.issueDebt} / {@code simos.unit.repayDebt} 的<b>纯推导</b>（辖区 · 税/地方债阶段 7 第二段 /
 * 计划 §4）： 从一份 {@link SimulationState} 与参数算出借入 / 还款的完整执行计划——<b>不碰 {@link
 * io.mosire.agentlib.tool.ToolContext}、不碰 {@code CoreSimos}</b>，preview 与 apply 因此共用同一份语义（工具只负责读态、
 * 组批、折叠结局）。
 *
 * <p>★★ <b>账本口径（计划 §4 的用户裁定）</b>：账本 = class-first 双边账户。借入腿 {@code owner = unitId / counterparty =
 * lenderId / cumulativeNet = -principal}，镜像腿 {@code owner = lenderId / counterparty = unitId /
 * cumulativeNet = +principal}；两条同批落（由 7a 的 {@link UnitBorrowHandler} / {@link UnitRepayHandler}
 * 负责），本类只推导" 现在能不能落、落完长什么样"。★ 钱 / 粮只在放贷方（economy）与单位国库 actor 账之间移动；本类<b>不</b>动 classFirst 的库存，
 * 也<b>不</b>改动 7a 的两条 handler。
 *
 * <p>★★ <b>为什么推导与载荷在同一个类</b>：两条工具的三条命令载荷（{@code economy.UnitBorrow} / {@code actor.AdjustAccounts}
 * / {@code sd.PutInfo}）都是这份计划的纯函数；把载荷组装留在工具里会多出一条"视图与载荷各读一次 Plan 字段" 的缝（漏一个字段没有症状）。载荷一律用 {@link
 * LinkedHashMap} 保序构造、{@link ToolSupport#json} 序列化 ⇒ 同状态同参数逐字节相同。
 *
 * <p>★ <b>负额语义</b>：{@code ClassFirstAccount.cumulativeNet} 里"单位负债"是<b>负数</b>；但本类对外（Plan 字段与行动记录）
 * 一律用<b>正数"未结清负债"</b>（借入前 / 借入后、还款前 / 还款后），免得读的人要心算符号。★ 国库可支配、放贷方可贷同理都是正数量。
 *
 * <p>★ <b>拒因分工（逐条中文、带数字与指路）</b>：参数 / 引用 / 量不足 ⇒ {@link IllegalArgumentException}（工具折 {@code
 * BAD_REQUEST}）；双边账户被破坏（镜像腿非 0 / 缺失 / 不对称） ⇒ {@link IllegalStateException} （与 7a handler
 * 同口径：状态损坏必须响亮，不得被折成"参数问题"）。★ 单位、国库落点、放贷方、可贷量、既有腿、国库可支配全部在<b>推导期</b> 判完 ⇒ preview 与 apply
 * 见同一组判据，apply 的批不会因为"工具没问"而半路被域层拒。
 */
final class UnitDebtPlan {

  /** 借入命令类型（唯一拼写点取自 7a handler；工具照此组装信封，模型够不着 type）。 */
  static final String BORROW_TYPE = UnitBorrowHandler.TYPE;

  /** 还款命令类型（唯一拼写点取自 7a handler）。 */
  static final String REPAY_TYPE = UnitRepayHandler.TYPE;

  /** 国库入 / 出账的命令类型（与 {@code AdjustAccountsHandler.type()} 同字面；该 handler 未导出常量）。 */
  static final String ADJUST_ACCOUNTS_TYPE = "actor.AdjustAccounts";

  /** 行动记录的命令类型（{@code PutInfoHandler.type()}）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 粮的商品 id（{@link PilotModel#GRAIN} 的<b>唯一</b>字面量来源；本类不另写 {@code "grain"}）。 */
  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);

  private UnitDebtPlan() {}

  // ── 借入 ───────────────────────────────────────────────────────────────────────────

  /**
   * 借入的纯推导（口径见类注）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 借入主体（军事单位；国库 = {@code ActorRef(UNIT, unitId)}）
   * @param lenderId 放贷方 id（必须已在 {@code classFirst.lenders()} 里；阶层池债权人分支本批具名拒）
   * @param unit 计价单位：只认 {@code money}/{@code grain}
   * @param principal 本金（&ge; 1；不得超过放贷方可贷量，不截断）
   * @param interestRatePerMille 利率（千分；&ge; 0，0 = 无息）
   * @param nextDueTick 下次到期 tick（必须 &gt; 当前 tick）
   * @param terms 条款词（非空白；工具缺省取 {@link UnitBorrowHandler#TERMS_UNIT_DEBT}）
   * @throws IllegalArgumentException 任一具名拒（工具折成 {@code BAD_REQUEST}）
   * @throws IllegalStateException 镜像腿非 0（状态损坏；工具折成 {@code TOOL_ERROR}，不静默）
   */
  static IssuePlan planIssue(
      SimulationState state,
      String unitId,
      String lenderId,
      String unit,
      long principal,
      long interestRatePerMille,
      long nextDueTick,
      String terms) {
    Objects.requireNonNull(state, "state");
    requireKnownUnit(BORROW_TYPE, unit);
    if (principal < 1L) {
      throw new IllegalArgumentException(BORROW_TYPE + " 的 principal 必须 >= 1: " + principal);
    }
    if (interestRatePerMille < 0L) {
      throw new IllegalArgumentException(
          BORROW_TYPE + " 的 interestRatePerMille 必须 >= 0: " + interestRatePerMille);
    }
    requireNonBlank(terms, "terms");
    SimosTimestamp at = state.meta().timestamp();
    long tick = at.tick();
    if (nextDueTick <= tick) {
      throw new IllegalArgumentException(
          BORROW_TYPE
              + " 的 nextDueTick 必须大于当前 tick: nextDueTick="
              + nextDueTick
              + "，当前 tick="
              + tick
              + "（先 unit.advance 或改 nextDueTick）");
    }
    UnitState units = ToolSupport.unitState(state);
    UnitId id = UnitId.parse(unitId);
    if (!units.units().containsKey(id)) {
      throw new IllegalArgumentException("单位不存在: " + unitId + "（先 unit.CreateUnit，或改用有效 unitId）");
    }
    HexCoord treasuryLocation = requireTreasuryLocation(units, id, at, unitId);

    EconomyData economy = ToolSupport.economyData(state);
    ClassFirstState classFirst = economy.classFirst();
    requireClassFirst(BORROW_TYPE, classFirst);
    PilotModel.Lender lender = requireLender(BORROW_TYPE, classFirst, lenderId);
    long lenderAvailableBefore = lendableOf(lender, unit);
    if (principal > lenderAvailableBefore) {
      throw new IllegalArgumentException(
          BORROW_TYPE
              + " 放贷方可贷 "
              + unit
              + " 不足（不截断）：principal="
              + principal
              + "，available="
              + lenderAvailableBefore
              + "（先增加放贷方可贷量或降低 principal）");
    }

    ClassFirstAccountId debtId = ClassFirstAccountId.idOf(unitId, lenderId, unit);
    ClassFirstAccountId mirrorId = ClassFirstAccountId.idOf(lenderId, unitId, unit);
    ClassFirstAccount existingDebt = classFirst.accounts().get(debtId);
    if (existingDebt != null && existingDebt.cumulativeNet() != 0L) {
      throw new IllegalArgumentException(
          BORROW_TYPE
              + " 已存在未结清的地方债（先 simos.unit.repayDebt 清账；本批一次一笔）: "
              + debtId.value()
              + " 净额="
              + existingDebt.cumulativeNet());
    }
    ClassFirstAccount existingMirror = classFirst.accounts().get(mirrorId);
    if (existingMirror != null && existingMirror.cumulativeNet() != 0L) {
      // ★ 状态损坏：借款腿可开（不存在或已结清）而镜像腿仍有净额 ⇒ 双边账户 Σ=0 的同批不变式被破坏。
      //   工具层同样 fail-closed：不许预览/提交到一条注定把镜像覆盖成相反数的批上。
      throw new IllegalStateException(
          BORROW_TYPE
              + " 状态损坏：镜像腿 "
              + mirrorId.value()
              + " 的 cumulativeNet="
              + existingMirror.cumulativeNet()
              + " 非 0（借款腿 "
              + debtId.value()
              + (existingDebt == null ? " 不存在" : " 已结清")
              + "；双边账户必须同批落、Σ=0）");
    }

    long lenderAvailableAfter = Math.subtractExact(lenderAvailableBefore, principal);
    // ★ 借前负债 = 既有借款腿净额的相反数；上面已保证它不存在或为 0（SETTLED 身份可复用重开）。
    long debtBefore = existingDebt == null ? 0L : Math.negateExact(existingDebt.cumulativeNet());
    return new IssuePlan(
        unitId,
        lenderId,
        unit,
        principal,
        interestRatePerMille,
        nextDueTick,
        terms,
        tick,
        treasuryLocation,
        debtBefore,
        principal,
        lenderAvailableBefore,
        lenderAvailableAfter);
  }

  // ── 还款 ───────────────────────────────────────────────────────────────────────────

  /**
   * 还款的纯推导（口径见类注）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 还款主体（军事单位；国库 = {@code ActorRef(UNIT, unitId)}）
   * @param lenderId 放贷方 id（必须已在 {@code classFirst.lenders()} 里）
   * @param unit 计价单位：只认 {@code money}/{@code grain}
   * @param amount 还款额（&ge; 1；不得超过未结清负债，不超付、不找零）
   * @throws IllegalArgumentException 任一具名拒（工具折成 {@code BAD_REQUEST}）
   * @throws IllegalStateException 镜像腿缺失 / 净额不对称（状态损坏；工具折成 {@code TOOL_ERROR}，不静默）
   */
  static RepayPlan planRepay(
      SimulationState state, String unitId, String lenderId, String unit, long amount) {
    Objects.requireNonNull(state, "state");
    requireKnownUnit(REPAY_TYPE, unit);
    if (amount < 1L) {
      throw new IllegalArgumentException(REPAY_TYPE + " 的 amount 必须 >= 1: " + amount);
    }
    SimosTimestamp at = state.meta().timestamp();
    long tick = at.tick();
    UnitState units = ToolSupport.unitState(state);
    UnitId id = UnitId.parse(unitId);
    if (!units.units().containsKey(id)) {
      throw new IllegalArgumentException("单位不存在: " + unitId + "（先 unit.CreateUnit，或改用有效 unitId）");
    }
    HexCoord treasuryLocation = requireTreasuryLocation(units, id, at, unitId);

    EconomyData economy = ToolSupport.economyData(state);
    ClassFirstState classFirst = economy.classFirst();
    requireClassFirst(REPAY_TYPE, classFirst);
    PilotModel.Lender lender = requireLender(REPAY_TYPE, classFirst, lenderId);

    ClassFirstAccountId debtId = ClassFirstAccountId.idOf(unitId, lenderId, unit);
    ClassFirstAccountId mirrorId = ClassFirstAccountId.idOf(lenderId, unitId, unit);
    ClassFirstAccount debt = classFirst.accounts().get(debtId);
    if (debt == null || debt.cumulativeNet() >= 0L) {
      throw new IllegalArgumentException(
          REPAY_TYPE + " 没有未结清的地方债: " + debtId.value() + "（先 simos.unit.issueDebt 借入；本批一次一笔）");
    }
    long outstandingBefore = Math.negateExact(debt.cumulativeNet());
    if (amount > outstandingBefore) {
      throw new IllegalArgumentException(
          REPAY_TYPE
              + " 的 amount 超过未结清负债（不超付、不找零）：amount="
              + amount
              + "，负债="
              + outstandingBefore
              + "（先查当前负债或分批还）");
    }

    ClassFirstAccount mirror = classFirst.accounts().get(mirrorId);
    long expectedMirror = Math.negateExact(debt.cumulativeNet());
    if (mirror == null) {
      // ★ 状态损坏：借款腿存在（净额 < 0）而镜像腿缺失。
      throw new IllegalStateException(
          REPAY_TYPE
              + " 状态损坏：镜像腿 "
              + mirrorId.value()
              + " 不存在（借款腿 "
              + debtId.value()
              + " 净额="
              + debt.cumulativeNet()
              + "；双边账户必须同批落、Σ=0）");
    }
    if (mirror.cumulativeNet() != expectedMirror) {
      // ★ 状态损坏：两腿净额不互为相反数。
      throw new IllegalStateException(
          REPAY_TYPE
              + " 状态损坏：镜像腿 "
              + mirrorId.value()
              + " 的 cumulativeNet="
              + mirror.cumulativeNet()
              + " ≠ 借款腿净额取反 "
              + expectedMirror
              + "（双边账户必须同批落、Σ=0）");
    }

    // 国库可支配：唯一算法走 AvailableStock（余额 − 冻结），本类不另写减法；缺账 ⇒ 具名拒（带数字）。
    ActorRef treasuryOwner = new ActorRef(ActorKind.UNIT, unitId);
    GoodsAccountKey treasuryKey = new GoodsAccountKey(treasuryOwner, treasuryLocation);
    ActorData actors = ApiViews.actorData(state);
    GoodsAccount treasury = actors.accounts().get(treasuryKey);
    if (treasury == null) {
      throw new IllegalArgumentException(
          "国库账不存在，无法还款：owner="
              + treasuryOwner
              + "，格="
              + hexText(treasuryLocation)
              + "，维度="
              + unit
              + "；amount="
              + amount
              + "，available=0（缺账不可负增量；先 simos.unit.issueDebt 借入或辖区抽取把款落进国库）");
    }
    long treasuryAvailableBefore = availableOf(treasury, unit);
    if (treasuryAvailableBefore < amount) {
      throw new IllegalArgumentException(
          "国库可支配 "
              + unit
              + " 不足：unit="
              + unitId
              + "，格="
              + hexText(treasuryLocation)
              + "，amount="
              + amount
              + "，available="
              + treasuryAvailableBefore
              + "，缺口="
              + (amount - treasuryAvailableBefore)
              + "（不部分、不截断；先补国库或降低 amount）");
    }

    long lenderAvailableBefore = lendableOf(lender, unit);
    return new RepayPlan(
        unitId,
        lenderId,
        unit,
        amount,
        tick,
        treasuryLocation,
        outstandingBefore,
        Math.subtractExact(outstandingBefore, amount),
        treasuryAvailableBefore,
        Math.subtractExact(treasuryAvailableBefore, amount),
        lenderAvailableBefore,
        Math.addExact(lenderAvailableBefore, amount));
  }

  // ── 引用 / 数值校验小件 ─────────────────────────────────────────────────────────────

  /** unit 词表：只认 money/grain 两个字面量（不做别名、不做大小写归一；与 7a handler 同口径）。 */
  private static void requireKnownUnit(String command, String unit) {
    if (!PilotModel.MONEY.equals(unit) && !PilotModel.GRAIN.equals(unit)) {
      throw new IllegalArgumentException(
          command
              + " 的 unit 只认 \""
              + PilotModel.MONEY
              + "\"/\""
              + PilotModel.GRAIN
              + "\"（不做别名/大小写归一）: "
              + unit);
    }
  }

  /** 国库落点 = 单位<b>当刻有效位置</b>；无位置 ⇒ 具名拒并指路 {@code unit.PlaceAt}。 */
  private static HexCoord requireTreasuryLocation(
      UnitState units, UnitId id, SimosTimestamp at, String unitId) {
    return units
        .effectivePosition(id, at)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "单位 " + unitId + " 当刻没有有效位置，国库落点无法确定；先 unit.PlaceAt 把单位放到地图上"));
  }

  /** {@code classFirst} 非空（否则 7a 两条原语都会在域层拒；这里提前给出同一句具名拒因）。 */
  private static void requireClassFirst(String command, ClassFirstState classFirst) {
    if (classFirst.isEmpty()) {
      throw new IllegalArgumentException(command + " 只在 class-first 世界可用（classFirst 为空/未播种）");
    }
  }

  /** 放贷方必须在 {@code classFirst.lenders()} 里；拒因列出现有 id（保序取自 LinkedHashMap）。 */
  private static PilotModel.Lender requireLender(
      String command, ClassFirstState classFirst, String lenderId) {
    ExternalLenderId lenderKey = ExternalLenderId.of(lenderId);
    PilotModel.Lender lender = classFirst.lenders().get(lenderKey);
    if (lender == null) {
      throw new IllegalArgumentException(
          command
              + " 放贷方不存在: "
              + lenderId
              + "；现有放贷方: "
              + existingLenderIds(classFirst)
              + "（先给 classFirst.lenders 加放贷方；阶层池债权人分支本批具名拒）");
    }
    return lender;
  }

  private static List<String> existingLenderIds(ClassFirstState classFirst) {
    return classFirst.lenders().values().stream().map(PilotModel.Lender::id).toList();
  }

  /** 放贷方可贷量（money = 现钱；grain = 粮食账户，0 保留）：与 7a handler 同一算法、同一缺省。 */
  private static long lendableOf(PilotModel.Lender lender, String unit) {
    return PilotModel.MONEY.equals(unit)
        ? lender.money()
        : lender.goods().getOrDefault(PilotModel.GRAIN, 0L);
  }

  /** 国库该维度的可支配量（唯一算法 = {@link AvailableStock#available}，本类不另写减法）。 */
  private static long availableOf(GoodsAccount account, String unit) {
    return PilotModel.MONEY.equals(unit)
        ? AvailableStock.available(account, MoneyVocabulary.SILVER_CURRENCY)
        : AvailableStock.available(account, GRAIN);
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " 必须是非空文本");
    }
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0L) {
      throw new IllegalArgumentException(name + " 不得为负: " + value);
    }
  }

  /** 格的可读文本（拒因与行动记录共用；格式不与任何资源路径语法绑定）。 */
  private static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }

  /**
   * 单位 canonical 地址（{@code unit:<unitId>}）：只经 {@link Address#parse} → {@link Address#canonical()}（与
   * {@code LevyRegionTool}/{@code RejectDirectiveTool} 同款），两条债务工具共用这一个拼写点。
   */
  static String unitAddress(String unitId) {
    Objects.requireNonNull(unitId, "unitId");
    return Address.parse(ToolSupport.UNIT_NAMESPACE + ":" + unitId).canonical();
  }

  // ── Plan：借入 ─────────────────────────────────────────────────────────────────────

  /**
   * 借入计划（全部字段是状态的纯函数）。
   *
   * @param unitId 借入主体
   * @param lenderId 放贷方
   * @param unit money | grain
   * @param principal 本金（&ge; 1）
   * @param interestRatePerMille 利率（千分；&ge; 0）
   * @param nextDueTick 下次到期 tick（&gt; {@code tick}）
   * @param terms 条款词（非空白）
   * @param tick 推导时的世界日
   * @param treasuryLocation 国库落点 = 单位当刻有效位置
   * @param debtBefore 借前未结清负债（正数；既有腿不存在或已结清 ⇒ 0）
   * @param debtAfter 借后未结清负债（正数）
   * @param lenderAvailableBefore 放贷方可贷量（借前）
   * @param lenderAvailableAfter 放贷方可贷量（借后；= 借前 − principal）
   */
  record IssuePlan(
      String unitId,
      String lenderId,
      String unit,
      long principal,
      long interestRatePerMille,
      long nextDueTick,
      String terms,
      long tick,
      HexCoord treasuryLocation,
      long debtBefore,
      long debtAfter,
      long lenderAvailableBefore,
      long lenderAvailableAfter) {

    IssuePlan {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(lenderId, "lenderId");
      requireKnownUnit(BORROW_TYPE, unit);
      if (principal < 1L) {
        throw new IllegalArgumentException("principal 必须 >= 1: " + principal);
      }
      if (interestRatePerMille < 0L) {
        throw new IllegalArgumentException("interestRatePerMille 必须 >= 0: " + interestRatePerMille);
      }
      requireNonBlank(terms, "terms");
      requireNonNegative(tick, "tick");
      if (nextDueTick <= tick) {
        throw new IllegalArgumentException(
            "nextDueTick 必须大于 tick: nextDueTick=" + nextDueTick + "，tick=" + tick);
      }
      Objects.requireNonNull(treasuryLocation, "treasuryLocation");
      requireNonNegative(debtBefore, "debtBefore");
      requireNonNegative(debtAfter, "debtAfter");
      requireNonNegative(lenderAvailableBefore, "lenderAvailableBefore");
      requireNonNegative(lenderAvailableAfter, "lenderAvailableAfter");
      if (lenderAvailableAfter != Math.subtractExact(lenderAvailableBefore, principal)) {
        throw new IllegalArgumentException(
            "lenderAvailableAfter 必须等于借前可贷 − principal: "
                + lenderAvailableAfter
                + " vs "
                + (lenderAvailableBefore - principal));
      }
    }

    /** 本工具提交的三条命令类型（按批内顺序；preview 视图与 apply 组批共用同一处）。 */
    List<String> commandTypes() {
      return List.of(BORROW_TYPE, ADJUST_ACCOUNTS_TYPE, PUT_INFO_TYPE);
    }

    /** {@code economy.UnitBorrow} 载荷（字段序 = handler 契约序；terms 显式给，不吃缺省）。 */
    String borrowPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("lenderId", lenderId);
      payload.put("unit", unit);
      payload.put("principal", principal);
      payload.put("interestRatePerMille", interestRatePerMille);
      payload.put("nextDueTick", nextDueTick);
      payload.put("terms", terms);
      return ToolSupport.json(payload);
    }

    /** {@code actor.AdjustAccounts} 载荷：国库一条<b>正增量</b>（缺账可新建）。 */
    String adjustPayloadJson() {
      Map<String, Object> owner = new LinkedHashMap<>();
      owner.put("kind", ActorKind.UNIT.name());
      owner.put("id", unitId);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("owner", owner);
      entry.put("q", treasuryLocation.q());
      entry.put("r", treasuryLocation.r());
      if (PilotModel.MONEY.equals(unit)) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put(MoneyVocabulary.SILVER_CURRENCY.toString(), principal);
        entry.put("money", money);
      } else {
        Map<String, Object> goods = new LinkedHashMap<>();
        goods.put(GRAIN.toString(), principal);
        entry.put("goods", goods);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", List.of(entry));
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>；字段序固定）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", unitId);
      value.put("lenderId", lenderId);
      value.put("unit", unit);
      value.put("principal", principal);
      value.put("interestRatePerMille", interestRatePerMille);
      value.put("nextDueTick", nextDueTick);
      value.put("terms", terms);
      value.put("tick", tick);
      value.put("treasuryLocation", ToolSupport.hexCoord(treasuryLocation));
      value.put("debtBefore", debtBefore);
      value.put("debtAfter", debtAfter);
      value.put("lenderAvailableBefore", lenderAvailableBefore);
      value.put("lenderAvailableAfter", lenderAvailableAfter);
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "单位 "
          + unitId
          + " 向放贷方 "
          + lenderId
          + " 借入 "
          + unit
          + " "
          + principal
          + "（tick "
          + tick
          + "，利率 "
          + interestRatePerMille
          + "‰，nextDueTick "
          + nextDueTick
          + "，terms="
          + terms
          + "）：单位负债 "
          + debtBefore
          + " → "
          + debtAfter
          + "；放贷方可贷 "
          + lenderAvailableBefore
          + " → "
          + lenderAvailableAfter
          + "；国库落点 "
          + hexText(treasuryLocation)
          + "；reason="
          + reason;
    }
  }

  // ── Plan：还款 ─────────────────────────────────────────────────────────────────────

  /**
   * 还款计划（全部字段是状态的纯函数）。
   *
   * @param unitId 还款主体
   * @param lenderId 放贷方
   * @param unit money | grain
   * @param amount 还款额（&ge; 1，且 &le; 未结清负债）
   * @param tick 推导时的世界日
   * @param treasuryLocation 国库落点 = 单位当刻有效位置
   * @param outstandingBefore 还前未结清负债（正数）
   * @param outstandingAfter 还后未结清负债（正数；0 = 已结清）
   * @param treasuryAvailableBefore 国库可支配（还前）
   * @param treasuryAvailableAfter 国库可支配（还后；= 还前 − amount）
   * @param lenderAvailableBefore 放贷方可贷量（还前）
   * @param lenderAvailableAfter 放贷方可贷量（还后；= 还前 + amount）
   */
  record RepayPlan(
      String unitId,
      String lenderId,
      String unit,
      long amount,
      long tick,
      HexCoord treasuryLocation,
      long outstandingBefore,
      long outstandingAfter,
      long treasuryAvailableBefore,
      long treasuryAvailableAfter,
      long lenderAvailableBefore,
      long lenderAvailableAfter) {

    RepayPlan {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(lenderId, "lenderId");
      requireKnownUnit(REPAY_TYPE, unit);
      if (amount < 1L) {
        throw new IllegalArgumentException("amount 必须 >= 1: " + amount);
      }
      requireNonNegative(tick, "tick");
      Objects.requireNonNull(treasuryLocation, "treasuryLocation");
      if (outstandingBefore < amount) {
        throw new IllegalArgumentException(
            "outstandingBefore 必须 >= amount: outstandingBefore="
                + outstandingBefore
                + "，amount="
                + amount);
      }
      if (outstandingAfter != outstandingBefore - amount) {
        throw new IllegalArgumentException(
            "outstandingAfter 必须等于还前负债 − amount: "
                + outstandingAfter
                + " vs "
                + (outstandingBefore - amount));
      }
      requireNonNegative(outstandingAfter, "outstandingAfter");
      if (treasuryAvailableBefore < amount) {
        throw new IllegalArgumentException(
            "treasuryAvailableBefore 必须 >= amount: treasuryAvailableBefore="
                + treasuryAvailableBefore
                + "，amount="
                + amount);
      }
      if (treasuryAvailableAfter != treasuryAvailableBefore - amount) {
        throw new IllegalArgumentException(
            "treasuryAvailableAfter 必须等于还前可支配 − amount: "
                + treasuryAvailableAfter
                + " vs "
                + (treasuryAvailableBefore - amount));
      }
      requireNonNegative(lenderAvailableBefore, "lenderAvailableBefore");
      if (lenderAvailableAfter != lenderAvailableBefore + amount) {
        throw new IllegalArgumentException(
            "lenderAvailableAfter 必须等于还前可贷 + amount: "
                + lenderAvailableAfter
                + " vs "
                + (lenderAvailableBefore + amount));
      }
    }

    /** 本工具提交的三条命令类型（按批内顺序：先出国库款，再销债）。 */
    List<String> commandTypes() {
      return List.of(ADJUST_ACCOUNTS_TYPE, REPAY_TYPE, PUT_INFO_TYPE);
    }

    /** {@code actor.AdjustAccounts} 载荷：国库一条<b>负增量</b>（推导期已确认可支配足够）。 */
    String adjustPayloadJson() {
      Map<String, Object> owner = new LinkedHashMap<>();
      owner.put("kind", ActorKind.UNIT.name());
      owner.put("id", unitId);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("owner", owner);
      entry.put("q", treasuryLocation.q());
      entry.put("r", treasuryLocation.r());
      if (PilotModel.MONEY.equals(unit)) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put(MoneyVocabulary.SILVER_CURRENCY.toString(), Math.negateExact(amount));
        entry.put("money", money);
      } else {
        Map<String, Object> goods = new LinkedHashMap<>();
        goods.put(GRAIN.toString(), Math.negateExact(amount));
        entry.put("goods", goods);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", List.of(entry));
      return ToolSupport.json(payload);
    }

    /** {@code economy.UnitRepay} 载荷。 */
    String repayPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("lenderId", lenderId);
      payload.put("unit", unit);
      payload.put("amount", amount);
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>；字段序固定）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", unitId);
      value.put("lenderId", lenderId);
      value.put("unit", unit);
      value.put("amount", amount);
      value.put("tick", tick);
      value.put("treasuryLocation", ToolSupport.hexCoord(treasuryLocation));
      value.put("outstandingBefore", outstandingBefore);
      value.put("outstandingAfter", outstandingAfter);
      value.put("settled", outstandingAfter == 0L);
      value.put("treasuryAvailableBefore", treasuryAvailableBefore);
      value.put("treasuryAvailableAfter", treasuryAvailableAfter);
      value.put("lenderAvailableBefore", lenderAvailableBefore);
      value.put("lenderAvailableAfter", lenderAvailableAfter);
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "单位 "
          + unitId
          + " 向放贷方 "
          + lenderId
          + " 归还 "
          + unit
          + " "
          + amount
          + "（tick "
          + tick
          + "）：单位负债 "
          + outstandingBefore
          + " → "
          + outstandingAfter
          + (outstandingAfter == 0L ? "（已结清）" : "（未结清）")
          + "；国库可支配 "
          + treasuryAvailableBefore
          + " → "
          + treasuryAvailableAfter
          + "；放贷方可贷 "
          + lenderAvailableBefore
          + " → "
          + lenderAvailableAfter
          + "；国库落点 "
          + hexText(treasuryLocation)
          + "；reason="
          + reason;
    }
  }
}
