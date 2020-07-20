package com.wavefront.sdk.grpc.reporter;

import com.wavefront.opentracing.WavefrontSpan;
import com.wavefront.opentracing.reporting.Reporter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class TestSpanReporter implements Reporter {
  private final ConcurrentMap<String, WavefrontSpan> spanCache = new ConcurrentHashMap<>();


  @Override
  public void report(WavefrontSpan wavefrontSpan) throws IOException {
    spanCache.putIfAbsent(wavefrontSpan.getOperationName(), wavefrontSpan);
  }

  public WavefrontSpan getReportedSpan(String operationName) {
    return spanCache.get(operationName);
  }

  @Override
  public int getFailureCount() {
    return 0;
  }

  @Override
  public void close() throws IOException {
  }

  @Override
  public void flush() {
    spanCache.clear();
  }
}
