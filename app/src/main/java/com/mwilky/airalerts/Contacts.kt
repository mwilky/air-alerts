package com.mwilky.airalerts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * Contacts lookups by display name. Calls and notifications only carry the
 * name the phone shows, so that's what rules, favourites and saved contacts are matched on; two
 * contacts sharing a name count as one.
 */
internal object Contacts {
    fun hasAccess(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** True when a starred contact has this name. False without Contacts access. */
    fun isFavourite(context: Context, name: String): Boolean =
        hasAccess(context) && exists(context, name, starredOnly = true)

    /** Whether a contact has this name, or null without Contacts access. */
    fun isSaved(context: Context, name: String): Boolean? =
        if (hasAccess(context)) exists(context, name, starredOnly = false) else null

    /** The display name of a contact picked with the system picker, which needs no permission. */
    fun pickedName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun exists(context: Context, name: String, starredOnly: Boolean): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val starred = if (starredOnly) " AND ${ContactsContract.Contacts.STARRED} = 1" else ""
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ? COLLATE NOCASE$starred",
                arrayOf(trimmed),
                null
            )?.use { it.moveToFirst() }
        }.getOrNull() == true
    }
}
