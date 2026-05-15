# Sensor-Assisted Tracking Roadmap

## Current State

- CameraX sends analysis frames into `FaceAnalysis`.
- OpenCV YuNet is the primary face detector; MediaPipe/BlazeFace is fallback only when YuNet cannot initialize.
- `CameraAnalysisController` owns CameraX analysis setup and detector lifecycle.
- `RangingSessionCoordinator` owns BLE role setup, UWB ranging, and the current UWB/IMU telemetry fusion path.

## Timestamp Discussion Summary

Long-term multi-sensor alignment should use one shared monotonic timeline. Each sample should carry a measurement timestamp, receive timestamp, and uncertainty. Camera frame timestamps should anchor visual output, while CNN results should remain tied to the frame timestamp they were computed from, not the later completion time.

That full time-alignment problem is intentionally deferred. The first tracking version only uses local phone sensors and latest UWB state to reduce detector load for a single tracked person.

## V1 Scope

- Track one person/face only.
- Run CNN at most once every 200 ms.
- Delay video display slightly so the bbox matches the displayed bitmap.
- Store a small display frame buffer and at most one copied CNN snapshot.
- Predict face bbox between CNN results using:
  - local camera phone gyroscope,
  - camera intrinsic/FoV based ray projection.
  - smoothed UWB distance as approximate person depth,
  - short-window linear acceleration as depth-scaled translation.
  - sparse KLT ROI tracking inside the previous face region.
- Defer sender IMU over BLE, multi-person UWB association, and cross-device clock alignment.

## V1 Frame Pipeline

```text
CameraX frame
    |
    |-- convert to display bitmap
    |-- close ImageProxy quickly
    |-- add bitmap to delayed display buffer
    |
    |-- if 200 ms elapsed and no CNN is running:
            copy one bitmap snapshot
            run CNN on worker thread
            queue CNN result by original frame timestamp
    |
    |-- render delayed buffered frames and apply bbox when tracker state is available
```

If CNN is still processing, the app skips the next CNN request instead of buffering more CNN snapshots. Display frames are held briefly so the blur bbox is computed for the same timestamp as the bitmap being shown. If no valid CNN/tracked bbox exists, the delayed frame is displayed without a face patch.

## Implemented Tracking Modules

- `CameraImuMotionBuffer`
  - stores recent local gyroscope angular deltas,
  - stores recent local linear-acceleration displacement deltas with aggressive damping and caps,
  - converts angular motion into a camera-ray projection transform for a requested camera-frame interval,
  - projects the tracked face center through the camera intrinsic model instead of applying one constant pixel shift to the whole frame,
  - converts lateral/down phone displacement into pixels only when the tracker has a usable depth estimate.

- `TrackingSignalStore`
  - stores latest raw UWB sample for tracking,
  - stores latest camera roll angle used when projecting UWB AoA into image coordinates.

- `FacePredictionTracker`
  - stores the latest CNN face bbox,
  - predicts bbox movement frame-to-frame,
  - uses local gyro ray projection for bbox center motion,
  - smooths latest UWB range as depth and uses it to scale accelerometer translation,
  - does not use UWB AoA/range as a direct image-position correction,
  - uses the ROI tracker result when enough feature points are tracked,
  - marks each output as `CNN`, `TRACKED`, or `NONE`.

- `FaceRoiTracker`
  - converts the displayed frame to grayscale,
  - seeds sparse OpenCV features inside the current face bbox,
  - tracks those features between frames with KLT optical flow,
  - returns a median ROI shift and confidence point count,
  - falls back to sensor prediction when the visual track is weak.

## Current Tracking Method

The tracker now treats the face bbox center as a camera ray:

```text
pixel center -> K^-1 camera ray -> gyro rotation -> K projection -> predicted pixel center
```

This is still a 2D bbox tracker, but the IMU correction is no longer a flat `dx/dy` value shared across the whole image. UWB-based bbox pulling was removed from this path because it was not reliable without face-to-UWB association.

The active prediction is split into two terms:

```text
rotation:
pixel center -> K^-1 camera ray -> gyro rotation -> K projection -> predicted pixel center

translation:
pixel shift ~= -focal_length_px * phone_translation_m / smoothed_uwb_depth_m

visual ROI correction:
previous face feature points -> KLT sparse flow -> median image shift
```

The translation term is intentionally conservative. Accelerometer displacement is double-integrated, so the app caps per-sample displacement, caps per-frame pixel translation, and damps velocity quickly. CNN results remain the periodic visual reset.

The ROI tracker is the primary correction for lateral phone/object motion between CNN results. It only tracks points selected in and around the face bbox, so it avoids full-frame dense optical flow. Gyro/depth prediction still acts as the fallback when ROI point tracking is not confident enough.

## Deferred Work

- Stream sender IMU values over BLE.
- Estimate face depth from face size or YuNet landmarks and compare it against UWB range.
- Add UWB depth only after the correct face-to-UWB association is reliable in multi-person scenes.
- Extend ROI tracking from single-face to multi-track after single-face behavior is validated.
- Add remote clock synchronization.
- Use a proper bounded timestamp buffer for multiple asynchronous sensor sources.
- Associate multiple UWB senders with multiple faces.
- Replace heuristic tracking with a formal filter after measurements are reliable.
