// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.phantom.db.core.exec

import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An Excel workbook with one sheet, written as it goes: the parts that never change, then the worksheet, whose rows
 * [DbExportFormat.XLSX] writes one at a time, so a whole table can go to it without being held in memory.
 * Numbers and booleans are Excel numbers and booleans; everything else is text, NULL an empty cell.
 */
object DbXlsx {
  /** Excel's limit for the text of one cell. */
  private const val MAX_TEXT = 32767

  /** [body] writes [format]'s text to [path]; for Excel that text is the worksheet, put in a workbook around it. */
  inline fun <T> file(path: Path, format: DbExportFormat, body: (Appendable) -> T): T {
    if (format != DbExportFormat.XLSX) return Files.newBufferedWriter(path).use(body)
    return ZipOutputStream(Files.newOutputStream(path)).use { zip ->
      parts(zip)
      zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
      val out = kept(zip).bufferedWriter()
      out.append(SHEET_HEAD)
      val result = body(out)
      out.append("</worksheet>")
      out.flush()
      zip.closeEntry()
      result
    }
  }

  /** The workbook's fixed parts. */
  fun parts(zip: ZipOutputStream) {
    for ((name, text) in PARTS) {
      zip.putNextEntry(ZipEntry(name))
      zip.write(text.toByteArray())
      zip.closeEntry()
    }
  }

  /** [zip] that its writer cannot close. */
  fun kept(zip: OutputStream): OutputStream = object : FilterOutputStream(zip) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = flush()
  }

  /** Row [number] (from 1) of the worksheet. */
  fun row(out: Appendable, number: Long, values: Array<out Any?>) {
    out.append("<row r=\"").append(number.toString()).append("\">")
    values.forEachIndexed { i, v ->
      val ref = column(i) + number
      when {
        v == null -> {}
        v is Boolean -> out.append("<c r=\"").append(ref).append("\" t=\"b\"><v>").append(if (v) "1" else "0").append("</v></c>")
        v is Number && finite(v) -> out.append("<c r=\"").append(ref).append("\"><v>").append(number(v)).append("</v></c>")
        else -> {
          val text = (if (v is ByteArray) Base64.getEncoder().encodeToString(v) else v.toString()).take(MAX_TEXT)
          out.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(text)).append("</t></is></c>")
        }
      }
    }
    out.append("</row>")
  }

  /** A, B, …, Z, AA, … for column [index] from 0. */
  internal fun column(index: Int): String {
    var n = index + 1
    val s = StringBuilder()
    while (n > 0) {
      s.append('A' + (n - 1) % 26)
      n = (n - 1) / 26
    }
    return s.reverse().toString()
  }

  private fun finite(v: Number) = !(v is Double && (v.isNaN() || v.isInfinite()) || v is Float && (v.isNaN() || v.isInfinite()))

  private fun number(v: Number): String = if (v is java.math.BigDecimal) v.toPlainString() else v.toString()

  /** Text for XML; characters XML 1.0 cannot hold are dropped. */
  private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

  const val SHEET_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
    "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"

  private val PARTS = listOf(
    "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""",
    "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""",
    "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
</workbook>""",
    "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""",
  )
}
