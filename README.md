# Wavefront by VMware gRPC SDK for Java

Wavefront gRPC SDK for Java instruments gRPC APIs to send telemetry data to Wavefront from Java 
applications. It consists of a simple and easily configurable API.

## Usage
If you are using Maven, add following maven dependency to your pom.xml
```
<dependency>
    <groupId>com.wavefront</groupId>
    <artifactId>wavefront-grpc-sdk-java</artifactId>
    <version>0.9.0</version>
</dependency>
```

## gRPC ClientInterceptor and ServerTracer Factory
SDK gather gRPC request/response related telemetry (including streaming specific stats) for both gRPC client and gRPC server. On client a gRPC `ClientInterceptor` is used to provide this info
and on server a `ServeTracer Factory` is used.

### Application Tags
Before you configure the SDK you need to decide the metadata that you wish to emit for those out of the box metrics and histograms. Each and every application should have the application tag defined. If the name of your application is Ordering application, then you can put that as the value for that tag.
```java
    /* Set the name of your grpc based application that you wish to monitor */
    String application = "OrderingApp";
```
grpc based application is composed of microservices. Each and every microservice in your application should have the service tag defined.
```java
    /* Set the name of your service, 
     * for instance - 'inventory' service for your OrderingApp */
    String service = "inventory";
```

You can also define optional tags (cluster and shard).
```java
    /* Optional cluster field, set it to 'us-west-2', assuming
     * your app is running in 'us-west-2' cluster */
    String cluster = "us-west-2";

    /* Optional shard field, set it to 'secondary', assuming your 
     * application has 2 shards - primary and secondary */
    String shard = "secondary";
```

You can add optional custom tags for your application.
```java
    /* Optional custom tags map */
    Map<String, String> customTags = new HashMap<String, String>() {{
      put("location", "Oregon");
      put("env", "Staging");
    }};
```
You can define the above metadata in your application YAML config file.
Now create ApplicationTags instance using the above metatdata.
```java
    /* Create ApplicationTags instance using the above metadata */
    ApplicationTags applicationTags = new ApplicationTags.Builder(application, service).
        cluster(cluster).shard(shard).customTags(customTags).build();
```

### WavefrontSender
We need to instantiate WavefrontSender 
(i.e. either WavefrontProxyClient or WavefrontDirectIngestionClient)
Refer to this page (https://github.com/wavefrontHQ/wavefront-java-sdk/blob/master/README.md)
to instantiate WavefrontProxyClient or WavefrontDirectIngestionClient.

### WavefrontGrpcReporter
```java

    /* Create WavefrontGrpcReporter.Builder using applicationTags.
    WavefrontGrpcReporter.Builder builder = new WavefrontGrpcReporter.Builder(applicationTags);

    /* Set the source for your metrics and histograms */
    builder.withSource("mySource");

    /* Optionally change the reporting frequency to 30 seconds, defaults to 1 min */
    builder.reportingIntervalSeconds(30);

    /* Create a WavefrontGrpcReporter using ApplicationTags metadata and WavefronSender */
    WavefrontGrpcReporter wfGrpcReporter = new WavefrontGrpcReporter.
        Builder(applicationTags).build(wavefrontSender);
```

### WavefrontClientInterceptor
If you are configuring telemetry for a gRPC client, then last step is to construct a 
`WavefrontClientInterceptor` and use it to intercept the gRPC `channel`. You can 
choose to enable or disable streaming specific stats.
```java
    /* Create a Wavefront Client Interceptor using the application tags and wavefront gRPC 
     * reporter */
    WavefrontClientInterceptor wfClientInterceptor = new WavefrontServerTracerFactory(
        wfGrpcReporter, applicationTags, true);
    
```

### WavefrontServerTracerFactory
If you are configuring telemetry for a gRPC server, then last step is to construct a 
`WavefrontServerTracerFactory` and use it to listen to all requests on server. You can choose to 
enable or disable streaming specific stats.
```java
    /* Create a Wavefront Server Tracer Factory using the application tags and wavefront gRPC 
     * reporter */
    WavefrontServerTracerFactory wfServerTracerFactory = new WavefrontServerTracerFactory(
        wfGrpcReporter, applicationTags, true);
    
```

## Out of the box metrics and histograms for your gRPC based application.
Let's say you have order managing gRPC service with the below given proto schema:

```proto
syntax = "proto3";
 
option java_package = "com.wf.examples.inventory"

package com.ordering
 
// The order service definition.
service OrderManager {
   // get single order status
   rpc getOrder (OrderDetails) returns (Order) {}

  // get a stream of orders, which is a server streaming RPC
  rpc getAllOrders (OrderFilter) returns (stream Order) {}
}
```

Let's assume this gRPC Service Server is 
1) part of 'Ordering' application 
2) running inside 'Inventory' microservice 
3) deployed in 'us-west-1' cluster 
4) serviced by 'primary' shard 
5) on source = host-1 
6) this API returns `OK` gRPC status code.

if this gRPC service client and server are instrumented using Wavefront SDK as mentioned above, 
you will see following stats that are sent directly to Wavefront.

Note: for gRPC stats, grpc.service is the service name defined in the proto schema and service refers to user provided value in ApplicationTags.

## Server Metrics
### Request Gauges
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.request.com.ordering.OrderManager.getOrder.inflight|Gauge|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.total_requests.inflight|Gauge|host-1|Ordering|us-west-1|Inventory|primary|n/a|n/a|

### Granular Response related metrics
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

### Granular Streaming metrics and histograms
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.response.com.ordering.OrderManager.getAllOrders.streaming.message_bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.request.com.ordering.OrderManager.getAllOrders.streaming.message_bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.response.com.ordering.OrderManager.getAllOrders.streaming.messages_per_rpc.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.request.com.ordering.OrderManager.getAllOrders.streaming.messages_per_rpc.m|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.response.com.ordering.OrderManager.getAllOrders.streaming.messages.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|
|grpc.server.request.com.ordering.OrderManager.getAllOrders.streaming.messages.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|OrderManager|

### Overall Response metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.response.completed.aggregated_per_source.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|n/a|
|grpc.server.response.completed.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|primary|n/a|
|grpc.server.response.completed.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|n/a|n/a|
|grpc.server.response.completed.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|n/a|
|grpc.server.response.completed.aggregated_per_application.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|n/a|

### Overall Error metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.server.response.errors.aggregated_per_source.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|n/a|
|grpc.server.response.errors.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|primary|n/a|
|grpc.server.response.errors.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|n/a|n/a|
|grpc.server.response.errors.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|n/a|
|grpc.server.response.errors.aggregated_per_application.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|n/a|

## Client Metrics

Let's assume this gRPC Service client accessing the OrderManager is 
1) part of 'Ordering' application 
2) running inside 'Billing' microservice 
3) deployed in 'us-west-1' cluster 
4) serviced by 'primary' shard 
5) on source = host-1 
6) this API returned `OK` gRPC status code.

### Request Gauges
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.request.com.ordering.OrderManager.getOrder.inflight|Gauge|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.total_requests.inflight|Gauge|host-1|Ordering|us-west-1|Billing|primary|n/a|n/a|

### Granular Response related metrics
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

### Granular Streaming metrics and histograms
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.response.com.ordering.OrderManager.getAllOrders.streaming.message_bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.request.com.ordering.OrderManager.getAllOrders.streaming.message_bytes.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.response.com.ordering.OrderManager.getAllOrders.streaming.messages_per_rpc.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.request.com.ordering.OrderManager.getAllOrders.streaming.messages_per_rpc.m|WavefrontHistogram|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.response.com.ordering.OrderManager.getAllOrders.streaming.messages.count|Counter|host-1|Ordering|us-west-1|Billing|primary|OrderManager|
|grpc.client.request.com.ordering.OrderManager.getAllOrders.streaming.messages.count|Counter|host-1|Ordering|us-west-1|Billing|primary|OrderManager|

### Overall Response metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.response.completed.aggregated_per_source.count|Counter|host-1|Ordering|us-west-1|Billing|primary|n/a|
|grpc.client.response.completed.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Billing|primary|n/a|
|grpc.client.response.completed.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Billing|n/a|n/a|
|grpc.client.response.completed.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|n/a|
|grpc.client.response.completed.aggregated_per_application.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|n/a|

### Overall Error metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|grpc.service|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|
|grpc.client.response.errors.aggregated_per_source.count|Counter|host-1|Ordering|us-west-1|Billing|primary|n/a|
|grpc.client.response.errors.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Billing|primary|n/a|
|grpc.client.response.errors.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Billing|n/a|n/a|
|grpc.client.response.errors.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|n/a|
|grpc.client.response.errors.aggregated_per_application.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|n/a|
