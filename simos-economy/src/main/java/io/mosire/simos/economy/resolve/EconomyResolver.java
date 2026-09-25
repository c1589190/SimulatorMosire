package io.mosire.simos.economy.resolve;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.ClassKey;
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
 *   <li>{@code economy:<mapId>:class.<industryId>.<slotId>} —— 阶层行（类型名 {@code "ClassRow"}）；无记录 ⇒
 *       空候选
 *   <li>{@code economy:<mapId>:flow.<industryId>.<slotId>} —— 周期流水（类型名 {@code "FlowRow"}）；无记录 ⇒ 空候选
 * </ul>
 *
 * <p>★ **class/flow 的两段在地址里以 {@code .} 相连**（§八 R1 的原文 {@code class.<industryId>.<slotId>}）：地址解析器把
 * {@code class.ag.farmer} 读成一个 {@code Entity(kind="class", name="ag.farmer")}（见 {@code
 * AddressParser}），故本类在 **第一个 {@code .}** 处拆开两段交给 {@link IndustryId#parse}/{@link
 * ClassSlotId#parse}（两段都非空才认，否则按坏名字抛）。 注意这与 {@link ClassKey#toString()} 的 {@code "|"} 是两套写法：后者是变更集的
 * key，前者是给人读的地址。
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
      case "class" -> resolveClassRow(data, mapId, entity.name());
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
    DebtId id = DebtId.parse(name);
    if (!data.debts().containsKey(id)) {
      return empty();
    }
    return single(
        new SubjectId("economy.debt", id.value()),
        entityAddress(mapId, "debt", id.value()),
        "Debt");
  }

  private static QueryResult resolveClassRow(EconomyData data, String mapId, String name) {
    ClassKey key = parseClassKey(name);
    if (!data.classes().containsKey(key)) {
      return empty();
    }
    return single(
        new SubjectId("economy.class", key.toString()),
        entityAddress(mapId, "class", dotted(key)),
        "ClassRow");
  }

  private static QueryResult resolveFlowRow(EconomyData data, String mapId, String name) {
    ClassKey key = parseClassKey(name);
    if (!data.flows().containsKey(key)) {
      return empty();
    }
    return single(
        new SubjectId("economy.flow", key.toString()),
        entityAddress(mapId, "flow", dotted(key)),
        "FlowRow");
  }

  /**
   * 把地址里的 {@code <industryId>.<slotId>} 拆成 {@link ClassKey}。
   *
   * <p>在**第一个 {@code .}** 处拆（产业段在前）；缺分隔符、任一段为空、或多一段都按坏名字抛（认领了的 kind 不静默 miss）。 两段仍各自交给 {@link
   * IndustryId#parse}/{@link ClassSlotId#parse} 判空白。
   */
  private static ClassKey parseClassKey(String name) {
    int i = name.indexOf('.');
    if (i <= 0 || i == name.length() - 1) {
      throw new IllegalArgumentException("非法阶层地址名（应为 <industryId>.<slotId>）: " + name);
    }
    return new ClassKey(
        IndustryId.parse(name.substring(0, i)), ClassSlotId.parse(name.substring(i + 1)));
  }

  /** {@link ClassKey} 的**地址写法**（点分），与 {@link ClassKey#toString()} 的 {@code "|"} 无关。 */
  private static String dotted(ClassKey key) {
    return key.industry().value() + "." + key.slot().value();
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
