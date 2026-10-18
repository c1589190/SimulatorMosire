package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>资产份额的唯一写口</b>（计划 §4 硬约束 2：「资产份额转移只走抽出的 {@code OwnershipStakeBook.transfer}」）。
 *
 * <pre>
 * transfer(...)   一次整条/部分转移：source 减量；减到 0 且不是本批返回目标行时删行
 * split(...)      严格部分拆分：source 必留行（0 &lt; quantity &lt; source.quantity），返回新份额 id
 * apply(...)      批写口：多条 Move 一次校验/一次提交（E2 租佃拆分的多腿都用它）
 * rebuild(...)    跨产业重建（D-023 跨 hex 资产随迁）：逐 AssetKind 守恒，目标产业必须能承载该资产
 * </pre>
 *
 * <p>★★ <b>先全量校验、生成好全部新 id、再写表</b>：本类用一份<b>覆盖层</b>（每条源份额的剩余量 + 本批新建行）完成全部移动与新 id
 * 生成，只有所有校验（源存在/数量够/industry 存在/守恒/新 id 唯一）都通过后才开始写调用方的可写表。 任一步校验失败 ⇒ 输入表<b>一字不动</b>。
 *
 * <p>★★ <b>新份额 id 确定性</b>：新 id 走 {@link OwnershipStake#idOf(IndustryId, AssetKind, ActorRef,
 * ActorRef, AssetRightKind, ProductionLocation, long)}，序号 = 从 0 起第一个既不在同表、也不在本批已生成集合里的空闲序号。 ★ Alpha
 * 不兼容旧档；合并键包含源份额地点，跨地点绝不合并。
 *
 * <p>★ <b>不造粮/钱/权利</b>：本类只在份额表内部改 owner/operator/kind/quantity、拆分/合并行；不碰商品、货币、债务、劳动或 unit。
 *
 * <p>★ <b>可写表要求</b>：传入的 {@code shares} 必须是可写 map（各调用点传的都是 {@code LinkedHashMap} 工作副本）；全部校验完成后才写它。
 */
public final class OwnershipStakeBook {

  private OwnershipStakeBook() {}

  /**
   * ★ 一条转移/拆分指令：把 {@code source} 的 {@code quantity} 改为新 owner/operator/kind。数量必须 {@code > 0}
   * 且不超过移动时源份额当前数量；等于源数量 ⇒ 整条转移（源行删除），小于 ⇒ 源行保留剩余量。
   *
   * @param source 源份额 id；不得为 null
   * @param quantity 移动数量；必须 {@code > 0}
   * @param toOwner 新所有权主体；不得为 null
   * @param toOperator 新经营主体；不得为 null
   * @param kind 新权利性质；不得为 null
   */
  public record Move(
      AssetShareId source,
      long quantity,
      ActorRef toOwner,
      ActorRef toOperator,
      AssetRightKind kind) {

    public Move {
      Objects.requireNonNull(source, "OwnershipStakeBook.Move.source 不得为 null");
      if (quantity <= 0L) {
        throw new IllegalArgumentException("OwnershipStakeBook.Move.quantity 必须 > 0: " + quantity);
      }
      Objects.requireNonNull(toOwner, "OwnershipStakeBook.Move.toOwner 不得为 null");
      Objects.requireNonNull(toOperator, "OwnershipStakeBook.Move.toOperator 不得为 null");
      Objects.requireNonNull(kind, "OwnershipStakeBook.Move.kind 不得为 null");
    }
  }

  /**
   * ★★ <b>整条或部分转移</b>（{@code quantity} 可等于源数量；等于 = 整条转移，旧 id 删除）。终态为 0 的目标行按返回 id 保留。
   *
   * @return 目标份额的稳定 id（若目标 tuple 已有现成行 ⇒ 返回那一行的 id）
   */
  public static AssetShareId transfer(
      Map<AssetShareId, OwnershipStake> shares,
      Map<IndustryId, IndustryDefinitionView> industries,
      AssetShareId source,
      long quantity,
      ActorRef toOwner,
      ActorRef toOperator,
      AssetRightKind kind) {
    List<AssetShareId> created =
        apply(shares, industries, List.of(new Move(source, quantity, toOwner, toOperator, kind)));
    return created.get(0);
  }

  /**
   * ★★ <b>土地块整块产权转移的唯一写口</b>：只替换源行的 {@code owner}/{@code operator}，其余组件逐字保留。
   *
   * <p>★ <b>为什么不复用 {@link #transfer}</b>：{@code transfer} 的目标合并键含 {@code
   * owner/operator/kind}，且会优先复用同 tuple 的既有行。土地上两个不同块即使 tuple 相同也必须是两个稳定身份；若走 {@code
   * transfer}，整块源可能被并入另一行 （返回另一行的 id、源 id 消失），违反"整块交易 id 不变"。本方法因此直接按源 id 覆盖，不合并、不拆分、不找零、不新建
   * id、不搬库存。
   *
   * <p>★ 前置条件：源必须存在、{@code asset == LAND}、{@code quantity > 0}；新旧主体均不得为 null。本方法只改 {@code
   * owner}/{@code operator}，{@code id/location/quantity/industry/kind} 全部不变。
   */
  public static void transferLandBlock(
      Map<AssetShareId, OwnershipStake> shares,
      AssetShareId sourceId,
      ActorRef newOwner,
      ActorRef newOperator) {
    Objects.requireNonNull(shares, "OwnershipStakeBook.transferLandBlock 的 shares 不得为 null");
    Objects.requireNonNull(sourceId, "OwnershipStakeBook.transferLandBlock 的 sourceId 不得为 null");
    Objects.requireNonNull(newOwner, "OwnershipStakeBook.transferLandBlock 的 newOwner 不得为 null");
    Objects.requireNonNull(
        newOperator, "OwnershipStakeBook.transferLandBlock 的 newOperator 不得为 null");
    OwnershipStake source = shares.get(sourceId);
    if (source == null) {
      throw new IllegalArgumentException(
          "OwnershipStakeBook.transferLandBlock 的源份额不存在: " + sourceId);
    }
    if (!sourceId.equals(source.id())) {
      throw new IllegalArgumentException(
          "OwnershipStakeBook.transferLandBlock 的键必须与 OwnershipStake.id 一致：键="
              + sourceId
              + "，行内 id="
              + source.id());
    }
    if (source.asset() != AssetKind.LAND) {
      throw new IllegalArgumentException(
          "OwnershipStakeBook.transferLandBlock 只接受 LAND 份额: id="
              + sourceId
              + " asset="
              + source.asset());
    }
    if (source.quantity() <= 0L) {
      throw new IllegalArgumentException(
          "OwnershipStakeBook.transferLandBlock 的源份额数量必须 > 0: id="
              + sourceId
              + " quantity="
              + source.quantity());
    }
    shares.put(
        sourceId,
        new OwnershipStake(
            sourceId,
            source.industry(),
            AssetKind.LAND,
            newOwner,
            newOperator,
            source.quantity(),
            source.kind(),
            source.location()));
  }

  /**
   * ★★ <b>严格部分拆分</b>：{@code 0 < quantity < source.quantity}（等于源数量不是拆分，是整条转移 —— 请用 {@link
   * #transfer}）；源行必留（剩余量正），返回新建份额 id。
   */
  public static AssetShareId split(
      Map<AssetShareId, OwnershipStake> shares,
      Map<IndustryId, IndustryDefinitionView> industries,
      AssetShareId sourceId,
      long quantity,
      ActorRef toOwner,
      ActorRef toOperator,
      AssetRightKind kind) {
    Objects.requireNonNull(shares, "shares 不得为 null");
    Objects.requireNonNull(sourceId, "sourceId 不得为 null");
    OwnershipStake source = shares.get(sourceId);
    if (source == null) {
      throw new IllegalArgumentException("OwnershipStakeBook.split 的源份额不存在: " + sourceId);
    }
    if (quantity <= 0L || quantity >= source.quantity()) {
      throw new IllegalArgumentException(
          "OwnershipStakeBook.split 必须 0 < quantity < 源数量（整条转移请用 transfer）: source="
              + sourceId
              + " quantity="
              + quantity
              + "，源数量="
              + source.quantity());
    }
    return transfer(shares, industries, sourceId, quantity, toOwner, toOperator, kind);
  }

  /**
   * ★★ <b>批写口</b>：按给定顺序校验全部 Move 并生成新 id；全过之后原子提交（校验失败时输入表一字不动）。
   *
   * @param shares 可写资产份额表（成功时就地更新；键 == 值内 id）；不得为 null
   * @param industries 产业模板（只读；空表 = 对侧尚未提供 ⇒ 不判 industry 存在）；可为 null（按空处理）
   * @param moves 移动列表；不得为 null、不得含 null；空列表 = no-op
   * @return 每次 Move 对应落到的目标份额 id（按 Move 顺序；一个新 id 可被多条 Move 共用 —— 同 tuple 合并）； 与传入 {@code moves} 等长
   */
  public static List<AssetShareId> apply(
      Map<AssetShareId, OwnershipStake> shares,
      Map<IndustryId, IndustryDefinitionView> industries,
      List<Move> moves) {
    Objects.requireNonNull(moves, "OwnershipStakeBook.apply 的 moves 不得为 null");
    List<PlannedMove> planned = new ArrayList<>(moves.size());
    for (Move move : moves) {
      Objects.requireNonNull(move, "OwnershipStakeBook.apply 的 moves 不得含 null");
      planned.add(
          new PlannedMove(
              move.source(),
              move.quantity(),
              null,
              move.toOwner(),
              move.toOperator(),
              move.kind()));
    }
    return execute(shares, industries, planned, false);
  }

  /**
   * ★★ <b>跨产业重建</b>（D-023 资产随迁的“跨 hex”一路）：把源份额的 quantity 拆到<b>另一个产业模板</b>下的同 {@link AssetKind}
   * 新份额（owner/operator/kind 可改）。与 {@link #apply} 同一原子承诺：全量校验后才写。
   *
   * <p>★ <b>与 {@link #apply} 的守恒口径差异</b>：{@code apply} 要求每个 {@code (industry, asset)} 一减一加归零；跨 hex
   * 时源/目标产业 id 不同（产业 id 带格键），这个分组守恒在语义上不成立。故本方法要求 <b>逐 {@link AssetKind} 的 Σquantity
   * 守恒</b>，并要求目标产业模板能承载该 asset（{@code capacityPerUnit} 含它）——绝不把 LAND 变 SHIP、也不在不能承载的产业下凭空造份额。
   */
  public record RebuildMove(
      AssetShareId source,
      long quantity,
      IndustryId toIndustry,
      ActorRef toOwner,
      ActorRef toOperator,
      AssetRightKind kind) {

    public RebuildMove {
      Objects.requireNonNull(source, "OwnershipStakeBook.RebuildMove.source 不得为 null");
      if (quantity <= 0L) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook.RebuildMove.quantity 必须 > 0: " + quantity);
      }
      Objects.requireNonNull(toIndustry, "OwnershipStakeBook.RebuildMove.toIndustry 不得为 null");
      Objects.requireNonNull(toOwner, "OwnershipStakeBook.RebuildMove.toOwner 不得为 null");
      Objects.requireNonNull(toOperator, "OwnershipStakeBook.RebuildMove.toOperator 不得为 null");
      Objects.requireNonNull(kind, "OwnershipStakeBook.RebuildMove.kind 不得为 null");
    }
  }

  /**
   * ★★ <b>跨产业重建的批写口</b>（D-023 跨 hex 资产随迁）：语义、原子性与“零行保留”口径同 {@link #apply}；
   * 唯一的差别是允许把源份额重建到<b>另一个产业模板</b>下（逐 {@link AssetKind} 守恒，目标模板必须能承载该 asset）。
   */
  public static List<AssetShareId> rebuild(
      Map<AssetShareId, OwnershipStake> shares,
      Map<IndustryId, IndustryDefinitionView> industries,
      List<RebuildMove> moves) {
    Objects.requireNonNull(moves, "OwnershipStakeBook.rebuild 的 moves 不得为 null");
    List<PlannedMove> planned = new ArrayList<>(moves.size());
    for (RebuildMove move : moves) {
      Objects.requireNonNull(move, "OwnershipStakeBook.rebuild 的 moves 不得含 null");
      planned.add(
          new PlannedMove(
              move.source(),
              move.quantity(),
              move.toIndustry(),
              move.toOwner(),
              move.toOperator(),
              move.kind()));
    }
    return execute(shares, industries, planned, true);
  }

  // ── 内部规划件 ───────────────────────────────────────────────────────────────────────

  /** 统一的移动规划（{@code toIndustry == null} = 同产业 {@code apply}；非 null = {@code rebuild}）。 */
  private record PlannedMove(
      AssetShareId source,
      long quantity,
      IndustryId toIndustry,
      ActorRef toOwner,
      ActorRef toOperator,
      AssetRightKind kind) {}

  /** 目标份额的合并键（同 tuple = 同一行，数量累加；地点是键的一部分）。 */
  private record ShareTuple(
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      AssetRightKind kind,
      ProductionLocation location) {}

  /** {@code (industry, asset)} 的守恒分组键。 */
  private record IndustryAsset(IndustryId industry, AssetKind asset) {}

  // ── 唯一执行体 ───────────────────────────────────────────────────────────────────────

  private static List<AssetShareId> execute(
      Map<AssetShareId, OwnershipStake> shares,
      Map<IndustryId, IndustryDefinitionView> industries,
      List<PlannedMove> moves,
      boolean rebuildMode) {
    Objects.requireNonNull(shares, "OwnershipStakeBook 的 shares 不得为 null");
    Objects.requireNonNull(moves, "OwnershipStakeBook 的 moves 不得为 null");
    if (moves.isEmpty()) {
      return List.of(); // ★ 无操作 ⇒ 不动表
    }
    Map<IndustryId, IndustryDefinitionView> knownIndustries =
        industries == null ? Map.of() : industries;

    // ── 1) 逐 Move 校验 + 守恒归组 + 源剩余覆盖层 ─────────────────────────────────────────
    Map<AssetShareId, Long> remaining = new LinkedHashMap<>();
    Map<IndustryAsset, Long> industryAssetDeltas = new LinkedHashMap<>();
    Map<AssetKind, Long> assetKindDeltas = new LinkedHashMap<>();
    for (PlannedMove move : moves) {
      OwnershipStake source = shares.get(move.source());
      if (source == null) {
        throw new IllegalArgumentException("OwnershipStakeBook 的源份额不存在（拒绝半笔操作）: " + move.source());
      }
      if (!move.source().equals(source.id())) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook 的键必须与 OwnershipStake.id 一致：键="
                + move.source()
                + "，行内 id="
                + source.id());
      }
      if (!knownIndustries.isEmpty() && !knownIndustries.containsKey(source.industry())) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook 的源份额产业不存在: source="
                + move.source()
                + " industry="
                + source.industry());
      }
      IndustryId toIndustry = rebuildMode ? move.toIndustry() : source.industry();
      IndustryDefinitionView targetIndustry = knownIndustries.get(toIndustry);
      if (targetIndustry == null) {
        // ★ 旧口径保留：industries 为空 = 对侧尚未提供 ⇒ apply 不判产业存在；rebuild 仍必须给出可承载模板。
        if (rebuildMode || !knownIndustries.isEmpty()) {
          throw new IllegalArgumentException("OwnershipStakeBook 的目标产业不存在（拒绝凭空造产业）: " + toIndustry);
        }
      }
      if (rebuildMode
          && targetIndustry != null
          && !targetIndustry.capacityPerUnit().containsKey(source.asset())) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook 的目标产业无法承载该资产: industry="
                + toIndustry
                + " asset="
                + source.asset()
                + "（capacityPerUnit="
                + targetIndustry.capacityPerUnit().keySet()
                + "）");
      }
      long available = remaining.getOrDefault(move.source(), source.quantity());
      if (move.quantity() > available) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook 的移动数量超过源份额（含本批已移走的部分；拒绝半笔操作）: source="
                + move.source()
                + " have="
                + available
                + " need="
                + move.quantity());
      }
      remaining.put(move.source(), available - move.quantity());
      industryAssetDeltas.merge(
          new IndustryAsset(source.industry(), source.asset()), -move.quantity(), Math::addExact);
      industryAssetDeltas.merge(
          new IndustryAsset(toIndustry, source.asset()), move.quantity(), Math::addExact);
      assetKindDeltas.merge(source.asset(), -move.quantity(), Math::addExact);
      assetKindDeltas.merge(source.asset(), move.quantity(), Math::addExact);
    }
    if (rebuildMode) {
      for (Map.Entry<AssetKind, Long> entry : assetKindDeltas.entrySet()) {
        if (entry.getValue() != 0L) {
          throw new IllegalArgumentException(
              "OwnershipStakeBook 的 Σ(asset) quantity 不守恒: "
                  + entry.getKey()
                  + " delta="
                  + entry.getValue());
        }
      }
    } else {
      for (Map.Entry<IndustryAsset, Long> entry : industryAssetDeltas.entrySet()) {
        if (entry.getValue() != 0L) {
          throw new IllegalArgumentException(
              "OwnershipStakeBook 的 Σ(industry, asset) quantity 不守恒: "
                  + entry.getKey()
                  + " delta="
                  + entry.getValue());
        }
      }
    }

    // ── 2) 目标行解析：同 tuple 合并（新建行先入 pending；已有行直接复用）──────────────────────
    Map<ShareTuple, AssetShareId> tupleIndex = buildTupleIndex(shares);
    Map<AssetShareId, OwnershipStake> pending = new LinkedHashMap<>();
    Map<AssetShareId, Long> movedOut = new LinkedHashMap<>();
    Map<AssetShareId, Long> movedIn = new LinkedHashMap<>();
    List<AssetShareId> destinations = new ArrayList<>(moves.size());
    for (PlannedMove move : moves) {
      OwnershipStake source = shares.get(move.source());
      IndustryId toIndustry = rebuildMode ? move.toIndustry() : source.industry();
      ShareTuple tuple =
          new ShareTuple(
              toIndustry,
              source.asset(),
              move.toOwner(),
              move.toOperator(),
              move.kind(),
              source.location());
      AssetShareId destination = tupleIndex.get(tuple);
      if (destination == null) {
        destination =
            nextShareId(
                shares,
                pending,
                toIndustry,
                source.asset(),
                move.toOwner(),
                move.toOperator(),
                move.kind(),
                source.location());
        pending.put(
            destination,
            new OwnershipStake(
                destination,
                toIndustry,
                source.asset(),
                move.toOwner(),
                move.toOperator(),
                0L,
                move.kind(),
                source.location()));
        tupleIndex.put(tuple, destination);
      }
      destinations.add(destination);
      if (!destination.equals(move.source())) {
        movedOut.merge(move.source(), move.quantity(), Math::addExact);
        movedIn.merge(destination, move.quantity(), Math::addExact);
      }
    }

    // ── 3) 终态数量（被本批碰过的每一行）───────────────────────────────────────────────────
    Set<AssetShareId> affected = new LinkedHashSet<>(movedIn.keySet());
    affected.addAll(movedOut.keySet());
    Map<AssetShareId, Long> finalQuantity = new LinkedHashMap<>();
    for (AssetShareId id : affected) {
      long before = shares.containsKey(id) ? shares.get(id).quantity() : 0L;
      long after =
          Math.subtractExact(
              Math.addExact(before, movedIn.getOrDefault(id, 0L)), movedOut.getOrDefault(id, 0L));
      if (after < 0L) {
        throw new IllegalStateException("OwnershipStakeBook 终态数量为负（内部规划错误）: " + id + " = " + after);
      }
      finalQuantity.put(id, after);
    }

    // ── 4) 零行保留：本批返回且终态为 0 的目标行保留，供调用方按返回 id 读取（其余 0 行删除）──
    Set<AssetShareId> keepZero = new LinkedHashSet<>();
    for (AssetShareId destination : new LinkedHashSet<>(destinations)) {
      if (finalQuantity.getOrDefault(destination, 1L) == 0L) {
        keepZero.add(destination);
      }
    }

    // ── 5) 提交（全部校验已过）────────────────────────────────────────────────────────
    Set<AssetShareId> destinationSet = new LinkedHashSet<>(destinations);
    for (Map.Entry<AssetShareId, Long> entry : finalQuantity.entrySet()) {
      AssetShareId id = entry.getKey();
      long quantity = entry.getValue();
      OwnershipStake base = pending.containsKey(id) ? pending.get(id) : shares.get(id);
      if (base == null) {
        throw new IllegalStateException("OwnershipStakeBook 提交时找不到份额行（表被并发修改？）: " + id);
      }
      if (quantity == 0L && !keepZero.contains(id) && !destinationSet.contains(id)) {
        shares.remove(id);
      } else {
        shares.put(
            id,
            new OwnershipStake(
                base.id(),
                base.industry(),
                base.asset(),
                base.owner(),
                base.operator(),
                quantity,
                base.kind(),
                base.location()));
      }
    }
    return List.copyOf(destinations);
  }

  /** 目标 tuple → 现成份额行 id（按 id 升序取第一条；重复 tuple 的旧档行不猜、确定性取最小 id）。 */
  private static Map<ShareTuple, AssetShareId> buildTupleIndex(
      Map<AssetShareId, OwnershipStake> shares) {
    List<OwnershipStake> ordered = new ArrayList<>(shares.values());
    ordered.sort(Comparator.comparing(share -> share.id().value()));
    Map<ShareTuple, AssetShareId> index = new LinkedHashMap<>();
    for (OwnershipStake share : ordered) {
      index.putIfAbsent(
          new ShareTuple(
              share.industry(),
              share.asset(),
              share.owner(),
              share.operator(),
              share.kind(),
              share.location()),
          share.id());
    }
    return index;
  }

  /** 新份额 id：从 0 起找第一个既不在同表、也不在本批已生成集合里的空闲序号（确定性；不解析旧档 opaque id）。 */
  private static AssetShareId nextShareId(
      Map<AssetShareId, OwnershipStake> shares,
      Map<AssetShareId, OwnershipStake> pending,
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      AssetRightKind kind,
      ProductionLocation location) {
    for (long sequence = 0L; sequence >= 0L; sequence++) {
      AssetShareId candidate =
          OwnershipStake.idOf(industry, asset, owner, operator, kind, location, sequence);
      if (!shares.containsKey(candidate) && !pending.containsKey(candidate)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException(
        "OwnershipStakeBook 无法分配空闲 sequence（fail-closed；table="
            + shares.size()
            + "，本批新建="
            + pending.size()
            + "）");
  }
}
