package com.homewatch.facerecognition.service;

import com.homewatch.facerecognition.config.AppProperties;
import com.homewatch.facerecognition.model.FaceMatchResult;
import com.homewatch.facerecognition.model.KnownPerson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS Rekognition integration layer.
 *
 * ─── API Call Budget (Free Tier) ─────────────────────────────────────────────
 *   • IndexFaces           : called once per reference image on startup only
 *   • SearchFacesByImage   : called per detected face per frame (guarded by
 *                            CooldownService and maxFacesPerFrame cap)
 *   • ListFaces            : called once on startup to detect already-indexed faces
 *   Free allowance: 5,000 face-analysis operations/month + 1 GB face vector storage
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Design decisions:
 *   1. SearchFacesByImage is preferred over CompareFaces because it searches the
 *      entire collection in one API call rather than one-to-one comparisons.
 *   2. The faceId→name lookup map is kept in-memory — the collection stores only
 *      face vectors; names are Rekognition ExternalImageId values we set during
 *      IndexFaces, so we can reconstruct names directly from the API response.
 *   3. DetectFaces is intentionally NOT used — SearchFacesByImage already detects
 *      and searches in one round-trip, halving API call count.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RekognitionService {

    private final RekognitionClient rekognitionClient;
    private final AppProperties appProperties;

    @Value("${aws.rekognition.collection-id}")
    private String collectionId;

    @Value("${aws.rekognition.confidence-threshold}")
    private float confidenceThreshold;

    /**
     * In-memory registry: faceId → KnownPerson.
     * Populated during startup indexing; used for fast local lookups.
     */
    private final Map<String, KnownPerson> faceRegistry = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Collection Management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ensures the Rekognition Collection exists.
     * Creates it if absent; no-ops if already present.
     * Called once on application startup.
     *
     * @return true if a new collection was created
     */
    public boolean ensureCollectionExists() {
        try {
            // Check if collection already exists (avoids ResourceAlreadyExistsException)
            ListCollectionsResponse existing = rekognitionClient.listCollections(
                    ListCollectionsRequest.builder().build());

            if (existing.collectionIds().contains(collectionId)) {
                log.info("Rekognition Collection '{}' already exists.", collectionId);
                return false;
            }

            CreateCollectionResponse response = rekognitionClient.createCollection(
                    CreateCollectionRequest.builder()
                            .collectionId(collectionId)
                            .build());

            log.info("Created Rekognition Collection '{}' — Status: {}",
                    collectionId, response.statusCode());
            return true;

        } catch (RekognitionException e) {
            log.error("Failed to ensure Rekognition Collection exists: {}", e.getMessage());
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Face Indexing (Startup)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Indexes a single reference image into the Rekognition Collection.
     *
     * The ExternalImageId is set to the person's name, allowing us to recover
     * the name from SearchFacesByImage results without a separate database.
     *
     * ─── Cost ────────────────────────────────────────────────────────────────
     * Each IndexFaces call costs 1 unit from the 5,000/month free quota.
     * Called only on startup — not per frame — so cost is negligible.
     * ────────────────────────────────────────────────────────────────────────
     *
     * @param imagePath path to the reference JPEG/PNG image
     * @param personName the person's name (used as ExternalImageId)
     * @return the indexed KnownPerson, or empty if no face was detected
     */
    public Optional<KnownPerson> indexFace(Path imagePath, String personName) {
        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);

            IndexFacesResponse response = rekognitionClient.indexFaces(
                    IndexFacesRequest.builder()
                            .collectionId(collectionId)
                            .externalImageId(sanitiseExternalId(personName))
                            .detectionAttributes(Attribute.DEFAULT)
                            // Index only the highest-quality face to avoid duplicates
                            .maxFaces(1)
                            .qualityFilter(QualityFilter.AUTO)
                            .image(Image.builder()
                                    .bytes(SdkBytes.fromByteArray(imageBytes))
                                    .build())
                            .build());

            if (response.faceRecords().isEmpty()) {
                log.warn("No face detected in reference image '{}' — skipping.", imagePath.getFileName());
                return Optional.empty();
            }

            FaceRecord record = response.faceRecords().get(0);
            String faceId = record.face().faceId();

            KnownPerson person = KnownPerson.builder()
                    .name(personName)
                    .faceId(faceId)
                    .imagePath(imagePath.toString())
                    .indexedAt(Instant.now())
                    .build();

            faceRegistry.put(faceId, person);
            log.info("Indexed '{}' → faceId={}", personName, faceId);
            return Optional.of(person);

        } catch (IOException e) {
            log.error("Cannot read reference image '{}': {}", imagePath, e.getMessage());
            return Optional.empty();
        } catch (RekognitionException e) {
            log.error("IndexFaces failed for '{}': {}", personName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Loads already-indexed faces from the Rekognition Collection into the
     * local registry. Called on startup when reindexOnStartup = false.
     */
    public void loadExistingFacesIntoRegistry() {
        try {
            String paginationToken = null;
            int total = 0;

            do {
                ListFacesRequest.Builder reqBuilder = ListFacesRequest.builder()
                        .collectionId(collectionId)
                        .maxResults(100); // max allowed per page

                if (paginationToken != null) {
                    reqBuilder.nextToken(paginationToken);
                }

                ListFacesResponse response = rekognitionClient.listFaces(reqBuilder.build());

                for (Face face : response.faces()) {
                    // ExternalImageId holds the person name we set during IndexFaces
                    String personName = desanitiseExternalId(face.externalImageId());
                    KnownPerson person = KnownPerson.builder()
                            .name(personName)
                            .faceId(face.faceId())
                            .imagePath("(loaded from collection)")
                            .indexedAt(Instant.now())
                            .build();
                    faceRegistry.put(face.faceId(), person);
                    total++;
                }

                paginationToken = response.nextToken();
            } while (paginationToken != null);

            log.info("Loaded {} face(s) from Rekognition Collection '{}'.", total, collectionId);

        } catch (RekognitionException e) {
            log.error("Failed to load existing faces from collection: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame Analysis
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Searches for known faces in a single video frame image byte array.
     *
     * ─── Cost Optimisation ───────────────────────────────────────────────────
     * SearchFacesByImage counts as ONE API call regardless of how many faces
     * are in the collection. This is dramatically cheaper than CompareFaces
     * (which would be O(n) calls — one per known person).
     *
     * The CooldownService caller-side filter MUST be applied before calling
     * this method to prevent duplicate API calls for the same person.
     * ────────────────────────────────────────────────────────────────────────
     *
     * @param frameBytes   JPEG-encoded byte array of the extracted video frame
     * @param frameNumber  frame index (for logging)
     * @param maxFaces     maximum number of matched faces to return
     * @return list of matches meeting the confidence threshold
     */
    public List<FaceMatchResult> searchFacesInFrame(byte[] frameBytes, long frameNumber, int maxFaces) {
        try {
            SearchFacesByImageResponse response = rekognitionClient.searchFacesByImage(
                    SearchFacesByImageRequest.builder()
                            .collectionId(collectionId)
                            .faceMatchThreshold(confidenceThreshold)
                            .maxFaces(maxFaces)
                            .qualityFilter(QualityFilter.AUTO) // skip blurry/dark frames for free
                            .image(Image.builder()
                                    .bytes(SdkBytes.fromByteArray(frameBytes))
                                    .build())
                            .build());

            if (response.faceMatches().isEmpty()) {
                log.debug("Frame {}: no matching faces found.", frameNumber);
                return Collections.emptyList();
            }

            List<FaceMatchResult> results = new ArrayList<>();

            for (FaceMatch match : response.faceMatches()) {
                String faceId = match.face().faceId();
                float similarity = match.similarity();

                // Resolve name: prefer local registry, fall back to ExternalImageId
                String personName = resolveName(faceId, match.face().externalImageId());

                FaceMatchResult result = FaceMatchResult.builder()
                        .personName(personName)
                        .confidence(similarity)
                        .detectedAt(Instant.now())
                        .frameNumber(frameNumber)
                        .faceId(faceId)
                        .build();

                results.add(result);
                log.debug("Frame {}: matched '{}' with {:.1f}% confidence.",
                        frameNumber, personName, similarity);
            }

            return results;

        } catch (InvalidParameterException e) {
            // Thrown when the frame contains no detectable face — normal, not an error
            log.debug("Frame {}: no face detected by Rekognition ({})", frameNumber, e.getMessage());
            return Collections.emptyList();

        } catch (InvalidImageFormatException e) {
            log.warn("Frame {}: invalid image format — {}", frameNumber, e.getMessage());
            return Collections.emptyList();

        } catch (RekognitionException e) {
            log.error("SearchFacesByImage failed for frame {}: {}", frameNumber, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all currently registered known persons.
     * Useful for startup validation and logging.
     */
    public List<KnownPerson> getAllKnownPersons() {
        return new ArrayList<>(faceRegistry.values());
    }

    public int getRegisteredFaceCount() {
        return faceRegistry.size();
    }

    /**
     * Resolves a person's display name from the local registry or falls back
     * to deserialising the ExternalImageId stored in Rekognition.
     */
    private String resolveName(String faceId, String externalImageId) {
        KnownPerson known = faceRegistry.get(faceId);
        if (known != null) {
            return known.getName();
        }
        // Fall back: name was baked into ExternalImageId during indexing
        return externalImageId != null ? desanitiseExternalId(externalImageId) : "Unknown";
    }

    /**
     * AWS ExternalImageId only allows alphanumeric, dash, underscore, colon, dot.
     * Replaces spaces with underscores for safe storage.
     */
    private String sanitiseExternalId(String name) {
        return name.trim().replaceAll("\\s+", "_");
    }

    private String desanitiseExternalId(String id) {
        return id == null ? "Unknown" : id.replaceAll("_", " ");
    }
}
