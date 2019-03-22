package com.wavefront.sdk.grpc;

import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.opentracing.WavefrontSpan;
import com.wavefront.opentracing.WavefrontTracer;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.grpc.reporter.GrpcTestReporter;
import com.wavefront.sdk.grpc.reporter.TestSpanReporter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import static com.wavefront.sdk.common.Constants.CLUSTER_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SERVICE_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SHARD_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SOURCE_KEY;
import static com.wavefront.sdk.common.Constants.WAVEFRONT_PROVIDED_SOURCE;
import static com.wavefront.sdk.grpc.Constants.GRPC_METHOD_TAG_KEY;
import static com.wavefront.sdk.grpc.Constants.GRPC_SERVICE_TAG_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for all gRPC server and client related wavefront metrics, histograms and traces.
 *
 * @author Srujan Narkedamalli (snarkedamall@wavefront.com).
 */
public class GrpcServerAndClientInstrumentationTest {
  private final String application = "testApplication";
  private final String cluster = "testCluster";
  private final String service = "testService";
  private final String shard = "testShard";
  private final String clientService = "testClientService";
  private final String grpcService = "wf.test.Sample";
  // set up tags
  private final ApplicationTags serverApplicationTags =
      new ApplicationTags.Builder(application, service).cluster(cluster).shard(shard).build();
  private final ApplicationTags clientApplicationTags =
      new ApplicationTags.Builder(application, clientService).cluster(cluster).shard(shard).build();
  private WavefrontServerTracerFactory serverTracerFactory;
  private WavefrontClientInterceptor clientInterceptor;
  private GrpcTestReporter grpcTestReporter;
  private TestSpanReporter serverSpanReporter;
  private TestSpanReporter clientSpanReporter;
  private Server server;
  private ManagedChannel channel;
  private SampleGrpc.SampleBlockingStub blockingStub;
  private SampleGrpc.SampleStub asyncStub;

  @Before
  public void setUp() throws Exception {
    setUpClientAndServerInterceptors(null);
    setUpChannelAndServer(new SampleService());
  }

  private void setUpClientAndServerInterceptors(Function<String, String> spanNameOverride) {
    // set up fake test reporter
    grpcTestReporter = new GrpcTestReporter();
    serverSpanReporter = new TestSpanReporter();
    clientSpanReporter = new TestSpanReporter();
    // set up sdk components with streaming stats
    serverTracerFactory = new WavefrontServerTracerFactory.Builder(
        grpcTestReporter, serverApplicationTags).
        withTracer(new WavefrontTracer.
            Builder(serverSpanReporter, serverApplicationTags).build()).
        recordStreamingStats().
        spanNameOverride(spanNameOverride).
        build();
    clientInterceptor = new WavefrontClientInterceptor.Builder(
        grpcTestReporter, clientApplicationTags).
        withTracer(new WavefrontTracer.
            Builder(clientSpanReporter, serverApplicationTags).build()).
        recordStreamingStats().
        spanNameOverride(spanNameOverride).
        build();
  }

  private void setUpChannelAndServer(SampleGrpc.SampleImplBase service) throws Exception {
    if (server != null && !server.isShutdown()) {
      server.shutdown();
    }
    if (channel != null && !channel.isShutdown()) {
      channel.shutdownNow();
    }
    // set up grpc server
    server = ServerBuilder.forPort(0).addStreamTracerFactory(serverTracerFactory).
        addService(service).build();
    server.start();
    // set up channel
    channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).
        intercept(clientInterceptor).usePlaintext().build();
    blockingStub = SampleGrpc.newBlockingStub(channel);
    asyncStub = SampleGrpc.newStub(channel);
  }

  @After
  public void cleanUp() {
    if (server != null && !server.isShutdown()) {
      server.shutdown();
    }
    if (channel != null && !channel.isShutdown()) {
      channel.shutdownNow();
    }
  }

  @Test
  public void testSuccessResponseStatsAndSpans() {
    // send message 1 message
    blockingStub.echo(Request.newBuilder().setMessage("message").build());
    // server and client heartbeats are registered
    assertTrue(grpcTestReporter.isClientHeartbeatRegistered());
    assertTrue(grpcTestReporter.isServerHeartbeatRegistered());
    // server granular metrics
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.wf.test.Sample.echo.OK.cumulative", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.wf.test.Sample.echo.OK.aggregated_per_shard",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.wf.test.Sample.echo.OK.aggregated_per_service",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.wf.test.Sample.echo.OK.aggregated_per_cluster",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.wf.test.Sample.echo.OK.aggregated_per_application",
        new HashMap<String, String>() {{
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    // client granular metrics
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.wf.test.Sample.echo.OK.cumulative", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.wf.test.Sample.echo.OK.aggregated_per_shard",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.wf.test.Sample.echo.OK.aggregated_per_service",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.wf.test.Sample.echo.OK.aggregated_per_cluster",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.wf.test.Sample.echo.OK.aggregated_per_application",
        new HashMap<String, String>() {{
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    // check no streaming stats
    assertEquals(0, grpcTestReporter.getCounter(new MetricName(
        "server.response.wf.test.Sample.echo.streaming.messages.count",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    assertEquals(0, grpcTestReporter.getCounter(new MetricName(
        "client.response.wf.test.Sample.echo.streaming.messages.count",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    // check server span
    WavefrontSpan serverSpan = serverSpanReporter.getReportedSpan("wf.test.Sample.echo");
    assertNotNull(serverSpan);
    Map<String, Collection<String>> serverTags = serverSpan.getTagsAsMap();
    assertEquals(serverTags.get("component").iterator().next(), "grpc-server");
    assertEquals(serverTags.get("grpc.method_type").iterator().next(), "UNARY");
    assertEquals(serverTags.get("grpc.status").iterator().next(), "OK");
    assertEquals(serverTags.get("request.bytes").iterator().next(), "9");
    assertEquals(serverTags.get("response.bytes").iterator().next(), "9");
    assertEquals(serverTags.get("span.kind").iterator().next(), "server");
    // check client span
    WavefrontSpan clientSpan = clientSpanReporter.getReportedSpan("wf.test.Sample.echo");
    assertNotNull(clientSpan);
    Map<String, Collection<String>> clientTags = clientSpan.getTagsAsMap();
    assertEquals(clientTags.get("component").iterator().next(), "grpc-client");
    assertEquals(clientTags.get("grpc.method_type").iterator().next(), "UNARY");
    assertEquals(clientTags.get("grpc.status").iterator().next(), "OK");
    assertEquals(clientTags.get("request.bytes").iterator().next(), "9");
    assertEquals(clientTags.get("response.bytes").iterator().next(), "9");
    assertEquals(clientTags.get("span.kind").iterator().next(), "client");
  }

  @Test
  public void testErrorAndOverallResponseStatsAndSpans() throws Exception {
    setUpChannelAndServer(new SampleService() {
      @Override
      public void echo(Request request, StreamObserver<Response> responseObserver) {
        throw new RuntimeException();
      }
    });
    // send message 1 message
    try {
      blockingStub.echo(Request.newBuilder().setMessage("message").build());
      fail("expected exception is not thrown");
    } catch (StatusRuntimeException e) {
      assertEquals(Status.Code.UNKNOWN, e.getStatus().getCode());
    }
    // server and client granular metrics
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.wf.test.Sample.echo.UNKNOWN.cumulative", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.wf.test.Sample.echo.UNKNOWN.cumulative", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get());
    // server overall stats
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.completed.aggregated_per_source", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.completed.aggregated_per_shard", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.completed.aggregated_per_service",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.completed.aggregated_per_cluster",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.completed.aggregated_per_application",
        new HashMap<String, String>() {{
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    // server error overall stats
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.errors.aggregated_per_source", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.errors.aggregated_per_shard", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.errors.aggregated_per_service",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.errors.aggregated_per_cluster",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "server.response.errors.aggregated_per_application",
        new HashMap<String, String>() {{
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    // client overall stats
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.completed.aggregated_per_source", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.completed.aggregated_per_shard", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.completed.aggregated_per_service",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.completed.aggregated_per_cluster",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.completed.aggregated_per_application",
        new HashMap<String, String>() {{
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    // client error overall stats
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.errors.aggregated_per_source", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.errors.aggregated_per_shard", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
    }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.errors.aggregated_per_service",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.errors.aggregated_per_cluster",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    assertEquals(1, grpcTestReporter.getCounter(new MetricName(
        "client.response.errors.aggregated_per_application",
        new HashMap<String, String>() {{
          put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
        }})).get());
    // check server span
    WavefrontSpan serverSpan = serverSpanReporter.getReportedSpan("wf.test.Sample.echo");
    assertNotNull(serverSpan);
    Map<String, Collection<String>> serverTags = serverSpan.getTagsAsMap();
    assertEquals(serverTags.get("error").iterator().next(), "true");
    assertEquals(serverTags.get("grpc.status").iterator().next(), "UNKNOWN");
    // check client span
    WavefrontSpan clientSpan = clientSpanReporter.getReportedSpan("wf.test.Sample.echo");
    assertNotNull(clientSpan);
    Map<String, Collection<String>> clientTags = clientSpan.getTagsAsMap();
    assertEquals(clientTags.get("error").iterator().next(), "true");
    assertEquals(clientTags.get("grpc.status").iterator().next(), "UNKNOWN");
  }

  @Test
  public void testPayloadSizeStats() throws Exception {
    // send message 1 message
    blockingStub.echo(Request.newBuilder().setMessage("message").build());
    Thread.sleep(1000);
    // server req and resp bytes
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.response.wf.test.Sample.echo.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
      put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.echo");
    }})).get(0));
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.request.wf.test.Sample.echo.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
      put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.echo");
    }})).get(0));
    // client req and resp bytes
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.response.wf.test.Sample.echo.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
      put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.echo");
    }})).get(0));
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.request.wf.test.Sample.echo.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
      put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.echo");
    }})).get(0));
  }

  @Test
  public void testStreamingStatsAndSpans() throws Exception {
    CompletableFuture<Response> responseFuture = new CompletableFuture<>();
    StreamObserver<Request> request = asyncStub.replayMessages(new StreamObserver<Response>() {
      @Override
      public void onNext(Response response) {
      }

      @Override
      public void onError(Throwable throwable) {
        responseFuture.completeExceptionally(throwable);
      }

      @Override
      public void onCompleted() {
        responseFuture.complete(null);
      }
    });
    // send 3 messages
    for (int i = 0; i < 3; i++) {
      request.onNext(Request.newBuilder().setMessage("message").build());
    }
    request.onCompleted();
    responseFuture.get(1, TimeUnit.MINUTES);
    Thread.sleep(1000);
    // streaming server stats
    assertEquals(27, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.request.wf.test.Sample.replayMessages.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
      put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
    }})).get(0));
    assertEquals(3, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.request.wf.test.Sample.replayMessages.streaming.message_bytes",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
          put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
        }})).size());
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.response.wf.test.Sample.replayMessages.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
      put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
    }})).get(0));
    assertEquals(1, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.response.wf.test.Sample.replayMessages.streaming.message_bytes",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
          put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
        }})).size());
    assertEquals(3, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.request.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
          put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
        }})).get(0));
    assertEquals(1, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.response.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
          put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
        }})).get(0));
    assertEquals(3, (long) grpcTestReporter.getCounter(new MetricName(
        "server.request.wf.test.Sample.replayMessages.streaming.messages",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    assertEquals(1, (long) grpcTestReporter.getCounter(new MetricName(
        "server.response.wf.test.Sample.replayMessages.streaming.messages",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    // client stats
    assertEquals(27, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.request.wf.test.Sample.replayMessages.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
      put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
    }})).get(0));
    assertEquals(3, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.request.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
          put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
        }})).get(0));
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.response.wf.test.Sample.replayMessages.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
      put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
    }})).get(0));
    assertEquals(1, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.response.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
          put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
        }})).get(0));
    assertEquals(3, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.request.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
          put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
        }})).get(0));
    assertEquals(1, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.response.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
          put(GRPC_METHOD_TAG_KEY, "wf.test.Sample.replayMessages");
        }})).get(0));
    assertEquals(3, (long) grpcTestReporter.getCounter(new MetricName(
        "client.request.wf.test.Sample.replayMessages.streaming.messages",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    assertEquals(1, (long) grpcTestReporter.getCounter(new MetricName(
        "client.response.wf.test.Sample.replayMessages.streaming.messages",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get());
    // check server span
    WavefrontSpan serverSpan = serverSpanReporter.getReportedSpan("wf.test.Sample.replayMessages");
    assertNotNull(serverSpan);
    Map<String, Collection<String>> serverTags = serverSpan.getTagsAsMap();
    assertEquals(serverTags.get("request.messages.count").iterator().next(), "3");
    assertEquals(serverTags.get("response.messages.count").iterator().next(), "1");
    // check client span
    WavefrontSpan clientSpan = clientSpanReporter.getReportedSpan("wf.test.Sample.replayMessages");
    assertNotNull(clientSpan);
    Map<String, Collection<String>> clientTags = clientSpan.getTagsAsMap();
    assertEquals(clientTags.get("request.messages.count").iterator().next(), "3");
    assertEquals(clientTags.get("response.messages.count").iterator().next(), "1");
  }

  @Test
  public void testServerAndClientInflightRequestStats() throws Exception {
    CountDownLatch latch1 = new CountDownLatch(2);
    CountDownLatch latch2 = new CountDownLatch(1);
    setUpChannelAndServer(new SampleService() {
      @Override
      public void echo(Request request, StreamObserver<Response> responseObserver) {
        latch1.countDown();
        try {
          latch2.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        responseObserver.onNext(Response.newBuilder().setMessage("").build());
        responseObserver.onCompleted();
      }
    });
    SampleGrpc.SampleFutureStub stub = SampleGrpc.newFutureStub(channel);
    MetricName serverInflight = new MetricName(
        "server.request.wf.test.Sample.echo.inflight", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }});
    MetricName serverTotalInflight = new MetricName(
        "server.total_requests.inflight", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
    }});
    MetricName clientInflight = new MetricName(
        "client.request.wf.test.Sample.echo.inflight", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }});
    MetricName clientTotalInflight = new MetricName(
        "client.total_requests.inflight", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
    }});
    stub.echo(Request.newBuilder().setMessage("").build());
    stub.echo(Request.newBuilder().setMessage("").build());
    latch1.await(1, TimeUnit.MINUTES);
    assertEquals(2.0, grpcTestReporter.getGauge(clientInflight).getValue(), 0.01);
    assertEquals(2.0, grpcTestReporter.getGauge(clientTotalInflight).getValue(), 0.01);
    assertEquals(2.0, grpcTestReporter.getGauge(serverInflight).getValue(), 0.01);
    assertEquals(2.0, grpcTestReporter.getGauge(serverTotalInflight).getValue(), 0.01);
    latch2.countDown();
    Thread.sleep(1000);
    assertEquals(0.0, grpcTestReporter.getGauge(serverInflight).getValue(), 0.01);
    assertEquals(0.0, grpcTestReporter.getGauge(serverTotalInflight).getValue(), 0.01);
    assertEquals(0.0, grpcTestReporter.getGauge(clientInflight).getValue(), 0.01);
    assertEquals(0.0, grpcTestReporter.getGauge(clientTotalInflight).getValue(), 0.01);
  }

  @Test
  public void testClientAndServerSpanNameOverride() throws Exception {
    setUpClientAndServerInterceptors(s -> s.substring(s.lastIndexOf(".") + 1));
    setUpChannelAndServer(new SampleService());
    blockingStub.echo(Request.newBuilder().setMessage("message").build());
    WavefrontSpan serverSpan = serverSpanReporter.getReportedSpan("echo");
    assertNotNull(serverSpan);
    // check client span
    WavefrontSpan clientSpan = clientSpanReporter.getReportedSpan("echo");
    assertNotNull(clientSpan);
  }

  private class SampleService extends SampleGrpc.SampleImplBase {
    @Override
    public void echo(Request request, StreamObserver<Response> responseObserver) {
      responseObserver.onNext(Response.newBuilder().setMessage(request.getMessage()).build());
      responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<Request> replayMessages(StreamObserver<Response> responseObserver) {
      List<String> requestMessages = new ArrayList<>();
      return new StreamObserver<Request>() {
        @Override
        public void onNext(Request request) {
          requestMessages.add(request.getMessage());
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onCompleted() {
          //send only first message back.
          responseObserver.onNext(Response.newBuilder().setMessage(requestMessages.get(0)).build());
          responseObserver.onCompleted();
        }
      };
    }
  }
}
