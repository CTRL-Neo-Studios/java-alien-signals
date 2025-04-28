package dev.ctrlneo.interfaces;

public interface Effect extends Subscriber, Dependency {
    void run();
}
