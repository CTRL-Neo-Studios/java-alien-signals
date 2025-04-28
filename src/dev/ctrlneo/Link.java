package dev.ctrlneo;

import dev.ctrlneo.interfaces.Dependency;
import dev.ctrlneo.interfaces.Subscriber;

public class Link {
    public Dependency dep;
    public Subscriber sub;
    public Link prevSub;
    public Link nextSub;
    public Link nextDep;

    public Link(Dependency dep, Subscriber sub) {
        this.dep = dep;
        this.sub = sub;
    }
}
