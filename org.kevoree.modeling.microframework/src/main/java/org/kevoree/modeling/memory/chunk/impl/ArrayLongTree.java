package org.kevoree.modeling.memory.chunk.impl;

import org.kevoree.modeling.KConfig;
import org.kevoree.modeling.memory.chunk.KLongTree;
import org.kevoree.modeling.memory.space.KChunkSpace;
import org.kevoree.modeling.memory.space.impl.HeapChunkSpace;

public class ArrayLongTree extends AbstractArrayTree implements KLongTree {

    public ArrayLongTree(HeapChunkSpace p_space) {
        super(p_space);
    }

    public long previousOrEqual(long key) {
        int result = internal_previousOrEqual_index(key);
        if (result != -1) {
            return key(result);
        } else {
            return KConfig.NULL_LONG;
        }
    }

    public void insert(long p_key) {
        internal_insert(p_key, p_key);
    }

    @Override
    public short type() {
        return 0;
    }

}
