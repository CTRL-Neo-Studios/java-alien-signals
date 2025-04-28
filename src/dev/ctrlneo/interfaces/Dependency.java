package dev.ctrlneo.interfaces;

import dev.ctrlneo.Link;

public interface Dependency {
    Link getSubs();
    Link getSubsTail();
    void setSubs(Link link);
    void setSubsTail(Link link);
}