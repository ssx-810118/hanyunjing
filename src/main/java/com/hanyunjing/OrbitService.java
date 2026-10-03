package com.hanyunjing;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Service
public class OrbitService {
    public interface Provider {boolean available();byte[] generate(byte[] image,Consumer<String> progress)throws Exception;}
    public record Status(boolean ready,String provider,String message){}
    public record Generate(boolean authorized,String retryOf){}
    public record Task(String id,String sourceTaskId,String status,String stage,String modelUrl,String error,Instant expiresAt){}
    private static final long MAX_MEMORY=180L*1024*1024;
    private final TryOnService tryon;private final Provider provider;
    private final Map<String,Entry> entries=new LinkedHashMap<>();private final Map<String,String> latest=new HashMap<>();
    private final ThreadPoolExecutor worker;private long memory;private boolean closed;
    private static final class Entry {
        final String id=UUID.randomUUID().toString(),source,sid;final Instant expires;
        String state="QUEUED",stage="WAITING",error;byte[] model;Future<?> future;
        Entry(String source,String sid,Instant expires){this.source=source;this.sid=sid;this.expires=expires;}
        Task view(){return new Task(id,source,state,stage,model==null?null:"/api/tryon/orbit/tasks/"+id+"/model",error,expires);}
    }
    @org.springframework.beans.factory.annotation.Autowired
    public OrbitService(TryOnService tryon,Tencent3dProvider provider){this(tryon,(Provider)provider);}
    OrbitService(TryOnService tryon,Provider provider){
        this.tryon=tryon;this.provider=provider;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,"hunyuan-3d");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        tryon.onResultRemoved(this::removeSource);
    }
    public Status status(){boolean ready=!closed&&provider.available();return new Status(ready,"tencent-hunyuan-3d-pro",ready?"腾讯混元3D已配置，实际可用性取决于服务权限和额度":"腾讯混元3D尚未配置，请联系管理员完成配置后生成");}
    public Task generate(String source,String sid,Generate request){
        if(request==null||!request.authorized())throw new IllegalArgumentException("请先确认将本次换装图片发送至腾讯云生成3D模型");
        var image=tryon.get(source,sid);if(!image.status().equals("DONE")||image.demo())throw new IllegalStateException("请先完成照片换装，再生成3D环绕");
        Entry entry;
        synchronized(this){
            cleanup();if(!status().ready())throw new AuthService.Failure(503,status().message());
            Entry previous=entries.get(latest.get(source));
            if(previous!=null){
                if(!Set.of("FAILED","CANCELLED").contains(previous.state)||!previous.id.equals(request.retryOf()))return previous.view();
            }else if(request.retryOf()!=null&&!request.retryOf().isBlank())throw new IllegalStateException("上一次任务已失效，请刷新后重试");
            long reserved=entries.values().stream().filter(e->Set.of("QUEUED","RUNNING").contains(e.state)).count()*GlbValidator.MAX_BYTES;
            if(entries.size()>=32||memory+reserved+GlbValidator.MAX_BYTES>MAX_MEMORY)throw new IllegalStateException("3D任务暂存空间已满，请稍后重试");
            if(entries.values().stream().anyMatch(e->e.sid.equals(sid)&&Set.of("QUEUED","RUNNING").contains(e.state)))throw new IllegalStateException("本会话已有3D任务，请等待完成");
            entry=new Entry(source,sid,image.expiresAt());entries.put(entry.id,entry);latest.put(source,entry.id);
            try{entry.future=worker.submit(()->run(entry));}catch(RejectedExecutionException e){entries.remove(entry.id);if(previous==null)latest.remove(source);else latest.put(source,previous.id);throw new IllegalStateException("3D生成队列已满，请稍后重试");}
        }
        // Cover deletion racing with insertion; no orbit lock is held while calling TryOnService.
        try{tryon.get(source,sid);}catch(NoSuchElementException e){removeSource(source);throw e;}
        synchronized(this){return entry.view();}
    }
    private void run(Entry entry){
        byte[] image=null,model=null;
        try{
            synchronized(this){if(!live(entry))return;entry.state="RUNNING";entry.stage="PREPARING";}
            image=tryon.result(entry.source,entry.sid);
            model=provider.generate(image,stage->{synchronized(this){if(live(entry))entry.stage=stage;}});
            GlbValidator.validate(model);
            tryon.get(entry.source,entry.sid);
            synchronized(this){if(!live(entry))return;if(memory+model.length>MAX_MEMORY)throw new IllegalStateException("3D模型暂存空间已满");entry.model=model;memory+=model.length;model=null;entry.state="DONE";entry.stage="COMPLETE";}
        }catch(Exception failure){
            synchronized(this){if(live(entry)){entry.state="FAILED";entry.error=safeError(failure);}}
        }finally{if(image!=null)Arrays.fill(image,(byte)0);if(model!=null)Arrays.fill(model,(byte)0);}
    }
    private static String safeError(Exception e){
        if(e instanceof TimeoutException||e instanceof java.net.http.HttpTimeoutException)return "3D生成等待超时，未自动重试；重新生成可能再次计费";
        if((e instanceof IllegalStateException||e instanceof IllegalArgumentException)&&e.getMessage()!=null&&e.getMessage().matches("^(腾讯|3D|换装).{1,120}$"))return e.getMessage();
        return "3D生成未完成，请稍后手动重试；已提交的请求可能仍在处理或计费";
    }
    private boolean live(Entry e){return !closed&&entries.get(e.id)==e&&e.expires.isAfter(Instant.now())&&Set.of("QUEUED","RUNNING").contains(e.state);}
    private synchronized Entry owned(String id,String sid){cleanup();var e=entries.get(id);if(e==null||!e.sid.equals(sid))throw new NoSuchElementException();return e;}
    public Task latest(String source,String sid){tryon.get(source,sid);synchronized(this){cleanup();var e=entries.get(latest.get(source));return e==null?null:e.view();}}
    public Task get(String id,String sid){var e=owned(id,sid);tryon.get(e.source,sid);synchronized(this){return owned(id,sid).view();}}
    public byte[] model(String id,String sid){var e=owned(id,sid);tryon.get(e.source,sid);synchronized(this){e=owned(id,sid);if(!e.state.equals("DONE")||e.model==null)throw new IllegalStateException("3D模型尚未完成或已取消");return e.model.clone();}}
    public void cancel(String id,String sid){var e=owned(id,sid);tryon.get(e.source,sid);synchronized(this){e=owned(id,sid);clear(e);e.state="CANCELLED";e.error=null;}}
    private void clear(Entry e){if(e.future!=null)e.future.cancel(true);if(e.model!=null){memory-=e.model.length;Arrays.fill(e.model,(byte)0);e.model=null;}}
    private synchronized void removeSource(String source){var all=new ArrayList<>(entries.values());for(var e:all)if(e.source.equals(source)){clear(e);entries.remove(e.id);}latest.remove(source);worker.purge();}
    @Scheduled(fixedDelay=5000) public synchronized void cleanup(){for(var e:new ArrayList<>(entries.values()))if(!e.expires.isAfter(Instant.now()))removeSource(e.source);}
    @PreDestroy public synchronized void close(){closed=true;for(var e:entries.values())clear(e);entries.clear();latest.clear();worker.shutdownNow();}
}
