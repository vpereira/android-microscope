# ScopeCam

A minimal Android camera app that turns a phone into a soldering microscope. Live preview with manual focus, zoom, and flashlight, streamed to a bench computer with scrcpy. Includes an experimental YOLO overlay for detecting electronic components.

## Why

Phone cameras make a cheap soldering microscope: good sensor, autofocus, built-in LED. The stock camera app gets in the way (no manual focus hold, auto modes fight you, no clean fullscreen). ScopeCam strips it down to the controls that matter for inspecting and soldering boards:

- Manual focus slider to lock focus on a board held still
- Zoom (pinch and slider)
- Torch toggle
- Fullscreen preview, screen stays on
- Optional on-device component detection

The phone screen is mirrored to the bench computer over USB or Wi-Fi with scrcpy, so you watch the board on a monitor while you work.

## Requirements

- Android 8.0 (API 26) or newer
- A camera with FULL Camera2 support for the manual focus slider (most main cameras on mid-range and flagship phones)
- Android SDK with build-tools 35, platform android-35
- JDK 17 or newer

## Build

```
./gradlew :app:assembleDebug
```

Output APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

Set the SDK path in `local.properties` if the build cannot find it:

```
sdk.dir=/path/to/Android/sdk
```

## Install

Standard:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Xiaomi/MIUI blocks adb install unless "Install via USB" is enabled (needs a Mi account). Workaround without an account:

```
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/ScopeCam.apk
```

Then on the phone open Files, tap the APK in Downloads, allow install from that source, install.

## Usage

- Open the app, grant the camera permission
- Mount the phone in a holder above the board
- Torch toggle for lighting
- Auto focus on, tap the preview to focus a spot
- Auto focus off to use the manual focus slider, then fine-tune by moving the phone
- Zoom with pinch or the slider

### Mirror to a bench computer

Install scrcpy and adb on the computer, enable USB debugging on the phone, connect, then:

```
scrcpy --no-control --max-size 1600
```

Wireless, so the cable can be unplugged:

```
adb tcpip 5555
adb connect PHONE_IP:5555
scrcpy
```

## YOLO component detection (experimental)

The app can overlay bounding boxes for electronic components using a bundled TensorFlow Lite model. Toggle it with the YOLO button. Off by default.

The bundled model detects 13 classes: Resistor, Capacitor, Electrolytic Capacitor, IC, Inductor, Diode, LED, Transistor, Oscillator, Connector, Switch/Button, Other, Marker.

Limitations:

- The model was trained on full-resolution board tiles, not magnified single-component views. It works best framing a board region with several components, not one part filling the screen.
- Accuracy is modest (about 0.43 mAP50). Expect misses and misclassifications.
- Inference on older phones is slow (not real time). Use it on a still frame, not while moving.
- Turn YOLO off for a smooth preview, on when you want IDs.

Model files live in `app/src/main/assets`:

- `model.tflite` input shape [1, 3, 640, 640], NCHW, float32, normalized by 255
- `labels.txt` one class name per line

### Using your own model

Replace `model.tflite` and `labels.txt`, then rebuild. The decoder in `Detector.kt` expects a YOLOv8-style output tensor `[1, 4 + numClasses, numAnchors]`. If your export uses NHWC input or an end-to-end decoded output, adjust the preprocessing and `bestBox` parsing in `Detector.kt`.

## Project layout

- `app/src/main/java/com/scopecam/MainActivity.kt` camera, controls, use case binding
- `app/src/main/java/com/scopecam/Detector.kt` tflite load, preprocess, decode, NMS
- `app/src/main/java/com/scopecam/OverlayView.kt` bounding box drawing
- `app/src/main/java/com/scopecam/BoundingBox.kt` detection data class
- `app/src/main/res/layout/activity_main.xml` preview, overlay, control panel
- `app/src/main/assets` model and labels

## Credit

The bundled detection model is exported from the PCB component dataset and weights at https://github.com/ParsaVictor/pcb-component-detection-yolo26 (MIT, dataset CC BY 4.0).
