package com.mwilky.airalerts

import org.json.JSONArray
import org.json.JSONObject

/** A caller, or a message sender, matched by the name the notification shows. [buzz] false keeps it quiet. */
data class ContactRule(val id: String, val name: String, val buzz: Boolean = true)

/** One app's notifications. [buzz] false keeps them quiet. */
data class AppRule(val packageName: String, val appName: String, val buzz: Boolean = true)

/**
 * Which calls and notifications buzz. Each page's default rows apply when no custom rule matches,
 * in this order: custom rule, then favourites, then the catch-all rows.
 */
internal object RuleMatch {
    /**
     * [isFavourite] and [isSaved] look the caller up in Contacts; [isSaved] is null when the app
     * can't read Contacts.
     */
    fun callBuzzes(
        settings: AlertSettings,
        caller: String,
        isFavourite: (String) -> Boolean,
        isSaved: (String) -> Boolean?
    ): Boolean {
        nameMatch(caller, settings.callerRules)?.let { return it.buzz }
        if (settings.callFavourites && caller.isNotBlank() && isFavourite(caller)) return true
        val saved = isSaved(caller) ?: !looksLikeNumber(caller)
        return if (saved) settings.callContacts else settings.callUnknown
    }

    fun notificationBuzzes(
        settings: AlertSettings,
        packageName: String,
        sender: String,
        isFavourite: (String) -> Boolean
    ): Boolean {
        nameMatch(sender, settings.senderRules)?.let { return it.buzz }
        if (settings.notifFavourites && sender.isNotBlank() && isFavourite(sender)) return true
        settings.appRules.firstOrNull { it.packageName.equals(packageName, ignoreCase = true) }?.let { return it.buzz }
        return settings.notifOthers
    }

    /**
     * Without Contacts access, a caller the dialer shows as a number (or nothing) is taken as
     * unknown: it shows the saved name for everyone in Contacts.
     */
    fun looksLikeNumber(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isEmpty() || trimmed.all { it.isDigit() || it in "+-() ." }
    }

    private fun nameMatch(incoming: String, rules: List<ContactRule>): ContactRule? {
        if (incoming.isBlank()) return null
        return rules.firstOrNull { contactNamesMatch(it.name, incoming) }
    }

    /**
     * Exact display-name match. Blank names never match, and a short name must
     * not match as part of a longer one ("Ann" vs "Joanne").
     */
    fun contactNamesMatch(ruleName: String, incomingName: String): Boolean {
        val rule = ruleName.trim()
        val incoming = incomingName.trim()
        if (rule.isEmpty() || incoming.isEmpty()) return false
        return rule.equals(incoming, ignoreCase = true)
    }
}

internal object RuleJson {
    fun encodeContacts(rules: List<ContactRule>): String = JSONArray().apply {
        rules.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("buzz", it.buzz)) }
    }.toString()

    fun decodeContacts(json: String?): List<ContactRule> = decode(json) { o ->
        ContactRule(id = o.getString("id"), name = o.getString("name"), buzz = o.optBoolean("buzz", true))
    }

    fun encodeApps(rules: List<AppRule>): String = JSONArray().apply {
        rules.forEach { put(JSONObject().put("package", it.packageName).put("name", it.appName).put("buzz", it.buzz)) }
    }.toString()

    fun decodeApps(json: String?): List<AppRule> = decode(json) { o ->
        AppRule(packageName = o.getString("package"), appName = o.getString("name"), buzz = o.optBoolean("buzz", true))
    }

    /** Skips entries it can't read rather than dropping the whole list. */
    private fun <T> decode(json: String?, read: (JSONObject) -> T): List<T> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> runCatching { read(array.getJSONObject(i)) }.getOrNull() }
    }
}
