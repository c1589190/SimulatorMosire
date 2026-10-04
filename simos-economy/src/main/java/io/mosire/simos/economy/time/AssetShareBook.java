package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.Pledge;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>E5a：资产份额的唯一写口</b>（计划 §4 硬约束 2：「资产份额转移只走抽出的 {@code AssetShareBook.transfer}」）。
 *
 * <pre>
 * transfer(...)   一次整条/部分转移：source 减量（减到 0 时**无任何质押引用**才删行；有引用则留 quantity=0 行，见 apply 的 E5b 注）
 * split(...)      严格部分拆分：source 必留行（0 &lt; quantity &lt; source.quantity），返回新份额 id
 * apply(...)      批写口：多条 Move 一次校验/一次提交（E2 租佃拆分的多腿、E5b 清算的多样转移都用它）
 * </pre>
 *
 * <p>★★ <b>先全量校验、生成好全部新 id、再写 map</b>：{@link #apply} 用一份 <b>覆盖层</b>（每条源份额的剩余量 + 本批新建行）完成全部移动与新 id
 * 生成，只有所有校验（源存在/数量够/industry 存在/Σ 逐 {@code (industry, asset)} 守恒/活跃质押上界/新 id
 * 唯一）都通过后才开始写调用方的可写表。任一步校验失败 ⇒ 输入表<b>一字不动</b> —— 不存在「先改源行、后生成 id 抛错」的半笔形态。
 *
 * <p>★★ <b>不做整表拷贝</b>：E2 自动组织每个成功组织一次就要落一批租佃腿，而份额表是全世界一张表（真档可到 10^5 行）；每次 apply 复制整表会让组织阶段退化成
 * O(N²)。故本类只在覆盖层上记「被这条 Move 碰过的源份额剩余」 与「本批新建行」，提交时只改受影响的行 —— 校验成本 O(moves + ACTIVE 质押数)。
 *
 * <p>★★ <b>确定性 id</b>：新 id 走 {@link AssetShare#idOf(IndustryId, AssetKind, ActorRef, ActorRef,
 * AssetShare.RightKind, long)}，序号 = 从 0 起第一个既不在同表、也不在本批已生成集合里的空闲序号（同一次调用的 输入确定 ⇒ 输出 id 确定；不用
 * UUID/时间/随机数）。★ 旧档 opaque id 不解析、不改写，只按「同 key 撞车」检测 —— 因此旧 {@code use-…} 行不会让新转移失败。
 *
 * <p>★★ <b>三段「对侧已提供」语义</b>（与 {@code EconomyData} 的跨表守卫同款，保证逐组件构造的中间态仍可用）：
 *
 * <ul>
 *   <li>{@code industries} 为空 ⇒ 不判 industry 存在；非空 ⇒ 每次操作涉及的产业必须存在（fail-closed）；
 *   <li>{@code pledges} 为空 ⇒ 不判质押上界；非空 ⇒ 逐 ACTIVE 质押求和，任一受影响份额的 {@code Σ活跃质押 ≤ 操作后 quantity}
 *       必须成立（被移走而消失的份额数量记 0，质押仍挂在旧 id 上 ⇒ fail-closed）；
 *   <li>{@code shares} 为空/无操作 ⇒ 本类整体 no-op。
 * </ul>
 *
 * <p>★ <b>不造粮/钱/权利</b>：本类只在份额表内部改 owner/operator/kind/quantity 并拆分/合并行；新行与源行同 {@code (industry,
 * asset)}（industry/asset 本身不可改），故每次操作在同一个守恒分组里一减一加。 它不碰商品、货币、债务、劳动或 unit。
 *
 * <p>★ <b>可写表要求</b>：传入的 {@code shares} 必须是可写 map（各调用点传的都是 {@code LinkedHashMap} 工作副本）； 校验完成后提交阶段的
 * {@code put/remove} 对可写表不会失败（非可写表或运行时故障不在本类的原子承诺内）。
 */
public final class AssetShareBook {

  private AssetShareBook() {}

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
      AssetShare.RightKind kind) {

    public Move {
      Objects.requireNonNull(source, "AssetShareBook.Move.source 不得为 null");
      if (quantity <= 0L) {
        throw new IllegalArgumentException("AssetShareBook.Move.quantity 必须 > 0: " + quantity);
      }
      Objects.requireNonNull(toOwner, "AssetShareBook.Move.toOwner 不得为 null");
      Objects.requireNonNull(toOperator, "AssetShareBook.Move.toOperator 不得为 null");
      Objects.requireNonNull(kind, "AssetShareBook.Move.kind 不得为 null");
    }
  }

  /**
   * ★★ <b>整条或部分转移</b>（{@code quantity} 可等于源数量；等于 = 整条转移，旧 id 删除并返回新 id；★ 但被任何质押引用时保留 quantity=0 行，见
   * {@link #apply} 的 E5b 注）。
   *
   * @return 新份额的稳定 id
   */
  public static AssetShareId transfer(
      Map<AssetShareId, AssetShare> shares,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      AssetShareId source,
      long quantity,
      ActorRef toOwner,
      ActorRef toOperator,
      AssetShare.RightKind kind) {
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
      Map<AssetShareId, AssetShare> shares,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      AssetShareId sourceId,
      long quantity,
      ActorRef toOwner,
      ActorRef toOperator,
      AssetShare.RightKind kind) {
    Objects.requireNonNull(shares, "shares 不得为 null");
    Objects.requireNonNull(sourceId, "sourceId 不得为 null");
    AssetShare source = shares.get(sourceId);
    if (source == null) {
      throw new IllegalArgumentException("AssetShareBook.split 的源份额不存在: " + sourceId);
    }
    if (quantity <= 0L || quantity >= source.quantity()) {
      throw new IllegalArgumentException(
          "AssetShareBook.split 必须 0 < quantity < 源数量（整条转移请用 transfer）: source="
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
   * <p>★★ <b>E5b：整条转移时的"零行保留"</b>——某个源份额被本批移空、但 {@code pledges} 里仍有<b>任何状态</b>（含
   * RELEASED/EXECUTED）的质押指名它时，<b>不删行</b>而是保留一条 {@code quantity = 0} 的同 id 行。理由：{@code EconomyData}
   * 的质押跨表守卫要求"质押指名的资产份额必须存在"（不分状态），删行会让已执行/已释放的质押把状态树变成构造失败； 而 {@code AssetShare.quantity == 0}
   * 是文档允许的合法状态。没有质押引用该 id 时仍按旧行为删行（旧路径逐值不变）。
   *
   * @param shares 可写资产份额表（成功时就地更新；键 == 值内 id）；不得为 null
   * @param industries 产业模板（只读；空表 = 对侧尚未提供 ⇒ 不判 industry 存在）；可为 null（按空处理）
   * @param pledges 质押表（只读；空表/null = 不判质押上界；ACTIVE 之外的质押不占额度，但影响"移空是否保留零行"）
   * @param moves 移动列表；不得为 null、不得含 null；空列表 = no-op
   * @return 本次新建的份额 id（按 Move 顺序；与传入 {@code moves} 等长）
   */
  public static List<AssetShareId> apply(
      Map<AssetShareId, AssetShare> shares,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      List<Move> moves) {
    Objects.requireNonNull(shares, "AssetShareBook.apply 的 shares 不得为 null");
    Objects.requireNonNull(moves, "AssetShareBook.apply 的 moves 不得为 null");
    if (moves.isEmpty()) {
      return List.of(); // ★ 无操作 ⇒ 不动表、不建覆盖层（空表基线的调用无成本）
    }
    Map<IndustryId, Industry> knownIndustries = industries == null ? Map.of() : industries;
    Map<PledgeId, Pledge> knownPledges = pledges == null ? Map.of() : pledges;
    // ── 校验/规划覆盖层：被 Move 碰过的源份额剩余量、本批新建行、守恒分组增减 ─────────────────
    Map<AssetShareId, Long> remaining = new LinkedHashMap<>();
    Map<AssetShareId, AssetShare> pending = new LinkedHashMap<>();
    List<AssetShareId> created = new ArrayList<>(moves.size());
    Map<IndustryAsset, Long> deltas = new LinkedHashMap<>();
    for (Move move : moves) {
      Objects.requireNonNull(move, "AssetShareBook.apply 的 moves 不得含 null");
      AssetShare source = shares.get(move.source());
      if (source == null) {
        throw new IllegalArgumentException(
            "AssetShareBook.apply 的源份额不存在（拒绝半笔操作）: " + move.source());
      }
      if (!move.source().equals(source.id())) {
        throw new IllegalArgumentException(
            "AssetShareBook 的键必须与 AssetShare.id 一致：键=" + move.source() + "，行内 id=" + source.id());
      }
      if (!knownIndustries.isEmpty() && !knownIndustries.containsKey(source.industry())) {
        throw new IllegalArgumentException(
            "AssetShareBook.apply 的源份额产业不存在: source="
                + move.source()
                + " industry="
                + source.industry());
      }
      long available = remaining.getOrDefault(move.source(), source.quantity());
      if (move.quantity() > available) {
        throw new IllegalArgumentException(
            "AssetShareBook.apply 的移动数量超过源份额（含本批已移走的部分；拒绝半笔操作）: source="
                + move.source()
                + " have="
                + available
                + " need="
                + move.quantity());
      }
      remaining.put(move.source(), available - move.quantity());
      AssetShareId newId =
          nextShareId(
              shares,
              pending,
              source.industry(),
              source.asset(),
              move.toOwner(),
              move.toOperator(),
              move.kind());
      pending.put(
          newId,
          new AssetShare(
              newId,
              source.industry(),
              source.asset(),
              move.toOwner(),
              move.toOperator(),
              move.quantity(),
              move.kind()));
      created.add(newId);
      // 守恒：同一 (industry, asset) 上一减一加；deltas 逐 key 必须归零（见下面的断言）。
      IndustryAsset key = new IndustryAsset(source.industry(), source.asset());
      deltas.merge(key, -move.quantity(), Math::addExact);
      deltas.merge(key, move.quantity(), Math::addExact);
    }
    for (Map.Entry<IndustryAsset, Long> entry : deltas.entrySet()) {
      if (entry.getValue() != 0L) {
        throw new IllegalArgumentException(
            "AssetShareBook 的 Σ(industry, asset) quantity 不守恒: "
                + entry.getKey()
                + " delta="
                + entry.getValue());
      }
    }
    requirePledgeBounds(shares, remaining, knownPledges);
    // ── E5b：被本批移空的源份额是否仍被任何质押（含 RELEASED/EXECUTED）指名 ⇒ 保留 quantity=0 行 ─────────
    Set<AssetShareId> keepZeroRow = new LinkedHashSet<>();
    if (!knownPledges.isEmpty()) {
      Set<AssetShareId> movedSources = new LinkedHashSet<>();
      for (Move move : moves) {
        movedSources.add(move.source());
      }
      for (Pledge pledge : knownPledges.values()) {
        if (pledge != null && movedSources.contains(pledge.assetShareId())) {
          keepZeroRow.add(pledge.assetShareId());
        }
      }
    }
    // ── 全部校验通过：提交（只改受影响行；可写表上不会失败）────────────────────────────────
    for (Move move : moves) {
      AssetShare current = shares.get(move.source());
      if (current == null) {
        // 规划覆盖层保证不会发生；真发生 = 表被并发改过 ⇒ 响亮失败，不静默跳过。
        throw new IllegalStateException("AssetShareBook 提交时源份额消失（表被并发修改？）: " + move.source());
      }
      long left = current.quantity() - move.quantity();
      if (left == 0L && !keepZeroRow.contains(move.source())) {
        shares.remove(move.source()); // 整条转移且无任何质押引用：旧 id 不再存在
      } else {
        shares.put(
            move.source(),
            new AssetShare(
                current.id(),
                current.industry(),
                current.asset(),
                current.owner(),
                current.operator(),
                left,
                current.kind()));
      }
    }
    shares.putAll(pending);
    return List.copyOf(created);
  }

  /**
   * ★★ <b>跨产业重建</b>（D-023 资产随迁的“跨 hex”一路）：把源份额的 quantity 拆到<b>另一个产业模板</b>下的同 {@link AssetKind}
   * 新份额（owner/operator/kind 可改）。与 {@link #apply} 同一原子承诺：全量校验后才写。
   *
   * <p>★ <b>与 {@link #apply} 的守恒口径差异</b>：{@code apply} 要求每个 {@code (industry, asset)} 一减一加归零； 跨 hex
   * 时源/目标产业 id 不同（产业 id 带格键），这个分组守恒在语义上不成立。故本方法要求 <b>逐 {@link AssetKind} 的 Σquantity
   * 守恒</b>，并要求目标产业模板能承载该 asset （{@code capacityPerUnit} 含它）——绝不把 LAND 变 SHIP、也不在不能承载的产业下凭空造份额。
   *
   * @param source 源份额 id；不得为 null
   * @param quantity 移动数量；必须 {@code > 0}
   * @param toIndustry 目标产业模板（必须能承载源份额的 asset）；不得为 null
   * @param toOwner 新所有权主体；不得为 null
   * @param toOperator 新经营主体；不得为 null
   * @param kind 新权利性质；不得为 null
   */
  public record RebuildMove(
      AssetShareId source,
      long quantity,
      IndustryId toIndustry,
      ActorRef toOwner,
      ActorRef toOperator,
      AssetShare.RightKind kind) {

    public RebuildMove {
      Objects.requireNonNull(source, "AssetShareBook.RebuildMove.source 不得为 null");
      if (quantity <= 0L) {
        throw new IllegalArgumentException(
            "AssetShareBook.RebuildMove.quantity 必须 > 0: " + quantity);
      }
      Objects.requireNonNull(toIndustry, "AssetShareBook.RebuildMove.toIndustry 不得为 null");
      Objects.requireNonNull(toOwner, "AssetShareBook.RebuildMove.toOwner 不得为 null");
      Objects.requireNonNull(toOperator, "AssetShareBook.RebuildMove.toOperator 不得为 null");
      Objects.requireNonNull(kind, "AssetShareBook.RebuildMove.kind 不得为 null");
    }
  }

  /**
   * ★★ <b>跨产业重建的批写口</b>（D-023 跨 hex 资产随迁）：语义、原子性与“零行保留”口径同 {@link #apply}；
   * 唯一的差别是允许把源份额重建到<b>另一个产业模板</b>下（逐 {@link AssetKind} 守恒，目标模板必须能承载该 asset）。
   */
  public static List<AssetShareId> rebuild(
      Map<AssetShareId, AssetShare> shares,
      Map<IndustryId, Industry> industries,
      Map<PledgeId, Pledge> pledges,
      List<RebuildMove> moves) {
    Objects.requireNonNull(shares, "AssetShareBook.rebuild 的 shares 不得为 null");
    Objects.requireNonNull(moves, "AssetShareBook.rebuild 的 moves 不得为 null");
    if (moves.isEmpty()) {
      return List.of();
    }
    Map<IndustryId, Industry> knownIndustries = industries == null ? Map.of() : industries;
    Map<PledgeId, Pledge> knownPledges = pledges == null ? Map.of() : pledges;
    // ── 校验/规划覆盖层（与 apply 同款：全过之前不碰调用方的表）──────────────────────────────
    Map<AssetShareId, Long> remaining = new LinkedHashMap<>();
    Map<AssetShareId, AssetShare> pending = new LinkedHashMap<>();
    List<AssetShareId> created = new ArrayList<>(moves.size());
    Map<AssetKind, Long> deltasByAssetKind = new LinkedHashMap<>();
    for (RebuildMove move : moves) {
      Objects.requireNonNull(move, "AssetShareBook.rebuild 的 moves 不得含 null");
      AssetShare source = shares.get(move.source());
      if (source == null) {
        throw new IllegalArgumentException(
            "AssetShareBook.rebuild 的源份额不存在（拒绝半笔操作）: " + move.source());
      }
      if (!move.source().equals(source.id())) {
        throw new IllegalArgumentException(
            "AssetShareBook 的键必须与 AssetShare.id 一致：键=" + move.source() + "，行内 id=" + source.id());
      }
      if (!knownIndustries.isEmpty() && !knownIndustries.containsKey(source.industry())) {
        throw new IllegalArgumentException(
            "AssetShareBook.rebuild 的源份额产业不存在: source="
                + move.source()
                + " industry="
                + source.industry());
      }
      Industry targetIndustry = knownIndustries.get(move.toIndustry());
      if (targetIndustry == null) {
        throw new IllegalArgumentException(
            "AssetShareBook.rebuild 的目标产业不存在（拒绝凭空造产业）: " + move.toIndustry());
      }
      if (!targetIndustry.capacityPerUnit().containsKey(source.asset())) {
        throw new IllegalArgumentException(
            "AssetShareBook.rebuild 的目标产业无法承载该资产: industry="
                + move.toIndustry()
                + " asset="
                + source.asset()
                + "（capacityPerUnit="
                + targetIndustry.capacityPerUnit().keySet()
                + "）");
      }
      long available = remaining.getOrDefault(move.source(), source.quantity());
      if (move.quantity() > available) {
        throw new IllegalArgumentException(
            "AssetShareBook.rebuild 的移动数量超过源份额（含本批已移走的部分；拒绝半笔操作）: source="
                + move.source()
                + " have="
                + available
                + " need="
                + move.quantity());
      }
      remaining.put(move.source(), available - move.quantity());
      // ★ id 的唯一拼写点 = AssetShare.idOf；序号由 nextShareId 确定性给（同输入同 id，不解析旧档 opaque id）。
      AssetShareId newId =
          nextShareId(
              shares,
              pending,
              move.toIndustry(),
              source.asset(),
              move.toOwner(),
              move.toOperator(),
              move.kind());
      pending.put(
          newId,
          new AssetShare(
              newId,
              move.toIndustry(),
              source.asset(),
              move.toOwner(),
              move.toOperator(),
              move.quantity(),
              move.kind()));
      created.add(newId);
      // 守恒：同一个 AssetKind 上一减一加；跨产业 id 变了，但“多少件 TOOL/CATTLE”不因换产业变化。
      deltasByAssetKind.merge(source.asset(), -move.quantity(), Math::addExact);
      deltasByAssetKind.merge(source.asset(), move.quantity(), Math::addExact);
    }
    for (Map.Entry<AssetKind, Long> entry : deltasByAssetKind.entrySet()) {
      if (entry.getValue() != 0L) {
        throw new IllegalArgumentException(
            "AssetShareBook.rebuild 的 Σ(asset) quantity 不守恒: "
                + entry.getKey()
                + " delta="
                + entry.getValue());
      }
    }
    requirePledgeBounds(shares, remaining, knownPledges);
    // E5b：被本批移空的源份额仍被任何质押（含 RELEASED/EXECUTED）指名 ⇒ 保留 quantity=0 行（同 apply）。
    Set<AssetShareId> keepZeroRow = new LinkedHashSet<>();
    if (!knownPledges.isEmpty()) {
      Set<AssetShareId> movedSources = new LinkedHashSet<>();
      for (RebuildMove move : moves) {
        movedSources.add(move.source());
      }
      for (Pledge pledge : knownPledges.values()) {
        if (pledge != null && movedSources.contains(pledge.assetShareId())) {
          keepZeroRow.add(pledge.assetShareId());
        }
      }
    }
    // ── 全部校验通过：提交（只改受影响行；可写表上不会失败）────────────────────────────────
    for (RebuildMove move : moves) {
      AssetShare current = shares.get(move.source());
      if (current == null) {
        throw new IllegalStateException(
            "AssetShareBook.rebuild 提交时源份额消失（表被并发修改？）: " + move.source());
      }
      long left = current.quantity() - move.quantity();
      if (left == 0L && !keepZeroRow.contains(move.source())) {
        shares.remove(move.source()); // 整条转移且无任何质押引用：旧 id 不再存在
      } else {
        shares.put(
            move.source(),
            new AssetShare(
                current.id(),
                current.industry(),
                current.asset(),
                current.owner(),
                current.operator(),
                left,
                current.kind()));
      }
    }
    shares.putAll(pending);
    return List.copyOf(created);
  }

  /** {@code (industry, asset)} 的守恒分组键（record 相等 ⇒ 不靠分隔符拼串，不会因 id 含分隔符而误合并）。 */
  private record IndustryAsset(IndustryId industry, AssetKind asset) {}

  /**
   * ACTIVE 质押上界守卫：Σ活跃质押 ≤ 操作后份额 quantity；ACTIVE 质押指名的份额必须仍在结果状态里（数量 0 = 已移走 ⇒ 视为不存在，fail-closed）。
   *
   * <p>★ {@code knownPledges} 为空 = 对侧尚未提供 ⇒ 整体 no-op（与 {@code EconomyData} 的分段口径一致）。
   */
  private static void requirePledgeBounds(
      Map<AssetShareId, AssetShare> shares,
      Map<AssetShareId, Long> remaining,
      Map<PledgeId, Pledge> knownPledges) {
    if (knownPledges.isEmpty()) {
      return;
    }
    Map<AssetShareId, Long> active = new LinkedHashMap<>();
    for (Pledge pledge : knownPledges.values()) {
      if (pledge == null || pledge.status() != Pledge.Status.ACTIVE) {
        continue;
      }
      active.merge(pledge.assetShareId(), pledge.quantity(), Math::addExact);
    }
    for (Map.Entry<AssetShareId, Long> entry : active.entrySet()) {
      AssetShare original = shares.get(entry.getKey());
      if (original == null) {
        throw new IllegalArgumentException(
            "AssetShareBook 的 ACTIVE 质押指名的份额不存在（先释放/执行质押再转移）: " + entry.getKey());
      }
      Long movedRemaining = remaining.get(entry.getKey());
      long finalQuantity = movedRemaining == null ? original.quantity() : movedRemaining;
      if (finalQuantity <= 0L) {
        throw new IllegalArgumentException(
            "AssetShareBook 的 ACTIVE 质押指名的份额被移走（先释放/执行质押再转移）: " + entry.getKey());
      }
      if (entry.getValue() > finalQuantity) {
        throw new IllegalArgumentException(
            "AssetShareBook 的 Σ活跃质押必须 ≤ 份额 quantity：份额="
                + entry.getKey()
                + " 质押合计="
                + entry.getValue()
                + "，操作后数量="
                + finalQuantity);
      }
    }
  }

  /** 新份额 id：从 0 起找第一个既不在同表、也不在本批已生成集合里的空闲序号（确定性；不解析旧档 opaque id）。 */
  private static AssetShareId nextShareId(
      Map<AssetShareId, AssetShare> shares,
      Map<AssetShareId, AssetShare> pending,
      IndustryId industry,
      AssetKind asset,
      ActorRef owner,
      ActorRef operator,
      AssetShare.RightKind kind) {
    for (long sequence = 0L; sequence >= 0L; sequence++) {
      AssetShareId candidate = AssetShare.idOf(industry, asset, owner, operator, kind, sequence);
      if (!shares.containsKey(candidate) && !pending.containsKey(candidate)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException(
        "AssetShareBook 无法分配空闲 sequence（fail-closed；table="
            + shares.size()
            + "，本批新建="
            + pending.size()
            + "）");
  }
}
