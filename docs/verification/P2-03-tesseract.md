# P2-03 verification: open-source OCR engine (Tesseract)

Date: 2026-09-24. Branch: `plan/P2-03-tesseract`. Device: `dev36` (API 36).
API 29 evidence comes from CI on the PR. Decision record:
`docs/decisions/0003-ocr-engine.md`.

## RED (765eb6f)

| Test | Failure on the unchanged tree |
| --- | --- |
| `TessdataInstallerTest.refusesToUseTraineddataWhoseDigestDoesNotMatch` | "expected TessdataIntegrityException to be thrown, but nothing was thrown" |
| `TessdataInstallerTest.installsOnceAndIsIdempotent` | "the asset is copied once expected:<1> but was:<0>" |
| `TesseractOcrEngineTest.extractsSyntheticLatinTextWithLineRegionsInSourcePixels` | no spans |
| `TesseractOcrEngineTest.rotatedInputRegionsMapBack` | no spans |
| `TesseractOcrEngineTest.cancellationStopsRecognitionAndRecyclesAfterCompletion` | recognition never started |
| JVM `OcrEngineContractTest` (2 new) | the mapper returned nothing; a confidence value was stored |

## GREEN (ebe46a9), REFACTOR (029bb8e, 60314a7)

**First GREEN run: cancellation.** Cancellation took 5,013 ms, which is the
settle timeout. A probe on dev36 explained why:

| Case | Result |
| --- | --- |
| Full recognition of a 2400x3200, 50-line page | 67,739 ms |
| `stop()` 50 ms in (during layout analysis) | settled after 29,890 ms |
| `stop()` 500 ms in | settled after 959 ms |
| `stop()` 1,500 ms in | settled after 481 ms |

`stop()` takes effect only in the recognition loop, which is the only place
the progress monitor runs. The engine therefore now:

- emits `recognizing` from the first progress callback, and the test cancels
  after that event;
- frees native state and recycles the bitmap from the recognition thread once
  the native call returns, if the caller gave up first. A new test,
  `earlyCancellationFreesNativeStateOnceRecognitionReturns`, covers this.

**Other changes**

- **Detekt refactor.** `TessdataInstaller` was split into write-with-digest
  and move-into-place steps; the engine was given `openRecognizer`; the engine
  list moved out of `OpenLifeApp`.
- **Licence check.** The check failed because the JitPack POM declares no
  licence. It is resolved as for SQLCipher: an Apache-2.0 entry in
  `config/license-overrides.txt` and an explicit entry in
  `config/allowed-licenses.json`.
- **Release smoke.** It now runs OCR on the R8 release build and requires
  `Engine: tesseract-eng-fast`.

## Verification

| Command | Result |
| --- | --- |
| `./gradlew :vault:testDebugUnitTest` | pass (`OcrEngineContractTest` including the 2 new tests) |
| `TessdataInstallerTest`, `TesseractOcrEngineTest` (connected) | 2/2, 4/4 |
| `./gradlew :vault:connectedDebugAndroidTest` | 123 testcases, all pass (timing harness skipped by assumption) |
| `./gradlew :app:connectedDebugAndroidTest` | 96/96, with Tesseract as the default engine |
| `./gradlew detekt lint :app:test :vault:test assembleDebug assembleRelease` | BUILD SUCCESSFUL (includes `DependencyBoundaryTest` and `checkLicense`) |
| `bash scripts/check-16kb-alignment.sh` (release APK) | `16KB_ALIGNMENT_RESULT=pass`, `NATIVE_LIB_COUNT=14` |
| `bash scripts/release-smoke.sh` (R8 release, OCR included) | `RELEASE_SMOKE_RESULT=pass` |

**Mutation: remove the R8 keep rules.** The release smoke, including the OCR
step, still passed. The rules are not proven necessary for this build and are
kept as defence in depth (ADR-0003).

### APK size (R4)

| Item | Bytes |
| --- | --- |
| Release APK, unsigned | 52,467,909 (was about 33 MB) |
| arm64-v8a native (7 libraries) | 21,490,696 |
| x86_64 native (7 libraries) | 22,643,976 |
| `assets/tessdata/eng.traineddata` | 4,113,088 |

### Duration and peak PSS (R7, synthetic fixtures, dev36 API 36)

Measured with `OcrEngineMeasurementTest` (opt-in, `p203Measure=true`). Each
fixture ran three times after a warm-up.

| Engine | Fixture | ms (3 runs) | Peak PSS (KB) | Spans |
| --- | --- | --- | --- | --- |
| tesseract-eng-fast | receipt 900x360 | 773, 873, 655 | 96,959 | 2 |
| tesseract-eng-fast | screenshot 1080x2400 | 2721, 2435, 2994 | 131,347 | 20 |
| tesseract-eng-fast | dense 1200x1600 | 4247, 3637, 4919 | 102,544 | 40 |
| mlkit-latin | receipt 900x360 | 2415, 2305, 2448 | 92,409 | 2 |
| mlkit-latin | screenshot 1080x2400 | 7570, 7942, 7626 | 128,457 | 20 |
| mlkit-latin | dense 1200x1600 | 11019, 9123, 7698 | 139,968 | 40 |

## Owner decisions (2026-09-24) and gaps

- **ABI set:** no 32-bit ARM; the shipped set is arm64-v8a and x86_64.
- **JitPack:** approved as the source of tesseract4android.
- **Bundled native licences:** IJG and libpng-2.0 are permitted as listed
  exceptions (`docs/dependency-policy.md`).
- **Gap, R7:** measured on synthetic fixtures, not P2-04's, and not on the CI
  emulator.
- **Gap, physical devices:** no physical-device run.
