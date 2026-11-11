package cz.maros.mobilecockpit
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

private val BG=Color(0xFF050B12); private val P=Color(0xFF0C1722); private val E=Color(0xFF233B4C)
private val M=Color(0xFF8BA2B3); private val T=Color(0xFFF2F6FA); private val C=Color(0xFF00D9F5)
private val G=Color(0xFF39E58C); private val O=Color(0xFFFFA33D); private val R=Color(0xFFFF5366)

class MainActivity:ComponentActivity(){
 private lateinit var ble:BleCockpitManager
 private lateinit var gateway:MobileGatewayClient
 private val gatewayState=mutableStateOf(GatewayStatus())
 private val state=mutableStateOf(CockpitSnapshot())
 private var pending=false
 private lateinit var permissionLauncher:ActivityResultLauncher<Array<String>>
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);ble=BleCockpitManager(this){next->state.value=next.copy(lidar=next.lidar?:state.value.lidar,imu=if(gatewayState.value.imu.updatedAt>0)gatewayState.value.imu else next.imu,gps=if(gatewayState.value.gps.updatedAt>0)gatewayState.value.gps else next.gps,lidarPackets=maxOf(next.lidarPackets,state.value.lidarPackets),lidarDropped=maxOf(next.lidarDropped,state.value.lidarDropped),lidarBad=maxOf(next.lidarBad,state.value.lidarBad))}
  gateway=MobileGatewayClient(this,{scan,packets,dropped,bad->runOnUiThread{state.value=state.value.copy(lidar=scan,lidarPackets=packets,lidarDropped=dropped,lidarBad=bad)}},{status->runOnUiThread{gatewayState.value=status;state.value=state.value.copy(imu=if(status.imu.updatedAt>0)status.imu else state.value.imu,gps=if(status.gps.updatedAt>0)status.gps else state.value.gps)}})
  permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->if(result.values.all{it}&&pending){pending=false;ble.startScan()}else if(pending){pending=false;ble.setScanMessage("Oprávnění Bluetooth / Zařízení v okolí nebylo povoleno")}}
  setContent{val s by state;val gs by gatewayState;MaterialTheme(colorScheme=darkColorScheme(primary=C,background=BG,surface=P,onSurface=T)){App(s,gs,{if(s.scanRunning)ble.stopScan()else if(ok())ble.startScan()else{pending=true;ask()}},{d,r->if(ok())ble.connect(d,r)else{pending=false;ask()}},{ble.disconnect(it)},{gateway.connect(it)},{gateway.sendStop()},{gateway.sendBridgeCommand(it)},{gateway.setLidarMotor(it)})}}}
 private fun ok():Boolean{val p=if(Build.VERSION.SDK_INT>=31)arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION);return p.all{ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED}}
 private fun ask(){val p=if(Build.VERSION.SDK_INT>=31)arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION);permissionLauncher.launch(p)}
 override fun onStop(){if(::ble.isInitialized)ble.stopScan();super.onStop()}
 override fun onDestroy(){if(::ble.isInitialized)ble.disconnectAll();if(::gateway.isInitialized)gateway.disconnect();super.onDestroy()}
}
private fun f(v:Float,d:Int=1)=if(v.isNaN()||v.isInfinite())"—" else "%.${d}f".format(v)
private fun sp(v:VescTelemetry)=if(v.valid)v.erpm/7f*(PI*.254*60/1000).toFloat()else Float.NaN

@Composable private fun App(s:CockpitSnapshot,gs:GatewayStatus,scan:()->Unit,connect:(SeenDevice,DeviceRole)->Unit,off:(DeviceRole)->Unit,onGatewayConnect:(String)->Unit,onRobotStop:()->Unit,onBridgeCommand:(Int)->Unit,onLidarMotor:(Boolean)->Unit){
 var page by remember{mutableStateOf("MAIN")};var role by remember{mutableStateOf(DeviceRole.VESC1)};var slam by remember{mutableStateOf(SlamSnapshot())};val engine=remember{SlamEngine()}
 LaunchedEffect(s.lidar?.receivedAt){s.lidar?.let{slam=withContext(Dispatchers.Default){engine.process(it,s.imu)}}}
 Scaffold(containerColor=BG,bottomBar={NavigationBar(containerColor=P){listOf("MAIN","POWER","SENSORS","SLAM","MENU").forEach{NavigationBarItem(page==it,{page=it},icon={Text(it.take(1))},label={Text(it,fontSize=9.sp)})}}}){pad->
  Column(Modifier.fillMaxSize().padding(pad).background(BG)){
   Row(Modifier.fillMaxWidth().background(P).padding(14.dp),horizontalArrangement=Arrangement.SpaceBetween){Column{Text("MAROŠ",fontSize=20.sp,fontWeight=FontWeight.Black,color=T);Text("MOBILE COCKPIT V3.1 · GATEWAY",fontSize=9.sp,color=M)};Text(page+" · BLE "+s.links.values.count{it.connected}+" · Wi-Fi "+if(gs.online)"ONLINE" else "OFF",color=if(gs.online)G else O)}
   HorizontalDivider(color=E)
   when(page){
    "MAIN"->Main(s){page=it}
    "POWER"->ValuesPage(s)
    "SENSORS"->Sensors(s,onBridgeCommand)
    "SLAM","ROUTE"->Slam(s,slam){x,y->slam=engine.setGoal(x,y)}
    "MENU"->Menu{page=it}
    "PAIRING"->Pair(s,gs,role,{role=it},scan,connect,onGatewayConnect)
    "VESC"->Vesc(s)
    "BMS"->Bms(s)
    "LIDAR"->Lidar(s,gs,onLidarMotor)
    "LINKS"->Links(s,off)
    "TEMPS"->Info("TEMPERATURES","FET "+f(s.vesc1.fetC)+" / "+f(s.vesc2.fetC)+" °C","MOTOR "+f(s.vesc1.motorC)+" / "+f(s.vesc2.motorC)+" °C")
    "ENERGY"->Info("ENERGY","Ah "+f(s.vesc1.ah+s.vesc2.ah,2),"Wh "+f(s.vesc1.wh+s.vesc2.wh,1))
    "GPS","TRACK"->Info("GPS / TRACK",if(s.gps.fix)"FIX "+s.gps.sats+" sats" else "NO GPS FIX",f(s.gps.latitude.toFloat(),6)+" / "+f(s.gps.longitude.toFloat(),6))
    "HISTORY"->History(s)
    "HORIZON","G-METER","GYRO"->Sensors(s,onBridgeCommand)
    "SETTINGS"->Info("SETTINGS","Wheel 0.254 m · pole pairs 7","VESC write disabled")
    "VESC CFG"->Info("VESC CONFIG","READ ONLY","Configuration writes disabled")
    "PROFILES"->Info("PROFILES","NO VERIFIED VESC PROFILE LOADED","Profiles remain read-only until live VESC configuration is read and validated.")
    "CONTROL"->Info("ROBOT CONTROL","MONITOR ONLY","Motor commands locked pending verified watchdog and STOP")
    "BLE DATA"->Info("BLE RAW",s.lastRawHex,"LDS RX "+s.lidarPackets+" / dropped "+s.lidarDropped+" / bad "+s.lidarBad)
    "RADAR"->Radar(s)
    "ROBOT007"->RobotPanel(gs,onRobotStop)
    else->Main(s){page=it}
   }
  }
 }
}
@Composable private fun Frame(title:String,sub:String?=null,body:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Column{Text(title,fontSize=19.sp,fontWeight=FontWeight.Bold,color=T);if(sub!=null)Text(sub,fontSize=10.sp,color=M)};Column(Modifier.fillMaxSize().weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),content=body)}}
@Composable private fun PanelCard(mod:Modifier=Modifier,body:@Composable ColumnScope.()->Unit){androidx.compose.material3.Card(mod.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=P),border=androidx.compose.foundation.BorderStroke(1.dp,E)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp),content=body)}}
@Composable private fun Metric(a:String,b:String,unit:String="",color:Color=C,mod:Modifier=Modifier){androidx.compose.material3.Card(mod,colors=CardDefaults.cardColors(containerColor=P),border=androidx.compose.foundation.BorderStroke(1.dp,E)){Column(Modifier.padding(11.dp)){Text(a,fontSize=9.sp,color=M);Text(b+" "+unit,fontSize=22.sp,fontWeight=FontWeight.Bold,color=color)}}}
@Composable private fun Main(s:CockpitSnapshot,go:(String)->Unit){Frame("LIVE COCKPIT","DUAL DRIVE / VESC / JBD / SENSOR HUB"){val v=if(s.vesc1.valid)s.vesc1 else s.vesc2
 PanelCard{Text("VEHICLE SPEED",color=M);Row(verticalAlignment=Alignment.CenterVertically){Text(f(sp(v))+" km/h",Modifier.weight(1f),fontSize=34.sp,fontWeight=FontWeight.Bold,color=T);Column{Text("POWER "+f(if(v.valid)v.voltage*v.batteryA/1000 else Float.NaN,2)+" kW",color=O);Text("SOC "+f(s.bms.soc,0)+" %",color=G);Text("PACK "+f(s.bms.voltage)+" V",color=C)}}}
 Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("BATTERY A",f(s.bms.current),"A",C,Modifier.weight(1f));Metric("MOTOR A",f(v.motorA),"A",O,Modifier.weight(1f))}
 Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("FET TEMP",f(maxOf(s.vesc1.fetC,s.vesc2.fetC)),"°C",O,Modifier.weight(1f));Metric("MOTOR TEMP",f(maxOf(s.vesc1.motorC,s.vesc2.motorC)),"°C",O,Modifier.weight(1f))}
 Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){OutlinedButton({go("VESC")},Modifier.weight(1f)){Text("VESC DETAIL")};OutlinedButton({go("PAIRING")},Modifier.weight(1f)){Text("PAIR DEVICES")}}
}}
@Composable private fun ValuesPage(s:CockpitSnapshot){Frame("DRIVE / POWER","Overview"){val v=if(s.vesc1.valid)s.vesc1 else s.vesc2;listOf("SPEED" to f(sp(v)),"POWER kW" to f(v.voltage*v.batteryA/1000,2),"DUTY" to f(v.duty*100,0),"PACK V" to f(s.bms.voltage),"CURRENT A" to f(s.bms.current),"SOC" to f(s.bms.soc,0),"MOTOR A" to f(v.motorA),"FET C" to f(v.fetC)).chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){row.forEach{Metric(it.first,it.second,mod=Modifier.weight(1f))}}}}}
@Composable private fun Vesc(s:CockpitSnapshot){Frame("VESC TELEMETRY","NUS / COMM_GET_VALUES / 250 ms / read only"){listOf("VESC 1" to s.vesc1,"VESC 2" to s.vesc2).forEach{(n,v)->PanelCard{Text(n+"  "+if(v.valid)"ONLINE"else"OFFLINE",color=if(v.valid)G else R);Text("SPEED "+f(sp(v))+" km/h · ERPM "+f(v.erpm,0),color=T);Text("MOTOR "+f(v.motorA)+" A · BATTERY "+f(v.batteryA)+" A",color=T);Text("FET "+f(v.fetC)+" °C · MOTOR "+f(v.motorC)+" °C · VIN "+f(v.voltage)+" V",color=M)}}}}
@Composable private fun Bms(s:CockpitSnapshot){Frame("JBD BMS","FF00 / FF01 / FF02 / read only"){Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("PACK",f(s.bms.voltage),"V",mod=Modifier.weight(1f));Metric("SOC",f(s.bms.soc,0),"%",G,Modifier.weight(1f))};Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("CURRENT",f(s.bms.current),"A",O,Modifier.weight(1f));Metric("REMAINING",f(s.bms.remainingAh),"Ah",C,Modifier.weight(1f))};PanelCard{Text("CELL VOLTAGES · "+s.bms.cells.size,color=C);s.bms.cells.forEachIndexed{i,v->Text("C"+(i+1)+"   "+f(v,3)+" V",color=T,fontSize=11.sp)}}}}
@Composable private fun Sensors(s:CockpitSnapshot,onCommand:(Int)->Unit={}){Frame("SENSOR HUB","MPU6050 / QMC5883L / GPS · ESP-NOW D7"){Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("ROLL",f(s.imu.roll),"°",O,Modifier.weight(1f));Metric("PITCH",f(s.imu.pitch),"°",C,Modifier.weight(1f))};Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("HEADING",f(s.imu.heading,0),"°",C,Modifier.weight(1f));Metric("GPS SPEED",f(s.gps.speedKmh),"km/h",G,Modifier.weight(1f))};Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("G FWD",f(s.imu.gForward),"g",G,Modifier.weight(1f));Metric("G LAT",f(s.imu.gRight),"g",O,Modifier.weight(1f))};Text(if(s.imu.zeroed)"IMU ZEROED"else"IMU NOT ZEROED",color=if(s.imu.zeroed)G else R)
 Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Button({onCommand(1)},Modifier.weight(1f)){Text("ZERO IMU")};Button({onCommand(5)},Modifier.weight(1f)){Text("ROTATE 90")}}
 Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Button({onCommand(2)},Modifier.weight(1f)){Text("MAG CAL START")};OutlinedButton({onCommand(3)},Modifier.weight(1f)){Text("MAG CAL STOP")}}
 Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({onCommand(6)},Modifier.weight(1f)){Text("LEVEL")};OutlinedButton({onCommand(7)},Modifier.weight(1f)){Text("NOSE UP")};OutlinedButton({onCommand(8)},Modifier.weight(1f)){Text("RIGHT SIDE")}}
 Text("Povel se předává přes displej D7 do LiDAR Bridge. Kalibraci prováděj podle pokynů na obrazovce.",color=M,fontSize=10.sp)}}
@Composable private fun Info(t:String,a:String,b:String){Frame(t){PanelCard{Text(a,color=C,fontWeight=FontWeight.Bold);Text(b,color=T);Text("Only real received data are shown; missing values are not simulated.",color=M,fontSize=10.sp)}}}
@Composable private fun Pair(s:CockpitSnapshot,gs:GatewayStatus,role:DeviceRole,setRole:(DeviceRole)->Unit,scan:()->Unit,connect:(SeenDevice,DeviceRole)->Unit,onGatewayConnect:(String)->Unit){Frame("PAIRING / BLE","VESC NUS · JBD · Sensor Hub · legacy LDS only"){
 var expanded by remember{mutableStateOf(false)}
 var gatewayHost by remember{mutableStateOf(gs.host.ifBlank{"192.168.4.1"})}
 PanelCard {
  Text("VESC DISPLAY · ESP-NOW GATEWAY",color=C,fontWeight=FontWeight.Bold)
  OutlinedTextField(value=gatewayHost,onValueChange={gatewayHost=it},label={Text("IP adresa displeje")},singleLine=true,modifier=Modifier.fillMaxWidth())
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){
   Text(if(gs.online)"ONLINE · "+gs.packets+" paketů" else if(gs.connecting)"PŘIPOJUJI…" else "OFFLINE",color=if(gs.online)G else O)
   Button({onGatewayConnect(gatewayHost)}){Text("PŘIPOJIT")}
  }
  Text("D9/LiDAR "+gs.droppedScans+" ztracených otáček · chybné rámce "+gs.badPackets,color=M,fontSize=10.sp)
 }
 Box{OutlinedButton({expanded=true},Modifier.fillMaxWidth()){Text("ROLE: "+role.title)};DropdownMenu(expanded,{expanded=false}){DeviceRole.values().forEach{DropdownMenuItem(text={Text(it.title)},onClick={setRole(it);expanded=false})}}}
 Button(scan,Modifier.fillMaxWidth()){Text(if(s.scanRunning)"STOP SCAN"else"SCAN BLE")}
 Text(s.scanMessage,color=if(s.scanRunning)C else O,fontSize=11.sp)
 Text(s.devices.size.toString()+" devices found",color=M)
 LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){items(s.devices,key={it.address}){d->PanelCard{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(d.name,color=T);Text(d.address+" · "+d.rssi+" dBm",color=M,fontSize=10.sp)};Button({connect(d,role)}){Text("CONNECT")}}}}}
}}
@Composable private fun History(s:CockpitSnapshot) {
    val h=s.history
    Frame("HISTORY / DRIVE","2 s sampling · max 346 samples · rolling ~11.5 min") {
        if(h.isEmpty()) {
            Text("Čekám na platná VESC data…",color=O)
        } else {
            PanelCard {
                Text("SPEED · km/h",color=C)
                Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                    val values=h.map{it.speedKmh}.filter{it.isFinite()}
                    val top=maxOf(5f,values.maxOrNull()?:5f)
                    for(i in 0..4) {
                        val y=size.height*i/4
                        drawLine(E,Offset(0f,y),Offset(size.width,y),1f)
                    }
                    if(values.size>1) {
                        val path=Path()
                        values.forEachIndexed { i,v ->
                            val x=i.toFloat()/(values.size-1)*size.width
                            val y=size.height-(v/top).coerceIn(0f,1f)*size.height
                            if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                        }
                        drawPath(path,C,style=androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    }
                }
            }
            PanelCard {
                Text("POWER · kW",color=O)
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val values=h.map{it.powerKw}.filter{it.isFinite()}
                    val top=maxOf(1f,values.maxOrNull()?:1f)
                    for(i in 0..3) {
                        val y=size.height*i/3
                        drawLine(E,Offset(0f,y),Offset(size.width,y),1f)
                    }
                    if(values.size>1) {
                        val path=Path()
                        values.forEachIndexed { i,v ->
                            val x=i.toFloat()/(values.size-1)*size.width
                            val y=size.height-(v/top).coerceIn(0f,1f)*size.height
                            if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                        }
                        drawPath(path,O,style=androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    }
                }
            }
            val last=h.last()
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                Metric("CURRENT",f(last.batteryA),"A",O,Modifier.weight(1f))
                Metric("FET",f(last.fetC),"°C",C,Modifier.weight(1f))
                Metric("MOTOR",f(last.motorC),"°C",O,Modifier.weight(1f))
            }
            Text("Samples "+h.size+" · newest "+((System.currentTimeMillis()-last.at)/1000)+" s ago",color=M,fontSize=10.sp)
        }
    }
}
@Composable private fun Radar(s:CockpitSnapshot){val r=s.radar;Frame("HLK-LD2451 RADAR","BLE AE00 / AE01 / AE02 · automotive targets"){PanelCard{Text(if(r.valid)"LIVE · "+r.targets.size+" TARGETS" else "WAITING FOR RADAR",color=if(r.valid)G else O);Text(if(r.alarm)"APPROACH ALARM" else "NO APPROACH ALARM",color=if(r.alarm)R else G,fontWeight=FontWeight.Bold);Canvas(Modifier.fillMaxWidth().weight(1f)){val center=Offset(size.width/2,size.height*.88f);val radius=min(size.width*.46f,size.height*.82f);for(i in 1..4)drawArc(E,180f,180f,false,topLeft=Offset(center.x-radius*i/4,center.y-radius*i/4),size=Size(radius*i/2,radius*i/2),style=androidx.compose.ui.graphics.drawscope.Stroke(1f));drawLine(E,Offset(center.x-radius,center.y),Offset(center.x+radius,center.y),1f);r.targets.forEach{t->if(t.distanceM in 0.1f..20f){val a=t.angleDeg*PI.toFloat()/180f;val d=t.distanceM/20f*radius;val pt=Offset(center.x+sin(a)*d,center.y-cos(a)*d);drawCircle(if(t.approaching)R else C,7f,pt)}};drawCircle(O,5f,center)}};if(r.targets.isEmpty())Text("Žádný cíl v zorném poli",color=M);r.targets.forEachIndexed{i,t->PanelCard{Text("TARGET "+(i+1)+" · "+(if(t.approaching)"APPROACHING" else "MOVING / STATIC"),color=if(t.approaching)R else C);Text("ANGLE "+f(t.angleDeg)+"° · DISTANCE "+f(t.distanceM)+" m · SPEED "+f(t.speedKmh)+" km/h · SNR "+t.snr,color=T,fontSize=11.sp)}};Text("Frames "+r.packets+" · invalid "+r.badFrames,color=M,fontSize=10.sp)}}
@Composable private fun RobotPanel(gs:GatewayStatus,onStop:()->Unit){Frame("ROBOT007 · ESP-NOW","Gateway: "+if(gs.online)"ONLINE" else "OFFLINE"){PanelCard{Text("CHASSIS TELEMETRY",color=C,fontWeight=FontWeight.Bold);Text("Flags 0x"+gs.robotFlags.toString(16)+" · sequence "+gs.robotSequence,color=M);Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("LEFT PWM",gs.leftPwm.toString(),color=O,mod=Modifier.weight(1f));Metric("RIGHT PWM",gs.rightPwm.toString(),color=O,mod=Modifier.weight(1f))};Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Metric("LEFT ENC",gs.encLeft.toString(),mod=Modifier.weight(1f));Metric("RIGHT ENC",gs.encRight.toString(),mod=Modifier.weight(1f))};Text("ODOMETRY "+(if(gs.odomValid)"SEQ "+gs.odomSequence+" · Δt "+gs.odomDtMs+" ms" else "NO D4 DATA"),color=if(gs.odomValid)G else O,fontSize=10.sp);Text("D2 SCAN HEAD "+(if(gs.scanHead.valid)"EL "+f(gs.scanHead.elevationDeg)+"° · AZ "+f(gs.scanHead.azimuthDeg)+"° · ToF "+gs.scanHead.tofMm+" mm · US "+gs.scanHead.ultrasonicMm+" mm" else "OFFLINE"),color=if(gs.scanHead.valid)G else O,fontSize=10.sp);Text("Motor commands remain disabled until the display firmware and physical STOP chain are verified.",color=O,fontSize=10.sp);Button(onStop,enabled=gs.online,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=R)){Text("NOUZOVÉ STOP · ROBOT007")}}}
@Composable private fun Links(s:CockpitSnapshot,off:(DeviceRole)->Unit){Frame("LINKS","BLE status"){DeviceRole.values().forEach{r->val l=s.links[r]?:LinkStatus();PanelCard{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(r.title,color=T);Text(l.address,color=M,fontSize=10.sp)};Text(l.label,color=if(l.connected)G else R);TextButton({off(r)}){Text("OFF")}}}}}}
@Composable private fun Lidar(s:CockpitSnapshot,gs:GatewayStatus,onLidarMotor:(Boolean)->Unit){Frame("XIAOMI LDS","D9 · 4 ESP-NOW fragments · 360 bins · millimetres"){val scan=s.lidar;PanelCard{Text(if(scan!=null)"LIVE "+scan.rpm+" RPM"else"WAITING FOR LDS",color=if(scan!=null)G else R);Canvas(Modifier.fillMaxWidth().weight(1f)){val c=Offset(size.width/2,size.height/2);val r=min(size.width,size.height)*.43f;for(i in 1..4)drawCircle(E,r*i/4,c,style=androidx.compose.ui.graphics.drawscope.Stroke(1f));scan?.rangesMm?.forEachIndexed{a,mm->if(mm in 80..12000){val q=a*PI.toFloat()/180f;val quality=if(gs.lidarQuality.size==360)gs.lidarQuality[a] else 0;val pointColor=if((quality and 0x80)!=0)O else C;drawCircle(pointColor,if(quality==0)1.5f else 2f,Offset(c.x+sin(q)*mm/12000f*r,c.y-cos(q)*mm/12000f*r))}};drawCircle(O,6f,c)}};Text("RX "+s.lidarPackets+" · dropped "+s.lidarDropped+" · bad "+s.lidarBad,color=M)
 Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){Button({onLidarMotor(true)},enabled=gs.online,modifier=Modifier.weight(1f)){Text("LIDAR MOTOR ON")};OutlinedButton({onLidarMotor(false)},enabled=gs.online,modifier=Modifier.weight(1f)){Text("MOTOR OFF")}}
 val st=gs.lidarBridge;if(st.valid){Text("MOTOR "+(if(st.motorOn)"ON" else "OFF")+" · "+f(st.rpm)+" RPM · DUTY "+st.dutyPct+"%",color=if(st.motorOn)G else O);Text("POINTS "+st.validPoints+" · RX "+st.packetsPerSecond+"/s · BAD "+st.badPackets+" · CRC "+st.checksumErrors,color=M);Text("SCANS "+st.completeScans+" complete / "+st.incompleteScans+" incomplete · AVG Q "+st.averageQuality+" · WARN "+st.warningPercent+"%",color=M)}else Text("DB BRIDGE STATUS: WAITING",color=O)}}
@Composable private fun Slam(s:CockpitSnapshot, slam:SlamSnapshot, goal:(Int,Int)->Unit) {
    Frame("MOBILE SLAM", "5 cm occupancy map · scan-to-map · A*") {
        PanelCard {
            Text("MAP · REV "+slam.mapRevision, color=C)
            Canvas(
                Modifier.fillMaxWidth().weight(1f).pointerInput(slam.mapRevision) {
                    detectTapGestures { p ->
                        val side=min(size.width,size.height)
                        val ox=(size.width-side)/2
                        val oy=(size.height-side)/2
                        goal(((p.x-ox)/side*256).toInt().coerceIn(0,255),
                             ((p.y-oy)/side*256).toInt().coerceIn(0,255))
                    }
                }
            ) {
                val side=min(size.width,size.height)
                val ox=(size.width-side)/2
                val oy=(size.height-side)/2
                val cell=side/256
                drawRect(Color(0xFF07111A),Offset(ox,oy),Size(side,side))
                for(y in 0 until 256 step 2) for(x in 0 until 256 step 2) {
                    val v=slam.grid[y*256+x]
                    if(v>=2) drawRect(Color.LightGray,Offset(ox+x*cell,oy+y*cell),Size(cell*2,cell*2))
                }
                if(slam.path.size>1) {
                    val path=Path()
                    slam.path.forEachIndexed { i,q ->
                        val point=Offset(ox+(q.first+.5f)*cell,oy+(q.second+.5f)*cell)
                        if(i==0) path.moveTo(point.x,point.y) else path.lineTo(point.x,point.y)
                    }
                    drawPath(path,C,style=androidx.compose.ui.graphics.drawscope.Stroke(3f))
                }
                val rx=ox+(128+slam.pose.x/.05f)*cell
                val ry=oy+(128-slam.pose.y/.05f)*cell
                drawCircle(O,7f,Offset(rx,ry))
            }
            Text("Tap map to set route goal · path "+slam.path.size+" cells",color=M,fontSize=10.sp)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Metric("WALL",slam.metrics.wallMs.toString(),"ms",C,Modifier.weight(1f))
            Metric("CPU",slam.metrics.cpuMs.toString(),"ms",O,Modifier.weight(1f))
            Metric("MATCH",f(slam.metrics.confidence*100,0),"%",G,Modifier.weight(1f))
        }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Metric("AVG",f(slam.metrics.averageMs),"ms",C,Modifier.weight(1f))
            Metric("MAX",slam.metrics.maxMs.toString(),"ms",O,Modifier.weight(1f))
            Metric("SCAN",f(slam.metrics.scanHz),"Hz",G,Modifier.weight(1f))
        }
    }
}
@Composable private fun Menu(open:(String)->Unit){val names=listOf("MAIN","DRIVE","VESC","BMS","TEMPS","ENERGY","HISTORY","SENSORS","GPS","TRACK","HORIZON","G-METER","GYRO","LINKS","PAIRING","SETTINGS","VESC CFG","PROFILES","CONTROL","BLE DATA","LIDAR","SLAM","ROUTE","RADAR","ROBOT007");Frame("ALL PANELS","25 pages · VESC Cockpit + Robot007"){LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)){items(names.chunked(2)){row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){row.forEach{n->PanelCard(Modifier.weight(1f).clickable{open(n)}){Text(n,color=if(n=="SLAM"||n=="LIDAR")C else T,fontWeight=FontWeight.Bold);Text("OPEN PANEL ›",fontSize=9.sp,color=M)}};if(row.size==1)Spacer(Modifier.weight(1f))}}}}}
