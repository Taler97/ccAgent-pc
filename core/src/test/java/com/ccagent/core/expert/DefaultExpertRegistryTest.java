package com.ccagent.core.expert;

import com.ccagent.api.port.Expert;
import com.ccagent.exception.tool.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultExpertRegistryTest {

    @Mock
    Expert writer;

    @Mock
    Expert researcher;

    private DefaultExpertRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultExpertRegistry();
    }

    @Test
    void shouldRegisterAndGetExpert() {
        when(writer.getName()).thenReturn("writer");
        registry.register(writer);

        assertThat(registry.getExpert("writer")).isSameAs(writer);
    }

    @Test
    void shouldThrowWhenExpertNotFound() {
        assertThatThrownBy(() -> registry.getExpert("unknown"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void shouldListExpertsSorted() {
        when(researcher.getName()).thenReturn("researcher");
        when(writer.getName()).thenReturn("writer");
        registry.register(writer);
        registry.register(researcher);

        List<String> names = registry.listExperts();
        assertThat(names).containsExactly("researcher", "writer");
    }

    @Test
    void shouldIgnoreNullExpertAndNullName() {
        registry.register(null);
        registry.register(writer); // getName 未打桩返回 null

        assertThat(registry.listExperts()).isEmpty();
    }

    @Test
    void shouldOverwriteExistingExpertWithSameName() {
        when(writer.getName()).thenReturn("writer");
        Expert writer2 = org.mockito.Mockito.mock(Expert.class);
        when(writer2.getName()).thenReturn("writer");

        registry.register(writer);
        registry.register(writer2);

        assertThat(registry.getExpert("writer")).isSameAs(writer2);
    }
}
