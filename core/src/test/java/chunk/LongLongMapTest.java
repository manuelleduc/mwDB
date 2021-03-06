package chunk;

import org.junit.Assert;
import org.junit.Test;
import org.mwdb.Constants;
import org.mwdb.chunk.KChunk;
import org.mwdb.chunk.KChunkListener;
import org.mwdb.chunk.KLongLongMap;
import org.mwdb.chunk.KLongLongMapCallBack;
import org.mwdb.chunk.heap.ArrayLongLongMap;
import org.mwdb.chunk.offheap.OffHeapByteArray;
import org.mwdb.chunk.offheap.OffHeapDoubleArray;
import org.mwdb.chunk.offheap.OffHeapLongArray;
import org.mwdb.chunk.offheap.OffHeapStringArray;
import org.mwdb.utility.Unsafe;

public class LongLongMapTest implements KChunkListener {

    private int dirtyCount = 0;

    @Test
    public void arrayHeapTest() {
        test(new ArrayLongLongMap(this, Constants.MAP_INITIAL_CAPACITY, null));
    }

    @Test
    public void arrayOffHeapTest() {
        OffHeapByteArray.alloc_counter = 0;
        OffHeapDoubleArray.alloc_counter = 0;
        OffHeapLongArray.alloc_counter = 0;
        OffHeapStringArray.alloc_counter = 0;

        Unsafe.DEBUG_MODE = true;

        org.mwdb.chunk.offheap.ArrayLongLongMap map = new org.mwdb.chunk.offheap.ArrayLongLongMap(this, Constants.MAP_INITIAL_CAPACITY,-1);
        org.mwdb.chunk.offheap.ArrayLongLongMap.incrementCopyOnWriteCounter(map.rootAddress());
        test(map);
        org.mwdb.chunk.offheap.ArrayLongLongMap.free(map.rootAddress());

        Assert.assertTrue(OffHeapByteArray.alloc_counter == 0);
        Assert.assertTrue(OffHeapDoubleArray.alloc_counter == 0);
        Assert.assertTrue(OffHeapLongArray.alloc_counter == 0);
        Assert.assertTrue(OffHeapStringArray.alloc_counter == 0);
    }

    private void test(KLongLongMap map) {
        dirtyCount = 0;
        map.put(0, 0);
        Assert.assertTrue(0 == map.get(0));
        map.put(1, 1);
        Assert.assertTrue(0 == map.get(0));
        Assert.assertTrue(1 == map.get(1));
        //no effect
        map.put(1, 1);

        map.put(0, 1);
        map.put(1, 2);
        Assert.assertTrue(1 == map.get(0));
        Assert.assertTrue(2 == map.get(1));

        map.put(2, Constants.NULL_LONG);
        Assert.assertTrue(2 == map.get(2));

        Assert.assertTrue(dirtyCount == 5);

        long[] keys = new long[3];
        long[] values = new long[3];
        final int[] resIndex = {0};
        map.each(new KLongLongMapCallBack() {
            @Override
            public void on(long key, long value) {
                keys[resIndex[0]] = key;
                values[resIndex[0]] = value;
                resIndex[0]++;
            }
        });
        Assert.assertTrue(1 == values[0]);
        Assert.assertTrue(2 == values[1]);
        Assert.assertTrue(2 == values[2]);
        Assert.assertTrue(0 == keys[0]);
        Assert.assertTrue(1 == keys[1]);
        Assert.assertTrue(2 == keys[2]);

        //force the graph to do a rehash capacity
        for (int i = 0; i < Constants.MAP_INITIAL_CAPACITY; i++) {
            map.put(i, i);
        }
        //test that all values are consistent
        for (int i = 0; i < Constants.MAP_INITIAL_CAPACITY; i++) {
            Assert.assertTrue(map.get(i) == i);
        }
    }

    @Override
    public void declareDirty(KChunk chunk) {
        dirtyCount++;
    }
}
