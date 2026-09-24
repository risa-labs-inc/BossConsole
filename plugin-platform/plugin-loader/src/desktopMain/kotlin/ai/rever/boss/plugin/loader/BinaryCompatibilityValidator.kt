@file:Suppress("TooManyFunctions")

package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.io.DataInputStream
import java.io.IOException
import java.util.jar.JarEntry
import java.util.jar.JarFile

/**
 * Validates binary compatibility of plugin JARs by loading all classes and
 * verifying that every method, field, and constructor reference in their
 * constant pools can be resolved against the current API.
 *
 * This catches:
 * - `NoClassDefFoundError` — a referenced class is entirely missing
 * - `NoSuchMethodError` — a method/constructor signature changed (e.g. parameter reorder)
 * - `NoSuchFieldError` — a field was removed or renamed
 *
 * The JVM resolves these lazily at first use, so without this check they
 * would surface as crashes during first UI render.
 */
object BinaryCompatibilityValidator {
    /** Classes the host has a contract with; everything else in a plugin JAR is its own runtime. */
    internal const val OWN_CLASS_PREFIX = "ai.rever.boss.plugin."

    /**
     * The most bytes one of the plugin's own class files may inflate to before it is refused.
     *
     * Generously above any real class file and far below a heap. The value, and the idea of
     * capping each class before anything loads it, are from BossConsole#1293.
     */
    internal const val MAX_CLASS_BYTES: Int = 8 * 1024 * 1024

    internal val logger = BossLogger.forComponent("BinaryCompatibilityValidator")

    data class ValidationResult(
        val isCompatible: Boolean,
        val errors: List<String> = emptyList(),
    )

    /**
     * Validate all classes in [jarPath] against the given [classLoader].
     *
     * For each `.class` entry (outside `META-INF/`), the constant pool is
     * parsed to extract all `MethodRef`, `FieldRef`, and `InterfaceMethodRef`
     * entries. Each referenced class is loaded and its members are checked
     * via reflection. This forces resolution of all symbolic references that
     * the JVM would otherwise defer until first execution.
     */
    fun validate(
        classLoader: ClassLoader,
        jarPath: String,
    ): ValidationResult {
        val errors = mutableListOf<String>()

        // The own class being validated when something threw, or null while the JAR was still
        // being opened and enumerated. Only the null case is a failure to read the archive; a throw
        // from inside the loop is a class load failing (a sealing violation, a signed-JAR digest
        // mismatch) and is reported against that class, beside the per-class errors found so far.
        var validating: String? = null

        // Names for every class, bytes for only the ones actually validated.
        //
        // Both were read together before, so the host held a JAR's entire uncompressed class
        // content at once and used a sliver of it. Measured on shipped plugins:
        // boss-plugin-terminal-tab 2.5.74 is 9,469 classes, 40.4MB read to validate 0.50MB;
        // fluck-browser 1.2.29 is 10.3MB read for 2.69MB. A JarEntry is metadata, so carrying
        // those costs nothing and the names below still cover the whole JAR.
        //
        // It also makes this honour the rule stated further down. A bundled third-party class
        // "must not disable the plugin", but reading one that failed threw into the catch below,
        // which returns isCompatible=false - and the caller refuses the load on that. Bytes for
        // those classes are now never read, so they cannot fail a plugin that does not depend on
        // them.
        val classCount =
            try {
                JarFile(jarPath).use { jar ->
                    val classEntries =
                        jar
                            .entries()
                            .asSequence()
                            .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                            .map { entry ->
                                entry.name.removeSuffix(".class").replace('/', '.') to entry
                            }.toList()

                    // Collect all class names in this JAR — references between them are
                    // self-consistent (compiled together) and don't need cross-validation.
                    val jarClassNames = classEntries.mapTo(mutableSetOf()) { it.first }

                    for ((className, entry) in classEntries) {
                        // Only validate the plugin's OWN classes (ai.rever.boss.plugin.*)
                        // against the host. Bundled third-party classes (ktor, mcp-sdk,
                        // kotlin-logging, …) are the plugin's self-contained runtime; their
                        // internal linkage is not a host-contract concern and must not
                        // disable the plugin. In particular, libraries ship OPTIONAL adapter
                        // classes for backends the host doesn't bundle — e.g. kotlin-logging's
                        // io.github.oshai.kotlinlogging.logback.internal.LogbackLogEvent
                        // references ch.qos.logback.* which isn't present, so merely LOADING
                        // that (never-used) class throws NoClassDefFoundError. Skipping
                        // third-party classes here mirrors the member-ref scoping below.
                        if (!className.startsWith(OWN_CLASS_PREFIX)) continue

                        validating = className
                        errors += validateOwnClass(jar, entry, className, classLoader, jarClassNames)
                    }
                    classEntries.size
                }
            } catch (e: Exception) {
                val failure =
                    validating?.let { "$it: validation aborted - ${e.javaClass.simpleName}: ${e.message}" }
                        ?: "Failed to read JAR: ${e.message}"
                logger.error(
                    LogCategory.SYSTEM,
                    "JAR validation aborted",
                    mapOf(
                        "jarPath" to jarPath,
                        "stage" to (validating ?: "opening the JAR"),
                        "error" to (e.message ?: "unknown"),
                    ),
                )
                return ValidationResult(isCompatible = false, errors = errors + failure)
            }

        if (errors.isNotEmpty()) {
            logger.warn(
                LogCategory.SYSTEM,
                "Binary compatibility validation failed",
                mapOf(
                    "jarPath" to jarPath,
                    "errorCount" to errors.size,
                    "errors" to errors.take(5),
                ),
            )
        } else {
            logger.debug(
                LogCategory.SYSTEM,
                "Binary compatibility validation passed",
                mapOf(
                    "jarPath" to jarPath,
                    "classCount" to classCount,
                ),
            )
        }

        return ValidationResult(
            isCompatible = errors.isEmpty(),
            errors = errors,
        )
    }

    /**
     * Validate one of the plugin's own classes, returning what is wrong with it.
     *
     * The bytes are read first, and bounded, because the plugin's own class loader reads the
     * whole entry inside [Class.forName] to define the class. A cap applied after loading is too
     * late: zeros deflate about 1000:1, so a 573 KiB JAR carried a 576 MiB class and ran a 512 MiB
     * heap out of memory inside `forName`, before this function's own read was reached. Reading
     * one class at a time bounds the total; this bounds each one.
     *
     * An entry this cannot read is not yet grounds to refuse - the loader still gets its say, and
     * a class it can load but this cannot read is skipped, as before.
     */
    private fun validateOwnClass(
        jar: JarFile,
        entry: JarEntry,
        className: String,
        classLoader: ClassLoader,
        jarClassNames: Set<String>,
    ): List<String> {
        val bytes = readBoundedOrNull(jar, entry, className)
        val refusal =
            if (bytes != null && bytes.size > MAX_CLASS_BYTES) {
                "$className: class file is larger than $MAX_CLASS_BYTES bytes"
            } else {
                // When the bounded read failed (bytes == null), `forName` below reads the entry again
                // with no bound. That cannot materialise more than the cap: an entry `readNBytes`
                // cannot read fails inside `forName` at the same offset, before anything past it is
                // inflated. Refusing here instead would break this function's own rule, stated in
                // its KDoc: an entry this cannot read is not by itself grounds to refuse.
                loadFailure(className, classLoader)
            }
        return when {
            refusal != null -> listOf(refusal)
            bytes == null -> emptyList()
            else -> referenceErrors(bytes, className, classLoader, jarClassNames)
        }
    }

    /** Parse [bytes]' constant pool and verify every symbolic reference it holds. */
    private fun referenceErrors(
        bytes: ByteArray,
        className: String,
        classLoader: ClassLoader,
        jarClassNames: Set<String>,
    ): List<String> {
        val errors = mutableListOf<String>()
        try {
            for (ref in ConstantPoolParser.extractReferences(bytes)) {
                // Skip references to classes within the same JAR - they were
                // compiled together and are guaranteed to be consistent.
                if (ref.ownerClassName in jarClassNames) continue
                verifyReference(ref, classLoader, className, errors)
            }
        } catch (e: Exception) {
            // Malformed class file - not a compatibility issue per se, skip.
            logger.debug(
                LogCategory.SYSTEM,
                "Failed to parse constant pool",
                mapOf("className" to className, "error" to (e.message ?: "unknown")),
            )
        }
        return errors
    }

    internal fun verifyReference(
        ref: ConstantPoolParser.MemberRef,
        classLoader: ClassLoader,
        sourceClass: String,
        errors: MutableList<String>,
    ) {
        val ownerClass = resolveOwnerClass(ref, classLoader, sourceClass, errors) ?: return
        if (!ref.ownerClassName.startsWith(OWN_CLASS_PREFIX)) return

        when (ref.type) {
            ConstantPoolParser.RefType.METHOD,
            ConstantPoolParser.RefType.INTERFACE_METHOD,
            -> {
                if (ref.name == "<init>") {
                    verifyConstructor(ref, ownerClass, classLoader, sourceClass, errors)
                } else {
                    verifyMethod(ref, ownerClass, classLoader, sourceClass, errors)
                }
            }

            ConstantPoolParser.RefType.FIELD -> {
                verifyField(ref, ownerClass, sourceClass, errors)
            }
        }
    }

    internal fun extractCandidateMethods(
        clazz: Class<*>,
        methodName: String,
    ): List<String> =
        try {
            val candidates =
                (clazz.methods.asSequence() + clazz.declaredMethods.asSequence())
                    .filter { it.name == methodName }
                    .mapNotNull { method ->
                        try {
                            val params = method.parameterTypes.joinToString(",") { it.simpleName }
                            "${method.name}($params): ${method.returnType.simpleName}"
                        } catch (_: LinkageError) {
                            null
                        } catch (_: RuntimeException) {
                            null
                        }
                    }.distinct()
                    .toList()
            if (candidates.size > 8) {
                candidates.take(8) + "... (${candidates.size - 8} more)"
            } else {
                candidates
            }
        } catch (_: LinkageError) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }

    /**
     * `ai.rever.boss.plugin.runtime.*` classes ship in the OOP plugin
     * child JVM's classpath (via `boss-microkernel-runtime`), never the
     * host. References from a plugin's host-side class to those names
     * resolve in the child but not the host; treat them as soft-fail
     * during host-side validation so an OOP plugin's `register()` path
     * can statically reference `RemotePluginContext` (etc.) without
     * being rejected.
     *
     * Exposed `internal` for unit tests.
     */
    internal fun isSoftFailReference(ownerClassName: String): Boolean = ownerClassName.startsWith("ai.rever.boss.plugin.runtime.")

    /**
     * Check the class and its superclasses/interfaces for the method.
     *
     * Interfaces have no `Object` in their superclass chain, but every
     * Object method (toString/equals/hashCode/getClass/wait/notify*) is
     * callable on any interface reference at runtime via dynamic dispatch.
     * When [clazz] is an interface we additionally check `Object`'s
     * declared methods so legitimate `interfaceRef.toString()` calls
     * don't surface as binary-compat false positives.
     *
     * Exposed `internal` for unit tests.
     */
    internal fun hasMethod(
        clazz: Class<*>,
        name: String,
        paramTypes: Array<Class<*>>,
    ): Boolean {
        return try {
            clazz.getMethod(name, *paramTypes)
            true
        } catch (_: NoSuchMethodException) {
            // getMethod only finds public methods; try declared on the hierarchy
            var current: Class<*>? = clazz
            while (current != null) {
                try {
                    current.getDeclaredMethod(name, *paramTypes)
                    return true
                } catch (_: NoSuchMethodException) {
                    // continue
                }
                current = current.superclass
            }
            // Interfaces have no `Object` in their superclass chain (the walk
            // above terminates immediately), but at runtime every Object
            // method is callable on any interface ref via dynamic dispatch
            // (`list.toString()`, `list.equals(x)`, etc.). Check Object too
            // so legitimate interface-method-refs against Object's methods
            // don't flag as missing.
            if (clazz.isInterface) {
                try {
                    Any::class.java.getDeclaredMethod(name, *paramTypes)
                    return true
                } catch (_: NoSuchMethodException) {
                    // fall through
                }
            }
            false
        }
    }

    internal sealed interface FieldResolutionResult {
        data object Found : FieldResolutionResult

        data class TypeMismatch(
            val actualDescriptor: String,
        ) : FieldResolutionResult

        data object NotFound : FieldResolutionResult
    }

    /** Computes standard JVM type descriptor for a Class. */
    internal fun typeDescriptor(clazz: Class<*>): String =
        when {
            clazz == java.lang.Byte.TYPE -> "B"
            clazz == java.lang.Character.TYPE -> "C"
            clazz == java.lang.Double.TYPE -> "D"
            clazz == java.lang.Float.TYPE -> "F"
            clazz == java.lang.Integer.TYPE -> "I"
            clazz == java.lang.Long.TYPE -> "J"
            clazz == java.lang.Short.TYPE -> "S"
            clazz == java.lang.Boolean.TYPE -> "Z"
            clazz == java.lang.Void.TYPE -> "V"
            clazz.isArray -> "[${typeDescriptor(clazz.componentType)}"
            else -> "L${clazz.name.replace('.', '/')};"
        }

    /**
     * Extra context for field names whose absence has a known, non-obvious cause.
     *
     * `$stable` is Compose-synthetic. It is present on a class only when that class was compiled
     * by the Compose compiler, so a missing one almost always means two copies of the owning type
     * exist — one built with Compose and one without — and the non-Compose copy is the one winning
     * at runtime. That is exactly what happened when the host's bundled `plugin-logging` lacked
     * the field that `boss-plugin-api`'s copy of the same FQCN had: it disabled whole plugins with
     * `ComponentLogger.$stable: field not found` as the only clue, and cost hours to identify.
     *
     * Deliberately a message change and not a soft-fail: the plugin's `getstatic` sits in its own
     * `<clinit>`, so a genuinely missing field is a hard `NoSuchFieldError` at first touch.
     * Rejecting up front with a clear reason beats crashing later with an opaque one.
     *
     * Exposed internal for unit tests, like the sibling helpers in this file.
     */
    internal fun hintFor(fieldName: String): String =
        if (fieldName == "\u0024stable") {
            " (Compose stability field - the host and boss-plugin-api copies of this class have " +
                "diverged: one was compiled with the Compose compiler and one was not. Fix the " +
                "duplicated class, not the plugin.)"
        } else {
            ""
        }
}

private val logger get() = BinaryCompatibilityValidator.logger

/**
 * Up to [BinaryCompatibilityValidator.MAX_CLASS_BYTES] + 1 bytes of [entry], or null when it
 * cannot be read at all. One byte past the cap is enough to know the cap was passed, and never
 * more than that is held.
 */
private fun readBoundedOrNull(
    jar: JarFile,
    entry: JarEntry,
    className: String,
): ByteArray? {
    val failure =
        try {
            return jar.getInputStream(entry).use {
                it.readNBytes(BinaryCompatibilityValidator.MAX_CLASS_BYTES + 1)
            }
        } catch (e: IOException) {
            e
        } catch (e: SecurityException) {
            // A signed JAR whose entry does not match its digest.
            e
        }
    logger.debug(
        LogCategory.SYSTEM,
        "Failed to read class file",
        mapOf("className" to className, "error" to (failure.message ?: "unknown")),
    )
    return null
}

/** Why [className] cannot be loaded, or null when it can. */
private fun loadFailure(
    className: String,
    classLoader: ClassLoader,
): String? =
    try {
        Class.forName(className, false, classLoader)
        null
    } catch (e: LinkageError) {
        "$className: ${e.javaClass.simpleName} - ${e.message}"
    } catch (e: ClassNotFoundException) {
        "$className: ClassNotFoundException - ${e.message}"
    }

private fun resolveOwnerClass(
    ref: ConstantPoolParser.MemberRef,
    classLoader: ClassLoader,
    sourceClass: String,
    errors: MutableList<String>,
): Class<*>? {
    if (ref.ownerClassName.startsWith("[") || ref.ownerClassName.isEmpty()) return null

    return try {
        Class.forName(ref.ownerClassName, false, classLoader)
    } catch (e: LinkageError) {
        // Gate on the contract prefix, the way the ClassNotFoundException branch already does:
        // a bundled third-party owner whose supertype is absent on the host must not disable the
        // plugin, and `Class.forName` resolves supertypes, so this is reachable, not theoretical.
        if (BinaryCompatibilityValidator.isSoftFailReference(ref.ownerClassName)) {
            logger.debug(
                LogCategory.SYSTEM,
                "Soft-skipping runtime-package ref",
                mapOf(
                    "sourceClass" to sourceClass,
                    "ref" to ref.ownerClassName,
                    "error" to e.toString(),
                ),
            )
        } else if (ref.ownerClassName.startsWith(BinaryCompatibilityValidator.OWN_CLASS_PREFIX)) {
            errors.add("$sourceClass -> ${ref.ownerClassName}: ${e.javaClass.simpleName} - ${e.message}")
        } else {
            logger.debug(
                LogCategory.SYSTEM,
                "Soft-skipping non-contract LinkageError",
                mapOf(
                    "sourceClass" to sourceClass,
                    "ref" to ref.ownerClassName,
                    "error" to e.toString(),
                ),
            )
        }
        null
    } catch (e: ClassNotFoundException) {
        handleClassNotFound(ref, sourceClass, e, errors)
        null
    }
}

private fun handleClassNotFound(
    ref: ConstantPoolParser.MemberRef,
    sourceClass: String,
    exception: ClassNotFoundException,
    errors: MutableList<String>,
) {
    if (BinaryCompatibilityValidator.isSoftFailReference(ref.ownerClassName)) {
        logger.debug(
            LogCategory.SYSTEM,
            "Soft-skipping runtime-package ref",
            mapOf(
                "sourceClass" to sourceClass,
                "ref" to ref.ownerClassName,
                "error" to exception.toString(),
            ),
        )
    } else if (ref.ownerClassName.startsWith(BinaryCompatibilityValidator.OWN_CLASS_PREFIX)) {
        errors.add("$sourceClass -> ${ref.ownerClassName}: class not found")
    }
}

private fun extractCandidateConstructors(clazz: Class<*>): List<String> =
    try {
        val candidates =
            clazz.declaredConstructors
                .asSequence()
                .mapNotNull { ctor ->
                    try {
                        "<init>(${ctor.parameterTypes.joinToString(",") { it.simpleName }})"
                    } catch (_: LinkageError) {
                        null
                    } catch (_: RuntimeException) {
                        null
                    }
                }.distinct()
                .toList()
        if (candidates.size > 8) {
            candidates.take(8) + "... (${candidates.size - 8} more)"
        } else {
            candidates
        }
    } catch (_: LinkageError) {
        emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }

@Suppress("SpreadOperator")
private fun verifyConstructor(
    ref: ConstantPoolParser.MemberRef,
    ownerClass: Class<*>,
    classLoader: ClassLoader,
    sourceClass: String,
    errors: MutableList<String>,
) {
    val paramTypes = ref.parseParameterTypes(classLoader) ?: return
    val constructorExists =
        try {
            ownerClass.getDeclaredConstructor(*paramTypes)
            true
        } catch (_: NoSuchMethodException) {
            false
        }
    if (!constructorExists) {
        val available = extractCandidateConstructors(ownerClass)
        val candidatesSuffix =
            if (available.isNotEmpty()) {
                ", available constructors: [${available.joinToString("; ")}]"
            } else {
                ""
            }
        errors.add(
            "$sourceClass -> ${ref.ownerClassName}.<init>${ref.descriptor}: constructor not found" +
                candidatesSuffix,
        )
    }
}

private fun verifyMethod(
    ref: ConstantPoolParser.MemberRef,
    ownerClass: Class<*>,
    classLoader: ClassLoader,
    sourceClass: String,
    errors: MutableList<String>,
) {
    if (ref.name == "<clinit>") return
    val paramTypes = ref.parseParameterTypes(classLoader) ?: return
    if (!BinaryCompatibilityValidator.hasMethod(ownerClass, ref.name, paramTypes)) {
        val available = BinaryCompatibilityValidator.extractCandidateMethods(ownerClass, ref.name)
        val candidatesSuffix =
            if (available.isNotEmpty()) {
                ", available candidates: [${available.joinToString("; ")}]"
            } else {
                ""
            }
        errors.add(
            "$sourceClass -> ${ref.ownerClassName}.${ref.name}${ref.descriptor}: method not found" +
                candidatesSuffix,
        )
    }
}

private fun verifyField(
    ref: ConstantPoolParser.MemberRef,
    ownerClass: Class<*>,
    sourceClass: String,
    errors: MutableList<String>,
) {
    when (val result = resolveField(ownerClass, ref.name, ref.descriptor)) {
        BinaryCompatibilityValidator.FieldResolutionResult.Found -> {
            // Valid resolution per JVMS §5.4.3.2
        }

        is BinaryCompatibilityValidator.FieldResolutionResult.TypeMismatch -> {
            errors.add(
                "$sourceClass -> ${ref.ownerClassName}.${ref.name}:${ref.descriptor}: field type mismatch " +
                    "(expected ${ref.descriptor}, found ${result.actualDescriptor})" +
                    BinaryCompatibilityValidator.hintFor(ref.name),
            )
        }

        BinaryCompatibilityValidator.FieldResolutionResult.NotFound -> {
            errors.add(
                "$sourceClass -> ${ref.ownerClassName}.${ref.name}:${ref.descriptor}: field not found" +
                    BinaryCompatibilityValidator.hintFor(ref.name),
            )
        }
    }
}

/**
 * Resolves a field reference according to JVMS §5.4.3.2:
 * 1. Search for exact (name, descriptor) match in clazz, its superinterfaces, then superclasses.
 * 2. If missing, search for name-only match to diagnose field type mismatch.
 * 3. Otherwise, report NotFound.
 */
@Suppress("TooGenericExceptionCaught")
internal fun resolveField(
    clazz: Class<*>,
    name: String,
    descriptor: String,
): BinaryCompatibilityValidator.FieldResolutionResult =
    try {
        val exact = findFieldExact(clazz, name, descriptor)
        if (exact != null) {
            BinaryCompatibilityValidator.FieldResolutionResult.Found
        } else {
            val nameMatch = findFieldNameOnly(clazz, name)
            if (nameMatch != null) {
                BinaryCompatibilityValidator.FieldResolutionResult.TypeMismatch(
                    BinaryCompatibilityValidator.typeDescriptor(nameMatch.type),
                )
            } else {
                BinaryCompatibilityValidator.FieldResolutionResult.NotFound
            }
        }
    } catch (e: LinkageError) {
        // `declaredFields` resolves every declared field's TYPE on the class and the whole
        // hierarchy, so an owner with any field whose type is absent on this host throws
        // NoClassDefFoundError-class errors here - and the owner was loaded with
        // initialize=false through a plugin classloader, exactly where that happens.
        // `validate` only catches Exception, so an uncaught Error would escape plugin load;
        // report it as a miss with the linkage evidence instead of disabling by crash.
        logger.warn(
            LogCategory.SYSTEM,
            "Field resolution hit a linkage failure",
            mapOf("owner" to clazz.name, "field" to name, "error" to e.toString()),
        )
        BinaryCompatibilityValidator.FieldResolutionResult.NotFound
    } catch (e: RuntimeException) {
        logger.warn(
            LogCategory.SYSTEM,
            "Field resolution hit a runtime failure",
            mapOf("owner" to clazz.name, "field" to name, "error" to e.toString()),
        )
        BinaryCompatibilityValidator.FieldResolutionResult.NotFound
    }

/** Find a field matching both name and descriptor per JVMS §5.4.3.2. */
internal fun findFieldExact(
    clazz: Class<*>,
    name: String,
    descriptor: String,
): java.lang.reflect.Field? =
    clazz.declaredFields.firstOrNull { field ->
        field.name == name && (
            descriptor.isEmpty() ||
                BinaryCompatibilityValidator.typeDescriptor(field.type) == descriptor
        )
    } ?: clazz.interfaces.firstNotNullOfOrNull {
        findFieldExact(it, name, descriptor)
    } ?: clazz.superclass?.let {
        findFieldExact(it, name, descriptor)
    }

/** Find any field matching name only across class, interfaces, and superclasses. */
internal fun findFieldNameOnly(
    clazz: Class<*>,
    name: String,
): java.lang.reflect.Field? =
    clazz.declaredFields.firstOrNull { it.name == name }
        ?: clazz.interfaces.firstNotNullOfOrNull { findFieldNameOnly(it, name) }
        ?: clazz.superclass?.let { findFieldNameOnly(it, name) }

/**
 * Minimal JVM constant pool parser that extracts MethodRef, FieldRef,
 * and InterfaceMethodRef entries from class file bytes.
 *
 * Follows the JVM Class File Format specification (JVMS §4.4).
 * Only parses enough to resolve symbolic references — skips attributes,
 * methods, and other sections.
 */
internal object ConstantPoolParser {
    enum class RefType { METHOD, FIELD, INTERFACE_METHOD }

    data class MemberRef(
        val type: RefType,
        val ownerClassName: String,
        val name: String,
        val descriptor: String,
    ) {
        /**
         * Parse JVM method descriptor parameter types into Class objects.
         * Returns null if any type cannot be resolved (e.g. plugin-internal types).
         */
        fun parseParameterTypes(classLoader: ClassLoader): Array<Class<*>>? =
            try {
                parseDescriptorParams(descriptor, classLoader)
            } catch (_: ClassNotFoundException) {
                null
            }
    }

    // Constant pool tag values (JVMS §4.4)
    private const val TAG_UTF8 = 1
    private const val TAG_INTEGER = 3
    private const val TAG_FLOAT = 4
    private const val TAG_LONG = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_CLASS = 7
    private const val TAG_STRING = 8
    private const val TAG_FIELDREF = 9
    private const val TAG_METHODREF = 10
    private const val TAG_INTERFACE_METHODREF = 11
    private const val TAG_NAME_AND_TYPE = 12
    private const val TAG_METHOD_HANDLE = 15
    private const val TAG_METHOD_TYPE = 16
    private const val TAG_DYNAMIC = 17
    private const val TAG_INVOKE_DYNAMIC = 18
    private const val TAG_MODULE = 19
    private const val TAG_PACKAGE = 20

    fun extractReferences(classBytes: ByteArray): List<MemberRef> {
        val dis = DataInputStream(classBytes.inputStream())

        // Magic number
        val magic = dis.readInt()
        if (magic != 0xCAFEBABE.toInt()) return emptyList()

        // Version
        dis.readUnsignedShort() // minor
        dis.readUnsignedShort() // major

        // Constant pool
        val cpCount = dis.readUnsignedShort()
        val utf8s = mutableMapOf<Int, String>()
        val classInfos = mutableMapOf<Int, Int>() // index -> nameIndex
        val nameAndTypes = mutableMapOf<Int, Pair<Int, Int>>() // index -> (nameIndex, descriptorIndex)
        val memberRefs = mutableListOf<Triple<RefType, Int, Int>>() // (type, classIndex, natIndex)

        var i = 1
        while (i < cpCount) {
            val tag = dis.readUnsignedByte()
            when (tag) {
                TAG_UTF8 -> {
                    utf8s[i] = dis.readUTF()
                }

                TAG_INTEGER, TAG_FLOAT -> {
                    dis.readInt()
                }

                TAG_LONG, TAG_DOUBLE -> {
                    dis.readLong()
                    i++ // 8-byte constants take two slots
                }

                TAG_CLASS -> {
                    classInfos[i] = dis.readUnsignedShort()
                }

                TAG_STRING -> {
                    dis.readUnsignedShort()
                }

                TAG_FIELDREF -> {
                    val classIdx = dis.readUnsignedShort()
                    val natIdx = dis.readUnsignedShort()
                    memberRefs.add(Triple(RefType.FIELD, classIdx, natIdx))
                }

                TAG_METHODREF -> {
                    val classIdx = dis.readUnsignedShort()
                    val natIdx = dis.readUnsignedShort()
                    memberRefs.add(Triple(RefType.METHOD, classIdx, natIdx))
                }

                TAG_INTERFACE_METHODREF -> {
                    val classIdx = dis.readUnsignedShort()
                    val natIdx = dis.readUnsignedShort()
                    memberRefs.add(Triple(RefType.INTERFACE_METHOD, classIdx, natIdx))
                }

                TAG_NAME_AND_TYPE -> {
                    val nameIdx = dis.readUnsignedShort()
                    val descIdx = dis.readUnsignedShort()
                    nameAndTypes[i] = nameIdx to descIdx
                }

                TAG_METHOD_HANDLE -> {
                    dis.readUnsignedByte()
                    dis.readUnsignedShort()
                }

                TAG_METHOD_TYPE -> {
                    dis.readUnsignedShort()
                }

                TAG_DYNAMIC, TAG_INVOKE_DYNAMIC -> {
                    dis.readUnsignedShort()
                    dis.readUnsignedShort()
                }

                TAG_MODULE, TAG_PACKAGE -> {
                    dis.readUnsignedShort()
                }

                else -> {
                    return emptyList()
                } // Unknown tag, bail out safely
            }
            i++
        }

        // Resolve references
        return memberRefs.mapNotNull { (refType, classIdx, natIdx) ->
            val classNameIdx = classInfos[classIdx] ?: return@mapNotNull null
            val ownerInternal = utf8s[classNameIdx] ?: return@mapNotNull null
            val ownerClassName = ownerInternal.replace('/', '.')

            val (nameIdx, descIdx) = nameAndTypes[natIdx] ?: return@mapNotNull null
            val name = utf8s[nameIdx] ?: return@mapNotNull null
            val descriptor = utf8s[descIdx] ?: return@mapNotNull null

            MemberRef(refType, ownerClassName, name, descriptor)
        }
    }

    /**
     * Parse JVM method descriptor parameter section into Class objects.
     * e.g. "(Ljava/lang/String;IZ)V" -> [String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType]
     */
    fun parseDescriptorParams(
        descriptor: String,
        classLoader: ClassLoader,
    ): Array<Class<*>> {
        val params = mutableListOf<Class<*>>()
        val paramSection = descriptor.substringAfter('(').substringBefore(')')
        var idx = 0
        while (idx < paramSection.length) {
            when (paramSection[idx]) {
                'B' -> {
                    params.add(Byte::class.javaPrimitiveType!!)
                    idx++
                }

                'C' -> {
                    params.add(Char::class.javaPrimitiveType!!)
                    idx++
                }

                'D' -> {
                    params.add(Double::class.javaPrimitiveType!!)
                    idx++
                }

                'F' -> {
                    params.add(Float::class.javaPrimitiveType!!)
                    idx++
                }

                'I' -> {
                    params.add(Int::class.javaPrimitiveType!!)
                    idx++
                }

                'J' -> {
                    params.add(Long::class.javaPrimitiveType!!)
                    idx++
                }

                'S' -> {
                    params.add(Short::class.javaPrimitiveType!!)
                    idx++
                }

                'Z' -> {
                    params.add(Boolean::class.javaPrimitiveType!!)
                    idx++
                }

                'V' -> {
                    params.add(Void::class.javaPrimitiveType!!)
                    idx++
                }

                'L' -> {
                    val end = paramSection.indexOf(';', idx)
                    val className = paramSection.substring(idx + 1, end).replace('/', '.')
                    params.add(Class.forName(className, false, classLoader))
                    idx = end + 1
                }

                '[' -> {
                    // Array type — find the element type and use Class.forName with JVM array notation
                    val start = idx
                    while (idx < paramSection.length && paramSection[idx] == '[') idx++
                    val arrayDesc =
                        if (paramSection[idx] == 'L') {
                            val end = paramSection.indexOf(';', idx)
                            val desc = paramSection.substring(start, end + 1)
                            idx = end + 1
                            desc
                        } else {
                            val desc = paramSection.substring(start, idx + 1)
                            idx++
                            desc
                        }
                    params.add(Class.forName(arrayDesc.replace('/', '.'), false, classLoader))
                }

                else -> {
                    idx++
                } // Skip unknown
            }
        }
        return params.toTypedArray()
    }
}
