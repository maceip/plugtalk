package dev.onionvault

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies 1:1 JNI symbol parity between [OvNative]'s `external fun` declarations
 * and `src/lib.rs`'s `#[no_mangle] pub extern "system" fn Java_dev_onionvault_OvNative_*`
 * exports so no signature mismatch can cause an `UnsatisfiedLinkError` at runtime.
 */
class OvNativeTest {

    @Test
    fun every_external_method_has_a_matching_native_export() {
        val declared = externalMethods()
        assertTrue(
            "OvNative must declare JNI external methods",
            declared.isNotEmpty(),
        )

        val exported = nativeExports()
        assertTrue(
            "src/lib.rs must export JNI functions",
            exported.isNotEmpty(),
        )

        val missing = declared.filterNot { it in exported }.sorted()
        assertEquals(
            "OvNative methods missing from src/lib.rs exports: ${missing.joinToString()}",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun the_library_of_the_engine_is_named_after_the_crate() {
        assertEquals("onion_vault", OvNativeLibrary.NAME)
        assertEquals("onion_vault", OvNative.LIBRARY_NAME)
    }

    private fun externalMethods(): Set<String> {
        val source = sourceFile("android/app/src/main/kotlin/dev/onionvault/OvNative.kt")
        val text = source.readText()
        val pattern = Regex("""external\s+fun\s+(\w+)\s*\(""")
        return pattern.findAll(text)
            .map { it.groupValues[1] }
            .map { "Java_dev_onionvault_OvNative_$it" }
            .toSet()
    }

    private fun nativeExports(): Set<String> {
        val source = sourceFile("src/lib.rs")
        val text = source.readText()
        val pattern = Regex("""fn\s+(Java_\w+)\s*\(""")
        return pattern.findAll(text).map { it.groupValues[1] }.toSet()
    }

    private fun sourceFile(path: String): File {
        val cwd = File(".").absoluteFile
        val root = when {
            File(cwd, path).isFile -> cwd
            cwd.parentFile != null && File(cwd.parentFile, path).isFile -> cwd.parentFile
            cwd.parentFile?.parentFile != null && File(cwd.parentFile.parentFile, path).isFile ->
                cwd.parentFile.parentFile
            else -> cwd.parentFile?.parentFile ?: cwd
        }
        val file = File(root, path)
        assertTrue("Expected repository file to exist: ${file.path}", file.isFile)
        return file
    }
}

object OvNativeLibrary {
    const val NAME = "onion_vault"
}
