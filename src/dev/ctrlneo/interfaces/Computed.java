package dev.ctrlneo.interfaces;

public interface Computed<T> extends Reactive.Signal<T>, Subscriber {
    T getter(T previousValue);
}
