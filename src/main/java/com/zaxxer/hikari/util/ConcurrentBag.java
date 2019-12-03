/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
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
package com.zaxxer.hikari.util;

import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.zaxxer.hikari.util.ClockSource.currentTime;
import static com.zaxxer.hikari.util.ClockSource.elapsedNanos;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.*;
import static java.lang.Thread.yield;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.locks.LockSupport.parkNanos;


/**
 * This is a specialized concurrent bag that achieves superior performance
 * to LinkedBlockingQueue and LinkedTransferQueue for the purposes of a
 * connection pool.  It uses ThreadLocal storage when possible to avoid
 * locks, but resorts to scanning a common collection if there are no
 * available items in the ThreadLocal list.  Not-in-use items in the
 * ThreadLocal lists can be "stolen" when the borrowing thread has none
 * of its own.  It is a "lock-less" implementation using a specialized
 * AbstractQueuedLongSynchronizer to manage cross-thread signaling.
 * <p>
 * Note that items that are "borrowed" from the bag are not actually
 * removed from any collection, so garbage collection will not occur
 * even if the reference is abandoned.  Thus care must be taken to
 * "requite" borrowed objects otherwise a memory leak will result.  Only
 * the "remove" method can completely remove an object from the bag.
 *
 * @param <T> the templated type to store in the bag
 * @author Brett Wooldridge
 */
public class ConcurrentBag<T extends IConcurrentBagEntry> implements AutoCloseable {
   private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentBag.class);
   //存储entry的Cow集合
   private final CopyOnWriteArrayList<T> sharedList;
   //标记ThreadLocal中的引用是否为弱引用
   private final boolean weakThreadLocals;
   //存储entry的ThreadLocal（线程隔离）
   private final ThreadLocal<List<Object>> threadList;
   //监听器
   private final IBagStateListener listener;
   //计数器-记录等待数量
   private final AtomicInteger waiters;
   //标记-记录是否已经关闭
   private volatile boolean closed;
   //一对一的传递entry
   private final SynchronousQueue<T> handoffQueue;
   //entry接口，Hikari中PoolEntry是他的唯一实现（也就是ConcurrentBag暂时只能存放PoolEntry）
   public interface IConcurrentBagEntry {
      /**
       * 状态-没有使用
       */
      int STATE_NOT_IN_USE = 0;
      /**
       * 状态-在使用
       */
      int STATE_IN_USE = 1;
      /**
       * 状态-已经被移除
       */
      int STATE_REMOVED = -1;
      /**
       * 状态-保留状态
       */
      int STATE_RESERVED = -2;
      //cas修改状态
      boolean compareAndSet(int expectState, int newState);
      //直接设置状态
      void setState(int newState);
      //获取状态
      int getState();
   }
   //监听添加元素的接口
   public interface IBagStateListener {
      void addBagItem(int waiting);
   }

   /**
    * 创建ConcurrentBag
    * Construct a ConcurrentBag with the specified listener.
    *
    * @param listener the IBagStateListener to attach to this bag
    */
   public ConcurrentBag(final IBagStateListener listener) {
      this.listener = listener;
      this.weakThreadLocals = useWeakThreadLocals();
      //创建一个先进先出的队列
      this.handoffQueue = new SynchronousQueue<>(true);
      this.waiters = new AtomicInteger();
      this.sharedList = new CopyOnWriteArrayList<>();
      //如果使用弱引用，则使用ArrayList初始化集合，否则使用FastList
      if (weakThreadLocals) {
         this.threadList = ThreadLocal.withInitial(() -> new ArrayList<>(16));
      } else {
         this.threadList = ThreadLocal.withInitial(() -> new FastList<>(IConcurrentBagEntry.class, 16));
      }
   }

   /**
    * 获取entry，可以指定超时时间
    * The method will borrow a BagEntry from the bag, blocking for the
    * specified timeout if none are available.
    *
    * @param timeout  how long to wait before giving up, in units of unit
    * @param timeUnit a <code>TimeUnit</code> determining how to interpret the timeout parameter
    * @return a borrowed instance from the bag or null if a timeout occurs
    * @throws InterruptedException if interrupted while waiting
    */
   public T borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException {
      // Try the thread-local list first
      //先尝试从ThreadLocal中取，避免竞争
      final List<Object> list = threadList.get();
      for (int i = list.size() - 1; i >= 0; i--) {
         final Object entry = list.remove(i);
         @SuppressWarnings("unchecked") final T bagEntry = weakThreadLocals ? ((WeakReference<T>) entry).get() : (T) entry;
         //判断entry是否在使用中
         if (bagEntry != null && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
            return bagEntry;
         }
      }
      //ThreadLocal中没有取到entry，则去共享队列中拿
      // Otherwise, scan the shared list ... then poll the handoff queue
      //计数器+1
      final int waiting = waiters.incrementAndGet();
      try {
         for (T bagEntry : sharedList) {
            //判断entry是否在使用中
            if (bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
               // If we may have stolen another waiter's connection, request another bag add.
               //如果有两个以上的线程在竞争获取entry,则触发监听器，创建entry
               if (waiting > 1) {
                  listener.addBagItem(waiting - 1);
               }
               return bagEntry;
            }
         }
         //没有从集合中获取entry，则触发监听器，创建entry
         listener.addBagItem(waiting);

         timeout = timeUnit.toNanos(timeout);
         do {
            final long start = currentTime();
            //尝试从队列中获取entry
            final T bagEntry = handoffQueue.poll(timeout, NANOSECONDS);
            //如果获取到了，将entry设置为使用中，并返回
            if (bagEntry == null || bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
               return bagEntry;
            }
            timeout -= elapsedNanos(start);
            //循环，直到超时，返回null
         } while (timeout > 10_000);

         return null;
      } finally {
         //释放计数器（-1）
         waiters.decrementAndGet();
      }
   }

   /**
    * 归还entry
    * This method will return a borrowed object to the bag.  Objects
    * that are borrowed from the bag but never "requited" will result
    * in a memory leak.
    *
    * @param bagEntry the value to return to the bag
    * @throws NullPointerException  if value is null
    * @throws IllegalStateException if the bagEntry was not borrowed from the bag
    */
   public void requite(final T bagEntry) {
      bagEntry.setState(STATE_NOT_IN_USE);

      for (int i = 0; waiters.get() > 0; i++) {
         //保证entry没有被外界使用
         if (bagEntry.getState() != STATE_NOT_IN_USE ||
            //如果entry已经入队
            handoffQueue.offer(bagEntry)) {
            return;
         }
         //线程挂起10纳秒(当i==255的倍数)
         else if ((i & 0xff) == 0xff) {
            parkNanos(MICROSECONDS.toNanos(10));
         } else {
            //让出时间片
            yield();
         }
      }

      final List<Object> threadLocalList = threadList.get();
      //如果当前线程存储的entry集合大小小于50，则将entry添加到ThreadLocal中
      if (threadLocalList.size() < 50) {
         threadLocalList.add(weakThreadLocals ? new WeakReference<>(bagEntry) : bagEntry);
      }
   }

   /**
    * 添加一个新的entry
    * Add a new object to the bag for others to borrow.
    *
    * @param bagEntry an object to add to the bag
    */
   public void add(final T bagEntry) {
      if (closed) {
         LOGGER.info("ConcurrentBag has been closed, ignoring add()");
         throw new IllegalStateException("ConcurrentBag has been closed, ignoring add()");
      }

      sharedList.add(bagEntry);

      //自旋，直到有entry被拿走，或者没有线程在等待获取entry
      // spin until a thread takes it or none are waiting
      while (waiters.get() > 0 && bagEntry.getState() == STATE_NOT_IN_USE && !handoffQueue.offer(bagEntry)) {
         yield();
      }
   }

   /**
    * 移除一个entry（移除的entry必须要是STATE_IN_USE，或者STATE_RESERVED状态）
    * Remove a value from the bag.  This method should only be called
    * with objects obtained by <code>borrow(long, TimeUnit)</code> or <code>reserve(T)</code>
    *
    * @param bagEntry the value to remove
    * @return true if the entry was removed, false otherwise
    * @throws IllegalStateException if an attempt is made to remove an object
    *                               from the bag that was not borrowed or reserved first
    */
   public boolean remove(final T bagEntry) {
      if (!bagEntry.compareAndSet(STATE_IN_USE, STATE_REMOVED) && !bagEntry.compareAndSet(STATE_RESERVED, STATE_REMOVED) && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that was not borrowed or reserved: {}", bagEntry);
         return false;
      }

      final boolean removed = sharedList.remove(bagEntry);
      if (!removed && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that does not exist: {}", bagEntry);
      }

      return removed;
   }

   /**
    * AutoClose接口实现，停止ConcurrentBag服务
    * Close the bag to further adds.
    */
   @Override
   public void close() {
      closed = true;
   }

   /**
    * 返回当前指定状态的集合
    * This method provides a "snapshot" in time of the BagEntry
    * items in the bag in the specified state.  It does not "lock"
    * or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in list before performing any action on them.
    *
    * @param state one of the {@link IConcurrentBagEntry} states
    * @return a possibly empty list of objects having the state specified
    */
   public List<T> values(final int state) {
      final List<T> list = sharedList.stream().filter(e -> e.getState() == state).collect(Collectors.toList());
      Collections.reverse(list);
      return list;
   }

   /**
    * 返回当前集合的快照（原样复制当前集合）
    * This method provides a "snapshot" in time of the bag items.  It
    * does not "lock" or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in the list, or understand the concurrency implications of
    * modifying items, before performing any action on them.
    *
    * @return a possibly empty list of (all) bag items
    */
   @SuppressWarnings("unchecked")
   public List<T> values() {
      return (List<T>) sharedList.clone();
   }

   /**
    * 使entry变为不可用
    * The method is used to make an item in the bag "unavailable" for
    * borrowing.  It is primarily used when wanting to operate on items
    * returned by the <code>values(int)</code> method.  Items that are
    * reserved can be removed from the bag via <code>remove(T)</code>
    * without the need to unreserve them.  Items that are not removed
    * from the bag can be make available for borrowing again by calling
    * the <code>unreserve(T)</code> method.
    *
    * @param bagEntry the item to reserve
    * @return true if the item was able to be reserved, false otherwise
    */
   public boolean reserve(final T bagEntry) {
      return bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
   }

   /**
    * 使entry变为可用
    * This method is used to make an item reserved via <code>reserve(T)</code>
    * available again for borrowing.
    *
    * @param bagEntry the item to unreserve
    */
   public void unreserve(final T bagEntry) {
      if (bagEntry.compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
         // spin until a thread takes it or none are waiting
         while (waiters.get() > 0 && !handoffQueue.offer(bagEntry)) {
            yield();
         }
      } else {
         LOGGER.warn("Attempt to relinquish an object to the bag that was not reserved: {}", bagEntry);
      }
   }

   /**
    * Get the number of threads pending (waiting) for an item from the
    * bag to become available.
    *
    * @return the number of threads waiting for items from the bag
    */
   public int getWaitingThreadCount() {
      return waiters.get();
   }

   /**
    * 获取指定状态entry的数量
    * Get a count of the number of items in the specified state at the time of this call.
    *
    * @param state the state of the items to count
    * @return a count of how many items in the bag are in the specified state
    */
   public int getCount(final int state) {
      int count = 0;
      for (IConcurrentBagEntry e : sharedList) {
         if (e.getState() == state) {
            count++;
         }
      }
      return count;
   }

   public int[] getStateCounts() {
      final int[] states = new int[6];
      for (IConcurrentBagEntry e : sharedList) {
         ++states[e.getState()];
      }
      states[4] = sharedList.size();
      states[5] = waiters.get();

      return states;
   }

   /**
    * Get the total number of items in the bag.
    *
    * @return the number of items in the bag
    */
   public int size() {
      return sharedList.size();
   }

   public void dumpState() {
      sharedList.forEach(entry -> LOGGER.info(entry.toString()));
   }

   /**
    * 确定是否需要使用弱引用
    * Determine whether to use WeakReferences based on whether there is a
    * custom ClassLoader implementation sitting between this class and the
    * System ClassLoader.
    *
    * @return true if we should use WeakReferences in our ThreadLocals, false otherwise
    */
   private boolean useWeakThreadLocals() {
      try {
         if (System.getProperty("com.zaxxer.hikari.useWeakReferences") != null) {   // undocumented manual override of WeakReference behavior
            return Boolean.getBoolean("com.zaxxer.hikari.useWeakReferences");
         }

         return getClass().getClassLoader() != ClassLoader.getSystemClassLoader();
      } catch (SecurityException se) {
         return true;
      }
   }
}
