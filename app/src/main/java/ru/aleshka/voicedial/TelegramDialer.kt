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
     * находится сам, без знания его имени.
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

    private const val PREFS = "voicedial"
    private const val KEY_CLIENT = "telegram_client"

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
     * Клиент, который система откроет по ссылке tg://, — то самое приложение
     * «по умолчанию для ссылок». Спрашиваем у системы, а не угадываем:
     * resolveActivity возвращает ровно то, что откроется по ссылке. Если
     * возвращается экран выбора или ничего, обработчика по умолчанию нет.
     */
    fun defaultPackage(context: Context): String? {
        val byResolve = try {
            context.packageManager.resolveActivity(tgProbe(), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        // Экран выбора приложения обработчиком tg:// не является, поэтому в
        // список клиентов не попадает: если resolveActivity вернул не клиента,
        // значит, приложение по умолчанию не назначено и нужно спросить.
        if (byResolve != null && linkHandlers(context).contains(byResolve)) return byResolve
        // Запасной путь: система помечает обработчик по умолчанию флагом isDefault.
        return try {
            context.packageManager.queryIntentActivities(tgProbe(), 0)
                .firstOrNull { it.isDefault }
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
    }

    /** Клиент, который владелец выбрал голосом, когда обработчика по умолчанию нет. */
    fun savedClient(context: Context): String? {
        val pkg = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLIENT, null)
        } catch (e: Exception) {
            null
        }
        if (pkg.isNullOrEmpty() || !installed(context, pkg)) return null
        return pkg
    }

    fun chooseClient(context: Context, pkg: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CLIENT, pkg).apply()
        } catch (e: Exception) {
            // не запомнили — спросим в следующий раз
        }
    }

    fun forgetClient(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_CLIENT).apply()
        } catch (e: Exception) {
            // нечего сбрасывать
        }
    }

    /** Варианты для голосового выбора клиента. */
    fun clientOptions(context: Context): List<String> = installedClients(context)

    /**
     * Нужно ли спросить клиента голосом. Не спрашиваем, если система уже
     * назначила приложение для ссылок tg://, — звоним через него. Спрашиваем,
     * только когда обработчика по умолчанию нет, владелец ещё не выбирал и
     * клиентов установлено больше одного.
     */
    fun needsChoice(context: Context): Boolean {
        if (defaultPackage(context) != null) return false
        if (savedClient(context) != null) return false
        return installedClients(context).size > 1
    }

    /**
     * Клиент, через который звоним: приложение для ссылок tg:// по умолчанию,
     * затем выбор владельца, затем единственный установленный клиент. null —
     * клиента нет или выбор неоднозначен (тогда MainActivity спрашивает).
     */
    fun preferredPackage(context: Context): String? {
        defaultPackage(context)?.let { return it }
        savedClient(context)?.let { return it }
        val clients = installedClients(context)
        if (clients.size == 1) return clients.first()
        return null
    }

    /** Как клиент называется для голоса: «Telegram», «Nikogram». */
    fun clientLabel(context: Context, pkg: String?): String {
        if (pkg.isNullOrEmpty()) return "Telegram"
        val appInfo = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val label = appInfo?.let { context.packageManager.getApplicationLabel(it).toString() }
        return if (label.isNullOrBlank()) "Telegram" else label
    }

    fun clientLabel(context: Context): String = clientLabel(context, preferredPackage(context))

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
        val preferred = preferredPackage(context) ?: savedClient(context)

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
