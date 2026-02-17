package com.homewatch.facerecognition.runner;

import com.homewatch.facerecognition.model.FaceMatchResult;
import com.homewatch.facerecognition.model.KnownPerson;
import com.homewatch.facerecognition.service.KnownFacesIndexerService;
import com.homewatch.facerecognition.service.VideoProcessorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Application lifecycle runner — executed once after the Spring context starts.
 *
 * Execution sequence:
 *   1. Index (or reload) the known faces reference library → Rekognition Collection
 *   2. Process the configured video file frame by frame
 *   3. Print a final summary report
 *
 * ApplicationRunner is preferred over CommandLineRunner because it receives
 * typed ApplicationArguments, making it easier to extend with CLI flags later.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationStartupRunner implements ApplicationRunner {

    private final KnownFacesIndexerService knownFacesIndexerService;
    private final VideoProcessorService videoProcessorService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("═══════════════════════════════════════════════════════");
        log.info("  HomeWatch Face Recognition — Starting               ");
        log.info("═══════════════════════════════════════════════════════");

        // ── Phase 1: Reference Library Setup ──────────────────────────────
        log.info("PHASE 1 ▶ Initialising face library...");
        List<KnownPerson> knownPersons = knownFacesIndexerService.initialise();

        if (knownPersons.isEmpty()) {
            log.warn("No known persons indexed. Video processing will produce no matches.");
            log.warn("Add images to the known-faces folder and restart with reindexOnStartup=true.");
            return;
        }

        log.info("PHASE 1 ✓ {} known person(s) ready:", knownPersons.size());
        knownPersons.forEach(p -> log.info("  • {} (faceId: {})", p.getName(), p.getFaceId()));

        // ── Phase 2: Video Processing ──────────────────────────────────────
        log.info("PHASE 2 ▶ Processing video...");
        long startTime = System.currentTimeMillis();
        List<FaceMatchResult> matches = videoProcessorService.processVideo();
        long elapsedMs = System.currentTimeMillis() - startTime;

        // ── Phase 3: Summary Report ────────────────────────────────────────
        log.info("═══════════════════════════════════════════════════════");
        log.info("  PROCESSING COMPLETE                                  ");
        log.info("═══════════════════════════════════════════════════════");
        log.info("  Duration      : {}ms ({:.1f}s)", elapsedMs, elapsedMs / 1000.0);
        log.info("  API Calls     : {}", videoProcessorService.getApiCallCount());
        log.info("  Total Matches : {}", matches.size());

        if (matches.isEmpty()) {
            log.info("  Result        : No known persons detected in the video.");
        } else {
            log.info("  Arrivals detected:");
            matches.forEach(m -> log.info("    ▶ {} at frame {} ({:.1f}% confidence)",
                    m.getPersonName(), m.getFrameNumber(), m.getConfidence()));
        }

        log.info("═══════════════════════════════════════════════════════");
    }
}
