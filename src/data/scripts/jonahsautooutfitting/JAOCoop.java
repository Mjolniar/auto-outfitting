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

    /** Game thread (0.5.93): cancel() and wait up to waitMs for the task to end, so it doesn't run beside the game's thread. */
    void cancelAndWait(long waitMs) {
        cancel();
        synchronized (lock) {
            long until = System.currentTimeMillis() + waitMs;
            while (!done) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try { lock.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    /**
     * 0.5.93: a task another mod steps from its own every-frame script (Auto Colonies' station fits). Without a worker
     * thread the whole task runs on the first step.
     */
    static final class Job {
        static final long CANCEL_WAIT_MS = 2_000L;
        private JAOCoop coop;
        private Runnable direct;
        private volatile boolean finished;
        private boolean stopped;
        private Throwable error;

        Job(Runnable task, String name) {
            Runnable t = () -> { task.run(); finished = true; };
            if (enabled) {
                try {
                    coop = new JAOCoop(t, name);
                    return;
                } catch (Throwable e) {
                    enabled = false;
                    com.fs.starfarer.api.Global.getLogger(JAOCoop.class).warn("Auto Outfitting: couldn't start a worker thread; fitting on the game's thread", e);
                }
            }
            direct = t;
        }

        /** Game thread: works for about budgetMs. True once the task ended (finished, failed or stopped). */
        boolean step(long budgetMs) {
            if (stopped) return true;
            if (direct != null) {
                Runnable r = direct;
                direct = null;
                try { r.run(); } catch (Throwable e) { error = e; }
                stopped = true;
                return true;
            }
            if (coop.run(budgetMs)) stopped = true;
            return stopped;
        }

        /** Null while running and after a task that finished; else what stopped it. */
        Throwable error() {
            if (!stopped) return null;
            if (error != null) return error;
            if (coop != null && coop.error != null) return coop.error;
            return finished ? null : new IllegalStateException("Auto Outfitting: the fit stopped before it finished (cancelled, or no turn for "
                    + IDLE_MS / 1000L + " s)");
        }

        /** Game thread: stops the task and waits for it to end. */
        void cancel() {
            if (stopped) return;
            direct = null;
            if (coop != null) coop.cancelAndWait(CANCEL_WAIT_MS);
            stopped = true;
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
