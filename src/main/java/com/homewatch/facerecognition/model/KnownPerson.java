package com.homewatch.facerecognition.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Represents a person indexed into the AWS Rekognition Collection.
 * Created once per reference image during application startup.
 */
@Value
@Builder
public class KnownPerson {

    /** Display name, parsed from the image filename (without extension). */
    String name;

    /** AWS Rekognition Face ID assigned after successful IndexFaces call. */
    String faceId;

    /** Original reference image file path on the local filesystem. */
    String imagePath;

    /** Timestamp when this face was indexed (or re-indexed) into AWS. */
    Instant indexedAt;
}
