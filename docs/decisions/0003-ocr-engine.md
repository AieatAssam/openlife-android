# ADR-0003: Open-source, telemetry-free OCR engine

Date: 2026-09-24
Status: accepted for P2-03; the engine choice was agreed by the owner on
2026-09-16 (design v0.3). ML Kit's removal waits for P2-04's gate and the
owner's acknowledgement (P2-05).
Supersedes: the engine choice in decision 0002. Every C1 boundary 0002 set
is kept.

## Context

The bundled ML Kit artefact from decision 0002 ships a telemetry transport
stack. It is inert only because INTERNET is stripped, which violates P1 as
written. Its models are closed source, which violates P6 and blocks F-Droid.
The C1 `OcrEngine` seam exists so that an engine can be swapped without
touching provenance rows.

## Decision

**Engine**
- **Library.** `cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0`
  (Apache-2.0). Upstream is adaptech-cz/Tesseract4Android, tag 4.9.0, commit
  `15c534717b1cb58261b58d4e4c1200c7f81f668c`. It bundles:
  - Tesseract 5.5.1 (Apache-2.0);
  - Leptonica 1.85.0 (BSD-2-Clause style);
  - libjpeg v9f (IJG licence);
  - libpng 1.6.48 (libpng licence v2).
- **Settings.** Engine id `tesseract-eng-fast`. The model version recorded on
  each revision is `t4a-4.9.0+tessdata_fast-4.1.0-7d4322bd`. OCR uses the LSTM
  engine only and automatic page segmentation, and produces text lines with
  bounding boxes.

**Model**
- **The file.** tessdata_fast `eng.traineddata` is vendored at
  `vault/src/main/assets/tessdata/eng.traineddata`.
  - Upstream: https://github.com/tesseract-ocr/tessdata_fast/raw/4.1.0/eng.traineddata
  - Tag 4.1.0, tag object `a8ba5063ab8013372a20e300da0c97ee46b92b07`
  - Size: 4,113,088 bytes
  - SHA-256: `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`
  - Licence: Apache-2.0
- **Installation.** `TessdataInstaller` copies the file to
  `noBackupFilesDir/ocr/tessdata/`. It verifies the SHA-256 before use and
  refuses a mismatch. It never downloads anything.

**Where it comes from**
- **JitPack.** The library is published only on JitPack, not Maven Central.
  JitPack is added in `settings.gradle.kts` as an `exclusiveContent`
  repository, restricted to the group `cz.adaptech.tesseract4android`.
- **Pinning.** Every artefact is SHA-256-pinned in
  `gradle/verification-metadata.xml`. If JitPack rebuilt or replaced the AAR,
  the build would fail rather than accept it.
- **POM-only metadata.** JitPack's Gradle module file names a different group
  (`cz.adaptech`), so the repository uses the POM plus the artefact only
  (`metadataSources { mavenPom(); artifact() }`).

**Selection**
- Both engines are registered. Tesseract is selected by a compile-time
  constant in `OpenLifeApp` (R3), and ML Kit stays present but unselected
  until P2-05.

**Confidence (R6)**
- `OcrConfidencePolicy` stores no engine score for either engine until P2-04
  shows a monotonic relation between score buckets and CER. The UI never shows
  a percentage.

**Cancellation**
- Recognition runs on its own thread. `stop()` works through tesseract4android's
  progress monitor, which is attached only in the recognition loop.
  - Measured on dev36: a stop during recognition took effect within about
    0.5–1 s.
  - Layout analysis before the recognition loop cannot be interrupted. On a
    2400x3200 page that phase took about 30 s.
- Native state is freed, and the bitmap recycled, only after the native call
  returns. If the caller has already given up (P2-02's 5 s settle), the task
  frees them itself when the call returns.

**Build and packaging**
- **R8 keep rules.** They cover `com.googlecode.tesseract.android.**` and
  `com.googlecode.leptonica.android.**`. A release build without them still
  ran OCR in the release smoke, which suggests the library's own annotations
  keep what the JNI code needs. The rules are kept as defence in depth.
- **Smoke test.** The release smoke now runs OCR on the R8 build.
- **16 KB alignment.** The arm64-v8a and x86_64 libraries are 16 KB aligned
  (0x4000). `check-16kb-alignment.sh` passes on the release APK with 14 native
  libraries.

## Deviations and open owner decisions

- **R5: ABI set (armeabi-v7a).** The step asked to ship
  arm64-v8a, armeabi-v7a and x86_64. tesseract4android 4.9.0's armeabi-v7a
  libraries are 4 KB aligned (0x1000), as are ML Kit's. The project's 16 KB
  gate checks every library, so 32-bit ARM stays out; the shipped set remains
  arm64-v8a and x86_64, and x86 is not shipped. The owner can choose either:
  - scope the gate to 64-bit ABIs, which is where Android's 16 KB page
    requirement applies; or
  - build Tesseract for armeabi-v7a from source with `-Wl,-z,max-page-size=16384`.
    That is CI-only here, because no NDK is installed locally.
- **Bundled native licences.** The IJG and libpng licences are permissive but
  are not on the dependency policy's allowlist. The Gradle licence checker sees
  only the module licence, which is recorded as Apache-2.0 through
  `config/license-overrides.txt` and `config/allowed-licenses.json`. The owner
  should accept or reject IJG and libpng as policy labels.
- **R7: measurement fixtures.** P2-04 defines the evaluation fixtures and has
  not landed. Duration and peak PSS were measured on synthetic fixtures instead
  (`OcrEngineMeasurementTest`, opt-in), on dev36 API 36 locally. A run on the CI
  emulator is still a gap.

## Consequences

- **APK size.** The release APK grew from about 33 MB to 52.5 MB. Native
  libraries are about 21–22 MB per ABI, plus the 4.1 MB model.
- **Accuracy.** Tesseract's accuracy on photographed receipts may be lower
  than ML Kit's. P2-04's gate decides. The recorded fallback is RapidOCR ONNX
  models (Apache-2.0) through onnxruntime-android (MIT).
