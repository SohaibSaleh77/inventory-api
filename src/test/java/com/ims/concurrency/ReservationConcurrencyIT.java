package com.ims.concurrency;

import com.ims.domain.sales.dto.SalesDtos.CreateRequest;
import com.ims.domain.sales.dto.SalesDtos.LineRequest;
import com.ims.domain.sales.dto.SalesDtos.Response;
import com.ims.testing.Fixtures;
import com.ims.testing.IntegrationTestEnv;
import com.ims.testing.InvariantVerifier;
import com.ims.testing.TestUsers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classic oversell scenario: many orders compete for the same limited
 * stock. The invariants: exactly the expected number of confirms succeed,
 * no oversell, no negative stock, no leaked reservations.
 */
class ReservationConcurrencyIT {

    static IntegrationTestEnv env;
    static TestUsers users;
    static Fixtures fx;
    static String sku;
    static String warehouseCode;

    @BeforeAll
    static void setUp() {
        env = IntegrationTestEnv.get();
        env.truncateAll();
        users = TestUsers.create(env);
        fx = new Fixtures(env.app);
        fx.category(users.manager, "C-CAT", null);
        fx.supplier(users.manager, "C-SUP");
        warehouseCode = "C-WH";
        fx.warehouse(users.manager, warehouseCode);
        fx.customer(users.manager, "C-CUS");
        sku = "C-SKU";
        fx.product(users.manager, sku, "C-CAT", BigDecimal.TEN,
                new BigDecimal("20.00"), BigDecimal.ZERO);
        // exactly 100 units available
        fx.receiveStock(users.manager, "C-SUP", warehouseCode, sku, 100, BigDecimal.TEN);
    }

    @AfterAll
    static void tearDown() {
        env.truncateAll();
    }

    @Test
    void twentyOrdersCompeteFor100Units_noOversell() throws Exception {
        int threads = 20;
        int qtyPerOrder = 10; // 20*10 = 200 demanded vs 100 available
        int expectedSuccess = 10;

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int orderNo = i;
                futures.add(pool.submit(() -> {
                    try {
                        Response so = env.app.salesOrderService.create(users.staff,
                                new CreateRequest("C-CUS", warehouseCode, null, null,
                                        List.of(new LineRequest(sku,
                                                BigDecimal.valueOf(qtyPerOrder),
                                                new BigDecimal("20.00"), null, null))));
                        // barrier: try to make confirms overlap in time
                        startGate.await(10, TimeUnit.SECONDS);
                        env.app.salesOrderService.confirm(users.staff, so.id());
                        successes.incrementAndGet();
                    } catch (Exception e) {
                        if (e instanceof com.ims.common.exception.InsufficientStockException
                                || e.getCause() instanceof
                                        com.ims.common.exception.InsufficientStockException) {
                            failures.incrementAndGet();
                        } else {
                            throw new RuntimeException("Unexpected: " + e, e);
                        }
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        assertEquals(expectedSuccess, successes.get(),
                "exactly 10 confirms must succeed for 100 units of stock");
        assertEquals(threads - expectedSuccess, failures.get());

        // stock state: 100 on hand, 100 reserved, 0 available
        var level = env.app.stockQueryService.stockLevels(users.viewer, sku, null, null,
                com.ims.common.util.PageRequest.of(1, 10, null, null)).content().get(0);
        assertEquals(new BigDecimal("100.000"), level.qtyOnHand());
        assertEquals(new BigDecimal("100.000"), level.qtyReserved());
        assertEquals(new BigDecimal("0.000"), level.qtyAvailable());

        // global invariants hold after the storm
        List<InvariantVerifier.Violation> violations = new InvariantVerifier(env)
                .verifyAll();
        assertEquals(List.of(), violations, () -> "invariants broken: " + violations);
    }

    @Test
    void concurrentDocumentNumbersAreUnique() throws Exception {
        int threads = 12;
        int perThread = 8;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<List<String>>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    List<String> numbers = new java.util.ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        Response so = env.app.salesOrderService.create(users.staff,
                                new CreateRequest("C-CUS", warehouseCode, null, null,
                                        List.of(new LineRequest(sku, BigDecimal.ONE,
                                                new BigDecimal("20.00"), null, null))));
                        numbers.add(so.soNo());
                    }
                    return numbers;
                }));
            }
            var all = new java.util.HashSet<String>();
            for (Future<List<String>> future : futures) {
                all.addAll(future.get(60, TimeUnit.SECONDS));
            }
            assertEquals(threads * perThread, all.size(),
                    "every document number must be unique");
        }
    }

    @Test
    void transferDispatchRace_exactlyOneWins() throws Exception {
        // stock a separate product and create the destination warehouse
        String raceSku = "C-RACE";
        fx.product(users.manager, raceSku, "C-CAT", BigDecimal.TEN,
                new BigDecimal("20.00"), BigDecimal.ZERO);
        fx.receiveStock(users.manager, "C-SUP", warehouseCode, raceSku, 10,
                BigDecimal.TEN);
        fx.warehouse(users.manager, "C-WH2");

        var t1 = env.app.transferService.create(users.staff,
                new com.ims.domain.transfer.dto.TransferDtos.CreateRequest(
                        raceSku, warehouseCode, "C-WH2", new BigDecimal("8"), null));
        var t2 = env.app.transferService.create(users.staff,
                new com.ims.domain.transfer.dto.TransferDtos.CreateRequest(
                        raceSku, warehouseCode, "C-WH2", new BigDecimal("8"), null));

        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(() -> {
                try {
                    gate.await();
                    env.app.transferService.dispatch(users.staff, t1.id());
                    return true;
                } catch (Exception e) {
                    return false;
                }
            });
            var f2 = pool.submit(() -> {
                try {
                    gate.await();
                    env.app.transferService.dispatch(users.staff, t2.id());
                    return true;
                } catch (Exception e) {
                    return false;
                }
            });
            gate.countDown();
            if (f1.get(30, TimeUnit.SECONDS)) ok.incrementAndGet(); else rejected.incrementAndGet();
            if (f2.get(30, TimeUnit.SECONDS)) ok.incrementAndGet(); else rejected.incrementAndGet();
        }
        assertEquals(1, ok.get(), "8+8 > 10 available: exactly one dispatch may win");
        assertEquals(1, rejected.get());
    }
}
