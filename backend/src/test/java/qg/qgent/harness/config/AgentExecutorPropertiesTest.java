package qg.qgent.harness.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AgentExecutorPropertiesTest {
    @Test
    void defaultConfigurationIsValid() {
        assertDoesNotThrow(new AgentExecutorProperties()::validate);
    }

    @Test
    void invalidPoolConfigurationFailsFast() {
        AgentExecutorProperties properties = new AgentExecutorProperties();
        properties.setCoreSize(4);
        properties.setMaxSize(2);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
