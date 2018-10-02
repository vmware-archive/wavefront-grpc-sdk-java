package com.wavefront.sdk.grpc;

import com.google.common.collect.ImmutableMap;

import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.grpc.reporter.WavefrontGrpcReporter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

import static com.wavefront.sdk.common.Constants.CLUSTER_TAG_KEY;
import static com.wavefront.sdk.common.Constants.NULL_TAG_VAL;
import static com.wavefront.sdk.common.Constants.SERVICE_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SHARD_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SOURCE_KEY;
import static com.wavefront.sdk.common.Constants.WAVEFRONT_PROVIDED_SOURCE;
import static com.wavefront.sdk.grpc.Constants.GRPC_SERVICE_TAG_KEY;

/**
 * A client interceptor to generate gRPC client related stats and send them to wavefront. Create
 * only one instance of {@link WavefrontClientInterceptor} per one service and use it to
 * intercept all channels.
 *
 * @author Srujan Narkedamalli (snarkedamall@wavefront.com).
 */
public class WavefrontClientInterceptor implements ClientInterceptor {
  private static final String REQUEST_PREFIX = "client.request.";
  private static final String RESPONSE_PREFIX = "client.response.";
  private final Map<MetricName, AtomicInteger> gauges = new ConcurrentHashMap<>();
  private final WavefrontGrpcReporter wfGrpcReporter;
  private final ApplicationTags applicationTags;
  private final boolean recordStreamingStats;

  public WavefrontClientInterceptor(WavefrontGrpcReporter wfGrpcReporter,
                                    ApplicationTags applicationTags,
                                    boolean recordStreamingStats) {
    this.wfGrpcReporter = wfGrpcReporter;
    this.applicationTags = applicationTags;
    this.recordStreamingStats = recordStreamingStats;
    wfGrpcReporter.registerClientHeartbeat();
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    final ClientCallTracer tracerFactory = new ClientCallTracer(
        Utils.getServiceName(method.getFullMethodName()),
        Utils.getFriendlyMethodName(method.getFullMethodName()),
        shouldRecordStreamingStats(method.getType()));
    ClientCall<ReqT, RespT> call =
        next.newCall(method, callOptions.withStreamTracerFactory(tracerFactory));
    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {
      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        delegate().start(
            new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
              @Override
              public void onClose(Status status, Metadata trailers) {
                tracerFactory.callEnded(status);
                super.onClose(status, trailers);
              }
            },
            headers);
      }
    };
  }

  private class ClientCallTracer extends ClientStreamTracer.Factory {
    final boolean streamingStats;
    final String grpcService;
    final String methodName;
    final AtomicBoolean streamClosed = new AtomicBoolean(false);
    @Nullable
    final AtomicLong requestMessageCount;
    @Nullable
    final AtomicLong responseMessageCount;
    final AtomicLong requestBytes = new AtomicLong(0);
    final AtomicLong responseBytes = new AtomicLong(0);
    final long startTime;
    // granular per API tags, with grpc service tag
    final Map<String, String> allTags;
    final Map<String, String> overallAggregatedPerSourceTags;

    ClientCallTracer(String grpcService, String methodName, boolean streamingStats) {
      this.grpcService = grpcService;
      this.methodName = methodName;
      this.streamingStats = streamingStats;
      this.startTime = System.currentTimeMillis();
      this.requestMessageCount = streamingStats ? new AtomicLong(0) : null;
      this.responseMessageCount = streamingStats ? new AtomicLong(0) : null;
      // granular per RPC method metrics related tags.
      this.allTags = ImmutableMap.<String, String>builder().
          put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
              applicationTags.getCluster()).
          put(SERVICE_TAG_KEY, applicationTags.getService()).
          put(SHARD_TAG_KEY, applicationTags.getShard() == null ? NULL_TAG_VAL :
              applicationTags.getShard()).
          put(GRPC_SERVICE_TAG_KEY, grpcService).
          build();
      this.overallAggregatedPerSourceTags = ImmutableMap.<String, String>builder().
          put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
              applicationTags.getCluster()).
          put(SERVICE_TAG_KEY, applicationTags.getService()).
          put(SHARD_TAG_KEY, applicationTags.getShard() == null ? NULL_TAG_VAL :
              applicationTags.getShard()).
          build();
      // update requests inflight
      getGaugeValue(new MetricName(REQUEST_PREFIX + methodName + ".inflight", allTags)).
          incrementAndGet();
      getGaugeValue(new MetricName("client.total_requests.inflight",
          overallAggregatedPerSourceTags)).incrementAndGet();
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(CallOptions callOptions, Metadata headers) {
      return new ClientTracer(this);
    }

    public void callEnded(Status status) {
      if (streamClosed.getAndSet(true)) {
        return;
      }
      long rpcLatency = System.currentTimeMillis() - startTime;
      String methodWithStatus = methodName + "." + status.getCode().toString();
      // update requests inflight
      getGaugeValue(new MetricName(REQUEST_PREFIX + methodName + ".inflight", allTags)).
          decrementAndGet();
      getGaugeValue(new MetricName("client.total_requests.inflight",
          overallAggregatedPerSourceTags)).decrementAndGet();
      // rpc latency
      wfGrpcReporter.updateHistogram(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".latency", allTags), rpcLatency);
      // request, response size
      wfGrpcReporter.updateHistogram(
          new MetricName(REQUEST_PREFIX +  methodName + ".bytes", allTags), requestBytes.get());
      wfGrpcReporter.updateHistogram(
          new MetricName(RESPONSE_PREFIX +  methodName + ".bytes", allTags), responseBytes.get());
      // streaming stats
      if (streamingStats) {
        wfGrpcReporter.updateHistogram(
            new MetricName(REQUEST_PREFIX + methodName + ".streaming.messages_per_rpc", allTags),
            requestMessageCount.get());
        wfGrpcReporter.updateHistogram(
            new MetricName(RESPONSE_PREFIX + methodName + ".streaming.messages_per_rpc", allTags),
            responseMessageCount.get());
      }
      Map<String, String> aggregatedPerShardTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(SERVICE_TAG_KEY, applicationTags.getService());
        put(SHARD_TAG_KEY, applicationTags.getShard() == null ? NULL_TAG_VAL :
            applicationTags.getShard());
        put(GRPC_SERVICE_TAG_KEY, grpcService);
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> aggregatedPerServiceTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(SERVICE_TAG_KEY, applicationTags.getService());
        put(GRPC_SERVICE_TAG_KEY, grpcService);
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> aggregatedPerClutserTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(GRPC_SERVICE_TAG_KEY, grpcService);
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> aggergatedPerApplicationTags = new HashMap<String, String>() {{
        put(GRPC_SERVICE_TAG_KEY, grpcService);
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      // overall RPC related metrics
      Map<String, String> overallAggregatedPerShardTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(SERVICE_TAG_KEY, applicationTags.getService());
        put(SHARD_TAG_KEY, applicationTags.getShard() == null ? NULL_TAG_VAL :
            applicationTags.getShard());
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> overallAggregatedPerServiceTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put(SERVICE_TAG_KEY, applicationTags.getService());
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> overallAggregatedPerClusterTags = new HashMap<String, String>() {{
        put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
              applicationTags.getCluster());
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      Map<String, String> overallAggregatedPerApplicationTags = new HashMap<String, String>() {{
        put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
      }};
      // granular RPC stats
      wfGrpcReporter.incrementCounter(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".cumulative", allTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".aggregated_per_shard",
              aggregatedPerShardTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".aggregated_per_service",
              aggregatedPerServiceTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".aggregated_per_cluster",
              aggregatedPerClutserTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + methodWithStatus + ".aggregated_per_application",
              aggergatedPerApplicationTags));
      // overall RPC stats
      wfGrpcReporter.incrementCounter(
          new MetricName(RESPONSE_PREFIX + "completed.aggregated_per_source",
              overallAggregatedPerSourceTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + "completed.aggregated_per_shard",
              overallAggregatedPerShardTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + "completed.aggregated_per_service",
              overallAggregatedPerServiceTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + "completed.aggregated_per_cluster",
              overallAggregatedPerClusterTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(RESPONSE_PREFIX + "completed.aggregated_per_application",
              overallAggregatedPerApplicationTags));
      // overall error stats
      if (status.getCode() != Status.Code.OK) {
        wfGrpcReporter.incrementCounter(
            new MetricName(RESPONSE_PREFIX + "errors.aggregated_per_source",
                overallAggregatedPerSourceTags));
        wfGrpcReporter.incrementDeltaCounter(
            new MetricName(RESPONSE_PREFIX + "errors.aggregated_per_shard",
                overallAggregatedPerShardTags));
        wfGrpcReporter.incrementDeltaCounter(
            new MetricName(RESPONSE_PREFIX + "errors.aggregated_per_service",
                overallAggregatedPerServiceTags));
        wfGrpcReporter.incrementDeltaCounter(
            new MetricName(RESPONSE_PREFIX + "errors.aggregated_per_cluster",
                overallAggregatedPerClusterTags));
        wfGrpcReporter.incrementDeltaCounter(
            new MetricName(RESPONSE_PREFIX + "errors.aggregated_per_application",
                overallAggregatedPerApplicationTags));
      }
    }
  }

  private class ClientTracer extends ClientStreamTracer {
    private final ClientCallTracer callTracer;

    ClientTracer(ClientCallTracer callTracer) {
      this.callTracer = callTracer;
    }

    @Override
    public void outboundWireSize(long bytes) {
      callTracer.requestBytes.addAndGet(bytes);
    }

    @Override
    public void inboundWireSize(long bytes) {
      callTracer.responseBytes.addAndGet(bytes);
    }

    @Override
    public void outboundMessageSent(int seqNo, long optionalWireSize,
                                    long optionalUncompressedSize) {
      if (callTracer.streamingStats) {
        callTracer.requestMessageCount.incrementAndGet();
        wfGrpcReporter.incrementCounter(
            new MetricName(REQUEST_PREFIX + callTracer.methodName + ".streaming.messages",
                callTracer.allTags));
        if (optionalWireSize >= 0) {
          wfGrpcReporter.updateHistogram(
              new MetricName(REQUEST_PREFIX + callTracer.methodName + ".streaming.message_bytes",
                  callTracer.allTags), optionalWireSize);
        }
      }
    }

    @Override
    public void inboundMessageRead(int seqNo, long optionalWireSize,
                                   long optionalUncompressedSize) {
      if (callTracer.streamingStats) {
        callTracer.responseMessageCount.incrementAndGet();
        wfGrpcReporter.incrementCounter(
            new MetricName(RESPONSE_PREFIX + callTracer.methodName + ".streaming.messages",
                callTracer.allTags));
        if (optionalWireSize >= 0) {
          wfGrpcReporter.updateHistogram(
              new MetricName(RESPONSE_PREFIX + callTracer.methodName + ".streaming.message_bytes",
                  callTracer.allTags), optionalWireSize);
        }
      }
    }
  }

  private boolean shouldRecordStreamingStats(MethodDescriptor.MethodType methodType) {
    if (recordStreamingStats) {
      return Utils.isStreamingMethod(methodType);
    } else {
      return false;
    }
  }

  private AtomicInteger getGaugeValue(MetricName metricName) {
    return gauges.computeIfAbsent(metricName, key -> {
      final AtomicInteger toReturn = new AtomicInteger();
      wfGrpcReporter.registerGauge(key, () -> (double) toReturn.get());
      return toReturn;
    });
  }
}
