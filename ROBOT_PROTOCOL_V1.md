# Robot Cockpit protocol v1 (draft)

JSON messages over a future BLE or Wi-Fi transport.

Drive: {"t":"drive","id":3,"l":0.50,"r":0.45} — normalized motor values -1..1.
Stop: {"t":"cmd","id":2,"cmd":"stop"}
Enable: {"t":"cmd","id":1,"cmd":"start"}
Home: {"t":"nav","id":5,"cmd":"home"}
Run route: {"t":"route","id":6,"cmd":"run","name":"garage"}
Telemetry: {"t":"tele","ms":123456,"x":1.246,"y":3.872,"yaw":73.4,"vl":0.42,"vr":0.40,"bat":58.7,"cur":3.2}
IMU: {"t":"imu","yaw":73.4,"pitch":0.2,"roll":-0.1,"gx":0.01,"gy":0.02,"gz":0.04}
Encoder: {"t":"enc","l":1248,"r":1261,"vl":0.42,"vr":0.40}
GPS: {"t":"gps","lat":49.123456,"lon":18.123456,"fix":1,"hdop":0.9}
LiDAR point: {"t":"lidar","a":0,"d":2.35} (angle degrees, distance metres).

Safety requirements for real hardware:
- ESP32-side watchdog, suggested 300 ms; missing commands must stop motors.
- STOP overrides route execution.
- START/enable must be separate from drive commands.
- This protocol is a draft and is not connected to the Android app yet.
