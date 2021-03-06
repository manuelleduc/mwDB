package org.mwdb.manager;

import org.mwdb.*;
import org.mwdb.plugin.KFactory;
import org.mwdb.plugin.KResolver;
import org.mwdb.plugin.KScheduler;
import org.mwdb.plugin.KStorage;
import org.mwdb.chunk.*;
import org.mwdb.utility.Buffer;

public class MWGResolver implements KResolver {

    private final KStorage _storage;

    private final KChunkSpace _space;

    private final KNodeTracker _tracker;

    private final KScheduler _scheduler;

    private static final String deadNodeError = "This Node has been tagged destroyed, please don't use it anymore!";

    private KGraph _graph;

    public MWGResolver(KStorage p_storage, KChunkSpace p_space, KNodeTracker p_tracker, KScheduler p_scheduler) {
        this._storage = p_storage;
        this._space = p_space;
        this._tracker = p_tracker;
        this._scheduler = p_scheduler;
    }

    private KStateChunk dictionary;

    @Override
    public void init(KGraph graph) {
        _graph = graph;
        dictionary = (KStateChunk) this._space.getAndMark(Constants.STATE_CHUNK, Constants.GLOBAL_DICTIONARY_KEY[0], Constants.GLOBAL_DICTIONARY_KEY[1], Constants.GLOBAL_DICTIONARY_KEY[2]);
    }

    @Override
    public void initNode(KNode node, long codeType) {
        KStateChunk cacheEntry = (KStateChunk) this._space.create(Constants.STATE_CHUNK, node.world(), node.time(), node.id(), null, null);
        //put and mark
        this._space.putAndMark(cacheEntry);
        //declare dirty now because potentially no insert could be done
        this._space.declareDirty(cacheEntry);

        //initiate superTime management
        KTimeTreeChunk superTimeTree = (KTimeTreeChunk) this._space.create(Constants.TIME_TREE_CHUNK, node.world(), Constants.NULL_LONG, node.id(), null, null);
        superTimeTree = (KTimeTreeChunk) this._space.putAndMark(superTimeTree);
        superTimeTree.insert(node.time());

        //initiate time management
        KTimeTreeChunk timeTree = (KTimeTreeChunk) this._space.create(Constants.TIME_TREE_CHUNK, node.world(), node.time(), node.id(), null, null);
        timeTree = (KTimeTreeChunk) this._space.putAndMark(timeTree);
        timeTree.insert(node.time());

        //initiate universe management
        KWorldOrderChunk objectWorldOrder = (KWorldOrderChunk) this._space.create(Constants.WORLD_ORDER_CHUNK, Constants.NULL_LONG, Constants.NULL_LONG, node.id(), null, null);
        objectWorldOrder = (KWorldOrderChunk) this._space.putAndMark(objectWorldOrder);
        objectWorldOrder.put(node.world(), node.time());
        objectWorldOrder.setExtra(codeType);
        //mark the global

        this._space.getAndMark(Constants.WORLD_ORDER_CHUNK, Constants.NULL_LONG, Constants.NULL_LONG, Constants.NULL_LONG);
        //monitor the node object
        this._tracker.monitor(node);
    }

    @Override
    public void initWorld(long parentWorld, long childWorld) {
        KWorldOrderChunk worldOrder = (KWorldOrderChunk) this._space.getAndMark(Constants.WORLD_ORDER_CHUNK, Constants.NULL_LONG, Constants.NULL_LONG, Constants.NULL_LONG);
        worldOrder.put(childWorld, parentWorld);
        this._space.unmarkChunk(worldOrder);
    }

    @Override
    public void freeNode(KNode node) {
        AbstractNode casted = (AbstractNode) node;
        long nodeId = node.id();
        long[] previous;
        do {
            previous = casted._previousResolveds.get();
        } while (!casted._previousResolveds.compareAndSet(previous, null));
        if (previous != null) {
            this._space.unmark(Constants.STATE_CHUNK, previous[Constants.PREVIOUS_RESOLVED_WORLD_INDEX], previous[Constants.PREVIOUS_RESOLVED_TIME_INDEX], nodeId);//FREE OBJECT CHUNK
            this._space.unmark(Constants.TIME_TREE_CHUNK, previous[Constants.PREVIOUS_RESOLVED_WORLD_INDEX], Constants.NULL_LONG, nodeId);//FREE TIME TREE
            this._space.unmark(Constants.TIME_TREE_CHUNK, previous[Constants.PREVIOUS_RESOLVED_WORLD_INDEX], previous[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX], nodeId);//FREE TIME TREE
            this._space.unmark(Constants.WORLD_ORDER_CHUNK, Constants.NULL_LONG, Constants.NULL_LONG, nodeId); //FREE OBJECT UNIVERSE MAP
            this._space.unmark(Constants.WORLD_ORDER_CHUNK, Constants.NULL_LONG, Constants.NULL_LONG, Constants.NULL_LONG); //FREE GLOBAL UNIVERSE MAP
        }
    }

    @Override
    public <A extends KNode> void lookup(long world, long time, long id, KCallback<A> callback) {
        this._scheduler.dispatch(lookupTask(world, time, id, callback));
    }

    @Override
    public <A extends KNode> KCallback lookupTask(final long world, final long time, final long id, final KCallback<A> callback) {
        final MWGResolver selfPointer = this;
        return new KCallback() {
            @Override
            public void on(Object o) {
                try {
                    selfPointer.getOrLoadAndMark(Constants.WORLD_ORDER_CHUNK, Constants.NULL_LONG, Constants.NULL_LONG, Constants.NULL_LONG, new KCallback<KChunk>() {
                        @Override
                        public void on(KChunk theGlobalWorldOrder) {
                            if (theGlobalWorldOrder != null) {
                                selfPointer.getOrLoadAndMark(Constants.WORLD_ORDER_CHUNK, Constants.NULL_LONG, Constants.NULL_LONG, id, new KCallback<KChunk>() {
                                    @Override
                                    public void on(KChunk theNodeWorldOrder) {
                                        if (theNodeWorldOrder == null) {
                                            selfPointer._space.unmarkChunk(theGlobalWorldOrder);
                                            callback.on(null);
                                        } else {
                                            final long closestWorld = resolve_world((KLongLongMap) theGlobalWorldOrder, (KLongLongMap) theNodeWorldOrder, time, world);
                                            selfPointer.getOrLoadAndMark(Constants.TIME_TREE_CHUNK, closestWorld, Constants.NULL_LONG, id, new KCallback<KChunk>() {
                                                @Override
                                                public void on(KChunk theNodeSuperTimeTree) {
                                                    if (theNodeSuperTimeTree == null) {
                                                        selfPointer._space.unmarkChunk(theNodeWorldOrder);
                                                        selfPointer._space.unmarkChunk(theGlobalWorldOrder);
                                                        callback.on(null);
                                                    } else {
                                                        final long closestSuperTime = ((KLongTree) theNodeSuperTimeTree).previousOrEqual(time);
                                                        if (closestSuperTime == Constants.NULL_LONG) {
                                                            selfPointer._space.unmarkChunk(theNodeSuperTimeTree);
                                                            selfPointer._space.unmarkChunk(theNodeWorldOrder);
                                                            selfPointer._space.unmarkChunk(theGlobalWorldOrder);
                                                            callback.on(null);
                                                            return;
                                                        }
                                                        selfPointer.getOrLoadAndMark(Constants.TIME_TREE_CHUNK, closestWorld, closestSuperTime, id, new KCallback<KChunk>() {
                                                            @Override
                                                            public void on(KChunk theNodeTimeTree) {
                                                                if (theNodeTimeTree == null) {
                                                                    selfPointer._space.unmarkChunk(theNodeSuperTimeTree);
                                                                    selfPointer._space.unmarkChunk(theNodeWorldOrder);
                                                                    selfPointer._space.unmarkChunk(theGlobalWorldOrder);
                                                                    callback.on(null);
                                                                } else {
                                                                    long closestTime = ((KLongTree) theNodeTimeTree).previousOrEqual(time);
                                                                    if (closestTime == Constants.NULL_LONG) {
                                                                        selfPointer._space.unmarkChunk(theNodeTimeTree);
                                                                        selfPointer._space.unmarkChunk(theNodeSuperTimeTree);
                                                                        selfPointer._space.unmarkChunk(theNodeWorldOrder);
                                                                        selfPointer._space.unmarkChunk(theGlobalWorldOrder);
                                                                        callback.on(null);
                                                                        return;
                                                                    }
                                                                    selfPointer.getOrLoadAndMark(Constants.STATE_CHUNK, closestWorld, closestTime, id, new KCallback<KChunk>() {
                                                                        @Override
                                                                        public void on(KChunk theObjectChunk) {
                                                                            if (theObjectChunk == null) {
                                                                                selfPointer._space.unmarkChunk(theNodeTimeTree);
                                                                                selfPointer._space.unmarkChunk(theNodeSuperTimeTree);
                                                                                selfPointer._space.unmarkChunk(theNodeWorldOrder);
                                                                                selfPointer._space.unmarkChunk(theGlobalWorldOrder);
                                                                                callback.on(null);
                                                                            } else {
                                                                                KWorldOrderChunk castedNodeWorldOrder = (KWorldOrderChunk) theNodeWorldOrder;
                                                                                long extraCode = castedNodeWorldOrder.extra();
                                                                                KFactory resolvedFactory = null;
                                                                                if (extraCode != Constants.NULL_LONG) {
                                                                                    resolvedFactory = ((Graph) _graph).factoryByCode(extraCode);
                                                                                }

                                                                                long[] initPreviouslyResolved = new long[6];
                                                                                //init previously resolved values
                                                                                initPreviouslyResolved[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] = closestWorld;
                                                                                initPreviouslyResolved[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = closestSuperTime;
                                                                                initPreviouslyResolved[Constants.PREVIOUS_RESOLVED_TIME_INDEX] = closestTime;
                                                                                //init previous magics
                                                                                initPreviouslyResolved[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] = ((KWorldOrderChunk) theNodeWorldOrder).magic();
                                                                                initPreviouslyResolved[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = ((KLongTree) theNodeSuperTimeTree).magic();
                                                                                initPreviouslyResolved[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] = ((KLongTree) theNodeTimeTree).magic();

                                                                                KNode resolvedNode;
                                                                                if (resolvedFactory == null) {
                                                                                    resolvedNode = new Node(world, time, id, _graph, initPreviouslyResolved);
                                                                                } else {
                                                                                    resolvedNode = resolvedFactory.create(world, time, id, _graph, initPreviouslyResolved);
                                                                                }
                                                                                selfPointer._tracker.monitor(resolvedNode);
                                                                                callback.on((A) resolvedNode);
                                                                            }
                                                                        }
                                                                    });
                                                                }
                                                            }
                                                        });
                                                    }
                                                }
                                            });
                                        }
                                    }
                                });
                            } else {
                                callback.on(null);
                            }
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
    }

    private long resolve_world(final KLongLongMap globalWorldOrder, final KLongLongMap nodeWorldOrder, final long timeToResolve, long originWorld) {
        if (globalWorldOrder == null || nodeWorldOrder == null) {
            return originWorld;
        }
        long currentUniverse = originWorld;
        long previousUniverse = Constants.NULL_LONG;
        long divergenceTime = nodeWorldOrder.get(currentUniverse);
        while (currentUniverse != previousUniverse) {
            //check range
            if (divergenceTime != Constants.NULL_LONG && divergenceTime <= timeToResolve) {
                return currentUniverse;
            }
            //next round
            previousUniverse = currentUniverse;
            currentUniverse = globalWorldOrder.get(currentUniverse);
            divergenceTime = nodeWorldOrder.get(currentUniverse);
        }
        return originWorld;
    }

    private void getOrLoadAndMark(final byte type, final long world, final long time, final long id, final KCallback<KChunk> callback) {
        if (world == Constants.NULL_KEY[0] && time == Constants.NULL_KEY[1] && id == Constants.NULL_KEY[2]) {
            callback.on(null);
            return;
        }
        KChunk cached = this._space.getAndMark(type, world, time, id);
        if (cached != null) {
            callback.on(cached);
        } else {
            KBuffer buffer = _graph.newBuffer();
            Buffer.keyToBuffer(buffer, type, world, time, id);
            load(new byte[]{type}, new long[]{world, time, id}, new KBuffer[]{buffer}, new KCallback<KChunk[]>() {
                @Override
                public void on(KChunk[] loadedElements) {
                    callback.on(loadedElements[0]);
                }
            });
        }
    }

    private static int KEY_SIZE = 3;

    private void getOrLoadAndMarkAll(byte[] types, long[] keys, final KCallback<KChunk[]> callback) {
        int nbKeys = keys.length / KEY_SIZE;
        final boolean[] toLoadIndexes = new boolean[nbKeys];
        int nbElem = 0;
        final KChunk[] result = new KChunk[nbKeys];
        for (int i = 0; i < nbKeys; i++) {
            if (keys[i * KEY_SIZE] == Constants.NULL_KEY[0] && keys[i * KEY_SIZE + 1] == Constants.NULL_KEY[1] && keys[i * KEY_SIZE + 2] == Constants.NULL_KEY[2]) {
                toLoadIndexes[i] = false;
                result[i] = null;
            } else {
                result[i] = this._space.getAndMark(types[i], keys[i * KEY_SIZE], keys[i * KEY_SIZE + 1], keys[i * KEY_SIZE + 2]);
                if (result[i] == null) {
                    toLoadIndexes[i] = true;
                    nbElem++;
                } else {
                    toLoadIndexes[i] = false;
                }
            }
        }
        if (nbElem == 0) {
            callback.on(result);
        } else {
            final long[] keysToLoadFlat = new long[nbElem * KEY_SIZE];
            final KBuffer[] keysToLoad = new KBuffer[nbElem];
            final byte[] typesToLoad = new byte[nbElem];
            int lastInsertedIndex = 0;
            for (int i = 0; i < nbKeys; i++) {
                if (toLoadIndexes[i]) {
                    keysToLoadFlat[lastInsertedIndex] = keys[i * KEY_SIZE];
                    keysToLoadFlat[lastInsertedIndex + 1] = keys[i * KEY_SIZE + 1];
                    keysToLoadFlat[lastInsertedIndex + 2] = keys[i * KEY_SIZE + 2];
                    typesToLoad[lastInsertedIndex] = types[i];
                    keysToLoad[lastInsertedIndex] = _graph.newBuffer();
                    Buffer.keyToBuffer(keysToLoad[lastInsertedIndex], types[i], keys[i * KEY_SIZE], keys[i * KEY_SIZE + 1], keys[i * KEY_SIZE + 2]);
                    lastInsertedIndex = lastInsertedIndex + 3;
                }
            }
            load(typesToLoad, keysToLoadFlat, keysToLoad, new KCallback<KChunk[]>() {
                @Override
                public void on(KChunk[] loadedElements) {
                    int currentIndexToMerge = 0;
                    for (int i = 0; i < nbKeys; i++) {
                        if (toLoadIndexes[i]) {
                            result[i] = loadedElements[currentIndexToMerge];
                            currentIndexToMerge++;
                        }
                    }
                    callback.on(result);
                }
            });
        }
    }

    private void load(byte[] types, long[] flatKeys, KBuffer[] keys, KCallback<KChunk[]> callback) {
        MWGResolver selfPointer = this;
        this._storage.get(keys, new KCallback<KBuffer[]>() {
            @Override
            public void on(KBuffer[] payloads) {
                KChunk[] results = new KChunk[keys.length];
                for (int i = 0; i < payloads.length; i++) {
                    keys[i].free(); //free the temp KBuffer
                    long loopWorld = flatKeys[i * KEY_SIZE];
                    long loopTime = flatKeys[i * KEY_SIZE + 1];
                    long loopUuid = flatKeys[i * KEY_SIZE + 2];
                    byte elemType = types[i];
                    if (payloads[i] != null) {
                        results[i] = selfPointer._space.create(elemType, loopWorld, loopTime, loopUuid, payloads[i], null);
                        selfPointer._space.putAndMark(results[i]);
                    }
                }
                callback.on(results);
            }
        });
    }

    @Override
    public KNodeState newState(KNode node, long world, long time) {

        //Retrieve Node needed chunks
        KWorldOrderChunk nodeWorldOrder = (KWorldOrderChunk) this._space.getAndMark(Constants.WORLD_ORDER_CHUNK, Constants.NULL_LONG, Constants.NULL_LONG, node.id());
        if (nodeWorldOrder == null) {
            return null;
        }
        //SOMETHING WILL MOVE HERE ANYWAY SO WE SYNC THE OBJECT, even for dePhasing read only objects because they can be unaligned after
        nodeWorldOrder.lock();
        //OK NOW WE HAVE THE TOKEN globally FOR the node ID

        AbstractNode castedNode = (AbstractNode) node;
        //protection against deleted KNode
        long[] previousResolveds = castedNode._previousResolveds.get();
        if (previousResolveds == null) {
            throw new RuntimeException(deadNodeError);
        }
        //let's go for the resolution now
        long nodeId = node.id();

        KChunk resultState = null;
        try {
            KTimeTreeChunk nodeSuperTimeTree = (KTimeTreeChunk) this._space.getAndMark(Constants.TIME_TREE_CHUNK, previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX], Constants.NULL_LONG, nodeId);
            if (nodeSuperTimeTree == null) {
                this._space.unmarkChunk(nodeWorldOrder);
                return null;
            }
            KTimeTreeChunk nodeTimeTree = (KTimeTreeChunk) this._space.getAndMark(Constants.TIME_TREE_CHUNK, previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX], previousResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX], nodeId);
            if (nodeTimeTree == null) {
                this._space.unmarkChunk(nodeSuperTimeTree);
                this._space.unmarkChunk(nodeWorldOrder);
                return null;
            }

            //first we create and insert the empty state
            long resolvedSuperTime = previousResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX];
            resultState = this._space.create(Constants.STATE_CHUNK, world, time, nodeId, null, null);
            resultState = _space.putAndMark(resultState);

            if (previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] == world) {

                //manage super tree here
                long superTreeSize = nodeSuperTimeTree.size();
                long threshold = Constants.SCALE_1 * 2;
                if (superTreeSize > threshold) {
                    threshold = Constants.SCALE_2 * 2;
                }
                if (superTreeSize > threshold) {
                    threshold = Constants.SCALE_3 * 2;
                }
                if (superTreeSize > threshold) {
                    threshold = Constants.SCALE_4 * 2;
                }
                nodeTimeTree.insert(time);
                if (nodeTimeTree.size() == threshold) {
                    final long[] medianPoint = {-1};
                    //we iterate over the tree without boundaries for values, but with boundaries for number of collected times
                    nodeTimeTree.range(Constants.BEGINNING_OF_TIME, Constants.END_OF_TIME, nodeTimeTree.size() / 2, new KTreeWalker() {
                        @Override
                        public void elem(long t) {
                            medianPoint[0] = t;
                        }
                    });

                    KTimeTreeChunk rightTree = (KTimeTreeChunk) this._space.create(Constants.TIME_TREE_CHUNK, world, medianPoint[0], nodeId, null, null);
                    rightTree = (KTimeTreeChunk) this._space.putAndMark(rightTree);
                    //TODO second iterate that can be avoided, however we need the median point to create the right tree
                    //we iterate over the tree without boundaries for values, but with boundaries for number of collected times
                    final KTimeTreeChunk finalRightTree = rightTree;
                    //rang iterate from the end of the tree
                    nodeTimeTree.range(Constants.BEGINNING_OF_TIME, Constants.END_OF_TIME, nodeTimeTree.size() / 2, new KTreeWalker() {
                        @Override
                        public void elem(long t) {
                            finalRightTree.insert(t);
                        }
                    });
                    nodeSuperTimeTree.insert(medianPoint[0]);
                    //remove times insert in the right tree
                    nodeTimeTree.clearAt(medianPoint[0]);

                    //ok ,now manage marks
                    if (time < medianPoint[0]) {

                        _space.unmarkChunk(rightTree);

                        long[] newResolveds = new long[6];
                        newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] = world;
                        newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = resolvedSuperTime;
                        newResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] = time;
                        newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrder.magic();
                        newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = nodeSuperTimeTree.magic();
                        newResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] = nodeTimeTree.magic();
                        castedNode._previousResolveds.set(newResolveds);
                    } else {

                        //we unMark
                        _space.unmarkChunk(nodeTimeTree);

                        //let's store the new state if necessary
                        long[] newResolveds = new long[6];
                        newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] = world;
                        newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = medianPoint[0];
                        newResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] = time;
                        newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrder.magic();
                        newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = rightTree.magic();
                        newResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] = nodeTimeTree.magic();
                        castedNode._previousResolveds.set(newResolveds);
                    }
                } else {
                    //update the state cache without superTree modification
                    long[] newResolveds = new long[6];
                    //previously resolved
                    newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] = world;
                    newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = resolvedSuperTime;
                    newResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] = time;
                    //previously magics
                    newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrder.magic();
                    newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = nodeSuperTimeTree.magic();
                    newResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] = nodeTimeTree.magic();
                    castedNode._previousResolveds.set(newResolveds);

                }
            } else {

                //TODO potential memory leak here
                //create a new node superTimeTree
                KTimeTreeChunk newSuperTimeTree = (KTimeTreeChunk) this._space.create(Constants.TIME_TREE_CHUNK, world, Constants.NULL_LONG, nodeId, null, null);
                newSuperTimeTree = (KTimeTreeChunk) this._space.putAndMark(newSuperTimeTree);
                newSuperTimeTree.insert(time);
                //create a new node timeTree
                //TODO potential memory leak here
                KTimeTreeChunk newTimeTree = (KTimeTreeChunk) this._space.create(Constants.TIME_TREE_CHUNK, world, time, nodeId, null, null);
                newTimeTree = (KTimeTreeChunk) this._space.putAndMark(newTimeTree);
                newTimeTree.insert(time);
                //insert into node world order
                nodeWorldOrder.put(world, time);

                //let's store the new state if necessary
                long[] newResolveds = new long[6];
                //previously resolved
                newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] = world;
                newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = time;
                newResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] = time;
                //previously magics
                newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrder.magic();
                newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = newSuperTimeTree.magic();
                newResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] = newTimeTree.magic();
                castedNode._previousResolveds.set(newResolveds);

                //we unMark
                _space.unmarkChunk(nodeSuperTimeTree);
                _space.unmarkChunk(nodeTimeTree);
            }

            //unMark previous state, for the newly created one
            _space.unmark(Constants.STATE_CHUNK, previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX], previousResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX], nodeId);
            _space.unmarkChunk(nodeSuperTimeTree);
            _space.unmarkChunk(nodeTimeTree);


        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            nodeWorldOrder.unlock();
        }

        //we should unMark previous state

        //unMark World order chunks
        _space.unmarkChunk(nodeWorldOrder);
        return (KNodeState) resultState;
    }

    @Override
    public KNodeState resolveState(KNode node, boolean allowDephasing) {
        AbstractNode castedNode = (AbstractNode) node;
        //protection against deleted KNode
        long[] previousResolveds = castedNode._previousResolveds.get();
        if (previousResolveds == null) {
            throw new RuntimeException(deadNodeError);
        }
        //let's go for the resolution now
        long nodeWorld = node.world();
        long nodeTime = node.time();
        long nodeId = node.id();

        //OPTIMIZATION #1: NO DEPHASING
        if (previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] == nodeWorld && previousResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] == nodeTime) {
            KStateChunk currentEntry = (KStateChunk) this._space.getAndMark(Constants.STATE_CHUNK, nodeWorld, nodeTime, nodeId);
            if (currentEntry != null) {
                this._space.unmarkChunk(currentEntry);
                return currentEntry;
            }
        }

        //Retrieve Node needed chunks
        KWorldOrderChunk nodeWorldOrder = (KWorldOrderChunk) this._space.getAndMark(Constants.WORLD_ORDER_CHUNK, Constants.NULL_LONG, Constants.NULL_LONG, nodeId);
        if (nodeWorldOrder == null) {
            return null;
        }
        KTimeTreeChunk nodeSuperTimeTree = (KTimeTreeChunk) this._space.getAndMark(Constants.TIME_TREE_CHUNK, previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX], Constants.NULL_LONG, nodeId);
        if (nodeSuperTimeTree == null) {
            this._space.unmarkChunk(nodeWorldOrder);
            return null;
        }
        KTimeTreeChunk nodeTimeTree = (KTimeTreeChunk) this._space.getAndMark(Constants.TIME_TREE_CHUNK, previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX], previousResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX], nodeId);
        if (nodeTimeTree == null) {
            this._space.unmarkChunk(nodeSuperTimeTree);
            this._space.unmarkChunk(nodeWorldOrder);
            return null;
        }

        long nodeWorldOrderMagic = nodeWorldOrder.magic();
        long nodeSuperTimeTreeMagic = nodeSuperTimeTree.magic();
        long nodeTimeTreeMagic = nodeTimeTree.magic();

        //OPTIMIZATION #2: SAME DEPHASING
        if (allowDephasing && (previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] == nodeWorldOrderMagic) && (previousResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] == nodeSuperTimeTreeMagic) && (previousResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] == nodeTimeTreeMagic)) {
            KStateChunk currentNodeState = (KStateChunk) this._space.getAndMark(Constants.STATE_CHUNK, previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX], previousResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX], nodeId);
            this._space.unmarkChunk(nodeWorldOrder);
            this._space.unmarkChunk(nodeSuperTimeTree);
            this._space.unmarkChunk(nodeTimeTree);
            if (currentNodeState != null) {
                //ERROR case protection, chunk has been removed from cache
                this._space.unmarkChunk(currentNodeState);
            }
            return currentNodeState;
        }

        //NOMINAL CASE, MAGIC NUMBER ARE NOT VALID ANYMORE
        KWorldOrderChunk globalWorldOrder = (KWorldOrderChunk) this._space.getAndMark(Constants.WORLD_ORDER_CHUNK, Constants.NULL_LONG, Constants.NULL_LONG, Constants.NULL_LONG);
        if (globalWorldOrder == null) {
            this._space.unmarkChunk(nodeWorldOrder);
            this._space.unmarkChunk(nodeSuperTimeTree);
            this._space.unmarkChunk(nodeTimeTree);
            return null;
        }

        //SOMETHING WILL MOVE HERE ANYWAY SO WE SYNC THE OBJECT, even for dePhasing read only objects because they can be unaligned after
        nodeWorldOrder.lock();
        //OK NOW WE HAVE THE TOKEN globally FOR the node ID

        //OPTIMIZATION #1: NO DEPHASING
        if (previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] == nodeWorld && previousResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] == nodeTime) {
            KStateChunk currentEntry = (KStateChunk) this._space.getAndMark(Constants.STATE_CHUNK, nodeWorld, nodeTime, nodeId);
            if (currentEntry != null) {
                this._space.unmarkChunk(globalWorldOrder);
                this._space.unmarkChunk(nodeWorldOrder);
                this._space.unmarkChunk(nodeSuperTimeTree);
                this._space.unmarkChunk(nodeTimeTree);
                this._space.unmarkChunk(currentEntry);
                return currentEntry;
            }
        }

        //REFRESH
        previousResolveds = castedNode._previousResolveds.get();
        if (previousResolveds == null) {
            throw new RuntimeException(deadNodeError);
        }

        nodeWorldOrderMagic = nodeWorldOrder.magic();
        nodeSuperTimeTreeMagic = nodeSuperTimeTree.magic();
        nodeTimeTreeMagic = nodeTimeTree.magic();

        KStateChunk resultStateChunk = null;
        boolean hasToCleanSuperTimeTree = false;
        boolean hasToCleanTimeTree = false;

        try {
            long resolvedWorld;
            long resolvedSuperTime;
            long resolvedTime;
            // OPTIMIZATION #3: SAME DEPHASING THAN BEFORE, DIRECTLY CLONE THE PREVIOUSLY RESOLVED TUPLE
            if (previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] == nodeWorldOrderMagic && previousResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] == nodeSuperTimeTreeMagic && previousResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] == nodeTimeTreeMagic) {
                resolvedWorld = previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX];
                resolvedSuperTime = previousResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX];
                resolvedTime = previousResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX];
                hasToCleanSuperTimeTree = true;
                hasToCleanTimeTree = true;
            } else {
                //Common case, we have to traverse World Order and Time chunks
                resolvedWorld = resolve_world(globalWorldOrder, nodeWorldOrder, nodeTime, nodeWorld);
                if (resolvedWorld != previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX]) {
                    //we have to update the superTree
                    KTimeTreeChunk tempNodeSuperTimeTree = (KTimeTreeChunk) this._space.getAndMark(Constants.TIME_TREE_CHUNK, resolvedWorld, Constants.NULL_LONG, nodeId);
                    if (tempNodeSuperTimeTree == null) {
                        throw new RuntimeException("Simultaneous rePhasing leading to cache miss!!!");
                    }
                    //free the method mark
                    _space.unmarkChunk(nodeSuperTimeTree);
                    //free the previous lookup mark
                    _space.unmarkChunk(nodeSuperTimeTree);
                    nodeSuperTimeTree = tempNodeSuperTimeTree;
                }
                resolvedSuperTime = nodeSuperTimeTree.previousOrEqual(nodeTime);
                if (previousResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] != resolvedSuperTime) {
                    //we have to update the timeTree
                    KTimeTreeChunk tempNodeTimeTree = (KTimeTreeChunk) this._space.getAndMark(Constants.TIME_TREE_CHUNK, resolvedWorld, resolvedSuperTime, nodeId);
                    if (tempNodeTimeTree == null) {
                        throw new RuntimeException("Simultaneous rephasing leading to cache miss!!!");
                    }
                    //free the method mark
                    _space.unmarkChunk(nodeTimeTree);
                    //free the lookup mark
                    _space.unmarkChunk(nodeTimeTree);
                    nodeTimeTree = tempNodeTimeTree;
                }
                resolvedTime = nodeTimeTree.previousOrEqual(nodeTime);
                //we only unMark superTimeTree in case of world phasing, otherwise we keep the mark (as new lookup mark)
                if (resolvedWorld == previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX]) {
                    hasToCleanSuperTimeTree = true;
                }
                //we only unMark timeTree in case of superTime phasing, otherwise we keep the mark (as new lookup mark)
                if (resolvedSuperTime == previousResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX]) {
                    hasToCleanTimeTree = true;
                }
            }
            boolean worldMoved = resolvedWorld != previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX];
            boolean superTimeTreeMoved = resolvedSuperTime != previousResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX];
            boolean timeTreeMoved = resolvedTime != previousResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX];

            //so we are dePhase
            if (allowDephasing) {
                resultStateChunk = (KStateChunk) this._space.getAndMark(Constants.STATE_CHUNK, resolvedWorld, resolvedTime, nodeId);
                if (resultStateChunk == null) {
                    throw new RuntimeException("Simultaneous rePhasing leading to cache miss!!!");
                }
                boolean refreshNodeCache = false;
                if (worldMoved || timeTreeMoved) {
                    _space.unmark(Constants.STATE_CHUNK, previousResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX], previousResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX], nodeId);
                    refreshNodeCache = true;
                } else {
                    if (superTimeTreeMoved) {
                        refreshNodeCache = true;
                    }
                    _space.unmarkChunk(resultStateChunk);
                }
                if (refreshNodeCache) {
                    long[] newResolveds = new long[6];
                    newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] = resolvedWorld;
                    newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = resolvedSuperTime;
                    newResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] = resolvedTime;
                    newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrderMagic;
                    newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = nodeSuperTimeTreeMagic;
                    newResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] = nodeTimeTreeMagic;
                    castedNode._previousResolveds.set(newResolveds);
                }
            } else {
                KStateChunk previousNodeState = (KStateChunk) this._space.getAndMark(Constants.STATE_CHUNK, resolvedWorld, resolvedTime, nodeId);
                //clone the chunk
                resultStateChunk = (KStateChunk) this._space.create(Constants.STATE_CHUNK, nodeWorld, nodeTime, nodeId, null, previousNodeState);
                this._space.putAndMark(resultStateChunk);
                this._space.declareDirty(resultStateChunk);
                //free the method mark
                this._space.unmarkChunk(previousNodeState);
                //free the previous lookup lock
                this._space.unmarkChunk(previousNodeState);

                if (resolvedWorld == nodeWorld) {
                    //manage super tree here
                    long superTreeSize = nodeSuperTimeTree.size();
                    long threshold = Constants.SCALE_1 * 2;
                    if (superTreeSize > threshold) {
                        threshold = Constants.SCALE_2 * 2;
                    }
                    if (superTreeSize > threshold) {
                        threshold = Constants.SCALE_3 * 2;
                    }
                    if (superTreeSize > threshold) {
                        threshold = Constants.SCALE_4 * 2;
                    }
                    nodeTimeTree.insert(nodeTime);
                    if (nodeTimeTree.size() == threshold) {
                        final long[] medianPoint = {-1};
                        //we iterate over the tree without boundaries for values, but with boundaries for number of collected times
                        nodeTimeTree.range(Constants.BEGINNING_OF_TIME, Constants.END_OF_TIME, nodeTimeTree.size() / 2, new KTreeWalker() {
                            @Override
                            public void elem(long t) {
                                medianPoint[0] = t;
                            }
                        });

                        KTimeTreeChunk rightTree = (KTimeTreeChunk) this._space.create(Constants.TIME_TREE_CHUNK, nodeWorld, medianPoint[0], nodeId, null, null);
                        rightTree = (KTimeTreeChunk) this._space.putAndMark(rightTree);
                        //TODO second iterate that can be avoided, however we need the median point to create the right tree
                        //we iterate over the tree without boundaries for values, but with boundaries for number of collected times
                        final KTimeTreeChunk finalRightTree = rightTree;
                        //rang iterate from the end of the tree
                        nodeTimeTree.range(Constants.BEGINNING_OF_TIME, Constants.END_OF_TIME, nodeTimeTree.size() / 2, new KTreeWalker() {
                            @Override
                            public void elem(long t) {
                                finalRightTree.insert(t);
                            }
                        });
                        nodeSuperTimeTree.insert(medianPoint[0]);
                        //remove times insert in the right tree
                        nodeTimeTree.clearAt(medianPoint[0]);

                        //ok ,now manage marks
                        if (nodeTime < medianPoint[0]) {
                            _space.unmarkChunk(rightTree);
                            long[] newResolveds = new long[6];
                            newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] = nodeWorld;
                            newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = resolvedSuperTime;
                            newResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] = nodeTime;
                            newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrderMagic;
                            newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = nodeSuperTimeTree.magic();
                            newResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] = nodeTimeTree.magic();
                            castedNode._previousResolveds.set(newResolveds);
                        } else {
                            //TODO check potentially marking bug (bad mark retention here...)
                            hasToCleanTimeTree = true;

                            //let's store the new state if necessary
                            long[] newResolveds = new long[6];
                            newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] = nodeWorld;
                            newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = medianPoint[0];
                            newResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] = nodeTime;
                            newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrderMagic;
                            newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = rightTree.magic();
                            newResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] = nodeTimeTree.magic();
                            castedNode._previousResolveds.set(newResolveds);
                        }
                    } else {
                        //update the state cache without superTree modification
                        long[] newResolveds = new long[6];
                        //previously resolved
                        newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] = nodeWorld;
                        newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = resolvedSuperTime;
                        newResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] = nodeTime;
                        //previously magics
                        newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrderMagic;
                        newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = nodeSuperTimeTreeMagic;
                        newResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] = nodeTimeTree.magic();
                        castedNode._previousResolveds.set(newResolveds);
                    }
                } else {
                    //TODO potential memory leak here
                    //create a new node superTimeTree
                    KTimeTreeChunk newSuperTimeTree = (KTimeTreeChunk) this._space.create(Constants.TIME_TREE_CHUNK, nodeWorld, Constants.NULL_LONG, nodeId, null, null);
                    newSuperTimeTree = (KTimeTreeChunk) this._space.putAndMark(newSuperTimeTree);
                    newSuperTimeTree.insert(nodeTime);
                    //create a new node timeTree
                    //TODO potential memory leak here
                    KTimeTreeChunk newTimeTree = (KTimeTreeChunk) this._space.create(Constants.TIME_TREE_CHUNK, nodeWorld, nodeTime, nodeId, null, null);
                    newTimeTree = (KTimeTreeChunk) this._space.putAndMark(newTimeTree);
                    newTimeTree.insert(nodeTime);
                    //insert into node world order
                    nodeWorldOrder.put(nodeWorld, nodeTime);

                    //let's store the new state if necessary
                    long[] newResolveds = new long[6];
                    //previously resolved
                    newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_INDEX] = nodeWorld;
                    newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = nodeTime;
                    newResolveds[Constants.PREVIOUS_RESOLVED_TIME_INDEX] = nodeTime;
                    //previously magics
                    newResolveds[Constants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrder.magic();
                    newResolveds[Constants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = newSuperTimeTree.magic();
                    newResolveds[Constants.PREVIOUS_RESOLVED_TIME_MAGIC] = newTimeTree.magic();
                    castedNode._previousResolveds.set(newResolveds);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            //free lock
            nodeWorldOrder.unlock();
        }
        if (hasToCleanSuperTimeTree) {
            _space.unmarkChunk(nodeSuperTimeTree);
        }
        if (hasToCleanTimeTree) {
            _space.unmarkChunk(nodeTimeTree);
        }
        //unMark World order chunks
        _space.unmarkChunk(globalWorldOrder);
        _space.unmarkChunk(nodeWorldOrder);
        return resultStateChunk;
    }

    @Override
    public void resolveTimepoints(final KNode node, final long beginningOfSearch, final long endOfSearch, final KCallback<long[]> callback) {
        long[] keys = new long[]{
                Constants.NULL_LONG, Constants.NULL_LONG, Constants.NULL_LONG,
                Constants.NULL_LONG, Constants.NULL_LONG, node.id()
        };
        getOrLoadAndMarkAll(new byte[]{Constants.WORLD_ORDER_CHUNK, Constants.WORLD_ORDER_CHUNK}, keys, new KCallback<KChunk[]>() {
            @Override
            public void on(KChunk[] orders) {
                if (orders == null || orders.length != 2) {
                    callback.on(new long[0]);
                    return;
                }
                final KWorldOrderChunk globalWorldOrder = (KWorldOrderChunk) orders[0];
                final KWorldOrderChunk objectWorldOrder = (KWorldOrderChunk) orders[1];
                //worlds collector
                final int[] collectionSize = {Constants.MAP_INITIAL_CAPACITY};
                final long[][] collectedWorlds = {new long[collectionSize[0]]};
                int collectedIndex = 0;

                long currentWorld = node.world();
                while (currentWorld != Constants.NULL_LONG) {
                    long divergenceTimepoint = objectWorldOrder.get(currentWorld);
                    if (divergenceTimepoint != Constants.NULL_LONG) {
                        if (divergenceTimepoint < beginningOfSearch) {
                            break;
                        } else if (divergenceTimepoint > endOfSearch) {
                            //next round, go to parent world
                            currentWorld = globalWorldOrder.get(currentWorld);
                        } else {
                            //that's fit, add to search
                            collectedWorlds[0][collectedIndex] = currentWorld;
                            collectedIndex++;
                            if (collectedIndex == collectionSize[0]) {
                                //reallocate
                                long[] temp_collectedWorlds = new long[collectionSize[0] * 2];
                                System.arraycopy(collectedWorlds[0], 0, temp_collectedWorlds, 0, collectionSize[0]);
                                collectedWorlds[0] = temp_collectedWorlds;
                                collectionSize[0] = collectionSize[0] * 2;
                            }
                            //go to parent
                            currentWorld = globalWorldOrder.get(currentWorld);
                        }
                    } else {
                        //go to parent
                        currentWorld = globalWorldOrder.get(currentWorld);
                    }
                }
                //create request concat keys
                resolveTimepointsFromWorlds(globalWorldOrder, objectWorldOrder, node, beginningOfSearch, endOfSearch, collectedWorlds[0], collectedIndex, callback);
            }
        });
    }

    private void resolveTimepointsFromWorlds(final KWorldOrderChunk globalWorldOrder, final KWorldOrderChunk objectWorldOrder, final KNode node, final long beginningOfSearch, final long endOfSearch, final long[] collectedWorlds, final int collectedWorldsSize, final KCallback<long[]> callback) {
        final MWGResolver selfPointer = this;

        final long[] timeTreeKeys = new long[collectedWorldsSize * 3];
        final byte[] types = new byte[collectedWorldsSize];
        for (int i = 0; i < collectedWorldsSize; i++) {
            timeTreeKeys[i * 3] = collectedWorlds[i];
            timeTreeKeys[i * 3 + 1] = Constants.NULL_LONG;
            timeTreeKeys[i * 3 + 2] = node.id();
            types[i] = Constants.TIME_TREE_CHUNK;
        }
        getOrLoadAndMarkAll(types, timeTreeKeys, new KCallback<KChunk[]>() {
            @Override
            public void on(final KChunk[] superTimeTrees) {
                if (superTimeTrees == null) {
                    selfPointer._space.unmarkChunk(objectWorldOrder);
                    selfPointer._space.unmarkChunk(globalWorldOrder);
                    callback.on(new long[0]);
                } else {
                    //time collector
                    final int[] collectedSize = {Constants.MAP_INITIAL_CAPACITY};
                    final long[][] collectedSuperTimes = {new long[collectedSize[0]]};
                    final long[][] collectedSuperTimesAssociatedWorlds = {new long[collectedSize[0]]};
                    final int[] insert_index = {0};

                    long previousDivergenceTime = endOfSearch;
                    for (int i = 0; i < collectedWorldsSize; i++) {
                        final KTimeTreeChunk timeTree = (KTimeTreeChunk) superTimeTrees[i];
                        if (timeTree != null) {
                            long currentDivergenceTime = objectWorldOrder.get(collectedWorlds[i]);
                            if (currentDivergenceTime < beginningOfSearch) {
                                currentDivergenceTime = beginningOfSearch;
                            }
                            final long finalPreviousDivergenceTime = previousDivergenceTime;
                            timeTree.range(currentDivergenceTime, previousDivergenceTime, Constants.END_OF_TIME, new KTreeWalker() {
                                @Override
                                public void elem(long t) {
                                    if (t != finalPreviousDivergenceTime) {
                                        collectedSuperTimes[0][insert_index[0]] = t;
                                        collectedSuperTimesAssociatedWorlds[0][insert_index[0]] = timeTree.world();
                                        insert_index[0]++;
                                        if (collectedSize[0] == insert_index[0]) {
                                            //reallocate
                                            long[] temp_collectedSuperTimes = new long[collectedSize[0] * 2];
                                            long[] temp_collectedSuperTimesAssociatedWorlds = new long[collectedSize[0] * 2];
                                            System.arraycopy(collectedSuperTimes[0], 0, temp_collectedSuperTimes, 0, collectedSize[0]);
                                            System.arraycopy(collectedSuperTimesAssociatedWorlds[0], 0, temp_collectedSuperTimesAssociatedWorlds, 0, collectedSize[0]);

                                            collectedSuperTimes[0] = temp_collectedSuperTimes;
                                            collectedSuperTimesAssociatedWorlds[0] = temp_collectedSuperTimesAssociatedWorlds;

                                            collectedSize[0] = collectedSize[0] * 2;
                                        }
                                    }
                                }
                            });
                            previousDivergenceTime = currentDivergenceTime;
                        }
                        selfPointer._space.unmarkChunk(timeTree);
                    }
                    //now we have superTimes, lets convert them to all times
                    resolveTimepointsFromSuperTimes(globalWorldOrder, objectWorldOrder, node, beginningOfSearch, endOfSearch, collectedSuperTimesAssociatedWorlds[0], collectedSuperTimes[0], insert_index[0], callback);
                }
            }
        });
    }

    private void resolveTimepointsFromSuperTimes(final KWorldOrderChunk globalWorldOrder, final KWorldOrderChunk objectWorldOrder, final KNode node, final long beginningOfSearch, final long endOfSearch, final long[] collectedWorlds, final long[] collectedSuperTimes, final int collectedSize, final KCallback<long[]> callback) {
        final MWGResolver selfPointer = this;

        final long[] timeTreeKeys = new long[collectedSize * 3];
        final byte[] types = new byte[collectedSize];
        for (int i = 0; i < collectedSize; i++) {
            timeTreeKeys[i * 3] = collectedWorlds[i];
            timeTreeKeys[i * 3 + 1] = collectedSuperTimes[i];
            timeTreeKeys[i * 3 + 2] = node.id();
            types[i] = Constants.TIME_TREE_CHUNK;
        }
        getOrLoadAndMarkAll(types, timeTreeKeys, new KCallback<KChunk[]>() {
            @Override
            public void on(KChunk[] timeTrees) {
                if (timeTrees == null) {
                    selfPointer._space.unmarkChunk(objectWorldOrder);
                    selfPointer._space.unmarkChunk(globalWorldOrder);
                    callback.on(new long[0]);
                } else {
                    //time collector
                    final int[] collectedTimesSize = {Constants.MAP_INITIAL_CAPACITY};
                    final long[][] collectedTimes = {new long[collectedTimesSize[0]]};
                    final int[] insert_index = {0};
                    long previousDivergenceTime = endOfSearch;
                    for (int i = 0; i < collectedSize; i++) {
                        final KTimeTreeChunk timeTree = (KTimeTreeChunk) timeTrees[i];
                        if (timeTree != null) {
                            long currentDivergenceTime = objectWorldOrder.get(collectedWorlds[i]);
                            if (currentDivergenceTime < beginningOfSearch) {
                                currentDivergenceTime = beginningOfSearch;
                            }
                            final long finalPreviousDivergenceTime = previousDivergenceTime;
                            timeTree.range(currentDivergenceTime, previousDivergenceTime, Constants.END_OF_TIME, new KTreeWalker() {
                                @Override
                                public void elem(long t) {
                                    if (t != finalPreviousDivergenceTime) {
                                        collectedTimes[0][insert_index[0]] = t;
                                        insert_index[0]++;
                                        if (collectedTimesSize[0] == insert_index[0]) {
                                            //reallocate
                                            long[] temp_collectedTimes = new long[collectedTimesSize[0] * 2];
                                            System.arraycopy(collectedTimes[0], 0, temp_collectedTimes, 0, collectedTimesSize[0]);
                                            collectedTimes[0] = temp_collectedTimes;
                                            collectedTimesSize[0] = collectedTimesSize[0] * 2;
                                        }
                                    }
                                }
                            });
                            if (i < collectedSize - 1) {
                                if (collectedWorlds[i + 1] != collectedWorlds[i]) {
                                    //world overriding semantic
                                    previousDivergenceTime = currentDivergenceTime;
                                }
                            }
                        }
                        selfPointer._space.unmarkChunk(timeTree);
                    }
                    //now we have times
                    if (insert_index[0] != collectedTimesSize[0]) {
                        long[] tempTimeline = new long[insert_index[0]];
                        System.arraycopy(collectedTimes[0], 0, tempTimeline, 0, insert_index[0]);
                        collectedTimes[0] = tempTimeline;
                    }
                    selfPointer._space.unmarkChunk(objectWorldOrder);
                    selfPointer._space.unmarkChunk(globalWorldOrder);
                    callback.on(collectedTimes[0]);
                }
            }
        });
    }

    /**
     * Dictionary methods
     */
    @Override
    public long stringToLongKey(String name) {
        KStringLongMap dictionaryIndex = (KStringLongMap) this.dictionary.get(0);
        if (dictionaryIndex == null) {
            dictionaryIndex = (KStringLongMap) this.dictionary.getOrCreate(0, KType.STRING_LONG_MAP);
        }
        long encodedKey = dictionaryIndex.getValue(name);
        if (encodedKey == Constants.NULL_LONG) {
            dictionaryIndex.put(name, Constants.NULL_LONG);
            encodedKey = dictionaryIndex.getValue(name);
        }
        return encodedKey;
    }

    @Override
    public String longKeyToString(long key) {
        KStringLongMap dictionaryIndex = (KStringLongMap) this.dictionary.get(0);
        if (dictionaryIndex != null) {
            return dictionaryIndex.getKey(key);
        }
        return null;
    }

}
