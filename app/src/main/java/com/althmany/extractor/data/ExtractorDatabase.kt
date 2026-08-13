package com.althmany.extractor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ExtractorDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    init {
        // Extraction writes arrive in bursts while the UI simultaneously reads counters/results.
        // WAL reduces writer/reader contention without changing the logical database model.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        // Mobile-friendly WAL tuning: keep commits durable enough for runtime checkpoints while
        // avoiding full fsync pressure on every burst of extracted links / scan updates.
        runCatching { db.execSQL("PRAGMA synchronous=NORMAL") }
        runCatching { db.execSQL("PRAGMA temp_store=MEMORY") }
        runCatching { db.execSQL("PRAGMA busy_timeout=2500") }
        runCatching { db.execSQL("PRAGMA wal_autocheckpoint=512") }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE target_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                selected INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL DEFAULT 'PENDING',
                extracted_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                discovered INTEGER NOT NULL DEFAULT 0,
                verified_group INTEGER NOT NULL DEFAULT 0,
                unread_count INTEGER NOT NULL DEFAULT 0,
                activity_text TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                publishable INTEGER NOT NULL DEFAULT 1,
                community_parent INTEGER NOT NULL DEFAULT 0,
                last_synced_at INTEGER,
                last_completed_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE extracted_links (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                normalized_url TEXT NOT NULL,
                group_name TEXT NOT NULL COLLATE NOCASE,
                occurrences INTEGER NOT NULL DEFAULT 1,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                UNIQUE(normalized_url, group_name)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE extraction_checkpoints (
                group_name TEXT PRIMARY KEY COLLATE NOCASE,
                anchor_tokens TEXT NOT NULL DEFAULT '',
                signature INTEGER NOT NULL DEFAULT 0,
                iteration INTEGER NOT NULL DEFAULT 0,
                unique_links INTEGER NOT NULL DEFAULT 0,
                mode TEXT NOT NULL DEFAULT 'DEEP',
                completed INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE extraction_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                group_name TEXT,
                level TEXT NOT NULL,
                code TEXT NOT NULL,
                message TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE scan_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                normalized_url TEXT NOT NULL UNIQUE,
                invite_code TEXT NOT NULL,
                source_group TEXT,
                status TEXT NOT NULL DEFAULT 'PENDING',
                group_name TEXT,
                detail TEXT,
                attempts INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                scanned_at INTEGER,
                confidence INTEGER NOT NULL DEFAULT 0,
                member_count_text TEXT,
                invite_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
                signal_code TEXT,
                duration_ms INTEGER,
                target_package TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE publish_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message TEXT NOT NULL,
                target_package TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'RUNNING',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                delay_ms INTEGER NOT NULL DEFAULT 4000,
                max_attempts INTEGER NOT NULL DEFAULT 2,
                content_mode TEXT NOT NULL DEFAULT 'SINGLE_TEXT',
                attachment_uri TEXT,
                attachment_mime TEXT,
                run_token TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE publish_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                group_name TEXT NOT NULL COLLATE NOCASE,
                status TEXT NOT NULL DEFAULT 'PENDING',
                detail TEXT,
                attempts INTEGER NOT NULL DEFAULT 0,
                sent_at INTEGER,
                verified INTEGER NOT NULL DEFAULT 0,
                UNIQUE(run_id, group_name),
                FOREIGN KEY(run_id) REFERENCES publish_runs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_publish_items_run ON publish_items(run_id)")
        db.execSQL("CREATE INDEX idx_publish_items_status ON publish_items(status)")
        db.execSQL("CREATE INDEX idx_scan_status ON scan_items(status)")
        db.execSQL("CREATE INDEX idx_scan_code ON scan_items(invite_code)")
        db.execSQL("CREATE INDEX idx_scan_confidence ON scan_items(confidence)")
        db.execSQL("CREATE INDEX idx_links_group ON extracted_links(group_name)")
        db.execSQL("CREATE INDEX idx_links_normalized ON extracted_links(normalized_url)")
        db.execSQL("CREATE INDEX idx_logs_time ON extraction_logs(timestamp)")
        db.execSQL("CREATE INDEX idx_logs_group ON extraction_logs(group_name)")
        db.execSQL("CREATE INDEX idx_groups_selected_status ON target_groups(selected,status,id)")
        db.execSQL("CREATE INDEX idx_groups_unread ON target_groups(unread_count,id)")
        db.execSQL("CREATE INDEX idx_groups_active ON target_groups(active,publishable,id)")
        db.execSQL("CREATE INDEX idx_scan_status_id ON scan_items(status,id)")
        db.execSQL("CREATE INDEX idx_publish_run_status_id ON publish_items(run_id,status,id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN discovered INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN verified_group INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN last_completed_at INTEGER") }
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS extraction_checkpoints (
                    group_name TEXT PRIMARY KEY COLLATE NOCASE,
                    anchor_tokens TEXT NOT NULL DEFAULT '', signature INTEGER NOT NULL DEFAULT 0,
                    iteration INTEGER NOT NULL DEFAULT 0, unique_links INTEGER NOT NULL DEFAULT 0,
                    mode TEXT NOT NULL DEFAULT 'DEEP', completed INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS extraction_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL,
                    group_name TEXT, level TEXT NOT NULL, code TEXT NOT NULL, message TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scan_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    url TEXT NOT NULL,
                    normalized_url TEXT NOT NULL UNIQUE,
                    invite_code TEXT NOT NULL,
                    source_group TEXT,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    group_name TEXT,
                    detail TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    added_at INTEGER NOT NULL,
                    scanned_at INTEGER,
                    confidence INTEGER NOT NULL DEFAULT 0,
                    member_count_text TEXT,
                    invite_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
                    signal_code TEXT,
                    duration_ms INTEGER,
                    target_package TEXT
                )
                """.trimIndent()
            )
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_scan_status ON scan_items(status)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_scan_code ON scan_items(invite_code)") }
        }
        if (oldVersion < 4) {
            runCatching { db.execSQL("ALTER TABLE scan_items ADD COLUMN confidence INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE scan_items ADD COLUMN member_count_text TEXT") }
            runCatching { db.execSQL("ALTER TABLE scan_items ADD COLUMN invite_kind TEXT NOT NULL DEFAULT 'UNKNOWN'") }
            runCatching { db.execSQL("ALTER TABLE scan_items ADD COLUMN signal_code TEXT") }
            runCatching { db.execSQL("ALTER TABLE scan_items ADD COLUMN duration_ms INTEGER") }
            runCatching { db.execSQL("ALTER TABLE scan_items ADD COLUMN target_package TEXT") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_scan_confidence ON scan_items(confidence)") }
        }
        if (oldVersion < 5) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS publish_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    message TEXT NOT NULL,
                    target_package TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'RUNNING',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    delay_ms INTEGER NOT NULL DEFAULT 4000,
                    max_attempts INTEGER NOT NULL DEFAULT 2
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS publish_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id INTEGER NOT NULL,
                    group_name TEXT NOT NULL COLLATE NOCASE,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    detail TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    sent_at INTEGER,
                    verified INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(run_id, group_name),
                    FOREIGN KEY(run_id) REFERENCES publish_runs(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_publish_items_run ON publish_items(run_id)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_publish_items_status ON publish_items(status)") }
        }
        if (oldVersion < 6) {
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_selected_status ON target_groups(selected,status,id)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_scan_status_id ON scan_items(status,id)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_publish_run_status_id ON publish_items(run_id,status,id)") }
        }
        if (oldVersion < 7) {
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN unread_count INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN activity_text TEXT") }
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN active INTEGER NOT NULL DEFAULT 1") }
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN publishable INTEGER NOT NULL DEFAULT 1") }
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN community_parent INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN last_synced_at INTEGER") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_unread ON target_groups(unread_count,id)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_active ON target_groups(active,publishable,id)") }
        }
        if (oldVersion < 8) {
            runCatching { db.execSQL("ALTER TABLE publish_runs ADD COLUMN content_mode TEXT NOT NULL DEFAULT 'SINGLE_TEXT'") }
            runCatching { db.execSQL("ALTER TABLE publish_runs ADD COLUMN attachment_uri TEXT") }
            runCatching { db.execSQL("ALTER TABLE publish_runs ADD COLUMN attachment_mime TEXT") }
            runCatching { db.execSQL("ALTER TABLE publish_runs ADD COLUMN run_token TEXT NOT NULL DEFAULT ''") }
        }
    }

    fun upsertGroupName(name: String, discovered: Boolean = false): Long {
        val clean = name.trim()
        if (clean.isBlank()) return -1
        val values = ContentValues().apply {
            put("name", clean)
            put("selected", 1)
            put("status", if (discovered) GroupStatus.DISCOVERED.name else GroupStatus.PENDING.name)
            put("discovered", if (discovered) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("target_groups", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (!discovered) {
            writableDatabase.execSQL(
                "UPDATE target_groups SET selected=1, discovered=0 WHERE name=? COLLATE NOCASE",
                arrayOf(clean)
            )
        }
        readableDatabase.rawQuery(
            "SELECT id FROM target_groups WHERE name = ? COLLATE NOCASE LIMIT 1", arrayOf(clean)
        ).use { c -> return if (c.moveToFirst()) c.getLong(0) else -1 }
    }

    fun replaceGroups(names: List<String>) {
        writableDatabase.beginTransaction()
        try {
            names.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
                .forEach { upsertGroupName(it, discovered = false) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun addDiscoveredGroups(names: Collection<String>): Int {
        var added = 0
        writableDatabase.beginTransaction()
        try {
            names.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase).forEach { name ->
                val before = readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM target_groups WHERE name=? COLLATE NOCASE", arrayOf(name)
                ).use { c -> c.moveToFirst(); c.getInt(0) }
                upsertGroupName(name, discovered = true)
                if (before == 0) added++
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return added
    }

    fun addDiscoveredGroupCandidates(candidates: Collection<GroupSyncCandidate>): Int {
        var added = 0
        val now = System.currentTimeMillis()
        val deduped = candidates
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.trim().lowercase() }
        writableDatabase.beginTransaction()
        try {
            deduped.forEach { candidate ->
                val clean = candidate.name.trim()
                val before = readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM target_groups WHERE name=? COLLATE NOCASE", arrayOf(clean)
                ).use { c -> c.moveToFirst(); c.getInt(0) }
                upsertGroupName(clean, discovered = true)
                writableDatabase.update(
                    "target_groups",
                    ContentValues().apply {
                        put("unread_count", candidate.unreadCount.coerceAtLeast(0))
                        if (candidate.activityText == null) putNull("activity_text") else put("activity_text", candidate.activityText.take(120))
                        put("active", if (candidate.active) 1 else 0)
                        put("publishable", if (candidate.publishableHint) 1 else 0)
                        put("community_parent", if (candidate.communityParentHint) 1 else 0)
                        put("last_synced_at", now)
                    },
                    "name=? COLLATE NOCASE",
                    arrayOf(clean)
                )
                if (before == 0) added++
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return added
    }

    fun getGroups(): List<TargetGroup> = queryGroups(
        "SELECT id,name,selected,status,extracted_count,last_error,discovered,verified_group,unread_count,activity_text,active,publishable,community_parent,last_synced_at FROM target_groups ORDER BY id",
        null
    )

    fun getSelectedGroups(): List<TargetGroup> = queryGroups(
        """
        SELECT id,name,selected,status,extracted_count,last_error,discovered,verified_group,unread_count,activity_text,active,publishable,community_parent,last_synced_at
        FROM target_groups WHERE selected=1 ORDER BY id
        """.trimIndent(), null
    )

    fun getSelectedPendingGroups(): List<TargetGroup> = queryGroups(
        """
        SELECT id,name,selected,status,extracted_count,last_error,discovered,verified_group,unread_count,activity_text,active,publishable,community_parent,last_synced_at
        FROM target_groups
        WHERE selected=1 AND status NOT IN ('COMPLETED','SKIPPED_NOT_GROUP')
        ORDER BY id
        """.trimIndent(), null
    )

    private fun queryGroups(sql: String, args: Array<String>?): List<TargetGroup> =
        readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    TargetGroup(
                        id = c.getLong(0), name = c.getString(1), selected = c.getInt(2) == 1,
                        status = runCatching { GroupStatus.valueOf(c.getString(3)) }.getOrDefault(GroupStatus.PENDING),
                        extractedCount = c.getInt(4), lastError = c.getString(5),
                        discovered = c.getInt(6) == 1, verifiedGroup = c.getInt(7) == 1,
                        unreadCount = c.getInt(8), activityText = c.getString(9),
                        active = c.getInt(10) == 1, publishable = c.getInt(11) == 1,
                        communityParent = c.getInt(12) == 1,
                        lastSyncedAt = if (c.isNull(13)) null else c.getLong(13)
                    )
                )
            }
        }

    fun setGroupSelected(id: Long, selected: Boolean) {
        writableDatabase.update("target_groups", ContentValues().apply { put("selected", if (selected) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    fun setAllGroupsSelected(selected: Boolean) {
        writableDatabase.update("target_groups", ContentValues().apply { put("selected", if (selected) 1 else 0) }, null, null)
    }

    fun setSelectionPreset(preset: GroupSelectionPreset) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            when (preset) {
                GroupSelectionPreset.ALL -> db.execSQL("UPDATE target_groups SET selected=1")
                GroupSelectionPreset.NONE -> db.execSQL("UPDATE target_groups SET selected=0")
                GroupSelectionPreset.UNREAD -> db.execSQL("UPDATE target_groups SET selected=CASE WHEN unread_count>0 THEN 1 ELSE 0 END")
                GroupSelectionPreset.ACTIVE -> db.execSQL("UPDATE target_groups SET selected=CASE WHEN active=1 THEN 1 ELSE 0 END")
                GroupSelectionPreset.PUBLISHABLE -> db.execSQL("UPDATE target_groups SET selected=CASE WHEN publishable=1 AND community_parent=0 THEN 1 ELSE 0 END")
                GroupSelectionPreset.UNVERIFIED -> db.execSQL("UPDATE target_groups SET selected=CASE WHEN verified_group=0 THEN 1 ELSE 0 END")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun updateGroupCapabilities(id: Long, verified: Boolean, active: Boolean, publishable: Boolean, communityParent: Boolean = false) {
        writableDatabase.update(
            "target_groups",
            ContentValues().apply {
                put("verified_group", if (verified) 1 else 0)
                put("active", if (active) 1 else 0)
                put("publishable", if (publishable) 1 else 0)
                put("community_parent", if (communityParent) 1 else 0)
            },
            "id=?", arrayOf(id.toString())
        )
    }

    fun updateGroupStatus(id: Long, status: GroupStatus, error: String? = null) {
        val values = ContentValues().apply {
            put("status", status.name)
            if (error == null) putNull("last_error") else put("last_error", error)
            if (status == GroupStatus.COMPLETED) put("last_completed_at", System.currentTimeMillis())
        }
        writableDatabase.update("target_groups", values, "id=?", arrayOf(id.toString()))
    }

    fun markVerifiedGroup(id: Long, verified: Boolean) {
        writableDatabase.update(
            "target_groups",
            ContentValues().apply { put("verified_group", if (verified) 1 else 0) },
            "id=?", arrayOf(id.toString())
        )
    }

    fun incrementGroupExtractedCount(groupName: String) {
        writableDatabase.execSQL(
            "UPDATE target_groups SET extracted_count=extracted_count+1 WHERE name=? COLLATE NOCASE",
            arrayOf(groupName)
        )
    }

    fun resetRunStatuses() {
        writableDatabase.execSQL(
            """
            UPDATE target_groups
            SET status=CASE WHEN discovered=1 AND verified_group=0 THEN 'DISCOVERED' ELSE 'PENDING' END,
                last_error=NULL
            WHERE selected=1 AND status!='SKIPPED_NOT_GROUP'
            """.trimIndent()
        )
    }

    fun clearAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("publish_items", null, null)
            writableDatabase.delete("publish_runs", null, null)
            writableDatabase.delete("scan_items", null, null)
            writableDatabase.delete("extraction_logs", null, null)
            writableDatabase.delete("extraction_checkpoints", null, null)
            writableDatabase.delete("extracted_links", null, null)
            writableDatabase.delete("target_groups", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun upsertLink(url: String, normalizedUrl: String, groupName: String, timestamp: Long): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("url", url); put("normalized_url", normalizedUrl); put("group_name", groupName)
            put("occurrences", 1); put("first_seen", timestamp); put("last_seen", timestamp)
        }
        val inserted = db.insertWithOnConflict("extracted_links", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted != -1L) {
            incrementGroupExtractedCount(groupName)
            return true
        }
        // Accessibility does not expose a stable WhatsApp message id on every build. Never inflate
        // occurrence counts from repeated rendering of the same message while scrolling.
        db.execSQL(
            "UPDATE extracted_links SET last_seen=?,url=? WHERE normalized_url=? AND group_name=? COLLATE NOCASE",
            arrayOf<Any?>(timestamp, url, normalizedUrl, groupName)
        )
        return false
    }

    /**
     * Inserts a visible Accessibility batch in one SQLite transaction.
     * This removes one IO context switch + one transaction per URL from the hot extraction loop.
     */
    fun upsertLinksBatch(
        links: List<Pair<String, String>>,
        groupName: String,
        timestamp: Long
    ): Int {
        if (links.isEmpty()) return 0
        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            links.forEach { (url, normalizedUrl) ->
                val values = ContentValues().apply {
                    put("url", url)
                    put("normalized_url", normalizedUrl)
                    put("group_name", groupName)
                    put("occurrences", 1)
                    put("first_seen", timestamp)
                    put("last_seen", timestamp)
                }
                val inserted = db.insertWithOnConflict(
                    "extracted_links",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                if (inserted != -1L) {
                    insertedCount++
                } else {
                    db.execSQL(
                        "UPDATE extracted_links SET last_seen=?,url=? WHERE normalized_url=? AND group_name=? COLLATE NOCASE",
                        arrayOf<Any?>(timestamp, url, normalizedUrl, groupName)
                    )
                }
            }
            if (insertedCount > 0) {
                db.execSQL(
                    "UPDATE target_groups SET extracted_count=extracted_count+? WHERE name=? COLLATE NOCASE",
                    arrayOf<Any?>(insertedCount, groupName)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return insertedCount
    }

    fun getLinks(limit: Int = 50_000): List<LinkRecord> = readableDatabase.rawQuery(
        """
        SELECT id,url,normalized_url,group_name,occurrences,first_seen,last_seen
        FROM extracted_links ORDER BY last_seen DESC LIMIT ?
        """.trimIndent(), arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                LinkRecord(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5), c.getLong(6))
            )
        }
    }

    fun saveCheckpoint(cp: GroupCheckpoint) {
        val values = ContentValues().apply {
            put("group_name", cp.groupName)
            put("anchor_tokens", cp.anchorTokens.joinToString("\u001F"))
            put("signature", cp.signature)
            put("iteration", cp.iteration)
            put("unique_links", cp.uniqueLinks)
            put("mode", cp.mode.name)
            put("completed", if (cp.completed) 1 else 0)
            put("updated_at", cp.updatedAt)
        }
        writableDatabase.insertWithOnConflict("extraction_checkpoints", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getCheckpoint(groupName: String): GroupCheckpoint? = readableDatabase.rawQuery(
        """
        SELECT group_name,anchor_tokens,signature,iteration,unique_links,mode,completed,updated_at
        FROM extraction_checkpoints WHERE group_name=? COLLATE NOCASE LIMIT 1
        """.trimIndent(), arrayOf(groupName)
    ).use { c ->
        if (!c.moveToFirst()) null else GroupCheckpoint(
            groupName = c.getString(0),
            anchorTokens = c.getString(1).split('\u001F').filter(String::isNotBlank),
            signature = c.getInt(2), iteration = c.getInt(3), uniqueLinks = c.getInt(4),
            mode = runCatching { ExtractionMode.valueOf(c.getString(5)) }.getOrDefault(ExtractionMode.DEEP),
            completed = c.getInt(6) == 1, updatedAt = c.getLong(7)
        )
    }

    fun log(groupName: String?, level: String, code: String, message: String) {
        val v = ContentValues().apply {
            put("timestamp", System.currentTimeMillis()); put("group_name", groupName)
            put("level", level); put("code", code); put("message", message)
        }
        writableDatabase.insert("extraction_logs", null, v)
        writableDatabase.execSQL(
            "DELETE FROM extraction_logs WHERE id NOT IN (SELECT id FROM extraction_logs ORDER BY id DESC LIMIT 5000)"
        )
    }

    fun getLogs(limit: Int = 1000): List<ExtractionLog> = readableDatabase.rawQuery(
        "SELECT id,timestamp,group_name,level,code,message FROM extraction_logs ORDER BY id DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(ExtractionLog(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5)))
        }
    }

    fun upsertScanItem(url: String, normalizedUrl: String, inviteCode: String, sourceGroup: String?, timestamp: Long): Boolean {
        val values = ContentValues().apply {
            put("url", url)
            put("normalized_url", normalizedUrl)
            put("invite_code", inviteCode)
            if (sourceGroup == null) putNull("source_group") else put("source_group", sourceGroup)
            put("status", ScanStatus.PENDING.name)
            put("attempts", 0)
            put("added_at", timestamp)
            put("confidence", 0)
            put("invite_kind", InviteKind.UNKNOWN.name)
        }
        val inserted = writableDatabase.insertWithOnConflict("scan_items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted == -1L && sourceGroup != null) {
            writableDatabase.execSQL(
                "UPDATE scan_items SET source_group=COALESCE(source_group,?) WHERE normalized_url=?",
                arrayOf(sourceGroup, normalizedUrl)
            )
        }
        return inserted != -1L
    }

    /**
     * Bulk scan import.  Large extraction exports can contain thousands of invite links; doing one
     * SQLite transaction per link is unnecessarily expensive on mobile flash.  This method keeps
     * the same UNIQUE(normalized_url) semantics while committing the entire import atomically.
     */
    fun upsertScanItemsBatch(items: List<ScanSeed>, timestamp: Long): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            items.forEach { item ->
                val values = ContentValues().apply {
                    put("url", item.url)
                    put("normalized_url", item.normalizedUrl)
                    put("invite_code", item.inviteCode)
                    if (item.sourceGroup == null) putNull("source_group") else put("source_group", item.sourceGroup)
                    put("status", ScanStatus.PENDING.name)
                    put("attempts", 0)
                    put("added_at", timestamp)
                    put("confidence", 0)
                    put("invite_kind", InviteKind.UNKNOWN.name)
                }
                val inserted = db.insertWithOnConflict("scan_items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted != -1L) {
                    insertedCount++
                } else if (item.sourceGroup != null) {
                    db.execSQL(
                        "UPDATE scan_items SET source_group=COALESCE(source_group,?) WHERE normalized_url=?",
                        arrayOf(item.sourceGroup, item.normalizedUrl)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return insertedCount
    }

    private fun scanRecordFromCursor(c: android.database.Cursor): ScanRecord = ScanRecord(
        id = c.getLong(0),
        url = c.getString(1),
        normalizedUrl = c.getString(2),
        inviteCode = c.getString(3),
        sourceGroup = c.getString(4),
        status = runCatching { ScanStatus.valueOf(c.getString(5)) }.getOrDefault(ScanStatus.UNKNOWN),
        groupName = c.getString(6),
        detail = c.getString(7),
        attempts = c.getInt(8),
        addedAt = c.getLong(9),
        scannedAt = if (c.isNull(10)) null else c.getLong(10),
        confidence = if (c.isNull(11)) 0 else c.getInt(11),
        memberCountText = c.getString(12),
        inviteKind = runCatching { InviteKind.valueOf(c.getString(13) ?: InviteKind.UNKNOWN.name) }.getOrDefault(InviteKind.UNKNOWN),
        signalCode = c.getString(14),
        durationMs = if (c.isNull(15)) null else c.getLong(15),
        targetPackage = c.getString(16)
    )

    private val scanSelect = """
        SELECT id,url,normalized_url,invite_code,source_group,status,group_name,detail,attempts,added_at,scanned_at,
               confidence,member_count_text,invite_kind,signal_code,duration_ms,target_package
        FROM scan_items
    """.trimIndent()

    fun getScanItems(limit: Int = 50_000): List<ScanRecord> = readableDatabase.rawQuery(
        "$scanSelect ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(scanRecordFromCursor(c)) } }

    fun getPendingScanItems(limit: Int = 50_000): List<ScanRecord> = readableDatabase.rawQuery(
        "$scanSelect WHERE status IN ('PENDING','UNKNOWN','NETWORK_ERROR','ERROR') ORDER BY id LIMIT ?",
        arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(scanRecordFromCursor(c)) } }

    fun getUncertainScanItems(limit: Int = 50_000): List<ScanRecord> = readableDatabase.rawQuery(
        "$scanSelect WHERE status IN ('UNKNOWN','NETWORK_ERROR','ERROR') ORDER BY id LIMIT ?",
        arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(scanRecordFromCursor(c)) } }

    fun resetAllScanItemsForRecheck() {
        writableDatabase.execSQL(
            """
            UPDATE scan_items
            SET status='PENDING', detail=NULL, confidence=0, signal_code=NULL, duration_ms=NULL, scanned_at=NULL
            """.trimIndent()
        )
    }

    fun markScanAttempt(id: Long, detail: String, targetPackage: String?) {
        val values = ContentValues().apply {
            put("status", ScanStatus.SCANNING.name)
            put("detail", detail)
            if (targetPackage == null) putNull("target_package") else put("target_package", targetPackage)
        }
        writableDatabase.update("scan_items", values, "id=?", arrayOf(id.toString()))
        writableDatabase.execSQL("UPDATE scan_items SET attempts=attempts+1 WHERE id=?", arrayOf(id))
    }

    fun updateScanResult(
        id: Long,
        status: ScanStatus,
        groupName: String?,
        detail: String?,
        incrementAttempt: Boolean,
        confidence: Int = 0,
        memberCountText: String? = null,
        inviteKind: InviteKind = InviteKind.UNKNOWN,
        signalCode: String? = null,
        durationMs: Long? = null,
        targetPackage: String? = null
    ) {
        val values = ContentValues().apply {
            put("status", status.name)
            if (groupName == null) putNull("group_name") else put("group_name", groupName)
            if (detail == null) putNull("detail") else put("detail", detail)
            put("confidence", confidence.coerceIn(0, 100))
            if (memberCountText == null) putNull("member_count_text") else put("member_count_text", memberCountText)
            put("invite_kind", inviteKind.name)
            if (signalCode == null) putNull("signal_code") else put("signal_code", signalCode)
            if (durationMs == null) putNull("duration_ms") else put("duration_ms", durationMs.coerceAtLeast(0L))
            if (targetPackage == null) putNull("target_package") else put("target_package", targetPackage)
            if (status != ScanStatus.SCANNING) put("scanned_at", System.currentTimeMillis())
        }
        writableDatabase.update("scan_items", values, "id=?", arrayOf(id.toString()))
        if (incrementAttempt) writableDatabase.execSQL("UPDATE scan_items SET attempts=attempts+1 WHERE id=?", arrayOf(id))
    }

    fun resetScanRunningItems() {
        writableDatabase.execSQL("UPDATE scan_items SET status='PENDING' WHERE status='SCANNING'")
    }

    fun clearScanItems() { writableDatabase.delete("scan_items", null, null) }

    fun scanStats(): ScanStats {
        val counts = mutableMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT status,COUNT(*) FROM scan_items GROUP BY status", null).use { c ->
            while (c.moveToNext()) counts[c.getString(0)] = c.getInt(1)
        }
        val total = counts.values.sum()
        val pending = (counts[ScanStatus.PENDING.name] ?: 0) + (counts[ScanStatus.SCANNING.name] ?: 0)
        val direct = counts[ScanStatus.DIRECT.name] ?: 0
        val approval = counts[ScanStatus.APPROVAL.name] ?: 0
        val requestPending = counts[ScanStatus.REQUEST_PENDING.name] ?: 0
        val already = counts[ScanStatus.ALREADY_MEMBER.name] ?: 0
        val invalid = (counts[ScanStatus.INVALID.name] ?: 0) + (counts[ScanStatus.FULL.name] ?: 0) +
            (counts[ScanStatus.REMOVED.name] ?: 0) + (counts[ScanStatus.ACCOUNT_LIMIT.name] ?: 0)
        val network = counts[ScanStatus.NETWORK_ERROR.name] ?: 0
        val unknown = counts[ScanStatus.UNKNOWN.name] ?: 0
        val accounted = pending + direct + approval + requestPending + already + invalid + network + unknown
        return ScanStats(total, pending, direct, approval, requestPending, already, invalid, network, unknown, (total - accounted).coerceAtLeast(0))
    }

    fun getStats(): ExtractionStats {
        fun scalar(sql: String): Int = readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return ExtractionStats(
            totalGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE selected=1"),
            completedGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE selected=1 AND status='COMPLETED'"),
            failedGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE selected=1 AND status IN ('FAILED','FAILED_FINAL')"),
            totalUniqueLinks = scalar("SELECT COUNT(DISTINCT normalized_url) FROM extracted_links"),
            totalOccurrences = scalar("SELECT COUNT(*) FROM extracted_links"),
            syncedGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE discovered=1"),
            unreadGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE unread_count>0"),
            activeGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE active=1"),
            publishableGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE publishable=1 AND community_parent=0")
        )
    }

    fun stopResumablePublishRuns() {
        writableDatabase.execSQL("UPDATE publish_runs SET status='STOPPED', updated_at=? WHERE status IN ('RUNNING','PAUSED','ERROR')", arrayOf(System.currentTimeMillis()))
    }

    fun createPublishRun(
        message: String,
        targetPackage: String,
        delayMs: Long,
        maxAttempts: Int,
        groupNames: List<String>,
        contentMode: PublishContentMode,
        attachmentUri: String?,
        attachmentMime: String?,
        runToken: String
    ): Long {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val runId = db.insertOrThrow("publish_runs", null, ContentValues().apply {
                put("message", message)
                put("target_package", targetPackage)
                put("status", PublishRunStatus.RUNNING.name)
                put("created_at", now)
                put("updated_at", now)
                put("delay_ms", delayMs)
                put("max_attempts", maxAttempts)
                put("content_mode", contentMode.name)
                if (attachmentUri == null) putNull("attachment_uri") else put("attachment_uri", attachmentUri)
                if (attachmentMime == null) putNull("attachment_mime") else put("attachment_mime", attachmentMime)
                put("run_token", runToken)
            })
            groupNames.distinctBy { it.lowercase() }.forEach { group ->
                db.insertWithOnConflict("publish_items", null, ContentValues().apply {
                    put("run_id", runId)
                    put("group_name", group)
                    put("status", PublishStatus.PENDING.name)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
            runId
        } finally { db.endTransaction() }
    }

    fun getPublishRun(id: Long): PublishRun? = readableDatabase.rawQuery(
        "SELECT id,message,target_package,status,created_at,updated_at,delay_ms,max_attempts,content_mode,attachment_uri,attachment_mime,run_token FROM publish_runs WHERE id=? LIMIT 1",
        arrayOf(id.toString())
    ).use { c ->
        if (!c.moveToFirst()) null else PublishRun(
            c.getLong(0), c.getString(1), c.getString(2),
            runCatching { PublishRunStatus.valueOf(c.getString(3)) }.getOrDefault(PublishRunStatus.ERROR),
            c.getLong(4), c.getLong(5), c.getLong(6), c.getInt(7),
            runCatching { PublishContentMode.valueOf(c.getString(8)) }.getOrDefault(PublishContentMode.SINGLE_TEXT),
            c.getString(9), c.getString(10), c.getString(11).orEmpty()
        )
    }

    fun latestResumablePublishRun(): PublishRun? = readableDatabase.rawQuery(
        "SELECT id,message,target_package,status,created_at,updated_at,delay_ms,max_attempts,content_mode,attachment_uri,attachment_mime,run_token FROM publish_runs WHERE status IN ('RUNNING','PAUSED','ERROR') ORDER BY id DESC LIMIT 1",
        null
    ).use { c ->
        if (!c.moveToFirst()) null else PublishRun(
            c.getLong(0), c.getString(1), c.getString(2),
            runCatching { PublishRunStatus.valueOf(c.getString(3)) }.getOrDefault(PublishRunStatus.ERROR),
            c.getLong(4), c.getLong(5), c.getLong(6), c.getInt(7),
            runCatching { PublishContentMode.valueOf(c.getString(8)) }.getOrDefault(PublishContentMode.SINGLE_TEXT),
            c.getString(9), c.getString(10), c.getString(11).orEmpty()
        )
    }

    fun getPublishItems(runId: Long): List<PublishItem> = readableDatabase.rawQuery(
        "SELECT id,run_id,group_name,status,detail,attempts,sent_at,verified FROM publish_items WHERE run_id=? ORDER BY id",
        arrayOf(runId.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(PublishItem(
                c.getLong(0), c.getLong(1), c.getString(2),
                runCatching { PublishStatus.valueOf(c.getString(3)) }.getOrDefault(PublishStatus.FAILED),
                c.getString(4), c.getInt(5), if (c.isNull(6)) null else c.getLong(6), c.getInt(7) == 1
            ))
        }
    }

    fun getPendingPublishItems(runId: Long): List<PublishItem> = readableDatabase.rawQuery(
        "SELECT id,run_id,group_name,status,detail,attempts,sent_at,verified FROM publish_items WHERE run_id=? AND status NOT IN ('SENT','VERIFIED','UNCERTAIN','SKIPPED') ORDER BY id",
        arrayOf(runId.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(PublishItem(
                c.getLong(0), c.getLong(1), c.getString(2),
                runCatching { PublishStatus.valueOf(c.getString(3)) }.getOrDefault(PublishStatus.FAILED),
                c.getString(4), c.getInt(5), if (c.isNull(6)) null else c.getLong(6), c.getInt(7) == 1
            ))
        }
    }

    fun updatePublishRunStatus(runId: Long, status: PublishRunStatus) {
        writableDatabase.update("publish_runs", ContentValues().apply {
            put("status", status.name); put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(runId.toString()))
    }

    fun updatePublishItem(id: Long, status: PublishStatus, detail: String? = null, incrementAttempt: Boolean = false, verified: Boolean = false) {
        val values = ContentValues().apply {
            put("status", status.name)
            if (detail == null) putNull("detail") else put("detail", detail)
            if (status == PublishStatus.SENT || status == PublishStatus.VERIFIED) put("sent_at", System.currentTimeMillis())
            put("verified", if (verified || status == PublishStatus.VERIFIED) 1 else 0)
        }
        writableDatabase.update("publish_items", values, "id=?", arrayOf(id.toString()))
        if (incrementAttempt) writableDatabase.execSQL("UPDATE publish_items SET attempts=attempts+1 WHERE id=?", arrayOf(id))
    }

    fun resetPublishTransientItems(runId: Long) {
        // OPENING/PREPARING are safe to retry because no send action was issued yet.
        writableDatabase.execSQL(
            "UPDATE publish_items SET status='PENDING', detail='استكمال بعد انقطاع قبل الإرسال' WHERE run_id=? AND status IN ('OPENING','PREPARING')",
            arrayOf(runId)
        )
        // SENDING is deliberately not retried after a process interruption. The send click may have
        // reached WhatsApp even though our process died before verification; retrying could duplicate.
        writableDatabase.execSQL(
            "UPDATE publish_items SET status='UNCERTAIN', detail='حالة الإرسال غير محسومة بعد انقطاع؛ لم تتم إعادة الإرسال تلقائيًا لمنع التكرار' WHERE run_id=? AND status='SENDING'",
            arrayOf(runId)
        )
    }

    fun publishStats(runId: Long): PublishStats {
        val counts = mutableMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT status,COUNT(*) FROM publish_items WHERE run_id=? GROUP BY status", arrayOf(runId.toString())).use { c ->
            while (c.moveToNext()) counts[c.getString(0)] = c.getInt(1)
        }
        val total = counts.values.sum()
        val sent = counts[PublishStatus.SENT.name] ?: 0
        val verified = counts[PublishStatus.VERIFIED.name] ?: 0
        val failed = counts[PublishStatus.FAILED.name] ?: 0
        val skipped = counts[PublishStatus.SKIPPED.name] ?: 0
        val uncertain = counts[PublishStatus.UNCERTAIN.name] ?: 0
        val pending = (total - sent - verified - failed - skipped - uncertain).coerceAtLeast(0)
        return PublishStats(total, pending, sent, verified, failed, skipped, uncertain)
    }

    fun clearPublishHistory() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("publish_items", null, null)
            writableDatabase.delete("publish_runs", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    companion object {
        private const val DB_NAME = "althmany_extractor.db"
        private const val DB_VERSION = 8
    }
}
