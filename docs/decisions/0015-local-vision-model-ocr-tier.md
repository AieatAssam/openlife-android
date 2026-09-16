# Decision 0015 — Local vision-language models as an optional, grounded OCR tier

Date: 2026-09-16
Status: accepted by the owner in principle (this record); implementation is
plan phase P10, gated on the evaluation set and on the local runtime from P8-01.

## Question

Should OpenLife use a small local vision-language model (VLM) for OCR and
interpretation instead of, or in addition to, a classical OCR engine? Recent
small open models (Qwen3-VL 2B/4B, dots.ocr, PaddleOCR-VL, DeepSeek-OCR,
Liquid's LFM2-VL / LFM2.5-VL family) read screenshots, receipts and letters
well and can also interpret them.

## Decision

Yes, as an **optional second tier**, never as the baseline, under these rules:

1. **Baseline stays classical.** Tesseract (decision 0003) remains the engine
   every device gets: bundled, ~5 MB, works on the 2 GB RAM floor, produces
   real line boxes. A VLM tier is opt-in, off until the user imports a model.
2. **Grounding is mandatory (P3).** A VLM's text becomes a reviewable span only
   if it is tied to a real image region. Two admissible mechanisms:
   - the model emits per-line boxes itself (dots.ocr, PaddleOCR-VL with layout,
     Qwen3-VL grounding) and the boxes pass the evidence-IoU gate; or
   - **hybrid grounding**: Tesseract supplies line boxes, the VLM supplies
     text, and a deterministic alignment (line-by-line edit-distance
     matching with a threshold) attaches VLM text to Tesseract boxes.
     VLM lines that align to nothing are stored as *ungrounded* spans and
     cannot become facts. This is the default mechanism because it works for
     any model and keeps evidence honest.
   A VLM output with no boxes and no alignment is never shown as an
   established value.
3. **Interpretation stays C11.** Facts, events, purchases proposed by a model
   go through the C11 citation validator and the same confirmation flows. A
   VLM does not get a shortcut around C2/C3.
4. **Privacy boundary unchanged.** Inference is in-process through the
   llama.cpp runtime built from source in CI (decision 0013); no network, no
   download, no service. Model files are public artefacts stored unencrypted
   under `noBackupFilesDir/models/`; user images never leave the process.
5. **Explicit import with an allowlist.** The user imports a model file through
   the single-document picker; it is accepted only if its SHA-256 matches an
   allowlist entry in the repository. Allowlist entries require an
   OSI-approved weights licence (Apache-2.0, MIT, BSD). Models whose weights
   carry non-OSI terms (LFM Open License, MiniCPM model licence, Qwen
   research licence) are **not** allowlisted even if technically excellent;
   the ADR is revisited if their licences change. Licences and hashes are
   verified at implementation time, not copied from this record.
6. **Measured before enabled (design §14).** A candidate is allowlisted only
   after the P2-04 OCR evaluation set (and the receipts set) shows it beats
   Tesseract on CER at equal or better evidence-IoU, within the device budget:
   peak RSS ≤ 2.5 GB, p95 ≤ 20 s per image on the CI emulator and a mid-range
   arm64 device, and the app must remain usable during inference
   (cancellable, background-safe, no ANR).
7. **Honest UI.** Text from the VLM tier is labelled with the model id and
   version; confidence is never shown as a percentage; ungrounded lines are
   visibly separated (existing C1 rule).

## Candidate list to evaluate in P10-01 (verify licences and sizes then)

| Model | Size | Weights licence (as known 2026-09) | Boxes | Note |
| --- | --- | --- | --- | --- |
| Qwen3-VL-2B-Instruct | ~2B | Apache-2.0 | grounding prompts | strongest general reader; llama.cpp mtmd support |
| dots.ocr | 1.7B | MIT | yes (layout JSON) | OCR-specialised; boxes native |
| PaddleOCR-VL | 0.9B | Apache-2.0 | via layout stage | smallest; needs its layout model |
| DeepSeek-OCR | ~3B | MIT | partial | heavy vision encoder |
| LFM2-VL-1.6B / LFM2.5-VL | 1.6B | LFM Open License (non-OSI) | no | excluded until licence changes |
| MiniCPM-V 4.x | 4B+ | non-OSI for commercial | partial | excluded |

## Consequences

- New plan phase P10 (v1.2.0): candidate evaluation, hybrid-grounding engine,
  acceptance. Runtime step P8-01 is decoupled from C8–C10 so it can start
  after v1.0.0.
- No change to C1 provenance rows; a VLM engine is another `OcrEngine` with
  an `engineId` such as `vlm-qwen3vl-2b@<hash8>`.
- Threat model gains a row for imported model artefacts (integrity by hash;
  a malicious model can only produce bad text, never actions).
