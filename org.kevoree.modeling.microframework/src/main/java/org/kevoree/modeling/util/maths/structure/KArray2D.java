package org.kevoree.modeling.util.maths.structure;

public interface KArray2D {

    int nbRaws();

    int nbColumns();

    double get(int rawIndex, int columnIndex);

    void set(int rawIndex, int columnIndex, double value);

}