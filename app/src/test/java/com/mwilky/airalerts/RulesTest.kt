package com.mwilky.airalerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val base = AlertSettings(calls = true, notifications = true)
    private val noFavourites: (String) -> Boolean = { false }

    @Test
    fun callerRuleWinsOverDefaults() {
        val settings = base.copy(
            callContacts = false,
            callerRules = listOf(ContactRule("1", "Mum", buzz = true), ContactRule("2", "Work", buzz = false))
        )
        assertTrue(RuleMatch.callBuzzes(settings, " mum ", noFavourites) { true })
        assertFalse(RuleMatch.callBuzzes(settings.copy(callContacts = true), "Work", noFavourites) { true })
        assertFalse(RuleMatch.callBuzzes(settings, "Mummy", noFavourites) { true })
    }

    @Test
    fun favouritesBuzzEvenWhenContactsAreOff() {
        val settings = base.copy(callContacts = false, callFavourites = true)
        assertTrue(RuleMatch.callBuzzes(settings, "Alex", { it == "Alex" }) { true })
        assertFalse(RuleMatch.callBuzzes(settings, "Sam", { it == "Alex" }) { true })
    }

    @Test
    fun savedAndUnknownCallers() {
        val settings = base.copy(callContacts = true, callUnknown = false)
        assertTrue(RuleMatch.callBuzzes(settings, "Sam", noFavourites) { true })
        assertFalse(RuleMatch.callBuzzes(settings, "07700 900123", noFavourites) { false })
        // No Contacts access: a number is unknown, a name is a contact.
        assertFalse(RuleMatch.callBuzzes(settings, "+44 7700 900123", noFavourites) { null })
        assertTrue(RuleMatch.callBuzzes(settings, "Sam", noFavourites) { null })
        assertFalse(RuleMatch.callBuzzes(settings, "", noFavourites) { null })
    }

    @Test
    fun notificationOrder() {
        val settings = base.copy(
            notifOthers = false,
            senderRules = listOf(ContactRule("1", "Mum", buzz = true)),
            appRules = listOf(AppRule("com.whatsapp", "WhatsApp", buzz = true), AppRule("com.insta", "Instagram", buzz = false))
        )
        assertTrue(RuleMatch.notificationBuzzes(settings, "com.insta", "Mum", noFavourites))
        assertTrue(RuleMatch.notificationBuzzes(settings, "com.whatsapp", "Sam", noFavourites))
        assertFalse(RuleMatch.notificationBuzzes(settings.copy(notifOthers = true), "com.insta", "Sam", noFavourites))
        assertFalse(RuleMatch.notificationBuzzes(settings, "com.other", "Sam", noFavourites))
        assertTrue(RuleMatch.notificationBuzzes(settings.copy(notifFavourites = true), "com.insta", "Alex") { it == "Alex" })
    }

    @Test
    fun rulesSurviveJsonAndSkipBadEntries() {
        val contacts = listOf(ContactRule("a", "Mum"), ContactRule("b", "Work", buzz = false))
        assertEquals(contacts, RuleJson.decodeContacts(RuleJson.encodeContacts(contacts)))
        val apps = listOf(AppRule("com.whatsapp", "WhatsApp", buzz = false))
        assertEquals(apps, RuleJson.decodeApps(RuleJson.encodeApps(apps)))
        assertEquals(listOf(ContactRule("a", "Mum")), RuleJson.decodeContacts("""[{"id":"a","name":"Mum"},{"name":"no id"}]"""))
        assertEquals(emptyList<AppRule>(), RuleJson.decodeApps("not json"))
    }
}
