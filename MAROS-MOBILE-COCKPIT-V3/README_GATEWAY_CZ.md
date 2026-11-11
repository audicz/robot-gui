# MAROS Mobile Cockpit – Display Gateway integration

The Android phone does not run ESP-NOW. The existing VESC Cockpit ESP32-S3 display remains the ESP-NOW node and forwards received frames to the phone over local Wi-Fi UDP.

## Current integration protocol

UDP display port: 4210
UDP phone source/bind port: 4211
Envelope: M7 | version:u8=1 | type:u8 | length:u16 LE | sequence:u16 LE | payload

- 0x01 HELLO: Android registers its address with the display.
- 0x81 HEARTBEAT: display confirms gateway availability.
- 0x80 FRAME: the payload is one unchanged ESP-NOW frame.
- 0x11 STOP: safe stop request handled by the display's existing Robot007 stop path.
- 0x20 BRIDGE_CMD: one-byte LiDAR Bridge D7 command.

The app decodes:
- D9: four fragments, 360 × uint16 millimetre distances.
- D1: Robot007 chassis telemetry.
- D5: Sensor Hub IMU payload.
- D6: Sensor Hub GPS payload.

D2, D4, DB and DC are forwarded unchanged and counted, but still need their dedicated Android-side typed decoders. The current Robot007 UI exposes telemetry and STOP only; motor drive commands remain locked.

## Setup

1. Flash the patched V24.10.18 cockpit-display.yaml and mobile_gateway.h from the companion firmware patch archive.
2. Put the Android phone and display on the same 2.4 GHz Wi-Fi network. For the display fallback AP, connect to Cockpit Fallback and use 192.168.4.1.
3. In the app, open MENU → PAIRING → VESC DISPLAY · ESP-NOW GATEWAY, enter the display IP, and connect.
4. Verify the gateway indicator changes to ONLINE and LiDAR packet counters increase.

## Safety / validation status

The UDP bridge is local-network, unauthenticated transport in this development build. Use only on a trusted Wi-Fi network. It intentionally exposes no normal motor-drive command. STOP is forwarded to the existing robot::stop() path. Before vehicle use, compile the modified ESPHome firmware with the exact ESPHome version used by the project, then test on a stationary robot with wheels raised: gateway reconnect, packet loss, STOP, Wi-Fi loss, and chassis watchdog.

This is an integration milestone, not a claim that every legacy VESC display page has already been ported to Android.