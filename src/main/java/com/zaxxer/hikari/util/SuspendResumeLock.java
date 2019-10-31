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

import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.concurrent.Semaphore;

/**
 * 基于信号量的数据库连接池的暂停锁
 * This class implements a lock that can be used to suspend and resume the pool.  It
 * also provides a faux implementation that is used when the feature is disabled that
 * hopefully gets fully "optimized away" by the JIT.
 *
 * @author Brett Wooldridge
 */
public class SuspendResumeLock
{
   public static final SuspendResumeLock FAUX_LOCK = new SuspendResumeLock(false) {
      @Override
      public void acquire() {}

      @Override
      public void release() {}

      @Override
      public void suspend() {}

      @Override
      public void resume() {}
   };
   //最大并发数
   private static final int MAX_PERMITS = 10000;
   //信号量
   private final Semaphore acquisitionSemaphore;

   /**
    * 默认构造器
    * Default constructor
    */
   public SuspendResumeLock()
   {
      this(true);
   }

   /**
    * 私有构造函数 ，用于创建公平的信号量
    * @param createSemaphore
    */
   private SuspendResumeLock(final boolean createSemaphore)
   {
      acquisitionSemaphore = (createSemaphore ? new Semaphore(MAX_PERMITS, true) : null);
   }

   /**
    * 获取一个令牌
    * @throws SQLException
    */
   public void acquire() throws SQLException
   {
      //尝试获取令牌
      if (acquisitionSemaphore.tryAcquire()) {
         return;
      }
      else if (Boolean.getBoolean("com.zaxxer.hikari.throwIfSuspended")) {
         throw new SQLTransientException("The pool is currently suspended and configured to throw exceptions upon acquisition");
      }
      //获取一个不可中断的令牌
      acquisitionSemaphore.acquireUninterruptibly();
   }

   /**
    * 归还令牌
    */
   public void release()
   {
      acquisitionSemaphore.release();
   }

   /**
    * 将令牌全取走，达到暂停状态
    */
   public void suspend()
   {
      acquisitionSemaphore.acquireUninterruptibly(MAX_PERMITS);
   }

   /**
    * 归还所有令牌，恢复正常状态
    */
   public void resume()
   {
      acquisitionSemaphore.release(MAX_PERMITS);
   }
}
