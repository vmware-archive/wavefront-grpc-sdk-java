package com.wavefront.sdk.grpc;

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
}
