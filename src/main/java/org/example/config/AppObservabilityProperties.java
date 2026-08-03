package org.example.config;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Runtime switches and price inputs for model observability.
 * Prices are estimates supplied by deployment configuration, not billing data.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.observability")
public class AppObservabilityProperties {

    private boolean modelUsageEnabled = true;
    private String defaultModel = "qwen3-max";
    private String currency = "CNY";
    @PositiveOrZero
    private double inputCostPer1kTokens;
    @PositiveOrZero
    private double outputCostPer1kTokens;

    public boolean isModelUsageEnabled() {
        return modelUsageEnabled;
    }

    public void setModelUsageEnabled(boolean modelUsageEnabled) {
        this.modelUsageEnabled = modelUsageEnabled;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public double getInputCostPer1kTokens() {
        return inputCostPer1kTokens;
    }

    public void setInputCostPer1kTokens(double inputCostPer1kTokens) {
        this.inputCostPer1kTokens = inputCostPer1kTokens;
    }

    public double getOutputCostPer1kTokens() {
        return outputCostPer1kTokens;
    }

    public void setOutputCostPer1kTokens(double outputCostPer1kTokens) {
        this.outputCostPer1kTokens = outputCostPer1kTokens;
    }
}
