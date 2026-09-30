package io.mosire.simos.economy.pilot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶层边界的 assetState 值（千分定点）。
 *
 * <pre>
 *   LABORER  L=0                 U=leaseStartThreshold
 *   TENANT   L=leaseMaintenance  U=selfFarmThreshold
 *   MIDDLE   L=selfFarmMinimum   U=selfOperableCapacity
 *   LANDLORD L=landlordMinimum   U=softReference
 *   x_C = clamp((A_C - L_C) / max(1, U_C - L_C), 0, 1)
 * </pre>
 */
public record ClassBounds(Map<String, Bound> byClass) {

  public record Bound(long lowerMilli, long upperMilli) {
    public Bound {
      if (lowerMilli < 0L) {
        throw new IllegalArgumentException("lower bound must be >= 0: " + lowerMilli);
      }
      if (upperMilli < lowerMilli) {
        throw new IllegalArgumentException(
            "upper bound must be >= lower bound: " + upperMilli + " < " + lowerMilli);
      }
    }
  }

  public ClassBounds {
    LinkedHashMap<String, Bound> copy = new LinkedHashMap<>();
    if (byClass != null) {
      copy.putAll(byClass);
    }
    byClass = Collections.unmodifiableMap(copy);
  }

  public static ClassBounds tenancyDefaults() {
    LinkedHashMap<String, Bound> bounds = new LinkedHashMap<>();
    bounds.put(PilotModel.LABORER_ID, new Bound(0L, 250L));
    bounds.put(PilotModel.TENANT_ID, new Bound(100L, 900L));
    bounds.put(PilotModel.MIDDLE_PEASANT_ID, new Bound(100L, 1000L));
    bounds.put(PilotModel.LANDLORD_ID, new Bound(600L, 1500L));
    return new ClassBounds(bounds);
  }

  public Bound boundFor(String classPositionId) {
    Bound bound = byClass.get(classPositionId);
    if (bound == null) {
      throw new IllegalArgumentException("no class bound for: " + classPositionId);
    }
    return bound;
  }

  public long xMilli(String classPositionId, long aMilli) {
    Bound bound = boundFor(classPositionId);
    long span = Math.max(1L, bound.upperMilli() - bound.lowerMilli());
    long x = (aMilli - bound.lowerMilli()) * 1000L / span;
    return Math.max(0L, Math.min(1000L, x));
  }
}
