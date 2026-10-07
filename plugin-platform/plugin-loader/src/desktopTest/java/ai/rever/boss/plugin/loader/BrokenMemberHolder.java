package ai.rever.boss.plugin.loader;

/**
 * Fixture for the verifyMethod/hasMethod and verifyConstructor linkage guards:
 * member signatures reference BrokenMethodType, which is blocked by the classloader,
 * so reflective method and constructor lookups (getMethod, getDeclaredMethod, getDeclaredConstructor)
 * throw NoClassDefFoundError.
 */
public class BrokenMemberHolder {
    public BrokenMemberHolder() {}

    public BrokenMemberHolder(BrokenMethodType broken) {}

    public void brokenMethod(BrokenMethodType broken) {}
}
