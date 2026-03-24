package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class Service {
    private final PerThreadContextExecutor executor;
    private final ServiceManager sm;
    final long weight;
    final String name;
    final BooleanSupplier limiter;

    private final Semaphore tasks = new Semaphore(0);
    private volatile boolean isLive = true;
    private volatile boolean isStopping = false;
    private final AtomicBoolean deadExecuteWarningIssued = new AtomicBoolean(false);

    Service(Supplier<Pair<Runnable, Runnable>> ctxSupplier, ServiceManager sm, long weight, String name, BooleanSupplier limiter) {
        this.sm = sm;
        this.weight = weight;
        this.name = name;
        this.limiter = limiter;

        this.executor = new PerThreadContextExecutor(ctxSupplier, e->sm.handleException(this, e));
    }

    public void execute() {
        this.tryExecute();
    }

    public boolean tryExecute() {
        if (this.isStopping || !this.isLive) {
            if (this.deadExecuteWarningIssued.compareAndSet(false, true)) {
                Logger.warn("Ignored execute request on stopping/dead service: " + this.name);
            }
            return false;
        }
        this.tasks.release();
        this.sm.execute(this);
        return true;
    }

    boolean runJob() {
        if (this.isStopping||!this.isLive) {
            return false;
        }
        if (!this.tasks.tryAcquire()) {
            //Failed to get the job, probably due to a race condition
            return false;
        }
        if (!this.executor.run()) {//Run the job
            throw new IllegalStateException("Executor failed to run");
        }
        return true;
    }

    public boolean isLive() {
        return this.isLive&&!this.isStopping;
    }

    public int numJobs() {
        return this.tasks.availablePermits();
    }

    public void blockTillEmpty() {
        while (this.isLive() && this.numJobs() != 0) {
            Thread.yield();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public int shutdown() {
        if (this.isStopping) {
            throw new IllegalStateException("Service not live");
        }
        this.isStopping = true;//First mark the service as stopping
        this.sm.removeService(this);//Remove the service this is so that new jobs are never executed
        this.executor.shutdown();//Await shutdown of all running jobs
        int remaining = this.tasks.drainPermits();//Drain the remaining tasks to 0
        this.isLive = false;//Mark the service as dead
        this.sm.remJobs(remaining);
        return remaining;
    }

    public boolean steal() {
        if (!this.tasks.tryAcquire()) {
            return false;
        }
        this.sm.remJobs(1);
        return true;
    }

    public int drain() {
        int tasks = this.tasks.drainPermits();
        if (tasks != 0) {
            this.sm.remJobs(tasks);
        }
        return tasks;
    }
}
