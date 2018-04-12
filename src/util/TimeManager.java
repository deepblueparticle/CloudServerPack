package util;

import com.google.gson.Gson;
import util.Settings;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Created by Kelles on 2017/5/18.
 */
//计时器;线程安全
public class TimeManager {

    Map<String,Timer> map=new HashMap<String, Timer>();

    public synchronized long getTotalTime(String key){
        if (key==null || "".equals(key)) return -1;
        Timer timer=map.get(key);
        if (timer==null) return 0;
        timer.refresh();
        return timer.getTotalTime();
    }

    public synchronized String getMessage(String key){
        if (key==null || "".equals(key)) return null;
        Timer timer=map.get(key);
        if (timer==null) return "未运行";
        long totalTime=timer.getTotalTime();
        final long originalTotalTime=totalTime;
        StringBuilder sb=new StringBuilder();
        //天
        long days=TimeUnit.DAYS.convert(totalTime,TimeUnit.MILLISECONDS);
        if (days>0){
            totalTime-=TimeUnit.MILLISECONDS.convert(days,TimeUnit.DAYS);
            sb.append(days+"天");
        }
        //小时
        long hours=TimeUnit.HOURS.convert(totalTime,TimeUnit.MILLISECONDS);
        if (hours>0){
            totalTime-=TimeUnit.MILLISECONDS.convert(hours,TimeUnit.HOURS);
            sb.append(hours+"小时");
        }
        //分钟
        long minutes=TimeUnit.MINUTES.convert(totalTime,TimeUnit.MILLISECONDS);
        if (minutes>0){
            totalTime-=TimeUnit.MILLISECONDS.convert(minutes,TimeUnit.MINUTES);
            sb.append(minutes+"分钟");
        }
        //秒
        long seconds=TimeUnit.SECONDS.convert(totalTime,TimeUnit.MILLISECONDS);
        if (seconds>0){
            totalTime-=TimeUnit.MILLISECONDS.convert(seconds,TimeUnit.SECONDS);
            sb.append(seconds+"秒");
        }
        if (days>0 || hours>0 || minutes>0)
            sb.append(" [ "+TimeUnit.SECONDS.convert(originalTotalTime,TimeUnit.MILLISECONDS)+"秒 ]");
        if ("".equals(sb.toString().trim())) return "未运行";
        return sb.toString();
    }

    public synchronized void resume(String key){
        if (key==null || "".equals(key)) return;
        Timer timer=map.get(key);
        if (timer==null){
            timer=new Timer(key);
            map.put(key,timer);
        }
        timer.resume();
    }

    public synchronized void pause(String key){
        if (key==null || "".equals(key)) return;
        Timer timer=map.get(key);
        if (timer==null){
            timer=new Timer(key);
            map.put(key,timer);
        }
        timer.pause();
    }

    public synchronized void reset(String key){
        if (key==null || "".equals(key)) return;
        Timer timer=map.get(key);
        if (timer==null) return;
        timer.reset();
    }

    static class Timer{
        String key =null;
        long totalTime=0, lastModifyTime =-1;
        public Timer(String key) {
            this.key = key;
        }
        void refresh(){
            if (lastModifyTime<0) return;
            long currentTime=System.currentTimeMillis();
            if (lastModifyTime<currentTime)
                totalTime+=currentTime-lastModifyTime;
            lastModifyTime=currentTime;
        }
        void reset(){
            totalTime=0;
            lastModifyTime=-1;
        }
        void resume(){
            refresh();
            if (lastModifyTime<0) lastModifyTime=System.currentTimeMillis();
        }
        void pause(){
            refresh();
            lastModifyTime=-1;
        }
        long getTotalTime(){
            refresh();
            return totalTime;
        }
    }
}
