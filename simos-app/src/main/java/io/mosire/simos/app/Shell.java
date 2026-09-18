package io.mosire.simos.app;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.spi.CancelRouteHandler;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.DisbandUnitHandler;
import io.mosire.simos.unit.spi.PlaceAtHandler;
import io.mosire.simos.unit.spi.PlanRouteHandler;
import io.mosire.simos.unit.spi.RenameUnitHandler;
import io.mosire.simos.unit.spi.ReparentUnitHandler;
import io.mosire.simos.unit.spi.SetStrengthHandler;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外壳：**唯一的装配点**（spec §3.2 的 1~2 步与 3~4 步中不依赖审批/MCP 的部分）。
 *
 * <p>★ **它是全仓唯一组装 CoreSimos 与领域模块的地方**：Core 的 main scope 看不见任何领域类型（ADR-1），三 codec / 八 handler / 一
 * participant 必须由组合根注入。GUI / MCP / 审批的装配归 T6/T7/T8，本任务**不接**——{@link #start}
 * 只走到"世界能提交命令、能重放、能推进"为止。
 *
 * <p>★ **本类不持有任何存储写路径**：{@code SqliteStore} / {@code Timeline.appendRevision} / {@code
 * CheckpointStore} 一个都不在 app 源码里（铁律 2 的结构化，R1 的扫描对象）。唯一的写入口是 {@link
 * CoreSimos#submit(io.mosire.simos.util.state.Command)}。
 */
public final class Shell implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Shell.class);

  private static final int HANDLER_COUNT = 8;

  private final ShellConfig config;
  private final CoreSimos coreSimos;

  /** 已注册模块 codec 的个数（map/social/unit）；由实际注册动作数出来，不是写死的常量。 */
  private final int registeredModuleCount;

  private Shell(ShellConfig config, CoreSimos coreSimos, int registeredModuleCount) {
    this.config = config;
    this.coreSimos = coreSimos;
    this.registeredModuleCount = registeredModuleCount;
  }

  /**
   * 起壳：建 CoreSimos 并按 spec §3.2 的顺序注册**三 codec + 八 handler + 一 participant**。
   *
   * <p>★ {@code MovementCost} 由 app 注入（M3 口径）：{@link TerrainMovementCost} 是当前唯一实现，取它的单例 {@link
   * TerrainMovementCost#INSTANCE}（构造器私有，不能 {@code new}——这是对派单文字 {@code new TerrainMovementCost()}
   * 的一处就地校正）。
   *
   * @param config 装配配置
   * @return 已装配、尚未封存的壳（封存发生在第一次 {@code submit}/{@code replay}）
   * @throws NullPointerException {@code config} 为 null
   */
  public static Shell start(ShellConfig config) {
    Objects.requireNonNull(config, "config");
    CoreSimos coreSimos =
        new CoreSimos(
            new CoreConfig(
                config.storeDir(), config.checkpointInterval(), SimosObjectMapper.create()));

    List<ModuleCodec> codecs = List.of(new MapCodec(), new SocialCodec(), new UnitCodec());
    for (ModuleCodec codec : codecs) {
      coreSimos.register(codec);
    }

    coreSimos.register(new RenameUnitHandler());
    coreSimos.register(new CreateUnitHandler());
    coreSimos.register(new ReparentUnitHandler());
    coreSimos.register(new SetStrengthHandler());
    coreSimos.register(new PlaceAtHandler());
    coreSimos.register(new PlanRouteHandler());
    coreSimos.register(new CancelRouteHandler());
    coreSimos.register(new DisbandUnitHandler());

    coreSimos.register(new UnitTimeParticipant(TerrainMovementCost.INSTANCE, config.mapId()));

    LOG.info(
        "Shell 装配完成: store={} checkpointInterval={} codec={} handler={} participant=1 mapId={}",
        config.storeDir(),
        config.checkpointInterval(),
        codecs.size(),
        HANDLER_COUNT,
        config.mapId());
    return new Shell(config, coreSimos, codecs.size());
  }

  /**
   * 底层门面（提交 / 重放 / 只读分支面）。★ 唯一写入口仍是 {@link CoreSimos#submit}。
   *
   * <p>★ **{@code EI_EXPOSE_REP} 是有意豁免（M5 T1 门禁实测）**：spec §3.2 要求组合根对外交出 {@code
   * CoreSimos}；它不是"内部表示"而是本壳的产物本身。SpotBugs 判它可变故报 {@code EI_EXPOSE_REP}，用
   * {@code @SuppressFBWarnings} 精确豁免在**这一个方法**上（注解依赖 provided、不进产物；与 AgentLibMosire/BrainMosire
   * 同法）。护栏不因此松：铁律 2 仍由"app 源码无 store/timeline 写面"的扫描 + 行为面守（R1，T8 落地）。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "spec §3.2 要求对外交出 CoreSimos 引用；唯一写入口仍是 CoreSimos.submit，豁免只作用于本方法")
  public CoreSimos coreSimos() {
    return coreSimos;
  }

  /** 生效配置（原样回显，不重算）。 */
  public ShellConfig config() {
    return config;
  }

  /** 已注册的模块 codec 个数（map/social/unit = 3）。{@code ShellMain} 用它打印装配实况。 */
  public int registeredModuleCount() {
    return registeredModuleCount;
  }

  /**
   * 关闭：T1 阶段只关 {@link CoreSimos}（它下面挂着唯一的一条 Sqlite 连接，幂等）。
   *
   * <p>★ spec §3.3 的完整次序是 GUI → MCP → 审批端点 → 审批通道 → CoreSimos；那些组件要到 T6/T7/T8 才存在，故**在此
   * 之前**先插它们、最后才关 Core。当前实现就是完整次序去掉尚不存在的四项。
   */
  @Override
  public void close() {
    coreSimos.close();
  }
}
