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
                name TEXT NOT NULL COLLATE NOCASE,
                selected INTEGER NOT NULL DEFAULT 0,
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
                last_completed_at INTEGER,
                jid_or_group_id TEXT,
                whatsapp_package TEXT NOT NULL DEFAULT '',
                last_known_access_method TEXT NOT NULL DEFAULT 'UNKNOWN',
                preferred_access_method TEXT NOT NULL DEFAULT 'UNKNOWN',
                last_successful_open_method TEXT NOT NULL DEFAULT 'UNKNOWN',
                access_success_count INTEGER NOT NULL DEFAULT 0,
                access_failure_count INTEGER NOT NULL DEFAULT 0,
                last_opened_at INTEGER,
                sync_order INTEGER NOT NULL DEFAULT 2147483647,
                last_publish_status TEXT,
                last_published_at INTEGER,
                last_publish_error TEXT,
                sync_generation INTEGER NOT NULL DEFAULT 0,
                stale INTEGER NOT NULL DEFAULT 0,
                UNIQUE(name, whatsapp_package)
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
                category TEXT NOT NULL DEFAULT 'OTHER',
                invite_code TEXT,
                source_group_id INTEGER,
                whatsapp_package TEXT,
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
        db.execSQL("CREATE INDEX idx_groups_package_order ON target_groups(whatsapp_package,sync_order,id)")
        db.execSQL("CREATE INDEX idx_groups_access ON target_groups(last_successful_open_method,preferred_access_method,id)")
        db.execSQL("CREATE INDEX idx_groups_stale_package ON target_groups(stale,whatsapp_package,sync_generation,id)")
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
        if (oldVersion < 9) {
            // Rebuild the group table so identity is scoped by WhatsApp package instead of display name only.
            // AccessibilityNodeInfo is deliberately never persisted; only stable metadata/access hints are kept.
            db.execSQL("ALTER TABLE target_groups RENAME TO target_groups_legacy")
            db.execSQL(
                """
                CREATE TABLE target_groups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL COLLATE NOCASE,
                    selected INTEGER NOT NULL DEFAULT 0,
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
                    last_completed_at INTEGER,
                    jid_or_group_id TEXT,
                    whatsapp_package TEXT NOT NULL DEFAULT '',
                    last_known_access_method TEXT NOT NULL DEFAULT 'UNKNOWN',
                    preferred_access_method TEXT NOT NULL DEFAULT 'UNKNOWN',
                    last_successful_open_method TEXT NOT NULL DEFAULT 'UNKNOWN',
                    access_success_count INTEGER NOT NULL DEFAULT 0,
                    access_failure_count INTEGER NOT NULL DEFAULT 0,
                    last_opened_at INTEGER,
                    sync_order INTEGER NOT NULL DEFAULT 2147483647,
                    last_publish_status TEXT,
                    last_published_at INTEGER,
                    last_publish_error TEXT,
                    sync_generation INTEGER NOT NULL DEFAULT 0,
                    stale INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(name, whatsapp_package)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO target_groups(
                    id,name,selected,status,extracted_count,last_error,discovered,verified_group,
                    unread_count,activity_text,active,publishable,community_parent,last_synced_at,last_completed_at
                )
                SELECT id,name,selected,status,extracted_count,last_error,discovered,verified_group,
                    unread_count,activity_text,active,publishable,community_parent,last_synced_at,last_completed_at
                FROM target_groups_legacy
                """.trimIndent()
            )
            db.execSQL("DROP TABLE target_groups_legacy")
            runCatching { db.execSQL("ALTER TABLE extracted_links ADD COLUMN category TEXT NOT NULL DEFAULT 'OTHER'") }
            runCatching { db.execSQL("ALTER TABLE extracted_links ADD COLUMN invite_code TEXT") }
            runCatching { db.execSQL("ALTER TABLE extracted_links ADD COLUMN source_group_id INTEGER") }
            runCatching { db.execSQL("ALTER TABLE extracted_links ADD COLUMN whatsapp_package TEXT") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_selected_status ON target_groups(selected,status,id)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_unread ON target_groups(unread_count,id)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_active ON target_groups(active,publishable,id)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_package_order ON target_groups(whatsapp_package,sync_order,id)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_access ON target_groups(last_successful_open_method,preferred_access_method,id)") }
        }
        if (oldVersion < 10) {
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN last_publish_status TEXT") }
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN last_published_at INTEGER") }
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN last_publish_error TEXT") }
        }
        if (oldVersion < 11) {
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN sync_generation INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE target_groups ADD COLUMN stale INTEGER NOT NULL DEFAULT 0") }
            // v2.14.x could persist toolbar/filter/system-card labels as discovered rows and selected
            // every discovered item by default. Hide those legacy discoveries until a clean group-only
            // synchronization sees them again. Manual rows are preserved.
            runCatching { db.execSQL("UPDATE target_groups SET selected=0, stale=1 WHERE discovered=1") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_stale_package ON target_groups(stale,whatsapp_package,sync_generation,id)") }
        }
    }

    fun upsertGroupName(name: String, discovered: Boolean = false, whatsappPackage: String = ""): Long {
        val clean = name.trim()
        if (clean.isBlank()) return -1
        val pkg = whatsappPackage.trim()
        val values = ContentValues().apply {
            put("name", clean)
            put("whatsapp_package", pkg)
            put("selected", if (discovered) 0 else 1)
            put("status", if (discovered) GroupStatus.DISCOVERED.name else GroupStatus.PENDING.name)
            put("discovered", if (discovered) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("target_groups", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (!discovered) {
            writableDatabase.execSQL(
                "UPDATE target_groups SET selected=1, discovered=0 WHERE name=? COLLATE NOCASE AND whatsapp_package=?",
                arrayOf(clean, pkg)
            )
        }
        readableDatabase.rawQuery(
            "SELECT id FROM target_groups WHERE name = ? COLLATE NOCASE AND whatsapp_package=? LIMIT 1",
            arrayOf(clean, pkg)
        ).use { c -> return if (c.moveToFirst()) c.getLong(0) else -1 }
    }

    fun replaceGroups(names: List<String>, whatsappPackage: String = "") {
        val pkg = whatsappPackage.trim()
        writableDatabase.beginTransaction()
        try {
            names.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
                .forEach { upsertGroupName(it, discovered = false, whatsappPackage = pkg) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun addDiscoveredGroups(names: Collection<String>): Int {
        var added = 0
        writableDatabase.beginTransaction()
        try {
            names.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase).forEach { name ->
                val before = readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM target_groups WHERE name=? COLLATE NOCASE AND whatsapp_package=''", arrayOf(name)
                ).use { c -> c.moveToFirst(); c.getInt(0) }
                upsertGroupName(name, discovered = true)
                if (before == 0) added++
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return added
    }

    fun addDiscoveredGroupCandidates(candidates: Collection<GroupSyncCandidate>, syncGeneration: Long): Int {
        var added = 0
        val now = System.currentTimeMillis()
        val deduped = candidates
            .filter { it.name.isNotBlank() }
            .distinctBy { "${it.whatsappPackage.trim()}|${it.name.trim().lowercase()}" }
        writableDatabase.beginTransaction()
        try {
            deduped.forEach { candidate ->
                val clean = candidate.name.trim()
                val pkg = candidate.whatsappPackage.trim()
                var exactId = readableDatabase.rawQuery(
                    "SELECT id FROM target_groups WHERE name=? COLLATE NOCASE AND whatsapp_package=? LIMIT 1",
                    arrayOf(clean, pkg)
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                val legacyId = if (pkg.isNotBlank()) readableDatabase.rawQuery(
                    "SELECT id FROM target_groups WHERE name=? COLLATE NOCASE AND whatsapp_package='' LIMIT 1",
                    arrayOf(clean)
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } else null

                // v9 migration may leave old package-less rows. Bind them to the selected WhatsApp
                // instead of creating a second logical GroupRecord for the same chat. If an exact
                // package-scoped row already exists, merge the user selection and remove the stale row.
                val existedBefore = exactId != null || legacyId != null
                if (exactId == null && legacyId != null) {
                    writableDatabase.update(
                        "target_groups",
                        ContentValues().apply { put("whatsapp_package", pkg) },
                        "id=?", arrayOf(legacyId.toString())
                    )
                    exactId = legacyId
                } else if (exactId != null && legacyId != null && exactId != legacyId) {
                    writableDatabase.execSQL(
                        "UPDATE target_groups SET selected=MAX(selected,(SELECT selected FROM target_groups WHERE id=?)), extracted_count=MAX(extracted_count,(SELECT extracted_count FROM target_groups WHERE id=?)) WHERE id=?",
                        arrayOf<Any?>(legacyId, legacyId, exactId)
                    )
                    writableDatabase.delete("target_groups", "id=?", arrayOf(legacyId.toString()))
                }

                if (exactId == null) {
                    exactId = upsertGroupName(clean, discovered = true, whatsappPackage = pkg)
                }
                writableDatabase.update(
                    "target_groups",
                    ContentValues().apply {
                        put("unread_count", candidate.unreadCount.coerceAtLeast(0))
                        if (candidate.activityText == null) putNull("activity_text") else put("activity_text", candidate.activityText.take(120))
                        put("active", if (candidate.active) 1 else 0)
                        put("publishable", if (candidate.publishableHint) 1 else 0)
                        put("community_parent", if (candidate.communityParentHint) 1 else 0)
                        put("last_synced_at", now)
                        put("whatsapp_package", pkg)
                        if (candidate.jidOrGroupId == null) putNull("jid_or_group_id") else put("jid_or_group_id", candidate.jidOrGroupId)
                        put("sync_order", candidate.syncOrder.coerceAtLeast(0))
                        put("last_known_access_method", candidate.lastKnownAccessMethod.name)
                        put("sync_generation", syncGeneration)
                        put("stale", 0)
                        if (candidate.verifiedGroupHint) put("verified_group", 1)
                        if (candidate.lastKnownAccessMethod != GroupAccessMethod.UNKNOWN) {
                            put("preferred_access_method", candidate.lastKnownAccessMethod.name)
                        }
                    },
                    "id=?", arrayOf(exactId.toString())
                )
                if (!existedBefore) added++
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return added
    }

    fun finalizeGroupSync(whatsappPackage: String, syncGeneration: Long) {
        val pkg = whatsappPackage.trim()
        if (pkg.isBlank()) return
        // Only auto-discovered rows participate in the freshness sweep. Manual rows remain intact.
        // Missing rows are retained as history but hidden/unselected, so extraction/publish cannot
        // accidentally act on stale toolbar/system-card entries from older builds.
        writableDatabase.execSQL(
            """
            UPDATE target_groups
            SET stale=1, selected=0, publishable=0
            WHERE discovered=1 AND whatsapp_package=? AND sync_generation<>?
            """.trimIndent(),
            arrayOf<Any?>(pkg, syncGeneration)
        )
        writableDatabase.execSQL(
            "UPDATE target_groups SET stale=0 WHERE discovered=1 AND whatsapp_package=? AND sync_generation=?",
            arrayOf<Any?>(pkg, syncGeneration)
        )
    }

    private val groupSelectColumns = """
        id,name,selected,status,extracted_count,last_error,discovered,verified_group,
        unread_count,activity_text,active,publishable,community_parent,last_synced_at,
        jid_or_group_id,whatsapp_package,last_known_access_method,preferred_access_method,
        last_successful_open_method,access_success_count,access_failure_count,last_opened_at,sync_order,
        last_publish_status,last_published_at,last_publish_error,sync_generation,stale
    """.trimIndent().replace("\n", " ")

    fun getGroups(): List<TargetGroup> = queryGroups(
        "SELECT $groupSelectColumns FROM target_groups WHERE stale=0 ORDER BY CASE WHEN sync_order=2147483647 THEN 1 ELSE 0 END,sync_order,id",
        null
    )

    fun getSelectedGroups(): List<TargetGroup> = queryGroups(
        "SELECT $groupSelectColumns FROM target_groups WHERE selected=1 AND stale=0 ORDER BY CASE WHEN sync_order=2147483647 THEN 1 ELSE 0 END,sync_order,id",
        null
    )

    fun getSelectedPendingGroups(whatsappPackage: String? = null): List<TargetGroup> {
        val pkg = whatsappPackage?.trim().orEmpty()
        return if (pkg.isBlank()) {
            queryGroups(
                "SELECT $groupSelectColumns FROM target_groups WHERE selected=1 AND stale=0 AND status NOT IN ('COMPLETED','SKIPPED_NOT_GROUP') ORDER BY CASE WHEN sync_order=2147483647 THEN 1 ELSE 0 END,sync_order,id",
                null
            )
        } else {
            queryGroups(
                "SELECT $groupSelectColumns FROM target_groups WHERE selected=1 AND stale=0 AND status NOT IN ('COMPLETED','SKIPPED_NOT_GROUP') AND (whatsapp_package=? OR whatsapp_package='') ORDER BY CASE WHEN whatsapp_package=? THEN 0 ELSE 1 END,CASE WHEN sync_order=2147483647 THEN 1 ELSE 0 END,sync_order,id",
                arrayOf(pkg, pkg)
            )
        }
    }

    fun getGroupByName(name: String, whatsappPackage: String? = null): TargetGroup? {
        val pkg = whatsappPackage?.trim()
        val sql = if (pkg.isNullOrEmpty()) {
            "SELECT $groupSelectColumns FROM target_groups WHERE stale=0 AND name=? COLLATE NOCASE ORDER BY last_opened_at DESC,id LIMIT 1"
        } else {
            "SELECT $groupSelectColumns FROM target_groups WHERE stale=0 AND name=? COLLATE NOCASE AND (whatsapp_package=? OR whatsapp_package='') ORDER BY CASE WHEN whatsapp_package=? THEN 0 ELSE 1 END,last_opened_at DESC,id LIMIT 1"
        }
        val args = if (pkg.isNullOrEmpty()) arrayOf(name.trim()) else arrayOf(name.trim(), pkg, pkg)
        return queryGroups(sql, args).firstOrNull()
    }

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
                        lastSyncedAt = if (c.isNull(13)) null else c.getLong(13),
                        jidOrGroupId = if (c.isNull(14)) null else c.getString(14),
                        whatsappPackage = c.getString(15).orEmpty(),
                        lastKnownAccessMethod = runCatching { GroupAccessMethod.valueOf(c.getString(16)) }.getOrDefault(GroupAccessMethod.UNKNOWN),
                        preferredAccessMethod = runCatching { GroupAccessMethod.valueOf(c.getString(17)) }.getOrDefault(GroupAccessMethod.UNKNOWN),
                        lastSuccessfulOpenMethod = runCatching { GroupAccessMethod.valueOf(c.getString(18)) }.getOrDefault(GroupAccessMethod.UNKNOWN),
                        accessSuccessCount = c.getInt(19),
                        accessFailureCount = c.getInt(20),
                        lastOpenedAt = if (c.isNull(21)) null else c.getLong(21),
                        syncOrder = c.getInt(22),
                        lastPublishStatus = if (c.isNull(23)) null else c.getString(23),
                        lastPublishedAt = if (c.isNull(24)) null else c.getLong(24),
                        lastPublishError = if (c.isNull(25)) null else c.getString(25),
                        syncGeneration = c.getLong(26),
                        stale = c.getInt(27) == 1
                    )
                )
            }
        }

    fun updateGroupAccessSuccess(id: Long, method: GroupAccessMethod) {
        writableDatabase.execSQL(
            """
            UPDATE target_groups
            SET last_known_access_method=?, last_successful_open_method=?, preferred_access_method=?,
                access_success_count=access_success_count+1, last_opened_at=?
            WHERE id=?
            """.trimIndent(),
            arrayOf<Any?>(method.name, method.name, method.name, System.currentTimeMillis(), id)
        )
    }

    fun updateGroupAccessFailure(id: Long, attempted: GroupAccessMethod) {
        writableDatabase.execSQL(
            """
            UPDATE target_groups
            SET last_known_access_method=?, access_failure_count=access_failure_count+1
            WHERE id=?
            """.trimIndent(),
            arrayOf<Any?>(attempted.name, id)
        )
    }

    fun updateGroupIdentity(id: Long, jidOrGroupId: String?, whatsappPackage: String) {
        val pkg = whatsappPackage.trim()
        val currentName = readableDatabase.rawQuery("SELECT name FROM target_groups WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return
        val collision = if (pkg.isBlank()) null else readableDatabase.rawQuery(
            "SELECT id FROM target_groups WHERE name=? COLLATE NOCASE AND whatsapp_package=? AND id<>? LIMIT 1",
            arrayOf(currentName, pkg, id.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (collision != null) {
            writableDatabase.execSQL(
                "UPDATE target_groups SET selected=MAX(selected,(SELECT selected FROM target_groups WHERE id=?)), extracted_count=MAX(extracted_count,(SELECT extracted_count FROM target_groups WHERE id=?)) WHERE id=?",
                arrayOf<Any?>(id, id, collision)
            )
            writableDatabase.delete("target_groups", "id=?", arrayOf(id.toString()))
            return
        }
        writableDatabase.update(
            "target_groups",
            ContentValues().apply {
                if (jidOrGroupId == null) putNull("jid_or_group_id") else put("jid_or_group_id", jidOrGroupId)
                put("whatsapp_package", pkg)
            },
            "id=?", arrayOf(id.toString())
        )
    }

    fun updateGroupPublishState(id: Long, status: PublishStatus, error: String? = null) {
        writableDatabase.update(
            "target_groups",
            ContentValues().apply {
                put("last_publish_status", status.name)
                if (status in setOf(PublishStatus.SENT, PublishStatus.VERIFIED, PublishStatus.UNCERTAIN)) {
                    put("last_published_at", System.currentTimeMillis())
                }
                if (error.isNullOrBlank()) putNull("last_publish_error") else put("last_publish_error", error.take(500))
            },
            "id=?", arrayOf(id.toString())
        )
    }

    fun setGroupSelected(id: Long, selected: Boolean) {
        writableDatabase.update("target_groups", ContentValues().apply { put("selected", if (selected) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    fun setAllGroupsSelected(selected: Boolean) {
        writableDatabase.update("target_groups", ContentValues().apply { put("selected", if (selected) 1 else 0) }, "stale=0", null)
    }

    fun setSelectionPreset(preset: GroupSelectionPreset, whatsappPackage: String? = null) {
        val db = writableDatabase
        val pkg = whatsappPackage?.trim().orEmpty()
        val scope = if (pkg.isBlank()) " WHERE stale=0" else " WHERE stale=0 AND (whatsapp_package=? OR whatsapp_package='')"
        val args = if (pkg.isBlank()) emptyArray<Any?>() else arrayOf<Any?>(pkg)
        fun apply(expression: String) {
            db.execSQL("UPDATE target_groups SET selected=$expression$scope", args)
        }
        db.beginTransaction()
        try {
            when (preset) {
                GroupSelectionPreset.ALL -> apply("1")
                GroupSelectionPreset.NONE -> apply("0")
                GroupSelectionPreset.UNREAD -> apply("CASE WHEN unread_count>0 THEN 1 ELSE 0 END")
                GroupSelectionPreset.ACTIVE -> apply("CASE WHEN active=1 THEN 1 ELSE 0 END")
                GroupSelectionPreset.PUBLISHABLE -> apply("CASE WHEN publishable=1 AND community_parent=0 THEN 1 ELSE 0 END")
                GroupSelectionPreset.UNVERIFIED -> apply("CASE WHEN verified_group=0 THEN 1 ELSE 0 END")
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

    fun resetRunStatuses(whatsappPackage: String? = null) {
        val pkg = whatsappPackage?.trim().orEmpty()
        val packageClause = if (pkg.isBlank()) "" else " AND (whatsapp_package=? OR whatsapp_package='')"
        val args = if (pkg.isBlank()) emptyArray<Any?>() else arrayOf<Any?>(pkg)
        writableDatabase.execSQL(
            """
            UPDATE target_groups
            SET status=CASE WHEN discovered=1 AND verified_group=0 THEN 'DISCOVERED' ELSE 'PENDING' END,
                last_error=NULL
            WHERE selected=1 AND stale=0 AND status!='SKIPPED_NOT_GROUP'$packageClause
            """.trimIndent(),
            args
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
        links: List<LinkCandidate>,
        group: TargetGroup,
        timestamp: Long
    ): Int {
        if (links.isEmpty()) return 0
        val db = writableDatabase
        var insertedCount = 0
        db.beginTransaction()
        try {
            links.forEach { link ->
                val values = ContentValues().apply {
                    put("url", link.url)
                    put("normalized_url", link.normalizedUrl)
                    put("group_name", group.name)
                    put("occurrences", 1)
                    put("first_seen", timestamp)
                    put("last_seen", timestamp)
                    put("category", link.category.name)
                    if (link.inviteCode == null) putNull("invite_code") else put("invite_code", link.inviteCode)
                    put("source_group_id", group.id)
                    if (group.whatsappPackage.isBlank()) putNull("whatsapp_package") else put("whatsapp_package", group.whatsappPackage)
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
                        """
                        UPDATE extracted_links
                        SET last_seen=?,url=?,category=?,invite_code=?,source_group_id=?,whatsapp_package=?
                        WHERE normalized_url=? AND group_name=? COLLATE NOCASE
                        """.trimIndent(),
                        arrayOf<Any?>(
                            timestamp, link.url, link.category.name, link.inviteCode, group.id,
                            group.whatsappPackage.ifBlank { null }, link.normalizedUrl, group.name
                        )
                    )
                }
            }
            if (insertedCount > 0) {
                db.execSQL(
                    "UPDATE target_groups SET extracted_count=extracted_count+? WHERE id=?",
                    arrayOf<Any?>(insertedCount, group.id)
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
        SELECT id,url,normalized_url,group_name,occurrences,first_seen,last_seen,category,invite_code,source_group_id,whatsapp_package
        FROM extracted_links ORDER BY last_seen DESC LIMIT ?
        """.trimIndent(), arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                LinkRecord(
                    id = c.getLong(0), url = c.getString(1), normalizedUrl = c.getString(2), groupName = c.getString(3),
                    occurrences = c.getInt(4), firstSeen = c.getLong(5), lastSeen = c.getLong(6),
                    category = runCatching { LinkCategory.valueOf(c.getString(7)) }.getOrDefault(LinkCategory.OTHER),
                    inviteCode = if (c.isNull(8)) null else c.getString(8),
                    sourceGroupId = if (c.isNull(9)) null else c.getLong(9),
                    whatsappPackage = if (c.isNull(10)) null else c.getString(10)
                )
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
        val joined = counts[ScanStatus.JOINED.name] ?: 0
        val actionUncertain = counts[ScanStatus.ACTION_UNCERTAIN.name] ?: 0
        val already = counts[ScanStatus.ALREADY_MEMBER.name] ?: 0
        val invalid = (counts[ScanStatus.INVALID.name] ?: 0) + (counts[ScanStatus.FULL.name] ?: 0) +
            (counts[ScanStatus.REMOVED.name] ?: 0) + (counts[ScanStatus.ACCOUNT_LIMIT.name] ?: 0)
        val network = counts[ScanStatus.NETWORK_ERROR.name] ?: 0
        val unknown = counts[ScanStatus.UNKNOWN.name] ?: 0
        val accounted = pending + direct + approval + requestPending + joined + actionUncertain + already + invalid + network + unknown
        return ScanStats(total, pending, direct, approval, requestPending, joined, actionUncertain, already, invalid, network, unknown, (total - accounted).coerceAtLeast(0))
    }

    fun getStats(): ExtractionStats {
        fun scalar(sql: String): Int = readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return ExtractionStats(
            totalGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE selected=1 AND stale=0"),
            completedGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE selected=1 AND stale=0 AND status='COMPLETED'"),
            failedGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE selected=1 AND stale=0 AND status IN ('FAILED','FAILED_FINAL')"),
            totalUniqueLinks = scalar("SELECT COUNT(DISTINCT normalized_url) FROM extracted_links"),
            totalOccurrences = scalar("SELECT COUNT(*) FROM extracted_links"),
            syncedGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE discovered=1 AND stale=0"),
            unreadGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE stale=0 AND unread_count>0"),
            activeGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE stale=0 AND active=1"),
            publishableGroups = scalar("SELECT COUNT(*) FROM target_groups WHERE stale=0 AND publishable=1 AND community_parent=0")
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
        private const val DB_VERSION = 11
    }
}
