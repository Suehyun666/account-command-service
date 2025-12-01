package infrastructure.metrics;

import io.grpc.*;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@GlobalInterceptor
@ApplicationScoped
public class GrpcMetricsInterceptor implements ServerInterceptor {

    private static final Logger log = Logger.getLogger(GrpcMetricsInterceptor.class);

    @Inject GrpcMetrics grpcMetrics;
    @Inject EndToEndMetrics endToEndMetrics;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> serverCall,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        long startNanos = System.nanoTime();
        String fullMethodName = serverCall.getMethodDescriptor().getFullMethodName(); // e.g. account.AccountService/Reserve

        ServerCall<ReqT, RespT> monitoringCall =
                new SimpleForwardingServerCall<>(serverCall) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        long durationNanos = System.nanoTime() - startNanos;

                        // gRPC metrics
                        grpcMetrics.recordRequest(fullMethodName, status, durationNanos);

                        // End-to-End (result는 일단 OK/FAILURE 기준, 나중에 Command 레이어에서 세분화 가능)
                        String result = status.isOk() ? "SUCCESS" : "FAILURE";
                        endToEndMetrics.record(fullMethodName, result, durationNanos);

                        // Structured log
                        log.infof(
                                "{\"msg\":\"grpc_request\",\"method\":\"%s\",\"code\":\"%s\",\"latency_ms\":%.3f}",
                                fullMethodName,
                                status.getCode().name(),
                                durationNanos / 1_000_000.0
                        );

                        super.close(status, trailers);
                    }
                };

        return next.startCall(monitoringCall, headers);
    }
}
