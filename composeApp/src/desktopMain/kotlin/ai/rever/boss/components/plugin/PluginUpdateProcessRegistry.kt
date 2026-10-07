package ai.rever.boss.components.plugin

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.management.InstanceAlreadyExistsException
import javax.management.ObjectName
import javax.management.RuntimeErrorException
import javax.management.modelmbean.ModelMBeanInfoSupport
import javax.management.modelmbean.ModelMBeanOperationInfo
import javax.management.modelmbean.RequiredModelMBean

/** JDK-only shared state: no system-property pollution or plugin-classloader retention. */
internal object PluginUpdateProcessRegistry {
    private val name = ObjectName("boss.plugins:type=UpdateLeaseRegistry,protocol=2")

    fun owners(): ConcurrentHashMap<String, Any> = owners(name)

    @Suppress("UNCHECKED_CAST")
    fun owners(name: ObjectName): ConcurrentHashMap<String, Any> {
        val server = ManagementFactory.getPlatformMBeanServer()
        if (!server.isRegistered(name)) {
            val operation =
                ModelMBeanOperationInfo(
                    "Get process lease owners",
                    AtomicReference::class.java.getMethod("get"),
                )
            val info =
                ModelMBeanInfoSupport(
                    RequiredModelMBean::class.java.name,
                    "Process lease registry protocol 2",
                    null,
                    null,
                    arrayOf(operation),
                    null,
                )
            val bean = RequiredModelMBean(info)
            bean.setManagedResource(AtomicReference(ConcurrentHashMap<String, Any>()), "ObjectReference")
            try {
                server.registerMBean(bean, name)
            } catch (_: InstanceAlreadyExistsException) {
                // Another classloader won registration; use its registry without replacing it.
            }
        }
        val info = server.getMBeanInfo(name)
        val operation = info.operations.singleOrNull()
        check(
            info.className == RequiredModelMBean::class.java.name &&
                info.attributes.isEmpty() && operation?.name == "get" &&
                operation.signature.isEmpty() && operation.returnType == Any::class.java.name,
        ) { "Incompatible plugin update process registry" }
        val owners =
            try {
                server.invoke(name, "get", null, null)
            } catch (fatal: RuntimeErrorException) {
                throw fatal.targetError
            }
        check(owners?.javaClass == ConcurrentHashMap::class.java) { "Invalid plugin update process registry" }
        return owners as ConcurrentHashMap<String, Any>
    }
}
