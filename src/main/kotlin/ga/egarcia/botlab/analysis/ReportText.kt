package ga.egarcia.botlab.analysis

/** Plain-text report for the screen and the console. */
object ReportText {
    fun render(report: Report, env: List<EnvSignal>): String = buildString {
        appendLine("BEHAVIOUR  ${report.score}/100  ·  ${report.verdict.label}  (${report.eventCount} events)")
        if (report.findings.isEmpty()) appendLine(if (report.verdict == Verdict.NOT_ENOUGH_DATA) "  Interact with the arena above (taps, typing, scrolling, swiping)." else "  Nothing suspicious in the interaction data.")
        report.findings.forEach {
            appendLine("  [${it.severity.name}] ${it.title}")
            appendLine("      ${it.evidence}")
            appendLine("      ${it.meaning}")
        }
        appendLine()
        val order = listOf("high", "medium", "low", "info")
        appendLine("ENVIRONMENT  (${env.size} signals)")
        env.sortedBy { order.indexOf(it.severity).let { i -> if (i < 0) order.size else i } }.forEach {
            appendLine("  [${it.severity.uppercase()}] (${it.source}) ${it.title}")
            if (it.detail.isNotEmpty()) appendLine("      ${it.detail}")
        }
    }
}
