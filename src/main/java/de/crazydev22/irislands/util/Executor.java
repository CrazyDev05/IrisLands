package de.crazydev22.irislands.util;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;

public class Executor extends ForkJoinPool {
	public Executor(String name, int priority) {
		super(Math.min(0x7fff, Runtime.getRuntime().availableProcessors()),
				new ForkJoinPool.ForkJoinWorkerThreadFactory(){
					int m = 0;
					@Override
					public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
						ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
						worker.setPriority(priority);
						worker.setName(name + " " + ++this.m);
						return worker;
					}
				}, null, false,
				0, 0x7fff, 1, null, 60_000L, TimeUnit.MILLISECONDS);
	}

	@Override
	public @NotNull ForkJoinTask<?> submit(Runnable task) {
		return super.submit(task);
	}
}
