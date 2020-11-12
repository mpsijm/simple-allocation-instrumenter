package com.google.monitoring.runtime.instrumentation;

import java.lang.instrument.Instrumentation;

public class AllocationSizeMeasurer {

  private long size;
  private boolean measuring;
  private final SizeSummingSampler sampler = new SizeSummingSampler();

  public void startMeasure() {
    if (measuring)
      throw new IllegalStateException("This SizeSummingSampler is already measuring");
    measuring = true;
    size = 0;
    AllocationRecorder.addSampler(sampler);
  }

  public long endMeasure() {
    AllocationRecorder.removeSampler(sampler);
    measuring = false;
    return size;
  }

  private class SizeSummingSampler implements Sampler {
    @Override
    public void sampleAllocation(int count, String desc, Object newObj, Instrumentation instr) {
      size += instr.getObjectSize(newObj);
    }
  }
}
