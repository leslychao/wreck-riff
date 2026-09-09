package game.wreckriff.diagnostics;

import java.util.*;

/** Bounded 0.05 ms histogram includes every frame; extrema are exact. */
public final class FrameMetrics {
    private final long[] histogram=new long[20001];
    private long count,over33ms,over50ms,over100ms;
    private double seconds,maximum;
    public void add(double frameSeconds) {
        if(!Double.isFinite(frameSeconds)||frameSeconds<0) throw new IllegalArgumentException("Invalid frame duration");
        double milliseconds=frameSeconds*1000;
        histogram[Math.min(histogram.length-1,(int)Math.ceil(milliseconds*20))]++;
        count++;seconds+=frameSeconds;maximum=Math.max(maximum,milliseconds);
        if(milliseconds>33)over33ms++;
        if(milliseconds>50)over50ms++;
        if(milliseconds>100) over100ms++;
    }
    public long count() { return count; }
    public double seconds() {return seconds;}
    public double maximumMilliseconds() {return maximum;}
    public double percentileMilliseconds(double fraction) {
        if(!Double.isFinite(fraction)||fraction<0||fraction>1)throw new IllegalArgumentException("Invalid percentile");
        return count==0?0:percentile(fraction);
    }
    private double percentile(double fraction) {
        long threshold=(long)Math.ceil(count*fraction),sum=0;
        for(int i=0;i<histogram.length;i++) { sum+=histogram[i];if(sum>=threshold)return i/20.0; }
        return 0;
    }
    public Map<String,Object> snapshot() {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("frames",count);data.put("sampleSeconds",seconds);data.put("histogramPrecisionMs",0.05);
        if(count>0) {
            data.put("medianFrameMs",percentile(.5));data.put("p95FrameMs",percentile(.95));
            data.put("p99FrameMs",percentile(.99));data.put("maxFrameMs",maximum);data.put("framesOver100ms",over100ms);
            data.put("framesOver33ms",over33ms);data.put("framesOver50ms",over50ms);
        }
        return data;
    }
    public boolean withinTarget() { return count>0&&percentile(.95)<=16.7&&percentile(.99)<=25&&over100ms==0; }
}
