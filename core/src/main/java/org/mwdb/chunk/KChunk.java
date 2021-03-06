package org.mwdb.chunk;

public interface KChunk {

    long world();

    long time();

    long id();

    byte chunkType();

    long marks();

    long flags();

    void save(KBuffer buffer);

}
