package dev.ctrlneo;

import dev.ctrlneo.interfaces.*;

import java.util.ArrayList;
import java.util.List;

public class ReactiveSystem {
    public static final int COMPUTED = 1 << 0;
    public static final int EFFECT = 1 << 1;
    public static final int TRACKING = 1 << 2;
    public static final int NOTIFIED = 1 << 3;
    public static final int RECURSED = 1 << 4;
    public static final int DIRTY = 1 << 5;
    public static final int PENDING_COMPUTED = 1 << 6;
    public static final int PENDING_EFFECT = 1 << 7;
    public static final int PROPAGATED = DIRTY | PENDING_COMPUTED | PENDING_EFFECT;

    private final ComputedUpdater updateComputed;
    private final EffectNotifier notifyEffect;
    private final List<Subscriber> notifyBuffer = new ArrayList<>();
    private int notifyIndex = 0;
    private int notifyBufferLength = 0;

    public ReactiveSystem(ComputedUpdater updateComputed, EffectNotifier notifyEffect) {
        this.updateComputed = updateComputed;
        this.notifyEffect = notifyEffect;
    }

    public Link link(Dependency dep, Subscriber sub) {
        Link currentDep = sub.getDepsTail();
        if (currentDep != null && currentDep.dep == dep) {
            return null;
        }

        Link nextDep = currentDep != null ? currentDep.nextDep : sub.getDeps();
        if (nextDep != null && nextDep.dep == dep) {
            sub.setDepsTail(nextDep);
            return null;
        }

        Link depLastSub = dep.getSubsTail();
        if (depLastSub != null && depLastSub.sub == sub && isValidLink(depLastSub, sub)) {
            return null;
        }

        return linkNewDep(dep, sub, nextDep, currentDep);
    }

    public void propagate(Link current) {
        Link next = current.nextSub;
        OneWayLink<Link> branchs = null;
        int branchDepth = 0;
        int targetFlag = DIRTY;

        top: do {
            Subscriber sub = current.sub;
            int subFlags = sub.getFlags();

            boolean shouldNotify = false;

            if ((subFlags & (TRACKING | RECURSED | PROPAGATED)) == 0) {
                sub.setFlags(subFlags | targetFlag | NOTIFIED);
                shouldNotify = true;
            } else if ((subFlags & RECURSED) != 0 && (subFlags & TRACKING) == 0) {
                sub.setFlags((subFlags & ~RECURSED) | targetFlag | NOTIFIED);
                shouldNotify = true;
            } else if ((subFlags & PROPAGATED) == 0 && isValidLink(current, sub)) {
                sub.setFlags(subFlags | RECURSED | targetFlag | NOTIFIED);
                shouldNotify = ((Dependency) sub).getSubs() != null;
            }

            if (shouldNotify) {
                Link subSubs = ((Dependency) sub).getSubs();
                if (subSubs != null) {
                    current = subSubs;
                    if (subSubs.nextSub != null) {
                        branchs = new SimpleOneWayLink<>(next, branchs);
                        ++branchDepth;
                        next = current.nextSub;
                        targetFlag = PENDING_COMPUTED;
                    } else {
                        targetFlag = (subFlags & EFFECT) != 0
                                ? PENDING_EFFECT
                                : PENDING_COMPUTED;
                    }
                    continue;
                }
                if ((subFlags & EFFECT) != 0) {
                    if (notifyBufferLength == notifyBuffer.size()) {
                        notifyBuffer.add(sub);
                    } else {
                        notifyBuffer.set(notifyBufferLength, sub);
                    }
                    notifyBufferLength++;
                }
            } else if ((subFlags & (TRACKING | targetFlag)) == 0) {
                sub.setFlags(subFlags | targetFlag | NOTIFIED);
                if ((subFlags & (EFFECT | NOTIFIED)) == EFFECT) {
                    if (notifyBufferLength == notifyBuffer.size()) {
                        notifyBuffer.add(sub);
                    } else {
                        notifyBuffer.set(notifyBufferLength, sub);
                    }
                    notifyBufferLength++;
                }
            } else if ((subFlags & targetFlag) == 0
                    && (subFlags & PROPAGATED) != 0
                    && isValidLink(current, sub)) {
                sub.setFlags(subFlags | targetFlag);
            }

            if ((current = next) != null) {
                next = current.nextSub;
                targetFlag = branchDepth != 0
                        ? PENDING_COMPUTED
                        : DIRTY;
                continue;
            }

            while (branchDepth-- > 0) {
                current = branchs.getTarget();
                branchs = branchs.getLinked();
                if (current != null) {
                    next = current.nextSub;
                    targetFlag = branchDepth != 0
                            ? PENDING_COMPUTED
                            : DIRTY;
                    continue top;
                }
            }

            break;
        } while (true);
    }

    public void startTracking(Subscriber sub) {
        sub.setDepsTail(null);
        sub.setFlags((sub.getFlags() & ~(NOTIFIED | RECURSED | PROPAGATED)) | TRACKING);
    }

    public void endTracking(Subscriber sub) {
        Link depsTail = sub.getDepsTail();
        if (depsTail != null) {
            Link nextDep = depsTail.nextDep;
            if (nextDep != null) {
                clearTracking(nextDep);
                depsTail.nextDep = null;
            }
        } else if (sub.getDeps() != null) {
            clearTracking(sub.getDeps());
            sub.setDeps(null);
        }
        sub.setFlags(sub.getFlags() & ~TRACKING);
    }

    public boolean updateDirtyFlag(Subscriber sub, int flags) {
        if (checkDirty(sub.getDeps())) {
            sub.setFlags(flags | DIRTY);
            return true;
        } else {
            sub.setFlags(flags & ~PENDING_COMPUTED);
            return false;
        }
    }

    public void processComputedUpdate(Dependency computed, int flags) {
        if ((flags & DIRTY) != 0 || checkDirty(((Subscriber) computed).getDeps())) {
            if (updateComputed.updateComputed(computed)) {
                Link subs = computed.getSubs();
                if (subs != null) {
                    shallowPropagate(subs);
                }
            }
        } else {
            ((Subscriber) computed).setFlags(flags & ~PENDING_COMPUTED);
        }
    }

    public void processPendingInnerEffects(Subscriber sub, int flags) {
        if ((flags & PENDING_EFFECT) != 0) {
            sub.setFlags(flags & ~PENDING_EFFECT);
            Link link = sub.getDeps();
            do {
                Dependency dep = link.dep;
                if (dep instanceof Subscriber) {
                    Subscriber sDep = (Subscriber) dep;
                    if ((sDep.getFlags() & EFFECT) != 0
                            && (sDep.getFlags() & PROPAGATED) != 0) {
                        notifyEffect.notifyEffect(sDep);
                    }
                }
                link = link.nextDep;
            } while (link != null);
        }
    }

    public void processEffectNotifications() {
        while (notifyIndex < notifyBufferLength) {
            Subscriber effect = notifyBuffer.get(notifyIndex);
            notifyBuffer.set(notifyIndex++, null);
            if (!notifyEffect.notifyEffect(effect)) {
                effect.setFlags(effect.getFlags() & ~NOTIFIED);
            }
        }
        notifyIndex = 0;
        notifyBufferLength = 0;
    }

    private Link linkNewDep(Dependency dep, Subscriber sub, Link nextDep, Link depsTail) {
        Link newLink = new Link(dep, sub);
        newLink.nextDep = nextDep;

        if (depsTail == null) {
            sub.setDeps(newLink);
        } else {
            depsTail.nextDep = newLink;
        }

        if (dep.getSubs() == null) {
            dep.setSubs(newLink);
        } else {
            Link oldTail = dep.getSubsTail();
            newLink.prevSub = oldTail;
            oldTail.nextSub = newLink;
        }

        sub.setDepsTail(newLink);
        dep.setSubsTail(newLink);
        return newLink;
    }

    private boolean checkDirty(Link current) {
        OneWayLink<Link> prevLinks = null;
        int checkDepth = 0;
        boolean dirty;

        top: do {
            dirty = false;
            Dependency dep = current.dep;

            if ((current.sub.getFlags() & DIRTY) != 0) {
                dirty = true;
            } else if (dep instanceof Subscriber) {
                Subscriber sDep = (Subscriber) dep;
                int depFlags = sDep.getFlags();
                if ((depFlags & (COMPUTED | DIRTY)) == (COMPUTED | DIRTY)) {
                    if (updateComputed.updateComputed(dep)) {
                        Link subs = ((Dependency) sDep).getSubs();
                        if (subs.nextSub != null) {
                            shallowPropagate(subs);
                        }
                        dirty = true;
                    }
                } else if ((depFlags & (COMPUTED | PENDING_COMPUTED)) == (COMPUTED | PENDING_COMPUTED)) {
                    if (current.nextSub != null || current.prevSub != null) {
                        prevLinks = new SimpleOneWayLink<>(current, prevLinks);
                    }
                    current = sDep.getDeps();
                    ++checkDepth;
                    continue;
                }
            }

            if (!dirty && current.nextDep != null) {
                current = current.nextDep;
                continue;
            }

            while (checkDepth > 0) {
                --checkDepth;
                Subscriber sub = (Subscriber) current.sub;
                Link firstSub = ((Dependency) sub).getSubs();
                if (dirty) {
                    if (updateComputed.updateComputed((Dependency) sub)) {
                        if (firstSub.nextSub != null) {
                            current = prevLinks.getTarget();
                            prevLinks = prevLinks.getLinked();
                            shallowPropagate(firstSub);
                        } else {
                            current = firstSub;
                        }
                        continue;
                    }
                } else {
                    sub.setFlags(sub.getFlags() & ~PENDING_COMPUTED);
                }
                if (firstSub.nextSub != null) {
                    current = prevLinks.getTarget();
                    prevLinks = prevLinks.getLinked();
                } else {
                    current = firstSub;
                }
                if (current.nextDep != null) {
                    current = current.nextDep;
                    continue top;
                }
                dirty = false;
            }

            return dirty;
        } while (true);
    }

    private void shallowPropagate(Link link) {
        do {
            Subscriber sub = link.sub;
            int subFlags = sub.getFlags();
            if ((subFlags & (PENDING_COMPUTED | DIRTY)) == PENDING_COMPUTED) {
                sub.setFlags(subFlags | DIRTY | NOTIFIED);
                if ((subFlags & (EFFECT | NOTIFIED)) == EFFECT) {
                    if (notifyBufferLength == notifyBuffer.size()) {
                        notifyBuffer.add(sub);
                    } else {
                        notifyBuffer.set(notifyBufferLength, sub);
                    }
                    notifyBufferLength++;
                }
            }
            link = link.nextSub;
        } while (link != null);
    }

    private boolean isValidLink(Link checkLink, Subscriber sub) {
        Link depsTail = sub.getDepsTail();
        if (depsTail != null) {
            Link link = sub.getDeps();
            do {
                if (link == checkLink) {
                    return true;
                }
                if (link == depsTail) {
                    break;
                }
                link = link.nextDep;
            } while (link != null);
        }
        return false;
    }

    private void clearTracking(Link link) {
        do {
            Dependency dep = link.dep;
            Link nextDep = link.nextDep;
            Link nextSub = link.nextSub;
            Link prevSub = link.prevSub;

            if (nextSub != null) {
                nextSub.prevSub = prevSub;
            } else {
                dep.setSubsTail(prevSub);
            }

            if (prevSub != null) {
                prevSub.nextSub = nextSub;
            } else {
                dep.setSubs(nextSub);
            }

            if (dep.getSubs() == null && dep instanceof Subscriber) {
                Subscriber sDep = (Subscriber) dep;
                int depFlags = sDep.getFlags();
                if ((depFlags & DIRTY) == 0) {
                    sDep.setFlags(depFlags | DIRTY);
                }
                Link depDeps = sDep.getDeps();
                if (depDeps != null) {
                    link = depDeps;
                    sDep.getDepsTail().nextDep = nextDep;
                    sDep.setDeps(null);
                    sDep.setDepsTail(null);
                    continue;
                }
            }
            link = nextDep;
        } while (link != null);
    }
}
