package ai.rever.boss.plugin.launchpad.scan

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/** A method or field a class refers to: the owner's internal name, the member name and its descriptor. */
internal data class MemberRef(
    val owner: String,
    val name: String,
    val descriptor: String,
)

/**
 * What a class file's constant pool says the class refers to.
 *
 * This is what the class MENTIONS, not what it does: a method reference means some code may call it, nothing
 * here proves a call is reached, and nothing here sees code that is loaded or built at run time.
 */
@Suppress("LongParameterList")
internal class ClassInfo(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val methodRefs: Set<MemberRef>,
    val fieldRefs: Set<MemberRef>,
    val classRefs: Set<String>,
    val strings: Set<String>,
) {
    /** Every type the class names, as a class constant or inside a member descriptor. */
    val mentionedTypes: Set<String> by lazy {
        val out = HashSet<String>(classRefs)
        (methodRefs + fieldRefs).forEach { ref -> out += typesIn(ref.descriptor) }
        out
    }
}

/** A class file that could not be read. The reason never echoes bytes from the file. */
internal class MalformedClassException(
    reason: String,
) : Exception(reason)

private val typeRef = Regex("L([^;]+);")

/** The class types named in a method or field descriptor, in internal form (`java/lang/String`). */
internal fun typesIn(descriptor: String): List<String> = typeRef.findAll(descriptor).map { it.groupValues[1] }.toList()

/**
 * Reads the constant pool of a `.class` file and nothing else.
 *
 * The constant pool already names every class, method and field a class can reach directly, it is a flat table,
 * and reading only it keeps the parser small enough to check by eye. The input is whatever a stranger put in a
 * JAR, so every index and length is bounds-checked: a truncated file, a pool that lies about its own size and a
 * string longer than the file all end in [MalformedClassException], never a crash or an unbounded allocation.
 */
internal object ConstantPoolReader {
    private const val MAGIC = 0xCAFEBABE.toInt()
    private const val MAX_STRINGS = 4096
    private const val MAX_STRING_CHARS = 512
    private const val U8 = 0xFF
    private const val MIN_HEADER = 10

    private const val UTF8 = 1
    private const val INTEGER = 3
    private const val FLOAT = 4
    private const val LONG = 5
    private const val DOUBLE = 6
    private const val CLASS = 7
    private const val STRING = 8
    private const val FIELDREF = 9
    private const val METHODREF = 10
    private const val INTERFACE_METHODREF = 11
    private const val NAME_AND_TYPE = 12
    private const val METHOD_HANDLE = 15
    private const val METHOD_TYPE = 16
    private const val DYNAMIC = 17
    private const val INVOKE_DYNAMIC = 18
    private const val MODULE = 19
    private const val PACKAGE = 20

    fun read(bytes: ByteArray): ClassInfo =
        try {
            parse(ByteBuffer.wrap(bytes))
        } catch (_: BufferUnderflowException) {
            throw MalformedClassException("class file ends inside the constant pool")
        } catch (_: IndexOutOfBoundsException) {
            throw MalformedClassException("constant pool index out of range")
        }

    private class Pool(
        size: Int,
    ) {
        val tag = IntArray(size)
        val a = IntArray(size)
        val b = IntArray(size)
        val utf8 = arrayOfNulls<String>(size)
    }

    @Suppress("ThrowsCount")
    private fun parse(buf: ByteBuffer): ClassInfo {
        if (buf.remaining() < MIN_HEADER || buf.int != MAGIC) throw MalformedClassException("not a class file")
        buf.short // minor
        buf.short // major
        val count = buf.u16()
        if (count < 1) throw MalformedClassException("empty constant pool")
        val pool = Pool(count)
        readPool(buf, pool, count)

        buf.short // access flags
        val thisName = className(pool, buf.u16()) ?: throw MalformedClassException("no class name")
        val superIdx = buf.u16()
        val superName = if (superIdx == 0) null else className(pool, superIdx)
        val interfaces = (0 until buf.u16()).mapNotNull { className(pool, buf.u16()) }
        return collect(pool, count, thisName, superName, interfaces)
    }

    private fun readPool(
        buf: ByteBuffer,
        pool: Pool,
        count: Int,
    ) {
        var i = 1
        while (i < count) {
            val tag = buf.get().toInt() and U8
            pool.tag[i] = tag
            when (tag) {
                UTF8 -> {
                    pool.utf8[i] = readUtf8(buf)
                }

                INTEGER, FLOAT -> {
                    buf.int
                }

                LONG, DOUBLE -> {
                    buf.long
                    i++ // a long or double takes two pool slots
                }

                CLASS, STRING, METHOD_TYPE, MODULE, PACKAGE -> {
                    pool.a[i] = buf.u16()
                }

                FIELDREF, METHODREF, INTERFACE_METHODREF, NAME_AND_TYPE, DYNAMIC, INVOKE_DYNAMIC -> {
                    pool.a[i] = buf.u16()
                    pool.b[i] = buf.u16()
                }

                METHOD_HANDLE -> {
                    buf.get()
                    pool.a[i] = buf.u16()
                }

                else -> {
                    throw MalformedClassException("unknown constant pool tag $tag")
                }
            }
            i++
        }
    }

    private fun readUtf8(buf: ByteBuffer): String {
        val len = buf.u16()
        if (len > buf.remaining()) throw MalformedClassException("string longer than the file")
        val raw = ByteArray(len)
        buf.get(raw)
        return String(raw, Charsets.UTF_8)
    }

    private class Collected {
        val methods = LinkedHashSet<MemberRef>()
        val fields = LinkedHashSet<MemberRef>()
        val classes = LinkedHashSet<String>()
        val strings = LinkedHashSet<String>()
    }

    private fun collect(
        pool: Pool,
        count: Int,
        name: String,
        superName: String?,
        interfaces: List<String>,
    ): ClassInfo {
        val out = Collected()
        for (i in 1 until count) collectOne(pool, i, out)
        return ClassInfo(name, superName, interfaces, out.methods, out.fields, out.classes, out.strings)
    }

    private fun collectOne(
        pool: Pool,
        i: Int,
        out: Collected,
    ) {
        when (pool.tag[i]) {
            CLASS -> {
                className(pool, i)?.let { out.classes += if (it.startsWith("[")) typesIn(it) else listOf(it) }
            }

            METHODREF, INTERFACE_METHODREF -> {
                memberRef(pool, i)?.let { out.methods += it }
            }

            FIELDREF -> {
                memberRef(pool, i)?.let { out.fields += it }
            }

            STRING -> {
                if (out.strings.size < MAX_STRINGS) {
                    utf8(pool, pool.a[i])?.let { out.strings += it.take(MAX_STRING_CHARS) }
                }
            }
        }
    }

    private fun utf8(
        pool: Pool,
        index: Int,
    ): String? = if (index in 1 until pool.utf8.size) pool.utf8[index] else null

    private fun className(
        pool: Pool,
        index: Int,
    ): String? = if (index in 1 until pool.tag.size && pool.tag[index] == CLASS) utf8(pool, pool.a[index]) else null

    private fun memberRef(
        pool: Pool,
        index: Int,
    ): MemberRef? {
        val owner = className(pool, pool.a[index])
        val nat = pool.b[index]
        val isNameAndType = nat in 1 until pool.tag.size && pool.tag[nat] == NAME_AND_TYPE
        val name = if (isNameAndType) utf8(pool, pool.a[nat]) else null
        val descriptor = if (isNameAndType) utf8(pool, pool.b[nat]) else null
        return if (owner != null && name != null && descriptor != null) MemberRef(owner, name, descriptor) else null
    }
}

private const val U16_MASK = 0xFFFF

private fun ByteBuffer.u16(): Int = short.toInt() and U16_MASK
