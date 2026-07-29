package com.nova.link;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for NovaLinkMain.
 */
class NovaLinkMainTest {
    
    @Test
    void mainClassShouldExist() {
        // Verify the main class can be loaded
        assertThat(NovaLinkMain.class).isNotNull();
    }
}
