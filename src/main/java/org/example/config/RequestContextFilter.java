package org.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Establishes request/trace correlation independently of authentication. */
@Component("appRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String REQUEST_ID_ATTRIBUTE = "APP_REQUEST_ID";

    private final ObjectProvider<TraceExportService> traceExportProvider;

    public RequestContextFilter(ObjectProvider<TraceExportService> traceExportProvider) {
        this.traceExportProvider = traceExportProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = requestCorrelationId(request);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(TraceExportService.START_NANOS_ATTRIBUTE, System.nanoTime());
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("request_id", requestId);

        TraceContext.Context traceContext = TraceContext.from(
                request.getHeader(TraceContext.TRACEPARENT_HEADER));
        request.setAttribute(TraceExportService.TRACE_ID_ATTRIBUTE, traceContext.traceId());
        request.setAttribute(TraceExportService.SPAN_ID_ATTRIBUTE, traceContext.spanId());
        response.setHeader(TraceContext.TRACEPARENT_HEADER, traceContext.traceparent());
        MDC.put("trace_id", traceContext.traceId());
        MDC.put("span_id", traceContext.spanId());
        MDC.put("traceparent", traceContext.traceparent());

        Exception failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException exception) {
            failure = exception;
            throw exception;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            TraceExportService traceExportService = traceExportProvider.getIfAvailable();
            if (traceExportService != null) {
                traceExportService.export(request, response, failure);
            }
            MDC.remove("request_id");
            MDC.remove("trace_id");
            MDC.remove("span_id");
            MDC.remove("traceparent");
        }
    }

    private String requestCorrelationId(HttpServletRequest request) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        if (supplied != null && supplied.matches("[A-Za-z0-9._:-]{1,64}")) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }
}
