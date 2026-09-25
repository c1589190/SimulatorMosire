package io.mosire.simos.ledger.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.actor.ActorKind;
import io.mosire.simos.economy.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AccountId;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.ledger.LedgerData;
import io.mosire.simos.ledger.LedgerSnapshot;
import io.mosire.simos.ledger.model.Account;
import io.mosire.simos.ledger.model.Claim;
import io.mosire.simos.ledger.model.ClaimKind;
import io.mosire.simos.ledger.model.EconomyMeta;
import io.mosire.simos.ledger.model.Transfer;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 铁律 5 的机械化落地（照 {@code SocialRoundTripTest}）：反射枚举 {@link LedgerData} 的 record 组件，逐组件造 差异，三条断言 ——
 * 新增状态组件若忘了进变更集，本测试自动红。
 */
class LedgerRoundTripTest {

  private static final AccountId A1 = new AccountId("acc-1");
  private static final ClaimId C1 = new ClaimId("cl-1");
  private static final TransferId T1 = new TransferId("tr-1");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final ActorRef LOT = new ActorRef(ActorKind.PEOPLE_LOT, "lot-1");
  private static final ActorRef GOV = new ActorRef(ActorKind.GOVERNMENT, "gov-1");

  /** ★ 唯一的豁免集合：v1 的 LedgerData 没有"不进变更集"的组件 ⇒ 必须是空集，且被单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  @Test
  void everyLedgerDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : LedgerData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      LedgerData base = LedgerData.empty();
      LedgerData target = mutate(base, name);
      LedgerChangeSet cs = LedgerChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 变更集整个为空）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(LedgerChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("LedgerData 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  @Test
  void changeSetHasExactlyFourComponents() {
    assertThat(LedgerChangeSet.class.getRecordComponents()).hasSize(4);
    assertThat(componentNames(LedgerChangeSet.class))
        .as("变更集的每个组件都必须在 LedgerData 里有同名的 record 组件")
        .isSubsetOf(componentNames(LedgerData.class));
    assertThat(componentNames(LedgerData.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(LedgerChangeSet.class));
  }

  @Test
  void snapshotNamespaceIsLedger() {
    LedgerSnapshot snapshot =
        new LedgerSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(5),
            LedgerData.empty());
    assertThat(snapshot.namespace()).isEqualTo("ledger");
  }

  private static LedgerData mutate(LedgerData base, String name) {
    return switch (name) {
      case "economyMeta" -> base.withEconomyMeta(Optional.of(meta()));
      case "accounts" -> base.withAccounts(Map.of(A1, account()));
      case "claims" -> base.withClaims(Map.of(C1, claim()));
      case "transfers" -> base.withTransfers(Map.of(T1, transfer()));
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(LedgerChangeSet cs, String name) {
    return switch (name) {
      case "economyMeta" -> cs.economyMeta().changed();
      case "accounts" -> cs.accounts().changed();
      case "claims" -> cs.claims().changed();
      case "transfers" -> cs.transfers().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  private static EconomyMeta meta() {
    return new EconomyMeta("m1", 0L, OptionalLong.empty(), "v1", Optional.empty());
  }

  private static Account account() {
    return new Account(A1, LOT, Map.of(GRAIN, 100L), Map.of(GRAIN, 10L), 50L, 5L);
  }

  private static Claim claim() {
    return new Claim(
        C1, ClaimKind.LOAN, LOT, GOV, Optional.of(GRAIN), 100L, 30L, OptionalLong.empty());
  }

  private static Transfer transfer() {
    return new Transfer(T1, 1L, LOT, GOV, Map.of(GRAIN, 5L), 10L, Optional.of(C1));
  }
}
