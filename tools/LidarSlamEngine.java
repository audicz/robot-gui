package cz.maros.mobile;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lightweight 2-D LDS frontend.
 * Assembles the MAROS D3 23-fragment scan, filters isolated range spikes,
 * estimates inter-scan motion by coarse correlative matching, accumulates
 * a world-frame point map and extracts wall segments/right-angle corners.
 */
final class LidarSlamEngine {
    private static final int BINS = 360, FRAGMENTS = 23;
    private static final float MAX_RANGE = 8.0f, MIN_RANGE = 0.12f;
    private final int[] ranges = new int[BINS];
    private final boolean[] received = new boolean[FRAGMENTS];
    private boolean collecting = false;
    private int receivedCount = 0;
    private int[] filtered = new int[BINS];
    private float[] prevX = new float[BINS], prevY = new float[BINS];
    private boolean[] prevValid = new boolean[BINS];
    private boolean hasPrevious = false;
    private float poseX = 0, poseY = 0, poseYaw = 0;
    private int completedScans = 0, droppedScans = 0;
    private final ArrayList<PointF> map = new ArrayList<>();
    private final ArrayList<PointF> trail = new ArrayList<>();
    private final ArrayList<Segment> walls = new ArrayList<>();
    private final ArrayList<PointF> corners = new ArrayList<>();

    private static final class Segment {
        final float x1,y1,x2,y2;
        Segment(float a,float b,float c,float d){x1=a;y1=b;x2=c;y2=d;}
    }

    synchronized boolean feed(byte[] packet) {
        if (packet == null || packet.length != 20 || (packet[0] & 255) != 0xD3) return false;
        int fragment = packet[2] & 255;
        if (fragment >= FRAGMENTS) { droppedScans++; return false; }
        // Fragment zero is the unambiguous start marker. Keep the sequence nibble
        // as telemetry only; some bridge revisions increment it per notification.
        if (fragment == 0) {
            if (collecting && receivedCount != FRAGMENTS) droppedScans++;
            Arrays.fill(received, false);
            receivedCount = 0;
            collecting = true;
        }
        if (!collecting || received[fragment]) return false;
        int offset = fragment * 16;
        int count = Math.min(16, BINS - offset);
        for (int i=0;i<count;i++) ranges[offset+i] = packet[4+i] & 255;
        received[fragment] = true;
        receivedCount++;
        if (receivedCount != FRAGMENTS) return false;
        collecting = false;
        processScan();
        return true;
    }

    private void processScan() {
        completedScans++;
        for (int i=0;i<BINS;i++) {
            int v=ranges[i], a=ranges[(i+BINS-1)%BINS], b=ranges[(i+1)%BINS];
            // Remove a single-bin spike when both neighbours agree.
            if (v>0 && a>0 && b>0 && Math.abs(v-a)>12 && Math.abs(v-b)>12 && Math.abs(a-b)<5)
                filtered[i]=(a+b)/2;
            else filtered[i]=v;
        }
        float[] sx=new float[BINS], sy=new float[BINS];
        boolean[] valid=new boolean[BINS];
        for(int i=0;i<BINS;i++){
            float d=filtered[i]*0.05f;
            if(d<MIN_RANGE || d>MAX_RANGE) continue;
            double a=Math.toRadians(i);
            // LDS zero points forward; screen/world Y is positive forward.
            sx[i]=(float)(Math.sin(a)*d);
            sy[i]=(float)(Math.cos(a)*d);
            valid[i]=true;
        }
        if(hasPrevious) estimateMotion(sx,sy,valid);
        addMap(sx,sy,valid);
        extractGeometry(sx,sy,valid);
        if(completedScans==1 || trail.isEmpty() ||
                Math.hypot(poseX-trail.get(trail.size()-1).x,poseY-trail.get(trail.size()-1).y)>0.025f ||
                trail.size()>0 && Math.abs(wrap(poseYaw-lastTrailYaw))>Math.toRadians(1.5)) {
            trail.add(new PointF(poseX,poseY));
            if(trail.size()>2500) trail.remove(0);
            lastTrailYaw=poseYaw;
        }
        System.arraycopy(sx,0,prevX,0,BINS);
        System.arraycopy(sy,0,prevY,0,BINS);
        System.arraycopy(valid,0,prevValid,0,BINS);
        hasPrevious=true;
    }
    private float lastTrailYaw=0;

    private void estimateMotion(float[] x,float[] y,boolean[] valid) {
        float best=Float.MAX_VALUE, bestX=0,bestY=0,bestA=0;
        // Coarse bounded search: enough for adjacent revolutions at normal robot speed.
        for(int iy=-3;iy<=3;iy++) for(int ix=-3;ix<=3;ix++) for(int ia=-3;ia<=3;ia++) {
            float dx=ix*0.05f, dy=iy*0.05f, da=(float)Math.toRadians(ia*2.0);
            float ca=(float)Math.cos(da), sa=(float)Math.sin(da);
            double sum=0; int used=0;
            for(int i=0;i<BINS;i+=4) {
                if(!valid[i]) continue;
                float tx=dx+ca*x[i]-sa*y[i], ty=dy+sa*x[i]+ca*y[i];
                float nearest=0.36f;
                for(int j=0;j<BINS;j+=3) if(prevValid[j]) {
                    float ex=tx-prevX[j], ey=ty-prevY[j], q=ex*ex+ey*ey;
                    if(q<nearest) nearest=q;
                }
                if(nearest<0.36f){sum+=nearest;used++;}
            }
            if(used>=18) {
                float score=(float)(sum/used) + (used<35?0.03f:0);
                if(score<best){best=score;bestX=dx;bestY=dy;bestA=da;}
            }
        }
        if(best<Float.MAX_VALUE && best<0.10f) {
            float c=(float)Math.cos(poseYaw), s=(float)Math.sin(poseYaw);
            poseX += c*bestX-s*bestY;
            poseY += s*bestX+c*bestY;
            poseYaw=wrap(poseYaw+bestA);
        }
    }

    private void addMap(float[] x,float[] y,boolean[] valid) {
        float c=(float)Math.cos(poseYaw),s=(float)Math.sin(poseYaw);
        for(int i=0;i<BINS;i+=2) if(valid[i]) {
            float wx=poseX+c*x[i]-s*y[i], wy=poseY+s*x[i]+c*y[i];
            map.add(new PointF(wx,wy));
        }
        if(map.size()>9000) map.subList(0,map.size()-9000).clear();
    }

    private void extractGeometry(float[] x,float[] y,boolean[] valid) {
        ArrayList<PointF> cluster=new ArrayList<>();
        ArrayList<Segment> scanLines=new ArrayList<>();
        for(int i=0;i<=BINS;i++) {
            boolean keep=i<BINS && valid[i];
            PointF q=keep?new PointF(x[i],y[i]):null;
            if(keep && !cluster.isEmpty()) {
                PointF z=cluster.get(cluster.size()-1);
                if(Math.hypot(q.x-z.x,q.y-z.y)>0.16) keep=false;
            }
            if(keep) cluster.add(q);
            else {
                if(cluster.size()>=5) {
                    Segment line=fitLine(cluster);
                    if(line!=null) scanLines.add(line);
                }
                cluster.clear();
                if(i<BINS && valid[i]) cluster.add(q);
            }
        }
        float c=(float)Math.cos(poseYaw),s=(float)Math.sin(poseYaw);
        for(Segment l:scanLines) {
            walls.add(new Segment(poseX+c*l.x1-s*l.y1,poseY+s*l.x1+c*l.y1,
                    poseX+c*l.x2-s*l.y2,poseY+s*l.x2+c*l.y2));
        }
        if(walls.size()>700) walls.subList(0,walls.size()-700).clear();
        for(int i=1;i<scanLines.size();i++) {
            Segment a=scanLines.get(i-1),b=scanLines.get(i);
            float ax=a.x2-a.x1, ay=a.y2-a.y1, bx=b.x2-b.x1, by=b.y2-b.y1;
            double na=Math.hypot(ax,ay), nb=Math.hypot(bx,by);
            if(na<0.25 || nb<0.25) continue;
            double cos=Math.abs((ax*bx+ay*by)/(na*nb));
            if(cos>Math.cos(Math.toRadians(12))) continue;
            PointF p=intersection(a,b);
            if(p!=null && Math.hypot(p.x-a.x2,p.y-a.y2)<0.35) {
                corners.add(new PointF(poseX+c*p.x-s*p.y,poseY+s*p.x+c*p.y));
            }
        }
        if(corners.size()>500) corners.subList(0,corners.size()-500).clear();
    }

    private Segment fitLine(List<PointF> pts) {
        PointF first=pts.get(0),last=pts.get(pts.size()-1);
        float dx=last.x-first.x,dy=last.y-first.y,len=(float)Math.hypot(dx,dy);
        if(len<0.20f) return null;
        float nx=-dy/len,ny=dx/len,max=0;
        for(PointF p:pts) max=Math.max(max,Math.abs((p.x-first.x)*nx+(p.y-first.y)*ny));
        if(max>0.07f) return null;
        return new Segment(first.x,first.y,last.x,last.y);
    }
    private PointF intersection(Segment a,Segment b) {
        float x1=a.x1,y1=a.y1,x2=a.x2,y2=a.y2,x3=b.x1,y3=b.y1,x4=b.x2,y4=b.y2;
        float den=(x1-x2)*(y3-y4)-(y1-y2)*(x3-x4);
        if(Math.abs(den)<1e-5) return null;
        float d1=d3(x1,y1,x2,y2),d2=d3(x3,y3,x4,y4);
        return new PointF((d1*(x3-x4)-(x1-x2)*d2)/den,(d1*(y3-y4)-(y1-y2)*d2)/den);
    }
    private float d3(float x1,float y1,float x2,float y2){return x1*y2-y1*x2;}
    private static float wrap(float a){while(a>(float)Math.PI)a-=2*(float)Math.PI;while(a<-(float)Math.PI)a+=2*(float)Math.PI;return a;}

    synchronized void reset(){map.clear();trail.clear();walls.clear();corners.clear();hasPrevious=false;poseX=poseY=poseYaw=0;completedScans=0;droppedScans=0;collecting=false;receivedCount=0;}
    synchronized int scans(){return completedScans;}
    synchronized int dropped(){return droppedScans;}
    synchronized int points(){return map.size();}

    synchronized void draw(Canvas canvas,float width,float height,Paint p) {
        if(width<20||height<20)return;
        float minX=poseX-1,maxX=poseX+1,minY=poseY-1,maxY=poseY+1;
        for(PointF q:map){minX=Math.min(minX,q.x);maxX=Math.max(maxX,q.x);minY=Math.min(minY,q.y);maxY=Math.max(maxY,q.y);}
        for(PointF q:trail){minX=Math.min(minX,q.x);maxX=Math.max(maxX,q.x);minY=Math.min(minY,q.y);maxY=Math.max(maxY,q.y);}
        float dx=Math.max(1.5f,maxX-minX),dy=Math.max(1.5f,maxY-minY);
        float scale=Math.min((width-42)/dx,(height-58)/dy);
        float cx=(minX+maxX)*0.5f,cy=(minY+maxY)*0.5f;
        float ox=width*0.5f-cx*scale,oy=height*0.5f+cy*scale+8;
        // A shared transform is used for scan points, wall fits, trail and robot pose.
        p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(77,198,255));p.setStrokeWidth(2.2f);
        for(PointF q:map)canvas.drawCircle(ox+q.x*scale,oy-q.y*scale,1.8f,p);
        p.setColor(Color.rgb(65,220,155));p.setStrokeWidth(3f);p.setStyle(Paint.Style.STROKE);
        for(Segment l:walls)canvas.drawLine(ox+l.x1*scale,oy-l.y1*scale,ox+l.x2*scale,oy-l.y2*scale,p);
        p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(255,190,70));
        for(PointF q:corners)canvas.drawCircle(ox+q.x*scale,oy-q.y*scale,4.5f,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);p.setColor(Color.rgb(255,145,75));
        for(int i=1;i<trail.size();i++){PointF a=trail.get(i-1),b=trail.get(i);canvas.drawLine(ox+a.x*scale,oy-a.y*scale,ox+b.x*scale,oy-b.y*scale,p);}
        float rx=ox+poseX*scale,ry=oy-poseY*scale;
        p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);canvas.drawCircle(rx,ry,7,p);
        float hx=(float)Math.sin(poseYaw),hy=(float)Math.cos(poseYaw);
        p.setColor(Color.rgb(64,220,155));p.setStrokeWidth(4);canvas.drawLine(rx,ry,rx+hx*22,ry-hy*22,p);
        Path nose=new Path();nose.moveTo(rx+hx*22,ry-hy*22);nose.lineTo(rx+hx*11-hy*7,ry-hy*11-hx*7);nose.lineTo(rx+hx*11+hy*7,ry-hy*11+hx*7);nose.close();canvas.drawPath(nose,p);
        p.setStyle(Paint.Style.FILL);p.setColor(Color.LTGRAY);p.setTextSize(12);
        canvas.drawText(String.format(java.util.Locale.US,"SLAM scans %d  points %d  scale %.0f px/m",completedScans,map.size(),scale),12,20,p);
        canvas.drawText(String.format(java.util.Locale.US,"X %.2f m  Y %.2f m  yaw %.1f°",poseX,poseY,Math.toDegrees(poseYaw)),12,height-12,p);
    }
}
