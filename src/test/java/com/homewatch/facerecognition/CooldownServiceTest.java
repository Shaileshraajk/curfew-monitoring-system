package com.homewatch.facerecognition;

import com.homewatch.facerecognition.config.AppProperties;
import com.homewatch.facerecognition.service.CooldownService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CooldownService.
 * No Spring context or AWS calls required — pure logic tests.
 */
class CooldownServiceTest {

    private CooldownService cooldownService;

    @BeforeEach
    void setUp() {
        // Build minimal AppProperties without loading application.yml
        AppProperties props = new AppProperties();
        AppProperties.Cooldown cooldown = new AppProperties.Cooldown();
        cooldown.setDurationMinutes(5);
        props.setCooldown(cooldown);

        cooldownService = new CooldownService(props);
    }

    @Test
    @DisplayName("New person should NOT be on cooldown")
    void newPersonIsNotOnCooldown() {
        assertThat(cooldownService.isOnCooldown("Alice")).isFalse();
    }

    @Test
    @DisplayName("Person should be on cooldown immediately after match")
    void personIsOnCooldownAfterMatch() {
        cooldownService.recordMatch("Bob");
        assertThat(cooldownService.isOnCooldown("Bob")).isTrue();
    }

    @Test
    @DisplayName("Cooldown should be case-insensitive")
    void cooldownIsCaseInsensitive() {
        cooldownService.recordMatch("Charlie");
        assertThat(cooldownService.isOnCooldown("charlie")).isTrue();
        assertThat(cooldownService.isOnCooldown("CHARLIE")).isTrue();
    }

    @Test
    @DisplayName("Clearing cooldown should allow re-detection")
    void clearCooldownAllowsRedetection() {
        cooldownService.recordMatch("Diana");
        cooldownService.clearCooldown("Diana");
        assertThat(cooldownService.isOnCooldown("Diana")).isFalse();
    }

    @Test
    @DisplayName("Active cooldown count should reflect current state")
    void activeCooldownCountIsAccurate() {
        assertThat(cooldownService.activeCooldownCount()).isZero();

        cooldownService.recordMatch("Eve");
        cooldownService.recordMatch("Frank");
        assertThat(cooldownService.activeCooldownCount()).isEqualTo(2);

        cooldownService.clearAll();
        assertThat(cooldownService.activeCooldownCount()).isZero();
    }
}
