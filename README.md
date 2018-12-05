# wavefront-grpc-sdk-java [![build status][ci-img]][ci] [![Released Version][maven-img]][maven]

The Wavefront gRPC SDK for Java is a library that collects out-of-the-box metrics, histograms, and trace data from gRPC operations in your Java application, and reports that data to Wavefront. You can analyze the telemetry data in [Wavefront](https://www.wavefront.com) to better understand how your application is performing in production. 


## Maven
If you are using Maven, add following maven dependency to your `pom.xml`.
```
<dependency>
    <groupId>com.wavefront</groupId>
    <artifactId>wavefront-grpc-sdk-java</artifactId>
    <version>$releaseVersion</version>
</dependency>
```
Replace `$releaseVersion` with the latest version available on [maven](http://search.maven.org/#search%7Cga%7C1%7Cwavefront-grpc-sdk-java).

## Setup Steps

Follow the steps below to set up objects for collecting metrics, histograms, and trace data from gRPC requests and responses in a microservice. 


For each gRPC-based microservice, [add the dependency](#maven) if you have not already done so, and then perform the following steps:

1. [Create an `ApplicationTags` instance](#1-set-up-application-tags), which specifies metadata about your application.
2. [Create a `WavefrontSender`](#2-set-up-a-wavefrontsender) for sending data to Wavefront.
3. [Create a `WavefrontGrpcReporter`](#3-set-up-a-wavefrontgrpcreporter) for reporting gRPC metrics and histograms to Wavefront.
4. Create a `WavefrontClientInterceptor` or a `WavefrontServerTracerFactory`, as appropriate:
    - [To instrument a gRPC client, create a `WavefrontClientInterceptor`](#option-1-instrument-a-grpc-client) to collect telemetry data from sent requests and received responses.

    - [To instrument a gRPC server, create a `WavefrontServerTracerFactory`](#option-2-instrument-a-grpc-server) to collect telemetry data from the received requests and sent responses.

For the details of each step, see the sections below.

### 1. Set Up Application Tags

Application tags determine the metadata (point tags and span tags) that are included with every metric/histogram/span reported to Wavefront. These tags enable you to filter and query the reported data in Wavefront.

You encapsulate application tags in an `ApplicationTags` object. See [Instantiating ApplicationTags](https://github.com/wavefrontHQ/wavefront-sdk-java/blob/master/docs/apptags.md) for details.

### 2. Set Up a WavefrontSender

A `WavefrontSender` object implements the low-level interface for sending data to Wavefront. You can choose to send data to Wavefront using either the [Wavefront proxy](https://docs.wavefront.com/proxies.html) or [direct ingestion](https://docs.wavefront.com/direct_ingestion.html).

* If you have already set up a `WavefrontSender` for another SDK that will run in the same JVM, use that one.  (For details about sharing a `WavefrontSender` instance, see [Share a WavefrontSender](https://github.com/wavefrontHQ/wavefront-sdk-java/blob/master/docs/sender.md#share-a-wavefrontsender).)

* Otherwise, follow the steps in [Set Up a WavefrontSender](https://github.com/wavefrontHQ/wavefront-sdk-java/blob/master/docs/sender.md#set-up-a-wavefrontsender).


### 3. Set Up a WavefrontGrpcReporter

A `WavefrontGrpcReporter` reports trace data to Wavefront. To build a `WavefrontGrpcReporter`, you specify:

* An `ApplicationTags` object ([see above](#1-set-up-application-tags))
* A `WavefrontSender` object ([see above](#2-set-up-a-wavefrontsender)).

You can optionally specify:
* A nondefault source for the reported data. If you omit the source, the host name is automatically used.
* A nondefault reporting interval, which controls how often data is reported to the WavefrontSender. The reporting interval determines the timestamps on the data sent to Wavefront. If you omit the reporting interval, data is reported once a minute.

```java

ApplicationTags applicationTags = buildTags(); // pseudocode; see above

// Create WavefrontGrpcReporter.Builder using applicationTags.
WavefrontGrpcReporter.Builder wfGrpcReporterBuilder = new WavefrontGrpcReporter.Builder(applicationTags);

// Optionally set a nondefault source name for your metrics and histograms. Omit this statement to use the host name.
wfGrpcReporterBuilder.withSource("mySource");

// Optionally change the reporting interval to 30 seconds. Default is 1 minute.
wfGrpcReporterBuilder.reportingIntervalSeconds(30);

// Create a WavefrontGrpcReporter with a WavefrontSender
WavefrontGrpcReporter wfGrpcReporter = wfGrpcReporterBuilder.build(wavefrontSender);
```

### 4. Set Up Client or Server Instrumentation

Choose one or both of the following options based on your microservice's behavior.

#### Option 1. Instrument a gRPC Client
If you are instrumenting a gRPC client, set up a
`WavefrontClientInterceptor` to intercept the gRPC `channel` and collect telemetry data from all requests that the client sends. Specify `true` or `false` to enable or disable streaming specific stats.

```java
// Create a WavefrontClientInterceptor using ApplicationTags and a WavefrontGrpcReporter.
WavefrontClientInterceptor wfClientInterceptor = new WavefrontServerTracerFactory(wfGrpcReporter, applicationTags, true);
```

#### Option 2. Instrument a gRPC Server
If you are instrumenting a gRPC server, set up a `WavefrontServerTracerFactory` and to collect telemetry data from all requests that the client receives. Specify `true` or `false` to enable or disable streaming specific stats.

```java
// Create a WavefrontServerTracerFactory using the application tags and a WavefrontGrpcReporter.
WavefrontServerTracerFactory wfServerTracerFactory = new WavefrontServerTracerFactory(wfGrpcReporter, applicationTags, true);
```

## Metrics and Histograms Sent From gRPC Operations

Let's say you have an order-managing gRPC service with the following proto schema:

```proto
syntax = "proto3";

option java_package = "com.wf.examples.inventory"

package com.ordering

// The order service definition.
service OrderManager {
   // Get single order status
   rpc getOrder (OrderDetails) returns (Order) {}

  // Get a stream of orders, which is a server streaming RPC
  rpc getAllOrders (OrderFilter) returns (stream Order) {}
}
```

When instrumented using the Wavefront gRPC SDK for Java, the gRPC service sends [Server Metrics](#server-metrics) or [Client Metrics](#client-metrics) as appropriate.

**Note:** For gRPC stats, `grpc.service` is the service name defined in the proto schema and `service` refers to user-provided value in `ApplicationTags`.

## Server Metrics

Assume this gRPC Service server is:
1) Part of the `Ordering` application
2) Running inside the `Inventory` microservice
3) Deployed in `us-west-1` cluster
4) Serviced by `primary` shard
5) On source = `host-1`
6) And the API call returns `OK` gRPC status code.

If this gRPC service is instrumented as a server using the Wavefront gRPC SDK for Java, the stats listed below are sent to Wavefront.

### Request Gauges
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.request.com.ordering.OrderManager.getOrder.inflight|Gauge|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.total_requests.inflight|Gauge|host-1|Ordering|us-west-1|Inventory|primary|n/a|n/a|

### Granular Response Related Metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.com.ordering.OrderManager.getOrder.OK.cumulative.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.rcom.ordering.OrderManager.getOrder.OK.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.response.com.ordering.OrderManager.getOrder.OK.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|n/a|OrderManager|
|grpc.server.response.icom.ordering.OrderManager.getOrder.OK.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|OrderManager|
|grpc.server.response.com.ordering.OrderManager.getOrder.OK.aggregated_per_appliation.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|OrderManager|

### Granular Response Latency Histogram
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.response.com.ordering.OrderManager.getOrder.OK.latency.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|

### Granular Request and Response Payload Size Histograms
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.response.com.ordering.OrderManager.getOrder.OK.bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.request.com.ordering.OrderManager.getOrder.OK.bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|

### Granular Streaming Metrics and Histograms
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.response.com.ordering.OrderManager.getAllOrders.streaming.message_bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.request.com.ordering.OrderManager.getAllOrders.streaming.message_bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.response.com.ordering.OrderManager.getAllOrders.streaming.messages_per_rpc.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.request.com.ordering.OrderManager.getAllOrders.streaming.messages_per_rpc.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.response.com.ordering.OrderManager.getAllOrders.streaming.messages.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.request.com.ordering.OrderManager.getAllOrders.streaming.messages.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|

### Overall Response Metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.response.completed.aggregated_per_source.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|n/a|
|grpc.server.response.completed.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|primary|n/a|
|grpc.server.response.completed.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|n/a|n/a|
|grpc.server.response.completed.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|n/a|
|grpc.server.response.completed.aggregated_per_application.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|n/a|

### Overall Error Metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.response.errors.aggregated_per_source.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|n/a|
|grpc.server.response.errors.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|primary|n/a|
|grpc.server.response.errors.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|n/a|n/a|
|grpc.server.response.errors.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|n/a|
|grpc.server.response.errors.aggregated_per_application.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|n/a|

## Client Metrics

Assume this gRPC Service client accessing the `OrderManager` is:

1) Part of the `Ordering` application
2) Running inside the `Billing` microservice
3) Deployed in `us-west-1` cluster
4) Serviced by `primary` shard
5) On source = `host-1`
6) And the API call returns `OK` gRPC status code.

If this gRPC service is instrumented as a client using the Wavefront gRPC SDK for Java, the stats listed below are sent to Wavefront.

### Request Gauges
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.request.com.ordering.OrderManager.getOrder.inflight|Gauge|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.total_requests.inflight|Gauge|host-1|Ordering|us-west-1|Billing|primary|n/a|n/a|

### Granular Response Related Metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.com.ordering.OrderManager.getOrder.OK.cumulative.count|Counter|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.rcom.ordering.OrderManager.getOrder.OK.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.response.com.ordering.OrderManager.getOrder.OK.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Billing|n/a|OrderManager|
|grpc.client.response.icom.ordering.OrderManager.getOrder.OK.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|OrderManager|
|grpc.client.response.com.ordering.OrderManager.getOrder.OK.aggregated_per_appliation.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|OrderManager|

### Granular Response Latency Histogram
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.response.com.ordering.OrderManager.getOrder.OK.latency.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|

### Granular Request and Response Payload Size Histograms
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.response.com.ordering.OrderManager.getOrder.OK.bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.request.com.ordering.OrderManager.getOrder.OK.bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|

### Granular Streaming Metrics and Histograms
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.response.com.ordering.OrderManager.getAllOrders.streaming.message_bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.request.com.ordering.OrderManager.getAllOrders.streaming.message_bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.response.com.ordering.OrderManager.getAllOrders.streaming.messages_per_rpc.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.request.com.ordering.OrderManager.getAllOrders.streaming.messages_per_rpc.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.response.com.ordering.OrderManager.getAllOrders.streaming.messages.count|Counter|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.request.com.ordering.OrderManager.getAllOrders.streaming.messages.count|Counter|host-1|Ordering|us-west-1|Billing|primary|OrderManager|

### Overall Response Metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.response.completed.aggregated_per_source.count|Counter|host-1|Ordering|us-west-1|Billing|primary|n/a|
|grpc.client.response.completed.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Billing|primary|n/a|
|grpc.client.response.completed.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Billing|n/a|n/a|
|grpc.client.response.completed.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|n/a|
|grpc.client.response.completed.aggregated_per_application.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|n/a|

### Overall Error Metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.response.errors.aggregated_per_source.count|Counter|host-1|Ordering|us-west-1|Billing|primary|n/a|
|grpc.client.response.errors.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Billing|primary|n/a|
|grpc.client.response.errors.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Billing|n/a|n/a|
|grpc.client.response.errors.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|n/a|
|grpc.client.response.errors.aggregated_per_application.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|n/a|

[ci-img]: https://travis-ci.com/wavefrontHQ/wavefront-grpc-sdk-java.svg?branch=master
[ci]: https://travis-ci.com/wavefrontHQ/wavefront-grpc-sdk-java
[maven-img]: https://img.shields.io/maven-central/v/com.wavefront/wavefront-grpc-sdk-java.svg?maxAge=2592000
[maven]: http://search.maven.org/#search%7Cga%7C1%7Cwavefront-grpc-sdk-java
