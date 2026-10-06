package com.openminis.app.runtime.files

import com.openminis.app.runtime.ubuntu.UbuntuPaths.SecureFilePath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SecureFilePathIdentityTest {
    private val workspace = File("/data/user/0/app/files/minis/workspace")

    /** `/workspace/attachments/a` */
    private val viaWorkspace = SecureFilePath(workspace, listOf("attachments", "a"))

    /** `/var/minis/attachments/a`: the alias carries a deeper root and fewer components. */
    private val viaAttachmentsAlias = SecureFilePath(File(workspace, "attachments"), listOf("a"))

    @Test
    fun oneFileReachedThroughTwoRootSplitsIsTheSamePath() {
        assertTrue(SecureFileAccess.samePath(viaWorkspace, viaAttachmentsAlias))
        assertTrue(SecureFileAccess.samePath(viaAttachmentsAlias, viaWorkspace))
    }

    @Test
    fun differentFilesAreNotTheSame() {
        assertFalse(SecureFileAccess.samePath(viaWorkspace, SecureFilePath(File(workspace, "attachments"), listOf("b"))))
        assertFalse(SecureFileAccess.samePath(viaWorkspace, SecureFilePath(workspace, listOf("attachments", "a", "x"))))
    }

    @Test
    fun aTargetInsideTheSourceThroughAnAliasIsADescendant() {
        val source = SecureFilePath(workspace, emptyList()) // /workspace
        val target = SecureFilePath(File(workspace, "attachments"), listOf("copy")) // /var/minis/attachments/copy
        assertTrue(SecureFileAccess.isDescendant(target, source))
    }

    @Test
    fun aPathIsNotItsOwnDescendantNorADescendantOfASibling() {
        assertFalse(SecureFileAccess.isDescendant(viaWorkspace, viaAttachmentsAlias))
        val sibling = SecureFilePath(File(workspace.parentFile, "workspace-other"), listOf("attachments", "a"))
        assertFalse(SecureFileAccess.isDescendant(sibling, SecureFilePath(workspace, emptyList())))
    }

    @Test
    fun dotDotInARootIsNormalisedBeforeComparing() {
        val odd = SecureFilePath(File(workspace, "attachments/../attachments"), listOf("a"))
        assertTrue(SecureFileAccess.samePath(odd, viaWorkspace))
    }
}
