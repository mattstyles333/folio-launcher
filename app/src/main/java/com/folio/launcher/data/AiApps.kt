package com.folio.launcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri

enum class AiKind(
    val label: String,
    val packages: List<String>,
) {
    Grok("Grok", listOf("ai.x.grok")),
    ChatGpt("ChatGPT", listOf("com.openai.chatgpt")),
    Gemini(
        "Gemini",
        listOf("com.google.android.apps.gemini", "com.google.android.apps.bard"),
    ),
    Claude("Claude", listOf("com.anthropic.claude")),
}

object AiApps {
    fun installed(apps: List<LaunchableApp>): List<AiKind> =
        installedFrom(apps.map { it.packageName }.toSet())

    fun installedFrom(packages: Set<String>): List<AiKind> =
        AiKind.entries.filter { kind -> kind.packages.any { it in packages } }

    fun resolve(preferred: String, installed: List<AiKind>): AiKind? {
        if (installed.isEmpty()) return null
        return installed.firstOrNull { preferred in it.packages } ?: installed.first()
    }

    fun matchedPackage(kind: AiKind, packages: Set<String>): String? =
        kind.packages.firstOrNull { it in packages }

    fun cyclePackage(current: String, installed: List<AiKind>): String {
        if (installed.isEmpty()) return ""
        val idx = installed.indexOfFirst { current in it.packages }.let { if (it < 0) 0 else it }
        val next = installed[(idx + 1).mod(installed.size)]
        return next.packages.first()
    }

    fun suggestions(aiLabel: String, quote: String): List<Pair<String, String>> {
        val chips = ArrayList<Pair<String, String>>(4)
        chips += "Open $aiLabel" to ""
        val q = quote.trim()
        if (q.isNotEmpty()) {
            val label = if (q.length <= 36) q else q.take(34).trimEnd() + "…"
            chips += label to "What do you make of this: “$q”"
        }
        chips += "What's the weather" to "What's the weather, briefly."
        chips += "A toast" to "One short toast. Nothing else."
        return chips
    }

    fun open(host: Context, kind: AiKind, prompt: String, pkg: String): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        val text = prompt.trim()
        if (text.isNotEmpty()) {
            val encoded = Uri.encode(text)
            for (uri in viewUris(kind, encoded)) {
                if (start(host, viewIntent(uri, pkg, flags))) return true
            }
            for (uri in viewUris(kind, encoded)) {
                if (start(host, viewIntent(uri, packageName = null, flags))) return true
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                `package` = pkg
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(flags)
            }
            if (start(host, send)) return true
        }
        val launch = host.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(flags)
        return launch != null && start(host, launch)
    }

    internal fun viewUris(kind: AiKind, encoded: String): List<String> = when (kind) {
        AiKind.Grok -> listOf(
            "https://grok.com/?q=$encoded",
            "xai-grok://chat?q=$encoded",
        )
        AiKind.ChatGpt -> listOf(
            "chatgpt://new-chat?prompt=$encoded",
            "https://chatgpt.com/?q=$encoded",
        )
        AiKind.Gemini -> listOf(
            "https://gemini.google.com/app?q=$encoded",
        )
        AiKind.Claude -> listOf(
            "https://claude.ai/new?q=$encoded",
        )
    }

    private fun viewIntent(uri: String, packageName: String?, flags: Int): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(flags)
            if (packageName != null) `package` = packageName
        }
    }

    private fun start(host: Context, intent: Intent): Boolean {
        if (intent.resolveActivity(host.packageManager) == null) return false
        return runCatching { host.startActivity(intent); true }.getOrDefault(false)
    }
}
