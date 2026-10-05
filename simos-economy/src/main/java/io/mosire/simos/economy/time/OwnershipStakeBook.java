package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.Pledge;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>E5a：资产份额的唯一写口</b>（计划 §4 硬约束 2：「资产份额转移只走抽出的 {@code OwnershipStakeBook.transfer}」）。
 *
 * <pre>
 * transfer(...)   一次整条/部分转移：source 减量（减到 0 且无任何质押引用才删行；有引用则留 quantity=0 行）
 * split(...)      严格部分拆分：source 必留行（0 &lt; quantity &lt; source.quantity），返回新份额 id
 * apply(...)      批写口：多条 Move 一次校验/一次提交（E2 租佃拆分的多腿、E5b 清算的多样转移都用它）
 * rebuild(...)    跨产业重建（D-023 跨 hex 资产随迁）：逐 AssetKind 守恒，目标产业必须能承载该资产
 * </pre>
 *
 * <p>★★ <b>先全量校验、生成好全部新 id、再写表</b>：本类用一份<b>覆盖层</b>（每条源份额的剩余量 + 本批新建行）完成全部移动与新 id
 * 生成，只有所有校验（源存在/数量够/industry 存在/守恒/质押终态上界/新 id 唯一）都通过后才开始写调用方的可写表。
 * 任一步校验失败 ⇒ 输入表<b>一字不动</b>。
 *
 * <p>★★ <b>2026-10-09 用户口径：ACTIVE 质押按比例跟到新份额</b>（不再是"移走就质押越界"的 fail-closed）：
 *
 * <ul>
 *   <li><b>拆分</b>：被移动那一份质押按比例拆到新份额（按各目标份额的接收量做最大余数法定点分配）；
 *   <li><b>合并</b>：同 {@code (industry, asset, owner, operator, kind)} 的行合并成一行，跟过去的质押也按
 *       {@code (debtContractId, modeId, priority)} 合并（同一债务合同在同一目标份额上的多笔 ACTIVE 质押累加）；
 *   <li><b>整对象转移</b>：整条源份额被移空 ⇒ 质押全部跟到目标份额，原质押行删除（不再留 0 数量的 ACTIVE 质押）；
 *   <li>部分转移 ⇒ 原质押按剩余量减量，跟走的部分落到目标份额。
 * </ul>
 *
 * <p>★★ <b>非 ACTIVE 质押（RELEASED / EXECUTED）的具名语义</b>：它们是<b>历史凭据</b>，不随份额走 ——
 * 保留对旧份额 id 的引用；若源份额被本批移空，<b>保留一条 {@code quantity = 0} 的零行</b>，让"质押 → 份额"的引用继续成立。
 * 零行不占任何额度，也不参与实物总量。
 *
 * <p>★★ <b>新份额 id 确定性</b>：新 id 走 {@link OwnershipStake#idOf(IndustryId, AssetKind, ActorRef, ActorRef,
 * OwnershipStake.RightKind, long)}，序号 = 从 0 起第一个既不在同表、也不在本批已生成集合里的空闲序号。★ 旧档 opaque id
 * 不解析、不改写，只按「同 tuple 已有行」直接合并。
 *
 * <p>★★ <b>新质押 id 确定性</b>：跟随产生的质押走 {@link #nextPledgeId}，形如 {@code pledge-follow-N}（N 从 0 起找
 * 第一个未占用序号）。它只表达"由跟随产生的新质押行"，与旧 {@code PledgeId} 不解析、不拼接（旧 id 可能带 {@code "."}，
 * 拼进新 id 会破坏地址解析）。同输入状态 ⇒ 同 id。
 *
 * <p>★ <b>不造粮/钱/权利</b>：本类只在份额表内部改 owner/operator/kind/quantity、拆分/合并行，并在质押表里按比例
 * 改数量/引用；不碰商品、货币、债务、劳动或 unit。
 *
 * <p>★ <b>可写表要求</b>：传入的 {@code shares} 与（非空时的）{@code pledges} 必须是可写 map（各调用点传的都是
 * {@code LinkedHashMap} 工作副本）；全部校验完成后才写它们。
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
      OwnershipStake.RightKind kind) {

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
   * ★★ <b>整条或部分转移</b>（{@code quantity} 可等于源数量；等于 = 整条转移，旧 id 删除）。被任何质押引用时保留
   * {@code quantity = 0} 行；ACTIVE 质押按比例跟到目标份额。
   *
   * @return 目标份额的稳定 id（若目标 tuple 已有现成行 ⇒ 返回那一行的 id）
   */
  public static AssetShareId transfer(
      Map<AssetShareId, OwnershipStake> shares,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      AssetShareId source,
      long quantity,
      ActorRef toOwner,
      ActorRef toOperator,
      OwnershipStake.RightKind kind) {
    List<AssetShareId> created =
        apply(
            shares,
            industries,
            pledges,
            List.of(new Move(source, quantity, toOwner, toOperator, kind)));
    return created.get(0);
  }

  /**
   * ★★ <b>严格部分拆分</b>：{@code 0 < quantity < source.quantity}（等于源数量不是拆分，是整条转移 —— 请用 {@link
   * #transfer}）；源行必留（剩余量正），返回新建份额 id。
   */
  public static AssetShareId split(
      Map<AssetShareId, OwnershipStake> shares,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      AssetShareId sourceId,
      long quantity,
      ActorRef toOwner,
      ActorRef toOperator,
      OwnershipStake.RightKind kind) {
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
    return transfer(shares, industries, pledges, sourceId, quantity, toOwner, toOperator, kind);
  }

  /**
   * ★★ <b>批写口</b>：按给定顺序校验全部 Move 并生成新 id；全过之后原子提交（校验失败时输入表一字不动）。
   *
   * @param shares 可写资产份额表（成功时就地更新；键 == 值内 id）；不得为 null
   * @param industries 产业模板（只读；空表 = 对侧尚未提供 ⇒ 不判 industry 存在）；可为 null（按空处理）
   * @param pledges 质押表（<b>可写</b>：ACTIVE 质押按比例跟随会就地改写它；空表/null = 不做跟随、也不判上界）
   * @param moves 移动列表；不得为 null、不得含 null；空列表 = no-op
   * @return 每次 Move 对应落到的目标份额 id（按 Move 顺序；一个新 id 可被多条 Move 共用 —— 同 tuple 合并）；
   *     与传入 {@code moves} 等长
   */
  public static List<AssetShareId> apply(
      Map<AssetShareId, OwnershipStake> shares,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      List<Move> moves) {
    Objects.requireNonNull(moves, "OwnershipStakeBook.apply 的 moves 不得为 null");
    List<PlannedMove> planned = new ArrayList<>(moves.size());
    for (Move move : moves) {
      Objects.requireNonNull(move, "OwnershipStakeBook.apply 的 moves 不得含 null");
      planned.add(
          new PlannedMove(
              move.source(), move.quantity(), null, move.toOwner(), move.toOperator(), move.kind()));
    }
    return execute(shares, industries, pledges, planned, false);
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
      OwnershipStake.RightKind kind) {

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
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
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
    return execute(shares, industries, pledges, planned, true);
  }

  // ── 内部规划件 ───────────────────────────────────────────────────────────────────────

  /** 统一的移动规划（{@code toIndustry == null} = 同产业 {@code apply}；非 null = {@code rebuild}）。 */
  private record PlannedMove(
      AssetShareId source,
      long quantity,
      IndustryId toIndustry,
      ActorRef toOwner,
      ActorRef toOperator,
      OwnershipStake.RightKind kind) {}

  /** 目标份额的合并键（同 tuple = 同一行，数量与质押都比例累加）。 */
  private record ShareTuple(
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      OwnershipStake.RightKind kind) {}

  /** 跟随质押的合并键：同一目标份额上「同债务合同 + 同 mode + 同优先级」的 ACTIVE 质押累加。 */
  private record PledgeMergeKey(
      AssetShareId share, DebtContractId debtContractId, ProductionModeId modeId, int priority) {}

  /** {@code (industry, asset)} 的守恒分组键。 */
  private record IndustryAsset(IndustryId industry, AssetKind asset) {}

  // ── 唯一执行体 ───────────────────────────────────────────────────────────────────────

  private static List<AssetShareId> execute(
      Map<AssetShareId, OwnershipStake> shares,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      List<PlannedMove> moves,
      boolean rebuildMode) {
    Objects.requireNonNull(shares, "OwnershipStakeBook 的 shares 不得为 null");
    Objects.requireNonNull(moves, "OwnershipStakeBook 的 moves 不得为 null");
    if (moves.isEmpty()) {
      return List.of(); // ★ 无操作 ⇒ 不动表
    }
    Map<IndustryId, Industry> knownIndustries = industries == null ? Map.of() : industries;
    Map<PledgeId, Pledge> knownPledges = pledges == null ? Map.of() : pledges;

    // ── 1) 逐 Move 校验 + 守恒归组 + 源剩余覆盖层 ─────────────────────────────────────────
    Map<AssetShareId, Long> remaining = new LinkedHashMap<>();
    Map<IndustryAsset, Long> industryAssetDeltas = new LinkedHashMap<>();
    Map<AssetKind, Long> assetKindDeltas = new LinkedHashMap<>();
    for (PlannedMove move : moves) {
      OwnershipStake source = shares.get(move.source());
      if (source == null) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook 的源份额不存在（拒绝半笔操作）: " + move.source());
      }
      if (!move.source().equals(source.id())) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook 的键必须与 OwnershipStake.id 一致：键=" + move.source() + "，行内 id=" + source.id());
      }
      if (!knownIndustries.isEmpty() && !knownIndustries.containsKey(source.industry())) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook 的源份额产业不存在: source="
                + move.source()
                + " industry="
                + source.industry());
      }
      IndustryId toIndustry = rebuildMode ? move.toIndustry() : source.industry();
      Industry targetIndustry = knownIndustries.get(toIndustry);
      if (targetIndustry == null) {
        // ★ 旧口径保留：industries 为空 = 对侧尚未提供 ⇒ apply 不判产业存在；rebuild 仍必须给出可承载模板。
        if (rebuildMode || !knownIndustries.isEmpty()) {
          throw new IllegalArgumentException(
              "OwnershipStakeBook 的目标产业不存在（拒绝凭空造产业）: " + toIndustry);
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
    Map<AssetShareId, Map<AssetShareId, Long>> flowsBySource = new LinkedHashMap<>();
    List<AssetShareId> destinations = new ArrayList<>(moves.size());
    for (PlannedMove move : moves) {
      OwnershipStake source = shares.get(move.source());
      IndustryId toIndustry = rebuildMode ? move.toIndustry() : source.industry();
      ShareTuple tuple =
          new ShareTuple(toIndustry, source.asset(), move.toOwner(), move.toOperator(), move.kind());
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
                move.kind());
        pending.put(
            destination,
            new OwnershipStake(
                destination,
                toIndustry,
                source.asset(),
                move.toOwner(),
                move.toOperator(),
                0L,
                move.kind()));
        tupleIndex.put(tuple, destination);
      }
      destinations.add(destination);
      if (!destination.equals(move.source())) {
        movedOut.merge(move.source(), move.quantity(), Math::addExact);
        movedIn.merge(destination, move.quantity(), Math::addExact);
        flowsBySource
            .computeIfAbsent(move.source(), ignored -> new LinkedHashMap<>())
            .merge(destination, move.quantity(), Math::addExact);
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

    // ── 4) 零行保留：任何质押（含 RELEASED/EXECUTED）指名的被移空行；以及本批返回的已存在目标行 ──
    Set<AssetShareId> keepZero = new LinkedHashSet<>();
    if (!knownPledges.isEmpty()) {
      for (Pledge pledge : knownPledges.values()) {
        if (pledge != null && movedOut.containsKey(pledge.assetShareId())) {
          keepZero.add(pledge.assetShareId());
        }
      }
    }
    for (AssetShareId destination : new LinkedHashSet<>(destinations)) {
      if (finalQuantity.getOrDefault(destination, 1L) == 0L) {
        keepZero.add(destination);
      }
    }

    // ── 5) ACTIVE 质押按比例跟随（定点 + 最大余数；合并到目标份额的同合同/mode/优先级）────────
    Map<PledgeId, Pledge> plannedPledges = new LinkedHashMap<>(knownPledges);
    Set<PledgeId> createdPledgeIds = new LinkedHashSet<>();
    if (!knownPledges.isEmpty()) {
      planPledgeFollow(
          shares, finalQuantity, plannedPledges, createdPledgeIds, movedOut, flowsBySource);
    }

    // ── 6) 终态校验：Σ活跃质押 ≤ 终态 quantity；所有质押指名的份额都必须存在 ─────────────────
    requireFinalPledgeBounds(shares, finalQuantity, plannedPledges);

    // ── 7) 提交（全部校验已过）：质押表先写，再写份额表 ─────────────────────────────────────
    if (!knownPledges.isEmpty()) {
      pledges.clear();
      pledges.putAll(plannedPledges);
    }
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
                base.kind()));
      }
    }
    return List.copyOf(destinations);
  }

  /** 目标 tuple → 现成份额行 id（按 id 升序取第一条；重复 tuple 的旧档行不猜、确定性取最小 id）。 */
  private static Map<ShareTuple, AssetShareId> buildTupleIndex(Map<AssetShareId, OwnershipStake> shares) {
    List<OwnershipStake> ordered = new ArrayList<>(shares.values());
    ordered.sort(Comparator.comparing(share -> share.id().value()));
    Map<ShareTuple, AssetShareId> index = new LinkedHashMap<>();
    for (OwnershipStake share : ordered) {
      index.putIfAbsent(
          new ShareTuple(
              share.industry(), share.asset(), share.owner(), share.operator(), share.kind()),
          share.id());
    }
    return index;
  }

  /**
   * ★★ ACTIVE 质押跟随的核心：逐源份额、逐 ACTIVE 质押按"移动量 / 源数量"做定点比例分配
   * （最大余数法，余数相同按目标 id 升序），生成/合并目标份额上的质押；原质押按剩余量减量，整条移走则删除。
   *
   * <p>★ 非 ACTIVE 质押原样保留（引用旧份额；{@link #execute} 已把被移空的旧行留成 quantity=0 零行）。
   *
   * <p>★ 每次处理一个源份额时都<b>重新扫描</b> {@code plannedPledges} 里该源上的 ACTIVE 质押 —— 这样"先落到某源份额、
   * 之后该源份额又被本批移走"的跟随质押也会参与比例跟随，不会在终态变成 0 行上的 ACTIVE 质押。
   */
  private static void planPledgeFollow(
      Map<AssetShareId, OwnershipStake> shares,
      Map<AssetShareId, Long> finalQuantity,
      Map<PledgeId, Pledge> plannedPledges,
      Set<PledgeId> createdPledgeIds,
      Map<AssetShareId, Long> movedOut,
      Map<AssetShareId, Map<AssetShareId, Long>> flowsBySource) {
    // 现成 ACTIVE 质押的合并索引（按 pledge id 升序，确定性）。
    Map<PledgeMergeKey, PledgeId> mergeIndex = new LinkedHashMap<>();
    List<PledgeId> pledgeIds = sortedPledgeIds(plannedPledges);
    for (PledgeId pledgeId : pledgeIds) {
      Pledge pledge = plannedPledges.get(pledgeId);
      if (pledge != null && pledge.status() == Pledge.Status.ACTIVE) {
        mergeIndex.putIfAbsent(mergeKeyOf(pledge), pledgeId);
      }
    }
    // ★★ P2-E：逐份额的 ACTIVE 质押合计（终态上界的实时读数）。跟随写回时同步维护，见下面的两步：
    //   ① 主跟随（逐源逐质押按比例 floor）；② **源侧补差**（floor 会漏掉"总跟随量 < 源数量减少量"的部分）。
    Map<AssetShareId, Long> activeByShare = new LinkedHashMap<>();
    for (Pledge pledge : plannedPledges.values()) {
      if (pledge != null && pledge.status() == Pledge.Status.ACTIVE) {
        activeByShare.merge(pledge.assetShareId(), pledge.quantity(), Math::addExact);
      }
    }
    List<AssetShareId> sources = new ArrayList<>(flowsBySource.keySet());
    sources.sort(Comparator.comparing(AssetShareId::value));
    for (AssetShareId sourceId : sources) {
      OwnershipStake source = shares.get(sourceId);
      if (source == null || source.quantity() <= 0L) {
        continue;
      }
      long totalOut = movedOut.getOrDefault(sourceId, 0L);
      if (totalOut <= 0L) {
        continue;
      }
      Map<AssetShareId, Long> flows = flowsBySource.get(sourceId);
      List<PledgeId> pledgesOnSource = new ArrayList<>();
      for (PledgeId pledgeId : sortedPledgeIds(plannedPledges)) {
        Pledge pledge = plannedPledges.get(pledgeId);
        if (pledge != null
            && pledge.status() == Pledge.Status.ACTIVE
            && pledge.assetShareId().equals(sourceId)) {
          pledgesOnSource.add(pledgeId);
        }
      }
      for (PledgeId pledgeId : pledgesOnSource) {
        Pledge pledge = plannedPledges.get(pledgeId);
        if (pledge == null || pledge.status() != Pledge.Status.ACTIVE) {
          continue;
        }
        long quantity = pledge.quantity();
        if (quantity <= 0L) {
          continue;
        }
        Map<AssetShareId, Long> followAmounts =
            proportionalFollow(quantity, source.quantity(), flows);
        long movedPledge = 0L;
        List<AssetShareId> destinations = new ArrayList<>(followAmounts.keySet());
        destinations.sort(Comparator.comparing(AssetShareId::value));
        for (AssetShareId destination : destinations) {
          long amount = followAmounts.get(destination);
          if (amount <= 0L) {
            continue;
          }
          movedPledge = Math.addExact(movedPledge, amount);
          followPledgeTo(
              plannedPledges, mergeIndex, createdPledgeIds, pledgeId, pledge, destination, amount);
          activeByShare.merge(destination, amount, Math::addExact);
        }
        // 源质押按已跟随量减量（全跟走 ⇒ 删行）；同步源侧合计。
        activeByShare.merge(sourceId, -movedPledge, Math::addExact);
      }
      // ── ② 源侧补差（P2-E 修）：逐质押 floor + 最大余数可能**整体少跟**（源数量只减了 1，而每笔质押的
      //   比例都 floor 到 0）⇒ 源上剩下的 ACTIVE 合计会超过源终态数量。这里把差额补跟到有余额的目标份额上：
      //   只动本批 flows 里的目标，且以**目标终态余额**为硬上限（不制造新的越界）。补不动 ⇒ 具名抛（不静默）。
      long finalSource = finalQuantity.getOrDefault(sourceId, source.quantity());
      long excess = activeByShare.getOrDefault(sourceId, 0L) - finalSource;
      if (excess > 0L) {
        List<AssetShareId> destinations = new ArrayList<>(flows.keySet());
        destinations.sort(Comparator.comparing(AssetShareId::value));
        List<PledgeId> repledgeable = new ArrayList<>();
        for (PledgeId pledgeId : sortedPledgeIds(plannedPledges)) {
          Pledge pledge = plannedPledges.get(pledgeId);
          if (pledge != null
              && pledge.status() == Pledge.Status.ACTIVE
              && pledge.assetShareId().equals(sourceId)
              && pledge.quantity() > 0L) {
            repledgeable.add(pledgeId);
          }
        }
        for (PledgeId pledgeId : repledgeable) {
          if (excess <= 0L) {
            break;
          }
          Pledge pledge = plannedPledges.get(pledgeId);
          if (pledge == null || pledge.status() != Pledge.Status.ACTIVE) {
            continue;
          }
          for (AssetShareId destination : destinations) {
            if (excess <= 0L) {
              break;
            }
            long spare =
                finalQuantity.getOrDefault(destination, 0L)
                    - activeByShare.getOrDefault(destination, 0L);
            if (spare <= 0L) {
              continue;
            }
            long amount = Math.min(excess, Math.min(pledge.quantity(), spare));
            if (amount <= 0L) {
              continue;
            }
            followPledgeTo(
                plannedPledges, mergeIndex, createdPledgeIds, pledgeId, pledge, destination, amount);
            activeByShare.merge(destination, amount, Math::addExact);
            activeByShare.merge(sourceId, -amount, Math::addExact);
            excess -= amount;
          }
        }
        if (excess > 0L) {
          throw new IllegalStateException(
              "OwnershipStakeBook 的质押跟随无法在终态上界内补齐（拒绝越界）：源份额="
                  + sourceId
                  + " 尚缺="
                  + excess
                  + "（终态数量="
                  + finalSource
                  + "，ACTIVE 合计="
                  + activeByShare.getOrDefault(sourceId, 0L)
                  + "）");
        }
      }
    }
  }

  /**
   * 把 {@code sourcePledgeId} 上的 {@code amount} 跟随到 {@code destination}：同 (债务合同, mode, 优先级) 的
   * 目标质押存在则累加，否则新发一条 {@code pledge-follow-N}；源质押按量减量（归零即删）。只动质押表，不动份额表。
   */
  private static void followPledgeTo(
      Map<PledgeId, Pledge> plannedPledges,
      Map<PledgeMergeKey, PledgeId> mergeIndex,
      Set<PledgeId> createdPledgeIds,
      PledgeId sourcePledgeId,
      Pledge pledge,
      AssetShareId destination,
      long amount) {
    PledgeMergeKey key =
        new PledgeMergeKey(destination, pledge.debtContractId(), pledge.modeId(), pledge.priority());
    PledgeId existingId = mergeIndex.get(key);
    Pledge existing = existingId == null ? null : plannedPledges.get(existingId);
    if (existing != null
        && existing.status() == Pledge.Status.ACTIVE
        && !existingId.equals(sourcePledgeId)) {
      plannedPledges.put(
          existingId, existing.withQuantity(Math.addExact(existing.quantity(), amount)));
    } else {
      PledgeId followId = nextPledgeId(plannedPledges, createdPledgeIds);
      plannedPledges.put(
          followId,
          new Pledge(
              followId,
              pledge.debtContractId(),
              destination,
              amount,
              pledge.modeId(),
              pledge.priority(),
              Pledge.Status.ACTIVE));
      createdPledgeIds.add(followId);
      mergeIndex.put(key, followId);
    }
    Pledge current = plannedPledges.get(sourcePledgeId);
    if (current == null) {
      throw new IllegalStateException("OwnershipStakeBook 跟随写回找不到源质押行: " + sourcePledgeId);
    }
    long left = Math.subtractExact(current.quantity(), amount);
    if (left > 0L) {
      plannedPledges.put(sourcePledgeId, current.withQuantity(left));
    } else {
      plannedPledges.remove(sourcePledgeId);
      PledgeMergeKey ownKey = mergeKeyOf(pledge);
      if (ownKey != null && sourcePledgeId.equals(mergeIndex.get(ownKey))) {
        mergeIndex.remove(ownKey);
      }
    }
  }

  /** 按 pledge id 升序（确定性）的键列表。 */
  private static List<PledgeId> sortedPledgeIds(Map<PledgeId, Pledge> plannedPledges) {
    List<PledgeId> ids = new ArrayList<>(plannedPledges.keySet());
    ids.sort(Comparator.comparing(PledgeId::value));
    return ids;
  }

  /** 一条 ACTIVE 质押的合并键（RELEASED/EXECUTED 不合并，因此不会走到这里）。 */
  private static PledgeMergeKey mergeKeyOf(Pledge pledge) {
    return new PledgeMergeKey(
        pledge.assetShareId(), pledge.debtContractId(), pledge.modeId(), pledge.priority());
  }

  /**
   * ★★ <b>定点比例分配（最大余数法）</b>：把 {@code pledgeQuantity} 按 {@code flows}（目标 → 接收量）的比例
   * 分到各目标，余数按 remainder 降序、同余数按目标 id 升序补 1。★ 全源移走（Σflows == sourceQuantity）时
   * 结果精确等于 {@code pledgeQuantity}（不会留下 0 数量的 ACTIVE 质押）。
   */
  private static Map<AssetShareId, Long> proportionalFollow(
      long pledgeQuantity, long sourceQuantity, Map<AssetShareId, Long> flows) {
    if (sourceQuantity <= 0L) {
      throw new IllegalStateException("OwnershipStakeBook 按比例跟随的源数量必须 > 0: " + sourceQuantity);
    }
    List<AssetShareId> destinations = new ArrayList<>(flows.keySet());
    destinations.sort(Comparator.comparing(AssetShareId::value));
    BigInteger source = BigInteger.valueOf(sourceQuantity);
    BigInteger pledge = BigInteger.valueOf(pledgeQuantity);
    long totalOut = 0L;
    for (AssetShareId destination : destinations) {
      totalOut = Math.addExact(totalOut, flows.get(destination));
    }
    BigInteger target = pledge.multiply(BigInteger.valueOf(totalOut)).divide(source);
    long targetQuantity = target.longValueExact();
    Map<AssetShareId, Long> result = new LinkedHashMap<>();
    Map<AssetShareId, BigInteger> remainders = new LinkedHashMap<>();
    long assigned = 0L;
    for (AssetShareId destination : destinations) {
      BigInteger numerator = pledge.multiply(BigInteger.valueOf(flows.get(destination)));
      BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(source);
      long share = quotientAndRemainder[0].longValueExact();
      assigned = Math.addExact(assigned, share);
      result.put(destination, share);
      remainders.put(destination, quotientAndRemainder[1]);
    }
    long leftover = Math.subtractExact(targetQuantity, assigned);
    if (leftover < 0L) {
      throw new IllegalStateException(
          "OwnershipStakeBook 质押跟随分配超过目标量（内部错误）: target=" + targetQuantity + " assigned=" + assigned);
    }
    if (leftover > 0L) {
      List<AssetShareId> byRemainder = new ArrayList<>(destinations);
      byRemainder.sort(
          Comparator.comparing((AssetShareId id) -> remainders.get(id))
              .reversed()
              .thenComparing(AssetShareId::value));
      for (int i = 0; i < leftover && i < byRemainder.size(); i++) {
        AssetShareId destination = byRemainder.get(i);
        result.put(destination, Math.addExact(result.get(destination), 1L));
      }
      if (leftover > byRemainder.size()) {
        throw new IllegalStateException(
            "OwnershipStakeBook 质押跟随余数超过目标数（内部错误）: leftover=" + leftover);
      }
    }
    return result;
  }

  /**
   * ACTIVE 质押上界终态守卫：Σ活跃质押 ≤ 终态 quantity；所有质押（不分状态）指名的份额都必须仍在终态里
   * （被移空的旧行由零行保留兜住）。{@code plannedPledges} 为空 = 对侧尚未提供 ⇒ 整体 no-op。
   */
  private static void requireFinalPledgeBounds(
      Map<AssetShareId, OwnershipStake> shares,
      Map<AssetShareId, Long> finalQuantity,
      Map<PledgeId, Pledge> plannedPledges) {
    if (plannedPledges.isEmpty()) {
      return;
    }
    Map<AssetShareId, Long> active = new LinkedHashMap<>();
    for (Pledge pledge : plannedPledges.values()) {
      if (pledge == null) {
        continue;
      }
      long quantityAfter = quantityAfter(pledge.assetShareId(), shares, finalQuantity);
      if (quantityAfter < 0L) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook 的质押指名的份额不存在（拒绝静默丢引用）: " + pledge.assetShareId());
      }
      if (pledge.status() == Pledge.Status.ACTIVE) {
        active.merge(pledge.assetShareId(), pledge.quantity(), Math::addExact);
      }
    }
    for (Map.Entry<AssetShareId, Long> entry : active.entrySet()) {
      long quantityAfter = quantityAfter(entry.getKey(), shares, finalQuantity);
      if (quantityAfter <= 0L) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook 的 ACTIVE 质押指名的份额被移走（按比例跟随失败）: " + entry.getKey());
      }
      if (entry.getValue() > quantityAfter) {
        throw new IllegalArgumentException(
            "OwnershipStakeBook 的 Σ活跃质押必须 ≤ 份额 quantity：份额="
                + entry.getKey()
                + " 质押合计="
                + entry.getValue()
                + "，终态数量="
                + quantityAfter);
      }
    }
  }

  /** 某份额的终态数量（本批碰过的看覆盖层；没碰过的看原表）；不存在 ⇒ -1。 */
  private static long quantityAfter(
      AssetShareId id, Map<AssetShareId, OwnershipStake> shares, Map<AssetShareId, Long> finalQuantity) {
    Long after = finalQuantity.get(id);
    if (after != null) {
      return after;
    }
    OwnershipStake share = shares.get(id);
    return share == null ? -1L : share.quantity();
  }

  /** 新份额 id：从 0 起找第一个既不在同表、也不在本批已生成集合里的空闲序号（确定性；不解析旧档 opaque id）。 */
  private static AssetShareId nextShareId(
      Map<AssetShareId, OwnershipStake> shares,
      Map<AssetShareId, OwnershipStake> pending,
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      OwnershipStake.RightKind kind) {
    for (long sequence = 0L; sequence >= 0L; sequence++) {
      AssetShareId candidate = OwnershipStake.idOf(industry, asset, owner, operator, kind, sequence);
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

  /** 跟随产生的新质押 id：{@code pledge-follow-N}，N 从 0 起找第一个未占用序号（确定性、不拼旧 id）。 */
  private static PledgeId nextPledgeId(
      Map<PledgeId, Pledge> plannedPledges, Set<PledgeId> createdPledgeIds) {
    for (long sequence = 0L; sequence >= 0L; sequence++) {
      PledgeId candidate = PledgeId.parse("pledge-follow-" + sequence);
      if (!plannedPledges.containsKey(candidate) && !createdPledgeIds.contains(candidate)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("OwnershipStakeBook 无法分配空闲质押序号（fail-closed）");
  }
}
