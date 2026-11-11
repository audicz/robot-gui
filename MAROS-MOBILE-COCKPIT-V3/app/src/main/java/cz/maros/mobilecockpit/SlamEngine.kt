package cz.maros.mobilecockpit

import android.os.Debug
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class RobotPose(val x:Float=0f,val y:Float=0f,val headingDeg:Float=0f)
data class SlamMetrics(
    val wallMs:Long=0,val cpuMs:Long=0,val averageMs:Float=0f,val maxMs:Long=0,
    val confidence:Float=0f,val validPoints:Int=0,val scanHz:Float=0f,val droppedScans:Long=0
)
data class SlamSnapshot(
    val pose:RobotPose=RobotPose(),val grid:IntArray=IntArray(SlamEngine.SIZE*SlamEngine.SIZE),
    val path:List<Pair<Int,Int>> = emptyList(),val goal:Pair<Int,Int>?=null,
    val metrics:SlamMetrics=SlamMetrics(),val mapRevision:Long=0
)

/**
 * Android-side 2D scan-to-map engine. 5 cm occupancy cells, bounded local
 * correlative matching, log-odds ray integration and A* path planning.
 * It deliberately reports confidence rather than pretending to have a pose
 * when the scan does not match the current map.
 */
class SlamEngine {
    companion object {
        const val SIZE=256
        const val CELL_M=0.05f
        private const val HALF=SIZE/2
    }
    private val odds=ByteArray(SIZE*SIZE)
    private var pose=RobotPose()
    private var previousScanAt=0L
    private var lastGyroAt=0L
    private var avgMs=0f
    private var maxMs=0L
    private var goal:Pair<Int,Int>?=null
    private var path=emptyList<Pair<Int,Int>>()
    private var revision=0L
    private var dropped=0L

    @Synchronized
    fun process(scan:LidarScan, imu:ImuTelemetry?=null):SlamSnapshot {
        val wallStart=System.nanoTime()
        val cpuStart=Debug.threadCpuTimeNanos()
        val ranges=scan.rangesMm
        val valid=(1..359).count{ranges[it] in 80..12000}
        val dt=if(previousScanAt==0L)0L else scan.receivedAt-previousScanAt
        val scanHz=if(dt>0)1000f/dt else 0f
        previousScanAt=scan.receivedAt
        if(imu!=null && imu.valid && imu.updatedAt>lastGyroAt){
            val gyroDt=if(lastGyroAt==0L)0L else (imu.updatedAt-lastGyroAt).coerceIn(0L,250L)
            if(gyroDt>0L) pose=pose.copy(headingDeg=normalize(pose.headingDeg+imu.gz*gyroDt/1000f))
            lastGyroAt=imu.updatedAt
        }
        val match=if(revision==0L || valid<30) Match(pose,0f) else matchScan(ranges,pose)
        pose=match.pose
        integrate(ranges,pose)
        revision++
        val wallMs=(System.nanoTime()-wallStart)/1_000_000L
        val cpuMs=(Debug.threadCpuTimeNanos()-cpuStart)/1_000_000L
        avgMs=if(avgMs==0f)wallMs.toFloat() else avgMs*0.85f+wallMs*0.15f
        maxMs=max(maxMs,wallMs)
        val metrics=SlamMetrics(wallMs,cpuMs,avgMs,maxMs,match.confidence,valid,scanHz,dropped)
        return SlamSnapshot(pose,occupancyCopy(),path,goal,metrics,revision)
    }

    @Synchronized
    fun setGoal(gridX:Int,gridY:Int):SlamSnapshot {
        goal=Pair(gridX.coerceIn(0,SIZE-1),gridY.coerceIn(0,SIZE-1))
        path=planPath(robotCell(),goal!!)
        return snapshot()
    }

    @Synchronized
    fun clear():SlamSnapshot {
        odds.fill(0);pose=RobotPose();previousScanAt=0;lastGyroAt=0;avgMs=0f;maxMs=0;goal=null;path=emptyList();revision=0
        return snapshot()
    }

    @Synchronized
    fun snapshot()=SlamSnapshot(pose,occupancyCopy(),path,goal,SlamMetrics(maxMs=maxMs),revision)

    private data class Match(val pose:RobotPose,val confidence:Float)
    private fun matchScan(r:IntArray,base:RobotPose):Match {
        var best=base
        var bestScore=Float.NEGATIVE_INFINITY
        var bestHits=0
        val sample=(0..359 step 3).filter{r[it] in 100..8000}
        if(sample.size<12)return Match(base,0f)
        // Search small motion around last pose. This is intentionally bounded
        // so phone CPU cost is predictable at the LDS scan frequency.
        for(dh in -4..4 step 2){
            val heading=base.headingDeg+dh
            val rad=heading*Math.PI.toFloat()/180f
            for(dx in -3..3){
                for(dy in -3..3){
                    val x=base.x+dx*CELL_M
                    val y=base.y+dy*CELL_M
                    var score=0f;var hits=0
                    for(a in sample){
                        val dist=r[a]/1000f
                        val ang=rad+a*Math.PI.toFloat()/180f
                        val wx=x+dist*sin(ang);val wy=y+dist*cos(ang)
                        val idx=worldIndex(wx,wy)
                        if(idx>=0){
                            val o=odds[idx].toInt()
                            score+=when{o>=3->2.0f;o>0->0.7f;o< -2->-0.25f;else->0f}
                            if(o>=2)hits++
                        }
                    }
                    // Slight preference for smaller correction when scores tie.
                    score-=0.015f*(abs(dx)+abs(dy))+0.01f*abs(dh)
                    if(score>bestScore){bestScore=score;best=RobotPose(x,y,normalize(heading));bestHits=hits}
                }
            }
        }
        val confidence=bestHits.toFloat()/sample.size
        return if(confidence>=0.08f)Match(best,confidence) else Match(base,confidence)
    }

    private fun integrate(r:IntArray,p:RobotPose){
        val h=p.headingDeg*Math.PI.toFloat()/180f
        for(a in 0..359){
            val mm=r[a]
            if(mm !in 80..12000)continue
            val distance=mm/1000f
            val angle=h+a*Math.PI.toFloat()/180f
            val ex=p.x+distance*sin(angle)
            val ey=p.y+distance*cos(angle)
            val steps=max(1,(distance/(CELL_M*0.75f)).toInt())
            for(k in 1 until steps){
                val t=k.toFloat()/steps
                val idx=worldIndex(p.x+(ex-p.x)*t,p.y+(ey-p.y)*t)
                if(idx>=0)odds[idx]=(odds[idx].toInt()-1).coerceAtLeast(-6).toByte()
            }
            val end=worldIndex(ex,ey)
            if(end>=0)odds[end]=(odds[end].toInt()+3).coerceAtMost(6).toByte()
        }
    }

    private fun worldIndex(x:Float,y:Float):Int{
        val gx=(HALF+x/CELL_M).toInt()
        val gy=(HALF-y/CELL_M).toInt()
        return if(gx in 0 until SIZE && gy in 0 until SIZE)gy*SIZE+gx else -1
    }
    private fun robotCell()=Pair((HALF+pose.x/CELL_M).toInt().coerceIn(0,SIZE-1),(HALF-pose.y/CELL_M).toInt().coerceIn(0,SIZE-1))
    private fun normalize(a:Float):Float{var v=a%360f;if(v<0)v+=360f;return v}
    private fun occupancyCopy():IntArray=IntArray(odds.size){odds[it].toInt()}

    private data class Node(val x:Int,val y:Int,val g:Float,val f:Float)
    private fun planPath(start:Pair<Int,Int>,end:Pair<Int,Int>):List<Pair<Int,Int>>{
        val startId=start.second*SIZE+start.first;val endId=end.second*SIZE+end.first
        val open=PriorityQueue<Node>(compareBy{it.f})
        val gScore=FloatArray(SIZE*SIZE){Float.POSITIVE_INFINITY}
        val parent=IntArray(SIZE*SIZE){-1}
        val closed=BooleanArray(SIZE*SIZE)
        fun heuristic(x:Int,y:Int)=abs(x-end.first)+abs(y-end.second).toFloat()
        if(odds[endId].toInt() > -2) return emptyList()
        gScore[startId]=0f;open.add(Node(start.first,start.second,0f,heuristic(start.first,start.second)))
        val dirs=arrayOf(intArrayOf(1,0),intArrayOf(-1,0),intArrayOf(0,1),intArrayOf(0,-1))
        var found=false
        while(open.isNotEmpty()){
            val n=open.poll();val id=n.y*SIZE+n.x
            if(closed[id])continue
            if(id==endId){found=true;break}
            closed[id]=true
            for(d in dirs){
                val x=n.x+d[0];val y=n.y+d[1]
                if(x !in 1 until SIZE-1 || y !in 1 until SIZE-1)continue
                val ni=y*SIZE+x
                if(closed[ni])continue
                val occ=odds[ni].toInt()
                // Unknown and occupied cells are never treated as traversable.
                // The map must contain repeated free-space evidence (log-odds <= -2).
                if(occ > -2) continue
                val cost=1f
                val tentative=n.g+cost
                if(tentative<gScore[ni]){
                    gScore[ni]=tentative;parent[ni]=id
                    open.add(Node(x,y,tentative,tentative+heuristic(x,y)))
                }
            }
        }
        if(!found)return emptyList()
        val out=ArrayList<Pair<Int,Int>>();var at=endId;var guard=0
        while(at!=startId && at>=0 && guard++<SIZE*SIZE){
            out.add(Pair(at%SIZE,at/SIZE));at=parent[at]
        }
        out.add(start);out.reverse()
        return out
    }
}
