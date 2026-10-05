package ai.rever.boss.plugin.loader;

/**
 * Fixture for the resolveField linkage guard: the static field's TYPE is blocked from the
 * classloader, so reflective field resolution (getDeclaredFields) throws
 * NoClassDefFoundError. Static and never assigned, so class definition itself succeeds and
 * only the reflection call hits the missing type.
 */
public class HolderWithBrokenField {
    public static BrokenFieldType broken;
}
