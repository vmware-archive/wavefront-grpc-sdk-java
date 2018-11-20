package com.wavefront.sdk.grpc;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.grpc.reporter.WavefrontGrpcReporter;

import java.util.Iterator;
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
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;

import static com.wavefront.sdk.common.Constants.CLUSTER_TAG_KEY;
import static com.wavefront.sdk.common.Constants.NULL_TAG_VAL;
import static com.wavefront.sdk.common.Constants.SERVICE_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SHARD_TAG_KEY;
import static com.wavefront.sdk.grpc.Constants.GRPC_CLIENT_COMPONENT;
import static com.wavefront.sdk.grpc.Constants.GRPC_CONTEXT_SPAN_KEY;
import static com.wavefront.sdk.grpc.Constants.GRPC_METHOD_TAG_KEY;
import static com.wavefront.sdk.grpc.Constants.GRPC_METHOD_TYPE_KEY;
import static com.wavefront.sdk.grpc.Constants.GRPC_SERVICE_TAG_KEY;
import static com.wavefront.sdk.grpc.Constants.GRPC_STATUS_KEY;
import static com.wavefront.sdk.grpc.Constants.REQUEST_BYTES_TAG_KEY;
import static com.wavefront.sdk.grpc.Constants.REQUEST_MESSAGES_COUNT_TAG_KEY;
import static com.wavefront.sdk.grpc.Constants.RESPONSE_BYTES_TAG_KEY;
import static com.wavefront.sdk.grpc.Constants.RESPONSE_MESSAGES_COUNT_TAG_KEY;
import static com.wavefront.sdk.grpc.Utils.reportLatency;
import static com.wavefront.sdk.grpc.Utils.reportRequestMessageCount;
import static com.wavefront.sdk.grpc.Utils.reportResponseAndErrorStats;
import static com.wavefront.sdk.grpc.Utils.reportResponseMessageCount;
import static com.wavefront.sdk.grpc.Utils.reportRpcRequestBytes;
import static com.wavefront.sdk.grpc.Utils.reportRpcResponseBytes;

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
  private static final String CLIENT_PREFIX = "client.";
  private static final String CLIENT_TOTAL_INFLIGHT = "client.total_requests.inflight";
  private final Map<MetricName, AtomicInteger> gauges = new ConcurrentHashMap<>();
  private final WavefrontGrpcReporter wfGrpcReporter;
  @Nullable
  private final Tracer tracer;
  private final ApplicationTags applicationTags;
  private final boolean recordStreamingStats;

  public static class Builder {
    private WavefrontGrpcReporter wfGrpcReporter;
    @Nullable
    private Tracer tracer;
    private ApplicationTags applicationTags;
    boolean recordStreamingStats = false;

    public Builder(WavefrontGrpcReporter wfGrpcReporter, ApplicationTags applicationTags) {
      this.wfGrpcReporter = Preconditions.checkNotNull(wfGrpcReporter, "invalid reporter");
      this.applicationTags = Preconditions.checkNotNull(applicationTags, "invalid app tags");
    }

    public Builder recordStreamingStats() {
      recordStreamingStats = true;
      return this;
    }

    public Builder withTracer(Tracer tracer) {
      this.tracer = tracer;
      return this;
    }

    public WavefrontClientInterceptor build() {
      return new WavefrontClientInterceptor(wfGrpcReporter, tracer, applicationTags,
          recordStreamingStats);
    }
  }

  private WavefrontClientInterceptor(WavefrontGrpcReporter wfGrpcReporter, Tracer tracer,
                                     ApplicationTags applicationTags,
                                     boolean recordStreamingStats) {
    this.wfGrpcReporter = wfGrpcReporter;
    this.tracer = tracer;
    this.applicationTags = applicationTags;
    this.recordStreamingStats = recordStreamingStats;
    wfGrpcReporter.registerClientHeartbeat();
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    String methodName = Utils.getFriendlyMethodName(method.getFullMethodName());
    Span span = createClientSpan(methodName, method.getType().toString());
    final ClientCallTracer tracerFactory = new ClientCallTracer(
        Utils.getServiceName(method.getFullMethodName()), methodName,
        shouldRecordStreamingStats(method.getType()), span);
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

  @Nullable
  private Span createClientSpan(String spanName, String methodType) {
    if (tracer == null) {
      return null;
    }
    // attempt to get active spanContext, stored in grpc context
    Span toReturn;
    Span activeSpan = GRPC_CONTEXT_SPAN_KEY.get();
    if (activeSpan != null) {
      toReturn = tracer.buildSpan(spanName).asChildOf(activeSpan.context()).start();
    } else {
      toReturn = tracer.buildSpan(spanName).start();
    }
    Tags.SPAN_KIND.set(toReturn, Tags.SPAN_KIND_CLIENT);
    Tags.COMPONENT.set(toReturn, GRPC_CLIENT_COMPONENT);
    toReturn.setTag(GRPC_METHOD_TYPE_KEY, methodType);
    return toReturn;
  }

  private class ClientCallTracer extends ClientStreamTracer.Factory {
    final String grpcService;
    final String methodName;
    final boolean streamingStats;
    final Span span;
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
    final Map<String, String> histogramAllTags;

    ClientCallTracer(String grpcService, String methodName,
                     boolean streamingStats, @Nullable Span span) {
      this.grpcService = grpcService;
      this.methodName = methodName;
      this.streamingStats = streamingStats;
      this.span = span;
      this.startTime = System.currentTimeMillis();
      this.requestMessageCount = streamingStats ? new AtomicLong(0) : null;
      this.responseMessageCount = streamingStats ? new AtomicLong(0) : null;
      ImmutableMap.Builder<String, String> tagsBuilder = ImmutableMap.<String, String>builder().
          put(CLUSTER_TAG_KEY, applicationTags.getCluster() == null ? NULL_TAG_VAL :
              applicationTags.getCluster()).
          put(SERVICE_TAG_KEY, applicationTags.getService()).
          put(SHARD_TAG_KEY, applicationTags.getShard() == null ? NULL_TAG_VAL :
              applicationTags.getShard());
      // granular per RPC method metrics related tags.
      this.overallAggregatedPerSourceTags = tagsBuilder.build();
      this.allTags = tagsBuilder.put(GRPC_SERVICE_TAG_KEY, grpcService).build();
      this.histogramAllTags = tagsBuilder.put(GRPC_METHOD_TAG_KEY, methodName).build();
      // update requests inflight
      getGaugeValue(new MetricName(REQUEST_PREFIX + methodName + ".inflight", allTags)).
          incrementAndGet();
      getGaugeValue(new MetricName(CLIENT_TOTAL_INFLIGHT,
          overallAggregatedPerSourceTags)).incrementAndGet();
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(CallOptions callOptions, Metadata headers) {
      if (span != null) {
        tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, new TextMap() {
          @Override
          public void put(String key, String value) {
            Metadata.Key<String> headerKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
            headers.put(headerKey, value);
          }

          @Override
          public Iterator<Map.Entry<String, String>> iterator() {
            throw new UnsupportedOperationException(
                "TextMap should only be used with Tracer.inject()");
          }
        });
      }
      return new ClientTracer(this);
    }

    public void callEnded(Status status) {
      if (streamClosed.getAndSet(true)) {
        return;
      }
      long rpcLatency = System.currentTimeMillis() - startTime;
      finishClientSpan(status);
      // update requests inflight
      getGaugeValue(new MetricName(REQUEST_PREFIX + methodName + ".inflight", allTags)).
          decrementAndGet();
      getGaugeValue(new MetricName(CLIENT_TOTAL_INFLIGHT, overallAggregatedPerSourceTags)).
          decrementAndGet();
      // rpc latency
      reportLatency(CLIENT_PREFIX, methodName, status, rpcLatency, histogramAllTags, allTags,
          wfGrpcReporter);
      // request, response size
      reportRpcRequestBytes(CLIENT_PREFIX, methodName, requestBytes.get(), histogramAllTags,
          allTags, wfGrpcReporter);
      reportRpcResponseBytes(CLIENT_PREFIX, methodName, responseBytes.get(), histogramAllTags,
          allTags, wfGrpcReporter);
      // streaming stats
      if (streamingStats) {
        reportRequestMessageCount(CLIENT_PREFIX, methodName, requestMessageCount.get(), allTags,
            histogramAllTags, wfGrpcReporter);
        reportResponseMessageCount(CLIENT_PREFIX, methodName, responseMessageCount.get(), allTags,
            histogramAllTags, wfGrpcReporter);
      }
      reportResponseAndErrorStats(CLIENT_PREFIX, methodName, grpcService, status, applicationTags,
          allTags, overallAggregatedPerSourceTags, wfGrpcReporter);
    }

    private void finishClientSpan(Status status) {
      if (span != null) {
        span.setTag(GRPC_STATUS_KEY, status.getCode().toString());
        if (status.getCode() != Status.Code.OK) {
          Tags.ERROR.set(span, true);
        }
        span.setTag(REQUEST_BYTES_TAG_KEY, requestBytes.get());
        span.setTag(RESPONSE_BYTES_TAG_KEY, responseBytes.get());
        if (streamingStats) {
          span.setTag(REQUEST_MESSAGES_COUNT_TAG_KEY, requestMessageCount.get());
          span.setTag(RESPONSE_MESSAGES_COUNT_TAG_KEY, responseMessageCount.get());
        }
        span.finish();
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
        if (optionalWireSize >= 0) {
          wfGrpcReporter.updateHistogram(
              new MetricName(REQUEST_PREFIX + callTracer.methodName + ".streaming.message_bytes",
                  callTracer.histogramAllTags), optionalWireSize);
        }
      }
    }

    @Override
    public void inboundMessageRead(int seqNo, long optionalWireSize,
                                   long optionalUncompressedSize) {
      if (callTracer.streamingStats) {
        callTracer.responseMessageCount.incrementAndGet();
        if (optionalWireSize >= 0) {
          wfGrpcReporter.updateHistogram(
              new MetricName(RESPONSE_PREFIX + callTracer.methodName + ".streaming.message_bytes",
                  callTracer.histogramAllTags), optionalWireSize);
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
