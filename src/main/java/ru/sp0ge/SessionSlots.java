package ru.sp0ge;
import java.util.*;
/** Atomic capacity by connection identity, including players still logging in. */
public final class SessionSlots {
  private final int limit;
  private final Set<Object> sessions=Collections.newSetFromMap(new IdentityHashMap<>());
  public SessionSlots(int limit){this.limit=limit;}
  public synchronized boolean acquire(Object player){if(sessions.contains(player))return true;if(sessions.size()>=limit)return false;sessions.add(player);return true;}
  public synchronized void release(Object player){sessions.remove(player);}
}
