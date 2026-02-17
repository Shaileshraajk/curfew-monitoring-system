package com.homewatch.facerecognition.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Immutable value object representing a successful face-match result
 * returned by AWS Rekognition SearchFacesByImage.
 */
@Value
@Builder
public class FaceMatchResult {

    /** Person's name derived from the reference image filename. */
    String personName;

    /** Rekognition similarity score (0–100). */
    float confidence;

    /** Wall-clock time when the match was detected. */
    Instant detectedAt;

    /** Frame number within the video at which the face was found. */
    long frameNumber;

    /** External face ID assigned by Rekognition during IndexFaces. */
    String faceId;

    @Override
    public String toString() {
        return String.format(
                "FaceMatchResult{person='%s', confidence=%.1f%%, frame=%d, at=%s}",
                personName, confidence, frameNumber, detectedAt);
    }
}
