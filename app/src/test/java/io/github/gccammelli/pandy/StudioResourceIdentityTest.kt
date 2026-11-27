package io.github.gccammelli.pandy

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StudioResourceIdentityTest {
    private val zip = "ab".repeat(32)
    private val tree = "cd".repeat(32)
    private fun orca() = JSONObject().put("version", 1).put("engine_id", "orca").put("profile_root", "pandy/editor")
        .put("sha256", zip).put("resource_version", zip)
    @Test fun orcaRequiresItsOwnMatchingVersionedArchive() {
        assertEquals(zip, studioResourceVersion("orca", orca(), zip, zip))
        for (bad in listOf(orca().put("engine_id", "bambu"), orca().put("profile_root", "resources"),
            orca().put("resource_version", ""), orca().put("resource_version", tree), orca().put("sha256", tree))) {
            assertFailsWith<IllegalArgumentException> { studioResourceVersion("orca", bad, zip, null) }
        }
        assertFailsWith<IllegalArgumentException> { studioResourceVersion("orca", orca(), zip, tree) }
    }
    @Test fun legacyBambuKeepsItsSeparateTreeDigestAndCannotBeUsedAsOrca() {
        val old = JSONObject().put("version", 1).put("sha256", zip)
        assertEquals(tree, studioResourceVersion("bambu", old, zip, tree))
        assertNull(studioResourceVersion("bambu", old, zip, null))
        assertFailsWith<IllegalArgumentException> { studioResourceVersion("orca", old, zip, tree) }
        assertFailsWith<IllegalArgumentException> { studioResourceVersion("bambu", old, tree, null) }
    }

    private fun makeTree(engineId: String): File {
        val dir = Files.createTempDirectory("studio-tree-$engineId").toFile()
        val root = if (engineId == "orca") "pandy/editor" else "resources"
        fun write(rel: String, text: String) {
            File(dir, rel).apply { parentFile?.mkdirs(); writeText(text) }
        }
        write(".complete", zip)
        write("pandy/schema.json", "{}")
        write("pandy/layout.json", """{"process":[],"filament":[]}""")
        write("$root/profiles/BBL.json", """{"machine_list":[{"name":"M","sub_path":"machine/m.json"}],"process_list":[{"name":"P","sub_path":"process/p.json"}],"filament_list":[{"name":"F","sub_path":"filament/f.json"}]}""")
        write("$root/profiles/BBL/machine/m.json", """{"name":"M","instantiation":"true"}""")
        write("$root/profiles/BBL/process/p.json", """{"name":"P","instantiation":"true"}""")
        write("$root/profiles/BBL/filament/f.json", """{"name":"F","instantiation":"true"}""")
        return dir
    }

    @Test fun profileTreeCompletenessDetectsEvictedOrCorruptFiles() {
        for (engine in listOf("bambu", "orca")) {
            val dir = makeTree(engine)
            try {
                assertTrue(studioProfileTreeComplete(dir, engine))
                val root = if (engine == "orca") "pandy/editor" else "resources"
                // Simulates Android evicting a machine preset while .complete and BBL.json survive.
                assertTrue(File(dir, "$root/profiles/BBL/machine/m.json").delete())
                assertFalse(studioProfileTreeComplete(dir, engine))
            } finally {
                dir.deleteRecursively()
            }
        }
        val missingSchema = makeTree("bambu")
        try {
            assertTrue(File(missingSchema, "pandy/schema.json").delete())
            assertFalse(studioProfileTreeComplete(missingSchema, "bambu"))
        } finally { missingSchema.deleteRecursively() }
        val traversal = makeTree("bambu")
        try {
            File(traversal, "resources/profiles/BBL.json").writeText("""{"machine_list":[{"name":"M","sub_path":"../escape.json"}]}""")
            File(traversal, "resources/profiles/escape.json").writeText("{}")
            assertFalse(studioProfileTreeComplete(traversal, "bambu"))
        } finally { traversal.deleteRecursively() }
        val emptyMachines = makeTree("bambu")
        try {
            File(emptyMachines, "resources/profiles/BBL.json").writeText("""{"machine_list":[],"process_list":[],"filament_list":[]}""")
            assertFalse(studioProfileTreeComplete(emptyMachines, "bambu"))
        } finally { emptyMachines.deleteRecursively() }
        val corruptMachine = makeTree("bambu")
        try {
            File(corruptMachine, "resources/profiles/BBL/machine/m.json").writeText("{truncated")
            assertFalse(studioProfileTreeComplete(corruptMachine, "bambu"))
        } finally { corruptMachine.deleteRecursively() }
        val zeroByteProcess = makeTree("bambu")
        try {
            File(zeroByteProcess, "resources/profiles/BBL/process/p.json").writeText("")
            assertFalse(studioProfileTreeComplete(zeroByteProcess, "bambu"))
        } finally { zeroByteProcess.deleteRecursively() }
        val nonInstantiable = makeTree("bambu")
        try {
            File(nonInstantiable, "resources/profiles/BBL/machine/m.json").writeText("""{"name":"M","instantiation":"false"}""")
            assertFalse(studioProfileTreeComplete(nonInstantiable, "bambu"))
        } finally { nonInstantiable.deleteRecursively() }
        val corruptSchema = makeTree("bambu")
        try {
            File(corruptSchema, "pandy/schema.json").writeText("")
            assertFalse(studioProfileTreeComplete(corruptSchema, "bambu"))
        } finally { corruptSchema.deleteRecursively() }
        val absolutePath = makeTree("bambu")
        try {
            File(absolutePath, "resources/profiles/BBL.json").writeText("""{"machine_list":[{"name":"M","sub_path":"/etc/passwd"}]}""")
            assertFalse(studioProfileTreeComplete(absolutePath, "bambu"))
        } finally { absolutePath.deleteRecursively() }
    }
}
