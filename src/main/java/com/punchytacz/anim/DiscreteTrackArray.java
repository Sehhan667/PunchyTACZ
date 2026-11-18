package com.punchytacz.anim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/** 逐行移植 TACZ DiscreteTrackArray：轨道行 -> 轨道指针，迭代顺序即状态机更新顺序 */
public final class DiscreteTrackArray implements Iterable<Integer> {
    private int top = 0;
    private final ArrayList<LinkedList<Integer>> tracks = new ArrayList<>();
    private int modCount = 0;

    public void ensureCapacity(int size) {
        int cap = tracks.size();
        for (int i = cap; i < size; i++) {
            modCount++;
            tracks.add(null);
        }
    }

    public void ensureTrackAmount(int index, int amount) {
        if (index >= tracks.size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + tracks.size());
        }
        LinkedList<Integer> list = tracks.get(index);
        int oldSize = list == null ? 0 : list.size();
        for (int i = oldSize; i < amount; i++) {
            assignNewTrack(index);
        }
    }

    public int addTrackLine() {
        modCount++;
        tracks.add(null);
        return tracks.size() - 1;
    }

    public int assignNewTrack(int index) {
        if (top == Integer.MAX_VALUE) {
            throw new RuntimeException("Can't assign new track due to overflow");
        }
        modCount++;
        Optional.ofNullable(tracks.get(index)).ifPresentOrElse(
                list -> list.add(top++),
                () -> {
                    LinkedList<Integer> list = new LinkedList<>();
                    list.add(top++);
                    tracks.set(index, list);
                }
        );
        return top - 1;
    }

    public int getTrackLineSize() {
        return tracks.size();
    }

    public List<Integer> getByIndex(int index) {
        LinkedList<Integer> list = tracks.get(index);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    @Override
    public Iterator<Integer> iterator() {
        return new MyIterator(modCount);
    }

    private final class MyIterator implements Iterator<Integer> {
        private final int expectedModCount;
        private Iterator<Integer> iterator;
        private int nextIndex;

        private MyIterator(int expectedModCount) {
            this.expectedModCount = expectedModCount;
            int index = findNextNotEmptyList(-1);
            if (index != -1) {
                iterator = tracks.get(index).iterator();
                nextIndex = findNextNotEmptyList(index);
            }
        }

        @Override
        public boolean hasNext() {
            checkForModifications();
            if (iterator != null && iterator.hasNext()) {
                return true;
            }
            return nextIndex != -1;
        }

        @Override
        public Integer next() {
            checkForModifications();
            if (iterator != null && iterator.hasNext()) {
                return iterator.next();
            }
            if (nextIndex != -1) {
                iterator = tracks.get(nextIndex).iterator();
                nextIndex = findNextNotEmptyList(nextIndex);
                return iterator.next();
            }
            throw new IllegalStateException("No more elements");
        }

        private void checkForModifications() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException("Container modified during iteration");
            }
        }

        private int findNextNotEmptyList(int index) {
            int i = index + 1;
            while (i < tracks.size()) {
                LinkedList<Integer> list = tracks.get(i);
                if (list != null && !list.isEmpty()) {
                    return i;
                }
                i++;
            }
            return -1;
        }
    }
}
