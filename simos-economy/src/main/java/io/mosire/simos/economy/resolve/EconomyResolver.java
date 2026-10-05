package io.mosire.simos.economy.resolve;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.Resolver;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Objects;

/**
 * {@code economy:} 命名空间的地址解析器（新经济设计 §八 R1）。认四类地址（与 {@code LedgerResolver} 同形制）：
 *
 * <ul>
 *   <li>{@code economy:<mapId>} —— 该地图的经济切片根主体（第 2 段是根主体 {@code Entity(∅,·)}）
 *   <li>{@code economy:<mapId>:industry.<id>} —— 产业（类型名 {@code "Industry"}）；无记录 ⇒ 空候选
 *   <li>{@code economy:<mapId>:debt.<id>} —— 债务（类型名 {@code "Debt"}）；无记录 ⇒ 空候选
 *   <li>{@code economy:<mapId>:class.<cohort>} —— 家户经济（Java 类型 {@code HouseholdEconomy}；对外类型名 wire 仍为
 *       {@code "ClassRow"}）；无记录 ⇒ 空候选。 {@code
 *       <cohort>} = {@link CohortKey#toString()} 的**规范串**（如 {@code 0_0|rural|poor_peasant}）
 *   <li>{@code economy:<mapId>:flow.<cohort>} —— 周期流水（类型名 {@code "FlowRow"}）；无记录 ⇒ 空候选
 * </ul>
 *
 * <p>★★ <b>class/flow 的局部名 = {@link CohortKey} 的规范串 —— 唯一拼写点就在这里与那个类型上</b>（H0；裁定 R-N1-A）：地址解析器把
 * {@code class.0_0|rural|poor_peasant} 读成一个 {@code Entity(kind="class",
 * name="0_0|rural|poor_peasant")} （见 {@code AddressParser}），本类把这个名字**整份**交给 {@link
 * CohortKey#parse}（坏名字抛它自己的 IAE，不包不吞）。
 *
 * <p>★★ <b>为什么不再拼 {@code <industryId>.<slotId>}</b>（改前的 {@code dotted(...)}）：① H0 起行就是家户（键 = 格 +
 * 居住类型 + 阶层），产业段**在身份里已经不存在了**；② 那个点分串在 app 侧（时间参与者的读写集）还有第二、第三处内联拼接 —— 同一个格式的多个拼写点正是 本仓明令禁止的形态。⇒
 * 现在只有一处：{@code CohortKey.toString()} / {@code CohortKey.parse}。 ★ 规范串里**没有 {@code '.'}**（坐标是
 * {@code 数字_数字}、居住与阶层都是封闭词表）⇒ 不会被 {@code AddressParser} 在第一个点处截断。
 *
 * <p>**空候选与抛的分工**（与 {@code MapResolver}/{@code LedgerResolver} 同款）：合法但本模块不服务（其它 kind、属性段、 段数 &gt; 3
 * 或 = 4、Index 段、没有记录的产业/阶层/债务/流水）一律空候选；**抛只有两处**——装配故障（state 里没有 economy 切片 / 切片类型不对）与认领了的 kind
 * 里**名字解析失败**（{@link IndustryId#parse} 等抛它自己的 IAE，不包不吞）。
 *
 * <p>★ **canonical 只能由 {@link Address} AST 构造后调 {@code canonical()} 产出**：§3.4 的按需加引规则不在本类重实现。
 * {@code mapId} **只回显、不校验**（与 social/ledger 同款：地图 ID 没有本切片内的判据）。
 */
public final class EconomyResolver implements Resolver {

  private static final String NAMESPACE = "economy";

  /** 本解析器负责的命名空间（注册表按它建键，与地址首段一致）。 */
  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    if (!NAMESPACE.equals(address.namespace())) {
      return empty(); // 认领与否由返回值表达；未知命名空间抛是注册表的职责
    }
    // 装配故障在解析任何 economy: 地址时就炸，不留到某个查询路径上静默 miss（先于段形状判定）
    EconomyData data = dataOf(ctx);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isPresent()) {
      return empty(); // 第 2 段必须是根主体 Entity(∅,·)
    }
    String mapId = root.name();
    if (segments.size() == 2) {
      return single(new SubjectId(NAMESPACE, mapId), rootAddress(mapId), "Economy");
    }
    if (segments.size() > 3) {
      return empty(); // 属性访问（economy:m1:industry.ag:cycleDays）本切片不服务
    }
    AddressSegment third = segments.get(2);
    // Index 段（economy:m1:[ag]）与缺 kind 的实体都不服务：本切片没有"位置型"主体。
    if (!(third instanceof Entity entity) || entity.kind().isEmpty()) {
      return empty();
    }
    return switch (entity.kind().get()) {
      case "industry" -> resolveIndustry(data, mapId, entity.name());
      case "debt" -> resolveDebt(data, mapId, entity.name());
      case "class" -> resolveHouseholdEconomy(data, mapId, entity.name());
      case "flow" -> resolveFlowRow(data, mapId, entity.name());
      default -> empty(); // 其它 kind 的合法地址，本模块不服务
    };
  }

  private static QueryResult resolveIndustry(EconomyData data, String mapId, String name) {
    IndustryId id = IndustryId.parse(name); // 名字非法抛它自己的 IAE，不包不吞
    if (!data.industries().containsKey(id)) {
      return empty(); // 合法但不存在的产业：空候选，不是错误
    }
    return single(
        new SubjectId("economy.industry", id.value()),
        entityAddress(mapId, "industry", id.value()),
        "Industry");
  }

  private static QueryResult resolveDebt(EconomyData data, String mapId, String name) {
    DebtContractId id = DebtContractId.parse(name);
    if (!data.debtContracts().containsKey(id)) {
      return empty();
    }
    return single(
        new SubjectId("economy.debt", id.value()),
        entityAddress(mapId, "debt", id.value()),
        "Debt");
  }

  private static QueryResult resolveHouseholdEconomy(EconomyData data, String mapId, String name) {
    // ★ S1：class 的局部名 = 家户**稳定身份**（HouseholdId）的规范串；旧档的 CohortKey 串由
    //   EconomyCodec 在读入时映射成 ofLegacy 身份（地址解析器不复述那段兼容）。
    HouseholdId id = HouseholdId.parse(name);
    if (!data.classes().containsKey(id)) {
      return empty();
    }
    return single(
        new SubjectId("economy.class", id.value()),
        entityAddress(mapId, "class", id.value()),
        "ClassRow");
  }

  private static QueryResult resolveFlowRow(EconomyData data, String mapId, String name) {
    HouseholdId id = HouseholdId.parse(name);
    if (!data.flows().containsKey(id)) {
      return empty();
    }
    return single(
        new SubjectId("economy.flow", id.value()),
        entityAddress(mapId, "flow", id.value()),
        "FlowRow");
  }

  /** 切片只能从 economy 模块拿（铁律 3/4：SimulationState 没有跨模块访问器）。缺席或类型不对都是装配故障。 */
  private static EconomyData dataOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 economy 模块切片——EconomyResolver 需要 EconomySnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalArgumentException(
          "economy 模块切片不是 EconomySnapshot：" + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  // ★ canonical 一律由 Address AST 构造后调 canonical() 产出（R13）：§3.4 的加引规则不许在这里手写重实现。

  private static Address rootAddress(String mapId) {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of(mapId)));
  }

  private static Address entityAddress(String mapId, String kind, String localId) {
    return new Address(
        List.of(new Namespace(NAMESPACE), Entity.of(mapId), Entity.of(kind, localId)));
  }

  private static QueryResult single(SubjectId id, Address canonicalAddress, String typeName) {
    return new QueryResult(
        List.of(new ResolvedSubject(id, canonicalAddress.canonical(), typeName)));
  }

  private static QueryResult empty() {
    return new QueryResult(List.of());
  }
}
