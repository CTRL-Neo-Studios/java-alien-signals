package dev.ctrlneo.interfaces;

public interface OneWayLink<T> {
    T getTarget();
    OneWayLink<T> getLinked();
}
