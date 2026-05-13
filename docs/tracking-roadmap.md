# Sensor-Assisted Tracking Roadmap

## Current State

- CameraX sends analysis frames into `FaceAnalysis`.
- OpenCV YuNet is the primary face detector; MediaPipe/BlazeFace is fallback only when YuNet cannot initialize.
- `CameraAnalysisController` owns CameraX analysis setup and detector lifecycle.
- `RangingSessionCoordinator` owns BLE role setup, UWB ranging, and the current UWB/IMU telemetry fusion path.
- Full-screen fail-closed blur is disabled while detector behavior is being debugged.

## Timestamp Discussion Summary

Long-term multi-sensor alignment should use one shared monotonic timeline. Each sample should carry a measurement timestamp, receive timestamp, and uncertainty. Camera frame timestamps should anchor visual output, while CNN results should remain tied to the frame timestamp they were computed from, not the later completion time.

That full time-alignment problem is intentionally deferred. The first tracking version only uses local phone sensors and latest UWB state to reduce detector load for a single tracked person.

## V1 Scope

- Blur all detected face patches, with lightweight propagation between CNN results.
- Run CNN at most once every 200 ms.
- Delay video display slightly so the bbox matches the displayed bitmap.
- Store a small display frame buffer and at most one copied CNN snapshot.
- Predict face bbox between CNN results using:
  - local camera phone gyroscope,
  - latest UWB range/AoA signal only when there is a single tracked face.
- Defer sender IMU over BLE, multiple UWB sender support, and cross-device clock alignment.

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
    |-- render only buffered frames whose timestamp has matching CNN/tracker state
```

If CNN is still processing, the app skips the next CNN request instead of buffering more CNN snapshots. Display frames are held briefly so the blur bbox is computed for the same timestamp as the bitmap being shown. If no valid CNN/tracked bbox exists, the frame is not displayed as raw video.

## Implemented Tracking Modules

- `CameraImuMotionBuffer`
  - stores recent local gyroscope angular deltas,
  - converts angular motion into pixel shift for a requested camera-frame interval.

- `TrackingSignalStore`
  - stores latest raw UWB sample for tracking,
  - stores latest camera roll angle used when projecting UWB AoA into image coordinates.

- `FacePredictionTracker`
  - stores the latest CNN face bbox,
  - predicts bbox movement frame-to-frame,
  - uses local IMU shift and weak UWB projection correction,
  - keeps stable face track IDs for detected faces,
  - buffers recent face and UWB projection trajectories,
  - scores the UWB signal against each face using a front-pocket image-plane prior,
  - marks each output as `CNN`, `TRACKED`, or `NONE`.

## Deferred Work

- Stream sender IMU values over BLE.
- Add remote clock synchronization.
- Use a proper bounded timestamp buffer for multiple asynchronous sensor sources.
- Extend the current single-UWB-to-face association to multiple UWB senders and opt-out identities.
- Replace heuristic tracking with a formal filter after measurements are reliable.
