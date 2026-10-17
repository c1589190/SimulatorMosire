package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★★ <b>角色产品分配的纯函数（规则解释唯一拼写点）</b>（D-032 D2 §6.3/§8.3）。
 *
 * <p>它只做算术：不读状态、不写账户、不读市场价、不 import economy 实现。调用方（economy）负责把净池、实际认可 劳动与权益权重作为不可变事实传入，并按返回结果落账。
 *
 * <p>★ 两层分配：
 *
 * <ol>
 *   <li><b>角色池</b>：每个 {@link RolePayment.ProductShare} 角色按 {@code sharePerMille} 从每个商品净池切出 {@code
 *       floor(net × share ÷ 1000)}；{@link RolePayment.MoneyWage} 角色的角色池恒为 0；
 *   <li><b>角色内</b>：按 {@link IntraRoleSplitRule} 取权重，用整数最大余数法拆给参与户；余数降序、 {@code household.value()}
 *       升序补足。
 * </ol>
 *
 * <p>★ 两类角色池余数（份额余数 + 整数除法余数）按 {@link RoleShareRemainder} 显式处理： {@code TO_LAST_ROLE} 全部并入最后一个
 * {@code PRODUCT_SHARE} 角色；{@code UNALLOCATED} 全部进入 {@link
 * RoleDistributionResult#unallocated()}。零权重角色池同样进入 unallocated。逐商品守恒由 {@link
 * RoleDistributionResult} 构造器复核。
 */
public final class RoleDistribution {

  private static final long PER_MILLE = 1_000L;

  private RoleDistribution() {}

  /**
   * 执行分配。
   *
   * @param request 不可变输入；不得为 null
   * @return 逐参与户份额 + 具名未分配 + 逐商品池总量
   * @throws ProductionContractFault 未知角色、重复参与者、份额配置非法等契约错误
   */
  public static RoleDistributionResult allocate(RoleDistributionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("RoleDistribution.allocate 的 request 不得为 null");
    }
    IndustryDefinitionView definition = request.definition();

    Map<ProductionRoleId, ProductionRoleDefinition> rolesById = new LinkedHashMap<>();
    long shareTotal = 0L;
    ProductionRoleDefinition lastProductShareRole = null;
    for (ProductionRoleDefinition role : definition.roles()) {
      rolesById.put(role.id(), role);
      shareTotal = Math.addExact(shareTotal, role.sharePerMille());
      if (role.payment() instanceof RolePayment.ProductShare) {
        lastProductShareRole = role;
      }
      if (role.payment() instanceof RolePayment.MoneyWage && role.sharePerMille() != 0L) {
        throw fault(
            "role-distribution-money-wage-share",
            "MONEY_WAGE 角色的 sharePerMille 必须为 0: " + role.id().value());
      }
      if (role.payment() instanceof RolePayment.ProductShare && role.sharePerMille() <= 0L) {
        throw fault(
            "role-distribution-product-share-zero",
            "PRODUCT_SHARE 角色的 sharePerMille 必须 > 0: " + role.id().value());
      }
    }
    if (shareTotal > PER_MILLE) {
      throw fault("role-distribution-share-total", "角色 sharePerMille 合计不得 > 1000: " + shareTotal);
    }

    Map<ProductionRoleId, List<RoleDistributionParticipant>> participantsByRole =
        new LinkedHashMap<>();
    Set<ProductionParticipantKey> seenParticipants = new LinkedHashSet<>();
    for (RoleDistributionParticipant participant : request.participants()) {
      ProductionRoleDefinition role = rolesById.get(participant.role());
      if (role == null) {
        throw fault("role-distribution-unknown-role", "参与者引用未知角色: " + participant.role().value());
      }
      ProductionParticipantKey key = participant.participantKey();
      if (!seenParticipants.add(key)) {
        throw fault("role-distribution-duplicate-participant", "参与者重复: " + key);
      }
      if (role.intraRoleSplit() != IntraRoleSplitRule.CONFIGURED_WEIGHTS
          && participant.configuredWeight() != 0L) {
        throw fault(
            "role-distribution-configured-weight-invalid",
            "非 CONFIGURED_WEIGHTS 角色不得给 configuredWeight: "
                + role.id().value()
                + "/"
                + participant.household().value());
      }
      participantsByRole
          .computeIfAbsent(participant.role(), ignored -> new ArrayList<>())
          .add(participant);
    }

    validateWeightRoles(request.recognizedLaborMilli(), rolesById, "recognizedLaborMilli");
    validateWeightRoles(request.rightsWeights(), rolesById, "rightsWeights");

    Map<ProductionParticipantKey, Map<CommodityId, Long>> shares = new LinkedHashMap<>();
    Map<CommodityId, Long> unallocated = new LinkedHashMap<>();
    Map<CommodityId, Long> poolTotal = new LinkedHashMap<>();

    for (Map.Entry<CommodityId, Long> poolEntry : request.distributablePools().entrySet()) {
      CommodityId commodity = poolEntry.getKey();
      long pool = poolEntry.getValue();
      poolTotal.put(commodity, pool);

      Map<ProductionRoleId, Long> rolePools = new LinkedHashMap<>();
      long rolePoolSum = 0L;
      for (ProductionRoleDefinition role : definition.roles()) {
        long rolePool =
            role.payment() instanceof RolePayment.ProductShare
                ? multiplyDivide(pool, role.sharePerMille(), PER_MILLE)
                : 0L;
        rolePools.put(role.id(), rolePool);
        rolePoolSum = Math.addExact(rolePoolSum, rolePool);
      }
      long remainder = Math.subtractExact(pool, rolePoolSum);
      if (remainder > 0L) {
        if (definition.roleShareRemainderRule() == RoleShareRemainder.TO_LAST_ROLE
            && lastProductShareRole != null) {
          rolePools.merge(lastProductShareRole.id(), remainder, Math::addExact);
        } else {
          unallocated.merge(commodity, remainder, Math::addExact);
        }
      }

      for (ProductionRoleDefinition role : definition.roles()) {
        long rolePool = rolePools.getOrDefault(role.id(), 0L);
        if (rolePool <= 0L) {
          continue;
        }
        List<RoleDistributionParticipant> roleParticipants =
            participantsByRole.getOrDefault(role.id(), List.of());
        if (roleParticipants.isEmpty()) {
          unallocated.merge(commodity, rolePool, Math::addExact);
          continue;
        }
        List<WeightedParticipant> weighted = weightsFor(role, roleParticipants, request);
        long weightSum = 0L;
        for (WeightedParticipant item : weighted) {
          weightSum = Math.addExact(weightSum, item.weight());
        }
        if (weightSum == 0L) {
          unallocated.merge(commodity, rolePool, Math::addExact);
          continue;
        }
        long[] allocations = new long[weighted.size()];
        long[] remainders = new long[weighted.size()];
        long allocatedSum = 0L;
        for (int i = 0; i < weighted.size(); i++) {
          WeightedParticipant item = weighted.get(i);
          allocations[i] = multiplyDivide(rolePool, item.weight(), weightSum);
          remainders[i] = multiplyRemainder(rolePool, item.weight(), weightSum);
          allocatedSum = Math.addExact(allocatedSum, allocations[i]);
        }
        long leftovers = Math.subtractExact(rolePool, allocatedSum);
        if (leftovers > weighted.size()) {
          throw fault(
              "role-distribution-integer-remainder",
              "角色内整数余数超过参与人数（不可能的最大余数法状态）: "
                  + role.id().value()
                  + " leftovers="
                  + leftovers
                  + " participants="
                  + weighted.size());
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weighted.size(); i++) {
          order.add(i);
        }
        order.sort(
            Comparator.comparingLong((Integer i) -> remainders[i])
                .reversed()
                .thenComparing(i -> weighted.get(i).participant().household().value()));
        for (int i = 0; i < leftovers; i++) {
          allocations[order.get(i)] = Math.addExact(allocations[order.get(i)], 1L);
        }
        for (int i = 0; i < weighted.size(); i++) {
          long amount = allocations[i];
          if (amount <= 0L) {
            continue;
          }
          shares
              .computeIfAbsent(
                  weighted.get(i).participant().participantKey(), ignored -> new LinkedHashMap<>())
              .put(commodity, amount);
        }
      }
    }

    return new RoleDistributionResult(shares, unallocated, poolTotal);
  }

  private static void validateWeightRoles(
      Map<ProductionParticipantKey, Long> weights,
      Map<ProductionRoleId, ProductionRoleDefinition> rolesById,
      String field) {
    for (ProductionParticipantKey key : weights.keySet()) {
      if (!rolesById.containsKey(key.role())) {
        throw fault("role-distribution-unknown-role", field + " 引用未知角色: " + key.role().value());
      }
    }
  }

  private static List<WeightedParticipant> weightsFor(
      ProductionRoleDefinition role,
      List<RoleDistributionParticipant> participants,
      RoleDistributionRequest request) {
    List<WeightedParticipant> weighted = new ArrayList<>();
    for (RoleDistributionParticipant participant : participants) {
      ProductionParticipantKey key = participant.participantKey();
      long weight =
          switch (role.intraRoleSplit()) {
            case BY_RECOGNIZED_LABOR -> request.recognizedLaborMilli().getOrDefault(key, 0L);
            case BY_RIGHTS -> request.rightsWeights().getOrDefault(key, 0L);
            case EQUAL -> 1L;
            case CONFIGURED_WEIGHTS -> participant.configuredWeight();
          };
      if (weight < 0L) {
        throw fault("role-distribution-negative-weight", "角色内权重不得为负: " + key + " = " + weight);
      }
      weighted.add(new WeightedParticipant(participant, weight));
    }
    return weighted;
  }

  /** {@code floor(value × numerator ÷ denominator)}，乘法用 BigInteger 避免 long 溢出。 */
  private static long multiplyDivide(long value, long numerator, long denominator) {
    if (value == 0L || numerator == 0L) {
      return 0L;
    }
    BigInteger product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator));
    return product.divide(BigInteger.valueOf(denominator)).longValueExact();
  }

  private static long multiplyRemainder(long value, long numerator, long denominator) {
    if (value == 0L || numerator == 0L) {
      return 0L;
    }
    BigInteger product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator));
    return product.remainder(BigInteger.valueOf(denominator)).longValueExact();
  }

  private static ProductionContractFault fault(String reason, String detail) {
    return new ProductionContractFault(reason, detail);
  }

  private record WeightedParticipant(RoleDistributionParticipant participant, long weight) {}
}
