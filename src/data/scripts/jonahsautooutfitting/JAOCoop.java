package data.scripts.jonahsautooutfitting;

/**
 * Long planning without freezing the game (0.5.40, user 11/14: Optimize fleet "ends up hanging the game"; one Optimal
 * fit took 20 s on an IX Paragon). The task runs on its own thread, but strictly in turns with the game's thread: the
 * game hands it a slice (run), waits while it works, and gets control back at the next checkpoint after the slice;
 * then it draws a frame while the task waits. Game code never runs on both threads at once. Checkpoints sit between
 * evaluations of the search and before each scratch-ship refresh, so a slice ends within one refresh.
 * A task the game stops handing turns to (its panel closed) ends itself after IDLE_MS, or at once on cancel().
 */
final class JAOCoop {
    /** Off after a worker thread could not be used; planning then runs on the game's thread as before. */
    static volatile boolean enabled = true;
    static final long IDLE_MS = 30_000L;

    static final class Cancelled extends RuntimeException {
        Cancelled() { super("planning cancelled", null, false, false); }
    }

    private static final ThreadLocal<JAOCoop> CURRENT = new ThreadLocal<>();
    private final Object lock = new Object();
    private final Thread worker;
    private boolean workerTurn, done, cancelled;
    private long deadline;
    Throwable error;

    JAOCoop(Runnable task, String name) {
        worker = new Thread(() -> {
            CURRENT.set(this);
            try {
                awaitTurn();
                task.run();
            } catch (Cancelled c) {
                // the panel closed
            } catch (Throwable t) {
                error = t;
            } finally {
                synchronized (lock) { done = true; workerTurn = false; lock.notifyAll(); }
            }
        }, name);
        worker.setDaemon(true);
        worker.start();
    }

    /** Game thread: lets the task work for about budgetMs (to its next checkpoint after that). True once it finished. */
    boolean run(long budgetMs) {
        synchronized (lock) {
            if (done) return true;
            deadline = System.nanoTime() + Math.max(1L, budgetMs) * 1_000_000L;
            workerTurn = true;
            lock.notifyAll();
            while (workerTurn && !done) {
                try { lock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            return done;
        }
    }

    /** Game thread: stops the task at its next checkpoint. */
    void cancel() {
        synchronized (lock) {
            cancelled = true;
            workerTurn = true;
            lock.notifyAll();
        }
    }

    boolean done() {
        synchronized (lock) { return done; }
    }

    /** Task thread: hands control back to the game once the slice is used up. No-op outside a task. */
    static void checkpoint() {
        JAOCoop c = CURRENT.get();
        if (c != null) c.yieldIfDue();
    }

    private void yieldIfDue() {
        if (!cancelled && System.nanoTime() < deadline) return;
        synchronized (lock) {
            workerTurn = false;
            lock.notifyAll();
        }
        awaitTurn();
    }

    private void awaitTurn() {
        synchronized (lock) {
            long idleUntil = System.currentTimeMillis() + IDLE_MS;
            while (!workerTurn && !cancelled) {
                long left = idleUntil - System.currentTimeMillis();
                if (left <= 0) { cancelled = true; break; }
                try { lock.wait(left); } catch (InterruptedException e) { cancelled = true; break; }
            }
            if (cancelled) throw new Cancelled();
        }
    }
}
