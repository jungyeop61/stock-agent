package com.jusika.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(name: String): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry(name)); it.write("test".toByteArray()); it.closeEntry()
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
    @Test fun safeModelFileIsExtracted() {
        val root = temporary.newFolder()
        ModelInstaller.extract(archive("${ModelInstaller.MODEL_NAME}/conf/model.conf"), root)
        assertTrue(java.io.File(root, "${ModelInstaller.MODEL_NAME}/conf/model.conf").isFile)
    }
    @Test fun zipTraversalCannotEscapeInstallation() {
        assertThrows(IllegalStateException::class.java) {
            ModelInstaller.extract(archive("${ModelInstaller.MODEL_NAME}/../../escaped"), temporary.newFolder())
        }
    }
    @Test fun unexpectedModelIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            ModelInstaller.extract(archive("unexpected/am/final.mdl"), temporary.newFolder())
        }
    }
}
