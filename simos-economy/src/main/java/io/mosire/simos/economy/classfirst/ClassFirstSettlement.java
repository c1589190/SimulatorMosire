package io.mosire.simos.economy.classfirst;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>阶层池经济的正式单日结算入口</b>（计划 R1）：{@code settleOneDay(base, inputs) -> result}。
 *
 * <p>它把 {@link ClassFirstPilotEngine}（生产/投入/阶层分配/家户份额/滚动账户/消费/借贷/催收/双向人口流动）包在一个 <b>无会话状态</b>的 API
 * 后面：调用方给上一日状态与当日输入，得到新状态 + actor 账户增量 + social 人口增量 + 日审计。 引擎的全部跨 tick 状态都在 {@link
 * ClassFirstState} 里（见 {@link ClassFirstPilotEngine#snapshot()} / {@link
 * ClassFirstPilotEngine#restore(ClassFirstState)}），因此逐日链式调用与一次性 360 tick 逐值同轨迹。
 *
 * <p>★★ <b>边界纪律</b>：本类不调用 {@code 旧结算引擎（R3a 已删除）} / {@code 旧日推进器（R3a 已删除）} / 旧生产分配类；它是 R2
 * 切换调用方时唯一要认识的入口。{@code base} 为空态时用 {@code inputs} 播种（config + 家户账户 + 放贷账户）；非空态时 {@code
 * inputs.config} 必须与状态内 config 一致（同一状态流中途换参数 ⇒ 显式拒绝，不静默换规则）。
 *
 * <p>★ <b>输入形状</b>：{@code day}（>=1，非空态必须 = base.tick + 1）、{@code config}（世界/模式参数）、{@code
 * households}（actor 商品/货币账户 + social 人口批次）、{@code lenders}（可选；缺省用 {@code config.lender()}）。
 *
 * <p>★ <b>输出形状</b>：新的 {@link ClassFirstState}、按账户聚合的 {@link AccountDelta}（库存商品/货币）、按池聚合的 {@link
 * PopulationDelta}、以及当日 {@link PilotModel.TickReport} 审计（生产账户/流动事件/红灯/守恒读数）。
 */
public final class ClassFirstSettlement {

  private ClassFirstSettlement() {}

  /**
   * 单日结算输入。
   *
   * @param day 结算日（从 1 开始；非空态必须 = base.meta.tick() + 1）
   * @param config 世界/模式参数；空态播种必填，非空态可为 null（以状态内 config 为准）
   * @param households actor 商品/货币账户 + social 人口批次（空态播种必填；非空态忽略）
   * @param lenders actor 放贷账户；空/缺省 ⇒ {@code config.lender()}
   */
  public record Inputs(
      long day,
      PilotConfig config,
      List<PilotModel.Household> households,
      List<PilotModel.Lender> lenders) {

    public Inputs {
      if (day < 1L) {
        throw new IllegalArgumentException("day 必须 >= 1: " + day);
      }
      households = households == null ? List.of() : List.copyOf(households);
      lenders = lenders == null ? List.of() : List.copyOf(lenders);
    }

    /** 最常用形状：显式参数 + 家户账户，放贷账户回落到 config.lender()。 */
    public static Inputs of(long day, PilotConfig config, List<PilotModel.Household> households) {
      return new Inputs(day, config, households, List.of());
    }
  }

  /** actor 账户增量：某账户的某商品/货币维度在当日的变化。 */
  public record AccountDelta(String accountId, String commodity, long delta) {
    public AccountDelta {
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(commodity, "commodity");
      if (delta == 0L) {
        throw new IllegalArgumentException("AccountDelta.delta 不得为 0（0 增量不产生记录）");
      }
    }
  }

  /** social 人口批次（阶层池）增量。 */
  public record PopulationDelta(ClassPoolId poolId, long populationDelta, long laborDelta) {
    public PopulationDelta {
      Objects.requireNonNull(poolId, "poolId");
      if (populationDelta == 0L && laborDelta == 0L) {
        throw new IllegalArgumentException("PopulationDelta 不得两个维度都为 0");
      }
    }
  }

  /** 单日结算结果：新状态 + 读写边界增量 + 日审计。 */
  // ★ SpotBugs 不做跨过程/紧凑构造器重赋值跟踪：两个 List 已在构造器里 unmodifiableList(new ArrayList<>(...))，
  //   访问器返回的既不是调用方原对象也改不动 ⇒ 按类豁免（先例：simos-social 的 ArmyPlan）。
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "紧凑构造器已 Collections.unmodifiableList(new ArrayList<>(...))；检测器对 record 紧凑构造器的重赋值不做跟踪")
  public record Result(
      ClassFirstState state,
      List<AccountDelta> actorAccountDeltas,
      List<PopulationDelta> socialPopulationDeltas,
      PilotModel.TickReport audit) {

    public Result {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(audit, "audit");
      actorAccountDeltas =
          actorAccountDeltas == null
              ? List.of()
              : Collections.unmodifiableList(new ArrayList<>(actorAccountDeltas));
      socialPopulationDeltas =
          socialPopulationDeltas == null
              ? List.of()
              : Collections.unmodifiableList(new ArrayList<>(socialPopulationDeltas));
    }
  }

  /** 单日结算：推进一天，返回新状态与当日增量/审计。 */
  public static Result settleOneDay(ClassFirstState base, Inputs inputs) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(inputs, "inputs");

    ClassFirstPilotEngine engine;
    ClassFirstState before;
    if (base.isEmpty()) {
      if (inputs.config() == null) {
        throw new IllegalArgumentException("空态播种必须给 config（世界/模式参数）");
      }
      if (inputs.households().isEmpty()) {
        throw new IllegalArgumentException("空态播种必须给至少一个家户账户（social 人口批次）");
      }
      List<PilotModel.Lender> lenderSpecs =
          inputs.lenders().isEmpty() ? List.of(inputs.config().lender()) : inputs.lenders();
      engine = new ClassFirstPilotEngine(inputs.config(), inputs.households(), lenderSpecs);
      engine.setTick(inputs.day() - 1L);
      before = engine.snapshot();
    } else {
      PilotConfig stateConfig = base.meta().config();
      if (inputs.config() != null && stateConfig != null && !inputs.config().equals(stateConfig)) {
        throw new IllegalArgumentException(
            "classFirst 状态已带 config；settleOneDay 不接受中途换参数（GM 调参请显式重建状态）");
      }
      long expectedDay = base.meta().tick() + 1L;
      if (inputs.day() != expectedDay) {
        throw new IllegalArgumentException("day 与状态不同步：期望 " + expectedDay + "，收到 " + inputs.day());
      }
      engine = ClassFirstPilotEngine.restore(base);
      before = base;
    }

    PilotModel.TickReport audit = engine.advanceTick();
    ClassFirstState next = engine.snapshot();
    return new Result(next, accountDeltas(before, next), populationDeltas(before, next), audit);
  }

  /** actor 账户增量：阶层池库存维度 + 外部放贷账户（商品/货币）。 */
  private static List<AccountDelta> accountDeltas(ClassFirstState before, ClassFirstState after) {
    List<AccountDelta> deltas = new ArrayList<>();
    Set<ClassPoolId> poolIds = new LinkedHashSet<>(before.classPools().keySet());
    poolIds.addAll(after.classPools().keySet());
    for (ClassPoolId poolId : poolIds) {
      ClassPool beforePool = before.classPools().get(poolId);
      ClassPool afterPool = after.classPools().get(poolId);
      for (AssetKind kind : AssetKind.ordered()) {
        if (!kind.stock()) {
          continue;
        }
        long beforeValue = beforePool == null ? 0L : beforePool.stock(kind);
        long afterValue = afterPool == null ? 0L : afterPool.stock(kind);
        long delta = afterValue - beforeValue;
        if (delta != 0L) {
          deltas.add(new AccountDelta(poolId.toString(), kind.schemaName(), delta));
        }
      }
    }
    Set<ExternalLenderId> lenderIds = new LinkedHashSet<>(before.lenders().keySet());
    lenderIds.addAll(after.lenders().keySet());
    for (ExternalLenderId lenderId : lenderIds) {
      PilotModel.Lender beforeLender = before.lenders().get(lenderId);
      PilotModel.Lender afterLender = after.lenders().get(lenderId);
      long beforeMoney = beforeLender == null ? 0L : beforeLender.money();
      long afterMoney = afterLender == null ? 0L : afterLender.money();
      if (afterMoney != beforeMoney) {
        deltas.add(new AccountDelta(lenderId.toString(), "money", afterMoney - beforeMoney));
      }
      Set<String> commodities = new LinkedHashSet<>();
      if (beforeLender != null) {
        commodities.addAll(beforeLender.goods().keySet());
      }
      if (afterLender != null) {
        commodities.addAll(afterLender.goods().keySet());
      }
      for (String commodity : commodities) {
        long beforeValue =
            beforeLender == null ? 0L : beforeLender.goods().getOrDefault(commodity, 0L);
        long afterValue =
            afterLender == null ? 0L : afterLender.goods().getOrDefault(commodity, 0L);
        if (afterValue != beforeValue) {
          deltas.add(new AccountDelta(lenderId.toString(), commodity, afterValue - beforeValue));
        }
      }
    }
    return Collections.unmodifiableList(deltas);
  }

  /** social 人口增量：按阶层池读人口/劳动差分。 */
  private static List<PopulationDelta> populationDeltas(
      ClassFirstState before, ClassFirstState after) {
    List<PopulationDelta> deltas = new ArrayList<>();
    Set<ClassPoolId> poolIds = new LinkedHashSet<>(before.classPools().keySet());
    poolIds.addAll(after.classPools().keySet());
    for (ClassPoolId poolId : poolIds) {
      ClassPool beforePool = before.classPools().get(poolId);
      ClassPool afterPool = after.classPools().get(poolId);
      long beforePopulation = beforePool == null ? 0L : beforePool.population();
      long afterPopulation = afterPool == null ? 0L : afterPool.population();
      long beforeLabor = beforePool == null ? 0L : beforePool.labor();
      long afterLabor = afterPool == null ? 0L : afterPool.labor();
      if (beforePopulation != afterPopulation || beforeLabor != afterLabor) {
        deltas.add(
            new PopulationDelta(
                poolId, afterPopulation - beforePopulation, afterLabor - beforeLabor));
      }
    }
    return Collections.unmodifiableList(deltas);
  }

  /** 只读快照辅助：{@code Map} 形式的池索引（供调用方遍历；不改变状态）。 */
  public static Map<ClassPoolId, ClassPool> poolsOf(ClassFirstState state) {
    return new LinkedHashMap<>(state.classPools());
  }
}
