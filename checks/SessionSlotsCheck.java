package ru.sp0ge;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class SessionSlotsCheck {
 public static void main(String[] args)throws Exception{
  SessionSlots slots=new SessionSlots(64);ExecutorService pool=Executors.newFixedThreadPool(32);AtomicInteger accepted=new AtomicInteger();
  for(int i=0;i<1000;i++)pool.submit(()->{if(slots.acquire(new Object()))accepted.incrementAndGet();});
  pool.shutdown();pool.awaitTermination(10,TimeUnit.SECONDS);if(accepted.get()!=64)throw new AssertionError("Concurrent limit: "+accepted);
  SessionSlots two=new SessionSlots(1);Object first=new Object(),second=new Object();
  if(!two.acquire(first)||!two.acquire(first)||two.acquire(second))throw new AssertionError("Identity reservation");
  two.release(first);if(!two.acquire(second))throw new AssertionError("Slot release");System.out.println("PASS atomic 64-player admission and slot release");
 }
}
