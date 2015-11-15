/*
 * Copyright (C) 2013 Brett Wooldridge
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

package com.zaxxer.nuprocess.linux;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcess.Stream;
import com.zaxxer.nuprocess.internal.BaseEventProcessor;
import com.zaxxer.nuprocess.internal.LibC;

import static com.zaxxer.nuprocess.internal.LibC.WIFEXITED;
import static com.zaxxer.nuprocess.internal.LibC.WEXITSTATUS;
import static com.zaxxer.nuprocess.internal.LibC.WIFSIGNALED;
import static com.zaxxer.nuprocess.internal.LibC.WTERMSIG;

/**
 * @author Brett Wooldridge
 */
class ProcessEpoll extends BaseEventProcessor<LinuxProcess>
{
   private static final int EVENT_POOL_SIZE = 32;

   private final Logger LOGGER = LoggerFactory.getLogger(ProcessEpoll.class);

   private int epoll;
   private EpollEvent triggeredEvent;
   private List<LinuxProcess> deadPool;

   private static BlockingQueue<EpollEvent> eventPool;

   ProcessEpoll()
   {
      epoll = LibEpoll.epoll_create(1024);
      if (epoll < 0) {
         throw new RuntimeException("Unable to create kqueue: " + Native.getLastError());
      }

      triggeredEvent = new EpollEvent();
      deadPool = new LinkedList<LinuxProcess>();
      eventPool = new ArrayBlockingQueue<>(EVENT_POOL_SIZE);
      for (int i = 0; i < EVENT_POOL_SIZE; i++) {
         eventPool.add(new EpollEvent());
      }
   }

   // ************************************************************************
   //                         IEventProcessor methods
   // ************************************************************************

   @Override
   public void registerProcess(LinuxProcess process)
   {
      if (shutdown) {
         return;
      }

      int stdin  = process.getStdin().get();
      int stdout = process.getStdout().get();
      int stderr = process.getStderr().get();

      pidToProcessMap.put(process.getUid(), process);
      fildesToProcessMap.put(process.getKey(stdout), process);
      fildesToProcessMap.put(process.getKey(stderr), process);
      fildesToProcessMap.put(process.getKey(stdin), process);

      // In order for soft-exit detection to work, we must allways be listening for HUP/ERR on
      // stdout and stderr.
      try {
         EpollEvent epEvent = eventPool.take();
         epEvent.events = LibEpoll.EPOLLHUP | LibEpoll.EPOLLERR;
         epEvent.data.fd = stdout;
         LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdout, epEvent);
         LOGGER.debug("action(EPOLL_CTL_ADD) EPOLLHUP | EPOLLERR on stdout({}) for {}", stdout, process);
         epEvent.data.fd = stderr;
         LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stderr, epEvent);
         LOGGER.debug("action(EPOLL_CTL_ADD) EPOLLHUP | EPOLLERR on stderr({}) for {}", stderr, process);
         eventPool.offer(epEvent);
      }
      catch (InterruptedException e) {
         throw new RuntimeException("Interrupted during acquire epoll event for registration.");
      }
   }

   @Override
   public void queueRead(LinuxProcess process, Stream stream)
   {
      final int fd;
      switch (stream) {
         case STDOUT:
            fd = process.getStdout().get();
            break;
         case STDERR:
            fd = process.getStderr().get();
            break;
         default:
            throw new IllegalArgumentException(stream.name() + " is not a valid Stream for queueRead()");
      }

      EpollEvent event = null;
      try {
         event = eventPool.take();
         event.events = LibEpoll.EPOLLIN | LibEpoll.EPOLLONESHOT;
         event.data.fd = fd;
         synchronized (process) {
	         int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, fd, event);
	         if (rc == -1) {
	            rc = Native.getLastError();
	            if (rc == LibEpoll.ENOENT) {
	               rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, fd, event);
	               LOGGER.debug("queueRead(): action(EPOLL_CTL_ADD) EPOLLIN | EPOLLONESHOT on {} for {}", stream, process);
	            }
	            else {
	               throw new RuntimeException("Unable to register new events to epoll, errorcode: " + rc);
	            }
	         }
	         else {
	            LOGGER.debug("queueRead(): action(EPOLL_CTL_MOD) EPOLLIN | EPOLLONESHOT on {} for {}", stream, process);
	         }
         }
      }
      catch (InterruptedException ie) {
         throw new RuntimeException(ie);
      }  
      finally {
         if (event != null) {
            eventPool.offer(event);
         }
      }
   }

   @Override
   public void queueWrite(LinuxProcess process)
   {
      if (shutdown) {
         return;
      }

      int stdin = process.getStdin().get();
      if (stdin == -1) {
        return;
      }

      EpollEvent event = null;
      try {
         event = eventPool.take();
         event.events = LibEpoll.EPOLLOUT | LibEpoll.EPOLLONESHOT | LibEpoll.EPOLLRDHUP | LibEpoll.EPOLLHUP;
         event.data.fd = stdin;
         int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, stdin, event);
         if (rc == -1) {
            rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, stdin, event);
            rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdin, event);
            LOGGER.debug("queueWrite(): action(EPOLL_CTL_ADD) EPOLLOUT | EPOLLONESHOT | EPOLLRDHUP | EPOLLHUP on stdin({}) for {}", stdin, process);
         }
         else {
            LOGGER.debug("queueWrite(): action(EPOLL_CTL_MOD) EPOLLOUT | EPOLLONESHOT | EPOLLRDHUP | EPOLLHUP on stdin({}) for {}", stdin, process);
         }

         if (rc == -1) {
            rc = Native.getLastError();
            throw new RuntimeException("Unable to register new event to epoll queue");
         }
      }
      catch (InterruptedException ie) {
         throw new RuntimeException(ie);
      }
      finally {
         if (event != null) {
            eventPool.offer(event);
         }
      }
   }

   @Override
   public void closeStdin(LinuxProcess process)
   {
      int stdin = process.getStdin().getAndSet(-1);
      if (stdin != -1) {
         fildesToProcessMap.remove(stdin);
         LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, stdin, null);
      }
   }

   @Override
   public boolean process()
   {
      try {
         int nev = LibEpoll.epoll_wait(epoll, triggeredEvent, 1, DEADPOOL_POLL_INTERVAL);
         if (nev == -1) {
            throw new RuntimeException("Error waiting for epoll");
         }

         if (nev == 0) {
            return false;
         }

         EpollEvent epEvent = triggeredEvent;
         int ident = epEvent.data.fd;
         int events = epEvent.events;

         LinuxProcess linuxProcess = fildesToProcessMap.get(ident);
         if (linuxProcess == null) {
            return true;
         }

         synchronized (linuxProcess) {
	         if ((events & LibEpoll.EPOLLIN) != 0) // stdout/stderr data available to read
	         {
	            boolean again = false;
	            if (ident == linuxProcess.getStdout().get()) {
	               again = linuxProcess.readStdout(NuProcess.BUFFER_CAPACITY);
	            }
	            else {
	               again = linuxProcess.readStderr(NuProcess.BUFFER_CAPACITY);
	            }
	
	            epEvent.events = LibEpoll.EPOLLHUP | LibEpoll.EPOLLERR;
	            if (again) {
	               epEvent.events = epEvent.events | LibEpoll.EPOLLIN | LibEpoll.EPOLLONESHOT;
	               LOGGER.debug("process(): action(EPOLL_CTL_MOD) EPOLLIN | EPOLLONESHOT | EPOLLHUP | EPOLLERR on stdout/err({}) for {}", ident, linuxProcess);
	            }
	            else {
	                 LOGGER.debug("process(): action(EPOLL_CTL_MOD) EPOLLHUP | EPOLLERR on stdout/err({}) for {}", ident, linuxProcess);
	            }
	            LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, ident, epEvent);
	         }
	         else if ((events & LibEpoll.EPOLLOUT) != 0) // Room in stdin pipe available to write
	         {
	            if (linuxProcess.getStdin().get() != -1) {
	               if (linuxProcess.writeStdin(NuProcess.BUFFER_CAPACITY)) {
	                  epEvent.events = LibEpoll.EPOLLOUT | LibEpoll.EPOLLONESHOT | LibEpoll.EPOLLRDHUP | LibEpoll.EPOLLHUP;
	                  LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, ident, epEvent);
	               }
	            }
	         }
	         else if ((events & LibEpoll.EPOLLHUP) != 0 || (events & LibEpoll.EPOLLRDHUP) != 0 || (events & LibEpoll.EPOLLERR) != 0) {
	            LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, ident, null);
	            if (ident == linuxProcess.getStdout().get()) {
                  LOGGER.debug("process(): Received EPOLLHUP on stdout({}) for {}", ident, linuxProcess);
	               linuxProcess.readStdout(-1);
	            }
	            else if (ident == linuxProcess.getStderr().get()) {
                  LOGGER.debug("process(): Received EPOLLHUP on stderr({}) for {}", ident, linuxProcess);
	               linuxProcess.readStderr(-1);
	            }
	            else if (ident == linuxProcess.getStdin().get()) {
                  LOGGER.debug("process(): Received EPOLLHUP on stdin({}) for {}", ident, linuxProcess);
	               linuxProcess.closeStdin(true);
	            }
	         }
         }

         if (linuxProcess.isSoftExit()) {
            cleanupProcess(linuxProcess);
         }

         return true;
      }
      finally {
         triggeredEvent.clear();
         checkDeadPool();
      }
   }

   // ************************************************************************
   //                             Private methods
   // ************************************************************************
   AtomicInteger count = new AtomicInteger();

   private void cleanupProcess(LinuxProcess linuxProcess)
   {
      pidToProcessMap.remove(linuxProcess.getPid());
      fildesToProcessMap.remove(linuxProcess.getStdin().get());
      fildesToProcessMap.remove(linuxProcess.getStdout().get());
      fildesToProcessMap.remove(linuxProcess.getStderr().get());

      //        linuxProcess.close(linuxProcess.getStdin());
      //        linuxProcess.close(linuxProcess.getStdout());
      //        linuxProcess.close(linuxProcess.getStderr());

      if (linuxProcess.cleanlyExitedBeforeProcess.get()) {
         linuxProcess.onExit(0);
         return;
      }

      IntByReference ret = new IntByReference();
      int rc = LibC.waitpid(linuxProcess.getPid(), ret, LibC.WNOHANG);

      if (rc == 0) {
         deadPool.add(linuxProcess);
      }
      else if (rc < 0) {
         linuxProcess.onExit((Native.getLastError() == LibC.ECHILD) ? Integer.MAX_VALUE : Integer.MIN_VALUE);
      }
      else {
         int status = ret.getValue();
         if (WIFEXITED(status)) {
            status = WEXITSTATUS(status);
            if (status == 127) {
               linuxProcess.onExit(Integer.MIN_VALUE);
            }
            else {
               linuxProcess.onExit(status);
            }
         }
         else if (WIFSIGNALED(status)) {
            linuxProcess.onExit(WTERMSIG(status));
         }
         else {
            linuxProcess.onExit(Integer.MIN_VALUE);
         }
      }
   }

   private void checkDeadPool()
   {
      if (deadPool.isEmpty()) {
         return;
      }

      IntByReference ret = new IntByReference();
      Iterator<LinuxProcess> iterator = deadPool.iterator();
      while (iterator.hasNext()) {
         LinuxProcess process = iterator.next();
         int rc = LibC.waitpid(process.getPid(), ret, LibC.WNOHANG);
         if (rc == 0) {
            continue;
         }

         iterator.remove();
         if (rc < 0) {
            process.onExit((Native.getLastError() == LibC.ECHILD) ? Integer.MAX_VALUE : Integer.MIN_VALUE);
            continue;
         }

         int status = ret.getValue();
         if (WIFEXITED(status)) {
            status = WEXITSTATUS(status);
            if (status == 127) {
               process.onExit(Integer.MIN_VALUE);
            }
            else {
               process.onExit(status);
            }
         }
         else if (WIFSIGNALED(status)) {
            process.onExit(WTERMSIG(status));
         }
         else {
            process.onExit(Integer.MIN_VALUE);
         }
      }
   }
}
