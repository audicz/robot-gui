# MAROS Mobile Cockpit V3

Nová Android aplikace napsaná od nuly. Starý Robot Cockpit 0.x ani původní Mobile Cockpit V1 se nepoužívají jako základ UI.

## Cíl
Přenést funkční panely z VESC Cockpit displeje JC3248W535 (480×320) do telefonu a rozšířit je o mobilní SLAM, 2D mapu, plánování tras a diagnostiku výkonu telefonu.

## Panely
MAIN, DRIVE, VESC 1/2, BMS + články, TEMPS, ENERGY, HISTORY, SENSORS, GPS, TRACK, HORIZON, G-METER, GYRO, LINKS, PAIRING, SETTINGS, VESC CFG, PROFILES, CONTROL, BLE DATA, LIDAR, SLAM, ROUTE, RADAR a ROBOT007.

## Ověřené BLE profily ze současného firmware
- VESC NUS service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`, RX `6e400002...`, TX notify `6e400003...`.
- JBD/Xiaoxiang: `FF00` / notify `FF01` / write `FF02` (alternativa FFE0/FFE2/FFE1).
- Sensor Hub: service `7e1c0001-5b0e-4a6f-9c7a-0d6a5b3f2e10`, IMU `...0002`, GPS `...0003`, command `...0004`.
- Xiaomi LDS Bridge: service `a71d0001-5b0e-4a6f-9c7a-0d6a5b3f2e10`, notify `a71d0002-5b0e-4a6f-9c7a-0d6a5b3f2e10`. D3 frames: 20 bytes, 23 sectors, 16 range bins per sector (last sector 8); range unit 5 cm.

## Bezpečnostní stav
- VESC telemetrie je read-only; aplikace neposílá proudové ani konfigurační povely.
- BMS je read-only; MOSFET a ochranné zápisy jsou vypnuté.
- Robot007 joystick je zatím monitorovací / UI vrstva. Žádné motorové povely se neodesílají, dokud nebude ověřeno potvrzování, watchdog, timeout a STOP na cílovém ESP32.
- Neplatná a stará data se zobrazují jako stale/offline, nikdy se nenahrazují simulovanými hodnotami.

## SLAM
Mobil přijímá 360° LDS skeny přímo přes BLE. První mobilní engine používá lokální scan-to-map korelaci a occupancy grid; je určený pro měření výkonu a ladění. Bez enkodérů/IMU zatím nejde o plnohodnotný globální SLAM s robustním loop closure. Kvalitu lokalizace a stáří skenu zobrazuje diagnostika.

## Build
Z kořene repozitáře:
`gradle -p MAROS-MOBILE-COCKPIT-V3 assembleDebug`

GitHub Actions workflow vytvoří debug APK jako artifact po úspěšném sestavení.
