package io.mosire.simos.core.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code events} 表的读写（spec §6.1 / §7.2）。**独立文件**——{@code SqliteStore} 归 C1 独占（裁定 10），
 * 本类只**用**它已经建好的表与索引，不碰它的文件。
 *
 * <p>★★ **写侧故意是 {@code static} 且收 {@link Connection}，不是"本类自己开事务"**——这是本类最要紧的一条： spec §〇.3 第 2
 * 条说得很死，"`revisions` 行与它的**全部**事件行必须同一个事务，否则进程在两步之间死掉会留下 『有 revision
 * 无事件』或反过来的残迹"，而判据二正是"一条命令从入口追到落盘"。 ⇒ 事务边界**只能由调用方**（{@link
 * io.mosire.simos.core.timeline.Timeline}）开，本类只往那条连接上写。 **若把 {@code insert} 写成自己 {@code
 * store.inTransaction(...)}，原子性当场就断了**——而且断得没有症状。
 *
 * <p>★ 这也是**不复用 agentlib 的 {@code SqliteEventStore}** 的原因（spec §〇.3 第 2 条明文否掉）： 它的写路径是单条 INSERT +
 * autocommit，**不暴露事务边界**。表结构照抄，差的只是事务边界。
 *
 * <p>★ 读侧是实例方法（自开读事务）：读不需要与任何写同事务，而 {@code byCorrelation} 正是判据二那条 `WHERE correlation_id = ?` 的落点。
 */
public final class EventStore {

  /** 写五列，{@code seq} 交给 AUTOINCREMENT。 */
  private static final String INSERT_SQL =
      "INSERT INTO events (ts, type, agent, payload, correlation_id) VALUES (?, ?, ?, ?, ?)";

  /**
   * 判据二那条 SQL。★ {@code ORDER BY seq} 不可省：R6 断言的是**类型序列**，没有排序就没有序列 （SQLite 不保证无序 SELECT 的行序，实测常按
   * rowid 出来，但那是**实现巧合**不是契约）。
   */
  private static final String BY_CORRELATION_SQL =
      "SELECT ts, type, agent, payload, correlation_id FROM events"
          + " WHERE correlation_id = ? ORDER BY seq";

  private static final String COUNT_SQL = "SELECT COUNT(*) FROM events";

  private final SqliteStore store;

  public EventStore(SqliteStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /**
   * **在调用方的事务里**写一行。★ 本方法**不**开事务、**不**收 {@link SqliteStore}——见类注。
   *
   * <p>{@code ts} 以 {@link DateTimeFormatter#ISO_INSTANT} 落 TEXT：该格式**往返无损** （{@link Instant#parse}
   * 读得回），且与 agentlib 的 {@code Instant} 语义一致。
   */
  public static void insert(Connection connection, EventRow row) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
      statement.setString(1, DateTimeFormatter.ISO_INSTANT.format(row.ts()));
      statement.setString(2, row.type());
      statement.setString(3, row.agent());
      statement.setString(4, row.payload());
      statement.setString(5, row.correlationId());
      statement.executeUpdate();
    }
  }

  /** 同 {@link #insert}，批量。顺序即写入顺序（也就是 {@code seq} 顺序）。 */
  public static void insertAll(Connection connection, List<EventRow> rows) throws SQLException {
    for (EventRow row : rows) {
      insert(connection, row);
    }
  }

  /**
   * 按 correlationId 取该链路的**全部**事件，**按 {@code seq} 升序**。
   *
   * <p>★ 这是判据二的落点：一次成功推进的全部事件行与它的 revision 行共享同一个 correlationId， 故"从入口追到落盘"就是这一条查询。
   */
  public List<EventRow> byCorrelation(String correlationId) {
    Objects.requireNonNull(correlationId, "correlationId");
    return store.inTransaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(BY_CORRELATION_SQL)) {
            statement.setString(1, correlationId);
            try (ResultSet resultSet = statement.executeQuery()) {
              List<EventRow> rows = new ArrayList<>();
              while (resultSet.next()) {
                rows.add(
                    new EventRow(
                        Instant.parse(resultSet.getString("ts")),
                        resultSet.getString("type"),
                        resultSet.getString("agent"),
                        resultSet.getString("payload"),
                        resultSet.getString("correlation_id")));
              }
              return rows;
            }
          }
        });
  }

  /** 全表行数（R9 / R14 那类"拒绝必须原子：不留残行"的断言要用）。 */
  public long count() {
    return store.inTransaction(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(COUNT_SQL);
              ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
          }
        });
  }
}
