package com.dbtespresso.adapter;

import com.dbtespresso.qa.AdapterComplianceSuite;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers
class PostgresAdapterComplianceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestFactory
    Stream<DynamicTest> compliance() {
        var adapter = new PostgresAdapter(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var results = AdapterComplianceSuite.runAll(adapter, "public");
        return results.results().stream()
                .map(r -> DynamicTest.dynamicTest(r.testName(), () ->
                        assertThat(r.passed())
                                .withFailMessage(r.message())
                                .isTrue()));
    }
}
