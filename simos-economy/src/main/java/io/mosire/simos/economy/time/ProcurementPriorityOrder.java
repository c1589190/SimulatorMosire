package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * ★★ <b>P-T1d：政府采购优先级的"置顶推进"核心（唯一拼写点；买/卖两侧共用同一段算式）</b>。
 *
 * <p>2026-10-10 口岸设计书 §16.3 / §17；用户原话「如果政府要求管控市场，视为政府强制把自己的账户在市场交易里强制到最开始卖、最开始买，
 * 这时候前方要超越多少家户，政府就需要付多少额外行政劳动力」「可以改成政府要求管控后自己选择是否扩大官僚队伍，如果行政力见底了那就不允许继续超越了」。
 *
 * <pre>
 * 输入：一张挂单簿的槽位（列表序 = 该簿的服务序）、"这一槽属于哪一户"、两个写口、注入表、逐政府剩余池
 * 输出：每只槽位的**全序名次**（置顶后的位次）+ "是否真的越过 ≥1 户"；池子就地扣减
 * 规则：
 *   ① 政府的处理序 = 国库户 id 升序（谁在最前由 id 定，不由遍历序定）
 *   ② 同政府的槽位按服务序处理，且后续单不许越过前面那张单的落点（⇒ 同政府多单保持 canonical 序）
 *   ③ 从"本政府已放置块之后"起逐只向前越过；**每越过一户（distinct 家户）扣一份行政力**
 *   ④ 池 = 0 ⇒ 就地停住（fail-closed：不超越、不欠账）；该槽停在哪里，名次就是哪里
 * </pre>
 *
 * <p>★★ <b>本类只写"顺序"</b>：它不碰订单、不碰价格、不碰任何量算式 —— 成交价/量规则由撮合侧原样执行，只是按这里给的顺序走。
 *
 * <p>★★ <b>确定性（I7）</b>：簿序 = 调用方给的列表序（内容的纯函数）；政府序 = id 升序；付账对象 = 从近到远的 distinct 家户； 全程无随机数、无时钟、不依赖
 * {@code Set}/{@code Map} 的迭代序。
 */
final class ProcurementPriorityOrder {

  private static final Logger MARKET = EconomyLog.market();

  private ProcurementPriorityOrder() {}

  /**
   * 一张挂单簿的推进结果（只进轮级日志）。
   *
   * @param consumed 本次推进消耗的行政力份数（= 越过的 distinct 家户次数）
   * @param promoted 真的被向前推过（≥ 1 户）的槽位数
   * @param hardStops 因池子见底而就地停住的次数（"见底硬停"的判据读数）
   */
  record BookResult(long consumed, int promoted, int hardStops) {

    static final BookResult ZERO = new BookResult(0L, 0, 0);

    BookResult plus(BookResult other) {
      return new BookResult(
          Math.addExact(consumed, other.consumed),
          promoted + other.promoted,
          hardStops + other.hardStops);
    }
  }

  /**
   * ★★ <b>推进一张簿</b>（买/卖两侧唯一的算法实现）。
   *
   * @param bucket 该簿的槽位（列表序 = 服务序：买侧 = 插入序，卖侧 = 成本序）
   * @param householdOf 槽位 → 所属家户（{@code null} = 经营者主体没解析到家户 ⇒ 既不收费、也不参与置顶）
   * @param writeRank 写"置顶后的全序名次"（0 起；每只槽位恰好写一次）
   * @param writeOvertook 写"是否真的越过 ≥ 1 户"
   * @param input 本轮注入值（"谁要求管控"的判据 = {@link ProcurementPriorityInput#controls(HouseholdId)}）
   * @param poolLeft 逐政府剩余行政力池（<b>跨簿共享、就地扣减</b>：池子是"每 tick 一份编制劳动力"）
   * @param day 世界日（只进 DEBUG 日志）
   * @return 本簿的消耗/推进/硬停读数
   */
  static <S> BookResult advance(
      List<S> bucket,
      Function<S, HouseholdId> householdOf,
      BiConsumer<S, Long> writeRank,
      BiConsumer<S, Boolean> writeOvertook,
      ProcurementPriorityInput input,
      Map<HouseholdId, Long> poolLeft,
      long day) {
    List<S> working = new ArrayList<>(bucket);
    List<HouseholdId> governments = new ArrayList<>();
    for (S slot : working) {
      HouseholdId household = householdOf.apply(slot);
      if (household != null && input.controls(household) && !governments.contains(household)) {
        governments.add(household);
      }
    }
    if (governments.isEmpty()) {
      // 这张簿里没有要求管控的政府 ⇒ 名次 = 服务序（"按名次排序"因此逐值等于"按既有顺序"，I-C2）。
      for (int i = 0; i < working.size(); i++) {
        writeRank.accept(working.get(i), (long) i);
      }
      return BookResult.ZERO;
    }
    governments.sort(Comparator.comparing(HouseholdId::value));
    long consumed = 0L;
    int promoted = 0;
    int hardStops = 0;
    int base = 0; // 前面几张（其他政府）已放置块的合计长度
    for (HouseholdId government : governments) {
      int placed = 0;
      int floorIndex = 0; // 后续同政府单不得越过前面那张单的落点（⇒ 同政府多单保持 canonical 序）
      Set<HouseholdId> paidHouseholds = new LinkedHashSet<>(); // 本（簿 × 政府）已付过账的家户（一户只付一次）
      List<S> own = new ArrayList<>();
      for (S slot : working) {
        if (government.equals(householdOf.apply(slot))) {
          own.add(slot);
        }
      }
      for (S slot : own) {
        int position = indexOfIdentity(working, slot);
        int target = Math.max(base + placed, floorIndex);
        if (position <= target) {
          // 已经在目标位（政府块内部，或本来就在最前）—— 不动它，也不收费；
          //   ★ placed/floorIndex 仍要跟着走，否则后面同政府的单会与它前后颠倒（canonical 序）。
          placed = Math.max(placed, position - base + 1);
          floorIndex = Math.max(floorIndex, position + 1);
          continue;
        }
        int index = position - 1;
        boolean blocked = false;
        HouseholdId unpaid = null;
        while (index >= target) {
          HouseholdId other = householdOf.apply(working.get(index));
          if (other != null && !other.equals(government) && !paidHouseholds.contains(other)) {
            long left = poolLeft.getOrDefault(government, 0L);
            if (left <= 0L) {
              blocked = true; // ★ 见底硬停：付不起这一户 ⇒ 就地停住（fail-closed，绝不先超后欠）
              unpaid = other;
              break;
            }
            poolLeft.put(government, left - 1L);
            consumed++;
            paidHouseholds.add(other);
          }
          index--;
        }
        int landing = blocked ? index + 1 : target;
        if (landing < position) {
          working.remove(position);
          working.add(landing, slot);
          writeOvertook.accept(slot, true);
          promoted++;
        }
        if (blocked) {
          hardStops++;
          floorIndex = landing + 1; // 后面的同政府单停在这张单之后（canonical 序不许反转）
          if (MARKET.isDebugEnabled()) {
            // §一.9 DEBUG 写"为什么"：这一轮为什么不再超越（池子见底 = 付不起下一户）。
            EventLog.channel(MARKET)
                .debug(
                    LogEvent.of(
                        "MARKET_PROCUREMENT_PRIORITY_HARD_STOP",
                        EconomyLogSource.ECONOMY_MARKET,
                        "day",
                        day,
                        "government",
                        government.value(),
                        "blockingHousehold",
                        unpaid == null ? "-" : unpaid.value(),
                        "landingIndex",
                        landing,
                        "poolUnitsLeft",
                        poolLeft.getOrDefault(government, 0L),
                        "reason",
                        "administrative-pool-exhausted-no-overtake-without-payment"));
          }
        } else {
          placed++;
          floorIndex = landing + 1;
        }
      }
      base += placed;
    }
    // 全序名次 = 置顶后的位次（每只槽位恰好写一次；未动的槽位也写 —— 撮合按名次排序必须无缺口）。
    for (int i = 0; i < working.size(); i++) {
      writeRank.accept(working.get(i), (long) i);
    }
    return new BookResult(consumed, promoted, hardStops);
  }

  /** 身份定位（{@code indexOf} 用的是 {@code equals}；槽位是可变对象、不重写 equals ⇒ 这里显式按引用找，意图写死）。 */
  private static <S> int indexOfIdentity(List<S> list, S target) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i) == target) {
        return i;
      }
    }
    throw new IllegalStateException("置顶 pass 在挂单簿里找不到自己的槽位（簿被重排坏了）");
  }
}
