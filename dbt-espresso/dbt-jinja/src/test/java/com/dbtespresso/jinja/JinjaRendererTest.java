package com.dbtespresso.jinja;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JinjaRendererTest {

    private final JinjaRenderer renderer = new JinjaRenderer();

    // ── ref() ─────────────────────────────────────────────────────────────────

    @Test
    void rendersRefToResolvedTable() {
        var ctx = RenderContext.builder()
                .ref("stg_orders", "raw.stg_orders")
                .build();
        String sql = "SELECT * FROM {{ ref('stg_orders') }}";
        assertThat(renderer.render(sql, ctx)).isEqualTo("SELECT * FROM raw.stg_orders");
    }

    @Test
    void rendersTwoArgRefUsingPackagePlusModelKey() {
        var ctx = RenderContext.builder()
                .ref("my_pkg", "stg_orders", "pkg_schema.stg_orders")
                .build();
        String sql = "SELECT * FROM {{ ref('my_pkg', 'stg_orders') }}";
        assertThat(renderer.render(sql, ctx)).isEqualTo("SELECT * FROM pkg_schema.stg_orders");
    }

    @Test
    void refFallsBackToModelNameWhenNotInMap() {
        var ctx = RenderContext.builder().build();
        String sql = "SELECT * FROM {{ ref('unknown_model') }}";
        assertThat(renderer.render(sql, ctx)).isEqualTo("SELECT * FROM unknown_model");
    }

    @Test
    void rendersMultipleRefsInOneSql() {
        var ctx = RenderContext.builder()
                .ref("stg_customers", "raw.stg_customers")
                .ref("stg_orders",   "raw.stg_orders")
                .build();
        String sql = "SELECT c.id, o.total FROM {{ ref('stg_customers') }} c "
                   + "JOIN {{ ref('stg_orders') }} o ON c.id = o.customer_id";
        assertThat(renderer.render(sql, ctx))
                .isEqualTo("SELECT c.id, o.total FROM raw.stg_customers c "
                          + "JOIN raw.stg_orders o ON c.id = o.customer_id");
    }

    // ── source() ──────────────────────────────────────────────────────────────

    @Test
    void rendersSourceToResolvedTable() {
        var ctx = RenderContext.builder()
                .source("jaffle_shop", "orders", "raw.orders")
                .build();
        String sql = "SELECT * FROM {{ source('jaffle_shop', 'orders') }}";
        assertThat(renderer.render(sql, ctx)).isEqualTo("SELECT * FROM raw.orders");
    }

    @Test
    void sourceFallsBackToSourceDotTableWhenNotInMap() {
        var ctx = RenderContext.builder().build();
        String sql = "SELECT * FROM {{ source('stripe', 'payments') }}";
        assertThat(renderer.render(sql, ctx)).isEqualTo("SELECT * FROM stripe.payments");
    }

    // ── config() ──────────────────────────────────────────────────────────────

    @Test
    void stripsConfigBlock() {
        var ctx = RenderContext.builder().build();
        String sql = "{{ config(materialized='table') }}\nSELECT 1 AS id";
        assertThat(renderer.render(sql, ctx)).isEqualTo("SELECT 1 AS id");
    }

    @Test
    void stripsConfigBlockWithMultipleArgs() {
        var ctx = RenderContext.builder().build();
        String sql = "{{ config(materialized='table', schema='analytics') }}\nSELECT 1";
        assertThat(renderer.render(sql, ctx)).isEqualTo("SELECT 1");
    }

    // ── is_incremental() ──────────────────────────────────────────────────────

    @Test
    void isIncrementalFalseSkipsBlock() {
        var ctx = RenderContext.builder().incremental(false).build();
        String sql = """
                SELECT * FROM base
                {% if is_incremental() %}
                WHERE updated_at > '2024-01-01'
                {% endif %}
                """;
        String result = renderer.render(sql, ctx);
        assertThat(result).doesNotContain("WHERE updated_at");
    }

    @Test
    void isIncrementalTrueIncludesBlock() {
        var ctx = RenderContext.builder().incremental(true).build();
        String sql = """
                SELECT * FROM base
                {% if is_incremental() %}
                WHERE updated_at > '2024-01-01'
                {% endif %}
                """;
        String result = renderer.render(sql, ctx);
        assertThat(result).contains("WHERE updated_at > '2024-01-01'");
    }

    // ── var() ─────────────────────────────────────────────────────────────────

    @Test
    void rendersVarInExpression() {
        var ctx = RenderContext.builder().var("target", "prod").build();
        String sql = "SELECT '{{ var(\"target\") }}' AS env";
        assertThat(renderer.render(sql, ctx)).isEqualTo("SELECT 'prod' AS env");
    }

    @Test
    void varUsesDefaultWhenNotDefined() {
        var ctx = RenderContext.builder().build();
        String sql = "SELECT {{ var('min_rows', 100) }} AS threshold";
        assertThat(renderer.render(sql, ctx)).isEqualTo("SELECT 100 AS threshold");
    }

    @Test
    void varWorksInJinja2IfTag() {
        var ctx = RenderContext.builder().var("target", "prod").build();
        String sql = """
                {% if var('target', 'dev') == 'prod' %}
                SELECT * FROM prod_table
                {% else %}
                SELECT * FROM dev_table
                {% endif %}
                """;
        assertThat(renderer.render(sql, ctx)).contains("prod_table");
        assertThat(renderer.render(sql, ctx)).doesNotContain("dev_table");
    }

    // ── full model rendering ───────────────────────────────────────────────────

    @Test
    void rendersFullOrdersModel() {
        var ctx = RenderContext.builder()
                .ref("stg_orders",   "analytics.stg_orders")
                .ref("stg_customers","analytics.stg_customers")
                .ref("stg_payments", "analytics.stg_payments")
                .build();

        String rawSql = """
                {{ config(materialized='table', schema='analytics') }}
                WITH orders AS (SELECT * FROM {{ ref('stg_orders') }}),
                customers AS (SELECT * FROM {{ ref('stg_customers') }}),
                payments AS (SELECT order_id, SUM(amount) AS total FROM {{ ref('stg_payments') }} GROUP BY 1)
                SELECT o.order_id, o.customer_id
                FROM orders o
                LEFT JOIN customers c ON o.customer_id = c.customer_id
                LEFT JOIN payments p ON o.order_id = p.order_id
                """;

        String result = renderer.render(rawSql, ctx);
        assertThat(result).doesNotContain("config(");
        assertThat(result).doesNotContain("ref(");
        assertThat(result).contains("analytics.stg_orders");
        assertThat(result).contains("analytics.stg_customers");
        assertThat(result).contains("analytics.stg_payments");
    }

    @Test
    void rendersStagingModelWithSource() {
        var ctx = RenderContext.builder()
                .source("jaffle_shop", "orders", "raw.jaffle_shop.orders")
                .build();

        String rawSql = """
                {{ config(materialized='view') }}
                SELECT id AS order_id, user_id AS customer_id, order_date, status
                FROM {{ source('jaffle_shop', 'orders') }}
                """;

        String result = renderer.render(rawSql, ctx);
        assertThat(result).doesNotContain("config(");
        assertThat(result).doesNotContain("source(");
        assertThat(result).contains("raw.jaffle_shop.orders");
    }
}
