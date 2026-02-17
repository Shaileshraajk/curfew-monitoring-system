package com.homewatch.facerecognition.service;

import com.homewatch.facerecognition.config.AppProperties;
import com.homewatch.facerecognition.model.KnownPerson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the reference face library — the folder of known person images.
 *
 * ─── Filename Convention ──────────────────────────────────────────────────────
 * Each image filename represents a person's identity:
 *   Shailesh.jpg      → "Shailesh"
 *   John Doe.png      → "John Doe"
 *   alice_jones.jpeg  → "alice_jones"
 *
 * Supported formats: JPEG (.jpg, .jpeg), PNG (.png)
 *
 * ─── Free-Tier Optimisation ───────────────────────────────────────────────────
 * When reindexOnStartup=false, images are NOT re-sent to AWS; instead, the app
 * loads existing face vectors from the collection via ListFaces.
 * This avoids consuming IndexFaces quota on every application restart.
 * ────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnownFacesIndexerService {

    private final AppProperties appProperties;
    private final RekognitionService rekognitionService;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");

    /**
     * Main entry point called by the startup ApplicationRunner.
     *
     * Workflow:
     *  1. Ensure the Rekognition Collection exists.
     *  2. If reindexOnStartup = true → scan local folder and index all images.
     *  3. If reindexOnStartup = false → load existing face vectors from AWS.
     *
     * @return list of successfully indexed/loaded KnownPerson entries
     */
    public List<KnownPerson> initialise() {
        // Step 1: Create collection if it doesn't exist
        rekognitionService.ensureCollectionExists();

        if (appProperties.getKnownFaces().isReindexOnStartup()) {
            log.info("reindexOnStartup=true → indexing reference images from: {}",
                    appProperties.getKnownFaces().getFolderPath());
            return indexAllReferenceImages();
        } else {
            log.info("reindexOnStartup=false → loading existing faces from Rekognition Collection.");
            rekognitionService.loadExistingFacesIntoRegistry();
            List<KnownPerson> loaded = rekognitionService.getAllKnownPersons();
            log.info("Loaded {} known person(s) from existing collection.", loaded.size());
            return loaded;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scans the known-faces folder, extracts names from filenames, and indexes
     * each image into the Rekognition Collection.
     */
    private List<KnownPerson> indexAllReferenceImages() {
        Path folderPath = Path.of(appProperties.getKnownFaces().getFolderPath());

        if (!Files.isDirectory(folderPath)) {
            log.error("Known faces folder not found: {}", folderPath);
            throw new IllegalStateException(
                    "Known faces directory does not exist: " + folderPath);
        }

        List<Path> imageFiles = listImageFiles(folderPath);

        if (imageFiles.isEmpty()) {
            log.warn("No supported images (jpg/jpeg/png) found in: {}", folderPath);
            return List.of();
        }

        log.info("Found {} reference image(s) to index.", imageFiles.size());

        List<KnownPerson> indexed = new ArrayList<>();

        for (Path imagePath : imageFiles) {
            String personName = extractPersonName(imagePath);
            log.info("Indexing '{}' from file '{}'...", personName, imagePath.getFileName());

            Optional<KnownPerson> result = rekognitionService.indexFace(imagePath, personName);
            result.ifPresentOrElse(
                    person -> {
                        indexed.add(person);
                        log.info("✓ Indexed: {} → faceId={}", person.getName(), person.getFaceId());
                    },
                    () -> log.warn("✗ Failed to index: {} (no face detected or API error)", personName)
            );
        }

        log.info("Indexing complete: {}/{} images successfully indexed.",
                indexed.size(), imageFiles.size());

        return indexed;
    }

    /**
     * Lists all supported image files in the given directory (non-recursive).
     */
    private List<Path> listImageFiles(Path folder) {
        try (Stream<Path> stream = Files.list(folder)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isSupportedImage(p.getFileName().toString()))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Cannot list files in directory '{}': {}", folder, e.getMessage());
            return List.of();
        }
    }

    /**
     * Extracts the person's name from the image filename.
     * "Shailesh Sharma.jpg" → "Shailesh Sharma"
     */
    private String extractPersonName(Path imagePath) {
        String filename = imagePath.getFileName().toString();
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
    }

    private boolean isSupportedImage(String filename) {
        String lower = filename.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
