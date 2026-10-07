package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.gov.dismiss} 的<b>纯推导</b>（阶段 10b-ii，2026-10-01 GOV/Army 计划 §2.2/§2.6）：从一份 {@link
 * SimulationState} 与参数算出 {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、不碰 {@code
 * CoreSimos}</b>， preview 与 apply 因此共用同一份语义（工具只负责读态、把 Plan 折成视图、组批、折叠结局）。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：离编在 {@code unit} 切片、退休待遇在 {@code actor} 国库、行动记录在 {@code sd}
 * 切片；单条命令只能落一个命名空间。本工具走 {@link io.mosire.simos.core.CoreSimos#submitBatch}（同 branch + 同
 * expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>待遇支付口径（逐值对应计划 §2.2；P2-C 后修订）</b>：{@code payment = policy.retirementPerStaff × count}
 * （银，最小币值）；{@code payment > 0} 时付款账户 = 政府家户 {@code GovernmentHouseholds.of(unitId)}（单位国库已与政府家户
 * 合一），国库落点 = 单位<b>当刻有效位置</b>，可支配银 = {@link AvailableStock#available}（余额 − 冻结，唯一算法；没有这本账 = 0）；{@code
 * payment > 0 且可支配 < payment} ⇒ <b>整条拒</b>（带 requested/available/缺口）；{@code payment == 0} ⇒ 批里<b>无
 * actor 命令</b>（也不要求单位有位置）。 乘法溢出 long ⇒ 具名拒（不静默回绕成负数/0）。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code unit.DismissStaff}（{@code {unitId, role, count}}；减到 0
 * <b>保留角色键</b>） →（{@code payment > 0} 时）{@code actor.AdjustAccounts}（国库银<b>负增量</b>一条）→ {@code
 * sd.PutInfo}（地址 = 单位 canonical，key={@code dismiss}，value=JSON <b>字符串</b>，含
 * role/count/待遇/tick，note=人可读摘要）。三条共享同一 batchId 与同一 branch/expectedRevision ⇒ 一条 revision。
 *
 * <p>★★ <b>具名缺口：人员社会回写明确留阶段 13，本工具不做</b>。{@code unit.DismissStaff} 只把 roster 减掉、本工具只支付 {@code
 * retirementPerStaff × count}；离编人员<b>不会</b>作为 {@code social.SeedGroups} 回到任何社会批次/hex（"回老家/父老乡亲"
 * 那条链路 = 阶段 13 的 {@code simos.gov.dispatchTeam}/{@code absorbUnit} 一类人员流转工具）。因此本工具绝不生成 {@code
 * social.*} 命令，也绝不把"没回写"伪装成"人员已安置"——需要回写的调用方必须走阶段 13 的完整链路。
 *
 * <p>★★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：单位存在且带 {@link
 * GovernmentFormation}； {@code count ≥ 1}；{@code role} 词表；{@code 现有在编 < count} ⇒ 具名拒；待遇乘法溢出 ⇒
 * 具名拒；{@code payment > 0} 时无 有效位置或国库可支配银不足 ⇒ 具名拒。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；Plan 不持有可变集合。
 */
final class GovDismissPlan {

  /** {@code unit.DismissStaff} 的命令类型（与 {@code DismissStaffHandler.type()} 同字面）。 */
  static final String DISMISS_STAFF_TYPE = "unit.DismissStaff";

  /** {@code actor.AdjustAccounts} 的命令类型（仅待遇 &gt; 0 才落）。 */
  static final String ADJUST_ACCOUNTS_TYPE = "actor.AdjustAccounts";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  private GovDismissPlan() {}

  /**
   * 纯推导入口（见类注的待遇口径与校验清单）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 离编主体（必须是带 {@link GovernmentFormation} 的单位）
   * @param roleText 行政角色词表（SCRIBE|YAMEN|POST）
   * @param count 离编人数（≥ 1，且不得超过现有在编）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan plan(SimulationState state, String unitId, String roleText, long count) {
    Objects.requireNonNull(state, "state");
    if (unitId == null || unitId.isBlank()) {
      throw new IllegalArgumentException("unitId 必须是非空文本");
    }
    StaffRole role = parseRole(roleText);
    if (count < 1L) {
      throw new IllegalArgumentException("离编人数 count 必须 ≥ 1: " + count);
    }
    UnitState units = ToolSupport.unitState(state);
    Unit unit = units.units().get(UnitId.parse(unitId));
    if (unit == null) {
      throw new IllegalArgumentException("GOV 单位不存在: " + unitId);
    }
    GovernmentFormation governmentFormation = requireGovernmentFormation(unit, unitId);
    // ★★ Z4/C4：离编/退休的 legacy 工具只对 posts 为空的旧档口径成立；新世界 staff 是岗位家户投影，
    //   人数只能通过 Social 家户人口/承诺改（岗位删改由 Z3 的岗位工具负责）。
    if (governmentFormation.staffIsHouseholdProjection()) {
      throw new IllegalArgumentException(
          "单位 "
              + unitId
              + " 的 householdPosts 非空：staff 只是岗位家户人口/承诺的投影，legacy 的 "
              + "simos.gov.dismiss 不能直改 staff（会制造第二本权威）。请改 Social 家户人口/承诺，"
              + "或用 simos.gov.retireStaff / Z3 的岗位工具");
    }
    long staffBefore = governmentFormation.staff().getOrDefault(role, 0L);
    if (staffBefore < count) {
      throw new IllegalArgumentException(
          "离编 " + role + " " + count + " 人超过现有在编: 现有 " + staffBefore + " < 请求 " + count);
    }
    long tick = state.meta().timestamp().tick();
    Payment payment = paymentFor(state, unit, governmentFormation, count);
    return new Plan(
        unitId,
        role,
        count,
        tick,
        staffBefore,
        staffBefore - count,
        payment.retirementPerStaff(),
        payment.payment(),
        payment.treasuryLocation(),
        payment.availableSilver());
  }

  /**
   * ★★ <b>Z4：退休待遇的一次性支付推导（{@link GovDismissPlan} 与 {@link GovRetireStaffPlan} 共用一处）</b>。
   *
   * <p>口径与旧实现逐值相同：{@code payment = policy.retirementPerStaff × count}（银）；{@code payment > 0} 时国库落点
   * = 单位当刻有效位置，可支配银 = {@link AvailableStock#available}（余额 − 冻结，账户缺失 = 0）；不足 ⇒ 整条具名拒。 本方法**不看
   * staff**（新世界的退休源是岗位家户人口），staff 前置由调用方各自判。
   */
  static Payment paymentFor(
      SimulationState state, Unit unit, GovernmentFormation governmentFormation, long count) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(governmentFormation, "governmentFormation");
    long retirementPerStaff = governmentFormation.policy().retirementPerStaff();
    long payment;
    try {
      payment = Math.multiplyExact(retirementPerStaff, count);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "退休待遇溢出 long: retirementPerStaff="
              + retirementPerStaff
              + " × count="
              + count
              + "（先下调政策或减少 count）",
          e);
    }
    Optional<HexCoord> treasuryLocation = Optional.empty();
    long availableSilver = 0L;
    if (payment > 0L) {
      SimosTimestamp at = state.meta().timestamp();
      UnitState units = ApiViews.unitState(state);
      treasuryLocation = units.effectivePosition(unit.id(), at);
      if (treasuryLocation.isEmpty()) {
        throw new IllegalArgumentException(
            "单位 "
                + unit.id().value()
                + " 当刻没有有效位置，国库落点无法确定（待遇 "
                + payment
                + " 需要支付）；先 unit.PlaceAt");
      }
      // ★★ P2-A §13.3：政府国库 = 政府家户账户 hh-gov-<unitId>；可支配银 = AvailableStock（余额 − 冻结）。
      //   账户缺失 = 0（与全仓口径一致），不猜、不新建。
      ActorData actors = ApiViews.actorData(state);
      availableSilver =
          AvailableStock.available(
              actors, GovernmentHouseholds.of(unit.id().value()), MoneyVocabulary.SILVER_CURRENCY);
      if (availableSilver < payment) {
        throw new IllegalArgumentException(
            "退休待遇支付不足：requested="
                + payment
                + "，available="
                + availableSilver
                + "，缺口="
                + (payment - availableSilver)
                + "（国库银可支配 = 余额 − 冻结；先补款或下调政策）");
      }
    }
    return new Payment(retirementPerStaff, payment, treasuryLocation, availableSilver);
  }

  /**
   * ★ Z4：退休待遇支付推导的纯数据（{@link #paymentFor} 的返回值，两个工具共用）。
   *
   * @param retirementPerStaff 政策里的每人一次性退休待遇（银/人）
   * @param payment = retirementPerStaff × count
   * @param treasuryLocation 国库落点（payment=0 时空）
   * @param availableSilver 国库可支配银（payment=0 时 0）
   */
  record Payment(
      long retirementPerStaff,
      long payment,
      Optional<HexCoord> treasuryLocation,
      long availableSilver) {

    Payment {
      Objects.requireNonNull(treasuryLocation, "treasuryLocation");
    }
  }

  /** 角色词表：只认 SCRIBE|YAMEN|POST，别的词给具名拒（不静默当缺省）。 */
  private static StaffRole parseRole(String roleText) {
    if (roleText == null || roleText.isBlank()) {
      throw new IllegalArgumentException("role 必须是非空文本（SCRIBE|YAMEN|POST）");
    }
    try {
      return StaffRole.valueOf(roleText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("role 不是合法角色（SCRIBE|YAMEN|POST）: " + roleText, e);
    }
  }

  /** 单位必须带 {@link GovernmentFormation}（离编命令的领域前置；消息给出下一步）。 */
  private static GovernmentFormation requireGovernmentFormation(Unit unit, String unitId) {
    if (unit.module().orElse(null) instanceof GovernmentFormation governmentFormation) {
      return governmentFormation;
    }
    throw new IllegalArgumentException(
        "单位 " + unitId + " 没有 GovernmentFormation：离编只对 GOV 单位；先 unit.SetGovFormation");
  }

  /**
   * 一份离编计划（全部字段是状态的纯函数）。
   *
   * @param unitId 离编主体
   * @param role 行政角色
   * @param count 离编人数
   * @param tick 推导时的世界日（行动记录用）
   * @param staffBefore 该角色现有在编
   * @param staffAfter 该角色离编后在编（= staffBefore − count，可为 0；0 保留角色键）
   * @param retirementPerStaff 政策里的每人一次性退休待遇（银/人）
   * @param payment 本次支付总额（= retirementPerStaff × count；0 = 不落 actor 命令）
   * @param treasuryLocation 国库落点（仅 payment &gt; 0 时有值）
   * @param availableSilver 国库可支配银（仅 payment &gt; 0 时求值；否则 0 = 未求值）
   */
  record Plan(
      String unitId,
      StaffRole role,
      long count,
      long tick,
      long staffBefore,
      long staffAfter,
      long retirementPerStaff,
      long payment,
      Optional<HexCoord> treasuryLocation,
      long availableSilver) {

    Plan {
      if (unitId == null || unitId.isBlank()) {
        throw new IllegalArgumentException("unitId 不得为空白");
      }
      Objects.requireNonNull(role, "role");
      if (count < 1L) {
        throw new IllegalArgumentException("count 必须 ≥ 1: " + count);
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      if (staffBefore < count || staffAfter != staffBefore - count) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：staffBefore=" + staffBefore + " count=" + count + " staffAfter=" + staffAfter);
      }
      if (retirementPerStaff < 0L) {
        throw new IllegalArgumentException("retirementPerStaff 不得为负: " + retirementPerStaff);
      }
      long expectedPayment;
      try {
        expectedPayment = Math.multiplyExact(retirementPerStaff, count);
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException(
            "退休待遇溢出 long: retirementPerStaff=" + retirementPerStaff + " × count=" + count, e);
      }
      if (payment != expectedPayment) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：payment=" + payment + " != retirementPerStaff×count=" + expectedPayment);
      }
      Objects.requireNonNull(treasuryLocation, "treasuryLocation");
      if (payment > 0L) {
        if (treasuryLocation.isEmpty()) {
          throw new IllegalArgumentException("payment > 0 却没有国库落点");
        }
        if (availableSilver < payment) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：availableSilver=" + availableSilver + " < payment=" + payment);
        }
      } else if (treasuryLocation.isPresent() || availableSilver != 0L) {
        throw new IllegalArgumentException("payment == 0 时不应带国库落点/可支配银（批里无 actor 命令）");
      }
    }

    /** 是否要落 {@code actor.AdjustAccounts}（待遇 &gt; 0 才落）。 */
    boolean hasPayment() {
      return payment > 0L;
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(3);
      types.add(DISMISS_STAFF_TYPE);
      if (hasPayment()) {
        types.add(ADJUST_ACCOUNTS_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /** {@code unit.DismissStaff} 载荷：{@code {unitId, role, count}}（命令只减 roster、不支付、不回写社会）。 */
    String dismissStaffPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("role", role.name());
      payload.put("count", count);
      return ToolSupport.json(payload);
    }

    /** {@code actor.AdjustAccounts} 载荷：国库银一条负增量（{@link #hasPayment()} 为真时才可调用）。 */
    String adjustAccountsPayloadJson() {
      if (!hasPayment()) {
        throw new IllegalStateException("批不自洽：无待遇却要组装 actor.AdjustAccounts 载荷");
      }
      HexCoord at = treasuryLocation.get();
      Map<String, Object> money = new LinkedHashMap<>();
      money.put(MoneyVocabulary.SILVER_CURRENCY.toString(), -payment);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("household", GovernmentHouseholds.of(unitId).value());
      entry.put("q", at.q());
      entry.put("r", at.r());
      entry.put("money", money);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", List.of(entry));
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>；含 role/count/待遇/tick）。 */
    String infoValueJson(String reason) {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason 必须是非空文本");
      }
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", unitId);
      value.put("role", role.name());
      value.put("count", count);
      value.put("tick", tick);
      value.put("staffBefore", staffBefore);
      value.put("staffAfter", staffAfter);
      value.put("retirementPerStaff", retirementPerStaff);
      value.put("payment", payment);
      value.put("treasury", treasuryLocation.map(GovDismissPlan::treasuryView).orElse(null));
      value.put("availableSilver", availableSilver);
      value.put("socialWriteback", "阶段 13 未做（具名缺口：人员不回写社会批次）");
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason 必须是非空文本");
      }
      return "离编 "
          + unitId
          + " 的 "
          + role
          + " "
          + count
          + " 人（tick "
          + tick
          + "）：在编 "
          + staffBefore
          + "→"
          + staffAfter
          + "，待遇 "
          + payment
          + " 银"
          + (hasPayment() ? "（国库 @ " + hexText(treasuryLocation.get()) + "）" : "（政策为 0，无支付命令）")
          + "；★ 人员回写社会留阶段 13，本工具不做；reason="
          + reason;
    }
  }

  /** 国库落点视图（{@code {q,r}}；行动记录与工具结果共用）。 */
  static Map<String, Object> treasuryView(HexCoord at) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("q", at.q());
    view.put("r", at.r());
    return view;
  }

  /** 格的可读文本（note 用；格式不与任何资源路径语法绑定）。 */
  private static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }
}
