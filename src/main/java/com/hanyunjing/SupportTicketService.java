package com.hanyunjing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Local support inbox. A saved inquiry does not imply a live operator is connected. */
@Service
public class SupportTicketService {
    static final Set<String> CATEGORIES = Set.of("商品咨询", "尺码选择", "试穿问题", "订单售后", "其他问题");
    public record Ticket(String id, String number, String category, String productId, String orderId, String message, String status, Instant createdAt) {}
    record Stored(String accountId, String clientId, Ticket ticket) {}
    public record Snapshot(int version, List<Stored> tickets) {}
    private final ObjectMapper json;
    private final Path path;
    private List<Stored> tickets = List.of();
    private CommerceStore database;
    @Autowired public SupportTicketService(ObjectMapper json, @Value("${app.support.store:.local/support-store.json}") String path, CommerceStore database, CoreService core) {
        this(json, Path.of(path).toAbsolutePath().normalize());
        this.database=database;
        database.tx(()->{if(!database.migrated("legacy-support-v1")){for(var s:tickets)insert(s.accountId(),s.clientId(),s.ticket());database.migration("legacy-support-v1");}return null;});
    }
    public SupportTicketService(ObjectMapper json,String path){this(json,Path.of(path).toAbsolutePath().normalize());}
    SupportTicketService(ObjectMapper json, Path path) {
        this.json = json; this.path = path;
        if (path != null && Files.exists(path)) {
            try {
                if (Files.size(path) > 16L * 1024 * 1024) throw new IOException("Store too large");
                Snapshot saved = json.readValue(path.toFile(), Snapshot.class);
                if (saved.version() != 1 || saved.tickets() == null) throw new IOException("Invalid store");
                tickets = List.copyOf(saved.tickets());
            } catch (IOException | RuntimeException e) { throw new IllegalStateException("客服留言读取失败，未重置原有记录"); }
        }
    }
    synchronized List<Ticket> list(String accountId) {
        if(database!=null)return database.jdbc.query("SELECT * FROM support_ticket WHERE account_id=? ORDER BY created_at DESC",(r,n)->read(r),accountId);
        return tickets.stream().filter(t -> t.accountId().equals(accountId)).map(Stored::ticket).sorted(Comparator.comparing(Ticket::createdAt).reversed()).toList();
    }
    synchronized Ticket create(String accountId, String clientId, String category, String productId, String orderId, String message) {
        if (accountId == null || accountId.isBlank()) throw new AuthService.Failure(401, "请先登录后再提交留言");
        if (clientId == null || !clientId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("留言编号无效，请刷新后重试");
        if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("请选择有效的问题类型");
        String clean = message == null ? "" : message.trim();
        if (clean.length() < 5 || clean.length() > 1000) throw new IllegalArgumentException("请填写5至1000字的问题描述");
        Stored previous = database==null?tickets.stream().filter(t -> t.accountId().equals(accountId) && t.clientId().equals(clientId)).findFirst().orElse(null):database.jdbc.query("SELECT * FROM support_ticket WHERE account_id=? AND client_id=?",(r,n)->new Stored(accountId,clientId,read(r)),accountId,clientId).stream().findFirst().orElse(null);
        if (previous != null) {
            Ticket t = previous.ticket();
            if (!Objects.equals(t.category(), category) || !Objects.equals(t.productId(), productId) || !Objects.equals(t.orderId(), orderId) || !t.message().equals(clean))
                throw new AuthService.Failure(409, "留言内容已变化，请刷新记录后重新提交");
            return t;
        }
        if (list(accountId).size() >= 100) throw new AuthService.Failure(429, "当前账号已有100条留言，请先查看已有记录");
        String id = UUID.randomUUID().toString();
        Ticket ticket = new Ticket(id, "KF" + id.substring(0,8).toUpperCase(Locale.ROOT), category, productId, orderId, clean, "RECORDED", Instant.now());
        if(database!=null){insert(accountId,clientId,ticket);return ticket;}
        var next = new ArrayList<>(tickets); next.add(new Stored(accountId, clientId, ticket));
        persist(List.copyOf(next));
        return ticket;
    }
    static Ticket read(java.sql.ResultSet r)throws java.sql.SQLException {
        return new Ticket(r.getString("id"),r.getString("ticket_number"),r.getString("category"),r.getString("product_id"),r.getString("order_id"),r.getString("message"),r.getString("status"),Instant.parse(r.getString("created_at")));
    }
    private void insert(String account,String client,Ticket t) {
        database.jdbc.update("INSERT INTO support_ticket(id,account_id,client_id,ticket_number,category,product_id,order_id,message,status,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",t.id(),account,client,t.number(),t.category(),t.productId(),t.orderId(),t.message(),t.status(),t.createdAt().toString());
    }
    private void persist(List<Stored> next) {
        if (path == null) { tickets = next; return; }
        Path temp = null;
        try {
            Files.createDirectories(path.getParent());
            temp = Files.createTempFile(path.getParent(), ".support-", ".tmp");
            Files.write(temp, json.writeValueAsBytes(new Snapshot(1, next)));
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tickets = next;
        } catch (IOException e) { throw new AuthService.Failure(503, "留言尚未保存成功，请稍后重试"); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {} }
    }
}
