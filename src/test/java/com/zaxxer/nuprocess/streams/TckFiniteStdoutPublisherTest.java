package com.zaxxer.nuprocess.streams;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.slf4j.LoggerFactory;
import org.testng.SkipException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.zaxxer.nuprocess.NuProcessBuilder;

@Test(singleThreaded = true, threadPoolSize = 1)
public class TckFiniteStdoutPublisherTest extends PublisherVerification<ByteBuffer>
{
   private static final long DEFAULT_TIMEOUT = 300L;
   private static final long DEFAULT_GC_TIMEOUT = 1000L;
   private String command;

   public TckFiniteStdoutPublisherTest()
   {
      super(new TestEnvironment(DEFAULT_TIMEOUT), DEFAULT_GC_TIMEOUT);
      command = "cat";
      if (System.getProperty("os.name").toLowerCase().contains("win")) {
         command = "src\\test\\java\\com\\zaxxer\\nuprocess\\cat.exe";
      }
   }

   @BeforeMethod
   protected void startSession(Method method) throws Exception
   {
      LoggerFactory.getLogger(this.getClass()).info("Starting test method: {}", method.getName());
   }

   @Override
   public Publisher<ByteBuffer> createPublisher(long elements)
   {
      LoggerFactory.getLogger(this.getClass()).info("Starting new test #################################################");
      NuProcessBuilder builder = new NuProcessBuilder(command, "src/test/resources/chunk.txt");
      NuStreamProcessBuilder streamBuilder = new NuStreamProcessBuilder(builder);
      NuStreamProcess process = streamBuilder.start();

      NuStreamPublisher nuStreamPublisher = process.getStdoutPublisher();

      return new TckFiniteStdoutPublisher(nuStreamPublisher, elements);
   }

   @Override
   public Publisher<ByteBuffer> createFailedPublisher()
   {
      throw new SkipException("Not implemented");
   }

   private static class TckFiniteStdoutPublisher implements Publisher<ByteBuffer>
   {
      private final NuStreamPublisher publisher;
      private final long elements;
      private ProxySubscriber proxySubscriber;

      TckFiniteStdoutPublisher(final NuStreamPublisher nuStreamPublisher, final long elements)
      {
         this.publisher = nuStreamPublisher;
         this.elements = elements;
      }

      @Override
      public void subscribe(Subscriber<? super ByteBuffer> sub)
      {
         this.proxySubscriber = new ProxySubscriber(sub);
         publisher.subscribe(proxySubscriber);
      }

      class ProxySubscriber implements Subscriber<ByteBuffer>
      {
         private Subscriber<? super ByteBuffer> subscriber;

         public ProxySubscriber(Subscriber<? super ByteBuffer> subscriber)
         {
            this.subscriber = subscriber;
         }

         @Override
         public void onComplete()
         {
            subscriber.onComplete();
         }
         
         @Override
         public void onError(Throwable t)
         {
            subscriber.onError(t);
         }
         
         @Override
         public void onNext(ByteBuffer buffer)
         {
            subscriber.onNext(buffer);
         }
         
         @Override
         public void onSubscribe(Subscription subscription)
         {
            subscriber.onSubscribe(subscription);
            subscription.request(elements);
         }
      }
   }
}
