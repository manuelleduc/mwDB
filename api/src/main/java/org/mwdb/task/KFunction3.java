package org.mwdb.task;

@FunctionalInterface
public interface KFunction3<T1, T2, T3, R> {

    R apply(T1 t1, T2 t2, T3 t3) throws Exception;

}