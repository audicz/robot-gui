#!/usr/bin/env python3
from pathlib import Path
import re
import shutil
import sys

project = Path(sys.argv[1] if len(sys.argv) > 1 else "source/MAROS-MOBILE-COCKPIT")
main = project / "app/src/main/java/cz/maros/mobile/MainActivity.java"
engine_src = Path("tools/LidarSlamEngine.java")
engine_dst = project / "app/src/main/java/cz/maros/mobile/LidarSlamEngine.java"

if not main.is_file() or not engine_src.is_file():
    raise SystemExit("Android MainActivity or LidarSlamEngine source not found")

s = main.read_text(encoding="utf-8")
if "final LidarSlamEngine slam = new LidarSlamEngine();" in s:
    print("SLAM patch already applied")
    raise SystemExit(0)

field_anchor = "int gpsSats=0,radarTargets=0, lidarPackets=0, radarPackets=0;"
if field_anchor not in s:
    raise SystemExit("Expected telemetry field anchor missing")
s = s.replace(field_anchor, field_anchor + "\n    final LidarSlamEngine slam = new LidarSlamEngine();", 1)

old_map = re.search(r'    void renderMap\(\)\{.*?\n', s)
if not old_map:
    raise SystemExit("renderMap method not found")
new_map = '''    void renderMap(){heading("SLAM / 2D MAPA");content.addView(new MapView(this),new LinearLayout.LayoutParams(-1,0,1));rowMetrics(new String[][]{{"DOKONČENÉ SKENY",String.valueOf(slam.scans())},{"MAPOVÉ BODY",String.valueOf(slam.points())},{"ZTRACENÉ SKENY",String.valueOf(slam.dropped())}});content.addView(txt("LDS D3 • skládání 23 fragmentů • scan matching • automatické měřítko • detekce stěn a pravých rohů",12,MUTED,false));actionRow("VYHLEDAT ZAŘÍZENÍ","RESET MAPY",()->startScan(),()->{slam.reset();render();});}'''
s = s[:old_map.start()] + new_map + s[old_map.end():]

old_lds = 'else if(type.equals("LDS")){lidarPackets++;runOnUiThread(()->{if(page.equals("MAPA")||page.equals("RADAR"))render();});}'
new_lds = 'else if(type.equals("LDS")){if(slam.feed(b)){lidarPackets++;runOnUiThread(()->{if(page.equals("MAPA")||page.equals("RADAR"))render();});}}'
if old_lds not in s:
    raise SystemExit("LDS notification handler anchor missing")
s = s.replace(old_lds, new_lds, 1)

pattern = r'    class MapView extends View\{.*?\n    class Joystick'
m = re.search(pattern, s, re.S)
if not m:
    raise SystemExit("MapView class anchor missing")
replacement = '''    class MapView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        MapView(Context c){super(c);setBackgroundColor(Color.rgb(8,15,24));}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float w=getWidth(),h=getHeight();
            p.setColor(Color.rgb(22,37,53));p.setStrokeWidth(1);p.setStyle(Paint.Style.STROKE);
            for(int x=0;x<w;x+=32)c.drawLine(x,0,x,h,p);
            for(int y=0;y<h;y+=32)c.drawLine(0,y,w,y,p);
            p.setStyle(Paint.Style.FILL);
            slam.draw(c,w,h,p);
        }
    }
    class Joystick'''
s = s[:m.start()] + replacement + s[m.end():]

main.write_text(s, encoding="utf-8")
engine_dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(engine_src, engine_dst)
print("Applied MAROS D3 scan assembly, scan matching, auto-fit map, wall and corner extraction")
