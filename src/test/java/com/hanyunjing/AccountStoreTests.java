package com.hanyunjing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreTests {
    @TempDir Path temporary;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    AccountStore.Account account(String name) {
        return new AccountStore.Account("u_" + name, name, name, "test-salt", "test-hash", 600_000, Instant.now());
    }
    @Test void transientReplacementLockRetriesOnlyTheSameWrittenFile() throws Exception {
        var attempts = new AtomicInteger(); var firstTemporary = new AtomicReference<Path>();
        var firstBytes = new AtomicReference<byte[]>();
        Path path = temporary.resolve("store.json");
        var store = new AccountStore(json, path, (source, target) -> {
            if (firstTemporary.get() == null) { firstTemporary.set(source); firstBytes.set(Files.readAllBytes(source)); }
            assertEquals(firstTemporary.get(), source); assertArrayEquals(firstBytes.get(), Files.readAllBytes(source));
            if (attempts.incrementAndGet() < 3) throw new AccessDeniedException(source.toString(), target.toString(), "synthetic file lock");
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        store.addAccount(account("one"));
        assertEquals(3, attempts.get()); assertNotNull(store.account("one"));
        assertEquals(1, json.readTree(Files.readAllBytes(path)).path("accounts").size());
    }
    @Test void persistentReplacementFailureIsBoundedAndPreservesPreviousSnapshot() throws Exception {
        Path path = temporary.resolve("store.json");
        var first = new AccountStore(json, path); first.addAccount(account("existing"));
        byte[] original = Files.readAllBytes(path);
        var attempts = new AtomicInteger();
        var blocked = new AccountStore(json, path, (source, target) -> {
            attempts.incrementAndGet(); throw new AccessDeniedException(source.toString(), target.toString(), "synthetic permanent lock");
        });
        assertThrows(AuthService.Failure.class, () -> blocked.addAccount(account("new")));
        assertEquals(5, attempts.get()); assertNull(blocked.account("new")); assertNotNull(blocked.account("existing"));
        assertArrayEquals(original, Files.readAllBytes(path));
        try (var files = Files.list(temporary)) { assertEquals(1, files.count()); }
    }
    @Test void UnsupportedAtomicMoveFailsClosedWithoutNonAtomicFallback() {
        var attempts = new AtomicInteger();
        var blocked = new AccountStore(json, temporary.resolve("store.json"), (source, target) -> {
            attempts.incrementAndGet(); throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported");
        });
        assertThrows(AuthService.Failure.class, () -> blocked.addAccount(account("new")));
        assertEquals(1, attempts.get()); assertNull(blocked.account("new"));
    }
}
