package com.wavefront.sdk.grpc;

import io.grpc.MethodDescriptor;

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
}
