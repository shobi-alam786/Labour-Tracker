package com.shobi.labourtracker

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// One row = one activity, one day, one block
data class DailyEntry(
    val date: LocalDate,
    val block: String,      // A..G
    val subBlock: String,   // A06
    val drrCode: String,
    val activity: String,
    val skilled: Int,
    val unskilled: Int,
    val progress: Int = 0
)

data class Totals(val skilled: Int, val unskilled: Int)

object Summary {
    fun total(entries: List<DailyEntry>) =
        Totals(entries.sumOf { it.skilled }, entries.sumOf { it.unskilled })

    // Total up to and including this date
    fun projectTotal(entries: List<DailyEntry>, upTo: LocalDate) =
        total(entries.filter { !it.date.isAfter(upTo) })

    fun byBlock(entries: List<DailyEntry>): Map<String, Totals> =
        entries.groupBy { it.block }.toSortedMap().mapValues { total(it.value) }
}

object WhatsAppReport {
    private val dateFmt = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
    private const val LINE = "------------------------------"

    private fun row(a: String, b: String, skl: String, unskl: String) =
        a.padEnd(5) + b.take(14).padEnd(15) + skl.padStart(4) + unskl.padStart(6)

    // Report for ONE block (each TM sends this)
    fun blockReport(block: String, date: LocalDate, all: List<DailyEntry>): String {
        val blockEntries = all.filter { it.block == block }
        val today = blockEntries.filter { it.date == date }
        val t = Summary.total(today)
        val p = Summary.projectTotal(blockEntries, date)

        val sb = StringBuilder()
        sb.appendLine("*DAILY REPORT - BLOCK $block*")
        sb.appendLine("Date: ${date.format(dateFmt)}")
        sb.appendLine()
        sb.appendLine("```")
        sb.appendLine(row("Sub", "Activity", "Skl", "Unsk"))
        sb.appendLine(LINE)
        today.groupBy { it.drrCode }.forEach { (_, list) ->
            val f = list.first()
            val s = Summary.total(list)
            sb.appendLine(row(f.subBlock, f.activity, s.skilled.toString(), s.unskilled.toString()))
        }
        sb.appendLine(LINE)
        sb.appendLine(row("", "Today total", t.skilled.toString(), t.unskilled.toString()))
        sb.appendLine(row("", "Project total", p.skilled.toString(), p.unskilled.toString()))
        sb.append("```")
        return sb.toString()
    }

    // Report for ALL blocks (admin view)
    fun allBlocksReport(date: LocalDate, all: List<DailyEntry>): String {
        fun r(b: String, a: String, c: String, d: String, e: String) =
            b.padEnd(6) + a.padStart(5) + c.padStart(6) + d.padStart(7) + e.padStart(6)

        val todayByBlock = Summary.byBlock(all.filter { it.date == date })
        val projByBlock = Summary.byBlock(all.filter { !it.date.isAfter(date) })

        val sb = StringBuilder()
        sb.appendLine("*DAILY REPORT - ALL BLOCKS*")
        sb.appendLine("Date: ${date.format(dateFmt)}")
        sb.appendLine()
        sb.appendLine("```")
        sb.appendLine(r("Block", "Skl", "Unsk", "P.Skl", "P.Unsk"))
        sb.appendLine(LINE)
        projByBlock.keys.forEach { b ->
            val t = todayByBlock[b] ?: Totals(0, 0)
            val p = projByBlock.getValue(b)
            sb.appendLine(r(b, t.skilled.toString(), t.unskilled.toString(),
                p.skilled.toString(), p.unskilled.toString()))
        }
        sb.appendLine(LINE)
        val tt = Summary.total(all.filter { it.date == date })
        val pt = Summary.projectTotal(all, date)
        sb.appendLine(r("TOTAL", tt.skilled.toString(), tt.unskilled.toString(),
            pt.skilled.toString(), pt.unskilled.toString()))
        sb.append("```")
        return sb.toString()
    }
}

// Opens WhatsApp with the report text ready to send
fun shareToWhatsApp(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent(intent).setPackage("com.whatsapp"))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent.createChooser(intent, "Share report"))
    }
}
