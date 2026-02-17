package com.homewatch.facerecognition.service;

import com.homewatch.facerecognition.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cooldown tracker.
 *
 * ─── Free-Tier Optimization ───────────────────────────────────────────────────
 * AWS Rekognition Free Tier grants 5,000 SearchFacesByImage operations/month.
 * Without throttling, a 1-hour video at 1 fps = 3,600 API calls for a single
 * person who stays in frame the whole time.
 *
 * The cooldown ensures that once a person is matched, subsequent frames
 * containing their face are ignored for the configured window (default 5 min),
 * reducing calls by up to 99% for continuous video streams.
 *
 * Uses ConcurrentHashMap for thread-safe access during parallel frame processing.
 * ────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CooldownService {

    private final AppProperties appProperties;

    /**
     * Key   = person name (case-insensitive, lowercased on write)
     * Value = timestamp of the last successful match
     */
    private final ConcurrentHashMap<String, Instant> lastSeenMap = new ConcurrentHashMap<>();

    /**
     * Checks whether a person is currently within their cooldown window.
     *
     * @param personName the name to check
     * @return {@code true} if the person should be suppressed (cooldown active)
     */
    public boolean isOnCooldown(String personName) {
        String key = normalise(personName);
        Instant lastSeen = lastSeenMap.get(key);

        if (lastSeen == null) {
            return false; // never seen before
        }

        Duration elapsed = Duration.between(lastSeen, Instant.now());
        boolean onCooldown = elapsed.toMinutes() < appProperties.getCooldown().getDurationMinutes();

        if (onCooldown) {
            long remaining = appProperties.getCooldown().getDurationMinutes() - elapsed.toMinutes();
            log.debug("Cooldown active for '{}': {}m remaining", personName, remaining);
        }

        return onCooldown;
    }

    /**
     * Records a successful match for the given person, starting their cooldown.
     *
     * @param personName the name of the matched person
     */
    public void recordMatch(String personName) {
        String key = normalise(personName);
        Instant now = Instant.now();
        lastSeenMap.put(key, now);
        log.debug("Cooldown started for '{}' at {}", personName, now);
    }

    /**
     * Returns the number of people currently tracked in the cooldown map.
     * Useful for monitoring and debugging.
     */
    public int activeCooldownCount() {
        long count = lastSeenMap.entrySet().stream()
                .filter(e -> {
                    Duration elapsed = Duration.between(e.getValue(), Instant.now());
                    return elapsed.toMinutes() < appProperties.getCooldown().getDurationMinutes();
                })
                .count();
        return (int) count;
    }

    /**
     * Manually clears the cooldown for a person (useful for testing or admin ops).
     *
     * @param personName the name to clear
     */
    public void clearCooldown(String personName) {
        lastSeenMap.remove(normalise(personName));
        log.debug("Cooldown manually cleared for '{}'", personName);
    }

    /** Clears all cooldowns — call during tests or on application restart. */
    public void clearAll() {
        lastSeenMap.clear();
        log.info("All cooldown records cleared.");
    }

    private String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }
}
