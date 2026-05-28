package org.example.exception;

public class DependencyUnavailableException extends RuntimeException {

    private final String dependency;
    private final String operation;
    private final String errorCode;

    public DependencyUnavailableException(String dependency, String operation, String errorCode, Throwable cause) {
        super("Dependency unavailable: " + dependency + "/" + operation + " (" + errorCode + ")", cause);
        this.dependency = dependency;
        this.operation = operation;
        this.errorCode = errorCode;
    }

    public String getDependency() {
        return dependency;
    }

    public String getOperation() {
        return operation;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
