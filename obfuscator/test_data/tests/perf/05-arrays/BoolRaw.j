.version 52 0
.class public super BoolRaw
.super java/lang/Object

.runtime invisible annotations
    .annotation Lby/radioegor146/nativeobfuscator/Native;
    .end annotation
.end runtime

.method public static once : ([Z)I
    .code stack 3 locals 1
        aload_0
        iconst_0
        iconst_2
        bastore
        aload_0
        iconst_0
        baload
        ireturn
    .end code
.end method

.method public static fill : ([ZI)I
    .code stack 3 locals 4
        iconst_0
        istore_2
Lfill:
        .stack append Integer
        iload_2
        iload_1
        if_icmpge LfillEnd
        aload_0
        iload_2
        iconst_2
        bastore
        iinc 2 1
        goto Lfill
LfillEnd:
        .stack same
        iconst_0
        istore_2
        iconst_0
        istore_3
Lsum:
        .stack append Integer
        iload_2
        aload_0
        arraylength
        if_icmpge LsumEnd
        iload_3
        aload_0
        iload_2
        baload
        iadd
        istore_3
        iinc 2 1
        goto Lsum
LsumEnd:
        .stack same
        iload_3
        ireturn
    .end code
.end method
.end class
