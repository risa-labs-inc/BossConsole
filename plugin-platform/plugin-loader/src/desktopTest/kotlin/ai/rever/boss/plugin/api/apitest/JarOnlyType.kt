package ai.rever.boss.plugin.api.apitest

/**
 * Test fixture for ApiClassLoaderTest: a type in the shared
 * `ai.rever.boss.plugin.api.` namespace whose BYTES are packed into synthetic
 * api jars at test runtime. Tests control which classloader can see it by
 * choosing parents that do or do not include the test classpath.
 *
 * Keep it dependency-free (single method, java types only) so it loads on a
 * chain without the Kotlin stdlib.
 */
interface JarOnlyType {
    fun ping(): String
}
