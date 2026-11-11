package cz.maros.mobilecockpit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class DeviceRole(val title: String) {
    VESC1("VESC 1"), VESC2("VESC 2"), BMS("JBD BMS"), HUB("Sensor Hub"),
    LIDAR("Xiaomi LDS"), RADAR("HLK radar"), ROBOT("Robot007")
}
data class SeenDevice(val name: String, val address: String, val rssi: Int, val device: BluetoothDevice)
data class VescTelemetry(
    val valid:Boolean=false, val fetC:Float=Float.NaN, val motorC:Float=Float.NaN,
    val motorA:Float=Float.NaN, val batteryA:Float=Float.NaN, val duty:Float=Float.NaN,
    val erpm:Float=Float.NaN, val voltage:Float=Float.NaN, val ah:Float=Float.NaN,
    val wh:Float=Float.NaN, val fault:Int=0, val updatedAt:Long=0L
)
data class BmsTelemetry(
    val valid:Boolean=false, val voltage:Float=Float.NaN, val current:Float=Float.NaN,
    val soc:Float=Float.NaN, val remainingAh:Float=Float.NaN, val capacityAh:Float=Float.NaN,
    val temp1:Float=Float.NaN, val temp2:Float=Float.NaN, val cells:List<Float> = emptyList(),
    val protection:Int=0, val fet:Int=0, val updatedAt:Long=0L
)
data class ImuTelemetry(
    val valid:Boolean=false, val zeroed:Boolean=false, val magOk:Boolean=false,
    val roll:Float=Float.NaN, val pitch:Float=Float.NaN, val gForward:Float=Float.NaN,
    val gRight:Float=Float.NaN, val gx:Float=Float.NaN, val gy:Float=Float.NaN,
    val gz:Float=Float.NaN, val heading:Float=Float.NaN, val temp:Float=Float.NaN,
    val updatedAt:Long=0L
)
data class GpsTelemetry(
    val fix:Boolean=false, val sats:Int=0, val hdop:Float=Float.NaN,
    val latitude:Double=Double.NaN, val longitude:Double=Double.NaN,
    val speedKmh:Float=Float.NaN, val course:Float=Float.NaN, val altitude:Float=Float.NaN,
    val updatedAt:Long=0L
)
data class LidarScan(val rangesMm:IntArray, val rpm:Int, val dutyPct:Int, val sequence:Int, val receivedAt:Long)
data class LinkStatus(val connected:Boolean=false, val label:String="OFFLINE", val address:String="")
data class CockpitSnapshot(
    val devices:List<SeenDevice> = emptyList(),
    val links:Map<DeviceRole,LinkStatus> = emptyMap(),
    val vesc1:VescTelemetry=VescTelemetry(), val vesc2:VescTelemetry=VescTelemetry(),
    val bms:BmsTelemetry=BmsTelemetry(), val imu:ImuTelemetry=ImuTelemetry(),
    val gps:GpsTelemetry=GpsTelemetry(), val lidar:LidarScan?=null,
    val lidarPackets:Long=0, val lidarDropped:Long=0, val lidarBad:Long=0,
    val lastRawHex:String="", val scanRunning:Boolean=false, val scanMessage:String="Připraveno ke skenování"
)

class BleCockpitManager(context: Context, private val changed:(CockpitSnapshot)->Unit) {
    companion object {
        val NUS_SERVICE=UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX=UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX=UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val HUB_SERVICE=UUID.fromString("7e1c0001-5b0e-4a6f-9c7a-0d6a5b3f2e10")
        val HUB_IMU=UUID.fromString("7e1c0002-5b0e-4a6f-9c7a-0d6a5b3f2e10")
        val HUB_GPS=UUID.fromString("7e1c0003-5b0e-4a6f-9c7a-0d6a5b3f2e10")
        val HUB_CMD=UUID.fromString("7e1c0004-5b0e-4a6f-9c7a-0d6a5b3f2e10")
        val LDS_SERVICE=UUID.fromString("a71d0001-5b0e-4a6f-9c7a-0d6a5b3f2e10")
        val LDS_NOTIFY=UUID.fromString("a71d0002-5b0e-4a6f-9c7a-0d6a5b3f2e10")
        val CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val JBD_SERVICE=UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        val JBD_NOTIFY=UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val JBD_WRITE=UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    }
    private val appContext=context.applicationContext
    private val adapter=(appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner get()=adapter.bluetoothLeScanner
    private val main=Handler(Looper.getMainLooper())
    private val seen=linkedMapOf<String,SeenDevice>()
    private val sessions=linkedMapOf<String,Session>()
    private var state=CockpitSnapshot()
    private var scanStop:Runnable?=null

    private inner class Session(val role:DeviceRole,val address:String) {
        var gatt:BluetoothGatt?=null
        var tx:BluetoothGattCharacteristic?=null
        var rx:BluetoothGattCharacteristic?=null
        var pollRunnable:Runnable?=null
        val vescParser=VescStreamParser()
        val jbdParser=JbdStreamParser()
        val ldsAssembler=LdsAssembler { scan,packets,dropped,bad ->
            state=state.copy(lidar=scan,lidarPackets=packets,lidarDropped=dropped,lidarBad=bad)
            publish()
        }
    }

    private val scanCallback=object:ScanCallback(){
        override fun onScanResult(callbackType:Int,result:ScanResult){
            val d=result.device
            val name=try { d.name ?: result.scanRecord?.deviceName ?: "BLE zařízení" } catch (_:SecurityException){"BLE zařízení"}
            seen[d.address]=SeenDevice(name,d.address,result.rssi,d)
            state=state.copy(devices=seen.values.sortedByDescending{it.rssi})
            publish()
        }
        override fun onBatchScanResults(results:MutableList<ScanResult>){
            results.forEach { result ->
                val d=result.device
                val name=try { d.name ?: result.scanRecord?.deviceName ?: "BLE zařízení" } catch (_:SecurityException){"BLE zařízení"}
                seen[d.address]=SeenDevice(name,d.address,result.rssi,d)
            }
            state=state.copy(devices=seen.values.sortedByDescending{it.rssi},scanMessage="Nalezeno ${seen.size} zařízení")
            publish()
        }
        override fun onScanFailed(errorCode:Int){
            val message=when(errorCode){
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Skenování už běží"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android odmítl registraci BLE skeneru"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Interní chyba Bluetooth adaptéru"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE skenování není podporováno"
                else -> "BLE skenování selhalo (kód $errorCode)"
            }
            state=state.copy(scanRunning=false,scanMessage=message)
            publish()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(){
        try{
            scanStop?.let{main.removeCallbacks(it)}
            try{scanner?.stopScan(scanCallback)}catch(_:Exception){}
            if(!adapter.isEnabled){
                state=state.copy(scanRunning=false,scanMessage="Bluetooth je vypnutý – zapni ho v nastavení telefonu")
                publish();return
            }
            val bleScanner=scanner
            if(bleScanner==null){
                state=state.copy(scanRunning=false,scanMessage="BLE skener není dostupný")
                publish();return
            }
            seen.clear()
            state=state.copy(devices=emptyList(),scanRunning=true,scanMessage="Vyhledávám BLE zařízení…")
            publish()
            val settings=ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .build()
            bleScanner.startScan(null,settings,scanCallback)
            scanStop=Runnable{
                try{bleScanner.stopScan(scanCallback)}catch(_:Exception){}
                state=state.copy(scanRunning=false,devices=seen.values.sortedByDescending{it.rssi},
                    scanMessage=if(seen.isEmpty())"Nic nenalezeno – ověř, že zařízení vysílá BLE reklamu a je v dosahu" else "Dokončeno: ${seen.size} zařízení")
                publish()
            }.also{main.postDelayed(it,15000L)}
        }catch(e:SecurityException){
            state=state.copy(scanRunning=false,scanMessage="Chybí oprávnění Zařízení v okolí / Bluetooth")
            publish()
        }catch(e:Exception){
            state=state.copy(scanRunning=false,scanMessage="Chyba BLE: ${e.javaClass.simpleName}: ${e.message ?: "bez detailu"}")
            publish()
        }
    }
    fun setScanMessage(message:String){
        state=state.copy(scanRunning=false,scanMessage=message)
        publish()
    }

    @SuppressLint("MissingPermission")
    fun connect(item:SeenDevice,role:DeviceRole){
        try{
            sessions[item.address]?.gatt?.close()
            val session=Session(role,item.address)
            sessions[item.address]=session
            setLink(role,LinkStatus(false,"CONNECTING",item.address))
            session.gatt=item.device.connectGatt(appContext,false,object:BluetoothGattCallback(){
                override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int){
                    if(newState==BluetoothProfile.STATE_CONNECTED){
                        setLink(role,LinkStatus(true,"DISCOVERING",item.address))
                        g.discoverServices()
                    }else if(newState==BluetoothProfile.STATE_DISCONNECTED){
                        session.pollRunnable?.let{main.removeCallbacks(it)}
                        setLink(role,LinkStatus(false,"DISCONNECTED",item.address))
                        try{g.close()}catch(_:Exception){}
                        sessions.remove(item.address)
                    }
                }
                override fun onServicesDiscovered(g:BluetoothGatt,status:Int){
                    if(status!=BluetoothGatt.GATT_SUCCESS){setLink(role,LinkStatus(false,"SERVICE ERROR",item.address));return}
                    when(role){
                        DeviceRole.VESC1,DeviceRole.VESC2 -> {
                            val service=g.getService(NUS_SERVICE)
                            session.rx=service?.getCharacteristic(NUS_RX)
                            session.tx=service?.getCharacteristic(NUS_TX)
                            if(session.tx!=null && session.rx!=null) enableNotify(g,session,session.tx!!)
                            else setLink(role,LinkStatus(false,"NUS NOT FOUND",item.address))
                        }
                        DeviceRole.LIDAR -> {
                            val service=g.getService(LDS_SERVICE)
                            session.tx=service?.getCharacteristic(LDS_NOTIFY)
                            if(session.tx!=null) enableNotify(g,session,session.tx!!)
                            else setLink(role,LinkStatus(false,"LDS SERVICE NOT FOUND",item.address))
                        }
                        DeviceRole.HUB -> {
                            val service=g.getService(HUB_SERVICE)
                            val imu=service?.getCharacteristic(HUB_IMU)
                            val gps=service?.getCharacteristic(HUB_GPS)
                            if(imu!=null) enableNotify(g,session,imu)
                            if(gps!=null) enableNotify(g,session,gps)
                            if(imu==null && gps==null) setLink(role,LinkStatus(false,"HUB SERVICE NOT FOUND",item.address))
                        }
                        DeviceRole.BMS -> {
                            val service=g.getService(JBD_SERVICE)
                            session.rx=service?.getCharacteristic(JBD_WRITE)
                            session.tx=service?.getCharacteristic(JBD_NOTIFY)
                            if(session.tx!=null && session.rx!=null) enableNotify(g,session,session.tx!!)
                            else setLink(role,LinkStatus(false,"JBD FF00 NOT FOUND",item.address))
                        }
                        else -> setLink(role,LinkStatus(true,"CONNECTED · RAW ONLY",item.address))
                    }
                }
                override fun onDescriptorWrite(g:BluetoothGatt,descriptor:BluetoothGattDescriptor,status:Int){
                    if(status==BluetoothGatt.GATT_SUCCESS){
                        setLink(role,LinkStatus(true,"ONLINE",item.address))
                        when(role){
                            DeviceRole.VESC1,DeviceRole.VESC2 -> startVescPoll(session)
                            DeviceRole.BMS -> startJbdPoll(session)
                            else -> Unit
                        }
                    }
                }
                override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic){
                    val value=c.value ?: return
                    receive(session,c.uuid,value)
                }
            })
        }catch(e:Exception){setLink(role,LinkStatus(false,"CONNECT ERROR",item.address))}
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g:BluetoothGatt,s:Session,c:BluetoothGattCharacteristic){
        g.setCharacteristicNotification(c,true)
        val d=c.getDescriptor(CCCD)
        if(d==null){setLink(s.role,LinkStatus(true,"NOTIFY READY",s.address));return}
        d.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    @SuppressLint("MissingPermission")
    private fun write(s:Session,bytes:ByteArray){
        val g=s.gatt?:return
        val c=s.rx?:return
        try{
            c.writeType=if(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE!=0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value=bytes
            g.writeCharacteristic(c)
        }catch(_:Exception){}
    }

    private fun startVescPoll(s:Session){
        val r=object:Runnable{
            override fun run(){
                if(sessions[s.address]!==s)return
                write(s,byteArrayOf(0x02,0x01,0x04,0x40.toByte(),0x84.toByte(),0x03))
                main.postDelayed(this,250L)
            }
        }
        s.pollRunnable=r
        main.post(r)
    }
    private fun startJbdPoll(s:Session){
        var cell=false
        val r=object:Runnable{
            override fun run(){
                if(sessions[s.address]!==s)return
                val cmd=if(cell)0x04 else 0x03
                val crc=(0x10000-cmd) and 0xffff
                write(s,byteArrayOf(0xDD.toByte(),0xA5.toByte(),cmd.toByte(),0,(crc shr 8).toByte(),crc.toByte(),0x77))
                cell=!cell
                main.postDelayed(this,350L)
            }
        }
        s.pollRunnable=r
        main.post(r)
    }

    private fun receive(s:Session,uuid:UUID,bytes:ByteArray){
        when(s.role){
            DeviceRole.VESC1,DeviceRole.VESC2 -> {
                val data=s.vescParser.feed(bytes)
                if(data!=null){
                    val t=data.copy(updatedAt=System.currentTimeMillis())
                    state=if(s.role==DeviceRole.VESC1)state.copy(vesc1=t)else state.copy(vesc2=t)
                    publish()
                }
            }
            DeviceRole.LIDAR -> {
                state=state.copy(lastRawHex=bytes.take(20).joinToString(" "){ "%02X".format(it) })
                s.ldsAssembler.feed(bytes)
            }
            DeviceRole.HUB -> {
                when(uuid){
                    HUB_IMU -> parseImu(bytes)?.let{state=state.copy(imu=it.copy(updatedAt=System.currentTimeMillis()));publish()}
                    HUB_GPS -> parseGps(bytes)?.let{state=state.copy(gps=it.copy(updatedAt=System.currentTimeMillis()));publish()}
                }
            }
            DeviceRole.BMS -> s.jbdParser.feed(bytes)?.let{state=state.copy(bms=it.copy(updatedAt=System.currentTimeMillis()));publish()}
            else -> state=state.copy(lastRawHex=bytes.joinToString(" "){ "%02X".format(it) }).also{publish()}
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(role:DeviceRole){
        val entries=sessions.filterValues{it.role==role}.toList()
        entries.forEach{(address,s)->
            s.pollRunnable?.let{main.removeCallbacks(it)}
            try{s.gatt?.disconnect();s.gatt?.close()}catch(_:Exception){}
            sessions.remove(address)
        }
        setLink(role,LinkStatus(false,"DISCONNECTED"))
    }
    fun disconnectAll(){DeviceRole.values().forEach{disconnect(it)}}
    private fun setLink(role:DeviceRole,status:LinkStatus){
        state=state.copy(links=state.links.toMutableMap().apply{put(role,status)})
        publish()
    }
    private fun publish(){main.post{changed(state)}}
    fun current()=state

    private fun parseImu(b:ByteArray):ImuTelemetry?{
        if(b.size<20)return null
        fun i16(i:Int)=((b[i].toInt() shl 8) or (b[i+1].toInt() and 255)).toShort().toInt()
        fun u16(i:Int)=((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)
        val flags=b[0].toInt() and 255
        return ImuTelemetry(true,flags and 0x02!=0,flags and 0x04!=0,
            i16(3)/100f,i16(5)/100f,i16(7)/1000f,i16(9)/1000f,
            i16(13)/10f,i16(15)/10f,i16(11)/10f,u16(17)/10f,b[19].toInt().toByte().toFloat())
    }
    private fun parseGps(b:ByteArray):GpsTelemetry?{
        if(b.size<18)return null
        fun u16(i:Int)=((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)
        fun i32(i:Int)=((b[i].toInt() shl 24) or ((b[i+1].toInt() and 255) shl 16) or ((b[i+2].toInt() and 255) shl 8) or (b[i+3].toInt() and 255))
        val flags=b[0].toInt() and 255
        return GpsTelemetry(flags and 1!=0,b[1].toInt() and 255,b[2].toInt()/10f,
            i32(3)/1e7,i32(7)/1e7,u16(11)/100f,u16(13)/10f,((b[15].toInt() shl 8) or (b[16].toInt() and 255)).toShort().toFloat())
    }

    private class VescStreamParser{
        private val buffer=ArrayList<Byte>()
        fun feed(chunk:ByteArray):VescTelemetry?{
            chunk.forEach{buffer.add(it)}
            while(buffer.size>=3){
                val start=buffer[0].toInt() and 255
                val head=when(start){2->2;3->3;else->{buffer.removeAt(0);continue}}
                val len=if(head==2)(buffer[1].toInt() and 255)else (((buffer[1].toInt() and 255) shl 8) or (buffer[2].toInt() and 255))
                val total=head+len+3
                if(total>300){buffer.clear();return null}
                if(buffer.size<total)return null
                if((buffer[total-1].toInt() and 255)!=3){buffer.removeAt(0);continue}
                val payload=ByteArray(len){buffer[head+it]}
                val crc=((buffer[head+len].toInt() and 255) shl 8) or (buffer[head+len+1].toInt() and 255)
                repeat(total){buffer.removeAt(0)}
                if(crc16(payload)==crc && payload.size>=54 && payload[0].toInt()==4){
                    fun s16(i:Int)=(((payload[i].toInt() and 255) shl 8) or (payload[i+1].toInt() and 255)).toShort().toInt()
                    fun s32(i:Int)=((payload[i].toInt() shl 24) or ((payload[i+1].toInt() and 255) shl 16) or ((payload[i+2].toInt() and 255) shl 8) or (payload[i+3].toInt() and 255))
                    return VescTelemetry(true,s16(1)/10f,s16(3)/10f,s32(5)/100f,s32(9)/100f,
                        s16(21)/1000f,s32(23).toFloat(),s16(27)/10f,s32(29)/10000f,s32(37)/10000f,payload[53].toInt() and 255,System.currentTimeMillis())
                }
            }
            return null
        }
        private fun crc16(a:ByteArray):Int{
            var crc=0
            a.forEach{v->crc=crc xor ((v.toInt() and 255) shl 8);repeat(8){crc=if(crc and 0x8000!=0)((crc shl 1) xor 0x1021) and 0xffff else (crc shl 1) and 0xffff}}
            return crc
        }
    }

    private class JbdStreamParser{
        private val buf=ArrayList<Byte>()
        private var latest=BmsTelemetry()
        fun feed(chunk:ByteArray):BmsTelemetry?{
            chunk.forEach{buf.add(it)}
            while(buf.size>=7){
                if((buf[0].toInt() and 255)!=0xDD){buf.removeAt(0);continue}
                val len=buf[3].toInt() and 255
                val total=len+7
                if(buf.size<total)return null
                if((buf[total-1].toInt() and 255)!=0x77){buf.removeAt(0);continue}
                val p=ByteArray(total){buf[it]}
                repeat(total){buf.removeAt(0)}
                var sum=(p[2].toInt() and 255)+(p[3].toInt() and 255)
                for(i in 4 until 4+len)sum+=p[i].toInt() and 255
                val expected=(0x10000-sum) and 0xffff
                val got=((p[4+len].toInt() and 255) shl 8) or (p[5+len].toInt() and 255)
                if(expected!=got)continue
                val cmd=p[1].toInt() and 255
                fun u16(i:Int)=((p[i].toInt() and 255) shl 8) or (p[i+1].toInt() and 255)
                val d=4
                if(cmd==3 && len>=21){
                    val cap=u16(d+6)/100f
                    latest=latest.copy(valid=true,voltage=u16(d)/100f,current=u16(d+2).toShort()/100f,
                        remainingAh=u16(d+4)/100f,capacityAh=cap,soc=(p[d+17].toInt() and 255).toFloat(),
                        protection=u16(d+14),fet=p[d+18].toInt() and 255,
                        temp1=if(len>=23)u16(d+21)/10f-273.15f else Float.NaN,
                        temp2=if(len>=25)u16(d+23)/10f-273.15f else Float.NaN)
                }else if(cmd==4){
                    val count=min(32,len/2)
                    val cells=(0 until count).map{u16(d+it*2)/1000f}
                    latest=latest.copy(cells=cells)
                }else continue
                return latest
            }
            return null
        }
    }

    private class LdsAssembler(private val complete:(LidarScan,Long,Long,Long)->Unit){
        private var sequence=-1
        private var received=BooleanArray(23)
        private var ranges=IntArray(360)
        private var count=0
        private var packets=0L
        private var dropped=0L
        private var bad=0L
        fun feed(p:ByteArray){
            if(p.size!=20 || (p[0].toInt() and 255)!=0xD3){bad++;return}
            val meta=p[1].toInt() and 255
            val seq=meta and 0x0f
            val sector=p[2].toInt() and 255
            if(sector !in 0..22){bad++;return}
            if(sector==0 && seq!=sequence){
                if(count in 1..22)dropped++
                sequence=seq;received=BooleanArray(23);ranges=IntArray(360);count=0
            }else if(sequence==-1){sequence=seq}
            if(seq!=sequence)return
            packets++
            if(!received[sector]){
                received[sector]=true;count++
                val bins=if(sector==22)8 else 16
                for(i in 0 until bins){
                    val v=p[4+i].toInt() and 255
                    ranges[sector*16+i]=if(v==0)0 else v*50
                }
            }
            if(count==23){
                val rpm=(p[3].toInt() and 255)*2
                val duty=((meta ushr 4) and 15)*100/15
                complete(LidarScan(ranges.copyOf(),rpm,duty,seq,System.currentTimeMillis()),packets,dropped,bad)
                count=0
            }
        }
    }
}
