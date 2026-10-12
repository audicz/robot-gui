# Návod: automaticky vytvořit APK přes GitHub Actions

## 1. Nahraj projekt na GitHub
1. Přihlas se na https://github.com/ a vytvoř nový repozitář, např. `RobotCockpitTest`.
2. Rozbal stažený ZIP v počítači.
3. Nahraj do repozitáře **obsah rozbalené složky**. V kořeni musí být `settings.gradle.kts`, `build.gradle.kts`, složka `app`, složka `protocol` a `.github`.
4. Na GitHubu klikni `Add file` → `Upload files`, přetáhni soubory a složky a potvrď `Commit changes`. Pokud webové rozhraní nepřenese skrytou složku `.github`, použij GitHub Desktop.

## 2. Spusť build
1. V repozitáři otevři záložku **Actions**.
2. Vyber workflow **Build Android APK**.
3. Klikni **Run workflow** → **Run workflow**. Build se spustí i automaticky po nahrání změn do větve `main` nebo `master`.

## 3. Stáhni APK
1. Počkej na zelenou fajfku u dokončeného běhu.
2. Otevři daný běh workflow a najdi část **Artifacts**.
3. Stáhni `RobotCockpit-debug-apk` (ZIP artefaktu) a rozbal jej.
4. V něm je `app-debug.apk`. Otevři jej na Androidu; pokud se instalace zablokuje, povol instalaci z daného zdroje v nastavení Androidu.

## Poznámky
- Jde o testovací debug APK, ne publikovanou aplikaci.
- Zatím jde o simulaci; aplikace není připojena k ESP32 ani k motorům.
- Build se provádí na serverech GitHubu, není potřeba token ani tajný klíč.
