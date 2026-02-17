package com.homewatch.facerecognition.service;

import com.homewatch.facerecognition.config.AppProperties;
import com.homewatch.facerecognition.model.FaceMatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core video processing pipeline.
 *
 * ─── Responsibilities ────────────────────────────────────────────────────────
 *  1. Opens the local video file with FFmpegFrameGrabber (via JavaCV).
 *  2. Extracts frames at a configurable interval (default 1 fps) to minimise
 *     the number of frames — and therefore AWS API calls — processed.
 *  3. Converts each raw Frame to a JPEG byte array suitable for Rekognition.
 *  4. Delegates to RekognitionService for face searching.
 *  5. Applies the cooldown filter (via CooldownService) before recording a
 *     "has arrived" event, preventing repeat alerts within the window.
 *
 * ─── Free-Tier Optimisation ──────────────────────────────────────────────────
 *  • frameIntervalSeconds  → skip frames; 1 fps on a 30 fps video = 97% fewer frames
 *  • maxFacesPerFrame      → cap the number of SearchFacesByImage calls per frame
 *  • CooldownService       → suppress already-matched persons for N minutes
 *  • JPEG compression (75%)→ smaller payload, faster upload to Rekognition
 * ────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessorService {

    private final AppProperties appProperties;
    private final RekognitionService rekognitionService;
    private final CooldownService cooldownService;

    /** Tracks total API calls made during this run — useful for cost auditing. */
    private final AtomicLong apiCallCounter = new AtomicLong(0);

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Entry point: process the configured video file end-to-end.
     *
     * @return aggregated list of all FaceMatchResults detected across all frames
     */
    public List<FaceMatchResult> processVideo() {
        Path videoPath = Path.of(appProperties.getVideo().getFilePath());
        log.info("Starting video processing: {}", videoPath);
        return processVideoFile(videoPath);
    }

    /**
     * Processes an arbitrary video file (useful for testing or multi-file scenarios).
     *
     * @param videoPath absolute path to the video file
     * @return all face matches found
     */
    public List<FaceMatchResult> processVideoFile(Path videoPath) {
        List<FaceMatchResult> allMatches = new ArrayList<>();
        int frameIntervalSec = appProperties.getVideo().getFrameIntervalSeconds();
        int maxFacesPerFrame = appProperties.getVideo().getMaxFacesPerFrame();

        log.info("Config → frameInterval={}s, maxFacesPerFrame={}, cooldown={}min",
                frameIntervalSec,
                maxFacesPerFrame,
                appProperties.getCooldown().getDurationMinutes());

        // ── Open video with FFmpegFrameGrabber ─────────────────────────────
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile());
             Java2DFrameConverter converter = new Java2DFrameConverter()) {

            grabber.start();

            double frameRate = grabber.getFrameRate();
            int totalFrames = grabber.getLengthInFrames();
            double videoDurationSec = totalFrames / frameRate;

            log.info("Video info → fps={:.2f}, totalFrames={}, duration={:.1f}s",
                    frameRate, totalFrames, videoDurationSec);

            // Calculate frame hop: how many raw frames to skip between extractions
            // e.g. 30fps video, 1s interval → hop = 30
            int frameHop = Math.max(1, (int) Math.round(frameRate * frameIntervalSec));

            log.info("Extracting every {} frame(s) (~1 per {}s) → ~{} API calls (before cooldown)",
                    frameHop, frameIntervalSec, (int) Math.ceil(videoDurationSec / frameIntervalSec));

            long currentFrameIndex = 0;
            long extractedCount = 0;

            // ── Main frame loop ──────────────────────────────────────────────
            Frame rawFrame;
            while ((rawFrame = grabber.grabImage()) != null) {
                currentFrameIndex++;

                // Skip frames that are not at the configured interval
                if (currentFrameIndex % frameHop != 0) {
                    continue;
                }

                extractedCount++;
                log.debug("Processing extracted frame #{} (video frame {})", extractedCount, currentFrameIndex);

                // Convert JavaCV Frame → JPEG bytes
                byte[] jpegBytes = convertFrameToJpeg(rawFrame, converter);
                if (jpegBytes == null || jpegBytes.length == 0) {
                    log.debug("Frame {}: conversion produced empty bytes — skipping.", currentFrameIndex);
                    continue;
                }

                // ── AWS Rekognition call ─────────────────────────────────────
                apiCallCounter.incrementAndGet();
                List<FaceMatchResult> frameMatches = rekognitionService
                        .searchFacesInFrame(jpegBytes, currentFrameIndex, maxFacesPerFrame);

                // ── Process each match with cooldown guard ────────────────────
                for (FaceMatchResult match : frameMatches) {
                    handleFaceMatch(match, allMatches);
                }
            }

            log.info("Video processing complete. Frames extracted: {}, API calls made: {}, Matches: {}",
                    extractedCount, apiCallCounter.get(), allMatches.size());

        } catch (FFmpegFrameGrabber.Exception e) {
            log.error("FFmpeg failed to open or read video '{}': {}", videoPath, e.getMessage(), e);
            throw new VideoProcessingException("Cannot open video file: " + videoPath, e);
        } catch (IOException e) {
            log.error("I/O error during frame conversion: {}", e.getMessage(), e);
            throw new VideoProcessingException("Frame conversion I/O error", e);
        }

        return allMatches;
    }

    /** Returns the total number of Rekognition API calls made in the current session. */
    public long getApiCallCount() {
        return apiCallCounter.get();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies the cooldown check and, on a new match, logs the arrival message
     * and records the match timestamp.
     *
     * @param match      the match result from Rekognition
     * @param allMatches accumulator list
     */
    private void handleFaceMatch(FaceMatchResult match, List<FaceMatchResult> allMatches) {
        String personName = match.getPersonName();

        if (cooldownService.isOnCooldown(personName)) {
            log.debug("Suppressed duplicate alert for '{}' (cooldown active)", personName);
            return;
        }

        // ── First detection within the cooldown window → fire the alert ──────
        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║  {} has arrived home  (confidence: {:.1f}%)", personName, match.getConfidence());
        log.info("║  Frame: {}  |  Time: {}",
                match.getFrameNumber(), match.getDetectedAt());
        log.info("╚══════════════════════════════════════════════════╝");

        // Start the cooldown timer for this person
        cooldownService.recordMatch(personName);
        allMatches.add(match);
    }

    /**
     * Converts a JavaCV {@link Frame} to a JPEG byte array.
     *
     * ─── Why JPEG not PNG? ────────────────────────────────────────────────────
     * JPEG at 75% quality is typically 5-10× smaller than PNG for photographic
     * content. Smaller payload = faster HTTP upload to Rekognition.
     * AWS Rekognition accepts JPEG, PNG — JPEG preferred for face photos.
     * ────────────────────────────────────────────────────────────────────────
     *
     * @param frame     the raw JavaCV frame
     * @param converter stateful converter (must be reused across calls, not recreated)
     * @return JPEG bytes, or null if the frame has no image data
     */
    private byte[] convertFrameToJpeg(Frame frame, Java2DFrameConverter converter) throws IOException {
        if (frame == null || frame.image == null) {
            return null;
        }

        BufferedImage bufferedImage = converter.getBufferedImage(frame);
        if (bufferedImage == null) {
            return null;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024)) {
            // ImageIO.write for JPEG — no external library needed
            boolean written = ImageIO.write(bufferedImage, "jpg", baos);
            if (!written) {
                log.warn("ImageIO could not write frame as JPEG.");
                return null;
            }
            return baos.toByteArray();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner Exception
    // ─────────────────────────────────────────────────────────────────────────

    /** Unchecked wrapper for video processing failures. */
    public static class VideoProcessingException extends RuntimeException {
        public VideoProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
