import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cafeteria Ordering Kiosk System — central server, kiosks, actors, and thread-safe ordering.
 * <p>
 * Process Impact Corporation: geographically distributed kiosks share one logical
 * {@link OrderingServer} over a WAN (simulated here as a single in-process server).
 */
public class CafeteriaSystem {

    private static final Logger LOG = Logger.getLogger(CafeteriaSystem.class.getName());

    // -------------------------------------------------------------------------
    // Custom exceptions (alternative flows)
    // -------------------------------------------------------------------------

    /** Requested quantity exceeds available stock for a menu line. */
    public static class MenuOutOfStockException extends Exception {
        public MenuOutOfStockException(String message) {
            super(message);
        }
    }

    /** Selected delivery window has no remaining capacity. */
    public static class DeliverySlotFullException extends Exception {
        public DeliverySlotFullException(String message) {
            super(message);
        }
    }

    /** Employee payroll / spending limit would be exceeded by this charge. */
    public static class PayrollLimitExceededException extends Exception {
        public PayrollLimitExceededException(String message) {
            super(message);
        }
    }

    /** Card authentication failed after the maximum number of attempts. */
    public static class AuthFailureException extends Exception {
        public AuthFailureException(String message) {
            super(message);
        }
    }

    // -------------------------------------------------------------------------
    // Domain model
    // -------------------------------------------------------------------------

    public enum KioskState {
        OFFLINE,
        IDLE
    }

    /** Registered employee; {@link CardReader} resolves card data to this record. */
    public static final class Employee {
        private final String id;
        private final String name;

        public Employee(String id, String name) {
            this.id = Objects.requireNonNull(id);
            this.name = Objects.requireNonNull(name);
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    /** Menu line managed by {@link CafeteriaManager}; stock updated only inside server critical sections. */
    public static final class MenuItem {
        private final String id;
        private final String name;
        private final double unitPrice;
        private int stockQuantity;

        public MenuItem(String id, String name, double unitPrice, int stockQuantity) {
            this.id = Objects.requireNonNull(id);
            this.name = Objects.requireNonNull(name);
            if (unitPrice < 0 || stockQuantity < 0) {
                throw new IllegalArgumentException("price and stock must be non-negative");
            }
            this.unitPrice = unitPrice;
            this.stockQuantity = stockQuantity;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public double getUnitPrice() {
            return unitPrice;
        }

        public int getStockQuantity() {
            return stockQuantity;
        }

        void setStockQuantity(int stockQuantity) {
            if (stockQuantity < 0) {
                throw new IllegalArgumentException("stock must be non-negative");
            }
            this.stockQuantity = stockQuantity;
        }

        void adjustStock(int delta) {
            int next = this.stockQuantity + delta;
            if (next < 0) {
                throw new IllegalStateException("stock would become negative");
            }
            this.stockQuantity = next;
        }
    }

    /** Immutable snapshot of a placed order (logical copy for display / audit). */
    public static final class Order {
        private final String orderId;
        private final String employeeId;
        private final Map<String, Integer> lineQuantities;
        private final String deliverySlot;
        private final double totalAmount;

        public Order(String orderId, String employeeId, Map<String, Integer> lineQuantities,
                     String deliverySlot, double totalAmount) {
            this.orderId = orderId;
            this.employeeId = employeeId;
            this.lineQuantities = Collections.unmodifiableMap(new HashMap<>(lineQuantities));
            this.deliverySlot = deliverySlot;
            this.totalAmount = totalAmount;
        }

        public String getOrderId() {
            return orderId;
        }

        public String getEmployeeId() {
            return employeeId;
        }

        public Map<String, Integer> getLineQuantities() {
            return lineQuantities;
        }

        public String getDeliverySlot() {
            return deliverySlot;
        }

        public double getTotalAmount() {
            return totalAmount;
        }

        @Override
        public String toString() {
            return "Order{" + "id=" + orderId + ", employee=" + employeeId
                    + ", slot=" + deliverySlot + ", total=" + totalAmount + ", lines=" + lineQuantities + '}';
        }
    }

    // -------------------------------------------------------------------------
    // Central Ordering Server (shared by all kiosks)
    // -------------------------------------------------------------------------

    /**
     * Central ordering service: menu, stock, delivery slots, payroll ledger, and orders.
     * Concurrency: mutable shared state is guarded by {@code synchronized} methods where noted.
     */
    public static class OrderingServer {
        private final Map<String, MenuItem> menu = new ConcurrentHashMap<>();
        /** slotId -> number of orders already booked */
        private final Map<String, Integer> deliverySlotUsage = new HashMap<>();
        /** employeeId -> cumulative spend this period */
        private final Map<String, Double> payrollSpend = new ConcurrentHashMap<>();
        /** employeeId -> per-period limit */
        private final Map<String, Double> payrollLimits = new ConcurrentHashMap<>();
        private final Map<String, Order> ordersById = new ConcurrentHashMap<>();
        private final AtomicLong orderIdSeq = new AtomicLong(1);

        private final int maxOrdersPerDeliverySlot;

        public OrderingServer(int maxOrdersPerDeliverySlot) {
            if (maxOrdersPerDeliverySlot < 1) {
                throw new IllegalArgumentException("slot capacity must be >= 1");
            }
            this.maxOrdersPerDeliverySlot = maxOrdersPerDeliverySlot;
        }

        public void registerPayrollLimit(String employeeId, double limit) {
            payrollLimits.put(employeeId, limit);
            payrollSpend.putIfAbsent(employeeId, 0.0);
        }

        public Map<String, MenuItem> getMenuSnapshot() {
            return Collections.unmodifiableMap(new HashMap<>(menu));
        }

        public Order getOrder(String orderId) {
            return ordersById.get(orderId);
        }

        /**
         * Critical Section: menu item creation; must not interleave with stock mutations on same id.
         */
        public synchronized void putMenuItem(MenuItem item) {
            menu.put(item.getId(), item);
        }

        /**
         * Critical Section: menu item removal; cancels must not run concurrently with same-item orders.
         */
        public synchronized void removeMenuItem(String itemId) {
            menu.remove(itemId);
        }

        /**
         * Critical Section: price/stock/name updates for manager workflows.
         */
        public synchronized void updateMenuItem(String itemId, String newName, Double newPrice, Integer newStock)
                throws IllegalArgumentException {
            MenuItem existing = menu.get(itemId);
            if (existing == null) {
                throw new IllegalArgumentException("Unknown menu item: " + itemId);
            }
            if (newName != null && !newName.isBlank()) {
                // MenuItem is immutable for name/price fields in this design — replace entry
                double price = newPrice != null ? newPrice : existing.getUnitPrice();
                int stock = newStock != null ? newStock : existing.getStockQuantity();
                menu.put(itemId, new MenuItem(itemId, newName.trim(), price, stock));
            } else {
                if (newPrice != null && newPrice >= 0) {
                    MenuItem replaced = new MenuItem(itemId, existing.getName(), newPrice, existing.getStockQuantity());
                    menu.put(itemId, replaced);
                }
                if (newStock != null) {
                    MenuItem m = menu.get(itemId);
                    m.setStockQuantity(newStock);
                }
            }
        }

        private double computeTotal(Map<String, Integer> items) {
            double total = 0.0;
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                MenuItem m = menu.get(e.getKey());
                int q = e.getValue();
                if (q <= 0) {
                    throw new IllegalArgumentException("Invalid quantity for " + e.getKey());
                }
                total += m.getUnitPrice() * q;
            }
            return total;
        }

        private void validateStock(Map<String, Integer> items) throws MenuOutOfStockException {
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                MenuItem m = menu.get(e.getKey());
                if (m == null) {
                    throw new MenuOutOfStockException("Item not on menu: " + e.getKey());
                }
                if (m.getStockQuantity() < e.getValue()) {
                    throw new MenuOutOfStockException(
                            "Out of stock: " + m.getName() + " (requested " + e.getValue()
                                    + ", available " + m.getStockQuantity() + ")");
                }
            }
        }

        private void applyStockDelta(Map<String, Integer> items, int sign) {
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                MenuItem m = menu.get(e.getKey());
                m.adjustStock(sign * e.getValue());
            }
        }

        private void validateSlot(String deliverySlot) throws DeliverySlotFullException {
            int used = deliverySlotUsage.getOrDefault(deliverySlot, 0);
            if (used >= maxOrdersPerDeliverySlot) {
                throw new DeliverySlotFullException(
                        "Delivery slot full: " + deliverySlot + " (" + used + "/" + maxOrdersPerDeliverySlot + ")");
            }
        }

        private void chargePayroll(String employeeId, double amount) throws PayrollLimitExceededException {
            double limit = payrollLimits.getOrDefault(employeeId, Double.POSITIVE_INFINITY);
            double spent = payrollSpend.getOrDefault(employeeId, 0.0);
            if (spent + amount > limit + 1e-6) {
                throw new PayrollLimitExceededException(
                        "Payroll limit exceeded for " + employeeId + ": would be "
                                + (spent + amount) + " vs limit " + limit);
            }
            payrollSpend.put(employeeId, spent + amount);
        }

        private void refundPayroll(String employeeId, double amount) {
            double spent = payrollSpend.getOrDefault(employeeId, 0.0);
            payrollSpend.put(employeeId, Math.max(0.0, spent - amount));
        }

        /**
         * Critical Section: validates stock and slot, charges payroll, decrements stock, persists order — atomically.
         */
        public synchronized String placeOrder(String employeeId, Map<String, Integer> items, String deliverySlot)
                throws MenuOutOfStockException, DeliverySlotFullException, PayrollLimitExceededException {
            Objects.requireNonNull(employeeId);
            Objects.requireNonNull(items);
            Objects.requireNonNull(deliverySlot);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("Empty order");
            }

            validateStock(items);
            validateSlot(deliverySlot);
            double total = computeTotal(items);
            chargePayroll(employeeId, total);

            try {
                applyStockDelta(items, -1);
            } catch (RuntimeException ex) {
                refundPayroll(employeeId, total);
                throw ex;
            }

            deliverySlotUsage.merge(deliverySlot, 1, Integer::sum);
            String orderId = "ORD-" + orderIdSeq.getAndIncrement();
            Order order = new Order(orderId, employeeId, new HashMap<>(items), deliverySlot, total);
            ordersById.put(orderId, order);
            return orderId;
        }

        /**
         * Critical Section: order cancellation restores stock, slot, and payroll consistently.
         */
        public synchronized void cancelOrder(String orderId, String employeeId)
                throws IllegalArgumentException {
            Order o = ordersById.get(orderId);
            if (o == null) {
                throw new IllegalArgumentException("Unknown order: " + orderId);
            }
            if (!o.getEmployeeId().equals(employeeId)) {
                throw new IllegalArgumentException("Order does not belong to employee");
            }
            ordersById.remove(orderId);
            applyStockDelta(o.getLineQuantities(), +1);
            int used = deliverySlotUsage.getOrDefault(o.getDeliverySlot(), 0);
            deliverySlotUsage.put(o.getDeliverySlot(), Math.max(0, used - 1));
            refundPayroll(employeeId, o.getTotalAmount());
        }

        /**
         * Critical Section: modify order — release old resources then acquire new in one lock hold.
         */
        public synchronized void changeOrder(String orderId, String employeeId, Map<String, Integer> newItems,
                                            String newSlot)
                throws MenuOutOfStockException, DeliverySlotFullException, PayrollLimitExceededException {
            Order old = ordersById.get(orderId);
            if (old == null) {
                throw new IllegalArgumentException("Unknown order: " + orderId);
            }
            if (!old.getEmployeeId().equals(employeeId)) {
                throw new IllegalArgumentException("Order does not belong to employee");
            }
            if (newItems == null || newItems.isEmpty()) {
                throw new IllegalArgumentException("New items required");
            }

            // Temporarily restore old reservation
            applyStockDelta(old.getLineQuantities(), +1);
            int usedOld = deliverySlotUsage.getOrDefault(old.getDeliverySlot(), 0);
            deliverySlotUsage.put(old.getDeliverySlot(), Math.max(0, usedOld - 1));
            refundPayroll(employeeId, old.getTotalAmount());

            try {
                validateStock(newItems);
                if (!newSlot.equals(old.getDeliverySlot())) {
                    validateSlot(newSlot);
                }
                double newTotal = computeTotal(newItems);
                chargePayroll(employeeId, newTotal);
                applyStockDelta(newItems, -1);
                deliverySlotUsage.merge(newSlot, 1, Integer::sum);
                Order updated = new Order(orderId, employeeId, new HashMap<>(newItems), newSlot, newTotal);
                ordersById.put(orderId, updated);
            } catch (Exception ex) {
                // Roll back to old order state
                try {
                    validateStock(old.getLineQuantities());
                    chargePayroll(employeeId, old.getTotalAmount());
                    applyStockDelta(old.getLineQuantities(), -1);
                    deliverySlotUsage.merge(old.getDeliverySlot(), 1, Integer::sum);
                    ordersById.put(orderId, old);
                } catch (Exception rollbackEx) {
                    LOG.log(Level.SEVERE, "Inconsistent state after failed changeOrder; manual reconciliation may be needed",
                            rollbackEx);
                    throw new IllegalStateException("Failed to roll back order change", rollbackEx);
                }
                if (ex instanceof MenuOutOfStockException) {
                    throw (MenuOutOfStockException) ex;
                }
                if (ex instanceof DeliverySlotFullException) {
                    throw (DeliverySlotFullException) ex;
                }
                if (ex instanceof PayrollLimitExceededException) {
                    throw (PayrollLimitExceededException) ex;
                }
                throw new IllegalStateException(ex);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Device actor: card reader (auth + payment orchestration)
    // -------------------------------------------------------------------------

    /**
     * Simulates a physical card reader: maps card payloads to employees and enforces auth retry policy.
     */
    public static class CardReader {
        public static final int MAX_AUTH_RETRIES = 3;

        private final Map<String, Employee> cardToEmployee = new ConcurrentHashMap<>();

        public void enrollCard(String cardData, Employee employee) {
            cardToEmployee.put(cardData, employee);
        }

        /**
         * Authenticates the employee card with at most {@link #MAX_AUTH_RETRIES} attempts.
         *
         * @throws AuthFailureException if all attempts fail
         */
        public Employee authenticate(String cardData) throws AuthFailureException {
            int attempts = 0;
            while (attempts < MAX_AUTH_RETRIES) {
                attempts++;
                Employee e = cardToEmployee.get(cardData);
                if (e != null) {
                    return e;
                }
                LOG.fine("Auth attempt " + attempts + " failed for card");
            }
            throw new AuthFailureException("Authentication failed after " + MAX_AUTH_RETRIES + " attempts");
        }

        /**
         * Simulates chip/PIN approval before the server runs payroll charging in {@link OrderingServer#placeOrder}.
         *
         * @return {@code true} if the card transaction is approved at the device
         */
        public boolean authorizePayment(String employeeId, double amount) {
            if (amount < 0 || employeeId == null || employeeId.isBlank()) {
                return false;
            }
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Kiosk Operator — lifecycle
    // -------------------------------------------------------------------------

    public static final class KioskOperator {
        public void startup(Kiosk kiosk) {
            kiosk.transitionToIdle();
        }

        public void shutdown(Kiosk kiosk) {
            kiosk.transitionToOffline();
        }
    }

    // -------------------------------------------------------------------------
    // Kiosk terminal (per building)
    // -------------------------------------------------------------------------

    public static final class Kiosk {
        private final String kioskId;
        private final OrderingServer server;
        private final CardReader cardReader;
        private volatile KioskState state = KioskState.OFFLINE;

        public Kiosk(String kioskId, OrderingServer server, CardReader cardReader) {
            this.kioskId = Objects.requireNonNull(kioskId);
            this.server = Objects.requireNonNull(server);
            this.cardReader = Objects.requireNonNull(cardReader);
        }

        public String getKioskId() {
            return kioskId;
        }

        public KioskState getState() {
            return state;
        }

        void transitionToIdle() {
            state = KioskState.IDLE;
        }

        void transitionToOffline() {
            state = KioskState.OFFLINE;
        }

        private void ensureIdle() {
            if (state != KioskState.IDLE) {
                throw new IllegalStateException("Kiosk " + kioskId + " is not IDLE (state=" + state + ")");
            }
        }

        /** Employee: menu query at the kiosk. */
        public Map<String, MenuItem> queryMenu() {
            ensureIdle();
            return server.getMenuSnapshot();
        }

        /** Employee: authenticated session places an order. */
        public String placeOrder(Employee employee, Map<String, Integer> items, String deliverySlot)
                throws MenuOutOfStockException, DeliverySlotFullException, PayrollLimitExceededException {
            ensureIdle();
            return server.placeOrder(employee.getId(), items, deliverySlot);
        }

        public void cancelOrder(Employee employee, String orderId) {
            ensureIdle();
            server.cancelOrder(orderId, employee.getId());
        }

        public void changeOrder(Employee employee, String orderId, Map<String, Integer> newItems, String newSlot)
                throws MenuOutOfStockException, DeliverySlotFullException, PayrollLimitExceededException {
            ensureIdle();
            server.changeOrder(orderId, employee.getId(), newItems, newSlot);
        }
    }

    // -------------------------------------------------------------------------
    // Cafeteria Manager — menu administration
    // -------------------------------------------------------------------------

    public static final class CafeteriaManager {
        private final OrderingServer server;

        public CafeteriaManager(OrderingServer server) {
            this.server = Objects.requireNonNull(server);
        }

        public void createMenuItem(MenuItem item) {
            server.putMenuItem(item);
        }

        public void modifyMenuItem(String itemId, String newName, Double newPrice, Integer newStock) {
            server.updateMenuItem(itemId, newName, newPrice, newStock);
        }

        public void deleteMenuItem(String itemId) {
            server.removeMenuItem(itemId);
        }
    }

    // -------------------------------------------------------------------------
    // Employee — kiosk-facing actions (uses card reader + kiosk)
    // -------------------------------------------------------------------------

    public static final class EmployeeActor {
        private final CardReader cardReader;
        private final Kiosk kiosk;
        private final String cardData;

        public EmployeeActor(CardReader cardReader, Kiosk kiosk, String cardData) {
            this.cardReader = cardReader;
            this.kiosk = kiosk;
            this.cardData = cardData;
        }

        public Employee login() throws AuthFailureException {
            return cardReader.authenticate(cardData);
        }

        public Map<String, MenuItem> browseMenu() {
            return kiosk.queryMenu();
        }

        public String submitOrder(Employee employee, Map<String, Integer> items, String slot)
                throws MenuOutOfStockException, DeliverySlotFullException, PayrollLimitExceededException {
            Map<String, MenuItem> menu = kiosk.queryMenu();
            double estimated = 0.0;
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                MenuItem m = menu.get(e.getKey());
                if (m != null) {
                    estimated += m.getUnitPrice() * e.getValue();
                }
            }
            if (!cardReader.authorizePayment(employee.getId(), estimated)) {
                throw new IllegalStateException("Card payment not authorized at reader");
            }
            return kiosk.placeOrder(employee, items, slot);
        }

        public void cancel(Employee employee, String orderId) {
            kiosk.cancelOrder(employee, orderId);
        }
    }

    /**
     * Console simulation: shared {@link OrderingServer}, three kiosk threads contending on the same SKU,
     * plus manager/operator and exception demonstrations.
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Cafeteria Ordering Kiosk System — concurrent demo ===\n");

        OrderingServer server = new OrderingServer(/* max orders per delivery slot */ 4);
        CardReader reader = new CardReader();
        CafeteriaManager manager = new CafeteriaManager(server);
        KioskOperator operator = new KioskOperator();

        Employee alice = new Employee("E100", "Alice");
        Employee bob = new Employee("E200", "Bob");
        reader.enrollCard("CARD-ALICE", alice);
        reader.enrollCard("CARD-BOB", bob);

        server.registerPayrollLimit(alice.getId(), 80.0);
        server.registerPayrollLimit(bob.getId(), 500.0);

        manager.createMenuItem(new MenuItem("BURGER", "Veggie Burger", 8.0, 12));
        manager.createMenuItem(new MenuItem("SOUP", "Soup", 4.0, 50));
        manager.createMenuItem(new MenuItem("SALAD", "Side Salad", 6.0, 3));
        manager.modifyMenuItem("BURGER", "Veggie Burger (chef special)", null, null);
        manager.deleteMenuItem("SALAD");

        Kiosk k1 = new Kiosk("Kiosk-North", server, reader);
        Kiosk k2 = new Kiosk("Kiosk-South", server, reader);
        Kiosk k3 = new Kiosk("Kiosk-East", server, reader);
        operator.startup(k1);
        operator.startup(k2);
        operator.startup(k3);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger oosCount = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(3);

        Runnable raceOrders = () -> {
            try {
                String tn = Thread.currentThread().getName();
                Kiosk kiosk = tn.contains("North") ? k1 : tn.contains("South") ? k2 : k3;
                String slot = tn.contains("North") ? "12:00-12:30"
                        : tn.contains("South") ? "12:30-13:00" : "13:00-13:30";
                Employee emp = tn.contains("North") ? alice : bob;
                EmployeeActor actor = new EmployeeActor(reader, kiosk, emp == alice ? "CARD-ALICE" : "CARD-BOB");
                Employee session = actor.login();
                for (int i = 0; i < 8; i++) {
                    Map<String, Integer> cart = new HashMap<>();
                    cart.put("BURGER", 1);
                    try {
                        String oid = actor.submitOrder(session, cart, slot);
                        successCount.incrementAndGet();
                        System.out.println(Thread.currentThread().getName() + " placed " + oid);
                    } catch (MenuOutOfStockException e) {
                        oosCount.incrementAndGet();
                        System.out.println(Thread.currentThread().getName() + " — " + e.getMessage());
                    } catch (DeliverySlotFullException e) {
                        System.out.println(Thread.currentThread().getName() + " — slot full: " + e.getMessage());
                        break;
                    } catch (PayrollLimitExceededException e) {
                        System.out.println(Thread.currentThread().getName() + " — payroll: " + e.getMessage());
                        break;
                    }
                }
            } catch (AuthFailureException e) {
                System.out.println("Auth failed: " + e.getMessage());
            } finally {
                done.countDown();
            }
        };

        Thread t1 = new Thread(raceOrders, "KioskThread-North");
        Thread t2 = new Thread(raceOrders, "KioskThread-South");
        Thread t3 = new Thread(raceOrders, "KioskThread-East");
        t1.start();
        t2.start();
        t3.start();
        done.await();

        MenuItem burgerAfter = server.getMenuSnapshot().get("BURGER");
        System.out.println("\n--- After concurrent orders ---");
        System.out.println("Successful order attempts (lines): " + successCount.get());
        System.out.println("Out-of-stock denials: " + oosCount.get());
        System.out.println("Remaining BURGER stock: " + (burgerAfter != null ? burgerAfter.getStockQuantity() : "n/a"));
        System.out.println("Expected remaining: " + (12 - successCount.get()) + " (initial 12 minus successful burger lines)\n");

        // AuthFailureException: unknown card, 3 attempts then fail
        Kiosk kDemo = new Kiosk("Kiosk-Demo", server, reader);
        operator.startup(kDemo);
        try {
            new EmployeeActor(reader, kDemo, "UNKNOWN-CARD").login();
        } catch (AuthFailureException e) {
            System.out.println("Expected auth failure: " + e.getMessage());
        }

        // DeliverySlotFullException: exhaust capacity for one window
        try {
            String packedSlot = "14:00-14:30";
            for (int i = 0; i < 4; i++) {
                Map<String, Integer> line = new HashMap<>();
                line.put("SOUP", 1);
                server.placeOrder(bob.getId(), line, packedSlot);
            }
            Map<String, Integer> overflow = new HashMap<>();
            overflow.put("SOUP", 1);
            server.placeOrder(bob.getId(), overflow, packedSlot);
        } catch (DeliverySlotFullException e) {
            System.out.println("Expected delivery slot full: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Unexpected slot demo: " + e);
        }

        // PayrollLimitExceededException: Alice has low limit
        try {
            EmployeeActor low = new EmployeeActor(reader, k1, "CARD-ALICE");
            Employee a = low.login();
            Map<String, Integer> big = new HashMap<>();
            big.put("SOUP", 25);
            low.submitOrder(a, big, "13:00-13:30");
        } catch (PayrollLimitExceededException e) {
            System.out.println("Expected payroll limit: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Unexpected: " + e);
        }

        operator.shutdown(k1);
        operator.shutdown(k2);
        operator.shutdown(k3);
        operator.shutdown(kDemo);
        System.out.println("\n=== Demo complete ===");
    }
}
