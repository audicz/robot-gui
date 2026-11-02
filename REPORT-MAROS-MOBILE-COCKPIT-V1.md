# MAROS MOBILE COCKPIT V1 — technický report

Datum auditu: 2026-11-02

## Vstupní zdroje
- Firmware: MAROS VESC Cockpit V24.7.2 NAV SETTINGS (ESPHome / ESP32-S3).
- Android projekt: MAROS-MOBILE-COCKPIT-V1.zip.
- Android package: cz.maros.mobile, versionName 1.0, versionCode 1, minSdk 26, targetSdk 35.

## Stav dodaného Android projektu
- ZIP je strukturálně validní (8 položek, bez chyby integrity archivu).
- Obsahuje Gradle projekt a jedinou hlavní aktivitu MainActivity.java.
- UI má dashboard, mapu, ovládání, správu BLE zařízení, VESC, BMS, radar a senzory.
- BLE scan/connect a část parsování jsou implementované v MainActivity.
- Motorové povely pro ESP32 jsou záměrně blokované; joystick a STOP jsou zatím pouze UI/náhled, nikoli skutečný bezpečnostní povel.
- SLAM mapa je zatím vizualizační placeholder; skutečné mapování/odometrie není v Androidu kompletně přenesena.
- Projekt nemá automatizované testy ani instrumentační testy.

## Porovnání s firmware V24.7.2
Ověřené podklady ve firmwaru:
- VESC: VESC packet framing, CRC16, COMM_GET_VALUES (ID 4) a Nordic UART Service. Android implementuje čtení paketů a periodické dotazování; fyzické spojení s konkrétními VESC adaptéry nebylo ověřeno.
- JBD BMS: rámce DD A5 pro čtení, DD 5A pro zápis; checksum a parser v jbd_proto.h. Android má základní BLE UUID FF00/FF01/FF02, ale kompatibilita s konkrétní BMS variantou není ověřena. Zápis MOSFETů je vypnutý.
- Sensor Hub: skutečný protokol v sh_proto.h používá 20B IMU a 18B GPS rámce, little-endian, vlastní binární layout. Android zdroj zatím uvádí vlastní GATT UUID a zobrazuje telemetrii, ale je nutné ověřit, že UUID i parsování odpovídají skutečnému BLE serveru.
- Xiaomi LDS bridge: UUID a fragmentace 23 × 20 B jsou popsány v LIDAR_BRIDGE_PROTOCOL.md. Android rozpozná službu, ale v aktuálním zdrojovém kódu zatím neprovádí úplné složení 360-bin skenu.
- HLK-LD2451: AE00/AE01/AE02 a init sekvence odpovídají HLK-BLE-PAIRING-CZ.md. Android posílá init sekvenci, ale aktuální implementace pouze počítá notifikace; plnohodnotný parser cílových dat není dokončen.

## Build / ověření
- V tomto běhovém prostředí není dostupný Gradle ani Android SDK.
- APK proto nebylo sestaveno.
- Projekt nebyl spuštěn na Android telefonu ani testován s fyzickými zařízeními.
- Nesmí být považován za hotovou nebo bezpečnostně ověřenou aplikaci.

## Kritické úkoly před APK pro reálný provoz
1. Zkompilovat projekt přes Android Gradle Plugin 8.7.3 / Gradle 8.9 a opravit všechny compiler chyby.
2. Ověřit BLE UUID, CCCD notifikace, MTU, pořadí zápisů a skutečné parsování pro každé zařízení.
3. Doplnit parser HLK a LDS včetně validace rámců a ztrát paketů.
4. Převést Sensor Hub parser přesně podle sh_proto.h.
5. Definovat ESP32-S3 robotický protokol: watchdog, deadman, STOP, limity proudu/rychlosti, enkodéry, CRC/sekvenční čísla. Motorové příkazy zůstanou vypnuté do fyzického ověření failsafe.
6. Doplnit automatizované testy parserů a testy odpojení BLE.

## Závěr
Jde o raný UI/protokolový prototyp, nikoli o hotovou aplikaci. Největší riziko je neověřená kompatibilita některých BLE služeb a neúplné parsování LDS/HLK/Sensor Hub. APK zatím neexistuje.
