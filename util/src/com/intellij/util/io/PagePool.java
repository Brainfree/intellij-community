/*
 * Copyright 2000-2007 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * @author max
 */
package com.intellij.util.io;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class PagePool {
  private static final Logger LOG = Logger.getInstance("#com.intellij.util.io.PagePool");

  private final static int PROTECTED_PAGES = 300;
  private final static int PROBATIONAL_PAGES = 300;

  private final Map<PoolPageKey, Page> myProtectedQueue = new LinkedHashMap<PoolPageKey, Page>() {
    @Override
    protected boolean removeEldestEntry(final Map.Entry<PoolPageKey, Page> eldest) {
      if (size() > PROTECTED_PAGES) {
        myProbationalQueue.put(eldest.getKey(), eldest.getValue());
        return true;
      }
      return false;
    }
  };

  private int finalizationId = 0;

  private final Map<PoolPageKey, Page> myProbationalQueue = new LinkedHashMap<PoolPageKey, Page>() {
    @Override
    protected boolean removeEldestEntry(final Map.Entry<PoolPageKey, Page> eldest) {
      if (size() > PROBATIONAL_PAGES) {
        scheduleFinalization(eldest.getValue());
        return true;
      }
      return false;
    }
  };
  private final TreeMap<PoolPageKey, FinalizationRequest> myFinalizationQueue = new TreeMap<PoolPageKey, FinalizationRequest>();

  private final Object lock = new Object();
  private final Object finalizationMonitor = new Object();
  private final PoolPageKey keyInstance = new PoolPageKey(null, -1);

  private PoolPageKey lastFinalizedKey = null;
  private Thread myFinalizerThread;


  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"}) private static int hits = 0;
  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"}) private static int cache_misses = 0;
  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"}) private static int protected_queue_hits = 0;
  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"}) private static int probational_queue_hits = 0;
  @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"}) private static int finalization_queue_hits = 0;

  @NonNls private static final String FINALIZER_THREAD_NAME = "Disk cache finalization queue";

  @SuppressWarnings({"AssignmentToStaticFieldFromInstanceMethod"})
  @NotNull
  public Page alloc(RandomAccessDataFile owner, long offset) {
    synchronized (lock) {
      hits++;
      offset -= offset % Page.PAGE_SIZE;
      PoolPageKey key = setupKey(owner, offset);

      Page page = myProtectedQueue.remove(key);
      if (page != null) {
        protected_queue_hits++;
        toProtectedQueue(page);
        return page;
      }

      page = myProbationalQueue.remove(key);
      if (page != null) {
        probational_queue_hits++;
        toProtectedQueue(page);
        return page;
      }

      final FinalizationRequest request = myFinalizationQueue.remove(key);
      if (request != null) {
        page = request.page;
        finalization_queue_hits++;
        toProtectedQueue(page);
        return page;
      }

      cache_misses++;
      page = new Page(owner, offset);

      myProbationalQueue.put(keyForPage(page), page);
      return page;
    }
  }

  private static double percent(int part, int whole) {
    return ((double)part * 1000 / whole) / 10;
  }

  @SuppressWarnings({"ALL"})
  public static void printStatistics() {
    System.out.println("Total requests: " + hits);
    System.out.println("Protected queue hits: " + protected_queue_hits + " (" + percent(protected_queue_hits, hits) + "%)");
    System.out.println("Probatinonal queue hits: " + probational_queue_hits + " (" + percent(probational_queue_hits, hits) + "%)");
    System.out.println("Finalization queue hits: " + finalization_queue_hits + " (" + percent(finalization_queue_hits, hits) + "%)");
    System.out.println("Cache misses: " + cache_misses + " (" + percent(cache_misses, hits) + "%)");

    System.out.println("Total reads: " + RandomAccessDataFile.totalReads + ". Bytes read: " + RandomAccessDataFile.totalReadBytes);
    System.out.println("Total writes: " + RandomAccessDataFile.totalWrites + ". Bytes written: " + RandomAccessDataFile.totalWriteBytes);
  }

  private static PoolPageKey keyForPage(final Page page) {
    return page.getKey();
  }

  private void toProtectedQueue(final Page page) {
    myProtectedQueue.put(keyForPage(page), page);
  }

  private PoolPageKey setupKey(RandomAccessDataFile owner, long offset) {
    keyInstance.setup(owner, offset);
    return keyInstance;
  }

  public void flushPages(final RandomAccessDataFile owner) {
    boolean hasFlushes;
    synchronized (lock) {
      hasFlushes = scanQueue(owner, myProtectedQueue);
      hasFlushes |= scanQueue(owner, myProbationalQueue);
    }

    if (hasFlushes) {
      flushFinalizationQueue(Integer.MAX_VALUE);
    }
  }

  private void flushFinalizationQueue(final int maxPagesToFlush) {
    int count = 0;

    while (count < maxPagesToFlush) {
      FinalizationRequest request = retreiveFinalizationRequest();
      if (request == null) {
        break;
      }
      processFinalizationRequest(request);
      count++;
    }
  }

  private boolean scanQueue(final RandomAccessDataFile owner, final Map<?, Page> queue) {
    Iterator<Page> iterator = queue.values().iterator();
    boolean hasFlushes = false;
    while (iterator.hasNext()) {
      Page page = iterator.next();

      if (page.getOwner() == owner) {
        scheduleFinalization(page);
        iterator.remove();
        hasFlushes = true;
      }
    }
    return hasFlushes;
  }

  private void scheduleFinalization(final Page page) {
    final int curFinalizationId;
    synchronized (lock) {
      curFinalizationId = ++finalizationId;
    }

    final FinalizationRequest request = page.prepareForFinalization(curFinalizationId);
    if (request == null) return;

    boolean flushQueueNow = false;

    synchronized (lock) {
      if (myFinalizerThread == null) {
        myFinalizerThread = new Thread(new FinalizationThreadWorker(), FINALIZER_THREAD_NAME);
        myFinalizerThread.start();
      }

      myFinalizationQueue.put(keyForPage(page), request);
      if (myFinalizationQueue.size() > 5000) {
        flushQueueNow = true;
      }
    }

    if (flushQueueNow) {
      flushFinalizationQueue(4000);
    }
    else {
      synchronized (finalizationMonitor) {
        finalizationMonitor.notifyAll();
      }
    }
  }

  private class FinalizationThreadWorker implements Runnable {
    public void run() {
      //noinspection InfiniteLoopStatement
      while (true) {
        try {
          FinalizationRequest request = retreiveFinalizationRequest();

          if (request != null) {
            processFinalizationRequest(request);
            Thread.sleep(5);
          }
          else {
            synchronized (finalizationMonitor) {
              try {
                finalizationMonitor.wait(10);
              }
              catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            }
          }
        }
        catch (Throwable e) {
          LOG.info(e);
        }
      }
    }
  }

  private void processFinalizationRequest(final FinalizationRequest request) {
    final Page page = request.page;
    try {
      page.flushIfFinalizationIdIsEqualTo(request.finalizationId);
    }
    finally {
      synchronized (lock) {
        myFinalizationQueue.remove(page.getKey());
      }
      page.recycleIfFinalizationIdIsEqualTo(request.finalizationId);
    }
  }

  @Nullable
  private FinalizationRequest retreiveFinalizationRequest() {
    FinalizationRequest request = null;
    synchronized (lock) {
      if (!myFinalizationQueue.isEmpty()) {
        final PoolPageKey key;
        if (lastFinalizedKey == null) {
          key = myFinalizationQueue.firstKey();
        }
        else {
          final SortedMap<PoolPageKey, FinalizationRequest> tail = myFinalizationQueue.tailMap(lastFinalizedKey);
          key = tail.isEmpty() ? myFinalizationQueue.firstKey() : tail.firstKey();
        }
        lastFinalizedKey = key;
        request = myFinalizationQueue.get(key);
      }
      else {
        lastFinalizedKey = null;
      }
    }
    return request;
  }
}
