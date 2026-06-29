package org.example.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.example.config.AppResilienceProperties;
import org.example.dto.DependencyHealthSnapshot;
import org.example.exception.DependencyUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class DependencyGuard {

    private final AppResilienceProperties properties;
    private final ToolCallAttemptContext attemptContext;
    private final CircuitBreakerRegistry registry;
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
    private final Map<String, Long> openedAt = new ConcurrentHashMap<>();
    private final Set<String> disabledDependencies = ConcurrentHashMap.newKeySet();
    private final Set<String> eventPublisherRegistered = ConcurrentHashMap.newKeySet();

    public DependencyGuard(AppResilienceProperties properties) {
        this(properties, new ToolCallAttemptContext());
    }

    @Autowired
    public DependencyGuard(AppResilienceProperties properties, ToolCallAttemptContext attemptContext) {
        this.properties = properties;
        this.attemptContext = attemptContext == null ? new ToolCallAttemptContext() : attemptContext;
        this.registry = CircuitBreakerRegistry.ofDefaults();
    }

    public <T> T execute(String dependency, String operation, Supplier<T> call, Function<Throwable, T> fallback) {
        if (!properties.isEnabled()) {
            disabledDependencies.add(dependency);
            attemptContext.recordAttempts(1);
            return call.get();
        }

        AppResilienceProperties.InstanceConfig config = properties.configFor(dependency);
        CircuitBreaker breaker = breaker(dependency);
        AtomicInteger attempts = new AtomicInteger();
        try {
            T result = breaker.executeSupplier(() -> executeWithRetry(dependency, operation, config, call, attempts));
            attemptContext.recordAttempts(attempts.get());
            return result;
        } catch (CallNotPermittedException e) {
            attemptContext.recordAttempts(0);
            DependencyUnavailableException unavailable =
                    new DependencyUnavailableException(dependency, operation, "CIRCUIT_OPEN", e);
            rememberOpen(dependency, unavailable);
            return fallback.apply(unavailable);
        } catch (RuntimeException e) {
            attemptContext.recordAttempts(attempts.get());
            rememberFailure(dependency, e);
            return fallback.apply(e);
        }
    }

    private <T> T executeWithRetry(String dependency,
                                   String operation,
                                   AppResilienceProperties.InstanceConfig config,
                                   Supplier<T> call,
                                   AtomicInteger attempts) {
        int maxAttempts = config.getRetryMaxAttempts() == null ? 1 : Math.max(1, config.getRetryMaxAttempts());
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attempts.incrementAndGet();
            try {
                return call.get();
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt >= maxAttempts || !isRetryableFailure(e)) {
                    throw e;
                }
                sleepBeforeRetry(dependency, operation, config);
            }
        }
        throw lastFailure == null ? new IllegalStateException("retry attempts exhausted") : lastFailure;
    }

    public void assertAvailable(String dependency, String operation) {
        if (!properties.isEnabled()) {
            disabledDependencies.add(dependency);
            return;
        }

        CircuitBreaker breaker = breaker(dependency);
        if (!breaker.tryAcquirePermission()) {
            DependencyUnavailableException unavailable =
                    new DependencyUnavailableException(dependency, operation, "CIRCUIT_OPEN", null);
            rememberOpen(dependency, unavailable);
            throw unavailable;
        }
        breaker.releasePermission();
    }

    public void recordFailure(String dependency, String operation, Throwable error) {
        if (!properties.isEnabled()) {
            disabledDependencies.add(dependency);
            rememberFailure(dependency, error);
            return;
        }

        CircuitBreaker breaker = breaker(dependency);
        Throwable failure = error == null ? new RuntimeException("unknown failure") : error;
        breaker.onError(0, TimeUnit.MILLISECONDS, failure);
        rememberFailure(dependency, failure);
    }

    public void recordSuccess(String dependency) {
        if (!properties.isEnabled()) {
            disabledDependencies.add(dependency);
            return;
        }

        CircuitBreaker breaker = breaker(dependency);
        if (!breaker.tryAcquirePermission()) {
            rememberOpen(dependency, new DependencyUnavailableException(
                    dependency, "recordSuccess", "CIRCUIT_OPEN", null));
            return;
        }
        breaker.onSuccess(0, TimeUnit.MILLISECONDS);
    }

    public List<DependencyHealthSnapshot> snapshot() {
        List<DependencyHealthSnapshot> snapshots = registry.getAllCircuitBreakers()
                .stream()
                .map(this::toCircuitBreakerSnapshot)
                .toList();

        Set<String> circuitBreakerNames = registry.getAllCircuitBreakers()
                .stream()
                .map(CircuitBreaker::getName)
                .collect(java.util.stream.Collectors.toSet());

        return java.util.stream.Stream.concat(
                        snapshots.stream(),
                        disabledDependencies.stream()
                                .filter(name -> !circuitBreakerNames.contains(name))
                                .map(this::toDisabledSnapshot)
                )
                .sorted(Comparator.comparing(DependencyHealthSnapshot::getName))
                .toList();
    }

    private CircuitBreaker breaker(String dependency) {
        CircuitBreaker breaker = registry.circuitBreaker(dependency, () -> buildConfig(properties.configFor(dependency)));
        registerEventPublisher(breaker);
        return breaker;
    }

    private void registerEventPublisher(CircuitBreaker breaker) {
        if (!eventPublisherRegistered.add(breaker.getName())) {
            return;
        }

        breaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.StateTransition transition = event.getStateTransition();
            if (transition.getToState() == CircuitBreaker.State.OPEN) {
                openedAt.put(breaker.getName(), System.currentTimeMillis());
            } else if (transition.getFromState() == CircuitBreaker.State.OPEN
                    || transition.getToState() == CircuitBreaker.State.CLOSED) {
                openedAt.remove(breaker.getName());
            }
        });
    }

    private CircuitBreakerConfig buildConfig(AppResilienceProperties.InstanceConfig config) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(config.getFailureRateThreshold())
                .slowCallRateThreshold(config.getSlowCallRateThreshold())
                .slowCallDurationThreshold(config.getSlowCallDuration())
                .minimumNumberOfCalls(config.getMinimumCalls())
                .permittedNumberOfCallsInHalfOpenState(config.getPermittedHalfOpenCalls())
                .waitDurationInOpenState(config.getOpenDuration())
                .build();
    }

    private boolean isRetryableFailure(RuntimeException error) {
        return !(error instanceof DependencyUnavailableException);
    }

    private void sleepBeforeRetry(String dependency,
                                  String operation,
                                  AppResilienceProperties.InstanceConfig config) {
        try {
            Thread.sleep(config.getRetryWaitDuration().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailableException(dependency, operation, "RETRY_INTERRUPTED", e);
        }
    }

    private DependencyHealthSnapshot toCircuitBreakerSnapshot(CircuitBreaker breaker) {
        CircuitBreaker.Metrics metrics = breaker.getMetrics();
        DependencyHealthSnapshot snapshot = new DependencyHealthSnapshot();
        snapshot.setName(breaker.getName());
        snapshot.setState(properties.isEnabled() ? breaker.getState().name() : "DISABLED");
        snapshot.setFailureRate(sanitizeRate(metrics.getFailureRate()));
        snapshot.setSlowCallRate(sanitizeRate(metrics.getSlowCallRate()));
        snapshot.setBufferedCalls(metrics.getNumberOfBufferedCalls());
        snapshot.setLastError(lastErrors.get(breaker.getName()));
        snapshot.setOpenedAt(openedAt.get(breaker.getName()));
        return snapshot;
    }

    private DependencyHealthSnapshot toDisabledSnapshot(String dependency) {
        DependencyHealthSnapshot snapshot = new DependencyHealthSnapshot();
        snapshot.setName(dependency);
        snapshot.setState("DISABLED");
        snapshot.setFailureRate(0);
        snapshot.setSlowCallRate(0);
        snapshot.setBufferedCalls(0);
        snapshot.setLastError(lastErrors.get(dependency));
        snapshot.setOpenedAt(null);
        return snapshot;
    }

    private float sanitizeRate(float rate) {
        return rate < 0 ? 0 : rate;
    }

    private void rememberFailure(String dependency, Throwable error) {
        lastErrors.put(dependency, messageFor(error));
        CircuitBreaker breaker = registry.find(dependency).orElse(null);
        if (breaker != null && breaker.getState() == CircuitBreaker.State.OPEN) {
            openedAt.putIfAbsent(dependency, System.currentTimeMillis());
        }
    }

    private void rememberOpen(String dependency, Throwable error) {
        lastErrors.putIfAbsent(dependency, messageFor(error));
        openedAt.putIfAbsent(dependency, System.currentTimeMillis());
    }

    private String messageFor(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    int registeredBreakerCount() {
        return eventPublisherRegistered.size();
    }
}
