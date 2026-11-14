package dev.onionvault

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test of the boundary between Java and the native code.
 *
 * The methods of [OvNative] are implemented in the native library of Trap.
 * The loader throws an `UnsatisfiedLinkError` when the library is not in
 * the process, and when a method is renamed on one side only, and every
 * call of the engine fails then: the application dies at once, on the
 * first call, with no window and no message.
 *
 * The test therefore compares the two sides of the boundary: every method
 * that Java declares as `external` must have a matching export in the
 * Rust code, with exactly the name that the JNI specification derives
 * from it.
 */
class OvNativeTest {

    @Test
    fun every_external_method_has_a_matching_native_export() {
        val declared = externalMethods()
        assertTrue(
            "the native bridge of Trap must declare methods",
            declared.isNotEmpty(),
        )

        val exported = nativeExports()
        assertTrue(
            "the Rust library of Trap must export native methods",
            exported.isNotEmpty(),
        )

        val missing = declared.filterNot { it in exported }.sorted()
        assertEquals(
            "these methods of the native bridge have no export in the Rust " +
                "library: ${missing.joinToString()}",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun the_library_of_the_engine_is_named_after_the_crate() {
        // The name of the library is the name of the file that cargo-ndk
        // writes into the directory of the shared objects of the APK; the
        // loader of Android looks the file up under exactly this name.
        assertEquals("onion_vault", OvNativeLibrary.NAME)
    }

    /**
     * The names of the JNI symbols of the methods that Java declares.
     */
    private fun externalMethods(): Set<String> {
        val source = sourceFile("kotlin/dev/onionvault/OvNative.kt")
        val text = source.readText()
        // Every `external fun name(...)` is a method of the native bridge.
        val pattern = Regex("""external\s+fun\s+(\w+)\s*\(""")
        return pattern.findAll(text)
            .map { it.groupValues[1] }
            .map { "Java_dev_onionvault_OvNative_$it" }
            .toSet()
    }

    /**
     * The names of the JNI symbols that the Rust library exports.
     */
    private fun nativeExports(): Set<String> {
        val source = sourceFile("src/lib.rs")
        val text = source.readText()
        // Every `#[no_mangle] pub extern "system" fn Java_...` is an export.
        val pattern = Regex("""fn\s+(Java_\w+)\s*\(""")
        return pattern.findAll(text).map { it.groupValues[1] }.toSet()
    }

    /**
     * The file of the repository that holds the given path.
     *
     * The tests run with the directory of the module as the working
     * directory; the paths are relative to the root of the repository.
     */
    private fun sourceFile(path: String): File {
        val module = File(".").absoluteFile
        // `android/app` is the directory of the module; the root of the
        // repository is two levels above it.
        val root = module.parentFile?.parentFile ?: module
        val file = File(root, path)
        assertTrue("the file ${file.path} has to exist", file.isFile)
        return file
    }
}

/**
 * The name of the native library of Trap.
 *
 * It lives here so that the test can check it without loading the library
 * itself: on the JVM of the tests there is no library for the host machine.
 */
object OvNativeLibrary {
    /** The name of the shared object that carries the engine. */
    const val NAME = "onion_vault"
}
