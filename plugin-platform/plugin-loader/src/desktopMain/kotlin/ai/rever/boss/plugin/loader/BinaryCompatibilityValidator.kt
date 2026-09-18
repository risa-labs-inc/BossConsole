package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.io.DataInputStream
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

        val classEntries =
            try {
                JarFile(jarPath).use { jar ->
                    jar
                        .entries()
                        .asSequence()
                        .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                        .map { entry ->
                            val className = entry.name.removeSuffix(".class").replace('/', '.')
                            val bytes = jar.getInputStream(entry).use { it.readBytes() }
                            className to bytes
                        }.toList()
                }
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Failed to read JAR for validation",
                    mapOf(
                        "jarPath" to jarPath,
                        "error" to (e.message ?: "unknown"),
                    ),
                )
                return ValidationResult(
                    isCompatible = false,
                    errors =
                        listOf(
                            "Failed to read JAR: ${e.message}",
                        ),
                )
            }

        // Collect all class names in this JAR — references between them are
        // self-consistent (compiled together) and don't need cross-validation.
        val jarClassNames = classEntries.map { it.first }.toSet()

        for ((className, bytes) in classEntries) {
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
            if (!className.startsWith("ai.rever.boss.plugin.")) continue

            // First, ensure the class itself can be loaded
            try {
                Class.forName(className, false, classLoader)
            } catch (e: LinkageError) {
                errors.add("$className: ${e.javaClass.simpleName} - ${e.message}")
                continue
            } catch (e: ClassNotFoundException) {
                errors.add("$className: ClassNotFoundException - ${e.message}")
                continue
            }

            // Parse constant pool and verify all symbolic references
            try {
                val refs = ConstantPoolParser.extractReferences(bytes)
                for (ref in refs) {
                    // Skip references to classes within the same JAR — they were
                    // compiled together and are guaranteed to be consistent.
                    if (ref.ownerClassName in jarClassNames) continue
                    verifyReference(ref, classLoader, className, errors)
                }
            } catch (e: Exception) {
                // Malformed class file — not a compatibility issue per se, skip
                logger.debug(
                    LogCategory.SYSTEM,
                    "Failed to parse constant pool",
                    mapOf(
                        "className" to className,
                        "error" to (e.message ?: "unknown"),
                    ),
                )
            }
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
                    "classCount" to classEntries.size,
                ),
            )
        }

        return ValidationResult(
            isCompatible = errors.isEmpty(),
            errors = errors,
        )
    }

    internal fun verifyReference(
        ref: ConstantPoolParser.MemberRef,
        classLoader: ClassLoader,
        sourceClass: String,
        errors: MutableList<String>,
    ) {
        val ownerClass = resolveOwnerClass(ref, classLoader, sourceClass, errors) ?: return
        if (!ref.ownerClassName.startsWith("ai.rever.boss.plugin.")) return

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

    internal fun extractCandidateConstructors(clazz: Class<*>): List<String> =
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

    sealed interface FieldResolutionResult {
        data class Found(
            val field: java.lang.reflect.Field,
        ) : FieldResolutionResult

        data class TypeMismatch(
            val actualDescriptor: String,
        ) : FieldResolutionResult

        data object NotFound : FieldResolutionResult
    }

    /** Find a field matching both name and descriptor per JVMS §5.4.3.2. */
    internal fun findFieldExact(
        clazz: Class<*>,
        name: String,
        descriptor: String,
    ): java.lang.reflect.Field? {
        for (field in clazz.declaredFields) {
            if (field.name == name && (descriptor.isEmpty() || typeDescriptor(field.type) == descriptor)) {
                return field
            }
        }
        for (iface in clazz.interfaces) {
            val found = findFieldExact(iface, name, descriptor)
            if (found != null) return found
        }
        val superclass = clazz.superclass
        if (superclass != null) {
            val found = findFieldExact(superclass, name, descriptor)
            if (found != null) return found
        }
        return null
    }

    /** Find any field matching name only across class, interfaces, and superclasses. */
    internal fun findFieldNameOnly(
        clazz: Class<*>,
        name: String,
    ): java.lang.reflect.Field? {
        for (field in clazz.declaredFields) {
            if (field.name == name) return field
        }
        for (iface in clazz.interfaces) {
            val found = findFieldNameOnly(iface, name)
            if (found != null) return found
        }
        val superclass = clazz.superclass
        if (superclass != null) {
            val found = findFieldNameOnly(superclass, name)
            if (found != null) return found
        }
        return null
    }

    /**
     * Resolves a field reference according to JVMS §5.4.3.2:
     * 1. Search for exact (name, descriptor) match in clazz, its superinterfaces, then superclasses.
     * 2. If missing, search for name-only match to diagnose field type mismatch.
     * 3. Otherwise, report NotFound.
     */
    internal fun resolveField(
        clazz: Class<*>,
        name: String,
        descriptor: String,
    ): FieldResolutionResult {
        val exact = findFieldExact(clazz, name, descriptor)
        if (exact != null) {
            return FieldResolutionResult.Found(exact)
        }
        val nameMatch = findFieldNameOnly(clazz, name)
        if (nameMatch != null) {
            return FieldResolutionResult.TypeMismatch(typeDescriptor(nameMatch.type))
        }
        return FieldResolutionResult.NotFound
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
        errors.add("$sourceClass -> ${ref.ownerClassName}: ${e.javaClass.simpleName} - ${e.message}")
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
    } else if (ref.ownerClassName.startsWith("ai.rever.boss.plugin.")) {
        errors.add("$sourceClass -> ${ref.ownerClassName}: class not found")
    }
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
        val available = BinaryCompatibilityValidator.extractCandidateConstructors(ownerClass)
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
    when (val result = BinaryCompatibilityValidator.resolveField(ownerClass, ref.name, ref.descriptor)) {
        is BinaryCompatibilityValidator.FieldResolutionResult.Found -> {
            // Valid resolution per JVMS §5.4.3.2
        }

        is BinaryCompatibilityValidator.FieldResolutionResult.TypeMismatch -> {
            errors.add(
                "$sourceClass -> ${ref.ownerClassName}.${ref.name}:${ref.descriptor}: field type mismatch " +
                    "(expected ${ref.descriptor}, found ${result.actualDescriptor})" +
                    BinaryCompatibilityValidator.hintFor(ref.name),
            )
        }

        is BinaryCompatibilityValidator.FieldResolutionResult.NotFound -> {
            errors.add(
                "$sourceClass -> ${ref.ownerClassName}.${ref.name}:${ref.descriptor}: field not found" +
                    BinaryCompatibilityValidator.hintFor(ref.name),
            )
        }
    }
}

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
