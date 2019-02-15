package com.wavefront.sdk.grpc.reporter;

import com.wavefront.internal.reporter.WavefrontInternalReporter;
import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.Gauge;
import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.entities.metrics.WavefrontMetricSender;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A gRPC reporter for test purpose that enables to validate the reported metric/histogram value.
 *
 * @author Srujan Narkedamalli (snarkedamall@wavefront.com).
 */
public class GrpcTestReporter extends WavefrontGrpcReporter {
  private ConcurrentMap<MetricName, AtomicInteger> counters = new ConcurrentHashMap<>();
  private ConcurrentMap<MetricName, Gauge<Double>> gauges = new ConcurrentHashMap<>();
  private ConcurrentMap<MetricName, List<Long>> histograms = new ConcurrentHashMap<>();
  private AtomicBoolean serverHeartbeat = new AtomicBoolean(false);
  private AtomicBoolean clientHeartbeat = new AtomicBoolean(false);

  public GrpcTestReporter() {
    this(null, 60, null, null, null);
  }

  GrpcTestReporter(WavefrontInternalReporter wfReporter, int reportingIntervalSeconds,
                   WavefrontMetricSender wavefrontMetricSender, ApplicationTags applicationTags,
                   String source) {
    super(wfReporter, reportingIntervalSeconds, wavefrontMetricSender, applicationTags, source);
  }

  @Override
  public void incrementCounter(MetricName metricName) {
    getCounter(metricName).incrementAndGet();
  }

  @Override
  public void incrementCounter(MetricName metricName, long incrementValue) {
    getCounter(metricName).addAndGet((int) incrementValue);
  }

  @Override
  public void incrementDeltaCounter(MetricName metricName) {
    getCounter(metricName).incrementAndGet();
  }

  @Override
  public void registerGauge(MetricName metricName, Supplier<Double> value) {
    gauges.put(metricName, () -> value.get());
  }

  @Override
  public void updateHistogram(MetricName metricName, long latencyMillis) {
    getHistogram(metricName).add(latencyMillis);
  }

  @Override
  public void registerGauge(MetricName metricName, AtomicInteger atomicInteger) {
    gauges.put(metricName, () -> (double) atomicInteger.get());
  }

  @Override
  public synchronized void registerServerHeartBeat() {
    serverHeartbeat.set(true);
  }

  @Override
  public synchronized void registerClientHeartbeat() {
    clientHeartbeat.set(true);
  }

  public AtomicInteger getCounter(MetricName metricName) {
    return counters.computeIfAbsent(metricName, metricName1 -> new AtomicInteger(0));
  }

  @Nullable
  public Gauge<Double> getGauge(MetricName metricName) {
    return gauges.computeIfAbsent(metricName, metricName1 -> null);
  }

  public List<Long> getHistogram(MetricName metricName) {
    return histograms.computeIfAbsent(
        metricName, metricName1 -> Collections.synchronizedList(new ArrayList<>()));
  }

  public boolean isServerHeartbeatRegistered() {
    return serverHeartbeat.get();
  }

  public boolean isClientHeartbeatRegistered() {
    return clientHeartbeat.get();
  }
}
