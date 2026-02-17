# HomeWatch — Face Recognition from Local Video (AWS Rekognition + Spring Boot 3)
This Java-based Spring Boot application uses AWS Rekognition to monitor home entry points and enforce house rules for children. By processing local video feeds, the system identifies specific family members in real-time.
A production-grade Spring Boot 3 application that extracts frames from a local video file and identifies known individuals using **AWS Rekognition**, optimised for the AWS Free Tier.

---

## Project Structure

```
face-recognition/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/homewatch/facerecognition/
│   │   │   ├── FaceRecognitionApplication.java       # Entry point
│   │   │   ├── config/
│   │   │   │   ├── AppProperties.java                # Type-safe YAML binding
│   │   │   │   └── AwsConfig.java                    # RekognitionClient bean
│   │   │   ├── model/
│   │   │   │   ├── FaceMatchResult.java              # Immutable match result VO
│   │   │   │   └── KnownPerson.java                  # Reference library entry
│   │   │   ├── service/
│   │   │   │   ├── VideoProcessorService.java        # Frame extraction (JavaCV)
│   │   │   │   ├── RekognitionService.java           # AWS API integration layer
│   │   │   │   ├── KnownFacesIndexerService.java     # Startup face indexing
│   │   │   │   └── CooldownService.java              # Free-Tier throttle guard
│   │   │   └── runner/
│   │   │       └── ApplicationStartupRunner.java     # Boot lifecycle orchestrator
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/com/homewatch/facerecognition/
│           └── CooldownServiceTest.java
└── data/
    ├── videos/
    │   └── input.mp4           ← place your video here
    └── known-faces/
        ├── Shailesh.jpg        ← filename = person name
        ├── Alice Smith.jpg
        └── Bob Jones.png
```

---

## Architecture & Flow

```
  Application Start
        │
        ▼
 KnownFacesIndexerService
   ├─ ensureCollectionExists()         [AWS: CreateCollection — once ever]
   └─ indexFace() for each image       [AWS: IndexFaces — once per image]
        │
        ▼
 VideoProcessorService.processVideo()
   └─ FFmpegFrameGrabber (JavaCV)
        ├─ Extract 1 frame/second      [Local: no AWS cost]
        ├─ Convert frame → JPEG bytes  [Local: no AWS cost]
        └─ RekognitionService.searchFacesInFrame()
              │
              ├─ [AWS: SearchFacesByImage — 1 call per extracted frame]
              │
              └─ For each match ≥ 90% confidence:
                    CooldownService.isOnCooldown()?
                      NO  → log "NAME has arrived home"
                           → cooldownService.recordMatch()
                      YES → suppress (no API call wasted)
```

---

## AWS Free-Tier Optimisation Strategies

| Technique | How It Saves API Calls |
|-----------|------------------------|
| **1 fps frame extraction** | 30fps video → 97% fewer frames sent to AWS |
| **5-minute cooldown** | Suppresses re-detection of same person in-window |
| **SearchFacesByImage** | 1 call searches entire collection (vs N calls with CompareFaces) |
| **maxFaces cap** | Limits candidates returned per frame |
| **QualityFilter.AUTO** | AWS rejects blurry/dark faces before charging |
| **reindexOnStartup=false** | Skip IndexFaces calls after initial setup |
| **UrlConnectionHttpClient** | Lighter SDK client, faster startup |

**Free Tier Allowance:** 5,000 face operations/month + 1 GB stored face vectors

**Estimated Usage Example:**
- 1-hour video at 1 fps = 3,600 extracted frames
- 2 unique people, each detected once (cooldown suppresses rest) = **2 API calls** for arrivals
- Total: ~3,600 SearchFacesByImage calls consumed from the 5,000/month quota

---

## Prerequisites

- Java 17+
- Maven 3.8+
- AWS Account (Free Tier sufficient)
- AWS CLI configured or IAM credentials

---

## Setup

### 1. AWS Credentials

**Option A — Environment Variables (recommended):**
```bash
export AWS_ACCESS_KEY_ID=your_access_key
export AWS_SECRET_ACCESS_KEY=your_secret_key
export AWS_DEFAULT_REGION=us-east-1
```

**Option B — AWS CLI Profile:**
```bash
aws configure
```

**Option C — IAM Role** (EC2/ECS — most secure, no keys needed)

### 2. Reference Images

Place one clear face photo per person in `/data/known-faces/`.
The filename (without extension) becomes the person's identity:
```
/data/known-faces/
  Shailesh.jpg    → "Shailesh"
  Alice Smith.png → "Alice Smith"
```

### 3. Video File

Place your video at `/data/videos/input.mp4` (or configure `app.video.file-path`).

### 4. Configure `application.yml`

```yaml
aws:
  region: us-east-1
  rekognition:
    collection-id: homewatch-faces
    confidence-threshold: 90.0

app:
  video:
    file-path: /data/videos/input.mp4
    frame-interval-seconds: 1
  known-faces:
    folder-path: /data/known-faces
    reindex-on-startup: true   # set false after first run
  cooldown:
    duration-minutes: 5
```

---

## Running the Application

```bash
# Build
mvn clean package -DskipTests

# Run
java -jar target/face-recognition-1.0.0.jar
```

### Expected Console Output

```
PHASE 1 ▶ Initialising face library...
✓ Indexed: Shailesh → faceId=abc123...
✓ Indexed: Alice Smith → faceId=def456...
PHASE 1 ✓ 2 known person(s) ready

PHASE 2 ▶ Processing video...
╔══════════════════════════════════════════════════╗
║  Shailesh has arrived home  (confidence: 97.3%)
║  Frame: 120  |  Time: 2024-05-01T10:32:00Z
╚══════════════════════════════════════════════════╝

PROCESSING COMPLETE
  Duration      : 45231ms (45.2s)
  API Calls     : 3600
  Total Matches : 1
  Arrivals detected:
    ▶ Shailesh at frame 120 (97.3% confidence)
```

---

## Key Design Decisions

### Why `SearchFacesByImage` over `CompareFaces`?
`CompareFaces` is 1:1 — you'd need one API call per known person per frame.
`SearchFacesByImage` is 1:N — one call searches the entire collection regardless of size.
**Cost saving: N× reduction in API calls where N = number of known persons.**

### Why JavaCV over plain OpenCV?
JavaCV provides Maven-compatible JARs including native binaries. No system-level OpenCV installation required — works on any OS with the same JAR.

### Why `ExternalImageId` for names?
Rekognition's Collection stores face vectors, not metadata. By setting `ExternalImageId = personName` during `IndexFaces`, we can recover names directly from `SearchFacesByImage` responses without a separate database.

---

## IAM Permissions Required

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "rekognition:CreateCollection",
      "rekognition:ListCollections",
      "rekognition:IndexFaces",
      "rekognition:SearchFacesByImage",
      "rekognition:ListFaces"
    ],
    "Resource": "arn:aws:rekognition:*:*:collection/homewatch-faces"
  }]
}
```

