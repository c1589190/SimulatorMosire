package io.mosire.simos.ledger.resolve;

import io.mosire.simos.economy.api.id.AccountId;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.ledger.LedgerData;
import io.mosire.simos.ledger.LedgerSnapshot;
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
 * {@code ledger:} 命名空间的地址解析器（增量 2 spec §三）。认四类地址（与 {@code SocialResolver} 同形制）：
 *
 * <ul>
 *   <li>{@code ledger:<mapId>} —— 该地图的账本切片根主体（第 2 段是根主体 {@code Entity(∅,·)}）
 *   <li>{@code ledger:<mapId>:account.<id>} —— 账户（类型名 {@code "Account"}）；无记录 ⇒ 空候选
 *   <li>{@code ledger:<mapId>:claim.<id>} —— 索取权（类型名 {@code "Claim"}）；无记录 ⇒ 空候选
 *   <li>{@code ledger:<mapId>:transfer.<id>} —— 转移凭据（类型名 {@code "Transfer"}）；无记录 ⇒ 空候选
 * </ul>
 *
 * <p>**空候选与抛的分工**（与 {@code MapResolver}/{@code SocialResolver} 同款）：合法但本模块不服务（其它 kind、 属性段、段数 &gt;
 * 3、Index 段、没有记录的账户/债权/流水）一律空候选；**抛只有两处**——装配故障（state 里没有 ledger 切片 / 切片类型不对）与认领了的 kind
 * 里**名字解析失败**（{@link AccountId#parse} 等抛它自己的 IAE， 不包不吞）。
 *
 * <p>★ **canonical 只能由 {@link Address} AST 构造后调 {@code canonical()} 产出**：M1 §3.4 的按需加引规则不在本类
 * 重实现。{@code mapId} **只回显、不校验**（与 social 同款：地图 ID 没有本切片内的判据）。
 */
public final class LedgerResolver implements Resolver {

  private static final String NAMESPACE = "ledger";

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
    // 装配故障在解析任何 ledger: 地址时就炸，不留到某个查询路径上静默 miss（先于段形状判定）
    LedgerData data = dataOf(ctx);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isPresent()) {
      return empty(); // 第 2 段必须是根主体 Entity(∅,·)
    }
    String mapId = root.name();
    if (segments.size() == 2) {
      return single(new SubjectId(NAMESPACE, mapId), rootAddress(mapId), "Ledger");
    }
    if (segments.size() > 3) {
      return empty(); // 属性访问（ledger:m1:account.a1:money）本切片不服务
    }
    AddressSegment third = segments.get(2);
    // Index 段（ledger:m1:[a1]）与缺 kind 的实体都不服务：本切片没有"位置型"主体。
    if (!(third instanceof Entity entity) || entity.kind().isEmpty()) {
      return empty();
    }
    return switch (entity.kind().get()) {
      case "account" -> resolveAccount(data, mapId, entity.name());
      case "claim" -> resolveClaim(data, mapId, entity.name());
      case "transfer" -> resolveTransfer(data, mapId, entity.name());
      default -> empty(); // 其它 kind 的合法地址，本模块不服务
    };
  }

  private static QueryResult resolveAccount(LedgerData data, String mapId, String name) {
    AccountId id = AccountId.parse(name); // 名字非法抛它自己的 IAE，不包不吞
    if (!data.accounts().containsKey(id)) {
      return empty(); // 合法但不存在的账户：空候选，不是错误
    }
    return single(
        new SubjectId("ledger.account", id.value()),
        entityAddress(mapId, "account", id.value()),
        "Account");
  }

  private static QueryResult resolveClaim(LedgerData data, String mapId, String name) {
    ClaimId id = ClaimId.parse(name);
    if (!data.claims().containsKey(id)) {
      return empty();
    }
    return single(
        new SubjectId("ledger.claim", id.value()),
        entityAddress(mapId, "claim", id.value()),
        "Claim");
  }

  private static QueryResult resolveTransfer(LedgerData data, String mapId, String name) {
    TransferId id = TransferId.parse(name);
    if (!data.transfers().containsKey(id)) {
      return empty();
    }
    return single(
        new SubjectId("ledger.transfer", id.value()),
        entityAddress(mapId, "transfer", id.value()),
        "Transfer");
  }

  /** 切片只能从 ledger 模块拿（铁律 3/4：SimulationState 没有跨模块访问器）。缺席或类型不对都是装配故障。 */
  private static LedgerData dataOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 ledger 模块切片——LedgerResolver 需要 LedgerSnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof LedgerSnapshot ledgerSnapshot)) {
      throw new IllegalArgumentException(
          "ledger 模块切片不是 LedgerSnapshot：" + snapshot.getClass().getName());
    }
    return ledgerSnapshot.data();
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
