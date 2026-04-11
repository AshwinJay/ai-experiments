package com.dbtespresso.jinja;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import static org.assertj.core.api.Assertions.*;

class RefExtractorTest {

    @Nested class SingleArgRef {
        @Test void singleQuoted() {
            var deps = RefExtractor.extract("SELECT * FROM {{ ref('stg_orders') }}");
            assertThat(deps).containsExactly(new Dependency.ModelRef("stg_orders"));
        }
        @Test void doubleQuoted() {
            var deps = RefExtractor.extract("SELECT * FROM {{ ref(\"stg_orders\") }}");
            assertThat(deps).containsExactly(new Dependency.ModelRef("stg_orders"));
        }
        @Test void whitespaceVariations() {
            var deps = RefExtractor.extract("{{  ref(  'stg_orders'  )  }}");
            assertThat(deps).hasSize(1);
        }
    }

    @Nested class TwoArgRef {
        @Test void extractsPackageAndModel() {
            var deps = RefExtractor.extract("{{ ref('my_pkg', 'shared_model') }}");
            assertThat(deps).contains(new Dependency.ModelRef("my_pkg", "shared_model"));
        }
        @Test void qualifiedName() {
            assertThat(new Dependency.ModelRef("pkg", "m").qualifiedName()).isEqualTo("pkg.m");
            assertThat(new Dependency.ModelRef("m").qualifiedName()).isEqualTo("m");
        }
    }

    @Nested class SourceRefs {
        @Test void extractsSource() {
            var deps = RefExtractor.extract("FROM {{ source('stripe', 'payments') }}");
            assertThat(deps).containsExactly(new Dependency.SourceRef("stripe", "payments"));
        }
    }

    @Nested class MultipleRefs {
        @Test void multiJoin() {
            var sql = """
                FROM {{ ref('stg_orders') }} o
                JOIN {{ ref('stg_customers') }} c ON o.cid = c.id
                JOIN {{ ref('stg_payments') }} p ON o.id = p.oid
                """;
            assertThat(RefExtractor.extractModelRefs(sql))
                    .extracting(Dependency.ModelRef::modelName)
                    .containsExactly("stg_orders", "stg_customers", "stg_payments");
        }

        @Test void deduplicates() {
            var sql = "{{ ref('a') }} UNION ALL {{ ref('a') }}";
            assertThat(RefExtractor.extract(sql)).hasSize(1);
        }

        @Test void mixedRefAndSource() {
            var sql = "{{ ref('stg_orders') }} JOIN {{ source('raw', 'orders') }}";
            assertThat(RefExtractor.extract(sql)).hasSize(2);
        }
    }

    @Nested class EdgeCases {
        @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"   ", "SELECT 1"})
        void emptyOrNoRefs(String sql) {
            assertThat(RefExtractor.extract(sql)).isEmpty();
        }

        @Test void ignoresJinjaComments() {
            var sql = "{# {{ ref('old') }} #}\n{{ ref('active') }}";
            assertThat(RefExtractor.extract(sql)).hasSize(1);
            assertThat(RefExtractor.extract(sql).getFirst())
                    .isEqualTo(new Dependency.ModelRef("active"));
        }

        @Test void handlesFilterSuffix() {
            var deps = RefExtractor.extract("{{ ref('stg_orders') | as_subquery }}");
            assertThat(deps).hasSize(1);
        }

        @Test void rejectsBlankName() {
            assertThatThrownBy(() -> new Dependency.ModelRef(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested class RealisticModels {
        @Test void typicalMartModel() {
            var sql = """
                {{ config(materialized='table', schema='analytics') }}
                WITH orders AS (SELECT * FROM {{ ref('stg_orders') }}),
                customers AS (SELECT * FROM {{ ref('stg_customers') }}),
                payments AS (SELECT order_id, SUM(amount) FROM {{ ref('stg_payments') }} GROUP BY 1)
                SELECT o.*, c.first_name, p.total
                FROM orders o LEFT JOIN customers c ON o.cid = c.id
                LEFT JOIN payments p ON o.id = p.oid
                """;
            assertThat(RefExtractor.extractModelRefs(sql))
                    .extracting(Dependency.ModelRef::modelName)
                    .containsExactly("stg_orders", "stg_customers", "stg_payments");
        }

        @Test void incrementalModel() {
            var sql = """
                {{ config(materialized='incremental', unique_key='event_id') }}
                SELECT * FROM {{ source('segment', 'tracks') }}
                {% if is_incremental() %}
                WHERE received_at > (SELECT MAX(received_at) FROM {{ this }})
                {% endif %}
                """;
            assertThat(RefExtractor.extract(sql)).hasSize(1);
            assertThat(RefExtractor.extract(sql).getFirst())
                    .isEqualTo(new Dependency.SourceRef("segment", "tracks"));
        }
    }
}
