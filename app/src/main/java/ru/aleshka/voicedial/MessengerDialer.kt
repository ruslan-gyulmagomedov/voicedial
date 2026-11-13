package ru.aleshka.voicedial

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import ru.aleshka.voicedial.ContactMatcher.Target

/**
 * Звонки через мессенджеры. Для Telegram и WhatsApp звонок запускается той
 * строкой «позвонить», которую мессенджер сам добавляет контакту в записную
 * книжку. Для ВКонтакте такой строки нет — там используется ссылка
 * https://vk.com/call?id=<числовой id>, а id берётся из контактов, которые
 * синхронизировал ВК.
 */
object MessengerDialer {

    private const val PREFS = "voicedial"

    /** Пакеты по умолчанию. Список — подстраховка, основной источник — система. */
    private val known = mapOf(
        Target.TELEGRAM to listOf(
            "tw.nekomimi.nekogram",
            "app.nicegram",
            "org.nicegram.messenger.app",
            "org.telegram.messenger.beta",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "org.telegram.messenger"
        ),
        Target.WHATSAPP to listOf("com.whatsapp", "com.whatsapp.w4b"),
        Target.VK to listOf("com.vkontakte.android")
    )

    /** Официальный клиент — только как предпочтение в списке строк. */
    private const val TELEGRAM_OFFICIAL = "org.telegram.messenger"

    fun displayName(target: Target): String = when (target) {
        Target.TELEGRAM -> "Telegram"
        Target.WHATSAPP -> "WhatsApp"
        Target.VK -> "ВКонтакте"
        else -> "телефон"
    }

    /** Как сказать «в …» вслух: «в Telegram», «в WhatsApp», «во ВКонтакте». */
    fun inPhrase(target: Target): String = when (target) {
        Target.TELEGRAM -> "в Telegram"
        Target.WHATSAPP -> "в WhatsApp"
        Target.VK -> "во ВКонтакте"
        else -> ""
    }

    private fun key(target: Target): String = when (target) {
        Target.TELEGRAM -> "telegram_client"
        Target.WHATSAPP -> "whatsapp_client"
        Target.VK -> "vk_client"
        else -> "phone_client"
    }

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Ссылка, по которой можно узнать обработчик мессенджера. */
    private fun probe(target: Target): Intent? = when (target) {
        Target.TELEGRAM -> Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve"))
        Target.WHATSAPP -> Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send"))
        else -> null
    }

    private fun linkHandlers(context: Context, intent: Intent): List<String> {
        val result = ArrayList<String>()
        try {
            for (info in context.packageManager.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (!result.contains(pkg)) result.add(pkg)
            }
        } catch (e: Exception) {
            // ничего не нашли — работаем по известным пакетам
        }
        return result
    }

    /**
     * Какая строка записной книжки принадлежит этому мессенджеру.
     * Возвращает пакет, создавший строку, или null.
     */
    private fun classify(mime: String, target: Target): String? {
        val m = mime.lowercase()
        // Видеозвонки не поддерживаются — только голосовые.
        if (m.contains("video.call")) return null
        return when (target) {
            Target.TELEGRAM ->
                if (m.endsWith(".android.call")) packageOfMime(mime) else null
            Target.WHATSAPP -> when {
                m.contains("whatsapp") && m.contains("call") ->
                    if (m.contains("w4b")) "com.whatsapp.w4b" else "com.whatsapp"
                else -> null
            }
            Target.VK ->
                if (m.contains("vkontakte") && m.contains("call")) "com.vkontakte.android" else null
            else -> null
        }
    }

    /** Клиенты, которые оставили в записной книжке строку «позвонить». */
    private fun contactClients(context: Context, target: Target): List<String> {
        val result = ArrayList<String>()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE),
                "${ContactsContract.Data.MIMETYPE} LIKE ?",
                arrayOf("%call%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(0) ?: continue
                    val pkg = classify(mime, target) ?: continue
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

    /** Все установленные клиенты мессенджера. */
    fun installedPackages(context: Context, target: Target): List<String> {
        val found = LinkedHashSet<String>()
        probe(target)?.let { found.addAll(linkHandlers(context, it)) }
        for (pkg in known[target].orEmpty()) {
            if (installed(context, pkg)) found.add(pkg)
        }
        found.addAll(contactClients(context, target))
        return found.toList()
    }

    fun isInstalled(context: Context, target: Target): Boolean =
        installedPackages(context, target).isNotEmpty()

    /**
     * Клиент, который система откроет по ссылке tg://, — приложение
     * «по умолчанию для ссылок». Для WhatsApp и ВК ссылки неоднозначны
     * (их открывает и браузер), поэтому там этот путь не используется.
     */
    fun defaultPackage(context: Context, target: Target): String? {
        if (target != Target.TELEGRAM) return null
        val intent = probe(target) ?: return null
        val byResolve = try {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        if (byResolve != null && linkHandlers(context, intent).contains(byResolve)) return byResolve
        return try {
            context.packageManager.queryIntentActivities(intent, 0)
                .firstOrNull { it.isDefault }
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
    }

    /** Клиент, который владелец выбрал голосом. */
    fun savedClient(context: Context, target: Target): String? {
        val pkg = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(target), null)
        } catch (e: Exception) {
            null
        }
        if (pkg.isNullOrEmpty() || !installed(context, pkg)) return null
        return pkg
    }

    fun chooseClient(context: Context, target: Target, pkg: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key(target), pkg).apply()
        } catch (e: Exception) {
            // не запомнили — спросим в следующий раз
        }
    }

    /** Долгое нажатие «Слушаю» сбрасывает выбор клиентов во всех мессенджерах. */
    fun forgetClients(context: Context) {
        try {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            for (t in listOf(Target.TELEGRAM, Target.WHATSAPP, Target.VK)) editor.remove(key(t))
            editor.apply()
        } catch (e: Exception) {
            // нечего сбрасывать
        }
    }

    /** Варианты для голосового выбора клиента. */
    fun clientOptions(context: Context, target: Target): List<String> =
        installedPackages(context, target)

    /**
     * Нужно ли спросить клиента голосом. Спрашиваем только у Telegram, и только
     * когда обработчика ссылок нет, владелец ещё не выбирал, а клиентов
     * несколько: у WhatsApp и ВК клиент один.
     */
    fun needsChoice(context: Context, target: Target): Boolean {
        if (target != Target.TELEGRAM) return false
        if (defaultPackage(context, target) != null) return false
        if (savedClient(context, target) != null) return false
        return installedPackages(context, target).size > 1
    }

    /**
     * Клиент, через который звоним. Для Telegram — приложение для ссылок tg://
     * по умолчанию, затем выбор владельца, затем единственный установленный.
     * Для WhatsApp и ВК — известный пакет по порядку предпочтения.
     */
    fun preferredPackage(context: Context, target: Target): String? {
        if (target == Target.TELEGRAM) {
            defaultPackage(context, target)?.let { return it }
            savedClient(context, target)?.let { return it }
            val clients = installedPackages(context, target)
            return if (clients.size == 1) clients.first() else null
        }
        for (pkg in known[target].orEmpty()) {
            if (installed(context, pkg)) return pkg
        }
        savedClient(context, target)?.let { return it }
        return installedPackages(context, target).firstOrNull()
    }

    /** Как клиент называется для голоса: «Telegram», «Nikogram», «WhatsApp». */
    fun clientLabel(context: Context, target: Target, pkg: String?): String {
        if (pkg.isNullOrEmpty()) return displayName(target)
        val appInfo = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val label = appInfo?.let { context.packageManager.getApplicationLabel(it).toString() }
        return if (label.isNullOrBlank()) displayName(target) else label
    }

    fun clientLabel(context: Context, target: Target): String =
        clientLabel(context, target, preferredPackage(context, target))

    /** «Звоню Роме в Nikogram» / «Звоню Роме во ВКонтакте». */
    fun callPhrase(context: Context, target: Target, name: String): String = when (target) {
        Target.TELEGRAM -> "Звоню $name в ${clientLabel(context, target)}."
        Target.WHATSAPP -> "Звоню $name в WhatsApp."
        Target.VK -> "Звоню $name во ВКонтакте."
        else -> "Звоню $name."
    }

    private fun packageOfMime(mime: String): String {
        var s = mime
        if (s.startsWith("vnd.android.cursor.item/vnd.")) {
            s = s.removePrefix("vnd.android.cursor.item/vnd.")
        }
        if (s.endsWith(".android.call")) s = s.removeSuffix(".android.call")
        return s
    }

    private data class CallRow(val dataId: Long, val mime: String, val pkg: String)

    /** Числовой id страницы ВК, который ВК сохранил в записной книжке. */
    private fun vkIds(context: Context): Map<Long, String> {
        val result = HashMap<Long, String>()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DATA2,
                    ContactsContract.Data.DATA3
                ),
                "${ContactsContract.Data.MIMETYPE} LIKE ?",
                arrayOf("%vkontakte%"),
                null
            )?.use { cursor ->
                val contactIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                val valueIdx = (1..3).map { cursor.getColumnIndex("data$it") }
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeIdx) ?: continue
                    // Строку «позвонить» разбираем отдельно — там не id.
                    if (mime.lowercase().contains("call")) continue
                    val values = valueIdx.map { i -> if (i >= 0) cursor.getString(i) else null }
                    val id = extractVkId(values) ?: continue
                    val contactId = cursor.getLong(contactIdx)
                    if (!result.containsKey(contactId)) result[contactId] = id
                }
            }
        } catch (e: Exception) {
            // ВК не синхронизирован с записной книжкой
        }
        return result
    }

    private fun extractVkId(values: List<String?>): String? {
        for (v in values) {
            if (v.isNullOrBlank()) continue
            val url = Regex("vk\\.com/(?:id)?(\\d{4,10})").find(v)
            if (url != null) return url.groupValues[1]
        }
        for (v in values) {
            val t = v?.trim().orEmpty()
            if (t.length in 4..10 && t.all { it.isDigit() }) return t
        }
        return null
    }

    /**
     * Контакты, которым мессенджер добавил действие «позвонить». Для ВК сюда
     * же попадают контакты, у которых есть числовой id страницы.
     */
    fun loadCallableContacts(context: Context, target: Target): List<ContactEntry> {
        val preferred = preferredPackage(context, target)
        val rowsByContact = LinkedHashMap<Long, MutableList<CallRow>>()

        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Data.MIMETYPE
                ),
                "${ContactsContract.Data.MIMETYPE} LIKE ?",
                arrayOf("%call%"),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
                val contactIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeIdx) ?: continue
                    val pkg = classify(mime, target) ?: continue
                    if (!installed(context, pkg)) continue
                    val row = CallRow(cursor.getLong(idIdx), mime, pkg)
                    rowsByContact.getOrPut(cursor.getLong(contactIdx)) { ArrayList() }.add(row)
                }
            }
        } catch (e: Exception) {
            // записная книжка недоступна
        }

        val vk = if (target == Target.VK) vkIds(context) else emptyMap()
        val contactIds = LinkedHashSet<Long>()
        contactIds.addAll(rowsByContact.keys)
        contactIds.addAll(vk.keys)
        if (contactIds.isEmpty()) return emptyList()

        val phones = ContactMatcher.bestPerContact(ContactMatcher.loadContacts(context))
            .associateBy { it.contactId }
        val missing = contactIds.filter { !phones.containsKey(it) }
        val extraNames = if (missing.isEmpty()) emptyMap() else loadNames(context, missing)

        return contactIds.mapNotNull { contactId ->
            val rows = rowsByContact[contactId].orEmpty()
            // Строка выбранного клиента, иначе — любого, кто её создал.
            val row = rows.firstOrNull { it.pkg == preferred }
                ?: rows.firstOrNull { it.pkg != TELEGRAM_OFFICIAL }
                ?: rows.firstOrNull()
            val phone = phones[contactId]
            val name = phone?.name ?: extraNames[contactId] ?: return@mapNotNull null
            val vkId = vk[contactId].orEmpty()
            if (target == Target.VK && row == null && vkId.isEmpty()) return@mapNotNull null
            ContactEntry(
                contactId = contactId,
                name = name,
                number = phone?.number.orEmpty(),
                type = phone?.type ?: 0,
                callData = row?.dataId ?: 0L,
                callMime = row?.mime.orEmpty(),
                callPackage = row?.pkg.orEmpty(),
                vkUserId = vkId
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

    fun callIntent(context: Context, entry: ContactEntry, target: Target): Intent? {
        val preferred = preferredPackage(context, target)

        if (entry.callData > 0L && entry.callMime.isNotEmpty()) {
            val base = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.contacts/data/${entry.callData}"),
                    entry.callMime
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Сначала выбранный клиент, затем клиент, создавший строку, затем
            // любой, кто эту строку откроет.
            for (pkg in listOf(preferred, entry.callPackage)) {
                if (pkg.isNullOrEmpty()) continue
                val toPkg = Intent(base).setPackage(pkg)
                if (context.packageManager.resolveActivity(toPkg, 0) != null) return toPkg
            }
            if (context.packageManager.resolveActivity(base, 0) != null) return base
        }

        if (target == Target.VK) {
            if (entry.vkUserId.isEmpty()) return null
            return Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://vk.com/call?id=${entry.vkUserId}")
            ).apply {
                if (!preferred.isNullOrEmpty()) setPackage(preferred)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        if (target != Target.TELEGRAM) return null

        val digits = entry.number.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val pkg = preferred ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("tg://call?phone=$digits")).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
