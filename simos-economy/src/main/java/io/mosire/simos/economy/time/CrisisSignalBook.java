package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.DebtStressBook.plan;
import static io.mosire.simos.economy.time.DebtStressBook.saturatedAdd;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.Context;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.DEBT_EXPLOSION_F_MULTIPLE;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.DebtExplosionPlan;
import static io.mosire.simos.economy.time.EconomyLiquidationSettlement.Plan;

import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** E5b 机械拆分 helper（由 EconomyLiquidationSettlement 抽取；行为不变）。 */
final class CrisisSignalBook {

  private CrisisSignalBook() {}

  /** 同 hex 同 kind 覆盖更新（键 = (hex, kind) 派生 id）；group 内按家户序聚合证据。 */
  static void writeCrisisSignals(Context context, Plan plan) {
    LinkedHashMap<HexCoord, List<DebtExplosionPlan>> explosionsByHex = new LinkedHashMap<>();
    for (DebtExplosionPlan explosion : plan.explosions()) {
      explosionsByHex.computeIfAbsent(explosion.hex(), ignored -> new ArrayList<>()).add(explosion);
    }
    if (explosionsByHex.isEmpty()) {
      return;
    }
    LinkedHashMap<CrisisSignalId, HexCrisisSignal> signals =
        context.session().sheet().crisisSignals();
    List<HexCoord> hexes = new ArrayList<>(explosionsByHex.keySet());
    hexes.sort(Comparator.naturalOrder());
    for (HexCoord hex : hexes) {
      List<DebtExplosionPlan> explosions = explosionsByHex.getOrDefault(hex, List.of());
      if (!explosions.isEmpty()) {
        HexCrisisSignal signal = debtExplosionSignal(context, hex, explosions);
        signals.put(signal.id(), signal);
      }
    }
  }

  static HexCrisisSignal debtExplosionSignal(
      Context context, HexCoord hex, List<DebtExplosionPlan> explosions) {
    List<DebtExplosionPlan> ordered = new ArrayList<>(explosions);
    ordered.sort(Comparator.comparing(explosion -> explosion.household().value()));
    LinkedHashMap<String, Long> evidence = new LinkedHashMap<>();
    List<HouseholdId> households = new ArrayList<>();
    Set<SocialClassId> classes = new LinkedHashSet<>();
    StringBuilder reason = new StringBuilder("debt-explosion");
    long principalTotal = 0L;
    long netDelta = 0L;
    long maxCycles = 0L;
    long minF = Long.MAX_VALUE;
    long maxMultiple = 0L;
    int severity = 1;
    int unknownF = 0;
    for (DebtExplosionPlan explosion : ordered) {
      households.add(explosion.household());
      HouseholdEconomy householdEconomy = context.householdEconomies().get(explosion.household());
      if (householdEconomy != null) {
        classes.add(householdEconomy.view().stratum());
      }
      principalTotal = saturatedAdd(principalTotal, explosion.principalTotalMilli());
      netDelta = saturatedAdd(netDelta, explosion.netPrincipalDeltaMilli());
      maxCycles = Math.max(maxCycles, explosion.stressCycles());
      maxMultiple = Math.max(maxMultiple, Math.max(0L, explosion.principalToFMultiple()));
      if (explosion.FMilli() < 0L) {
        unknownF++;
      } else {
        minF = Math.min(minF, explosion.FMilli());
      }
      severity = Math.max(severity, explosion.severity());
      reason.append(';').append(explosion.reason());
    }
    evidence.put("principalTotalMilli", principalTotal);
    evidence.put("netPrincipalDeltaMilli", netDelta);
    evidence.put("maxConsecutiveStressCycles", maxCycles);
    evidence.put("maxPrincipalToFMultiple", maxMultiple);
    evidence.put("FMilli", minF == Long.MAX_VALUE ? -1L : minF);
    evidence.put("FUnavailable", (long) unknownF);
    evidence.put("thresholdMultiple", DEBT_EXPLOSION_F_MULTIPLE);
    return new HexCrisisSignal(
        CrisisSignalId.idOf(hex, HexCrisisSignal.Kind.DEBT_EXPLOSION.name()),
        hex,
        HexCrisisSignal.Kind.DEBT_EXPLOSION,
        severity,
        context.day(),
        evidence,
        households,
        new ArrayList<>(classes),
        reason.toString());
  }
}
