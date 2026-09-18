package io.mosire.simos.core.timeline;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.EventRow;
import io.mosire.simos.core.store.EventStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 时间线 DAG 的读写与一切派生（spec §3.2 / §3.3 / §3.4）。**只有 {@code revisions} 一张表**——分支清单、head、分岔点、父链、
 * correlationId 落盘、checkpoint「应当有」的判定（C19）全部从它派生，没有第二真相来源可漂移。
 *
 * <p>★ DDL 归 {@code SqliteStore}（Task 5）：本类在打开的 store 之上工作，**不建表、不建索引**；表名/列名在本类里只是 SQL
 * 常量与行映射，不是第二份 schema。
 *
 * <p>★ 一切读写都过 {@link SqliteStore#inTransaction}（C23 / Task 5 的约定）：{@code fork} 的入口检查（head ==
 * expected）与 新行插入在**同一个事务**里——检查通过后被并发写抢走是不可能的。冲突时**不写**，返回 {@link Optional#empty()}（Timeline 只写，
 * 冲突判定归 CommandBus，spec §3.4 ①）。
 *
 * <p>★ {@link #hasCheckpoint} 是**纯函数**（C18/C19）：只看 {@code revisions} 表与构造期给定的周期 N，**不碰文件系统**——
 * 判的是「(b, r) **应当**有 checkpoint」；「实际有没有文件」归 Task 7 的 {@code CheckpointStore}。三项判定（C19，任取其一）： ①
 * {@code r % N == 0}；② (b, r) 是某分支 revision 1 的 parent（分岔强制一次，也是「重放步数上界 ≤ N」跨分支的保证）； ③ (b, r) ==
 * (main, 1)（创世）。
 *
 * <p>★ 分岔行的变更集是 {@link WorldChangeSet#empty()}（C13——分岔不改变世界，只增加一条边），经 {@link #changeSetJson}
 * 序列化：全仓**唯一**的 changeset 落盘点。谁需要把 {@code WorldChangeSet} 落进 {@code changeset_json} 列，谁就走它—— 手写
 * {@code {"modules":{}}} 这类字面量等于「手工对着状态类型维护的平行结构」（铁律 5 的事故形态）。
 */
public final class Timeline {

  /** 分岔行的命令类型（spec §3.2 注释冻结的 Core 自有命令类型之一）；由本类钉住，调用方不必重复写这个串。 */
  public static final String FORK_COMMAND_TYPE = "core.ForkBranch";

  /** 创世分支名（spec §3.1 冻结：{@code (main, 1)} 是创世）。供 {@link #hasCheckpoint} 第③项判定。 */
  private static final String GENESIS_BRANCH = "main";

  private static final String REVISIONS_TABLE = "revisions";

  /** spec §3.2 的列清单（只含本类用到的）：INSERT 与 SELECT 共用同一份顺序，行映射按下标对齐。 */
  private static final String ALL_COLUMNS =
      "branch, revision, parent_branch, parent_revision, tick, calendar_label, command_id,"
          + " correlation_id, initiator, command_type, changeset_json";

  private static final String INSERT_SQL =
      "INSERT INTO "
          + REVISIONS_TABLE
          + " ("
          + ALL_COLUMNS
          + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String SELECT_BY_REF_SQL =
      "SELECT " + ALL_COLUMNS + " FROM " + REVISIONS_TABLE + " WHERE branch = ? AND revision = ?";

  private static final String HEAD_SQL =
      "SELECT MAX(revision) FROM " + REVISIONS_TABLE + " WHERE branch = ?";

  private static final String BRANCHES_SQL =
      "SELECT DISTINCT branch FROM " + REVISIONS_TABLE + " ORDER BY branch";

  private static final String BY_CORRELATION_SQL =
      "SELECT "
          + ALL_COLUMNS
          + " FROM "
          + REVISIONS_TABLE
          + " WHERE correlation_id = ? ORDER BY branch, revision";

  /** C19 第②项：是否存在某分支的 revision 1 以 (branch, revision) 为 parent（分岔强制一次的落点）。 */
  private static final String FORK_PARENT_EXISTS_SQL =
      "SELECT 1 FROM "
          + REVISIONS_TABLE
          + " WHERE revision = 1 AND parent_branch = ? AND parent_revision = ? LIMIT 1";

  /**
   * changeset 的落盘 mapper：Task 3 的单点装配 + 一个**给 {@link ChangeSet} 补类型信息**的 mixin（裁定 21）。
   *
   * <p>为什么必须有类型信息（2026-09-18 当场探针实测，jshell + 本仓依赖 Jackson 2.22.2）：{@code ChangeSet} 是标记接口， 裸 mapper
   * 序列化空集 {@code {"modules":{}}} 能往返，但任何**非空**变更集落盘后读回必死（{@code InvalidDefinitionException: Cannot
   * construct instance of ChangeSet ... abstract types either need to be ... contain additional
   * type information}）。 裁定 4 的注解方案在这里**不可复制**—— {@code ChangeSet} 是 util 的冻结契约（U13）且不在本任务路径上；mixin
   * 是 Core 侧唯一不动它的办法， 故集中在本类一个点上注册，用例钉住它真的生效。
   *
   * <p>为什么 {@code Id.CLASS} 而非裁定 4 用的 {@code Id.NAME}：{@code Id.NAME} + {@code @JsonSubTypes}
   * 要求**封闭**子类集， 而变更集的子类在三个领域模块里、对 Core 是**开放集**（铁律 4：Core 编译期看不见它们）——封闭名单在这里写不出来。 代价是行内存的是全限定类名
   * （重命名/挪包即旧档不可读），这是开放集下唯一的选项，记入 task-6-report 的取代说明。
   */
  private static final ObjectMapper CHANGESET_MAPPER =
      SimosObjectMapper.create(
          new SimpleModule("changeset-typing")
              .setMixInAnnotation(ChangeSet.class, ChangeSetMixin.class));

  /** {@link #CHANGESET_MAPPER} 挂在 {@link ChangeSet} 上的类型信息（属性名 {@code @class} 不可能与任何模块键撞名）。 */
  @JsonTypeInfo(
      use = JsonTypeInfo.Id.CLASS,
      include = JsonTypeInfo.As.PROPERTY,
      property = "@class")
  interface ChangeSetMixin {}

  private final SqliteStore store;

  /** C19 第①项的周期 N（spec §3.5：默认 100，由 CoreConfig 给出；构造参数是为可测性服务的）。 */
  private final long checkpointInterval;

  /**
   * @param store 已打开的存储底座（DDL 已由它建好）；生命周期归调用方，本类不关闭它
   * @param checkpointInterval C19 第①项的周期 N，必须 ≥ 1
   */
  public Timeline(SqliteStore store, long checkpointInterval) {
    if (checkpointInterval < 1) {
      throw new IllegalArgumentException(
          "checkpointInterval 必须 ≥ 1（C19 第①项的取模周期）: " + checkpointInterval);
    }
    this.store = store;
    this.checkpointInterval = checkpointInterval;
  }

  /**
   * 落一行 revision。主键/外键冲突由库拒绝、整个事务回滚后以 {@code IllegalStateException} 上抛（R2 / R13 的形态：越界写不留残行）。
   *
   * <p>★ 本类**不校验** revision 是否 = head + 1：那道乐观并发检查归命令层（C17）。本类只保证「写进去的行与库的约束一致」。
   */
  public void appendRevision(RevisionRow row) {
    store.inTransaction(
        connection -> {
          insert(connection, row);
          return null;
        });
  }

  /**
   * 落一行 revision **并同时落它的全部事件行——同一个事务**（Task 11 新增；spec §〇.3 第 2 条 / 判据二）。
   *
   * <p>★★ **本重载存在的唯一理由就是原子性**：判据二要"一条命令从入口追到落盘"，若 revision 行与事件行分两个事务写， 进程在两步之间死掉就会留下**「有 revision
   * 无事件」或反过来**的残迹——而那种残迹**没有症状**， 只会在事后审计时表现为"这条命令的链路断了一截"。 ⇒ 事件行必须在**同一条 {@link Connection}**
   * 上写，而事务边界只有本类开得了 （{@link EventStore#insert} 因此故意是 static 且收 {@code Connection}，见其类注）。
   *
   * <p>★ **只加不改**：单参的那个 {@link #appendRevision(RevisionRow)} 原样保留（等价于本方法传空列表）， Task 6/8/10
   * 的既有护栏一个字都不用动。
   *
   * <p>★ 事件与 revision 的**同事务**是多对一：一次提交可以带 N 条事件（判据二的成功推进就带 5 种）。 任何一条写失败（如库约束）⇒ 整个事务回滚 ⇒
   * **revision 行也不落**，与 R2/R13 的形态一致。
   */
  public void appendRevision(RevisionRow row, List<EventRow> events) {
    store.inTransaction(
        connection -> {
          insert(connection, row);
          EventStore.insertAll(connection, events);
          return null;
        });
  }

  /**
   * 落一批事件行，**不开 revision**——**没有 revision 的命令**（被拒 / 冲突）的落点（Task 11 新增）。
   *
   * <p>★ 与上面那个重载**同一条原子性纪律**：一批事件要么全落要么全不落。被拒的命令不留 revision 行， 但它的 {@code command.received} 与
   * {@code command.rejected} **必须一起落**，否则事后审计会看到 "收到了却不知结局"的半条链路。
   *
   * <p>★ 本方法与 {@link #appendRevision(RevisionRow, List)} 一起，使本类成为 {@code CommandBus} 触库的
   * **唯一入口**——这是**有意**的：{@code CommandBus} 因此不必再持一个 {@code EventStore}， 它的构造函数**一个字都不用改**（Task 10
   * 的五轮变异体因此**依然是针对同一份源码的差分**，没有作废）。
   */
  public void appendEvents(List<EventRow> events) {
    store.inTransaction(
        connection -> {
          EventStore.insertAll(connection, events);
          return null;
        });
  }

  /**
   * 分岔（spec §3.4 的 ① + ③，冻结语义）：{@code head(source) == expected} 时写新行 {@code (newBranch, 1)}， parent
   * 指向 {@code (source, expected)}，变更集 = {@link WorldChangeSet#empty()}（C13），模拟时刻**继承父**。
   *
   * @return 成功 ⇒ {@code (newBranch, 1)}；{@code head(source)} 缺失或不等于 {@code expected} ⇒ {@link
   *     Optional#empty()} 且**不写任何行**（冲突判定归 CommandBus）
   */
  public Optional<StateRef> fork(
      BranchId source,
      RevisionId expected,
      BranchId newBranch,
      String commandId,
      String correlationId,
      String initiator) {
    return store.inTransaction(
        connection -> {
          Optional<RevisionId> current = head(connection, source);
          if (current.isEmpty() || current.get().compareTo(expected) != 0) {
            return Optional.empty();
          }
          StateRef forkPoint = new StateRef(source, expected);
          // head 存在 ⇒ 它的行必在：读它只为继承 tick（spec §3.4 ③）；缺席是库被绕过本类改写，当场炸
          RevisionRow parentRow =
              row(connection, forkPoint)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "head 行缺席（库被绕过本类写坏）: "
                                  + forkPoint.branch().value()
                                  + "@"
                                  + forkPoint.revision().value()));
          RevisionRow firstRevision =
              new RevisionRow(
                  newBranch,
                  new RevisionId(1),
                  Optional.of(forkPoint),
                  parentRow.timestamp(),
                  commandId,
                  correlationId,
                  initiator,
                  FORK_COMMAND_TYPE,
                  changeSetJson(WorldChangeSet.empty()));
          insert(connection, firstRevision);
          return Optional.of(new StateRef(newBranch, firstRevision.revision()));
        });
  }

  /** head(b)（spec §3.3）：分支不存在 ⇒ 空。 */
  public Optional<RevisionId> head(BranchId branch) {
    // 变异 m1：忽略 branch 参数，取全局 MAX(revision)（打判据一③）
    return store.inTransaction(
        connection -> {
          try (PreparedStatement statement =
                  connection.prepareStatement("SELECT MAX(revision) FROM " + REVISIONS_TABLE);
              ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
              return Optional.empty();
            }
            long max = resultSet.getLong(1);
            if (resultSet.wasNull()) {
              return Optional.empty();
            }
            return Optional.of(new RevisionId(max));
          }
        });
  }

  /** 分支清单（spec §3.3）：{@code SELECT DISTINCT branch}，按字典序（迭代序确定，可比）。 */
  public Set<BranchId> branches() {
    return store.inTransaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(BRANCHES_SQL);
              ResultSet resultSet = statement.executeQuery()) {
            Set<BranchId> out = new LinkedHashSet<>();
            while (resultSet.next()) {
              out.add(new BranchId(resultSet.getString(1)));
            }
            return Collections.unmodifiableSet(out);
          }
        });
  }

  /** 一行的全字段（spec §3.3）：不存在 ⇒ 空。 */
  public Optional<RevisionRow> row(StateRef ref) {
    return store.inTransaction(connection -> row(connection, ref));
  }

  /** 父坐标（spec §3.3 的分岔点/父列查询）：创世行与不存在的行都 ⇒ 空。 */
  public Optional<StateRef> parent(StateRef ref) {
    return row(ref).flatMap(RevisionRow::parent);
  }

  /**
   * 从 {@code from} 沿 parent 指针走到创世（spec §3.3 的父链递归，可能跨分支）：{@code [from, parent(from), …, 创世]}。 行不存在
   * ⇒ 空清单；行在但父行缺席 ⇒ {@code IllegalStateException}（复合外键本来防不住 NULL 混写，这里是兜底的显式炸点）。
   */
  public List<StateRef> chainToGenesis(StateRef from) {
    return store.inTransaction(
        connection -> {
          List<StateRef> chain = new ArrayList<>();
          Optional<StateRef> current = Optional.of(from);
          while (current.isPresent()) {
            // lambda 里引用的局部变量必须 effectively final：循环变量在此先拆成每步的 final 副本
            StateRef step = current.get();
            Optional<RevisionRow> rowHere = row(connection, step);
            if (rowHere.isEmpty()) {
              if (chain.isEmpty()) {
                return Collections.unmodifiableList(chain); // 起点行不存在 ⇒ 空清单
              }
              throw new IllegalStateException(
                  "父链中断（行在指针、行不在库）: " + step.branch().value() + "@" + step.revision().value());
            }
            chain.add(step);
            current = rowHere.orElseThrow().parent();
          }
          return Collections.unmodifiableList(chain);
        });
  }

  /** 某 correlationId 的全部落盘（spec §3.3，判据二的落点）：按 (branch, revision) 字典序。 */
  public List<RevisionRow> byCorrelation(String correlationId) {
    return store.inTransaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(BY_CORRELATION_SQL)) {
            statement.setString(1, correlationId);
            try (ResultSet resultSet = statement.executeQuery()) {
              List<RevisionRow> out = new ArrayList<>();
              while (resultSet.next()) {
                out.add(mapRow(resultSet));
              }
              return Collections.unmodifiableList(out);
            }
          }
        });
  }

  /**
   * (b, r) 是否**应当**有 checkpoint（C19）——纯函数，只看 revisions 表，不碰文件系统。
   *
   * <p>三项任取其一：① r % N == 0；② (b, r) 是某分支 revision 1 的 parent（分岔强制一次）；③ (b, r) == (main, 1)（创世）。
   *
   * <p>★ 为什么要「纯函数」：checkpoint 是**纯优化**（C18），缺文件不回退失败。若存在性要看磁盘，那磁盘就成了第二真相来源 ⇒ 违反 C18。 这里判「应当有」，Task
   * 7 判「实际有没有」。
   */
  public boolean hasCheckpoint(StateRef ref) {
    long revision = ref.revision().value();
    if (revision % checkpointInterval == 0) {
      return true;
    }
    if (isForkParent(ref)) {
      return true;
    }
    return GENESIS_BRANCH.equals(ref.branch().value()) && revision == 1;
  }

  /**
   * 变更集 → {@code changeset_json} 列的文本。**全仓唯一**的 changeset 落盘点（见类注）：带类型信息， 非空变更集落盘后能读回（裸 mapper
   * 实测必死，探针 P7）。
   *
   * <p>读回走 {@code readValue(json, WorldChangeSet.class)}；按 C26 的分工，模块变更集的**内容**由各模块自己的 codec 解释。
   */
  public static String changeSetJson(WorldChangeSet changeset) {
    try {
      return CHANGESET_MAPPER.writeValueAsString(changeset);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("变更集序列化失败", e);
    }
  }

  /**
   * {@link #changeSetJson} 的逆操作：{@code changeset_json} 列的文本 → {@link WorldChangeSet}。 与写侧同一个
   * mapper、同一份类型信息 （缺类型信息的字节在这里当场炸——探针实测，裸字节读回必死）。
   */
  public static WorldChangeSet readChangeSet(String json) {
    try {
      return CHANGESET_MAPPER.readValue(json, WorldChangeSet.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("变更集 JSON 非法或缺类型信息（@class）", e);
    }
  }

  /** C19 第②项：是否存在某分支的 revision 1 以 (branch, revision) 为 parent。 */
  private boolean isForkParent(StateRef ref) {
    return store.inTransaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(FORK_PARENT_EXISTS_SQL)) {
            statement.setString(1, ref.branch().value());
            statement.setLong(2, ref.revision().value());
            try (ResultSet resultSet = statement.executeQuery()) {
              return resultSet.next();
            }
          }
        });
  }

  private Optional<RevisionId> head(Connection connection, BranchId branch) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(HEAD_SQL)) {
      statement.setString(1, branch.value());
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        long max = resultSet.getLong(1);
        if (resultSet.wasNull()) {
          return Optional.empty();
        }
        return Optional.of(new RevisionId(max));
      }
    }
  }

  private Optional<RevisionRow> row(Connection connection, StateRef ref) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_REF_SQL)) {
      statement.setString(1, ref.branch().value());
      statement.setLong(2, ref.revision().value());
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        return Optional.of(mapRow(resultSet));
      }
    }
  }

  private static void insert(Connection connection, RevisionRow row) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
      statement.setString(1, row.branch().value());
      statement.setLong(2, row.revision().value());
      Optional<StateRef> parent = row.parent();
      if (parent.isPresent()) {
        statement.setString(3, parent.get().branch().value());
        statement.setLong(4, parent.get().revision().value());
      } else {
        statement.setNull(3, Types.VARCHAR);
        statement.setNull(4, Types.INTEGER);
      }
      SimosTimestamp timestamp = row.timestamp();
      statement.setLong(5, timestamp.tick());
      if (timestamp.calendarLabel().isPresent()) {
        statement.setString(6, timestamp.calendarLabel().get());
      } else {
        statement.setNull(6, Types.VARCHAR);
      }
      statement.setString(7, row.commandId());
      statement.setString(8, row.correlationId());
      statement.setString(9, row.initiator());
      statement.setString(10, row.commandType());
      statement.setString(11, row.changesetJson());
      statement.executeUpdate();
    }
  }

  /** 行映射（spec §3.2 的列 ↔ {@link RevisionRow}）：parent 两列同空即创世，只空一列是库被绕开本类写坏 ⇒ 当场炸。 */
  private static RevisionRow mapRow(ResultSet resultSet) throws SQLException {
    String parentBranch = resultSet.getString("parent_branch");
    long parentRevision = resultSet.getLong("parent_revision");
    boolean parentRevisionWasNull = resultSet.wasNull();
    Optional<StateRef> parent;
    if (parentBranch == null && parentRevisionWasNull) {
      parent = Optional.empty();
    } else if (parentBranch != null && !parentRevisionWasNull) {
      parent =
          Optional.of(new StateRef(new BranchId(parentBranch), new RevisionId(parentRevision)));
    } else {
      throw new IllegalStateException("revisions 行的 parent 两列只有一列为 NULL（库被绕开本类写坏）");
    }
    String calendarLabel = resultSet.getString("calendar_label");
    SimosTimestamp timestamp =
        new SimosTimestamp(
            resultSet.getLong("tick"),
            calendarLabel == null ? Optional.empty() : Optional.of(calendarLabel));
    return new RevisionRow(
        new BranchId(resultSet.getString("branch")),
        new RevisionId(resultSet.getLong("revision")),
        parent,
        timestamp,
        resultSet.getString("command_id"),
        resultSet.getString("correlation_id"),
        resultSet.getString("initiator"),
        resultSet.getString("command_type"),
        resultSet.getString("changeset_json"));
  }
}
