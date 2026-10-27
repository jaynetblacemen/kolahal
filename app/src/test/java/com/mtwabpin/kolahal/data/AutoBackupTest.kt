package com.mtwabpin.kolahal.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoBackupTest {
    /** The oldest automatic backups go past the newest few; a backup you exported by hand, or anything else, stays. */
    @Test fun onlyOldAutomaticBackupsGo() {
        val names = listOf(
            "kolahal-backup-2026-10-25.json", "kolahal-backup-2026-10-27.json", "kolahal-backup-2026-10-26.json",
            "kolahal-backup.json", "notes.txt", "kolahal-backup-2026-10-24.json",
        )
        assertEquals(listOf("kolahal-backup-2026-10-25.json", "kolahal-backup-2026-10-24.json"), AutoBackup.expired(names, 2))
        assertEquals(emptyList<String>(), AutoBackup.expired(names, 7))
    }
}
