package io.datadynamics.ranger.client.admin;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** One page of a search. {@link #hasMore()} is true when the page came back full. */
public final class Page<T> implements Iterable<T> {
    private final List<T> items;
    private final int startIndex;
    private final int pageSize;

    public Page(List<T> items, int startIndex, int pageSize) {
        this.items = items == null ? Collections.emptyList() : Collections.unmodifiableList(items);
        this.startIndex = startIndex;
        this.pageSize = pageSize;
    }

    public List<T> items() {
        return items;
    }

    public int startIndex() {
        return startIndex;
    }

    public int pageSize() {
        return pageSize;
    }

    public int size() {
        return items.size();
    }

    public boolean hasMore() {
        return items.size() >= pageSize;
    }

    /** Start index of the next page. */
    public int nextStartIndex() {
        return startIndex + items.size();
    }

    @Override
    public Iterator<T> iterator() {
        return items.iterator();
    }
}
