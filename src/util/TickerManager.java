package util;

import java.io.Closeable;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by Kelles on 2017/5/18.
 */
//线程安全
public class TickerManager implements Closeable{
    static Util util=Settings.util;
    ScheduledThreadPoolExecutor threadPool=new ScheduledThreadPoolExecutor(3);
    Map<String,Ticker> map=new ConcurrentHashMap<String,Ticker>();
    Random random=new Random();

    public boolean put(Runnable onSuccess,Runnable onTimeout,long timeout){
        String key=null;
        for (;;){
            key=String.valueOf(random.nextInt(1000000)+100000);
            if (!map.containsKey(key)) break;
        }
        return put(key,onSuccess,onTimeout,timeout);
    }
    public synchronized boolean put(String key,Runnable onSuccess,Runnable onTimeout,long timeout){
        if (key==null || "".equals(key)) return false;
        if (threadPool.isShutdown()){
            util.log("TickerManager Has Already Been Closed, Reject Put");
            return false;
        }
        Ticker ticker =new Ticker(key,onSuccess,onTimeout);
        if (map.containsKey(key)) return false;
        map.put(key, ticker);
        ScheduledFuture<?> future=threadPool.schedule(new ScheduleRunnable(ticker),timeout, TimeUnit.MILLISECONDS);
        ticker.future=future;
        return true;
    }

    public boolean tick(String key){
        Ticker ticker =map.remove(key);
        if (ticker ==null) return false;
        ticker.ticked =true;
        if (ticker.future!=null) ticker.future.cancel(true);
        if (ticker.onSuccess!=null) threadPool.execute(ticker.onSuccess);
        return true;
    }

    public void tickAll(){
        for (String key:map.keySet())
            tick(key);
    }

    class ScheduleRunnable implements Runnable{
        Ticker ticker;
        public ScheduleRunnable(Ticker ticker) {
            this.ticker = ticker;
        }
        @Override
        public void run() {
            if (ticker ==null) return;
            if (ticker.ticked) return;
            else{
                map.remove(ticker.key);
                if (ticker.onTimeout!=null) threadPool.execute(ticker.onTimeout);
            }
        }
    }

    class Ticker {
        String key;
        volatile boolean ticked =false;
        Runnable onSuccess,onTimeout;
        ScheduledFuture<?> future;

        public Ticker(String key, Runnable onSuccess, Runnable onTimeout) {
            this.key = key;
            this.onSuccess = onSuccess;
            this.onTimeout = onTimeout;
        }
    }

    @Override
    public synchronized void close(){
        if (threadPool!=null) threadPool.shutdown();
    }
}
