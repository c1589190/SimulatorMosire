package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★★ <b>D-032 RC4-D：合户时债务合同的双向重键与自债净额</b>。
 *
 * <p>父序列锚点：{@code docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md}
 * §6.2 的同 key 合户； 本类只承担其中"合同端点 source → survivor"的一段，不搬货币/库存/人口，也不改债务公式。
 *
 * <p>口径：{@code debtor/creditor} 两端都映射 source → survivor；映射后两端相等 ⇒ 合同本金归零并删除（自债无经济意义）， 其余合同按 {@link
 * DebtContractId#idOf} 重建稳定 id，{@code principal/openedDay/lastInterestDay/dueCycle/status/source}
 * 原值保留。若新 id 与任何最终保留合同冲突 ⇒ 首版 fail-closed，不静默合并 openedDay/status/terms。
 */
public final class HouseholdDebtMergeBook {

  private static final org.slf4j.Logger LOG = EconomyLog.debt();

  private HouseholdDebtMergeBook() {}

  /** 一条被自债净额删除的合同读数（只服务日志/台账；不落持久状态）。 */
  public record SelfNettedContract(
      DebtContractId originalId, HouseholdId debtor, HouseholdId creditor, long principal) {

    public SelfNettedContract {
      Objects.requireNonNull(originalId, "SelfNettedContract.originalId");
      Objects.requireNonNull(debtor, "SelfNettedContract.debtor");
      Objects.requireNonNull(creditor, "SelfNettedContract.creditor");
      if (principal < 0L) {
        throw new IllegalArgumentException("SelfNettedContract.principal 不得为负: " + principal);
      }
    }
  }

  /** 一次纯计算的债务重键结果；{@link #contracts()} 是新的稳定序表快照。 */
  public record Rework(
      Map<DebtContractId, DebtContract> contracts,
      int rekeyedContracts,
      int selfNettedContracts,
      long selfNettedPrincipal,
      List<SelfNettedContract> selfNets) {

    public Rework {
      Objects.requireNonNull(contracts, "Rework.contracts");
      if (rekeyedContracts < 0 || selfNettedContracts < 0 || selfNettedPrincipal < 0L) {
        throw new IllegalArgumentException("Rework 的计数/本金不得为负");
      }
      LinkedHashMap<DebtContractId, DebtContract> copy = new LinkedHashMap<>();
      for (Map.Entry<DebtContractId, DebtContract> entry : contracts.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("Rework.contracts 不得含 null 键/值");
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      contracts = Collections.unmodifiableMap(copy);
      selfNets = List.copyOf(selfNets);
    }

    public boolean changed() {
      return rekeyedContracts > 0 || selfNettedContracts > 0;
    }
  }

  /** ★ 只计算、不写传入表：供 RC4-D 在任何 work copy 写入前做碰撞预检。输出按原合同 id 稳定序。 */
  public static Rework plan(
      Map<DebtContractId, DebtContract> contracts, HouseholdId source, HouseholdId survivor) {
    Objects.requireNonNull(contracts, "HouseholdDebtMergeBook.plan contracts");
    Objects.requireNonNull(source, "HouseholdDebtMergeBook.plan source");
    Objects.requireNonNull(survivor, "HouseholdDebtMergeBook.plan survivor");
    if (source.equals(survivor)) {
      throw new IllegalArgumentException(
          "household-merge-debt-same-household: source == survivor: " + source.value());
    }

    List<DebtContract> ordered = new ArrayList<>(contracts.values());
    ordered.sort(Comparator.comparing(contract -> contract.id().value()));

    LinkedHashMap<DebtContractId, DebtContract> result = new LinkedHashMap<>();
    LinkedHashMap<DebtContractId, DebtContractId> finalIdOwner = new LinkedHashMap<>();
    List<SelfNettedContract> selfNets = new ArrayList<>();
    int rekeyed = 0;
    long selfNettedPrincipal = 0L;

    for (DebtContract contract : ordered) {
      boolean touchesSource =
          contract.debtor().equals(source) || contract.creditor().equals(source);
      DebtContractId finalId;
      DebtContract finalContract;
      if (!touchesSource) {
        finalId = contract.id();
        finalContract = contract;
      } else {
        HouseholdId debtor = contract.debtor().equals(source) ? survivor : contract.debtor();
        HouseholdId creditor = contract.creditor().equals(source) ? survivor : contract.creditor();
        if (debtor.equals(creditor)) {
          selfNets.add(
              new SelfNettedContract(
                  contract.id(), contract.debtor(), contract.creditor(), contract.principal()));
          selfNettedPrincipal = Math.addExact(selfNettedPrincipal, contract.principal());
          continue;
        }
        finalId = DebtContractId.idOf(debtor, creditor, contract.unit(), contract.terms());
        finalContract =
            new DebtContract(
                finalId,
                debtor,
                creditor,
                contract.unit(),
                contract.terms(),
                contract.source(),
                contract.principal(),
                contract.openedDay(),
                contract.lastInterestDay(),
                contract.dueCycle(),
                contract.status());
        rekeyed++;
      }

      DebtContractId priorOwner = finalIdOwner.putIfAbsent(finalId, contract.id());
      if (priorOwner != null) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "HOUSEHOLD_MERGE_DEBT_ID_COLLISION",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "source",
                    source.value(),
                    "survivor",
                    survivor.value(),
                    "newId",
                    finalId.value(),
                    "firstContract",
                    priorOwner.value(),
                    "secondContract",
                    contract.id().value()));
        throw new IllegalStateException(
            "household-merge-debt-id-collision: newId="
                + finalId.value()
                + " firstContract="
                + priorOwner.value()
                + " secondContract="
                + contract.id().value());
      }
      result.put(finalId, finalContract);
    }

    return new Rework(result, rekeyed, selfNets.size(), selfNettedPrincipal, selfNets);
  }

  /**
   * ★ 在工作表上执行一次债务重键；先 {@link #plan} 全量校验，成功后才改 {@code contracts}。
   *
   * @param contracts 债务工作表；会就地替换
   * @param day 世界日（只进日志/审计）
   * @param ledger 当天瞬态审计累加器；可为 null
   */
  public static Rework apply(
      Map<DebtContractId, DebtContract> contracts,
      HouseholdId source,
      HouseholdId survivor,
      long day,
      ProductionLedger.Accumulator ledger) {
    Objects.requireNonNull(contracts, "HouseholdDebtMergeBook.apply contracts");
    if (day < 0L) {
      throw new IllegalArgumentException("HouseholdDebtMergeBook.apply 的 day 不得为负");
    }
    Rework rework = plan(contracts, source, survivor);
    if (!rework.changed()) {
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "HOUSEHOLD_MERGE_DEBT_NOOP",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "source",
                    source.value(),
                    "survivor",
                    survivor.value()));
      }
      return rework;
    }

    contracts.clear();
    contracts.putAll(rework.contracts());
    for (SelfNettedContract selfNet : rework.selfNets()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "HOUSEHOLD_MERGE_DEBT_SELF_NETTED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "source",
                  source.value(),
                  "survivor",
                  survivor.value(),
                  "contract",
                  selfNet.originalId().value(),
                  "debtorBefore",
                  selfNet.debtor().value(),
                  "creditorBefore",
                  selfNet.creditor().value(),
                  "principal",
                  selfNet.principal(),
                  "reason",
                  "debtor-equals-creditor-after-household-merge"));
      if (ledger != null) {
        ledger.addLiquidationAudit(
            new ProductionLedger.LiquidationAudit(
                day,
                "household-merge-netted-self-debt",
                Optional.of(survivor),
                Optional.of(selfNet.originalId()),
                Optional.empty(),
                Optional.empty(),
                selfNet.principal(),
                0L,
                selfNet.principal(),
                0L,
                "debtor-equals-creditor-after-household-merge",
                Map.of("selfNettedContracts", 1L)));
      }
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_MERGE_DEBT_REKEYED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "source",
                source.value(),
                "survivor",
                survivor.value(),
                "rekeyedContracts",
                rework.rekeyedContracts(),
                "selfNettedContracts",
                rework.selfNettedContracts(),
                "selfNettedPrincipal",
                rework.selfNettedPrincipal()));
    return rework;
  }
}
