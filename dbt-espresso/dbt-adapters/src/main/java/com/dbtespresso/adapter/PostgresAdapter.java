package com.dbtespresso.adapter;

import com.dbtespresso.qa.AdapterContract;

import java.sql.*;
import java.util.*;

public class PostgresAdapter implements AdapterContract {

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private Connection connection;

    public PostgresAdapter(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    @Override
    public void open() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(jdbcUrl, user, password);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open connection: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close connection: " + e.getMessage(), e);
        } finally {
            connection = null;
        }
    }

    @Override
    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public String adapterType() {
        return "postgres";
    }

    @Override
    public QueryResult execute(String sql) {
        return execute(sql, 0);
    }

    @Override
    public QueryResult execute(String sql, long timeoutMs) {
        try (Statement stmt = connection.createStatement()) {
            if (timeoutMs > 0) {
                stmt.setQueryTimeout((int) Math.max(1, timeoutMs / 1000));
            }
            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
                return toQueryResult(stmt.getResultSet());
            }
            return QueryResult.empty(stmt.getUpdateCount());
        } catch (SQLException e) {
            throw new RuntimeException("SQL execution failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ColumnInfo> getColumns(String schema, String table) {
        String sql = """
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                ORDER BY ordinal_position
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            List<ColumnInfo> cols = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(new ColumnInfo(
                            rs.getString("column_name"),
                            rs.getString("data_type"),
                            "YES".equals(rs.getString("is_nullable"))
                    ));
                }
            }
            return cols;
        } catch (SQLException e) {
            throw new RuntimeException("getColumns failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean relationExists(String schema, String name) {
        String sql = """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = ? AND table_name = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("relationExists failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<RelationInfo> listRelations(String schema) {
        String sql = """
                SELECT table_schema, table_name, table_type
                FROM information_schema.tables
                WHERE table_schema = ?
                ORDER BY table_name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            List<RelationInfo> relations = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RelationType type = "VIEW".equals(rs.getString("table_type"))
                            ? RelationType.VIEW : RelationType.TABLE;
                    relations.add(new RelationInfo(
                            rs.getString("table_schema"),
                            rs.getString("table_name"),
                            type
                    ));
                }
            }
            return relations;
        } catch (SQLException e) {
            throw new RuntimeException("listRelations failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void createTableAs(String schema, String name, String selectSql, boolean replace) {
        String qualified = qualified(schema, name);
        try (Statement stmt = connection.createStatement()) {
            if (replace) {
                stmt.execute("DROP TABLE IF EXISTS " + qualified + " CASCADE");
            }
            stmt.execute("CREATE TABLE " + qualified + " AS " + selectSql);
        } catch (SQLException e) {
            throw new RuntimeException("createTableAs failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void createViewAs(String schema, String name, String selectSql, boolean replace) {
        String qualified = qualified(schema, name);
        String prefix = replace ? "CREATE OR REPLACE VIEW " : "CREATE VIEW ";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(prefix + qualified + " AS " + selectSql);
        } catch (SQLException e) {
            throw new RuntimeException("createViewAs failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void dropRelation(String schema, String name, RelationType type) {
        String keyword = type == RelationType.VIEW ? "VIEW" : "TABLE";
        String sql = "DROP " + keyword + " IF EXISTS " + qualified(schema, name) + " CASCADE";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("dropRelation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void renameRelation(String schema, String oldName, String newName) {
        String sql = "ALTER TABLE " + qualified(schema, oldName) + " RENAME TO " + newName;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("renameRelation failed: " + e.getMessage(), e);
        }
    }

    private static String qualified(String schema, String name) {
        return schema + "." + name;
    }

    private static QueryResult toQueryResult(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return new QueryResult(rows, rows.size());
    }
}
