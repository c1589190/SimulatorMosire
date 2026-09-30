package io.mosire.simos.sd.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.unit.UnitId;
import java.util.Optional;

/**
 * 军队归属（阶段 12，2026-10-01 用户裁定 6/8）：把 {@code ArmyId} 与一个**单位根**（{@code UnitId}）关联起来， 并可选地记一个**认领的 GOV
 * 单位**（{@code masterGovUnitId}）。
 *
 * <p>★★ <b>Army 不再认识 Nation</b>：用户原话「Army 我从来没要求算战力，只是根据 GOV 的这个包对应语义，把 Nation 一起拆除去而已」。 国家（{@code
 * Nation}）仍是 sd 的政治/外交实体、{@code NationSummary} 的显示名来源之一；军队只认 GOV 编制，不把 {@code NationId} 硬映射成 GOV。
 *
 * <p>★ <b>编制本身仍在 unit</b>（铁律 3）：sd 只存**归属关系**，不拥有编制树；{@code rootUnit} 与 {@code masterGovUnitId}
 * 的存在性由命令期经 {@code state.module("unit")} 只读（spec §四）。{@code masterGovUnitId} 是 {@link Optional} 且
 * Optional 本身非 null；缺省 = 未认主子。
 *
 * <p>★ <b>旧档兼容</b>：旧 JSON 里的 {@code "nationId"} 键已被本类型移除；类型上的
 * {@code @JsonIgnoreProperties("nationId")} 只忽略这一个已退役键、**不关掉全局的 fail-on-unknown**，也不把 nationId 映射成
 * GOV。旧 4 参构造器 {@link #Army(ArmyId, NationId, UnitId, String)} 保留为源码兼容构造，传进来的 {@code nationId}
 * 只做非空校验、随即丢弃。
 *
 * <p>★ <b>不算战力</b>：本类型不持有任何力量/战斗力字段；{@code simos-army} 一期也只做视野派生（裁定 6）。
 *
 * @param id 军队 id（非 null）
 * @param masterGovUnitId 认领的 GOV 单位（未认领用 {@code Optional.empty()}；Optional 本身非 null）
 * @param rootUnit 军队根单位（非 null）
 * @param name 展示名（非空白）
 */
@JsonIgnoreProperties({"nationId"})
public record Army(ArmyId id, Optional<UnitId> masterGovUnitId, UnitId rootUnit, String name) {

  public Army {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (masterGovUnitId == null) {
      throw new IllegalArgumentException("masterGovUnitId 不得为 null（未认主子用 Optional.empty()）");
    }
    if (rootUnit == null) {
      throw new IllegalArgumentException("rootUnit 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
  }

  /**
   * <b>deprecated 源码兼容构造</b>：旧 4 参形态 {@code (id, nationId, rootUnit, name)} 仍可编译，但 {@code
   * masterGovUnitId = Optional.empty()}——**不把 nationId 映射成 GOV**（两者不是同一个概念）。
   *
   * <p>★ 旧调用点（夹具/测试）因此零改动；生产代码一律走 canonical 4 参（第二参为 {@code Optional<UnitId>}）。 {@code nationId}
   * 只为保留旧签名与旧错误文案存在，构造完成后不再被任何字段或方法引用。
   *
   * @deprecated 阶段 12 起 Army 去 {@code NationId}；新调用请传 {@code Optional<UnitId> masterGovUnitId}。
   */
  @Deprecated
  public Army(ArmyId id, NationId nationId, UnitId rootUnit, String name) {
    this(id, Optional.empty(), rootUnit, name);
    if (nationId == null) {
      throw new IllegalArgumentException("nationId 不得为 null");
    }
  }
}
