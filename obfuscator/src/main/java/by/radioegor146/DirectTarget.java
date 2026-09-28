package by.radioegor146;

public final class DirectTarget {

    public final String namespace;
    public final String bodyName;
    public final boolean isStatic;
    public final boolean isPrivate;
    public final String desc;

    public DirectTarget(String namespace, String bodyName, boolean isStatic, boolean isPrivate, String desc) {
        this.namespace = namespace;
        this.bodyName = bodyName;
        this.isStatic = isStatic;
        this.isPrivate = isPrivate;
        this.desc = desc;
    }

    public String qualifiedBody() {
        return namespace + "::" + bodyName;
    }
}
