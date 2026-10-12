package io.mosire.simos.sd.resolve;

import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.spi.DecisionTablesLookup;
import io.mosire.simos.sd.state.SdSnapshot;
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
 * {@code sd:} 命名空间的地址解析器（spec §二.2，N15）。
 *
 * <p>**canonical 形态**：{@code sd:decision-maker.<id>} / {@code sd:directive.<id>} / {@code
 * sd:effect.<id>}。 canonical 一律由 {@link Address} AST 构造后 {@link Address#canonical()} 产出，**不手拼**。
 *
 * <p>★★ <b>2026-10-12 批次 3c：{@code nation} 支<b>整支删除</b></b>（用户裁定：{@code sd:nation.<id>}
 * 地址族「<b>直接扔</b>」）—— {@code nations} 表随外交搬进 {@code simos-gov}，本解析器连它的存在性都判不了（{@code sd} 不得依赖 {@code
 * gov}）。 ⇒ 今天 {@code sd:nation.<id>} 一律走 {@code default ->
 * empty()}（<b>空候选</b>，不是抛：那是"这个地址不存在"，与"路径形状非法"同一档）； 国家身份的读面走 gov 自己的面。★ 这是规划 §4 批次 3
 * 列的<b>不可逆点</b>，用户已批。
 *
 * <p>★★ <b>2026-10-12 批次 3d-1：{@code army} 支<b>整支删除</b></b>（同款处置，逐字照抄 3c）—— {@code armies}
 * 表与军队身份（{@code Army} / {@code ArmyId}）搬进 {@code simos-army}，而 {@code sd} 不得依赖 {@code army} ⇒ 同理落到
 * {@code default -> empty()}。军队身份的读面走 <b>army 自己的支</b>：{@code army:army.<id>} （见 {@link
 * io.mosire.simos.army.resolve.ArmyResolver}）。★ 这也是规划 §4 列的不可逆点，用户已批（「sd 整个弃用」）。
 *
 * <p>★★ <b>2026-10-12 批次 3d-2：{@code combat} 支<b>整支删除</b></b>（同款处置，旧战斗族<b>退役</b>而非搬迁）—— {@code
 * combats} 表与 {@code Combat} / {@code CombatStage} / {@code CombatOutcome} 三个 id 类型随该族一起删除， 本解析器
 * <b>连那些类型都不存在了</b>（编译期即不可能保留该支）。⇒ 今天 {@code sd:combat.<id>}（含旧的三段形态 {@code
 * sd:combat.<id>:stage.<sid>} 与四段形态 {@code …:outcome.<oid>}）一律走 {@code default ->
 * empty()}（<b>空候选</b>）。 交战的读面走 <b>army 自己的支</b>：{@code army:combat.<id>}（含 {@code
 * army:combat.<id>:stage.<sid>}）。
 *
 * <p>★ **空候选与抛的分工**同 {@code MapResolver}/{@code UnitResolver}：合法但不服务/不存在的 ⇒ 空候选；抛只有一处—— 装配故障（缺 sd
 * 切片、或切片类型不对）。
 *
 * <p>★ 决策人地址**不可用 {@code agent:}**（N15）：那是 AgentLib 的绑定值，不进地址。
 *
 * <p>★ <b>日志（2026-10-23 L2）</b>：只记空候选与装配故障（DEBUG，{@code SD_RESOLVE_*}/{@code
 * SD_RESOLVE_UNAVAILABLE}），**不逐次记成功查询**；地址串是领域 id，不是载荷原文。
 *
 * <p>★★ <b>{@code directive} 与 {@code decision-maker} 两支的存在性判据住 ga</b>（{@code directives} 随 3b-1、
 * {@code decisionMakers} 随 3b-2 搬进 {@code simos-ga}），而 {@code simos-sd} <b>不得</b>依赖 {@code
 * simos-ga}（成环） ⇒ 本类经构造期注入的 {@link DecisionTablesLookup} 问它（实现 {@code GaDecisionTablesLookup} 在
 * ga、组合根 {@code Shell} 接线）。★ <b>地址族串一字不改</b> （{@code sd:directive.<id>} / {@code
 * sd:decision-maker.<id>} 仍是本解析器的 对外形态；地址族的裁量属批次 3e）——变的只是"表住哪个切片"。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：{@code effect} 支<b>整支删除</b></b>（与 3c 的 {@code nation}、3d-1 的 {@code
 * army}、 3d-2 的 {@code combat} 三支同款处置）——{@code effects} 表随整族值类型搬进 {@code simos-ga}，而 {@code sd}
 * 不得依赖 {@code ga} ⇒ 本解析器<b>判不了它的存在性</b>（{@code EffectId} 类型还在 sd，但表已经不在了）⇒ 今天 {@code
 * sd:effect.<id>} 一律走 {@code default -> empty()}（<b>空候选</b>，不是抛：那是"这个地址不存在"，与"路径形状非法"同一档）。 ★
 * <b>如实记（本批的一处功能净损失，已上报）</b>：{@code ga} 侧本批<b>没有</b>补建等价的 {@code ga:effect.<id>} 解析器 ——{@code
 * effects} 今天全仓没有任何生产读者按地址取它（除本支外零消费点：GUI 只在"令"视图里列出 {@code Directive.effects} 的 id
 * 串），且"新开一个地址族"属另一次决定（3e 或后续批次复核；先例 = 3d-1 给 army 补 {@code army:army.<id>} 是因为身份读面只有 resolver 这一条）。
 *
 * <p>★ <b>本批起本解析器仍要求 sd 切片存在</b>（{@link #requireSdSlice}）：那是<b>装配期</b>判据（"命名空间在、切片得在、
 * 类型得对"），与搬迁前逐字一致——它挡的是"忘了注册 {@code SdCodec}"这类装配错，不是数据；★ 两支存活地址的数据都经 {@link DecisionTablesLookup}
 * 取自 ga。
 */
public final class SdResolver implements Resolver {

  private static final String NAMESPACE = "sd";

  private static final LogChannel RESOLVE = EventLog.channel(SdLog.resolve());

  private final DecisionTablesLookup decisionTables;

  /**
   * @param decisionTables ga 侧 {@code directives} / {@code decisionMakers} 两张表的只读口（装配期注入；非
   *     null——缺了它 {@code directive} / {@code decision-maker} 两支无从判存在性）
   */
  public SdResolver(DecisionTablesLookup decisionTables) {
    this.decisionTables = Objects.requireNonNull(decisionTables, "decisionTables");
  }

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
    requireSdSlice(ctx, address);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isEmpty()) {
      if (RESOLVE.isDebugEnabled()) {
        RESOLVE.debug(
            LogEvent.of(
                "SD_RESOLVE_EMPTY",
                SdLogSource.SD_RESOLVE,
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
          // ★ 2026-10-12 批次 3c：`nation` 支已整支删除（用户裁定「sd:nation.<id> 直接扔」）——
          //   nations 表搬进 simos-gov，本解析器判不了它的存在性（sd 不得依赖 gov）⇒ 落到 default -> empty()。
          // ★★ 2026-10-12 批次 3d-1：`army` 支**同款整支删除**（用户裁定「sd 整个弃用！」；逐字照抄 3c 对
          //   sd:nation.<id> 的处置）——armies 表与军队身份（Army / ArmyId）搬进 simos-army，而 sd 不得依赖
          //   army ⇒ 判不了"这个军队存在吗"。⇒ 今天 `sd:army.<id>` 一律走 `default -> empty()`
          //   （**空候选**，不是抛：那是"这个地址不存在"，与"路径形状非法"同一档）。
          //   ★ 军队身份的读面改走 army 自己的解析器支：`army:army.<id>`
          //   （见 io.mosire.simos.army.resolve.ArmyResolver 与 ArmyAddresses.ARMY_KIND）。
          //   ★ 这是规划 §4 批次 3 列的**不可逆点**（地址族），用户已批。
          // ★★ 2026-10-12 批次 3d-2：`combat` 支**同款整支删除**（旧战斗族**退役**：combats 表与
          //   Combat/CombatStage/CombatOutcome 三个 id 类型一起删除 ⇒ 该支在编译期已不可能保留）。
          //   ⇒ 今天 `sd:combat.<id>`（含旧三段 `:stage.<sid>` / 四段 `:outcome.<oid>`）一律走
          //   `default -> empty()`。★ 交战的读面改走 army 自己的支：`army:combat.<id>`（含
          //   `army:combat.<id>:stage.<sid>`，见 ArmyResolver）。
          case "decision-maker" ->
              rootOnly(
                  segments,
                  root,
                  SdResolver::parseDecisionMaker,
                  id -> decisionTables.decisionMakers(ctx.state()).containsKey(id),
                  "DecisionMaker");
          case "directive" ->
              rootOnly(
                  segments,
                  root,
                  SdResolver::parseDirective,
                  id -> decisionTables.containsDirective(ctx.state(), id),
                  "Directive");
          // ★ 2026-10-12 批次 3b-4：`effect` 支已整支删除（effects 表搬进 simos-ga，本解析器判不了它的存在性）
          //   ⇒ 落到 default -> empty()（空候选）。理由与"为什么不补 ga:effect"见类注。
          default -> empty();
        };
    if (result.candidates().isEmpty() && RESOLVE.isDebugEnabled()) {
      RESOLVE.debug(
          LogEvent.of(
              "SD_RESOLVE_EMPTY",
              SdLogSource.SD_RESOLVE,
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
   * 装配期判据（**返回 void**）：sd 切片必须在、且类型必须是 {@link SdSnapshot}，否则当场抛。★ 本批起本类不再读它的任何组件 （树已是 0
   * 组件），这条检查只为保住"忘了注册 {@code SdCodec} = 装配故障"这条既有语义（与搬迁前逐字同口径）； 诊断日志 {@code SD_RESOLVE_UNAVAILABLE}
   * 也一并保留。
   */
  private static void requireSdSlice(ResolveContext ctx, Address address) {
    Snapshot snapshot;
    try {
      snapshot =
          ctx.state()
              .module(NAMESPACE)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "状态里没有 sd 模块切片——SdResolver 需要 SdSnapshot（装配故障，不是\"没有候选\"）"));
      if (!(snapshot instanceof SdSnapshot)) {
        throw new IllegalArgumentException("sd 模块切片不是 SdSnapshot：" + snapshot.getClass().getName());
      }
    } catch (IllegalArgumentException e) {
      if (RESOLVE.isDebugEnabled()) {
        RESOLVE.debug(
            LogEvent.of(
                "SD_RESOLVE_UNAVAILABLE",
                SdLogSource.SD_RESOLVE,
                "reason",
                "sd 切片缺席或类型不对（装配故障）",
                "address",
                address.canonical()));
      }
      throw e;
    }
  }
}
