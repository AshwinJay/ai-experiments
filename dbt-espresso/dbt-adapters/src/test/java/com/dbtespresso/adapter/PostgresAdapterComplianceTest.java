package com.dbtespresso.adapter;

import com.dbtespresso.qa.AdapterComplianceSuite;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
class PostgresAdapterComplianceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void compliance() {
        var adapter = new PostgresAdapter(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var results = AdapterComplianceSuite.runAll(adapter, "public");
        assertThat(results.failures())
                .withFailMessage(() -> "Compliance failures: " + results.failures())
                .isEmpty();
    }
}
