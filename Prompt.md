# ROLE
You are a Senior Java Software Engineer and System Architect. Your goal is to implement a robust, thread-safe Cafeteria Ordering Kiosk System.

# TASK
Generate a complete, single-file Java program. It must be production-ready, self-contained (no external dependencies), and strictly follow the architecture below.

# System Overview
Process Impact Corporation operates several cafeteria ordering kiosks geographically
distributed across campus buildings, connected via a wide area network to a central
Ordering Server.

# Actors
1. Employee – places, changes, cancels orders, and queries the menu via the kiosk
2. Cafeteria Manager – creates, modifies, and deletes menu items
3. Kiosk Operator – starts up (Offline -> Idle) and shuts down (Idle -> Offline) the kiosk terminal
4. Card Reader (device actor) – logic for authentication and process payment, supplies employee card data to the system  

# ERROR HANDLING & ALTERNATIVE FLOWS
Implement custom Exceptions for:
- MenuOutOfStockException
- DeliverySlotFullException
- PayrollLimitExceededException
- AuthFailureException (max 3 retries)

# OUTPUT REQUIREMENTS
- Format: A single `public class CafeteriaSystem` containing all nested classes.
- Documentation: Use Javadoc-style comments to mark "Critical Sections."
- Demonstration: The `main()` method must spawn 3+ threads simulating different kiosks accessing the same stock simultaneously to prove thread safety.

# CONSTRAINTS
- Java Standard Library only.
- No GUI (Console-based simulation).
- Use OOP