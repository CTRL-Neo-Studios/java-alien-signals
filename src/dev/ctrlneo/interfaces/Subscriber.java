package dev.ctrlneo.interfaces;

import dev.ctrlneo.Link;

public interface Subscriber {
    int getFlags();
    Link getDeps();
    Link getDepsTail();
    void setFlags(int flags);
    void setDeps(Link link);
    void setDepsTail(Link link);
}
