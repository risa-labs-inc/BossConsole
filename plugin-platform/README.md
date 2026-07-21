# BOSS Plugin Platform

Host-side plugin platform (SDK + loader/runtime) that lets BOSS load and run
plugins. This directory holds the **infrastructure**, not the plugins
themselves — shippable plugins live in the separate `boss_plugins` repo.

## Architecture

```
plugin-platform/
├── plugin-api/              # Core plugin interfaces and contracts
├── plugin-ui/               # Shared UI components (colors, themes)
├── plugin-scrollbar/        # Scrollbar utilities
├── plugin-git-types/        # Git data types
├── plugin-bookmark-types/   # Bookmark data types
├── plugin-workspace-types/  # Workspace data types
├── plugin-window/           # Window state management
│
├── plugin-panel-*/          # Panel plugins
│   ├── plugin-panel-git-log/
│   ├── plugin-panel-git-status/
│   ├── plugin-panel-console/
│   ├── plugin-panel-performance/
│   ├── plugin-panel-bookmarks/
│   ├── plugin-panel-codebase/
│   ├── plugin-panel-terminal/
│   ├── plugin-panel-topofmind/
│   ├── plugin-panel-fluck/
│   ├── plugin-panel-secret-manager/
│   ├── plugin-panel-downloads/
│   └── ...
│
└── plugin-tab-*/            # Tab type plugins
    ├── plugin-tab-fluck/
    ├── plugin-tab-code-editor/
    └── plugin-tab-terminal/
```

## Creating a Plugin

### 1. Create Plugin Module

Add to `settings.gradle.kts`:
```kotlin
include(":plugin-platform:plugin-panel-myplugin")
```

Create `build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            api(projects.pluginPlatform.pluginApi)
            implementation(compose.runtime)
            implementation(compose.material)
        }
    }
}
```

### 2. Define Panel Info

```kotlin
object MyPanelInfo : PanelInfo {
    override val id = PanelId("my-panel", 50)
    override val displayName = "My Panel"
    override val icon = FeatherIcons.Star
    override val defaultSlotPosition = Panel.left.top
}
```

### 3. Create Component

```kotlin
class MyPanelComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        // Your UI here
    }
}
```

### 4. Create Plugin Object

```kotlin
object MyPanelPlugin : Plugin {
    override val pluginId = "ai.rever.boss.plugin.my-panel"
    override val displayName = "My Panel"

    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(MyPanelInfo) { ctx, panelInfo ->
            MyPanelComponent(ctx, panelInfo)
        }
    }
}
```

### 5. Register in DefaultPlugin

```kotlin
// In DefaultPlugin.kt init block
MyPanelPlugin.register(this)
```

## Plugin Types

### Static Plugins
Always registered when the app starts.

### Dynamic Plugins
Registered/unregistered based on conditions:
- **Auth-based**: SecretManager (admin only)
- **Project-based**: Git panels (git repos only)

## Lifecycle

1. `DefaultPlugin` is created with registries
2. Plugins call `register(context)`
3. Panels/tabs are registered with factories
4. On dispose, `pluginScope` is cancelled
5. Dynamic plugins unregister when conditions change

## Best Practices

- Use `pluginScope` for coroutines
- Implement `dispose()` for cleanup
- Use data provider interfaces, not direct dependencies
- Keep UI in commonMain, platform code in platform sources
