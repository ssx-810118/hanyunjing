package com.hanyunjing;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;
import java.util.*;

@Component
public class TraceBus {
    private record Subscriber(String session,String externalSession,SseEmitter emitter) {}
    private final Deque<Models.TraceEvent> events=new ArrayDeque<>();
    private final List<Subscriber> subscribers=new ArrayList<>();
    private long sequence;
    public synchronized Models.TraceEvent publish(String sid,String type,String summary){
        Models.TraceEvent event=new Models.TraceEvent(++sequence,sid,type,summary,Instant.now());events.addLast(event);while(events.size()>5000)events.removeFirst();
        for(var sub:new ArrayList<>(subscribers))if(sub.session()==null||sub.session().equals(sid))send(sub,event);
        return event;
    }
    private void send(Subscriber sub,Models.TraceEvent event){try{var outward=sub.externalSession()==null?event:new Models.TraceEvent(event.id(),sub.externalSession(),event.type(),event.summary(),event.timestamp());sub.emitter().send(SseEmitter.event().id(Long.toString(event.id())).name("trace").data(Models.Api.ok(outward)));}catch(Exception e){subscribers.remove(sub);sub.emitter().complete();}}
    public synchronized List<Models.TraceEvent> events(String sid,long after){return events.stream().filter(e->e.id()>after&&(sid==null||sid.equals(e.sessionId()))).toList();}
    public synchronized SseEmitter subscribe(String sid,long after){return subscribe(sid,after,null);}
    public synchronized SseEmitter subscribe(String sid,long after,String externalSession){
        if(subscribers.size()>=100)throw new IllegalStateException("SSE连接数已达上限");
        SseEmitter emitter=new SseEmitter(120000L);Subscriber sub=new Subscriber(sid,externalSession,emitter);subscribers.add(sub);
        emitter.onCompletion(()->remove(sub));emitter.onTimeout(()->{remove(sub);emitter.complete();});emitter.onError(e->remove(sub));
        // Flush the SSE response immediately.  Without a first frame an empty
        // trace stream stays pending in the browser, so the panel can remain
        // on "连接中" until the first model event or the heartbeat.
        try { emitter.send(SseEmitter.event().comment("connected")); }
        catch(Exception e) { subscribers.remove(sub); emitter.complete(); return emitter; }
        for(var event:events(sid,after))send(sub,event);return emitter;
    }
    private synchronized void remove(Subscriber s){subscribers.remove(s);}
    public synchronized void clear(String sid){events.removeIf(e->sid==null||e.sessionId().equals(sid));}
    @Scheduled(fixedDelay=30000) public synchronized void cleanup(){events.removeIf(e->e.timestamp().isBefore(Instant.now().minusSeconds(3600)));for(var sub:new ArrayList<>(subscribers))try{sub.emitter().send(SseEmitter.event().comment("heartbeat"));}catch(Exception e){remove(sub);}}
}
