package com.homewatch.facerecognition.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Type-safe binding for all 'app.*' properties in application.yml.
 * Using @ConfigurationProperties avoids scattered @Value annotations and
 * enables compile-time validation via the Spring configuration processor.
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @Valid
    @NotNull
    private Video video = new Video();

    @Valid
    @NotNull
    private KnownFaces knownFaces = new KnownFaces();

    @Valid
    @NotNull
    private Cooldown cooldown = new Cooldown();

    // ── Nested: video settings ────────────────────────────────────────────────
    @Data
    public static class Video {

        /** Absolute path to the video file to process. */
        @NotBlank
        private String filePath;

        /**
         * How many seconds to skip between extracted frames.
         * Higher value = fewer API calls = lower AWS cost.
         * Free-Tier budget: 5,000 SearchFacesByImage calls/month.
         */
        @Min(1)
        private int frameIntervalSeconds = 1;

        /** Upper bound on Rekognition calls per frame (guards burst spikes). */
        @Min(1)
        private int maxFacesPerFrame = 5;
    }

    // ── Nested: known-faces reference library ────────────────────────────────
    @Data
    public static class KnownFaces {

        /** Directory containing reference images. Filename = person's name. */
        @NotBlank
        private String folderPath;

        /**
         * When true, the startup runner will sync images to the Rekognition
         * Collection on every boot.  Set false once the collection is stable
         * to avoid unnecessary IndexFaces calls.
         */
        private boolean reindexOnStartup = true;
    }

    // ── Nested: cooldown settings ─────────────────────────────────────────────
    @Data
    public static class Cooldown {

        /**
         * Minutes to suppress repeated alerts for the same person.
         * Core Free-Tier optimisation — prevents flooding Rekognition with
         * SearchFacesByImage calls for a face already confirmed in the window.
         */
        @Min(1)
        private int durationMinutes = 5;
    }
}
