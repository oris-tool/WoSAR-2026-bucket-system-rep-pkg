package it.unifi.dinfo.stlab;

import java.util.Objects;

public record WorkloadLevel(String name, double rate) {
    public WorkloadLevel {
        Objects.requireNonNull(name);
        if (rate < 0) throw new IllegalArgumentException("Negative Rate: " + rate);
    }
}
