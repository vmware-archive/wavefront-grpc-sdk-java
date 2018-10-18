package com.wavefront.sdk.grpc;

import io.grpc.Context;
import io.opentracing.Span;

/**
 * gRPC SDK related constants.
 *
 * @author Srujan Narkedamalli (snarkedamall@wavefront.com).
 */
public final class Constants {

  private Constants() {
  }

  /**
   * Tag key to define a  grpc service, which is the service defined in RPC IDL.
   */
  public static final String GRPC_SERVICE_TAG_KEY = "grpc.service";

  /**
   * Name of gRPC server component.
   */
  public static final String GRPC_SERVER_COMPONENT = "grpc-server";

  /**
   * Name of gRPC client component.
   */
  public static final String GRPC_CLIENT_COMPONENT = "grpc-client";

  /**
   * gRPC context key used for storing the active span.
   */
  public static final Context.Key<Span> GRPC_CONTEXT_SPAN_KEY = Context.key("opentracing-span-key");

  /**
   * Tag key to define gRPC method type.
   */
  public static final String GRPC_METHOD_TYPE_KEY = "grpc.method_type";

  /**
   * Tag key to define gRPC status.
   */
  public static final String GRPC_STATUS_KEY = "grpc.status";
}
