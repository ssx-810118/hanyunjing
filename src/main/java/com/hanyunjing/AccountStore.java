package com.hanyunjing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Local account/order storage. Publish in-memory state only after an atomic file replacement. */
@Component
public class AccountStore {
    private static final Logger LOG = LoggerFactory.getLogger(AccountStore.class);
    @FunctionalInterface interface AtomicReplace { void replace(Path source, Path target) throws IOException; }
    public record Account(String id, String username, String displayName, String salt,
                          String passwordHash, int iterations, Instant createdAt) {
        Models.User user() { return new Models.User(id, username, displayName); }
    }
    public record StoredOrder(String accountId, String idempotencyKey, Models.Order order) {}
    public record Snapshot(int version, List<Account> accounts, List<StoredOrder> orders) {}
    private final ObjectMapper json;
    private final Path path;
    private final AtomicReplace atomicReplace;
    private Snapshot snapshot;
    private CommerceStore database;

    @Autowired
    public AccountStore(ObjectMapper json, @Value("${app.account.store:.local/account-store.json}") String path, CommerceStore database) {
        this(json, Path.of(path).toAbsolutePath().normalize());
        database.importAccounts(snapshot);
        this.database = database;
    }
    public AccountStore(ObjectMapper json, String path) { this(json, Path.of(path).toAbsolutePath().normalize()); }
    AccountStore(ObjectMapper json, Path path) {
        this(json, path, (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }
    AccountStore(ObjectMapper json, Path path, AtomicReplace atomicReplace) {
        this.json = json; this.path = path; this.atomicReplace = atomicReplace;
        snapshot = new Snapshot(1, List.of(), List.of());
        if (path != null && Files.exists(path)) {
            try {
                if (Files.size(path) > 32L * 1024 * 1024) throw new IOException("store too large");
                Snapshot saved = json.readValue(path.toFile(), Snapshot.class);
                if (saved.version() != 1 || saved.accounts() == null || saved.orders() == null)
                    throw new IOException("invalid store");
                snapshot = new Snapshot(1, List.copyOf(saved.accounts()), List.copyOf(saved.orders()));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("账号与订单存储读取失败，请检查本地存储文件；未重置现有数据");
            }
        }
    }
    static AccountStore inMemory() { return new AccountStore(new ObjectMapper().findAndRegisterModules(), (Path)null); }
    synchronized Account account(String username) {
        if(database != null) return database.account(username);
        return snapshot.accounts().stream().filter(a -> a.username().equalsIgnoreCase(username)).findFirst().orElse(null);
    }
    synchronized void addAccount(Account account) {
        if(database != null) { database.insertAccount(account); return; }
        if (account(account.username()) != null) throw new AuthService.Failure(409, "该用户名已注册，请登录或换一个用户名");
        var accounts = new ArrayList<>(snapshot.accounts()); accounts.add(account);
        persist(new Snapshot(1, List.copyOf(accounts), snapshot.orders()));
    }
    synchronized StoredOrder byKey(String accountId, String key) {
        if(database != null) return database.byKey(accountId,key);
        return snapshot.orders().stream().filter(o -> o.accountId().equals(accountId) && o.idempotencyKey().equals(key)).findFirst().orElse(null);
    }
    synchronized List<Models.Order> orders(String accountId) {
        if(database != null) return database.orders(accountId);
        return snapshot.orders().stream().filter(o -> o.accountId().equals(accountId)).map(StoredOrder::order)
            .sorted(Comparator.comparing(Models.Order::createdAt).reversed()).toList();
    }
    synchronized List<StoredOrder> allOrders() { return database != null ? database.storedOrders() : List.copyOf(snapshot.orders()); }
    synchronized void addOrder(StoredOrder order) {
        if(database != null) { database.tx(()->{database.insertOrder(order);return null;});return; }
        if (byKey(order.accountId(), order.idempotencyKey()) != null) throw new IllegalStateException("订单已提交，请刷新订单列表");
        var orders = new ArrayList<>(snapshot.orders()); orders.add(order);
        persist(new Snapshot(1, snapshot.accounts(), List.copyOf(orders)));
    }
    synchronized void updateOrder(String accountId, Models.Order replacement) {
        if(database != null) { database.updateOrder(accountId,replacement);return; }
        var orders = new ArrayList<>(snapshot.orders());
        for (int i = 0; i < orders.size(); i++) {
            var previous = orders.get(i);
            if (previous.accountId().equals(accountId) && previous.order().id().equals(replacement.id())) {
                orders.set(i, new StoredOrder(accountId, previous.idempotencyKey(), replacement));
                persist(new Snapshot(1, snapshot.accounts(), List.copyOf(orders)));
                return;
            }
        }
        throw new NoSuchElementException();
    }
    private void persist(Snapshot next) {
        if (path == null) { snapshot = next; return; }
        Path temporary = null;
        String stage = "prepare";
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".account-store-", ".tmp");
            stage = "serialize";
            byte[] bytes = json.writeValueAsBytes(next);
            stage = "write";
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            stage = "atomic-replace";
            replaceWithShortFileLockRetry(temporary);
            snapshot = next;
        } catch (IOException e) {
            // Never log the snapshot, credentials, addresses, exception message or full path.
            LOG.warn("Account store save failed: stage={}, type={}, filesystemReason={}", stage,
                e.getClass().getSimpleName(), e instanceof FileSystemException fs ? fs.getReason() : "not-filesystem-error");
            var failure = new AuthService.Failure(503, "本地账号或订单保存失败，请稍后重试；本次操作未完成");
            failure.initCause(e);
            throw failure;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
    private void replaceWithShortFileLockRetry(Path temporary) throws IOException {
        int[] delayMillis = {25, 75, 150, 300};
        for (int attempt = 0; ; attempt++) {
            try {
                atomicReplace.replace(temporary, path);
                return;
            } catch (FileSystemException e) {
                if (attempt >= delayMillis.length || e instanceof AtomicMoveNotSupportedException
                    || e instanceof NoSuchFileException || !Files.exists(temporary)) throw e;
                // Windows antivirus/sync readers can briefly deny replacement. Reuse the same
                // fully written file; do not rerun registration, checkout, stock changes or serialization.
                try { Thread.sleep(delayMillis[attempt]); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    var failure = new java.io.InterruptedIOException("Atomic store replacement interrupted");
                    failure.initCause(interrupted); throw failure;
                }
            }
        }
    }
}
