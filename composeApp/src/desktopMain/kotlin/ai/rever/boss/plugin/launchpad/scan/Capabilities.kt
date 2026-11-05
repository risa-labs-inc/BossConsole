package ai.rever.boss.plugin.launchpad.scan

internal enum class ScanRisk { INFO, LOW, MEDIUM, HIGH }

/**
 * Something a plugin can do, in terms a reviewer can act on. [why] is the reason it matters; for the host APIs it
 * is what the host itself documents about that surface (an event bus every plugin can read, a project rewrite no
 * permission gates, a vault). The ranking is a judgement, so the text says why and not only how bad.
 */
internal data class Capability(
    val id: String,
    val risk: ScanRisk,
    val title: String,
    val why: String,
)

/** One place a capability was seen: the class, and the reference that triggered it. */
internal data class Evidence(
    val className: String,
    val detail: String,
)

internal data class Hit(
    val capability: Capability,
    val evidence: Evidence,
)

private const val API = "ai/rever/boss/plugin/api/"

private class Rule(
    val capability: Capability,
    val method: ((MemberRef) -> Boolean)? = null,
    val type: ((String) -> Boolean)? = null,
    val superType: ((String) -> Boolean)? = null,
    val string: ((String) -> Boolean)? = null,
)

private fun cap(
    id: String,
    risk: ScanRisk,
    title: String,
    why: String,
) = Capability(id, risk, title, why)

private fun call(
    owner: String,
    vararg names: String,
): (MemberRef) -> Boolean = { ref -> ref.owner == owner && (names.isEmpty() || ref.name in names) }

private fun typeIn(vararg names: String): (String) -> Boolean = typeInSet(names.toSet())

private fun typeInSet(names: Set<String>): (String) -> Boolean = { it in names }

private fun typeStarts(vararg prefixes: String): (String) -> Boolean = { t -> prefixes.any { t.startsWith(it) } }

/**
 * The capabilities the scan knows about, and how each is recognised in a class's constant pool.
 *
 * The host-API tables ([hostTypes], [contextGetters]) are checked against the real plugin API in tests, so a
 * renamed type or getter fails the build instead of becoming a silent blind spot.
 */
internal object CapabilityCatalog {
    private val process =
        cap(
            "process.exec",
            ScanRisk.HIGH,
            "Runs other programs",
            "Starts an OS process with the user's rights.",
        )
    private val netClient =
        cap(
            "net.client",
            ScanRisk.MEDIUM,
            "Opens outbound network connections",
            "Can send data off the machine.",
        )
    private val netListen =
        cap(
            "net.listen",
            ScanRisk.HIGH,
            "Listens for inbound connections",
            "Opens a port other software can reach.",
        )
    private val fsWrite =
        cap(
            "fs.write",
            ScanRisk.MEDIUM,
            "Writes files",
            "Can create or change files outside its own storage.",
        )
    private val fsDelete =
        cap(
            "fs.delete",
            ScanRisk.MEDIUM,
            "Deletes or moves files",
            "Can remove or relocate user files.",
        )
    private val fsRead =
        cap(
            "fs.read",
            ScanRisk.LOW,
            "Reads files",
            "Ordinary, but with a network capability it is how data leaves.",
        )
    private val reflectUse =
        cap(
            "reflect.use",
            ScanRisk.LOW,
            "Uses reflection",
            "Calls code by name, which static analysis cannot follow.",
        )
    private val reflectAccess =
        cap(
            "reflect.access",
            ScanRisk.MEDIUM,
            "Bypasses access checks",
            "setAccessible reaches members that are private by design.",
        )
    private val unsafe =
        cap(
            "reflect.unsafe",
            ScanRisk.HIGH,
            "Uses Unsafe or JDK internals",
            "Raw memory access that bypasses the JVM's safety.",
        )
    private val dynClass =
        cap(
            "dynamic.classload",
            ScanRisk.HIGH,
            "Defines or loads classes at run time",
            "Code that is not in this JAR can run.",
        )
    private val dynScript =
        cap(
            "dynamic.script",
            ScanRisk.HIGH,
            "Compiles or evaluates code at run time",
            "A script engine runs text as code.",
        )
    private val nativeCode =
        cap(
            "native.code",
            ScanRisk.HIGH,
            "Loads native code",
            "Native libraries run outside the JVM's protections.",
        )
    private val envRead =
        cap(
            "env.read",
            ScanRisk.MEDIUM,
            "Reads environment variables",
            "BOSS keeps API keys in the environment.",
        )
    private val jvmExit =
        cap(
            "jvm.exit",
            ScanRisk.MEDIUM,
            "Can stop the whole app",
            "System.exit or Runtime.halt ends the host.",
        )
    private val robot =
        cap(
            "input.robot",
            ScanRisk.HIGH,
            "Synthesises input or captures the screen",
            "java.awt.Robot acts in any application.",
        )
    private val sysClip =
        cap(
            "clipboard.system",
            ScanRisk.MEDIUM,
            "Reads the system clipboard",
            "Clipboards routinely hold passwords.",
        )
    private val credPath =
        cap(
            "cred.path",
            ScanRisk.MEDIUM,
            "Names a credential file",
            "Mentions a path where tokens or keys live.",
        )

    private val secrets =
        cap(
            "host.secrets",
            ScanRisk.HIGH,
            "Uses the secret vault API",
            "SecretDataProvider reaches stored passwords.",
        )
    private val brokered =
        cap(
            "host.brokered-credential",
            ScanRisk.HIGH,
            "Uses brokered credentials",
            "Receives keys minted for the signed-in user.",
        )
    private val storeKey =
        cap(
            "host.store-key",
            ScanRisk.HIGH,
            "Uses the plugin store API key",
            "Can act against the plugin store as the app.",
        )
    private val supabase =
        cap(
            "host.supabase",
            ScanRisk.HIGH,
            "Uses the authenticated backend proxy",
            "Queries go out with the user's session attached.",
        )
    private val admin =
        cap(
            "host.admin",
            ScanRisk.HIGH,
            "Manages users or roles",
            "Changes who can do what.",
        )
    private val projectReplace =
        cap(
            "host.project-replace",
            ScanRisk.HIGH,
            "Rewrites files across the open project",
            "replaceInProject is ungated: no permission stands in the way.",
        )
    private val projectSearch =
        cap(
            "host.project-search",
            ScanRisk.MEDIUM,
            "Searches the open project",
            "Reads the content of every project file.",
        )
    private val hostFiles =
        cap(
            "host.filesystem",
            ScanRisk.MEDIUM,
            "Uses the host file system API",
            "Reads and writes files through the host.",
        )
    private val eventBus =
        cap(
            "host.event-bus",
            ScanRisk.MEDIUM,
            "Subscribes to the application event bus",
            "Ungated: sees browsing events and every project opened.",
        )
    private val browser =
        cap(
            "host.browser",
            ScanRisk.MEDIUM,
            "Drives the integrated browser",
            "Can read pages and act in logged-in sessions.",
        )
    private val editor =
        cap(
            "host.editor",
            ScanRisk.MEDIUM,
            "Reads editor content",
            "Sees the text of open files.",
        )
    private val hostClip =
        cap(
            "host.clipboard",
            ScanRisk.MEDIUM,
            "Uses the host clipboard API",
            "Clipboards routinely hold passwords.",
        )
    private val screen =
        cap(
            "host.screen-capture",
            ScanRisk.MEDIUM,
            "Captures the screen",
            "Screenshots show whatever is open.",
        )
    private val llm =
        cap(
            "host.llm",
            ScanRisk.MEDIUM,
            "Spends the user's AI credentials",
            "Makes model calls on the user's keys.",
        )
    private val tabs =
        cap(
            "host.active-tabs",
            ScanRisk.MEDIUM,
            "Reads open tabs in every window",
            "Tab titles and URLs show what the user is doing.",
        )
    private val mcp =
        cap(
            "host.mcp",
            ScanRisk.MEDIUM,
            "Exposes tools to AI agents",
            "Registered tools are callable by any connected agent.",
        )
    private val auth =
        cap(
            "host.auth",
            ScanRisk.LOW,
            "Reads the signed-in identity",
            "Learns who the user is.",
        )
    private val git =
        cap(
            "host.git",
            ScanRisk.LOW,
            "Uses the git API",
            "Reads repository state.",
        )

    /** Host provider types, by the capability that reaching them implies. */
    val hostTypes: Map<Capability, List<String>> =
        mapOf(
            secrets to listOf("${API}SecretDataProvider"),
            brokered to listOf("${API}BrokeredCredentialProvider"),
            storeKey to listOf("${API}PluginStoreApiKeyProvider"),
            supabase to listOf("${API}SupabaseDataProvider"),
            admin to listOf("${API}RoleManagementProvider", "${API}UserManagementProvider"),
            projectSearch to listOf("${API}ProjectSearchProvider"),
            hostFiles to listOf("${API}FileSystemDataProvider"),
            eventBus to listOf("${API}ApplicationEventBus"),
            browser to listOf("ai/rever/boss/plugin/browser/BrowserService"),
            editor to listOf("${API}EditorContentProvider"),
            hostClip to listOf("${API}ClipboardProvider"),
            screen to listOf("${API}ScreenCaptureProvider"),
            llm to listOf("${API}LlmProvider"),
            tabs to listOf("${API}ActiveTabsProvider"),
            auth to listOf("${API}AuthDataProvider"),
            git to listOf("${API}GitDataProvider"),
        )

    /** `PluginContext` getters the host API is reached through, by capability. */
    val contextGetters: Map<Capability, List<String>> =
        mapOf(
            secrets to listOf("getSecretDataProvider"),
            brokered to listOf("getBrokeredCredentialProvider"),
            storeKey to listOf("getPluginStoreApiKeyProvider"),
            supabase to listOf("getSupabaseDataProvider"),
            admin to listOf("getRoleManagementProvider", "getUserManagementProvider"),
            projectSearch to listOf("getProjectSearchProvider"),
            hostFiles to listOf("getFileSystemDataProvider"),
            eventBus to listOf("getApplicationEventBus"),
            browser to listOf("getBrowserService"),
            editor to listOf("getEditorContentProvider"),
            hostClip to listOf("getClipboardProvider"),
            screen to listOf("getScreenCaptureProvider"),
            llm to listOf("getLlmProvider"),
            tabs to listOf("getActiveTabsProvider"),
            auth to listOf("getAuthDataProvider"),
            git to listOf("getGitDataProvider"),
        )

    private val credentialMarkers =
        listOf(
            ".ssh/",
            ".ssh\\",
            "id_rsa",
            "id_ed25519",
            ".aws/credentials",
            ".npmrc",
            ".git-credentials",
            ".netrc",
            "env_vars",
        )

    private val netClientTypes =
        setOf(
            "java/net/Socket",
            "java/net/URL",
            "java/net/URLConnection",
            "java/net/HttpURLConnection",
            "java/net/DatagramSocket",
            "java/net/http/HttpClient",
            "java/nio/channels/SocketChannel",
            "javax/net/ssl/HttpsURLConnection",
        )

    private const val KOTLIN_FILES = "kotlin/io/FilesKt"
    private const val PROJECT_SEARCH = "${API}ProjectSearchProvider"
    private val loaderSupers =
        setOf("java/lang/ClassLoader", "java/net/URLClassLoader", "java/security/SecureClassLoader")

    private fun byOwnerPrefix(
        prefix: String,
        vararg namePrefixes: String,
    ): (MemberRef) -> Boolean = { ref -> ref.owner.startsWith(prefix) && namePrefixes.any { ref.name.startsWith(it) } }

    private val jdkRules: List<Rule> =
        listOf(
            Rule(process, method = call("java/lang/Runtime", "exec")),
            Rule(process, method = call("java/lang/ProcessBuilder", "<init>", "start", "command")),
            Rule(netClient, method = { it.owner in netClientTypes }),
            Rule(netClient, type = typeStarts("io/ktor/client/", "okhttp3/", "org/apache/http/client/")),
            Rule(netListen, method = call("java/net/ServerSocket", "<init>", "bind", "accept")),
            Rule(netListen, method = call("java/nio/channels/ServerSocketChannel", "open", "bind")),
            Rule(netListen, type = typeStarts("io/ktor/server/", "com/sun/net/httpserver/")),
            Rule(fsWrite, method = call("java/io/FileOutputStream", "<init>")),
            Rule(fsWrite, method = call("java/io/FileWriter", "<init>")),
            Rule(fsWrite, method = call("java/io/RandomAccessFile", "<init>")),
            Rule(fsWrite, method = call("java/nio/file/Files", "write", "writeString", "newOutputStream")),
            Rule(fsWrite, method = call("java/nio/file/Files", "newBufferedWriter")),
            Rule(fsWrite, method = call("java/nio/file/Files", "createFile", "copy", "setPosixFilePermissions")),
            Rule(fsWrite, method = byOwnerPrefix(KOTLIN_FILES, "writeText", "writeBytes", "appendText", "appendBytes")),
            Rule(fsWrite, method = byOwnerPrefix(KOTLIN_FILES, "copyTo")),
            Rule(fsDelete, method = call("java/io/File", "delete", "deleteOnExit", "renameTo")),
            Rule(fsDelete, method = call("java/nio/file/Files", "delete", "deleteIfExists", "move", "walkFileTree")),
            Rule(fsDelete, method = byOwnerPrefix(KOTLIN_FILES, "deleteRecursively")),
            Rule(fsRead, method = call("java/io/FileInputStream", "<init>")),
            Rule(fsRead, method = call("java/io/FileReader", "<init>")),
            Rule(fsRead, method = call("java/nio/file/Files", "readAllBytes", "readString", "readAllLines")),
            Rule(fsRead, method = call("java/nio/file/Files", "newInputStream")),
            Rule(fsRead, method = call("java/nio/file/Files", "newBufferedReader", "lines", "walk")),
            Rule(fsRead, method = byOwnerPrefix(KOTLIN_FILES, "readText", "readBytes", "readLines", "useLines")),
            Rule(fsRead, method = byOwnerPrefix(KOTLIN_FILES, "walk")),
            Rule(fsRead, method = byOwnerPrefix(KOTLIN_FILES, "inputStream", "bufferedReader", "reader")),
            Rule(fsRead, method = call("java/util/zip/ZipFile", "<init>")),
            Rule(fsRead, method = call("java/util/jar/JarFile", "<init>")),
            Rule(reflectUse, method = call("java/lang/Class", "forName")),
            Rule(reflectUse, method = call("java/lang/reflect/Method", "invoke")),
            Rule(reflectUse, method = call("java/lang/reflect/Constructor", "newInstance")),
            Rule(reflectAccess, method = { it.name == "setAccessible" || it.name == "trySetAccessible" }),
            Rule(unsafe, type = typeStarts("sun/misc/Unsafe", "jdk/internal/", "sun/reflect/")),
            Rule(dynClass, method = call("java/lang/ClassLoader", "defineClass")),
            Rule(dynClass, method = call("java/net/URLClassLoader", "<init>", "newInstance")),
            Rule(dynClass, method = call("java/lang/invoke/MethodHandles\$Lookup", "defineClass", "defineHiddenClass")),
            Rule(dynClass, superType = typeInSet(loaderSupers)),
            Rule(dynScript, type = typeStarts("javax/script/", "javax/tools/", "groovy/lang/")),
            Rule(dynScript, type = typeStarts("org/mozilla/javascript/")),
            Rule(nativeCode, method = call("java/lang/System", "load", "loadLibrary")),
            Rule(nativeCode, method = call("java/lang/Runtime", "load", "loadLibrary")),
            Rule(nativeCode, type = typeStarts("com/sun/jna/", "java/lang/foreign/", "jdk/incubator/foreign/")),
            Rule(envRead, method = call("java/lang/System", "getenv")),
            Rule(envRead, method = call("java/lang/ProcessBuilder", "environment")),
            Rule(jvmExit, method = call("java/lang/System", "exit")),
            Rule(jvmExit, method = call("java/lang/Runtime", "exit", "halt")),
            Rule(robot, type = typeIn("java/awt/Robot")),
            Rule(sysClip, method = call("java/awt/Toolkit", "getSystemClipboard")),
            Rule(credPath, string = { s -> credentialMarkers.any { s.contains(it) } }),
        )

    private val hostRules: List<Rule> =
        hostTypes.map { (capability, types) -> Rule(capability, type = typeInSet(types.toSet())) } +
            contextGetters.map { (capability, getters) ->
                Rule(capability, method = { it.owner == "${API}PluginContext" && it.name in getters })
            } +
            listOf(
                Rule(projectReplace, method = { it.owner == PROJECT_SEARCH && it.name.startsWith("replaceInProject") }),
                Rule(mcp, method = call("${API}PluginContext", "registerMcpToolProvider")),
                Rule(mcp, type = typeIn("${API}McpToolProvider")),
            )

    private val rules: List<Rule> = jdkRules + hostRules

    /** Every capability the scan can report, highest risk first. */
    val all: List<Capability> =
        rules
            .map { it.capability }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Capability> { it.risk }.thenBy { it.id })

    /** Every capability [info] references, with the first piece of evidence for each. */
    fun detect(info: ClassInfo): List<Hit> {
        val seen = LinkedHashMap<String, Hit>()
        for (rule in rules) {
            val detail = firstMatch(rule, info) ?: continue
            seen.putIfAbsent(rule.capability.id, Hit(rule.capability, Evidence(info.name, detail)))
        }
        return seen.values.toList()
    }

    private fun supertypes(info: ClassInfo): List<String> = listOfNotNull(info.superName) + info.interfaces

    private fun firstMatch(
        rule: Rule,
        info: ClassInfo,
    ): String? =
        rule.method?.let { m -> info.methodRefs.firstOrNull(m)?.let { "${it.owner}.${it.name}" } }
            ?: rule.type?.let { t -> info.mentionedTypes.firstOrNull(t) }
            ?: rule.superType?.let { t -> supertypes(info).firstOrNull(t)?.let { "extends $it" } }
            ?: rule.string?.let { s -> if (info.strings.any(s)) "string constant" else null }
}
