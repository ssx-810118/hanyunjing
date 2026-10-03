package com.hanyunjing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class OrderPaymentTests {
    @TempDir Path temporary;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    Models.Checkout checkout(String key) { return new Models.Checkout("测试收货人", "13900000000", "陕西省 西安市", "测试地址，无实际寄送", key); }
    Models.Order pending(CoreService core) { core.addCart("alice", new Models.CartAdd("p1", "p1-M", 2)); return core.order("alice", "device-one", checkout("checkout-key-001")); }
    int stock(CoreService core) { return core.product("p1").skus().stream().filter(s -> s.id().equals("p1-M")).findFirst().orElseThrow().stock(); }
    @Test void creationReservesStockAndWaitsForExplicitPayment() {
        var core = new CoreService(new TraceBus()); var order = pending(core);
        assertEquals("PENDING_PAYMENT", order.status()); assertNull(order.paymentMethod()); assertNull(order.paidAt());
        assertEquals(398, order.cart().total().intValue()); assertEquals(10, stock(core)); assertEquals(0, core.cart("alice").quantity());
        assertEquals(order, core.order("alice", "device-two", checkout("checkout-key-001")));
    }
    @Test void allThreeMethodsPersistAfterRestartWithSingleInventoryDeduction() {
        for (String method : List.of("ALIPAY", "WECHAT", "BALANCE")) {
            Path file = temporary.resolve(method + ".json"); var core = new CoreService(new TraceBus(), new AccountStore(json, file));
            var pending = pending(core); var paid = core.payOrder("alice", pending.id(), new Models.Payment(method));
            assertEquals("DEMO_PAID", paid.status()); assertEquals(method, paid.paymentMethod()); assertNotNull(paid.paidAt()); assertNull(paid.cancelledAt());
            var restored = new CoreService(new TraceBus(), new AccountStore(json, file));
            assertEquals(paid, restored.orderById("alice", paid.id())); assertEquals(10, stock(restored)); assertTrue(restored.orders("bob").isEmpty());
        }
    }
    @Test void duplicateConcurrentPaymentIsIdempotentAndDoesNotChangeMethod() throws Exception {
        var bus = new TraceBus(); var core = new CoreService(bus); var pending = pending(core);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = pool.invokeAll(List.of(() -> core.payOrder("alice", pending.id(), new Models.Payment("ALIPAY")), () -> core.payOrder("alice", pending.id(), new Models.Payment("WECHAT"))));
            assertEquals(futures.get(0).get(), futures.get(1).get()); assertEquals(10, stock(core));
            assertEquals(1, bus.events("alice", 0).stream().filter(e -> e.type().equals("ORDER_DEMO_PAID")).count());
        } finally { pool.shutdownNow(); }
    }
    @Test void cancellationIsIdempotentReleasesInventoryAndSurvivesRestart() {
        Path file = temporary.resolve("cancel.json"); var core = new CoreService(new TraceBus(), new AccountStore(json, file));
        var pending = pending(core); var cancelled = core.cancelOrder("alice", pending.id());
        assertEquals("CANCELLED", cancelled.status()); assertNotNull(cancelled.cancelledAt()); assertEquals(12, stock(core));
        assertEquals(cancelled, core.cancelOrder("alice", pending.id())); assertEquals(12, stock(core));
        assertThrows(IllegalStateException.class, () -> core.payOrder("alice", pending.id(), new Models.Payment("WECHAT")));
        var restored = new CoreService(new TraceBus(), new AccountStore(json, file)); assertEquals(12, stock(restored)); assertEquals(cancelled, restored.orderById("alice", pending.id()));
    }
    @Test void paidOrderCannotBeCancelledAndOtherAccountCannotMutateOrder() {
        var core = new CoreService(new TraceBus()); var pending = pending(core);
        assertThrows(NoSuchElementException.class, () -> core.payOrder("bob", pending.id(), new Models.Payment("ALIPAY")));
        assertThrows(NoSuchElementException.class, () -> core.cancelOrder("bob", pending.id()));
        core.payOrder("alice", pending.id(), new Models.Payment("BALANCE"));
        assertThrows(IllegalStateException.class, () -> core.cancelOrder("alice", pending.id())); assertEquals(10, stock(core));
    }
    @Test void invalidMethodDoesNotModifyPendingOrder() {
        var core = new CoreService(new TraceBus()); var pending = pending(core);
        assertThrows(IllegalArgumentException.class, () -> core.payOrder("alice", pending.id(), new Models.Payment("real-transfer")));
        assertThrows(IllegalArgumentException.class, () -> core.payOrder("alice", pending.id(), new Models.Payment(null)));
        assertEquals(pending, core.orderById("alice", pending.id())); assertEquals(10, stock(core));
    }
    @Test void persistenceFailureCannotReportPaidOrReleaseReservedInventory() {
        var failWrites = new AtomicBoolean(false); Path file = temporary.resolve("failure.json");
        var store = new AccountStore(json, file, (source, target) -> {
            if (failWrites.get()) throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "synthetic");
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        var core = new CoreService(new TraceBus(), store); var pending = pending(core); failWrites.set(true);
        assertThrows(AuthService.Failure.class, () -> core.payOrder("alice", pending.id(), new Models.Payment("ALIPAY")));
        assertThrows(AuthService.Failure.class, () -> core.cancelOrder("alice", pending.id()));
        assertEquals(pending, core.orderById("alice", pending.id())); assertEquals(10, stock(core));
        var restored = new CoreService(new TraceBus(), new AccountStore(json, file)); assertEquals(pending, restored.orderById("alice", pending.id()));
    }
    @Test void oldPaidOrdersWithoutNewPaymentFieldsRemainReadable() throws Exception {
        Path file = temporary.resolve("legacy.json"); var core = new CoreService(new TraceBus(), new AccountStore(json, file));
        var created = pending(core); var tree = json.readTree(Files.readAllBytes(file));
        var order = (com.fasterxml.jackson.databind.node.ObjectNode)tree.path("orders").get(0).path("order");
        order.put("status", "DEMO_PAID"); order.remove(List.of("paymentMethod", "paidAt", "cancelledAt"));
        Files.write(file, json.writeValueAsBytes(tree));
        var restored = new CoreService(new TraceBus(), new AccountStore(json, file));
        var legacy = restored.orderById("alice", created.id()); assertEquals("DEMO_PAID", legacy.status()); assertNull(legacy.paymentMethod()); assertEquals(10, stock(restored));
        assertEquals(legacy, restored.payOrder("alice", created.id(), new Models.Payment("ALIPAY")));
    }
}
