# Robot Cockpit 0.2 — Android test prototype

Contains map, simulated joystick, sensor cards, route editor, settings screen, draft JSON protocol, and GitHub Actions workflow to build a debug APK.

## Build automatically
Follow `GITHUB_BUILD_GUIDE_CS.md`.

## Important limitations
This is a UI prototype. Sensor values and robot movement are simulated. Android Bluetooth/Wi-Fi transport, real ESP32 commands, real GPS/IMU/encoder data, and Xiaomi LiDAR parsing are not implemented. Do not connect the UI to motor hardware expecting it to stop a real robot.
