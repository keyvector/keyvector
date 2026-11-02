package dev.keyvector.connector.backfill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;

import dev.keyvector.connector.model.Values;

/** Reads a table's rows through CQL, in the same normal form the stream produces. */
public final class KeyspacesRows {

    private final CqlSession session;
    private final String keyspace;
    private final String table;
    private final List<ColumnMetadata> primaryKey;
    private PreparedStatement byKey;

    public KeyspacesRows(CqlSession session, String keyspace, String table) {
        this.session = session;
        this.keyspace = keyspace;
        this.table = table;
        TableMetadata t = session.getMetadata().getKeyspace(CqlIdentifier.fromInternal(keyspace))
            .flatMap(k -> k.getTable(CqlIdentifier.fromInternal(table)))
            .orElseThrow(() -> new IllegalArgumentException("No table " + keyspace + "." + table + " visible to this session"));
        this.primaryKey = t.getPrimaryKey();
    }

    public List<String> primaryKeyColumns() {
        List<String> out = new ArrayList<>();
        primaryKey.forEach(c -> out.add(c.getName().asInternal()));
        return out;
    }

    private String from() {
        return CqlIdentifier.fromInternal(keyspace).asCql(true) + "." + CqlIdentifier.fromInternal(table).asCql(true);
    }

    /**
     * Every row, page by page, until {@code rowConsumer} returns false. Paging is what keeps
     * memory flat on a table of any size.
     */
    public long scan(int pageSize, Predicate<Map<String, Object>> rowConsumer) {
        SimpleStatement select = SimpleStatement.newInstance("SELECT * FROM " + from())
            .setPageSize(pageSize)
            .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM);
        ResultSet rs = session.execute(select);
        long n = 0;
        for (Row row : rs) {
            n++;
            if (!rowConsumer.test(toMap(row))) {
                break;
            }
        }
        return n;
    }

    /** The current row for a decoded vector key, or null if it no longer exists. */
    public Map<String, Object> fetch(Map<String, String> key) {
        PreparedStatement statement = byKey();
        Object[] values = new Object[primaryKey.size()];
        for (int i = 0; i < primaryKey.size(); i++) {
            ColumnMetadata c = primaryKey.get(i);
            String text = key.get(c.getName().asInternal());
            if (text == null) {
                throw new IllegalArgumentException("Key has no value for primary key column " + c.getName().asInternal());
            }
            values[i] = KeyText.parse(text, c.getType());
        }
        Row row = session.execute(statement.bind(values)).one();
        return row == null ? null : toMap(row);
    }

    private synchronized PreparedStatement byKey() {
        if (byKey == null) {
            StringBuilder cql = new StringBuilder("SELECT * FROM ").append(from()).append(" WHERE ");
            for (int i = 0; i < primaryKey.size(); i++) {
                if (i > 0) {
                    cql.append(" AND ");
                }
                cql.append(primaryKey.get(i).getName().asCql(true)).append(" = ?");
            }
            byKey = session.prepare(SimpleStatement.newInstance(cql.toString()).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM));
        }
        return byKey;
    }

    public Map<String, Object> keyOf(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ColumnMetadata c : primaryKey) {
            out.put(c.getName().asInternal(), row.get(c.getName().asInternal()));
        }
        return out;
    }

    static Map<String, Object> toMap(Row row) {
        Map<String, Object> out = new LinkedHashMap<>();
        int i = 0;
        for (ColumnDefinition d : row.getColumnDefinitions()) {
            out.put(d.getName().asInternal(), Values.fromDriver(row.getObject(i++)));
        }
        return out;
    }
}
