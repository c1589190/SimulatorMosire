package io.mosire.simos.economy.classfirst;

import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>class-first 原生 GM 抽取（{@code economy.GmAdjust.levyStock}）的纯函数</b>：把某个阶层池的粮/钱一次性抽到既有外部放贷
 * 主体账户（GOV/军队那一类），供"临时抽钱抽粮"落成工具。
 *
 * <p>★★ <b>只动两处</b>：{@code classFirst.classPools} 的源池与 {@code classFirst.lenders} 的目标 lender；其余组件（含
 * {@code meta} 的初始基数/累计读数）逐值不变。粮/钱守恒：源池 {@code takeStock} 减多少，目标 lender 就加多少 —— 两侧仍在总量里， 不发行、不销毁。
 *
 * <p>★★ <b>纯函数 / copy-on-write</b>：绝不原地改 {@code state} 里的池或 lender；先 {@link
 * ClassPool#copy()}，在副本上扣库存， 再用 {@link ClassFirstState#withClassPools} + {@link
 * ClassFirstState#withLenders} 产新 state（构造器再深拷一层， 新状态与入参不共享可写引用）。
 *
 * <p>★★ <b>上限（口粮保护）</b>：
 *
 * <ul>
 *   <li>{@code grain}：{@code available = stock(GRAIN) - config.protectedGrainReserve(population,
 *       labor)} —— 保护储备与结算 同源（{@link PilotConfig#protectedGrainReserve(long, long)}），不够就拒，不截断；
 *   <li>{@code money}：{@code available = stock(MONEY)}；
 *   <li>{@code amount > available} ⇒ {@link IllegalArgumentException}，消息带 {@code available}/{@code
 *       stock} 与保护储备数字， GM 可按预览改小重发。
 * </ul>
 *
 * <p>★ <b>拒绝消息</b>统一 {@value #LABEL} 前缀 + 可读中文（{@code EconomyGmAdjustments.project} 与 GM 工具把它原样折成
 * {@code Rejected}/{@code BAD_REQUEST}）。
 *
 * <p>★ <b>非目标</b>（计划 §10.4）：征调人力/征兵、反向 {@code grantStock}、税率/地方债、组军经济买单；本类只做一次性粮/钱抽取。
 */
public final class ClassFirstLevy {

  /** 拒绝消息前缀（classfirst 不反向依赖 spi 的命令常量，故在此独立拼写；与 {@code EconomyGmAdjustHandler.TYPE} 对齐）。 */
  private static final String LABEL = "economy.GmAdjust.levyStock";

  private ClassFirstLevy() {}

  /**
   * 一次性抽取：从 {@code fromClassPositionId} 的阶层池扣 {@code amount} 单位的 {@code unit}，加进 {@code lenderId}
   * 的外部放贷主体账户。
   *
   * <p>校验顺序（拒绝消息都带 {@value #LABEL} 前缀）：state 非空且带 {@code meta.config}；源池按 {@code classPositionId}
   * 存在；目标 lender 存在；{@code unit} 只认 {@code GRAIN}/{@code MONEY}；{@code amount ≥ 1}；最后按上限判可抽量。
   *
   * @param state 当前 class-first 持久状态（只读；不得为 null，且必须带 {@code meta.config}）
   * @param fromClassPositionId 源阶层位置 id（在 {@code classPools} 里按 {@link ClassPool#classPositionId()}
   *     找）
   * @param lenderId 目标外部放贷主体的稳定键（必须在 {@code classFirst.lenders} 里存在）
   * @param unit 抽取维度：只支持 {@link AssetKind#GRAIN}/{@link AssetKind#MONEY}
   * @param amount 抽取量（≥ 1；grain 不得超过 {@code stock − protectedGrainReserve}，money 不得超过 {@code
   *     stock}）
   * @return 新状态 + 源池/lender 的前后值（供 {@code EconomyGmAdjustments} 拼审计 {@code Change}）
   * @throws IllegalArgumentException 任一校验不通过（消息可直接进 {@code Rejected}/{@code BAD_REQUEST}）
   */
  public static Result extract(
      ClassFirstState state,
      String fromClassPositionId,
      ExternalLenderId lenderId,
      AssetKind unit,
      long amount) {
    if (state == null) {
      throw new IllegalArgumentException(LABEL + " 的 state 不得为 null");
    }
    PilotConfig config = state.meta() == null ? null : state.meta().config();
    if (config == null) {
      throw new IllegalArgumentException(
          LABEL + " 需要 classFirst.meta.config（当前 classFirst 未播种/无 config）；请先播种 class-first 世界");
    }
    SourcePool source = findSourcePool(state, fromClassPositionId);
    ClassPool poolBefore = source.pool();
    PilotModel.Lender lenderBefore = lenderId == null ? null : state.lenders().get(lenderId);
    if (lenderBefore == null) {
      throw new IllegalArgumentException(
          LABEL + " 找不到目标放贷方：lenderId=" + lenderId + "；现有放贷方: " + lenderIds(state));
    }
    if (unit == null || (unit != AssetKind.GRAIN && unit != AssetKind.MONEY)) {
      throw new IllegalArgumentException(
          LABEL + " 的 unit 只支持 " + PilotModel.GRAIN + " | " + PilotModel.MONEY + "，收到: " + unit);
    }
    if (amount < 1L) {
      throw new IllegalArgumentException(LABEL + " 的 amount 必须 >= 1: " + amount);
    }

    long stock = poolBefore.stock(unit);
    if (unit == AssetKind.GRAIN) {
      long protectedReserve =
          config.protectedGrainReserve(poolBefore.population(), poolBefore.labor());
      // ★ 消息里的 available 夹到 0：stock < reserve 时"一个都不能抽"，不把负数当可抽量给人看
      //   （判据不变：amount ≥ 1 > available=0 ⇒ 照样拒）。
      long available = Math.max(0L, stock - protectedReserve);
      if (amount > available) {
        throw new IllegalArgumentException(
            LABEL
                + " 的 amount 超过可用粮（口粮保护储备不可抽取，不截断）：amount="
                + amount
                + "，available="
                + available
                + "（stock="
                + stock
                + "，protectedGrainReserve="
                + protectedReserve
                + "）");
      }
    } else {
      long available = stock;
      if (amount > available) {
        throw new IllegalArgumentException(
            LABEL
                + " 的 amount 超过可用货币（不截断）：amount="
                + amount
                + "，available="
                + available
                + "（stock="
                + stock
                + "）");
      }
    }

    // ★ copy-on-write：扣库存只发生在副本上；takeStock 包内可见，本类与 ClassPool 同包。
    ClassPool poolAfter = poolBefore.copy();
    long taken = poolAfter.takeStock(unit, amount);
    if (taken != amount) {
      // 上限校验后不应发生；真发生 = 状态内部不一致，拒绝整笔（绝不部分抽取）。
      throw new IllegalArgumentException(LABEL + " 抽取未足额（拒绝部分抽取）：期望=" + amount + "，实际=" + taken);
    }

    PilotModel.Lender lenderAfter;
    if (unit == AssetKind.GRAIN) {
      LinkedHashMap<String, Long> goods = new LinkedHashMap<>(lenderBefore.goods());
      long beforeGrain = lenderBefore.goods().getOrDefault(PilotModel.GRAIN, 0L);
      goods.put(
          PilotModel.GRAIN, addExactOrReject("goods." + PilotModel.GRAIN, beforeGrain, amount));
      lenderAfter =
          new PilotModel.Lender(
              lenderBefore.id(),
              lenderBefore.money(),
              goods,
              lenderBefore.interestRatePerMille(),
              lenderBefore.nextDueTick(),
              lenderBefore.collectionPower());
    } else {
      long moneyAfter = addExactOrReject("money", lenderBefore.money(), amount);
      lenderAfter =
          new PilotModel.Lender(
              lenderBefore.id(),
              moneyAfter,
              lenderBefore.goods(),
              lenderBefore.interestRatePerMille(),
              lenderBefore.nextDueTick(),
              lenderBefore.collectionPower());
    }

    ClassFirstState nextState =
        state
            .withClassPools(Map.of(source.id(), poolAfter))
            .withLenders(Map.of(lenderId, lenderAfter));
    return new Result(nextState, poolBefore, poolAfter, lenderBefore, lenderAfter);
  }

  /** 在 {@code classPools} 里按 {@link ClassPool#classPositionId()} 找源池；找不到 ⇒ 具名拒绝并列出可用位置。 */
  private static SourcePool findSourcePool(ClassFirstState state, String fromClassPositionId) {
    if (fromClassPositionId != null && !fromClassPositionId.isBlank()) {
      for (Map.Entry<ClassPoolId, ClassPool> entry : state.classPools().entrySet()) {
        if (fromClassPositionId.equals(entry.getValue().classPositionId())) {
          return new SourcePool(entry.getKey(), entry.getValue());
        }
      }
    }
    throw new IllegalArgumentException(
        LABEL
            + " 找不到源阶层池：classPositionId="
            + fromClassPositionId
            + "；现有阶层位置: "
            + classPositionIds(state));
  }

  private static String classPositionIds(ClassFirstState state) {
    StringBuilder ids = new StringBuilder();
    for (ClassPool pool : state.classPools().values()) {
      if (ids.length() > 0) {
        ids.append(" | ");
      }
      ids.append(pool.classPositionId());
    }
    return ids.length() == 0 ? "（无）" : ids.toString();
  }

  private static String lenderIds(ClassFirstState state) {
    StringBuilder ids = new StringBuilder();
    for (PilotModel.Lender lender : state.lenders().values()) {
      if (ids.length() > 0) {
        ids.append(" | ");
      }
      ids.append(lender.id());
    }
    return ids.length() == 0 ? "（无）" : ids.toString();
  }

  /**
   * 加法溢出 fail-closed（照 {@link ClassPool#mergedWith} 的 {@code Math.addExact} 口径，但折成具名拒绝而不是裸
   * ArithmeticException）。
   */
  private static long addExactOrReject(String field, long base, long amount) {
    try {
      return Math.addExact(base, amount);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          LABEL + " 抽取后 " + field + " 溢出 long（base=" + base + "，amount=" + amount + "），拒绝");
    }
  }

  /** 源池定位结果：稳定键 + 池（键与值都来自 {@code state.classPools()} 的同一条目）。 */
  private record SourcePool(ClassPoolId id, ClassPool pool) {}

  /**
   * 一次抽取的结果：新状态 + 源池/lender 前后值。
   *
   * <p>★ {@code state} 只服务投影与落盘；{@code poolBefore}/{@code poolAfter} 与 {@code lenderBefore}/{@code
   * lenderAfter} 供 {@code EconomyGmAdjustments} 拼两条审计 {@code Change}（{@code classFirst.classPools}
   * 源池、{@code classFirst.lenders} 目标），不额外表达"改了什么"。
   *
   * @param state 抽取后的新 {@link ClassFirstState}（纯 copy-with 产出）
   * @param poolBefore 抽取前的源池（**防御性副本**：与入参状态不共享可写引用）
   * @param poolAfter 抽取后的源池（**防御性副本**，已扣库存）
   * @param lenderBefore 抽取前的目标 lender
   * @param lenderAfter 加库存后的目标 lender
   */
  public record Result(
      ClassFirstState state,
      ClassPool poolBefore,
      ClassPool poolAfter,
      PilotModel.Lender lenderBefore,
      PilotModel.Lender lenderAfter) {

    public Result {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(poolBefore, "poolBefore");
      Objects.requireNonNull(poolAfter, "poolAfter");
      Objects.requireNonNull(lenderBefore, "lenderBefore");
      Objects.requireNonNull(lenderAfter, "lenderAfter");
      // ★ SpotBugs EI_EXPOSE_REP2 的收口（与 state 里的池不共享可写引用）：ClassPool 是可变类型且只有
      //   copy-on-write 值语义 ⇒ 存副本、取副本；不引 @SuppressFBWarnings（本仓的既有口径：冻在赋值处）。
      poolBefore = poolBefore.copy();
      poolAfter = poolAfter.copy();
    }

    /** ★ 返回**防御性副本**（EI_EXPOSE_REP 的收口）：调用方拿到的是快照，改不到状态里的池。 */
    @Override
    public ClassPool poolBefore() {
      return poolBefore.copy();
    }

    /** ★ 返回**防御性副本**（EI_EXPOSE_REP 的收口）：口径同 {@link #poolBefore()}。 */
    @Override
    public ClassPool poolAfter() {
      return poolAfter.copy();
    }
  }
}
