package io.datadynamics.ranger.client.core;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Lazily walks Ranger's {@code startIndex}/{@code pageSize} pagination. */
public final class Paging {
    private Paging() {
    }

    /** @param fetch (startIndex, pageSize) -&gt; page */
    public static <T> Iterator<T> iterator(BiFunction<Integer, Integer, List<T>> fetch, int pageSize) {
        return new Iterator<T>() {
            private List<T> page;
            private int pos;
            private int start;
            private boolean last;

            @Override
            public boolean hasNext() {
                while (page == null || pos >= page.size()) {
                    if (last) {
                        return false;
                    }
                    page = fetch.apply(start, pageSize);
                    pos = 0;
                    start += page.size();
                    last = page.size() < pageSize;
                    if (page.isEmpty()) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return page.get(pos++);
            }
        };
    }

    public static <T> Stream<T> stream(BiFunction<Integer, Integer, List<T>> fetch, int pageSize) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(fetch, pageSize),
                Spliterator.ORDERED | Spliterator.NONNULL), false);
    }
}
