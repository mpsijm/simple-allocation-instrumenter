/*
 * Copyright (C) 2009 Google Inc.
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

package com.google.monitoring.runtime.instrumentation;

import java.lang.instrument.Instrumentation;
import java.util.Objects;

/**
 * The logic for recording allocations, called from bytecode rewritten by {@link
 * AllocationInstrumenter}.
 */
public class AllocationRecorder {
  static {
    // Sun's JVMs in 1.5.0_06 and 1.6.0{,_01} have a bug where calling
    // Instrumentation.getObjectSize() during JVM shutdown triggers a
    // JVM-crashing assert in JPLISAgent.c, so we make sure to not call it after
    // shutdown.  There can still be a race here, depending on the extent of the
    // JVM bug, but this seems to be good enough.
    // instrumentation is volatile to make sure the threads reading it (in
    // recordAllocation()) see the updated value; we could do more
    // synchronization but it's not clear that it'd be worth it, given the
    // ambiguity of the bug we're working around in the first place.
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                setInstrumentation(null);
              }
            });
  }

  // See the comment above the addShutdownHook in the static block above
  // for why this is volatile.
  private static volatile Instrumentation instrumentation = null;

  static Instrumentation getInstrumentation() {
    return instrumentation;
  }

  static void setInstrumentation(Instrumentation inst) {
    instrumentation = inst;
  }

  // Mostly because, yes, arrays are faster than collections.
  private static volatile Sampler[] additionalSamplers;

  private static class EmptySampler implements Sampler {
    private static final EmptySampler SINGLETON = new EmptySampler();

    @Override
    public void sampleAllocation(int count, String desc, Object newObj, Instrumentation instr) {
    }
  }

  private static class MultiSampler implements Sampler {
    private static final MultiSampler SINGLETON = new MultiSampler();

    @Override
    public void sampleAllocation(int count, String desc, Object newObj, Instrumentation instr) {
      //noinspection ForLoopReplaceableByForEach - Because that would create extra objects
      for (int i = 0, samplersLength = additionalSamplers.length; i < samplersLength; i++) {
        additionalSamplers[i].sampleAllocation(count, desc, newObj, instr);
      }
    }
  }

  private static volatile Sampler samplerWrapper = EmptySampler.SINGLETON;

  // Protects mutations of additionalSamplers.  Reads are okay because
  // the field is volatile, so anyone who reads additionalSamplers
  // will get a consistent view of it.
  private static final Object samplerLock = new Object();

  // Used for reentrancy checks
  private static final ThreadLocal<Boolean> recordingAllocation = new ThreadLocal<Boolean>();

  /**
   * Adds a {@link Sampler} that will get run <b>every time an allocation is performed from Java
   * code</b>. Use this with <b>extreme</b> judiciousness!
   *
   * @param sampler The sampler to add.
   */
  public static void addSampler(Sampler sampler) {
    synchronized (samplerLock) {
      Sampler[] samplers = additionalSamplers;
      /* create a new list of samplers from the old, adding this sampler */
      if (samplers != null) {
        Sampler[] newSamplers = new Sampler[samplers.length + 1];
        System.arraycopy(samplers, 0, newSamplers, 0, samplers.length);
        newSamplers[samplers.length] = sampler;
        additionalSamplers = newSamplers;
        samplerWrapper = MultiSampler.SINGLETON;
      } else {
        Sampler[] newSamplers = new Sampler[1];
        newSamplers[0] = sampler;
        additionalSamplers = newSamplers;
        samplerWrapper = sampler;
      }
    }
  }

  /**
   * Removes the given {@link Sampler}.
   *
   * @param sampler The sampler to remove.
   */
  public static void removeSampler(Sampler sampler) {
    synchronized (samplerLock) {
      Sampler[] samplers = additionalSamplers;
      if (samplers != null) {
        int samplerCount = samplers.length;
        //noinspection ForLoopReplaceableByForEach - Because that would create extra objects
        for (int i = 0, samplersLength = samplers.length; i < samplersLength; i++) {
          if (samplers[i].equals(sampler)) {
            samplerCount--;
          }
        }
        if (samplerCount == 0) {
          additionalSamplers = null;
          samplerWrapper = EmptySampler.SINGLETON;
        } else {
          Sampler[] newSamplers = new Sampler[samplerCount];
          int i = 0;
          //noinspection ForLoopReplaceableByForEach - Because that would create extra objects
          for (int j = 0, samplersLength = samplers.length; j < samplersLength; j++) {
            if (!samplers[j].equals(sampler)) {
              newSamplers[i++] = samplers[j];
            }
          }
          additionalSamplers = newSamplers;
          if (newSamplers.length == 1) samplerWrapper = sampler;
          else samplerWrapper = MultiSampler.SINGLETON;
        }
      }
    }
  }

  /**
   * Returns the size of the given object.
   *
   * @param obj the object.
   * @param instr the instrumentation object to use for finding the object size
   * @return the size of the given object.
   */
  public static long getObjectSize(Object obj, Instrumentation instr) {
    return instr.getObjectSize(obj);
  }

  public static void recordAllocation(Class<?> cls, Object newObj) {
    recordAllocation(-1, cls.getName(), newObj);
  }

  /**
   * Records the allocation. This method is invoked on every allocation performed by the system.
   *
   * @param count the count of how many instances are being allocated, if an array is being
   *     allocated. If an array is not being allocated, then this value will be -1.
   * @param desc the descriptor of the class/primitive type being allocated.
   * @param newObj the new <code>Object</code> whose allocation is being recorded.
   */
  public static void recordAllocation(int count, String desc, Object newObj) {
    if (Objects.equals(recordingAllocation.get(), Boolean.TRUE)) {
      return;
    }

    recordingAllocation.set(Boolean.TRUE);

    // Copy value into local variable to prevent NPE that occurs when
    // instrumentation field is set to null by this class's shutdown hook
    // after another thread passed the null check but has yet to call
    // instrumentation.getObjectSize()
    // See https://github.com/google/allocation-instrumenter/issues/15
    Instrumentation instr = instrumentation;
    if (instr != null) {
      samplerWrapper.sampleAllocation(count, desc, newObj, instr);
    }

    recordingAllocation.set(Boolean.FALSE);
  }
}
