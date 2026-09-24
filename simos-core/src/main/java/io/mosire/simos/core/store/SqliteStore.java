package io.mosire.simos.core.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 存储底座（spec §6.1，U16 甲）：一个库、两张表（{@code revisions} + {@code events}），同库同事务——
 * "落盘"与"留痕"不可能不一致。表 schema 以 spec §3.2 / §6.1 为准（冻结）：2 表 + 4 索引， 全部 {@code IF NOT EXISTS} 幂等
 * DDL，旧库打开时自动补建。
 *
 * <p>★ 2026-09-24 日制裁定后**多了一张 {@code store_meta}**（键值对，不是领域表）：它只存库级语义标签 {@code time_base}（见 {@link
 * #ensureTimeBase}）与格式版本。没有标签且已有 revision 的库**拒绝打开**——旧小时档 不静默按天读。
 *
 * <p>并发模型（C23，照 agentlib 的形态）：一个连接 + 全部公开方法走同一把私有锁。锁用私有 {@code Object} 而非 {@code synchronized}
 * 修饰符：实例经静态工厂 {@link #open} 暴露，用类自带锁会让外部持锁者干扰内部互斥 （SpotBugs USO_UNSAFE_METHOD_SYNCHRONIZATION）。
 *
 * <p>事务（C23）：{@code BEGIN IMMEDIATE} 在事务一开始就拿写锁，不出现"读事务升级为写事务"时的 SQLITE_BUSY 僵局——单连接 +
 * 私有锁下这是冗余的，但冗余的方向是安全。任何异常 ⇒ 回滚， 且清理自身失败不得顶掉原异常（否则现场被覆盖，debug 看到的是 rollback 的错，不是根因）。
 *
 * <p>★ 与 spec §6.2 草图的一处<b>实测取代</b>（2026-09-18，sqlite-jdbc 3.53.4.0 当场探针，详见 task-5-report.md）：spec
 * 草图写 {@code setAutoCommit(false)} + {@code conn.commit()} / {@code conn.rollback()}，但该驱动在
 * autoCommit=false 时<b>由驱动自己开事务</b>，此刻再执行显式 {@code BEGIN IMMEDIATE} 会撞 {@code cannot start a
 * transaction within a transaction}； 而 autoCommit=true 下 {@code conn.commit()} 又抛 {@code database
 * in auto-commit mode}。 故本实现保持 autoCommit 出厂值不动，用显式 SQL {@code BEGIN IMMEDIATE} / {@code COMMIT} /
 * {@code ROLLBACK} 驱动事务边界——语义与 spec 等价：单事务、写锁前置、显式边界，一样不少。
 */
public final class SqliteStore implements AutoCloseable {

  /** spec §3.2 的冻结 DDL：创世行 parent 两列为 NULL；复合外键指向自身主键 (branch, revision)。 */
  private static final String REVISIONS_DDL =
      """
      CREATE TABLE IF NOT EXISTS revisions (
        branch          TEXT    NOT NULL,
        revision        INTEGER NOT NULL,
        parent_branch   TEXT,                       -- 创世为 NULL
        parent_revision INTEGER,                    -- 创世为 NULL
        tick            INTEGER NOT NULL,           -- 模拟时刻（SimosTimestamp.tick）
        calendar_label  TEXT,                       -- 可空（SimosTimestamp.calendarLabel）
        command_id      TEXT    NOT NULL,
        correlation_id  TEXT    NOT NULL,
        initiator       TEXT    NOT NULL,
        command_type    TEXT    NOT NULL,
        changeset_json  TEXT    NOT NULL,
        PRIMARY KEY (branch, revision),
        FOREIGN KEY (parent_branch, parent_revision) REFERENCES revisions (branch, revision)
      )
      """;

  /** spec §3.2 的两个 revisions 索引之一：correlationId 全链（判据二）的落点。 */
  private static final String REVISIONS_CORRELATION_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_revisions_correlation "
          + "ON revisions (correlation_id, branch, revision)";

  /** spec §3.2 的两个 revisions 索引之二：父链递归（重放 / chainToGenesis）的落点。 */
  private static final String REVISIONS_PARENT_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_revisions_parent ON revisions (parent_branch, parent_revision)";

  /** spec §6.1：events 表，建表语句与 agentlib 逐字一致（列名 / 类型 / 默认值全同）。 */
  private static final String EVENTS_DDL =
      """
      CREATE TABLE IF NOT EXISTS events (
        seq            INTEGER PRIMARY KEY AUTOINCREMENT,
        ts             TEXT    NOT NULL,
        type           TEXT    NOT NULL,
        agent          TEXT    NOT NULL,
        payload        TEXT    NOT NULL DEFAULT '',
        correlation_id TEXT    NOT NULL DEFAULT ''
      )
      """;

  /** spec §6.1：agentlib 同款索引，"按类型分组取最大 seq"的落点。 */
  private static final String EVENTS_TYPE_CORRELATION_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_events_type_correlation_id_seq "
          + "ON events (type, correlation_id, seq)";

  /** spec §6.1 补的索引（spec 〇.3 第 3 条）：agentlib 那条以 type 为前导列，服务不了"只按 correlationId 查"。 */
  private static final String EVENTS_CORRELATION_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_events_correlation_id_seq ON events (correlation_id, seq)";

  /**
   * 库级元数据（2026-09-24 日制裁定新增）：{@code time_base} 是**时间语义的标签**，与 {@link Envelope#TIME_BASE_DAY}
   * 同源。它不是第三个领域表——它只回答"这个库的 tick 是小时还是天"这一个问题。
   */
  private static final String STORE_META_DDL =
      """
      CREATE TABLE IF NOT EXISTS store_meta (
        key   TEXT NOT NULL PRIMARY KEY,
        value TEXT NOT NULL
      )
      """;

  /** 时间基标签的键名（值见 {@link Envelope#TIME_BASE_DAY}）。 */
  static final String TIME_BASE_KEY = "time_base";

  /** 格式版本：日制底座落地的第一版；将来改 schema 再逐版加。 */
  private static final String FORMAT_VERSION_KEY = "format_version";

  private static final String FORMAT_VERSION_DAY_BASE = "1";

  private static final String SELECT_TIME_BASE =
      "SELECT value FROM store_meta WHERE key = '" + TIME_BASE_KEY + "'";

  private static final String ANY_REVISION_EXISTS = "SELECT EXISTS(SELECT 1 FROM revisions)";

  private static final String PRAGMA_JOURNAL_MODE_WAL = "PRAGMA journal_mode = WAL";
  private static final String PRAGMA_BUSY_TIMEOUT = "PRAGMA busy_timeout = 5000";
  private static final String PRAGMA_FOREIGN_KEYS_ON = "PRAGMA foreign_keys = ON";
  private static final String PRAGMA_WAL_CHECKPOINT_TRUNCATE = "PRAGMA wal_checkpoint(TRUNCATE)";

  private static final String BEGIN_TXN = "BEGIN IMMEDIATE";
  private static final String COMMIT_TXN = "COMMIT";
  private static final String ROLLBACK_TXN = "ROLLBACK";

  private final Connection connection;
  private final Path dbFile;
  private final Object lock = new Object();

  /** 仅在 {@code synchronized (lock)} 内读写：close 幂等 + 关闭后拒绝再用。 */
  private boolean closed;

  private SqliteStore(Connection connection, Path dbFile) {
    this.connection = connection;
    this.dbFile = dbFile;
  }

  /**
   * 打开存储：建目录、设三条 PRAGMA、建 2 表 4 索引。
   *
   * <p>PRAGMA 必须在任何事务之前：{@code journal_mode} 在事务内是空操作， {@code foreign_keys} 在事务内设置同样不生效（SQLite
   * 的设计如此，R2 的判别力正来源于此）。
   *
   * @param dbFile 库文件路径（父目录不存在则自动创建）
   */
  public static SqliteStore open(Path dbFile) {
    // 连接声明在 try 之外：initialize 失败时 catch 里要回收它；getConnection 自身失败时仍为 null。
    Connection connection = null;
    try {
      Path parent = dbFile.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
      SqliteStore store = new SqliteStore(connection, dbFile);
      store.initialize();
      return store;
    } catch (IOException e) {
      // 建目录失败必然在 getConnection 之前：此路径上没有连接可回收
      throw new UncheckedIOException("无法创建存储目录: " + dbFile, e);
    } catch (SQLException e) {
      // initialize（PRAGMA/DDL）失败时连接已建立且处于半初始化态：直接关 Connection，而不是调本类
      // close()——close() 先 wal_checkpoint 再 close，checkpoint 一抛错就跳过 close（泄漏照旧），
      // 且会用"关闭存储失败"顶掉这里的原异常
      closeSilently(connection);
      throw new IllegalStateException("打开 SQLite 存储失败: " + dbFile, e);
    } catch (RuntimeException e) {
      // ★ 时间基门禁（旧档/异基）抛的是运行时异常：此时连接同样已建立，必须回收，
      //   否则"拒绝打开"会顺手泄漏一个连接与它的 WAL 句柄。
      //   ★ 不能写 `throw e`：SpotBugs 的 THROWS_METHOD_THROWS_RUNTIMEEXCEPTION（SEI CERT ERR07-J）
      //   只放行"抛新实例"；门禁的拒因文案**逐字保留**（测试按它断言），原异常挂在 cause 上不丢。
      closeSilently(connection);
      if (e instanceof IllegalStateException) {
        throw new IllegalStateException(e.getMessage(), e);
      }
      throw new IllegalStateException("打开 SQLite 存储失败（initialize 期运行时异常）: " + dbFile, e);
    }
  }

  private void initialize() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(PRAGMA_JOURNAL_MODE_WAL);
      statement.execute(PRAGMA_BUSY_TIMEOUT);
      statement.execute(PRAGMA_FOREIGN_KEYS_ON);
      statement.execute(REVISIONS_DDL);
      statement.execute(REVISIONS_CORRELATION_INDEX);
      statement.execute(REVISIONS_PARENT_INDEX);
      statement.execute(EVENTS_DDL);
      statement.execute(EVENTS_TYPE_CORRELATION_INDEX);
      statement.execute(EVENTS_CORRELATION_INDEX);
      statement.execute(STORE_META_DDL);
      ensureTimeBase(statement);
    }
  }

  /**
   * 时间基门禁（2026-09-24 日制裁定，POLITICAL_ECONOMY_DESIGN.md §3）——**旧档 fail-closed，不静默按天读**：
   *
   * <ul>
   *   <li>已有 {@code time_base} 标签：必须是 {@link Envelope#TIME_BASE_DAY}，否则拒绝打开（异基/未知基）。
   *   <li>没有标签：<b>空库</b>（{@code revisions} 零行）⇒ 就地打标为日制——还没有任何语义事实，打标不改变任何东西； <b>非空库</b> ⇒
   *       抛异常。2026-09-24 之前的档其 {@code tick} 代表小时，按天重放会把"持续 24 小时"读成 "持续 24 天"，而库里已有 revision
   *       说明这种语义已经落进事实。
   * </ul>
   *
   * <p>★ 之所以把门禁放在 {@code open} 而不是某个 loader：这是**库级**的语义属性，读、写、重放、分叉都要经过它；
   * 只挡读会让旧库被新引擎写坏（正是本裁定要防的那类不可逆操作）。
   */
  private static void ensureTimeBase(Statement statement) throws SQLException {
    String existing = null;
    try (ResultSet rows = statement.executeQuery(SELECT_TIME_BASE)) {
      if (rows.next()) {
        existing = rows.getString(1);
      }
    }
    if (existing != null) {
      if (!Envelope.TIME_BASE_DAY.equals(existing)) {
        throw new IllegalStateException(
            "该 store 的时间基不是日制（time_base="
                + existing
                + "）：本引擎只认 "
                + Envelope.TIME_BASE_DAY
                + "（2026-09-24 日制裁定，POLITICAL_ECONOMY_DESIGN.md §3）");
      }
      return;
    }

    boolean anyRevision;
    try (ResultSet rows = statement.executeQuery(ANY_REVISION_EXISTS)) {
      anyRevision = rows.next() && rows.getInt(1) != 0;
    }
    if (anyRevision) {
      throw new IllegalStateException(
          "该 store 没有时间基标签（time_base）且已有 revision ⇒ 疑似 2026-09-24 日制裁定之前的旧档（1 tick = 1 小时）。"
              + "新引擎拒绝按天打开它（重放会把小时语义读成天语义）；请改用新 store 目录，或等待离线迁移工具"
              + "（POLITICAL_ECONOMY_DESIGN.md §3）");
    }

    statement.execute(
        "INSERT INTO store_meta (key, value) VALUES ('"
            + TIME_BASE_KEY
            + "', '"
            + Envelope.TIME_BASE_DAY
            + "')");
    statement.execute(
        "INSERT INTO store_meta (key, value) VALUES ('"
            + FORMAT_VERSION_KEY
            + "', '"
            + FORMAT_VERSION_DAY_BASE
            + "')");
  }

  /**
   * 在一个事务里执行 {@code work}：{@code BEGIN IMMEDIATE} 起步拿写锁，{@code work} 收到本连接， 正常返回即 {@code
   * COMMIT}，任何异常即回滚（清理自身失败不顶掉原异常）。
   *
   * <p>这是本类唯一的公开入口——上层（Timeline 等）的一切读写都从这里过， 保证 revision 行与它的全部事件行同生同死。
   *
   * @param work 事务体，参数是本 store 的连接（不得在该事务外继续使用它）
   * @param <T> work 的返回类型
   * @return work 的返回值，原样透传
   */
  public <T> T inTransaction(SqlFunction<T> work) {
    synchronized (lock) {
      ensureOpen();
      try {
        try (Statement statement = connection.createStatement()) {
          statement.execute(BEGIN_TXN);
        }
        T result = work.apply(connection);
        try (Statement statement = connection.createStatement()) {
          statement.execute(COMMIT_TXN);
        }
        return result;
      } catch (Exception e) {
        rollbackQuietly();
        throw new IllegalStateException("事务失败，已回滚", e);
      }
    }
  }

  /** 清理自身失败不得顶掉原异常：rollback 报错只说明"没回滚成"，根因仍是 work 抛的那个。 */
  private void rollbackQuietly() {
    try (Statement statement = connection.createStatement()) {
      statement.execute(ROLLBACK_TXN);
    } catch (SQLException ignored) {
      // 刻意静默：此处再抛只会替换掉"事务失败"这个根因
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return; // AutoCloseable 幂等：重复 close 不抛
      }
      closed = true;
      try {
        // 收尾 checkpoint：把 WAL 内容刷回主库文件，进程退出后不留 -wal 文件
        try (Statement statement = connection.createStatement()) {
          statement.execute(PRAGMA_WAL_CHECKPOINT_TRUNCATE);
        }
        connection.close();
      } catch (SQLException e) {
        throw new IllegalStateException("关闭 SQLite 存储失败: " + dbFile, e);
      }
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("SQLite 存储已关闭: " + dbFile);
    }
  }

  /** 半初始化失败路径的回收：没有连接或关不上都无补救手段，静默即可。 */
  private static void closeSilently(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException ignored) {
      // 刻意静默：清理自身失败不得顶掉调用方正上抛的异常
    }
  }

  /**
   * 事务体：拿到连接，在 BEGIN IMMEDIATE 与 COMMIT 之间执行。受检异常收窄到 {@code SQLException} （SpotBugs
   * THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION 不收 {@code throws Exception}）；非 SQL
   * 的运行时异常一样会触发回滚——收口在 {@code inTransaction} 的 {@code catch (Exception)}。
   */
  @FunctionalInterface
  public interface SqlFunction<T> {

    /**
     * 在事务内执行一段工作。
     *
     * @param connection 本 store 的唯一连接（仅限本事务内使用）
     * @return 工作结果，原样透传给调用方
     * @throws SQLException 任何异常（含运行时异常）都会触发整个事务回滚，并包成 IllegalStateException 上抛
     */
    T apply(Connection connection) throws SQLException;
  }
}
