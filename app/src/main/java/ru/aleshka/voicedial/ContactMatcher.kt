package ru.aleshka.voicedial

import android.content.Context
import android.provider.ContactsContract
import kotlin.math.max

data class ContactEntry(
    val contactId: Long,
    val name: String,
    val number: String,
    val type: Int,
    /** Строка записной книжки с действием «позвонить в Telegram»; 0 — нет. */
    val telegramCallData: Long = 0L,
    /** MIME-тип этой строки: по нему видно, какой клиент её создал. */
    val telegramMime: String = "",
    /** Пакет клиента, добавившего строку (официальный Telegram или форк). */
    val telegramPackage: String = ""
)

data class ContactMatch(
    val entry: ContactEntry,
    val score: Double
)

object ContactMatcher {

    // Вес по типу номера: мобильный в приоритете.
    private fun typeWeight(type: Int): Double = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> 0.05
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> 0.04
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> 0.01
        else -> 0.0
    }

    fun loadContacts(context: Context): List<ContactEntry> {
        val result = ArrayList<ContactEntry>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numberIdx) ?: continue
                if (number.isBlank()) continue
                result.add(
                    ContactEntry(
                        contactId = cursor.getLong(idIdx),
                        name = name,
                        number = number,
                        type = cursor.getInt(typeIdx)
                    )
                )
            }
        }
        return result
    }

    /** Оставляет по одному (лучшему) номеру на контакт. */
    fun bestPerContact(entries: List<ContactEntry>): List<ContactEntry> {
        val map = LinkedHashMap<Long, ContactEntry>()
        for (e in entries) {
            val prev = map[e.contactId]
            if (prev == null || typeWeight(e.type) > typeWeight(prev.type)) {
                map[e.contactId] = e
            }
        }
        return map.values.toList()
    }

    fun normalize(raw: String): String =
        raw.lowercase()
            .replace('ё', 'е')
            .replace('й', 'и')
            .replace(Regex("[^а-яa-z0-9]"), "")

    /** Обрезает падежные окончания, чтобы «Никите» совпало с «Никита». */
    private fun stems(s: String): List<String> {
        val out = ArrayList<String>()
        val maxTrim = minOf(2, max(0, s.length - 3))
        for (trim in 0..maxTrim) {
            out.add(if (trim == 0) s else s.dropLast(trim))
        }
        return out
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun ratio(a: String, b: String): Double {
        val len = max(a.length, b.length)
        if (len == 0) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / len
    }

    fun score(queryRaw: String, nameRaw: String): Double {
        val q = normalize(queryRaw)
        val n = normalize(nameRaw)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        if (n == q) return 1.0
        if (n.startsWith(q) || q.startsWith(n)) return 0.92
        if (n.contains(q)) return 0.86
        // отдельные слова: «позвони маше сергеевой» → «маша сергеева»
        val nWords = nameRaw.lowercase().replace('ё', 'е')
            .split(Regex("[^а-яa-z0-9]+")).filter { it.isNotEmpty() }.map { normalize(it) }
        val qWords = queryRaw.lowercase().replace('ё', 'е')
            .split(Regex("[^а-яa-z0-9]+")).filter { it.isNotEmpty() }.map { normalize(it) }
        var wordBest = 0.0
        for (qw in qWords) {
            for (nw in nWords) {
                for (qs in stems(qw)) {
                    for (ns in stems(nw)) {
                        val r = ratio(qs, ns)
                        if (r > wordBest) wordBest = r
                    }
                }
            }
        }
        var stemBest = 0.0
        for (qs in stems(q)) {
            for (ns in stems(n)) {
                val r = ratio(qs, ns)
                if (r > stemBest) stemBest = r
            }
        }
        return max(wordBest, stemBest * 0.95)
    }

    fun find(entries: List<ContactEntry>, query: String, threshold: Double = 0.6): List<ContactMatch> {
        val scored = entries.map { ContactMatch(it, score(query, it.name)) }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
        // по одному на контакт
        val seen = HashSet<Long>()
        return scored.filter { seen.add(it.entry.contactId) }
    }

    private val numberWords = mapOf(
        "ноль" to "0", "один" to "1", "два" to "2", "три" to "3", "четыре" to "4",
        "пять" to "5", "шесть" to "6", "семь" to "7", "восемь" to "8", "девять" to "9",
        "нуль" to "0"
    )

    /** Пытается вытащить номер телефона из фразы: цифрами или словами. */
    fun extractNumber(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length >= 3) return digits
        val words = raw.lowercase().split(Regex("[^а-яa-z0-9]+"))
        val sb = StringBuilder()
        for (w in words) {
            val d = numberWords[w]
            if (d != null) sb.append(d) else if (w.all { it.isDigit() }) sb.append(w)
        }
        return if (sb.length >= 3) sb.toString() else null
    }

    /** Куда звонить: обычный телефонный звонок или Telegram. */
    enum class Target { PHONE, TELEGRAM }

    private fun words(raw: String): List<String> =
        raw.lowercase().split(Regex("[^а-яёa-z0-9]+")).filter { it.isNotEmpty() }

    private fun isTelegramWord(word: String): Boolean {
        val w = normalize(word)
        if (w == "тг" || w == "tg" || w == "telega" || w == "telegram") return true
        if (w.startsWith("телеграф")) return false
        // «телеграм», «телеграмме», «телега», «телеге», «телегу», «телегой»
        return w.startsWith("телеграм") || w.startsWith("телег") || w.startsWith("telegram")
    }

    /** Возвращает TELEGRAM, если во фразе назван мессенджер; иначе null. */
    fun detectTarget(raw: String): Target? =
        if (words(raw).any { isTelegramWord(it) }) Target.TELEGRAM else null

    private val messengerPrepositions = setOf("в", "во", "по", "через", "на")

    /** Убирает из фразы «в телеграм», «в телеге», «по тг» и т.п. */
    fun stripTarget(raw: String): String {
        val parts = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val drop = HashSet<Int>()
        for ((i, w) in parts.withIndex()) {
            if (isTelegramWord(w)) {
                drop.add(i)
                if (i > 0) {
                    val prev = words(parts[i - 1]).firstOrNull()
                    if (prev != null && messengerPrepositions.contains(prev)) drop.add(i - 1)
                }
            }
        }
        if (drop.isEmpty()) return raw
        return parts.filterIndexed { i, _ -> !drop.contains(i) }.joinToString(" ")
    }

    private val commandPrefixes = listOf(
        "позвони", "позвонить", "позвоните", "набери", "набрать", "наберите",
        "вызови", "вызвать", "соедини", "соединить", "звонок", "на", "к", "номер"
    )

    /** Убирает из фразы «позвони», «набери» и т.п., оставляя имя. */
    fun extractName(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.replace(Regex("[^а-яёa-z0-9\\s-]"), " ")
        var changed = true
        while (changed) {
            changed = false
            val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) break
            val first = words.first()
            if (commandPrefixes.contains(first)) {
                s = words.drop(1).joinToString(" ")
                changed = true
            }
        }
        return s.trim()
    }

    private val ordinalWords = mapOf(
        "первый" to 1, "первая" to 1, "первое" to 1, "один" to 1, "одна" to 1,
        "второй" to 2, "вторая" to 2, "второе" to 2, "два" to 2, "две" to 2,
        "третий" to 3, "третья" to 3, "три" to 3,
        "четвертый" to 4, "четвертая" to 4, "четыре" to 4
    )

    fun extractChoice(raw: String): Int? {
        val digits = raw.filter { it.isDigit() }
        if (digits.isNotEmpty()) return digits.first().digitToInt()
        for (w in raw.lowercase().split(Regex("[^а-яa-z0-9]+"))) {
            ordinalWords[w]?.let { return it }
        }
        return null
    }

    fun isCancel(raw: String): Boolean {
        val s = raw.lowercase()
        return s.contains("отмена") || s.contains("отменить") || s.contains("стоп") ||
            s.contains("не звони") || s.contains("отбой")
    }
}
