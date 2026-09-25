package ai.rever.boss.files

/** Runs observable filesystem checks in the compiled binary as well as the JVM. */
fun main() {
    val tests = NativeDirectoryTest()
    tests.`platform temporary directories work without caller canonicalization`()
    tests.`Windows junction entries can be removed without traversing their target`()
    tests.`ordinary files metadata enumeration and atomic non-overwriting moves work`()
    tests.`held directory cannot be redirected by replacing its pathname with a link`()
    tests.`links cannot be opened but can be inspected renamed and unlinked`()
    val operations = NativeDirectoryOperationsTest()
    operations.`ordinary writable opens preserve content and private defaults remain private`()
    operations.`copy fallback preserves files timestamps empty directories and symbolic entries`()
    operations.`shared watch session delivers each directory events independently`()
    operations.`watch observes held directory after pathname replacement and precise file changes`()
}
