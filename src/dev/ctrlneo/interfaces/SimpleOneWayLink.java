package dev.ctrlneo.interfaces;

public class SimpleOneWayLink<T> implements OneWayLink<T> {
    private final T target;
    private final OneWayLink<T> linked;

    public SimpleOneWayLink(T target, OneWayLink<T> linked) {
        this.target = target;
        this.linked = linked;
    }

    @Override
    public T getTarget() {
        return target;
    }

    @Override
    public OneWayLink<T> getLinked() {
        return linked;
    }
}
