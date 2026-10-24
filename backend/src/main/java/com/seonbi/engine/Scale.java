package com.seonbi.engine;

public record Scale(double min, double max) {
    public double span() {
        return max - min;
    }
}
