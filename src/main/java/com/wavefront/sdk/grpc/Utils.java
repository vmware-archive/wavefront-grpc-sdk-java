package com.wavefront.sdk.grpc;

import com.google.common.collect.ImmutableMap;

import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.grpc.reporter.WavefrontGrpcReporter;

import java.util.Map;

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
 * Utilities for generating gRPC stats.
 *
 * @author Srujan Narkedamalli (snarkedamall@wavefront.com).
 */
final class Utils {

  private Utils() {
  }

  /**
   * Extracts the gRPC service name from the full method name.
   *
   * @param fullMethodName of the gRPC method.
   * @return gRPC service name.
   */
  static String getServiceName(String fullMethodName) {
    return fullMethodName.substring(0, fullMethodName.lastIndexOf('/'));
  }

  /**
   * Whether gRPC method is of streaming type.
   *
   * @param methodType type of the gRPC method for which to determine whether streaming or not.
   * @return true if its streaming gRPC.
   */
  static boolean isStreamingMethod(MethodDescriptor.MethodType methodType) {
    return methodType == MethodDescriptor.MethodType.SERVER_STREAMING ||
        methodType == MethodDescriptor.MethodType.CLIENT_STREAMING ||
        methodType == MethodDescriptor.MethodType.BIDI_STREAMING;
  }

  /**
   * Give a friendly name of gRPC full methodName that can be used as a part of a metric name.
   *
   * @param fullMethodName fully qualified gRPC method name.
   * @return a grpc full method name which is metric name friendly
   */
  static String getFriendlyMethodName(String fullMethodName) {
    return fullMethodName.replace("/", ".");
  }

  /**
   * Reports request message count related stats.
   */
  static void reportRequestMessageCount(String prefix, String methodName, long requestMessageCount,
                                        Map<String, String> allTags,
                                        Map<String, String> histogramTags,
                                        WavefrontGrpcReporter wfGrpcReporter) {
    wfGrpcReporter.updateHistogram(new MetricName(
            prefix + "request." + methodName + ".streaming.messages_per_rpc", histogramTags),
        requestMessageCount);
    wfGrpcReporter.incrementCounter(
        new MetricName(prefix + "request." + methodName + ".streaming.messages", allTags),
        requestMessageCount);
  }

  /**
   * Reports response message count related stats.
   */
  static void reportResponseMessageCount(String prefix, String methodName,
                                         long responseMessageCount, Map<String, String> allTags,
                                         Map<String, String> histogramTags,
                                         WavefrontGrpcReporter wfGrpcReporter) {
    wfGrpcReporter.updateHistogram(new MetricName(
            prefix + "response." + methodName + ".streaming.messages_per_rpc", histogramTags),
        responseMessageCount);
    wfGrpcReporter.incrementCounter(
        new MetricName(prefix + "response." + methodName + ".streaming.messages", allTags),
        responseMessageCount);
  }

  /**
   * Reports RPC latency related stats.
   */
  static void reportLatency(String prefix, String methodName, Status status, long rpcLatency,
                            Map<String, String> histogramTags, Map<String, String> allTags,
                            WavefrontGrpcReporter wfGrpcReporter) {
    String methodWithStatus = methodName + "." + status.getCode().toString();
    wfGrpcReporter.updateHistogram(new MetricName(
        prefix + "response." + methodWithStatus + ".latency", histogramTags), rpcLatency);
    wfGrpcReporter.incrementCounter(new MetricName(
        prefix + "response." + methodWithStatus + ".total_time", allTags), rpcLatency);
  }

  /**
   * Reports request bytes related stats.
   */
  static void reportRpcRequestBytes(String prefix, String methodName, long requestBytes,
                                    Map<String, String> histogramTags, Map<String, String> allTags,
                                    WavefrontGrpcReporter wfGrpcReporter) {
    wfGrpcReporter.updateHistogram(new MetricName(
        prefix + "request." + methodName + ".bytes", histogramTags), requestBytes);
    wfGrpcReporter.incrementCounter(new MetricName(
        prefix + "request." + methodName + ".total_bytes", allTags), requestBytes);
  }

  /**
   * Reports response bytes related stats.
   */
  static void reportRpcResponseBytes(String prefix, String methodName, long responseBytes,
                                     Map<String, String> tags, Map<String, String> allTags,
                                     WavefrontGrpcReporter wfGrpcReporter) {
    wfGrpcReporter.updateHistogram(new MetricName(
        prefix + "response." + methodName + ".bytes", tags), responseBytes);
    wfGrpcReporter.incrementCounter(new MetricName(
        prefix + "response." + methodName + ".total_bytes", allTags), responseBytes);
  }

  /**
   * Reports response and error overall and granular stats.
   */
  static void reportResponseAndErrorStats(String prefix, String methodName,
                                          String grpcService, Status status,
                                          ApplicationTags applicationTags,
                                          Map<String, String> allTags,
                                          Map<String, String> overallAggregatedPerSourceTags,
                                          WavefrontGrpcReporter wfGrpcReporter) {
    String methodWithStatus = methodName + "." + status.getCode().toString();
    ImmutableMap.Builder<String, String> granularMetricsTags =
        ImmutableMap.<String, String>builder().put(GRPC_SERVICE_TAG_KEY, grpcService).
            put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
    Map<String, String> aggergatedPerApplicationTags = granularMetricsTags.build();
    Map<String, String> aggregatedPerClutserTags = granularMetricsTags.put(CLUSTER_TAG_KEY,
        applicationTags.getCluster() == null ? NULL_TAG_VAL : applicationTags.getCluster()).build();
    Map<String, String> aggregatedPerServiceTags = granularMetricsTags.put(SERVICE_TAG_KEY,
        applicationTags.getService()).build();
    Map<String, String> aggregatedPerShardTags = granularMetricsTags.put(SHARD_TAG_KEY,
        applicationTags.getShard() == null ? NULL_TAG_VAL : applicationTags.getShard()).build();
    // granular RPC stats
    wfGrpcReporter.incrementCounter(
        new MetricName(prefix + "response." + methodWithStatus + ".cumulative", allTags));
    wfGrpcReporter.incrementDeltaCounter(
        new MetricName(prefix + "response." + methodWithStatus + ".aggregated_per_shard",
            aggregatedPerShardTags));
    wfGrpcReporter.incrementDeltaCounter(
        new MetricName(prefix + "response." + methodWithStatus + ".aggregated_per_service",
            aggregatedPerServiceTags));
    wfGrpcReporter.incrementDeltaCounter(
        new MetricName(prefix + "response." + methodWithStatus + ".aggregated_per_cluster",
            aggregatedPerClutserTags));
    wfGrpcReporter.incrementDeltaCounter(
        new MetricName(prefix + "response." + methodWithStatus + ".aggregated_per_application",
            aggergatedPerApplicationTags));
    // overall RPC stats
    ImmutableMap.Builder<String, String> overallTagsBuilder =
        ImmutableMap.<String, String>builder().put(SOURCE_KEY, WAVEFRONT_PROVIDED_SOURCE);
    Map<String, String> overallAggregatedPerApplicationTags = overallTagsBuilder.build();
    Map<String, String> overallAggregatedPerClusterTags = overallTagsBuilder.put(CLUSTER_TAG_KEY,
        applicationTags.getCluster() == null ? NULL_TAG_VAL : applicationTags.getCluster()).build();
    Map<String, String> overallAggregatedPerServiceTags = overallTagsBuilder.put(SERVICE_TAG_KEY,
        applicationTags.getService()).build();
    Map<String, String> overallAggregatedPerShardTags = overallTagsBuilder.put(SHARD_TAG_KEY,
        applicationTags.getShard() == null ? NULL_TAG_VAL : applicationTags.getShard()).build();
    wfGrpcReporter.incrementCounter(
        new MetricName(prefix + "response.completed.aggregated_per_source",
            overallAggregatedPerSourceTags));
    wfGrpcReporter.incrementDeltaCounter(
        new MetricName(prefix + "response.completed.aggregated_per_shard",
            overallAggregatedPerShardTags));
    wfGrpcReporter.incrementDeltaCounter(
        new MetricName(prefix + "response.completed.aggregated_per_service",
            overallAggregatedPerServiceTags));
    wfGrpcReporter.incrementDeltaCounter(
        new MetricName(prefix + "response.completed.aggregated_per_cluster",
            overallAggregatedPerClusterTags));
    wfGrpcReporter.incrementDeltaCounter(
        new MetricName(prefix + "response.completed.aggregated_per_application",
            overallAggregatedPerApplicationTags));
    // overall and granular error stats
    if (status.getCode() != Status.Code.OK) {
      wfGrpcReporter.incrementCounter(
          new MetricName(prefix + "response.errors.aggregated_per_source",
              overallAggregatedPerSourceTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(prefix + "response.errors.aggregated_per_shard",
              overallAggregatedPerShardTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(prefix + "response.errors.aggregated_per_service",
              overallAggregatedPerServiceTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(prefix + "response.errors.aggregated_per_cluster",
              overallAggregatedPerClusterTags));
      wfGrpcReporter.incrementDeltaCounter(
          new MetricName(prefix + "response.errors.aggregated_per_application",
              overallAggregatedPerApplicationTags));
      wfGrpcReporter.incrementCounter(
          new MetricName(prefix + "response." + methodName + ".errors", allTags));
    }
  }
}
