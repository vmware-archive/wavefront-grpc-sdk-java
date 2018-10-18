package com.wavefront.sdk.grpc;

import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.grpc.reporter.GrpcTestReporter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import static com.wavefront.sdk.grpc.Constants.GRPC_SERVICE_TAG_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for all gRPC server and client related wavefront metrics and histograms.
 *
 * @author Srujan Narkedamalli (snarkedamall@wavefront.com).
 */
public class GrpcServerAndClientStatsTest {
  private final String application = "testApplication";
  private final String cluster = "testCluster";
  private final String service = "testService";
  private final String shard = "testShard";
  private final String clientService = "testClientService";
  private final String grpcService = "wf.test.Sample";
  private WavefrontServerTracerFactory serverTracerFactory;
  private WavefrontClientInterceptor clientInterceptor;
  private GrpcTestReporter grpcTestReporter;
  private Server server;
  private ManagedChannel channel;
  private SampleGrpc.SampleBlockingStub blockingStub;
  private SampleGrpc.SampleStub asyncStub;

  @Before
  public void setUp() throws Exception {
    // set up tags
    ApplicationTags serverApplicationTags = new ApplicationTags.Builder(application, service).
        cluster(cluster).shard(shard).build();
    ApplicationTags clientApplicationTags = new ApplicationTags.Builder(application, clientService).
        cluster(cluster).shard(shard).build();
    // set up fake test reporter
    grpcTestReporter = new GrpcTestReporter();
    // set up sdk components with streaming stats
    serverTracerFactory = new WavefrontServerTracerFactory.Builder(
        grpcTestReporter, serverApplicationTags).recordStreamingStats().build();
    clientInterceptor = new WavefrontClientInterceptor.Builder(
        grpcTestReporter, clientApplicationTags).recordStreamingStats().build();
    setUpChannelAndServer(new SampleService());
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
  public void testSuccessResponseStats() throws Exception {
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
  }

  @Test
  public void testErrorAndOverallResponseStats() throws Exception {
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
  }

  @Test
  public void testPayloadSizeStats() {
    // send message 1 message
    blockingStub.echo(Request.newBuilder().setMessage("message").build());
    // server req and resp bytes
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.response.wf.test.Sample.echo.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get(0));
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.request.wf.test.Sample.echo.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get(0));
    // client req and resp bytes
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.response.wf.test.Sample.echo.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get(0));
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.request.wf.test.Sample.echo.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get(0));
  }

  @Test
  public void testStreamingStats() throws Exception {
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
    // streaming server stats
    assertEquals(27, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.request.wf.test.Sample.replayMessages.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get(0));
    assertEquals(3, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.request.wf.test.Sample.replayMessages.streaming.message_bytes",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).size());
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.response.wf.test.Sample.replayMessages.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, service);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get(0));
    assertEquals(1, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.response.wf.test.Sample.replayMessages.streaming.message_bytes",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).size());
    assertEquals(3, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.request.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get(0));
    assertEquals(1, (long) grpcTestReporter.getHistogram(new MetricName(
        "server.response.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, service);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
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
    }})).get(0));
    assertEquals(3, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.request.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get(0));
    assertEquals(9, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.response.wf.test.Sample.replayMessages.bytes", new HashMap<String, String>() {{
      put(CLUSTER_TAG_KEY, cluster);
      put(SERVICE_TAG_KEY, clientService);
      put(SHARD_TAG_KEY, shard);
      put(GRPC_SERVICE_TAG_KEY, grpcService);
    }})).get(0));
    assertEquals(1, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.response.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get(0));
    assertEquals(3, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.request.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
        }})).get(0));
    assertEquals(1, (long) grpcTestReporter.getHistogram(new MetricName(
        "client.response.wf.test.Sample.replayMessages.streaming.messages_per_rpc",
        new HashMap<String, String>() {{
          put(CLUSTER_TAG_KEY, cluster);
          put(SERVICE_TAG_KEY, clientService);
          put(SHARD_TAG_KEY, shard);
          put(GRPC_SERVICE_TAG_KEY, grpcService);
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
    assertEquals(2.0, grpcTestReporter.getGuage(clientInflight).getValue(), 0.01);
    assertEquals(2.0, grpcTestReporter.getGuage(clientTotalInflight).getValue(), 0.01);
    assertEquals(2.0, grpcTestReporter.getGuage(serverInflight).getValue(), 0.01);
    assertEquals(2.0, grpcTestReporter.getGuage(serverTotalInflight).getValue(), 0.01);
    latch2.countDown();
    Thread.sleep(1000);
    assertEquals(0.0, grpcTestReporter.getGuage(serverInflight).getValue(), 0.01);
    assertEquals(0.0, grpcTestReporter.getGuage(serverTotalInflight).getValue(), 0.01);
    assertEquals(0.0, grpcTestReporter.getGuage(clientInflight).getValue(), 0.01);
    assertEquals(0.0, grpcTestReporter.getGuage(clientTotalInflight).getValue(), 0.01);
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
