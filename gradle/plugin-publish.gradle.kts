// Plugin Publishing Script for BOSS Plugins
// Apply this script in plugin subprojects to enable publishing to the BOSS Plugin Store
//
// Usage in plugin build.gradle.kts:
//   apply(from = rootProject.file("gradle/plugin-publish.gradle.kts"))
//
// Then run:
//   ./gradlew :plugin-platform:plugin-my-plugin:publishPlugin -PpluginStoreToken=eyJ...

// Find the JAR task (varies by multiplatform setup)
val jarTask = tasks.findByName("desktopJar") 
    ?: tasks.findByName("jvmJar")
    ?: tasks.findByName("jar")

if (jarTask != null) {
    tasks.register<PublishPluginTask>("publishPlugin") {
        group = "publishing"
        description = "Publishes the plugin to the BOSS Plugin Store"
        
        // Set the JAR file from the appropriate jar task
        jarFile.set(
            when (jarTask) {
                is org.gradle.jvm.tasks.Jar -> jarTask.archiveFile
                else -> {
                    // For multiplatform, try to find the output file
                    val outputs = jarTask.outputs.files
                    layout.projectDirectory.file(outputs.singleFile.absolutePath)
                }
            }
        )
        
        // Read properties from project or gradle.properties
        pluginId.set(providers.gradleProperty("pluginId"))
        displayName.set(providers.gradleProperty("displayName"))
        pluginVersion.set(providers.gradleProperty("pluginVersion"))
        authorName.set(providers.gradleProperty("authorName"))
        pluginDescription.set(providers.gradleProperty("pluginDescription"))
        changelog.set(providers.gradleProperty("changelog"))
        tags.set(providers.gradleProperty("tags"))
        
        // Auth token - from property or environment
        authToken.set(
            providers.gradleProperty("pluginStoreToken").orElse(
                providers.environmentVariable("BOSS_PLUGIN_STORE_TOKEN")
            )
        )
        
        // Store URL - from property or environment
        storeUrl.set(
            providers.gradleProperty("pluginStoreUrl").orElse(
                providers.environmentVariable("BOSS_PLUGIN_STORE_URL")
            )
        )
        
        // Anon key - from property or environment
        anonKey.set(
            providers.gradleProperty("supabaseAnonKey").orElse(
                providers.environmentVariable("SUPABASE_ANON_KEY")
            )
        )
        
        // Depend on the jar task
        dependsOn(jarTask)
    }
} else {
    logger.warn("No JAR task found for ${project.name}, publishPlugin task not registered")
}
