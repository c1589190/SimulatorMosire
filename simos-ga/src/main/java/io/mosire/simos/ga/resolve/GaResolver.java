package io.mosire.simos.ga.resolve;

import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.id.DirectiveId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.Resolver;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>{@code ga:} 命名空间的地址解析器</b>（2026-10-12 批次 3e；前身是 {@code simos-sd} 的 {@code
 * SdResolver}，整类迁入并改挂命名空间）。
 *
 * <p>**canonical 形态**：{@code ga:decision-maker.<id>} / {@code ga:directive.<id>}。canonical 一律由
 * {@link Address} AST 构造后 {@link Address#canonical()} 产出，**不手拼**。
 *
 * <p>★★ <b>本批的判据：{@code sd:} 地址族整体消失</b>（用户裁定「<b>sd 整个弃用！</b>」＋「{@code sd:nation.<id>} 地址族 →
 * <b>直接扔</b>」，规划 §18.2 第 5 步「3e」）。上一代解析器里那几支已被逐批处置过（{@code nation} 随 3c 删、{@code army} 随 3d-1
 * 删、{@code combat} 随 3d-2 退役、{@code effect} 随 3b-4 删），本批把**最后两支存活族**改挂到 {@code ga:}：
 *
 * <ul>
 *   <li>{@code ga:decision-maker.<id>} —— 决策人身份（{@code GaState.decisionMakers}）。★ 它同时是 <b>D2
 *       判决面</b>的 subject 形态（见 {@link
 *       io.mosire.simos.ga.adjudication.Breakpoints#acceptsVerdictSubject}）：主体必须与解析器的 canonical
 *       逐字一致，否则判决会被判决面当场拒；
 *   <li>{@code ga:directive.<id>} —— 决策记录（{@code GaState.directives}）。
 * </ul>
 *
 * <p>★★ <b>为什么这两支能直读、而旧实现要经注入端口读</b>（本批的结构性收益，如实记）：旧 {@code SdResolver} 住 {@code simos-sd}，而两张表早已搬进
 * {@code simos-ga}，{@code sd} 又不得依赖 {@code ga}（成环）⇒ 只能经注入的 {@code DecisionTablesLookup}（实现在
 * ga、组合根接线）问存在性。整类搬进 {@code ga} 之后，{@code directives} / {@code decisionMakers}
 * 与解析器<b>同住一棵树</b>（{@code GaState}）⇒ 那个端口与它的唯一实现 {@code GaDecisionTablesLookup}
 * <b>同批删除</b>（"看着能注入、其实只有一条线"的接缝不留），本类直接读 {@link GaState}。
 *
 * <p>★ **空候选与抛的分工**同 {@code MapResolver}/{@code ArmyResolver}：合法但不服务/不存在的 ⇒ 空候选；抛只有一处—— 装配故障（缺 ga
 * 切片、或切片类型不对）。
 *
 * <p>★ 决策人地址**不可用 {@code agent:}**（N15）：那是 AgentLib 的绑定值，不进地址。
 *
 * <p>★ <b>日志（2026-10-23 L2）</b>：只记空候选与装配故障（DEBUG，{@code GA_RESOLVE_*}），**不逐次记成功查询**；地址串是领域
 * id，不是载荷原文。★ 本批来源档由 {@code sd-resolve} 换成 {@code ga-resolve}（{@code GaLogSource.GA_RESOLVE}）—— 与
 * 3b-4 把 {@code SD_TICK} 换成 {@code GA_TICK} 同款处置（搬迁换家，来源表随之换家）。
 */
public final class GaResolver implements Resolver {

  private static final String NAMESPACE = "ga";

  private static final LogChannel RESOLVE = EventLog.channel(GaLog.resolve());

  public GaResolver() {}

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    if (!NAMESPACE.equals(address.namespace())) {
      return empty();
    }
    GaState state = requireGaSlice(ctx, address);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isEmpty()) {
      if (RESOLVE.isDebugEnabled()) {
        RESOLVE.debug(
            LogEvent.of(
                "GA_RESOLVE_EMPTY",
                GaLogSource.GA_RESOLVE,
                "reason",
                "路径形状非法或根段缺 kind",
                "address",
                address.canonical(),
                "segments",
                segments.size()));
      }
      return empty();
    }
    QueryResult result =
        switch (root.kind().get()) {
          case "decision-maker" ->
              rootOnly(
                  segments,
                  root,
                  GaResolver::parseDecisionMaker,
                  id -> state.decisionMakers().containsKey(id),
                  "DecisionMaker");
          case "directive" ->
              rootOnly(
                  segments,
                  root,
                  GaResolver::parseDirective,
                  id -> state.directives().containsKey(id),
                  "Directive");
          // ★ 本批（3e）：`effect` 支**不补**（3b-4 已裁"全仓零生产读者按地址取 effects"）；`nation` / `army` /
          //   `combat` 三支随各自的搬迁/退役早已删除 ⇒ 其余 kind 一律空候选。
          default -> empty();
        };
    if (result.candidates().isEmpty() && RESOLVE.isDebugEnabled()) {
      RESOLVE.debug(
          LogEvent.of(
              "GA_RESOLVE_EMPTY",
              GaLogSource.GA_RESOLVE,
              "reason",
              "候选为空（id 非法/实体不存在/路径不受支持）",
              "address",
              address.canonical(),
              "root",
              root.kind().get(),
              "segments",
              segments.size()));
    }
    return result;
  }

  private static <T> QueryResult rootOnly(
      List<AddressSegment> segments,
      Entity root,
      java.util.function.Function<String, Optional<T>> parse,
      java.util.function.Predicate<T> exists,
      String typeName) {
    if (segments.size() != 2) {
      return empty();
    }
    Optional<T> id = parse.apply(root.name());
    if (id.isEmpty() || !exists.test(id.get())) {
      return empty();
    }
    return single(
        root.kind().get() + "." + root.name(), address(root.kind().get(), root.name()), typeName);
  }

  private static Optional<DecisionMakerId> parseDecisionMaker(String text) {
    return safe(() -> DecisionMakerId.parse(text));
  }

  private static Optional<DirectiveId> parseDirective(String text) {
    return safe(() -> DirectiveId.parse(text));
  }

  private static <T> Optional<T> safe(java.util.function.Supplier<T> supplier) {
    try {
      return Optional.of(supplier.get());
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static QueryResult single(String localId, Address canonical, String typeName) {
    return new QueryResult(
        List.of(
            new ResolvedSubject(
                new SubjectId(NAMESPACE, localId), canonical.canonical(), typeName)));
  }

  private static Address address(String kind, String name) {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of(kind, name)));
  }

  private static QueryResult empty() {
    return new QueryResult(List.of());
  }

  /**
   * 装配期判据（**返回 ga 状态树**）：ga 切片必须在、且类型必须是 {@link GaSnapshot}，否则当场抛。★ 与旧 {@code
   * SdResolver#requireSdSlice} 同口径——它挡的是"忘了注册 {@code GaCodec}"这类装配错，不是数据（诊断日志 {@code
   * GA_RESOLVE_UNAVAILABLE} 一并保留）。
   */
  private static GaState requireGaSlice(ResolveContext ctx, Address address) {
    Snapshot snapshot;
    try {
      snapshot =
          ctx.state()
              .module(NAMESPACE)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "状态里没有 ga 模块切片——GaResolver 需要 GaSnapshot（装配故障，不是\"没有候选\"）"));
      if (!(snapshot instanceof GaSnapshot gaSnapshot)) {
        throw new IllegalArgumentException("ga 模块切片不是 GaSnapshot：" + snapshot.getClass().getName());
      }
      return gaSnapshot.state();
    } catch (IllegalArgumentException e) {
      if (RESOLVE.isDebugEnabled()) {
        RESOLVE.debug(
            LogEvent.of(
                "GA_RESOLVE_UNAVAILABLE",
                GaLogSource.GA_RESOLVE,
                "reason",
                "ga 切片缺席或类型不对（装配故障）",
                "address",
                address.canonical()));
      }
      throw e;
    }
  }
}
