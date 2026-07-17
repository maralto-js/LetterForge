package com.letterforge.model;

public enum LetterType {
    DIRECT,
    ANONYMOUS,
    URGENT,
    OFFICIAL,
    BROADCAST,
    JORNAL;

    /** Tipos entregues a todos (mesmo pipeline de broadcast: storage, seen, cleanup). */
    public boolean isBroadcastLike() {
        return this == BROADCAST || this == JORNAL;
    }
}
