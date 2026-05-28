package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.resilience")
public class AppResilienceProperties {

    private boolean enabled = true;
    private InstanceConfig defaultConfig = new InstanceConfig();
    private Map<String, InstanceConfig> instances = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public InstanceConfig getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(InstanceConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Map<String, InstanceConfig> getInstances() {
        return instances;
    }

    public void setInstances(Map<String, InstanceConfig> instances) {
        this.instances = instances;
    }

    public InstanceConfig configFor(String name) {
        InstanceConfig merged = InstanceConfig.hardDefaults();
        merged.copyFrom(defaultConfig);

        InstanceConfig specific = instances == null ? null : instances.get(name);
        if (specific != null) {
            merged.copyFrom(specific);
        }

        return merged;
    }

    public static class InstanceConfig {
        private Float failureRateThreshold;
        private Float slowCallRateThreshold;
        private Duration slowCallDuration;
        private Integer minimumCalls;
        private Integer permittedHalfOpenCalls;
        private Duration openDuration;

        public static InstanceConfig hardDefaults() {
            InstanceConfig defaults = new InstanceConfig();
            defaults.setFailureRateThreshold(50.0f);
            defaults.setSlowCallRateThreshold(50.0f);
            defaults.setSlowCallDuration(Duration.ofSeconds(5));
            defaults.setMinimumCalls(5);
            defaults.setPermittedHalfOpenCalls(2);
            defaults.setOpenDuration(Duration.ofSeconds(30));
            return defaults;
        }

        public void copyFrom(InstanceConfig other) {
            if (other == null) {
                return;
            }
            if (other.failureRateThreshold != null) {
                this.failureRateThreshold = other.failureRateThreshold;
            }
            if (other.slowCallRateThreshold != null) {
                this.slowCallRateThreshold = other.slowCallRateThreshold;
            }
            if (other.slowCallDuration != null) {
                this.slowCallDuration = other.slowCallDuration;
            }
            if (other.minimumCalls != null) {
                this.minimumCalls = other.minimumCalls;
            }
            if (other.permittedHalfOpenCalls != null) {
                this.permittedHalfOpenCalls = other.permittedHalfOpenCalls;
            }
            if (other.openDuration != null) {
                this.openDuration = other.openDuration;
            }
        }

        public Float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(Float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Float getSlowCallRateThreshold() {
            return slowCallRateThreshold;
        }

        public void setSlowCallRateThreshold(Float slowCallRateThreshold) {
            this.slowCallRateThreshold = slowCallRateThreshold;
        }

        public Duration getSlowCallDuration() {
            return slowCallDuration;
        }

        public void setSlowCallDuration(Duration slowCallDuration) {
            this.slowCallDuration = slowCallDuration;
        }

        public Integer getMinimumCalls() {
            return minimumCalls;
        }

        public void setMinimumCalls(Integer minimumCalls) {
            this.minimumCalls = minimumCalls;
        }

        public Integer getPermittedHalfOpenCalls() {
            return permittedHalfOpenCalls;
        }

        public void setPermittedHalfOpenCalls(Integer permittedHalfOpenCalls) {
            this.permittedHalfOpenCalls = permittedHalfOpenCalls;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }
    }
}
