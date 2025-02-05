/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.file.cache;

import alluxio.client.file.cache.store.PageStoreOptions;
import alluxio.exception.PageNotFoundException;
import alluxio.exception.status.ResourceExhaustedException;
import alluxio.file.ReadTargetBuffer;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;

import com.codahale.metrics.Counter;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.SimpleTimeLimiter;
import com.google.common.util.concurrent.TimeLimiter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A wrapper class on PageStore with timeout. Note that, this page store will not queue any request.
 */
public class TimeBoundPageStore implements PageStore {
  private final PageStore mPageStore;
  private final long mTimeoutMs;
  private final TimeLimiter mTimeLimter;
  private final ExecutorService mExecutorService;

  /**
   * @param pageStore page store
   * @param options time out in ms
   */
  public TimeBoundPageStore(PageStore pageStore, PageStoreOptions options) {
    mPageStore = Preconditions.checkNotNull(pageStore, "pageStore");
    mTimeoutMs = options.getTimeoutDuration();
    mExecutorService = new ThreadPoolExecutor(options.getTimeoutThreads(),
        options.getTimeoutThreads(), 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
    mTimeLimter = SimpleTimeLimiter.create(mExecutorService);
  }

  @Override
  public void put(PageId pageId,
      ByteBuffer page,
      boolean isTemporary) throws IOException {
    Callable<Void> callable = () -> {
      mPageStore.put(pageId, page, isTemporary);
      return null;
    };
    try {
      mTimeLimter.callWithTimeout(callable, mTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      // Task got cancelled by others, interrupt the current thread
      // and then throw a runtime ex to make the higher level stop.
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (TimeoutException e) {
      Metrics.STORE_PUT_TIMEOUT.inc();
      throw new IOException(e);
    } catch (RejectedExecutionException e) {
      Metrics.STORE_THREADS_REJECTED.inc();
      throw new IOException(e);
    } catch (ExecutionException e) {
      Throwables.propagateIfPossible(e.getCause(), ResourceExhaustedException.class,
          IOException.class);
      throw new IOException(e);
    } catch (Throwable t) {
      Throwables.propagateIfPossible(t, IOException.class);
      throw new IOException(t);
    }
  }

  @Override
  public int get(PageId pageId, int pageOffset, int bytesToRead, ReadTargetBuffer target,
      boolean isTemporary) throws IOException, PageNotFoundException {
    Callable<Integer> callable = () ->
        mPageStore.get(pageId, pageOffset, bytesToRead, target, isTemporary);
    try {
      return mTimeLimter.callWithTimeout(callable, mTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      // Task got cancelled by others, interrupt the current thread
      // and then throw a runtime ex to make the higher level stop.
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (TimeoutException e) {
      Metrics.STORE_GET_TIMEOUT.inc();
      throw new IOException(e);
    } catch (RejectedExecutionException e) {
      Metrics.STORE_THREADS_REJECTED.inc();
      throw new IOException(e);
    } catch (Throwable t) {
      Throwables.propagateIfPossible(t, IOException.class, PageNotFoundException.class);
      throw new IOException(t);
    }
  }

  @Override
  public void delete(PageId pageId) throws IOException, PageNotFoundException {
    Callable<Void> callable = () -> {
      mPageStore.delete(pageId);
      return null;
    };
    try {
      mTimeLimter.callWithTimeout(callable, mTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      // Task got cancelled by others, interrupt the current thread
      // and then throw a runtime ex to make the higher level stop.
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (TimeoutException e) {
      Metrics.STORE_DELETE_TIMEOUT.inc();
      throw new IOException(e);
    } catch (RejectedExecutionException e) {
      Metrics.STORE_THREADS_REJECTED.inc();
      throw new IOException(e);
    } catch (Throwable t) {
      Throwables.propagateIfPossible(t, IOException.class, PageNotFoundException.class);
      throw new IOException(t);
    }
  }

  @Override
  public void close() throws Exception {
    mExecutorService.shutdown();
    mPageStore.close();
  }

  private static final class Metrics {
    // Note that only counter/guage can be added here.
    // Both meter and timer need to be used inline
    // because new meter and timer will be created after {@link MetricsSystem.resetAllMetrics()}
    /** Number of timeouts when deleting pages from page store. */
    private static final Counter STORE_DELETE_TIMEOUT =
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_STORE_DELETE_TIMEOUT.getName());
    /** Number of timeouts when reading pages from page store. */
    private static final Counter STORE_GET_TIMEOUT =
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_STORE_GET_TIMEOUT.getName());
    /** Number of timeouts when writing new pages to page store. */
    private static final Counter STORE_PUT_TIMEOUT =
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_STORE_PUT_TIMEOUT.getName());
    /**
     * Number of rejection of I/O threads on submitting tasks to thread pool,
     * likely due to unresponsive local file system.
     **/
    private static final Counter STORE_THREADS_REJECTED =
        MetricsSystem.counter(MetricKey.CLIENT_CACHE_STORE_THREADS_REJECTED.getName());

    private Metrics() {} // prevent instantiation
  }
}
