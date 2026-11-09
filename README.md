# PocketOptom

**Offline eye screening on any Android phone.** Built for the ZEISS India Hackathon *Beyond Code 2026*
(theme: No-Device Vision Intelligence & Accessible Clinical UX).

Most small and rural optical stores in India have no eye-testing equipment and patchy internet.
PocketOptom turns a budget Android phone into a guided screening station. Everything runs on the
device — no internet, no cloud, no customer data leaving the phone.

## What it does

| Step | How |
|---|---|
| **1. Pupillary distance** | MediaPipe Face Landmarker finds the irises. The human iris is ~11.7 mm wide, so it works as a built-in ruler — no reference card. 30 frames → median, corrected from near PD to distance PD. |
| **2. Vision test** | Tumbling-E near-acuity test, one eye at a time. The camera measures face-to-screen distance live and resizes the letter so it always subtends the correct angle (logMAR standard). |
| **3. Eye health screen** | Crops each eye using the iris landmarks and runs **our own INT8-quantised CNN** on it. |
| **4. Report** | PD, acuity (Snellen + logMAR), screen result, explainable referral rules, lens/coating suggestions. Shareable as text. |
| **Benchmark** | Runs the eye model as FP32 / FP16 / INT8 on CPU / NNAPI / GPU **on the phone itself**: size, median & p95 latency, cosine similarity and top-1 agreement vs FP32. |

## Run it

1. Open this folder in **Android Studio** (Ladybug or newer). It uses its bundled JDK 17/21 — Gradle 8.9 won't run on JDK 26.
2. First sync downloads Gradle + libraries; the first build downloads `face_landmarker.task` (3.7 MB) into `app/src/main/assets/`.
3. Plug in an Android 8.0+ phone with USB debugging on → **Run**.

Steps 1, 2 and 4 work immediately. Step 3 and the benchmark need the eye model ↓

## Add the eye model (your quantisation part)

```bash
cd ml
pip install -r requirements.txt          # or use Google Colab with a GPU
# put images in ml/data/train/<class>/ and ml/data/val/<class>/ — include a class named "normal"
python train_eye_model.py --data data --out ../app/src/main/assets/models
```

Use **external eye photos** (what a phone camera sees, e.g. a Kaggle "cataract image dataset" with
normal vs cataract eyes) — *not* fundus/retina scans, which a phone can't take.

The script fine-tunes MobileNetV2 and writes `eye_fp32.tflite`, `eye_fp16.tflite`,
`eye_int8.tflite` (full-integer, calibrated on 200 training images), `eye_labels.txt` and
`model_config.json`. Rebuild the app and the models appear.

**Using `quantisation_tool` instead:** copy its FP32/FP16/INT8 outputs here as
`eye_fp32/fp16/int8.tflite`, add `eye_labels.txt`, and set `model_config.json` to ImageNet
normalisation: `{"mean":[123.675,116.28,103.53],"std":[58.395,57.12,57.375]}`.
NCHW (PyTorch-layout) inputs are detected automatically.

Optional: put a few real eye photos in `app/src/main/assets/samples/` so the benchmark's
accuracy comparison uses real images instead of random noise.

## Code map — read in this order

| # | File | Learn |
|---|---|---|
| 1 | `EyeOptics.java` | Pure Java maths: iris ruler, near→far PD, letter size from logMAR |
| 2 | `Session.java` | Static fields shared between screens |
| 3 | `MainActivity.java` + `activity_main.xml` | Activities, layouts, `findViewById`, click listeners, Intents, runtime permissions |
| 4 | `vision/FaceTracker.java` | CameraX, background threads, callbacks, MediaPipe |
| 5 | `PdActivity.java` | Implementing an interface (`FaceTracker.Listener`), collecting samples, median |
| 6 | `ui/TumblingEView.java` | Custom View: `onDraw`, `Canvas`, `invalidate()` |
| 7 | `AcuityActivity.java` | A small state machine (the staircase test) |
| 8 | `ml/TfliteClassifier.java` | TFLite Interpreter, delegates, quantised inputs/outputs, `ByteBuffer` |
| 9 | `ml/Benchmark.java` + `BenchmarkActivity.java` | Timing, percentiles, ExecutorService |
| 10 | `EyeScreenActivity.java`, `Recommender.java`, `ReportActivity.java` | Putting it together, share Intent |

## Honest limitations

- Screening aid, not a diagnosis or prescription.
- Iris-based PD assumes an 11.7 mm iris (real range ~±0.5 mm → roughly ±2–3 mm PD); fine for
  screening, an optometrist should confirm for high-power lenses.
- The acuity test measures **near** vision at ~40 cm. Short-sightedness needs a distance test.
- Distance estimation uses the phone's reported lens data; a few phones report it inaccurately.

## Tech stack

Java · Android SDK (XML layouts) · CameraX · MediaPipe Face Landmarker (iris) · TensorFlow Lite
(CPU / NNAPI / GPU delegates) · Python + TensorFlow/Keras for training and post-training quantisation.
