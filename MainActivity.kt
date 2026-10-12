package cz.marek.robotcockpit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Cockpit() }
    }
}
@Composable fun Cockpit() {
    var page by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(false) }
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    var yaw by remember { mutableFloatStateOf(0f) }
    var left by remember { mutableFloatStateOf(0f) }
    var right by remember { mutableFloatStateOf(0f) }
    var steps by remember { mutableStateOf(listOf<String>()) }
    MaterialTheme {
        Scaffold(bottomBar = { NavigationBar {
            listOf("MAPA","OVLADAČ","SENZORY","TRASA","NASTAVENÍ").forEachIndexed { i, s ->
                NavigationBarItem(selected=page==i,onClick={page=i},icon={},label={Text(s)})
            }
        }}) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                    Text("ROBOT COCKPIT 0.2", fontWeight=FontWeight.Bold, fontSize=20.sp)
                    Button(onClick={active=!active; if(!active){left=0f;right=0f}}) { Text(if(active) "STOP" else "START") }
                }
                Spacer(Modifier.height(8.dp))
                when(page) {
                    0 -> {
                        Text("2D MAPA • testovací lokální souřadnice")
                        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF2F4F7))) {
                            for(i in 0..10) {
                                drawLine(Color.LightGray, Offset(i*size.width/10,0f), Offset(i*size.width/10,size.height))
                                drawLine(Color.LightGray, Offset(0f,i*size.height/10), Offset(size.width,i*size.height/10))
                            }
                            val c=Offset(size.width/2+x*2,size.height/2-y*2)
                            drawCircle(Color(0xFF1565C0),15f,c)
                            val a=(yaw-90)*Math.PI/180
                            drawLine(Color.Black,c,Offset(c.x+30*cos(a).toFloat(),c.y+30*sin(a).toFloat()),5f)
                        }
                        Text("HOME: X=0 Y=0 cm   |   X %.1f cm  Y %.1f cm  Yaw %.1f°".format(x,y,yaw))
                        Text(if(active) "ŘÍZENÍ AKTIVNÍ (simulace)" else "ZASTAVENO")
                    }
                    1 -> {
                        Text("Virtuální joystick • simulace")
                        var px by remember { mutableFloatStateOf(0f) }; var py by remember { mutableFloatStateOf(0f) }
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment=Alignment.Center) {
                            Box(Modifier.size(260.dp).background(Color(0xFFE5E7EB),CircleShape).pointerInput(active) {
                                detectDragGestures(onDragEnd={px=0f;py=0f;left=0f;right=0f},onDragCancel={px=0f;py=0f;left=0f;right=0f}) { c,d ->
                                    c.consume(); px=(px+d.x).coerceIn(-100f,100f); py=(py+d.y).coerceIn(-100f,100f)
                                    val t=(-py/100f).coerceIn(-1f,1f); val s=(px/100f).coerceIn(-1f,1f)
                                    left=(t+s).coerceIn(-1f,1f); right=(t-s).coerceIn(-1f,1f)
                                    if(active) { x+=(left+right)*0.03f; yaw+=(right-left)*0.3f }
                                }
                            },contentAlignment=Alignment.Center) {
                                Box(Modifier.offset(px.dp,py.dp).size(65.dp).background(Color(0xFF1565C0),CircleShape))
                            }
                        }
                        Text("Levý motor %.2f | Pravý motor %.2f".format(left,right))
                        Button(onClick={left=0f;right=0f}) { Text("NOUZOVÉ STOP (lokální)") }
                    }
                    2 -> Column(Modifier.fillMaxSize()) {
                        Text("SENZORY • zatím simulovaná data",fontWeight=FontWeight.Bold)
                        listOf("IMU / Gyro" to "Yaw %.1f° • pitch 0.0° • roll 0.0°".format(yaw),
                            "Enkodéry" to "L %.2f • R %.2f".format(left,right),
                            "Odometrie" to "X %.1f cm • Y %.1f cm".format(x,y),
                            "LiDAR Xiaomi" to "Čeká na připojení; formát dat nutno ověřit",
                            "GPS" to "Bez GPS modulu zatím nedostupné",
                            "Baterie / proud" to "Čeká na telemetrii z řídicí jednotky").forEach { (a,b) ->
                            Card(Modifier.fillMaxWidth().padding(vertical=4.dp)) { Column(Modifier.padding(12.dp)) { Text(a,fontWeight=FontWeight.Bold); Text(b) } }
                        }
                    }
                    3 -> Column(Modifier.fillMaxSize()) {
                        Text("EDITOR PŘEDDEFINOVANÉ TRASY",fontWeight=FontWeight.Bold)
                        Button(onClick={steps=steps+"JEĎ 100 cm"}) { Text("+ Jeď 100 cm") }
                        Button(onClick={steps=steps+"OTOČ 90°"}) { Text("+ Otoč 90°") }
                        Button(onClick={steps=steps+"ČEKEJ 2 s"}) { Text("+ Čekej 2 s") }
                        Button(onClick={steps=emptyList()}) { Text("Smazat trasu") }
                        steps.forEachIndexed { i,s -> Text("${i+1}. $s",Modifier.padding(5.dp)) }
                        Text("Trasa se zatím jen edituje v aplikaci; do robota se neposílá.")
                    }
                    else -> Column {
                        Text("NASTAVENÍ KOMUNIKACE",fontWeight=FontWeight.Bold)
                        Text("Prototyp zatím nemá implementované Bluetooth/Wi‑Fi spojení.")
                        Text("Protokol návrhu: JSON drive/stop/telemetry. Před připojením motorů je nutné doplnit skutečné GPIO a watchdog na ESP32.")
                        Text("Bezpečnost: ovládání a data jsou nyní pouze simulovaná.")
                    }
                }
            }
        }
    }
}
