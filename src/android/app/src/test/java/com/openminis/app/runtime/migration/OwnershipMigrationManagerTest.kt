package com.openminis.app.runtime.migration

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnershipMigrationManagerTest {

    @Test
    fun resolveIdentityCommandUsesStatForRealUidAndGid() {
        val cmd = OwnershipMigrationManager.resolveIdentityCommand("llc.slacker.minis")
        assertTrue(cmd.contains("stat -c '%u %g'"))
        assertTrue(cmd.contains("/data/user/0/llc.slacker.minis"))
        assertTrue(cmd.contains("/data/data/llc.slacker.minis"))
    }

    @Test
    fun parseIdentityOutputValidatesAppUids() {
        val valid = OwnershipMigrationManager.parseIdentityOutput("llc.slacker.minis", "10245 10245\n")
        assertNotNull(valid)
        assertEquals("llc.slacker.minis", valid!!.packageName)
        assertEquals(10245, valid.uid)
        assertEquals(10245, valid.gid)

        // Rejects system UIDs (< 10000)
        assertNull(OwnershipMigrationManager.parseIdentityOutput("llc.slacker.minis", "0 0\n"))
        assertNull(OwnershipMigrationManager.parseIdentityOutput("llc.slacker.minis", "1000 1000\n"))
        // Rejects malformed strings
        assertNull(OwnershipMigrationManager.parseIdentityOutput("llc.slacker.minis", "not_a_number\n"))
        assertNull(OwnershipMigrationManager.parseIdentityOutput("llc.slacker.minis", "10245\n"))
    }

    @Test
    fun lockPayloadSerializesMetadata() {
        val source = OwnershipMigrationManager.AppIdentity("dev.openminispet.android", 10100, 10100)
        val target = OwnershipMigrationManager.AppIdentity("llc.slacker.minis", 10245, 10245)
        val raw = OwnershipMigrationManager.buildLockPayload(source, target, 1725300000000L)
        val json = JSONObject(raw)

        assertEquals(1, json.getInt("version"))
        assertEquals("dev.openminispet.android", json.getString("sourcePackage"))
        assertEquals(10100, json.getInt("sourceUid"))
        assertEquals("llc.slacker.minis", json.getString("targetPackage"))
        assertEquals(10245, json.getInt("targetUid"))
        assertEquals(1725300000000L, json.getLong("timestampMs"))
    }

    @Test
    fun rollbackPlanExtractsOnlyMigratedEntriesInReverseOrder() {
        val lines = listOf(
            "workspace/file1.txt\t10100\t10100\t10245\t10245\tMIGRATED",
            "workspace/file2.txt\t10100\t10100\t10245\t10245\tMIGRATED",
            "workspace/file3.txt\t10100\t10100\t10245\t10245\tPENDING",
            "workspace/file4.txt\t10100\t10100\t10245\t10245\tREVERTED",
        )

        val plan = OwnershipMigrationManager.computeRollbackPlan(lines)
        assertEquals(2, plan.size)
        // Reversed: file2 then file1
        assertEquals("workspace/file2.txt", plan[0].relativePath)
        assertEquals("workspace/file1.txt", plan[1].relativePath)
        assertEquals(10100, plan[0].oldUid)
        assertEquals(10100, plan[1].oldUid)
    }

    @Test
    fun pathSecurityRejectsEscapes() {
        assertTrue(OwnershipMigrationManager.isSafeRelativePath("sessions/sess-1/chat.json"))
        assertTrue(OwnershipMigrationManager.isSafeRelativePath("workspace/sub/dir/file.txt"))

        // Rejects absolute paths
        assertFalse(OwnershipMigrationManager.isSafeRelativePath("/etc/passwd"))
        assertFalse(OwnershipMigrationManager.isSafeRelativePath("/data/adb/minis"))

        // Rejects directory traversal
        assertFalse(OwnershipMigrationManager.isSafeRelativePath("../escape.txt"))
        assertFalse(OwnershipMigrationManager.isSafeRelativePath("workspace/../../data"))
        assertFalse(OwnershipMigrationManager.isSafeRelativePath("./current.txt"))

        // Rejects NUL byte injection
        assertFalse(OwnershipMigrationManager.isSafeRelativePath("file\u0000.txt"))
    }
}
