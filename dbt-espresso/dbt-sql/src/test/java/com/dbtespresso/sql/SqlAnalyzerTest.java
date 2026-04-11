package com.dbtespresso.sql;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

class SqlAnalyzerTest {

    @Nested class Validation {
        @Test void validSelect() {
            assertThat(SqlAnalyzer.isValid("SELECT 1")).isTrue();
        }

        @Test void validJoin() {
            assertThat(SqlAnalyzer.isValid(
                    "SELECT a.id, b.name FROM orders a JOIN customers b ON a.cid = b.id"
            )).isTrue();
        }

        @Test void invalidSql() {
            assertThat(SqlAnalyzer.isValid("SELECTT FROMM")).isFalse();
        }

        @Test void parseThrowsOnInvalid() {
            assertThatThrownBy(() -> SqlAnalyzer.parse("NOT VALID SQL AT ALL"))
                    .isInstanceOf(SqlValidationException.class);
        }
    }

    @Nested class TableExtraction {
        @Test void singleTable() {
            var tables = SqlAnalyzer.extractTableNames("SELECT * FROM orders");
            assertThat(tables).containsExactly("orders");
        }

        @Test void schemaQualified() {
            var tables = SqlAnalyzer.extractTableNames(
                    "SELECT * FROM analytics.orders");
            assertThat(tables).containsExactly("analytics.orders");
        }

        @Test void joinExtractsMultiple() {
            var tables = SqlAnalyzer.extractTableNames("""
                    SELECT o.id, c.name
                    FROM raw.orders o
                    JOIN raw.customers c ON o.customer_id = c.id
                    """);
            assertThat(tables).containsExactlyInAnyOrder("raw.orders", "raw.customers");
        }

        @Test void cteExtractsBaseTable() {
            var tables = SqlAnalyzer.extractTableNames("""
                    WITH recent AS (
                        SELECT * FROM events WHERE created_at > '2024-01-01'
                    )
                    SELECT * FROM recent
                    """);
            // JSQLParser extracts the physical table "events" from the CTE
            assertThat(tables).contains("events");
        }

        @Test void subqueryExtraction() {
            var tables = SqlAnalyzer.extractTableNames("""
                    SELECT * FROM (
                        SELECT id FROM users WHERE active = true
                    ) sub
                    """);
            assertThat(tables).contains("users");
        }
    }

    @Nested class StatementType {
        @Test void selectIsSelect() {
            assertThat(SqlAnalyzer.isSelectStatement("SELECT 1")).isTrue();
        }

        @Test void insertIsNotSelect() {
            assertThat(SqlAnalyzer.isSelectStatement(
                    "INSERT INTO t VALUES (1)")).isFalse();
        }

        @Test void invalidIsNotSelect() {
            assertThat(SqlAnalyzer.isSelectStatement("GARBAGE")).isFalse();
        }
    }

    @Nested class RenderedDbtSql {
        @Test void renderedMartModel() {
            // After Jinja rendering, ref('stg_orders') becomes the actual table name.
            // This is what the SQL looks like when it hits the warehouse.
            var sql = """
                    WITH orders AS (SELECT * FROM analytics.stg_orders),
                    customers AS (SELECT * FROM analytics.stg_customers),
                    payments AS (SELECT order_id, SUM(amount) AS total
                                 FROM analytics.stg_payments GROUP BY 1)
                    SELECT o.order_id, c.first_name, COALESCE(p.total, 0) AS amount
                    FROM orders o
                    LEFT JOIN customers c ON o.customer_id = c.customer_id
                    LEFT JOIN payments p ON o.order_id = p.order_id
                    """;
            assertThat(SqlAnalyzer.isValid(sql)).isTrue();
            var tables = SqlAnalyzer.extractTableNames(sql);
            assertThat(tables).containsExactlyInAnyOrder(
                    "analytics.stg_orders",
                    "analytics.stg_customers",
                    "analytics.stg_payments");
        }

        @Test void snowflakeFullyQualified() {
            var sql = "SELECT * FROM my_db.my_schema.my_table";
            assertThat(SqlAnalyzer.isValid(sql)).isTrue();
            assertThat(SqlAnalyzer.extractTableNames(sql))
                    .containsExactly("my_db.my_schema.my_table");
        }
    }
}
