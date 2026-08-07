package com.ccagent.adapter.memory.repository;

import com.ccagent.adapter.memory.schema.DatabaseInitializer;
import com.ccagent.api.model.memory.UserPreferences;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PreferencesRepository 集成测试（真实 H2 内存库）
 */
class PreferencesRepositoryTest {

    private PreferencesRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pref-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DataSource dataSource = ds;
        new DatabaseInitializer(dataSource).initialize();
        repository = new PreferencesRepository(dataSource);
    }

    @Test
    void shouldLoadDefaultPreferences() {
        UserPreferences prefs = repository.loadPreferences();
        assertThat(prefs.getTechStack()).isEqualTo("{}");
        assertThat(prefs.getCodeStyle()).isEqualTo("{}");
        assertThat(prefs.getConstraints()).isEqualTo("{}");
    }

    @Test
    void shouldSaveAndReadBackPreferences() {
        repository.savePreferences(new UserPreferences(
                "{\"java\":21}", "{\"style\":\"google\"}", "{\"rule\":\"no-lombok\"}"));

        UserPreferences prefs = repository.loadPreferences();
        assertThat(prefs.getTechStack()).isEqualTo("{\"java\":21}");
        assertThat(prefs.getCodeStyle()).isEqualTo("{\"style\":\"google\"}");
        assertThat(prefs.getConstraints()).isEqualTo("{\"rule\":\"no-lombok\"}");
    }

    @Test
    void shouldSaveNullFieldsAsEmptyObjects() {
        repository.savePreferences(new UserPreferences(null, null, null));

        UserPreferences prefs = repository.loadPreferences();
        assertThat(prefs.getTechStack()).isEqualTo("{}");
        assertThat(prefs.getCodeStyle()).isEqualTo("{}");
        assertThat(prefs.getConstraints()).isEqualTo("{}");
    }
}
