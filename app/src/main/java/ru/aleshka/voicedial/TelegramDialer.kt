package ru.aleshka.voicedial

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

object TelegramDialer {

    /** Официальный клиент Telegram. */
    const val OFFICIAL = "org.telegram.messenger"

    /**
     * Известные клиенты, включая форки. Список нужен только как подстраховка:
     * основной способ найти клиент — спросить систему, кто открывает ссылки
     * tg://, так что любой незнакомый форк (Nikogram, Nekogram и т.п.)
     * находится сам.
     */
    private val knownPackages = listOf(
        "tw.nekomimi.nekogram",
        "app.nicegram",
        "org.nicegram.messenger.app",
        "org.telegram.messenger.beta",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        OFFICIAL
    )

    private const val MIME_PREFIX = "vnd.android.cursor.item/vnd."
    private const val MIME_SUFFIX = ".android.call"
    private const val MIME_VIDEO_SUFFIX = ".android.call.video"

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun tgProbe(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve"))

    /** Пакеты всех приложений, которые умеют открывать ссылки tg://. */
    private fun linkHandlers(context: Context): List<String> {
        val result = ArrayList<String>()
        try {
            for (info in context.packageManager.queryIntentActivities(tgProbe(), 0)) {
                val pkg = info.activityInfo.packageName
                if (!result.contains(pkg)) result.add(pkg)
            }
        } catch (e: Exception) {
            // ничего не нашли — работаем по известным пакетам
        }
        return result
    }

    /** Клиенты, которые оставили в записной книжке строку «позвонить». */
    private fun contactClients(context: Context): List<String> {
        val result = ArrayList<String>()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE),
                "${ContactsContract.Data.MIMETYPE} LIKE ?",
                arrayOf("%$MIME_SUFFIX%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(0) ?: continue
                    val pkg = packageOfMime(mime)
                    if (pkg.isNotEmpty() && installed(context, pkg) && !result.contains(pkg)) {
                        result.add(pkg)
                    }
                }
            }
        } catch (e: Exception) {
            // записная книжка недоступна — обойдёмся без этого источника
        }
        return result
    }

    /** Все установленные клиенты Telegram, включая любые форки. */
    fun installedClients(context: Context): List<String> {
        val found = LinkedHashSet<String>()
        found.addAll(linkHandlers(context))
        for (pkg in knownPackages) {
            if (installed(context, pkg)) found.add(pkg)
        }
        found.addAll(contactClients(context))
        return found.toList()
    }

    fun isInstalled(context: Context): Boolean = installedClients(context).isNotEmpty()

    /**
     * Клиент, через который звоним. Сначала сторонний клиент (Nikogram, Nekogram,
     * Nicegram и подобные) — именно его владелец ставил вместо официального;
     * официальный Telegram — только если других нет.
     */
    fun preferredPackage(context: Context): String? {
        val available = installedClients(context)
        if (available.isEmpty()) return null

        val resolved = try {
            context.packageManager.resolveActivity(tgProbe(), 0)?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        if (resolved != null && resolved != OFFICIAL && available.contains(resolved)) return resolved
        available.firstOrNull { it != OFFICIAL }?.let { return it }
        if (resolved != null && available.contains(resolved)) return resolved
        return available.first()
    }

    /** Как клиент называется для голоса: «Telegram», «Nikogram». */
    fun clientLabel(context: Context): String {
        val pkg = preferredPackage(context) ?: return "Telegram"
        val appInfo = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val label = appInfo?.let { context.packageManager.getApplicationLabel(it).toString() }
        return if (label.isNullOrBlank()) "Telegram" else label
    }

    private fun packageOfMime(mime: String): String {
        var s = mime
        if (s.startsWith(MIME_PREFIX)) s = s.removePrefix(MIME_PREFIX)
        if (s.endsWith(MIME_VIDEO_SUFFIX)) s = s.removeSuffix(MIME_VIDEO_SUFFIX)
        if (s.endsWith(MIME_SUFFIX)) s = s.removeSuffix(MIME_SUFFIX)
        return s
    }

    private data class CallRow(val dataId: Long, val mime: String, val pkg: String)

    /** Контакты, для которых клиент Telegram добавил действие «позвонить». */
    fun loadCallableContacts(context: Context): List<ContactEntry> {
        val preferred = preferredPackage(context)
        val rowsByContact = LinkedHashMap<Long, MutableList<CallRow>>()

        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE
            ),
            "${ContactsContract.Data.MIMETYPE} LIKE ?",
            arrayOf("%$MIME_SUFFIX%"),
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
            val contactIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIdx) ?: continue
                val pkg = packageOfMime(mime)
                if (!installed(context, pkg)) continue
                val row = CallRow(cursor.getLong(idIdx), mime, pkg)
                rowsByContact.getOrPut(cursor.getLong(contactIdx)) { ArrayList() }.add(row)
            }
        }
        if (rowsByContact.isEmpty()) return emptyList()

        val phones = ContactMatcher.bestPerContact(ContactMatcher.loadContacts(context))
            .associateBy { it.contactId }
        val missing = rowsByContact.keys.filter { !phones.containsKey(it) }
        val extraNames = if (missing.isEmpty()) emptyMap() else loadNames(context, missing)

        return rowsByContact.mapNotNull { (contactId, rows) ->
            // Строка выбранного клиента, иначе — строка стороннего клиента,
            // и только потом строка официального Telegram.
            val row = rows.firstOrNull { it.pkg == preferred }
                ?: rows.firstOrNull { it.pkg != OFFICIAL }
                ?: rows.first()
            val phone = phones[contactId]
            val name = phone?.name ?: extraNames[contactId] ?: return@mapNotNull null
            ContactEntry(
                contactId = contactId,
                name = name,
                number = phone?.number.orEmpty(),
                type = phone?.type ?: 0,
                telegramCallData = row.dataId,
                telegramMime = row.mime,
                telegramPackage = row.pkg
            )
        }
    }

    private fun loadNames(context: Context, ids: List<Long>): Map<Long, String> {
        val result = HashMap<Long, String>()
        val placeholders = ids.joinToString(",") { "?" }
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts._ID} IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
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

    fun callIntent(context: Context, entry: ContactEntry): Intent? {
        val preferred = preferredPackage(context)

        if (entry.telegramCallData > 0L && entry.telegramMime.isNotEmpty()) {
            val base = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.contacts/data/${entry.telegramCallData}"),
                    entry.telegramMime
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Сначала выбранный клиент, затем клиент, создавший строку, затем
            // любой, кто эту строку откроет.
            for (pkg in listOf(preferred, entry.telegramPackage)) {
                if (pkg.isNullOrEmpty()) continue
                val toPkg = Intent(base).setPackage(pkg)
                if (context.packageManager.resolveActivity(toPkg, 0) != null) return toPkg
            }
            if (context.packageManager.resolveActivity(base, 0) != null) return base
        }

        val digits = entry.number.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val pkg = preferred ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("tg://call?phone=$digits")).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
