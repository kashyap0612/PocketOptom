"""
Train a small eye-photo classifier and export FP32 / FP16 / full-integer INT8 TFLite models
for the PocketOptom app.

Dataset layout (any number of classes; include one called "normal"):
    data/train/<class_name>/*.jpg
    data/val/<class_name>/*.jpg
Use EXTERNAL eye photos (what a phone camera sees), not fundus/retina scans.

Run (Colab GPU recommended):
    pip install -r requirements.txt
    python train_eye_model.py --data data --out ../app/src/main/assets/models

Writes to --out:
    eye_fp32.tflite, eye_fp16.tflite, eye_int8.tflite, eye_labels.txt, model_config.json
and prints a host-side table (size, accuracy, latency) for the README / deck.
The real numbers that matter come from the app's on-device Benchmark screen.
"""
import argparse
import json
import pathlib
import tempfile
import time

import numpy as np
import tensorflow as tf

IMG = 224
AUTOTUNE = tf.data.AUTOTUNE


def load_datasets(data_dir: pathlib.Path, batch: int):
    train = tf.keras.utils.image_dataset_from_directory(
        data_dir / "train", image_size=(IMG, IMG), batch_size=batch, label_mode="int",
        shuffle=True, seed=42)
    val = tf.keras.utils.image_dataset_from_directory(
        data_dir / "val", image_size=(IMG, IMG), batch_size=batch, label_mode="int",
        shuffle=False)
    return train, val


def build_models(num_classes: int):
    """Returns (training model with augmentation, clean export model sharing the same weights).

    Input is RAW RGB pixels 0..255. Normalisation lives inside the model, so the Android
    side needs no maths and the INT8 model can take uint8 pixels directly.
    """
    base = tf.keras.applications.MobileNetV2(input_shape=(IMG, IMG, 3), include_top=False,
                                             weights="imagenet")
    base.trainable = False
    rescale = tf.keras.layers.Rescaling(1.0 / 127.5, offset=-1.0)   # MobileNetV2 expects [-1, 1]
    pool = tf.keras.layers.GlobalAveragePooling2D()
    head = tf.keras.layers.Dense(num_classes, activation="softmax")
    augment = tf.keras.Sequential([
        tf.keras.layers.RandomFlip("horizontal"),
        tf.keras.layers.RandomRotation(0.05),
        tf.keras.layers.RandomZoom(0.1),
        tf.keras.layers.RandomContrast(0.15),
        tf.keras.layers.RandomBrightness(0.15, value_range=(0, 255)),
    ])

    inp = tf.keras.Input((IMG, IMG, 3))
    x = augment(inp)
    x = rescale(x)
    x = base(x, training=False)
    x = pool(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    train_model = tf.keras.Model(inp, head(x))

    e_inp = tf.keras.Input((IMG, IMG, 3))
    e = head(pool(base(rescale(e_inp), training=False)))
    export_model = tf.keras.Model(e_inp, e)
    return train_model, export_model, base


def convert(export_model, train_ds, out: pathlib.Path):
    saved = pathlib.Path(tempfile.mkdtemp()) / "saved_model"
    export_model.export(str(saved))

    def converter():
        return tf.lite.TFLiteConverter.from_saved_model(str(saved))

    # FP32
    (out / "eye_fp32.tflite").write_bytes(converter().convert())

    # FP16 weights
    c = converter()
    c.optimizations = [tf.lite.Optimize.DEFAULT]
    c.target_spec.supported_types = [tf.float16]
    (out / "eye_fp16.tflite").write_bytes(c.convert())

    # Full-integer INT8 (weights AND activations), calibrated on real training images.
    # Unlike dynamic-range INT8 this runs integer kernels end to end → fast on ARM NEON / NNAPI.
    def representative():
        for images, _ in train_ds.unbatch().batch(1).take(200):
            yield [tf.cast(images, tf.float32)]

    c = converter()
    c.optimizations = [tf.lite.Optimize.DEFAULT]
    c.representative_dataset = representative
    c.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    c.inference_input_type = tf.uint8
    c.inference_output_type = tf.uint8
    (out / "eye_int8.tflite").write_bytes(c.convert())


def evaluate_tflite(path: pathlib.Path, val_ds):
    interp = tf.lite.Interpreter(model_path=str(path), num_threads=4)
    interp.allocate_tensors()
    inp, outp = interp.get_input_details()[0], interp.get_output_details()[0]
    correct = total = 0
    times = []
    for images, labels in val_ds.unbatch().batch(1):
        x = images.numpy().astype(np.float32)
        if inp["dtype"] != np.float32:
            scale, zero = inp["quantization"]
            info = np.iinfo(inp["dtype"])
            x = np.clip(np.round(x / scale + zero), info.min, info.max).astype(inp["dtype"])
        interp.set_tensor(inp["index"], x)
        t0 = time.perf_counter()
        interp.invoke()
        times.append((time.perf_counter() - t0) * 1000)
        y = interp.get_tensor(outp["index"])[0]
        correct += int(np.argmax(y) == int(labels.numpy()[0]))
        total += 1
    return {
        "size_mb": round(path.stat().st_size / 1e6, 3),
        "accuracy": round(correct / max(total, 1), 4),
        "median_ms_host": round(float(np.median(times)), 2),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data")
    ap.add_argument("--out", default="../app/src/main/assets/models")
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--fine_tune_epochs", type=int, default=4)
    ap.add_argument("--batch", type=int, default=32)
    args = ap.parse_args()

    data, out = pathlib.Path(args.data), pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    train_ds, val_ds = load_datasets(data, args.batch)
    classes = train_ds.class_names
    print("Classes:", classes)
    train_ds, val_ds = train_ds.prefetch(AUTOTUNE), val_ds.prefetch(AUTOTUNE)

    model, export_model, base = build_models(len(classes))

    # Stage 1: train only the new head.
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3),
                  loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    model.fit(train_ds, validation_data=val_ds, epochs=args.epochs)

    # Stage 2: unfreeze the top of MobileNetV2 and fine-tune gently.
    base.trainable = True
    for layer in base.layers[:-30]:
        layer.trainable = False
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-5),
                  loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    model.fit(train_ds, validation_data=val_ds, epochs=args.fine_tune_epochs)

    convert(export_model, train_ds, out)
    (out / "eye_labels.txt").write_text("\n".join(classes) + "\n")
    (out / "model_config.json").write_text(json.dumps(
        {"mean": [0, 0, 0], "std": [1, 1, 1], "note": "raw RGB 0-255; normalisation is inside the model"},
        indent=2))

    results = {v: evaluate_tflite(out / f"eye_{v}.tflite", val_ds) for v in ("fp32", "fp16", "int8")}
    (pathlib.Path(__file__).parent / "benchmark_host.json").write_text(json.dumps(results, indent=2))
    print("\nvariant  size(MB)  val_acc  median_ms(host)")
    for v, r in results.items():
        print(f"{v:7s}  {r['size_mb']:8.2f}  {r['accuracy']:7.3f}  {r['median_ms_host']:8.2f}")


if __name__ == "__main__":
    main()
