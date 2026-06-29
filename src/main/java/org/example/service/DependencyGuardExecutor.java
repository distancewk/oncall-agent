package org.example.service;

import io.milvus.param.R;
import org.example.exception.DependencyUnavailableException;

import java.util.function.Supplier;

public final class DependencyGuardExecutor {

    private DependencyGuardExecutor() {
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    public static <T> T executeChecked(DependencyGuard guard,
                                       String dependency,
                                       String operation,
                                       CheckedSupplier<T> supplier) throws Exception {
        if (guard == null) {
            return supplier.get();
        }
        try {
            return guard.execute(dependency, operation,
                    () -> {
                        try {
                            return supplier.get();
                        } catch (Exception e) {
                            throw new GuardedDependencyException(e);
                        }
                    },
                    error -> {
                        if (error instanceof DependencyUnavailableException unavailable) {
                            throw unavailable;
                        }
                        if (error instanceof RuntimeException runtimeException) {
                            throw runtimeException;
                        }
                        throw new GuardedDependencyException(error);
                    });
        } catch (GuardedDependencyException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    public static <T> R<T> executeMilvus(DependencyGuard guard,
                                         String operation,
                                         Supplier<R<T>> call) {
        if (guard == null) {
            return call.get();
        }
        return guard.execute("milvus", operation,
                () -> {
                    R<T> response = call.get();
                    if (response.getStatus() != 0 && response.getStatus() != 65535) {
                        throw new RuntimeException(response.getMessage());
                    }
                    return response;
                },
                error -> {
                    if (error instanceof DependencyUnavailableException unavailable) {
                        throw unavailable;
                    }
                    if (error instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new RuntimeException(error);
                });
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static class GuardedDependencyException extends RuntimeException {
        GuardedDependencyException(Throwable cause) {
            super(cause);
        }
    }
}
