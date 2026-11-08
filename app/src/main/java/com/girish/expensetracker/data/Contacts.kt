package com.girish.expensetracker.data

import android.content.Context
import android.provider.ContactsContract
import com.girish.expensetracker.core.Contact

/** Reads the phone's contacts (display name + numbers) for on-device matching. Nothing is stored or sent. */
object Contacts {
    fun read(context: Context): List<Contact> {
        val numbers = HashMap<Long, MutableList<String>>()
        val names = LinkedHashMap<Long, String>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null,
        )?.use { c -> while (c.moveToNext()) c.getString(1)?.takeIf { it.isNotBlank() }?.let { names[c.getLong(0)] = it } }
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
        )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { numbers.getOrPut(c.getLong(0)) { mutableListOf() } += it } }
        return names.map { (id, name) -> Contact(name, numbers[id].orEmpty()) }
    }
}
