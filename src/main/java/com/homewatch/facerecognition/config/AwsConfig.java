package com.homewatch.facerecognition.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;

/**
 * AWS SDK v2 client configuration.
 *
 * Credential resolution priority (best-practice for Free-Tier / local dev):
 *   1. Explicit key/secret in application.yml  (dev only, never commit secrets)
 *   2. Environment variables AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY
 *   3. ~/.aws/credentials (AWS CLI profile)
 *   4. IAM Role attached to EC2 / ECS / Lambda (production)
 *
 * The UrlConnectionHttpClient is used instead of the default Apache HTTP client
 * because it has zero additional dependencies, reducing JAR size and startup time.
 */
@Slf4j
@Configuration
public class AwsConfig {

    @Value("${aws.region}")
    private String region;

    @Value("${aws.credentials.access-key:}")
    private String accessKey;

    @Value("${aws.credentials.secret-key:}")
    private String secretKey;

    @Bean
    public RekognitionClient rekognitionClient() {
        var builder = RekognitionClient.builder()
                .region(Region.of(region))
                // Lightweight HTTP client — no Netty/Apache dependency needed
                .httpClient(UrlConnectionHttpClient.builder().build());

        if (accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank()) {
            log.warn("Using static AWS credentials from config. " +
                     "Prefer IAM Role or environment variables in production.");
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            // Falls back through env vars → ~/.aws/credentials → EC2 role
            log.info("Using DefaultCredentialsProvider for AWS authentication.");
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
