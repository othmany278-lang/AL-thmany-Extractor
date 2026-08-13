package com.althmany.extractor.export

import android.content.ContentResolver
import android.net.Uri
import com.althmany.extractor.data.LinkRecord
import com.althmany.extractor.data.ScanRecord
import com.althmany.extractor.data.PublishItem
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mime: String) {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv"),
    TXT("txt", "text/plain"),
    JSON("json", "application/json")
}

object ExportManager {
    fun export(resolver: ContentResolver, uri: Uri, format: ExportFormat, links: List<LinkRecord>) {
        when (format) {
            ExportFormat.CSV -> exportCsv(resolver, uri, links)
            ExportFormat.TXT -> exportTxt(resolver, uri, links)
            ExportFormat.JSON -> exportJson(resolver, uri, links)
            ExportFormat.XLSX -> exportXlsx(resolver, uri, links)
        }
    }

    fun exportScan(resolver: ContentResolver, uri: Uri, format: ExportFormat, items: List<ScanRecord>) {
        when (format) {
            ExportFormat.CSV -> exportScanCsv(resolver, uri, items)
            ExportFormat.TXT -> exportScanTxt(resolver, uri, items)
            ExportFormat.JSON -> exportScanJson(resolver, uri, items)
            ExportFormat.XLSX -> exportScanXlsx(resolver, uri, items)
        }
    }

    fun exportPublish(resolver: ContentResolver, uri: Uri, format: ExportFormat, items: List<PublishItem>) {
        when (format) {
            ExportFormat.CSV -> exportPublishCsv(resolver, uri, items)
            ExportFormat.TXT -> exportPublishTxt(resolver, uri, items)
            ExportFormat.JSON -> exportPublishJson(resolver, uri, items)
            ExportFormat.XLSX -> exportPublishXlsx(resolver, uri, items)
        }
    }

    private fun exportCsv(resolver: ContentResolver, uri: Uri, links: List<LinkRecord>) {
        resolver.openOutputStream(uri)?.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                w.write("URL,Normalized URL,Group,Verified Occurrences,First Seen,Last Seen\n")
                links.forEach { r ->
                    w.write(listOf(r.url, r.normalizedUrl, r.groupName, r.occurrences.toString(), r.firstSeen.toString(), r.lastSeen.toString())
                        .joinToString(",") { csv(it) })
                    w.newLine()
                }
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportTxt(resolver: ContentResolver, uri: Uri, links: List<LinkRecord>) {
        resolver.openOutputStream(uri)?.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                links.forEach { r ->
                    w.write("${r.url}\t${r.groupName}\t${r.normalizedUrl}")
                    w.newLine()
                }
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportJson(resolver: ContentResolver, uri: Uri, links: List<LinkRecord>) {
        resolver.openOutputStream(uri)?.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                w.write("[\n")
                links.forEachIndexed { i, r ->
                    w.write("  {\"url\":\"${json(r.url)}\",\"normalizedUrl\":\"${json(r.normalizedUrl)}\",\"group\":\"${json(r.groupName)}\",\"sourceCount\":${r.occurrences},\"firstSeen\":${r.firstSeen},\"lastSeen\":${r.lastSeen}}")
                    if (i != links.lastIndex) w.write(",")
                    w.newLine()
                }
                w.write("]\n")
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    /** Minimal standards-compliant XLSX using inline strings; no heavyweight spreadsheet dependency. */
    private fun exportXlsx(resolver: ContentResolver, uri: Uri, links: List<LinkRecord>) {
        resolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>
                """.trimIndent())
                put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                """.trimIndent())
                put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="Links" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                """.trimIndent())
                put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>
                """.trimIndent())

                val sheet = buildString {
                    append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                    row(1, listOf("URL", "Normalized URL", "Group", "Verified Occurrences", "First Seen", "Last Seen"))
                    links.forEachIndexed { i, r ->
                        row(i + 2, listOf(r.url, r.normalizedUrl, r.groupName, r.occurrences.toString(), r.firstSeen.toString(), r.lastSeen.toString()))
                    }
                    append("</sheetData></worksheet>")
                }
                put(zip, "xl/worksheets/sheet1.xml", sheet)
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportScanCsv(resolver: ContentResolver, uri: Uri, items: List<ScanRecord>) {
        resolver.openOutputStream(uri)?.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                w.write("URL,Invite Code,Status,Confidence,Kind,Source Group,Detected Group,Members,Signal,Detail,Attempts,Duration ms,Target Package,Scanned At\n")
                items.forEach { r ->
                    w.write(listOf(
                        r.normalizedUrl, r.inviteCode, r.status.labelAr, r.confidence.toString(), r.inviteKind.labelAr,
                        r.sourceGroup.orEmpty(), r.groupName.orEmpty(), r.memberCountText.orEmpty(), r.signalCode.orEmpty(),
                        r.detail.orEmpty(), r.attempts.toString(), r.durationMs?.toString().orEmpty(), r.targetPackage.orEmpty(),
                        r.scannedAt?.toString().orEmpty()
                    ).joinToString(",") { csv(it) })
                    w.newLine()
                }
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportScanTxt(resolver: ContentResolver, uri: Uri, items: List<ScanRecord>) {
        resolver.openOutputStream(uri)?.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                items.forEach { r ->
                    w.write("${r.normalizedUrl}\t${r.status.labelAr}\t${r.confidence}%\t${r.inviteKind.labelAr}\t${r.groupName.orEmpty()}\t${r.memberCountText.orEmpty()}\t${r.sourceGroup.orEmpty()}\t${r.detail.orEmpty()}")
                    w.newLine()
                }
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportScanJson(resolver: ContentResolver, uri: Uri, items: List<ScanRecord>) {
        resolver.openOutputStream(uri)?.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                w.write("[\n")
                items.forEachIndexed { i, r ->
                    w.write("  {\"url\":\"${json(r.normalizedUrl)}\",\"inviteCode\":\"${json(r.inviteCode)}\",\"status\":\"${json(r.status.name)}\",\"statusAr\":\"${json(r.status.labelAr)}\",\"confidence\":${r.confidence},\"inviteKind\":\"${json(r.inviteKind.name)}\",\"inviteKindAr\":\"${json(r.inviteKind.labelAr)}\",\"sourceGroup\":\"${json(r.sourceGroup.orEmpty())}\",\"detectedGroup\":\"${json(r.groupName.orEmpty())}\",\"memberCount\":\"${json(r.memberCountText.orEmpty())}\",\"signalCode\":\"${json(r.signalCode.orEmpty())}\",\"detail\":\"${json(r.detail.orEmpty())}\",\"attempts\":${r.attempts},\"durationMs\":${r.durationMs ?: 0},\"targetPackage\":\"${json(r.targetPackage.orEmpty())}\"}")
                    if (i != items.lastIndex) w.write(",")
                    w.newLine()
                }
                w.write("]\n")
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportScanXlsx(resolver: ContentResolver, uri: Uri, items: List<ScanRecord>) {
        resolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>
                """.trimIndent())
                put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                """.trimIndent())
                put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="Scan" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                """.trimIndent())
                put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>
                """.trimIndent())
                val sheet = buildString {
                    append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                    row(1, listOf("URL", "Invite Code", "Status", "Confidence", "Kind", "Source Group", "Detected Group", "Members", "Signal", "Detail", "Attempts", "Duration ms", "Target Package", "Scanned At"))
                    items.forEachIndexed { i, r ->
                        row(i + 2, listOf(
                            r.normalizedUrl, r.inviteCode, r.status.labelAr, r.confidence.toString(), r.inviteKind.labelAr,
                            r.sourceGroup.orEmpty(), r.groupName.orEmpty(), r.memberCountText.orEmpty(), r.signalCode.orEmpty(),
                            r.detail.orEmpty(), r.attempts.toString(), r.durationMs?.toString().orEmpty(), r.targetPackage.orEmpty(),
                            r.scannedAt?.toString().orEmpty()
                        ))
                    }
                    append("</sheetData></worksheet>")
                }
                put(zip, "xl/worksheets/sheet1.xml", sheet)
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportPublishCsv(resolver: ContentResolver, uri: Uri, items: List<PublishItem>) {
        resolver.openOutputStream(uri)?.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                w.write("Group,Status,Detail,Attempts,Verified,Sent At,Run ID\n")
                items.forEach { r ->
                    w.write(listOf(r.groupName, r.status.labelAr, r.detail.orEmpty(), r.attempts.toString(), r.verified.toString(), r.sentAt?.toString().orEmpty(), r.runId.toString()).joinToString(",") { csv(it) })
                    w.newLine()
                }
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportPublishTxt(resolver: ContentResolver, uri: Uri, items: List<PublishItem>) {
        resolver.openOutputStream(uri)?.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                items.forEach { r ->
                    w.write("${r.groupName}\t${r.status.labelAr}\t${r.attempts}\t${r.detail.orEmpty()}")
                    w.newLine()
                }
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportPublishJson(resolver: ContentResolver, uri: Uri, items: List<PublishItem>) {
        resolver.openOutputStream(uri)?.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                w.write("[\n")
                items.forEachIndexed { i, r ->
                    w.write("  {\"group\":\"${json(r.groupName)}\",\"status\":\"${json(r.status.name)}\",\"statusAr\":\"${json(r.status.labelAr)}\",\"detail\":\"${json(r.detail.orEmpty())}\",\"attempts\":${r.attempts},\"verified\":${r.verified},\"sentAt\":${r.sentAt ?: 0},\"runId\":${r.runId}}")
                    if (i != items.lastIndex) w.write(",")
                    w.newLine()
                }
                w.write("]\n")
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun exportPublishXlsx(resolver: ContentResolver, uri: Uri, items: List<PublishItem>) {
        resolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>
                """.trimIndent())
                put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                """.trimIndent())
                put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="Publish" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                """.trimIndent())
                put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>
                """.trimIndent())
                val sheet = buildString {
                    append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                    append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                    row(1, listOf("Group", "Status", "Detail", "Attempts", "Verified", "Sent At", "Run ID"))
                    items.forEachIndexed { i, r ->
                        row(i + 2, listOf(r.groupName, r.status.labelAr, r.detail.orEmpty(), r.attempts.toString(), r.verified.toString(), r.sentAt?.toString().orEmpty(), r.runId.toString()))
                    }
                    append("</sheetData></worksheet>")
                }
                put(zip, "xl/worksheets/sheet1.xml", sheet)
            }
        } ?: error("تعذر فتح ملف التصدير")
    }

    private fun StringBuilder.row(index: Int, cells: List<String>) {
        append("<row r=\"").append(index).append("\">")
        cells.forEachIndexed { col, value ->
            append("<c r=\"").append(columnName(col + 1)).append(index)
                .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(xml(value)).append("</t></is></c>")
        }
        append("</row>")
    }

    private fun columnName(index: Int): String {
        var n = index
        val out = StringBuilder()
        while (n > 0) {
            n--
            out.append(('A'.code + n % 26).toChar())
            n /= 26
        }
        return out.reverse().toString()
    }

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun csv(s: String) = "\"${s.replace("\"", "\"\"")}\""
    private fun json(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
