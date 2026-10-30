package com.drkkr.strategos

import android.content.Context
import androidx.room.Room
import com.drkkr.strategos.data.db.StrategosDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Creates an in-memory Room DB with a controlled query/transaction executor.
 * Threads in the executor swallow uncaught exceptions; the executor wrapper silently
 * discards tasks submitted after shutdown so leftover Room callbacks don't crash.
 *
 * Pair.second is the executor; pass both to [closeDbSafely] in @After.
 */
internal fun inMemoryDbForTest(context: Context): Pair<StrategosDatabase, ExecutorService> {
    val delegate = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable).apply {
            setUncaughtExceptionHandler { _, _ -> }
        }
    }
    // Silently discard tasks submitted after shutdown (Room's close path may call execute()).
    val executor = object : ExecutorService by delegate {
        override fun execute(command: Runnable) {
            try { delegate.execute(command) } catch (_: RejectedExecutionException) {}
        }
    }
    val db = Room.inMemoryDatabaseBuilder(context, StrategosDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(executor)
        .setTransactionExecutor(executor)
        .build()
    return db to executor
}

/**
 * Race-free DB teardown for in-memory test databases.
 *
 * Room's Flow queries run on arch_disk_io_* threads (ArchTaskExecutor), completely
 * outside our roomExecutor. After cancelAndJoin() those threads may still be mid-query
 * inside a withContext(NonCancellable) block. Calling db.close() while they are active
 * throws IllegalStateException with no CoroutineExceptionHandler on Room's internal
 * scope, so it escapes to kotlinx-coroutines-test's global ServiceLoader handler and
 * surfaces as UncaughtExceptionsBeforeTest in the NEXT test class.
 *
 * Fix: wait until all arch_disk_io_* threads transition out of RUNNABLE state (i.e. they
 * are idle in their thread-pool queue), then close. This is deterministic — no fixed
 * sleep — and reliable because in-memory SQLite queries take microseconds. The 5 ms
 * extra buffer after the last RUNNABLE observation guards against a thread that just
 * finished the query and hasn't yet parked itself in TIMED_WAITING.
 */
internal fun closeDbSafely(db: StrategosDatabase, executor: ExecutorService) {
    flushExecutor(executor)
    executor.shutdown()
    executor.awaitTermination(2, TimeUnit.SECONDS)
    awaitArchDiskIoIdle()
    runCatching { db.close() }
}

/**
 * Blocks until all arch_disk_io_* threads (Room's ArchTaskExecutor IO pool) are no
 * longer in RUNNABLE state, then waits an extra 5 ms so the last active thread has
 * time to park itself before the caller proceeds.
 */
private fun awaitArchDiskIoIdle(timeoutMs: Long = 2_000L) {
    val archThreads = Thread.getAllStackTraces().keys
        .filter { it.name.startsWith("arch_disk_io") }
    if (archThreads.isEmpty()) return
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (archThreads.all { it.state != Thread.State.RUNNABLE }) {
            try { Thread.sleep(5) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            return
        }
        try { Thread.sleep(1) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}

private fun flushExecutor(executor: ExecutorService) {
    try { executor.submit {}.get(2, TimeUnit.SECONDS) } catch (_: Exception) {}
}
