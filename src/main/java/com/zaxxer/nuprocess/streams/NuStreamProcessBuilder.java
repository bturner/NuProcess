/*
 * Copyright (C) 2015 Brett Wooldridge
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

package com.zaxxer.nuprocess.streams;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.reactivestreams.Subscriber;

import com.zaxxer.nuprocess.NuAbstractProcessHandler;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcess.Stream;
import com.zaxxer.nuprocess.NuProcessBuilder;

public class NuStreamProcessBuilder
{
   private final NuProcessBuilder builder;

   public NuStreamProcessBuilder(final NuProcessBuilder builder)
   {
      this.builder = builder;
   }

   public NuStreamProcess start()
   {
      return start(null);
   }

   public NuStreamProcess start(final NuStreamProcessHandler streamProcessHandler)
   {
      NuStreamProcessImpl streamProcess = new NuStreamProcessImpl();

      BridgeProcessHandler bridgeProcessHandler = new BridgeProcessHandler(streamProcess, streamProcessHandler);
      builder.setProcessListener(bridgeProcessHandler);

      NuProcess nuProcess = builder.start();

      streamProcess.setNuProcess(nuProcess);
      streamProcess.setStreamProcessHandler(bridgeProcessHandler);

      return streamProcess;
   }

   static class BridgeProcessHandler extends NuAbstractProcessHandler
   {
      private final AtomicLong stdinRequests;
      private final AtomicLong stdoutRequests;
      private final AtomicLong stderrRequests;
      private final NuStreamProcess streamProcess;
      private final NuStreamProcessHandler streamProcessHandler;
      
      private NuProcess nuProcess;
      private Subscriber<? super ByteBuffer> stdinSubscriber;
      private Subscriber<? super ByteBuffer> stdoutSubscriber;
      private Subscriber<? super ByteBuffer> stderrSubscriber;

      public BridgeProcessHandler(final NuStreamProcess streamProcess, final NuStreamProcessHandler streamProcessHandler)
      {
         this.streamProcessHandler = streamProcessHandler;
         this.streamProcess = streamProcess;
         this.stdinRequests = new AtomicLong();
         this.stdoutRequests = new AtomicLong();
         this.stderrRequests = new AtomicLong();
      }

      @Override
      public void onPreStart(final NuProcess nuProcess)
      {
         this.nuProcess = nuProcess;
         if (streamProcessHandler != null) {
            streamProcessHandler.onPreStart(streamProcess);
         }
      }

      @Override
      public void onStart(final NuProcess nuProcess)
      {
         if (streamProcessHandler != null) {
            streamProcessHandler.onStart(streamProcess);
         }
      }

      @Override
      public void onExit(int statusCode)
      {
         // TODO do we ever need to call stdinSubscriber.onError() ?
         stdinSubscriber.onComplete();
         stdinRequests.set(-1);;

         super.onExit(statusCode);
      }

      @Override
      public boolean onStdinReady(final ByteBuffer buffer)
      {
         stdinSubscriber.onNext(buffer);
         buffer.flip();
         return stdinRequests.decrementAndGet() > 0;
      }

      @Override
      public boolean onStdout(final ByteBuffer buffer, final boolean closed)
      {
         if (buffer.hasRemaining()) {
            stdoutSubscriber.onNext(buffer);
         }

         if (closed) {
            stdoutSubscriber.onComplete();
            stdoutRequests.set(-1);
         }

         return !closed && stdoutRequests.decrementAndGet() > 0;
      }
      
      @Override
      public boolean onStderr(final ByteBuffer buffer, final boolean closed)
      {
         stderrSubscriber.onNext(buffer);

         if (closed) {
            stderrSubscriber.onComplete();
            stderrRequests.set(-1);
         }

         return !closed && stderrRequests.decrementAndGet() > 0;
      }

      void setSubscriber(final Stream stream, final Subscriber<? super ByteBuffer> subscriber)
      {
         switch (stream)
         {
            case STDIN:
               stdinSubscriber = subscriber;
               stdinRequests.set(0);
               break;
            case STDOUT:
               stdoutSubscriber = subscriber;
               stdoutRequests.set(0);
               break;
            case STDERR:
               stderrSubscriber = subscriber;
               stderrRequests.set(0);
               break;
         }
      }
      
      void request(Stream stream, long n)
      {
         switch (stream) {
         case STDOUT:
            if (stdoutRequests.get() >= 0 && stdoutRequests.getAndAdd(n) == 0) {
               nuProcess.want(stream);
            }
            break;
         case STDIN:
            if (stdinRequests.get() >= 0 && stdinRequests.getAndAdd(n) == 0) {
               nuProcess.want(stream);
            }
            break;
         case STDERR:
            if (stderrRequests.get() >= 0 && stderrRequests.getAndAdd(n) == 0) {
               nuProcess.want(stream);
            }
            break;
         }
      }

      void cancel(Stream stream)
      {
         switch (stream) {
         case STDOUT:
            stdoutRequests.set(-1);
            break;
         case STDIN:
            stdinRequests.set(-1);
            break;
         case STDERR:
            stderrRequests.set(-1);
            break;
         }         
      }
   }
}
