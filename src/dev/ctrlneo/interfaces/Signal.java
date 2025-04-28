package dev.ctrlneo.interfaces;

public interface Signal<T> extends Dependency {
    T getCurrentValue();
    void setCurrentValue(T value);
}
