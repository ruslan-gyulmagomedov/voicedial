package ru.aleshka.voicedial

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * Звонки в Telegram.
 *
 * Telegram для Android не даёт отдельного API звонков, но кладёт в записную
 * книжку на каждого синхронизированного пользователя строку с действием
 * «позвонить». Открытие этой строки системным интентом сразу начинает звонок.
 * Запасной путь — схема `tg://call?phone=<номер>`, которую понимает Telegram
 * для Android; по ней звонок пойдёт, если номер есть в контактах Telegram.
 */
object TelegramDialer {

    const val MIME_CALL = "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call"
    const val MIME_VIDEO = "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call.video"

    private val packages = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.thunderdog.challegram"
    )

    fun installedPackage(context: Context): String? =
        packages.firstOrNull {
            try {
                context.packageManager.getPackageInfo(it, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /** Контакты, для которых Telegram добавил действие «позвонить». */
    fun loadCallableContacts(context: Context): List<ContactEntry> {
        // contactId -> id строки Data с действием звонка (обычный звонок в приоритете)
        val dataByContact = LinkedHashMap<Long, Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE
            ),
            "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            arrayOf(MIME_CALL, MIME_VIDEO),
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
            val contactIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(contactIdx)
                val dataId = cursor.getLong(idIdx)
                val mime = cursor.getString(mimeIdx)
                if (mime == MIME_CALL || !dataByContact.containsKey(contactId)) {
                    dataByContact[contactId] = dataId
                }
            }
        }
        if (dataByContact.isEmpty()) return emptyList()

        val phones = ContactMatcher.bestPerContact(ContactMatcher.loadContacts(context))
            .associateBy { it.contactId }
        val missing = dataByContact.keys.filter { !phones.containsKey(it) }
        val extraNames = if (missing.isEmpty()) emptyMap() else loadNames(context, missing)

        return dataByContact.mapNotNull { (contactId, dataId) ->
            val phone = phones[contactId]
            val name = phone?.name ?: extraNames[contactId] ?: return@mapNotNull null
            ContactEntry(
                contactId = contactId,
                name = name,
                number = phone?.number.orEmpty(),
                type = phone?.type ?: 0,
                telegramCallData = dataId
            )
        }
    }

    private fun loadNames(context: Context, ids: List<Long>): Map<Long, String> {
        val result = HashMap<Long, String>()
        val selection = "${ContactsContract.Contacts._ID} IN (${ids.joinToString(",")})"
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            selection,
            null,
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                result[cursor.getLong(idIdx)] = name
            }
        }
        return result
    }

    /**
     * Интент, который начинает звонок. Сначала — действие из записной книжки,
     * затем `tg://call?phone=`. null — Telegram не установлен или нечем звонить.
     */
    fun callIntent(context: Context, entry: ContactEntry): Intent? {
        if (entry.telegramCallData > 0L) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.contacts/data/${entry.telegramCallData}"),
                    MIME_CALL
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.resolveActivity(intent, 0) != null) return intent
        }
        val digits = entry.number.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val pkg = installedPackage(context) ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("tg://call?phone=$digits")).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
