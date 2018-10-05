package com.wavefront.sdk.grpc.reporter;

import com.google.common.annotations.VisibleForTesting;

import com.wavefront.internal.reporter.SdkReporter;
import com.wavefront.internal.reporter.WavefrontInternalReporter;
import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.common.application.HeartbeaterService;
import com.wavefront.sdk.entities.metrics.WavefrontMetricSender;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import static com.wavefront.sdk.common.Constants.APPLICATION_TAG_KEY;
import static com.wavefront.sdk.grpc.Constants.GRPC_CLIENT_COMPONENT;
import static com.wavefront.sdk.grpc.Constants.GRPC_SERVER_COMPONENT;

/**
 * Wavefront reporter for your gRPC based application responsible for reporting metrics and
 * histograms out of the box for you. Typically this is instantiated once per service and passed
 * to grpc client/server tracers/interceptors.
 *
 * @author Srujan Narkedamalli (snarkedamall@wavefront.com).
 */
public class WavefrontGrpcReporter implements SdkReporter {
  private static final Logger logger =
      Logger.getLogger(WavefrontGrpcReporter.class.getCanonicalName());
  private final int reportingIntervalSeconds;
  private final WavefrontInternalReporter wfReporter;
  private final WavefrontMetricSender wavefrontMetricSender;
  private final ApplicationTags applicationTags;
  private final String source;
  private HeartbeaterService clientHeartbeat;
  private HeartbeaterService serverHeartbeat;

  @VisibleForTesting
  WavefrontGrpcReporter(WavefrontInternalReporter wfReporter, int reportingIntervalSeconds,
                        WavefrontMetricSender wavefrontMetricSender,
                        ApplicationTags applicationTags, String source) {
    this.wfReporter = wfReporter;
    this.reportingIntervalSeconds = reportingIntervalSeconds;
    this.wavefrontMetricSender = wavefrontMetricSender;
    this.applicationTags = applicationTags;
    this.source = source;
  }

  @Override
  public void incrementCounter(MetricName metricName) {
    wfReporter.newCounter(metricName).inc();
  }

  public void incrementCounter(MetricName metricName, long incrementValue) {
    wfReporter.newCounter(metricName).inc(incrementValue);
  }

  @Override
  public void incrementDeltaCounter(MetricName metricName) {
    wfReporter.newDeltaCounter(metricName).inc();
  }

  public void registerGauge(MetricName metricName, Supplier<Double> value) {
    wfReporter.newGauge(metricName, value);
  }

  @Override
  public void updateHistogram(MetricName metricName, long latencyMillis) {
    wfReporter.newWavefrontHistogram(metricName).update(latencyMillis);
  }

  @Override
  public void registerGauge(MetricName metricName, AtomicInteger atomicInteger) {
    wfReporter.newGauge(metricName, () -> (double) atomicInteger.get());
  }

  @Override
  public void start() {
    wfReporter.start(reportingIntervalSeconds, TimeUnit.SECONDS);
  }

  @Override
  public void stop() {
    if (serverHeartbeat != null) {
      serverHeartbeat.close();
    }
    if (clientHeartbeat != null) {
      clientHeartbeat.close();
    }
  }

  public synchronized void registerServerHeartBeat() {
    if (serverHeartbeat != null) {
      serverHeartbeat = new HeartbeaterService(wavefrontMetricSender, applicationTags,
          GRPC_SERVER_COMPONENT);
    }
  }

  public synchronized void registerClientHeartbeat() {
    if (clientHeartbeat != null) {
      clientHeartbeat = new HeartbeaterService(wavefrontMetricSender, applicationTags,
          GRPC_CLIENT_COMPONENT);
    }
  }

  public static class Builder {
    // Required parameters
    private final ApplicationTags applicationTags;
    private final String prefix = "grpc";

    // Optional parameters
    private int reportingIntervalSeconds = 60;

    @Nullable
    private String source;

    /**
     * Builder to build WavefrontGrpcReporter.
     *
     * @param applicationTags  metadata about your application that you want to be propagated as
     *                         tags when metrics/histograms are sent to Wavefront.
     */
    public Builder(ApplicationTags applicationTags) {
      this.applicationTags = applicationTags;
    }

    /**
     * Set reporting interval i.e. how often you want to report the metrics/histograms to
     * Wavefront.
     *
     * @param reportingIntervalSeconds reporting interval in seconds.
     * @return {@code this}.
     */
    public Builder reportingIntervalSeconds(int reportingIntervalSeconds) {
      this.reportingIntervalSeconds = reportingIntervalSeconds;
      return this;
    }

    /**
     * Set the source tag for your metric and histograms.
     *
     * @param source Name of the source/host where your application is running.
     * @return {@code this}.
     */
    public Builder withSource(String source) {
      this.source = source;
      return this;
    }

    /**
     * Build a WavefrontGrpcReporter.
     *
     * @param wavefrontSender send data to Wavefront via proxy or direct ingestion.
     * @return An instance of {@link WavefrontGrpcReporter}.
     */
    public WavefrontGrpcReporter build(WavefrontSender wavefrontSender) {
      if (source == null) {
        try {
          source = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
          // Should never happen
          source = "unknown";
        }
      }
      // application tag is reported with every metric
      Map<String, String> pointTags = new HashMap<>();
      pointTags.put(APPLICATION_TAG_KEY, applicationTags.getApplication());
      if (applicationTags.getCustomTags() != null) {
        pointTags.putAll(applicationTags.getCustomTags());
      }
      WavefrontInternalReporter wfReporter = new WavefrontInternalReporter.Builder().
          prefixedWith(prefix).withSource(source).withReporterPointTags(pointTags).
          reportMinuteDistribution().build(wavefrontSender);
      return new WavefrontGrpcReporter(wfReporter, reportingIntervalSeconds, wavefrontSender,
          applicationTags, source);
    }
  }
}
