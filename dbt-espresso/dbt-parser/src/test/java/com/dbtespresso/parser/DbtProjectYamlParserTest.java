package com.dbtespresso.parser;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DbtProjectYamlParserTest {

    private static final String SAMPLE = "/sample_project/";

    // ==================== dbt_project.yml ====================

    @Nested
    class ProjectConfigTest {

        ProjectConfig config;

        @BeforeEach
        void load() throws Exception {
            try (var in = getClass().getResourceAsStream(SAMPLE + "dbt_project.yml")) {
                config = DbtProjectYamlParser.parseProject(in);
            }
        }

        @Test
        void parsesName() {
            assertThat(config.name()).isEqualTo("jaffle_shop");
        }

        @Test
        void parsesVersion() {
            assertThat(config.version()).isEqualTo("1.0.0");
        }

        @Test
        void parsesProfile() {
            assertThat(config.profile()).isEqualTo("jaffle_shop");
        }

        @Test
        void parsesModelPaths() {
            assertThat(config.modelPaths()).containsExactly("models");
        }

        @Test
        void parsesSeedAndTestPaths() {
            assertThat(config.seedPaths()).containsExactly("seeds");
            assertThat(config.testPaths()).containsExactly("tests");
            assertThat(config.snapshotPaths()).containsExactly("snapshots");
        }

        @Test
        void parsesModelsBlock() {
            assertThat(config.models()).containsKey("jaffle_shop");
        }

        @Test
        void parsesVars() {
            assertThat(config.vars()).containsKey("payment_method_vals");
            assertThat(config.vars()).containsKey("start_date");
            assertThat(config.vars().get("start_date")).isEqualTo("2018-01-01");
        }

        @Test
        void resolvesMartsMaterialization() {
            assertThat(config.defaultMaterialization("marts")).isEqualTo("table");
        }

        @Test
        void resolvesStagingMaterialization() {
            assertThat(config.defaultMaterialization("staging")).isEqualTo("view");
        }

        @Test
        void defaultsToViewForUnknownSubdir() {
            assertThat(config.defaultMaterialization("unknown")).isEqualTo("view");
        }
    }

    // ==================== profiles.yml ====================

    @Nested
    class ProfileConfigTest {

        List<ProfileConfig> profiles;

        @BeforeEach
        void load() throws Exception {
            try (var in = getClass().getResourceAsStream(SAMPLE + "profiles.yml")) {
                profiles = DbtProjectYamlParser.parseProfiles(in);
            }
        }

        @Test
        void parsesOneProfile() {
            assertThat(profiles).hasSize(1);
        }

        @Test
        void parsesProfileName() {
            assertThat(profiles.getFirst().profileName()).isEqualTo("jaffle_shop");
        }

        @Test
        void parsesDefaultTarget() {
            assertThat(profiles.getFirst().defaultTarget()).isEqualTo("dev");
        }

        @Test
        void parsesTwoOutputs() {
            assertThat(profiles.getFirst().outputs()).containsKeys("dev", "prod");
        }

        @Test
        void parsesDevOutput() {
            ProfileConfig.OutputConfig dev = profiles.getFirst().activeOutput();
            assertThat(dev.type()).isEqualTo("postgres");
            assertThat(dev.host()).isEqualTo("localhost");
            assertThat(dev.port()).isEqualTo(5432);
            assertThat(dev.dbname()).isEqualTo("analytics");
            assertThat(dev.schema()).isEqualTo("dbt_dev");
            assertThat(dev.threads()).isEqualTo(4);
        }

        @Test
        void parsesProdOutput() {
            ProfileConfig.OutputConfig prod = profiles.getFirst().outputs().get("prod");
            assertThat(prod.host()).isEqualTo("prod-db.example.com");
            assertThat(prod.threads()).isEqualTo(8);
            assertThat(prod.schema()).isEqualTo("dbt");
        }
    }
}
