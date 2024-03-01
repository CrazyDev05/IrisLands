package de.crazydev22.irislands.util;

import java.util.concurrent.CountDownLatch;

public class ThreadLatch extends Thread {
	private final CountDownLatch latch = new CountDownLatch(1);

	public ThreadLatch(Runnable runnable, String name) {
		super(runnable, name);
	}

	public final void await() throws InterruptedException {
		if (isAlive()) latch.await();
	}

	@Override
	public final void run() {
		try {
			super.run();
		} finally {
			latch.countDown();
		}
	}
}
