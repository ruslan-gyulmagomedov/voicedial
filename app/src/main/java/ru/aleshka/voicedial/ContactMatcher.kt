package ru.aleshka.voicedial

import android.content.Context
import android.provider.ContactsContract
import kotlin.math.max

data class ContactEntry(
    val contactId: Long,
    val name: String,
    val number: String,
    val type: Int,
    /** Строка записной книжки с действием «позвонить» в мессенджере; 0 — нет. */
    val callData: Long = 0L,
    /** MIME-тип этой строки: по нему видно, какой мессенджер её создал. */
    val callMime: String = "",
    /** Пакет приложения, добавившего строку. */
    val callPackage: String = "",
    /** Числовой id страницы во ВКонтакте, если контакт синхронизирован с ВК. */
    val vkUserId: String = ""
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

    /**
     * Падежные окончания русских слов — от длинных к коротким. Отрезаем
     * самое длинное подходящее: «Никите» → «никит», «Никитой» → «никит»,
     * «Ивановой» → «иванов», «Сергеевичу» → «сергеевич». После этого
     * «позвони Никите» и контакт «Никита» дают один и тот же стебель.
     */
    private val caseEndings = listOf(
        "иями", "ями", "ами", "ией", "иям", "иях", "ием",
        "ого", "его", "ому", "ему", "ыми", "ими",
        "ая", "яя", "ое", "ее", "ые", "ие", "ой", "ей", "ий", "ый", "ых", "их",
        "у", "ю", "а", "я", "о", "е", "ы", "и", "ь", "й"
    ).sortedByDescending { it.length }

    /** Отрезает падежное окончание, оставляя основу слова. */
    private fun stem(word: String): String {
        for (e in caseEndings) {
            if (word.length - e.length >= 2 && word.endsWith(e)) {
                return word.dropLast(e.length)
            }
        }
        return word
    }

    /**
     * Уменьшительные имена: «Саша» — это «Александр», «Маша» — «Мария»,
     * «Петя» — «Пётр». Ключи и значения — уже основы слов.
     */
    private val nicknames = mapOf(
        "саш" to "александр", "маш" to "мари", "миш" to "михаил", "дим" to "дмитри",
        "лен" to "елен", "наташ" to "натал", "кат" to "екатерин", "пет" to "петр",
        "вас" to "васил", "ван" to "иван", "ан" to "анн", "ол" to "ольг",
        "люб" to "любов", "тан" to "татьян", "сереж" to "серге", "ген" to "геннади",
        "юр" to "юри", "кол" to "никола", "тол" to "анатол", "вит" to "виктор",
        "вов" to "владимир", "слав" to "вячеслав", "бор" to "борис", "паш" to "павел",
        "гриш" to "григори", "зин" to "зинаид", "над" to "надежд", "сон" to "софи",
        "свет" to "светлан", "гал" to "галин", "рит" to "маргарит", "юл" to "юли",
        "ром" to "роман", "влад" to "владимир", "стас" to "станислав"
    )

    private fun canonical(stemWord: String): String = nicknames[stemWord] ?: stemWord

    /**
     * Возможные основы слова. Творительный падеж после нормализации даёт
     * «никитои»/«машеи», поэтому кроме обычной основы пробуем отрезать
     * «ои»/«еи»/«аи»: «Машей» — это «Маша», а не «Маше».
     */
    private fun stemsOf(word: String): List<String> {
        val out = ArrayList<String>(6)
        fun add(w: String) {
            if (w.length >= 2 && !out.contains(w)) out.add(w)
        }
        add(word)
        // «й» на конце ведёт себя как «и»: «Сергей» / «Сергею».
        add(word.replace('й', 'и'))
        for (base in out.toList()) {
            add(stem(base))
            if (base.length > 4) {
                for (tail in listOf("ои", "еи", "аи", "уи")) {
                    if (base.endsWith(tail)) {
                        add(base.dropLast(tail.length))
                        break
                    }
                }
            }
        }
        return out
    }

    /**
     * Похожесть двух слов с учётом падежей, уменьшительных форм и опечаток
     * распознавания: «Никите»/«Никита» — 0.96, «Саше»/«Александр» — 0.9.
     */
    private fun tokenSim(a: String, b: String): Double {
        if (a == b) return 1.0
        var best = 0.0
        for (sa in stemsOf(a)) {
            for (sb in stemsOf(b)) {
                if (sa == sb) return 0.96
                if (canonical(sa) == canonical(sb)) return 0.9
                var r = ratio(sa, sb)
                // «Оле» и «Олег», «Иван» и «Иванов» отличаются одной буквой
                // на конце — это разные люди, а не падеж. Такое совпадение
                // сильно слабее: настоящие уменьшительные разбирает словарь
                // выше («Рома» → «Роман»).
                val extra = when {
                    sb.length > sa.length && sb.startsWith(sa) -> sb.substring(sa.length)
                    sa.length > sb.length && sa.startsWith(sb) -> sa.substring(sb.length)
                    else -> ""
                }
                if (extra.isNotEmpty() && extra.length <= 2 &&
                    extra.all { it !in "аеиоуыэюя" }
                ) {
                    r -= 0.25
                }
                best = max(best, r)
            }
        }
        return best
    }

    /** Слова, которые ничего не говорят о контакте: «позвони мне, пожалуйста». */
    private val fillers = setOf(
        "мне", "пожалуйста", "это", "ну", "сейчас", "срочно", "потом", "уже",
        "прямо", "просто", "давай"
    )

    /** Разбивает фразу на слова для сравнения по частям имени. */
    private fun tokenize(raw: String): List<String> =
        raw.lowercase()
            .replace('ё', 'е')
            .split(Regex("[^а-яa-z0-9]+"))
            .filter { it.isNotEmpty() }

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

    /**
     * Сравнивает фразу с именем контакта по словам, а не по всей строке.
     * «Позвони Гюльмагомедову» находит «Руслан Гюльмагомедов», «позвони
     * Руслану Гюльмагомедову» и «позвони Гюльмагомедову Руслану» — один и
     * тот же контакт, а лишние слова вроде «мне» и «пожалуйста» не мешают.
     */
    fun score(queryRaw: String, nameRaw: String): Double {
        val qAll = tokenize(queryRaw)
        val nWords = tokenize(nameRaw)
        if (qAll.isEmpty() || nWords.isEmpty()) return 0.0
        // Служебные слова («позвони», «мне», «в телеграм») на совпадение не
        // влияют: имя ищется по тому, что осталось.
        val qWords = qAll.filterIndexed { i, w ->
            val prev = qAll.getOrNull(i - 1)
            val next = qAll.getOrNull(i + 1)
            val afterNext = qAll.getOrNull(i + 2)
            // «сап» и «вот» из разбитого «вот сап» — тоже название мессенджера.
            val messenger = isMessengerWord(w, prev) ||
                (w in whatsappSplitPrefixes && next != null && isWhatsappWord(next, w))
            // Предлог перед названием мессенджера («в ватсап», «во вконтакте»).
            val preposition = w in messengerPrepositions && next != null &&
                (isMessengerWord(next, w) ||
                    (next in whatsappSplitPrefixes && afterNext != null &&
                        isWhatsappWord(afterNext, next)))
            w !in fillers && w !in commandPrefixes && !messenger && !preposition
        }.ifEmpty { qAll }

        // Та же строка целиком — точное совпадение.
        if (qWords.joinToString("") == nWords.joinToString("")) return 1.0

        // Для каждого слова запроса — лучшая похожесть на любое слово имени.
        val bests = DoubleArray(qWords.size)
        for ((i, qw) in qWords.withIndex()) {
            var best = 0.0
            for (nw in nWords) {
                val s = tokenSim(qw, nw)
                if (s > best) best = s
            }
            bests[i] = best
        }

        // Максимум — чтобы лишнее слово запроса не обнуляло попадание по
        // фамилии; среднее с весом длины — чтобы частичное совпадение по
        // одному слову из двух не притворялось полным.
        var maxv = 0.0
        var sum = 0.0
        var weight = 0.0
        var minv = 1.0
        for (i in qWords.indices) {
            val w = qWords[i].length.toDouble()
            maxv = max(maxv, bests[i])
            minv = minOf(minv, bests[i])
            sum += bests[i] * w
            weight += w
        }
        val avg = if (weight == 0.0) 0.0 else sum / weight
        var s = 0.75 * maxv + 0.25 * avg

        // Все слова запроса уверенно нашли своё слово в имени — это «имя и
        // фамилия», сильнее, чем совпадение по одному слову.
        if (qWords.size >= 2 && minv >= 0.85) s = max(s, 0.97)
        return s.coerceIn(0.0, 1.0)
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

    /** Куда звонить: обычный телефонный звонок или мессенджер. */
    enum class Target { PHONE, TELEGRAM, WHATSAPP, VK, MAX }

    private fun words(raw: String): List<String> =
        raw.lowercase().split(Regex("[^а-яёa-z0-9]+")).filter { it.isNotEmpty() }

    private fun isTelegramWord(word: String): Boolean {
        val w = normalize(word)
        if (w.isEmpty()) return false
        if (w == "тг" || w == "tg" || w == "телегра" || w == "телеграмм") return true
        if (w.startsWith("телеграф")) return false
        // «инстаграм» — не мессенджер, звонков не бывает
        if (w.contains("инстаграм") || w.contains("instagram")) return false

        // «телеграм», «телеграмме», «телега», «телеге», «телегу», «телегой»
        if (w.startsWith("телеграм") || w.startsWith("телег") || w.startsWith("telegram")) return true

        // Единицы веса и прочие слова с «грам» — не мессенджеры.
        for (bad in listOf("килограмм", "миллиграмм", "сантиграмм", "дециграмм", "программ")) {
            if (w.contains(bad)) return false
        }

        // Название клиента знать не нужно: у любого клиента Telegram — форка
        // тоже — в названии есть «грам»/«gram», в любом падеже. Это же спасает,
        // когда распознавание речи слегка исказило название («никаграм»).
        return w.contains("грам") || w.contains("gram")
    }

    /**
     * WhatsApp: «ватсап», «вотсап», «вацап», «whatsapp» в любом падеже.
     * Распознавание речи часто пишет слитно или через дефис, поэтому
     * сравниваем по началу слова.
     */
    private fun isWhatsappWord(w: String, prev: String?): Boolean {
        if (w.isEmpty()) return false
        for (stem in listOf(
            "ватсап", "вотсап", "вацап", "васап", "вотсаб", "ватсаб",
            "whatsapp", "whatsap", "watsapp", "watsap", "vatsap"
        )) {
            if (w.startsWith(stem)) return true
        }
        // «вот сап», «ват сап» — распознавание разбило слово на два.
        if ((w == "сап" || w == "ап") && prev != null &&
            (prev == "вот" || prev == "ват" || prev == "вотс" || prev == "ватс")
        ) return true
        return false
    }

    /**
     * ВКонтакте: «вк», «вконтакте», «в контакте», «vk». Слово «контакте»
     * считается мессенджером только после предлога — иначе это обычное слово.
     */
    private fun isVkWord(w: String, prev: String?): Boolean {
        if (w.isEmpty()) return false
        if (w == "вк" || w == "vk" || w == "вконтакте" || w == "vkontakte") return true
        if (w.startsWith("вконтакт") || w.startsWith("vkontakt")) return true
        if ((w == "контакте" || w == "контакт") && prev != null && (prev == "в" || prev == "во")) return true
        return false
    }

    /** Предлоги, после которых «Макс» — название мессенджера, а не имя. */
    private val maxPrepositions = setOf("в", "во", "через", "по")

    /**
     * MAX: «в Макс», «в Максе», «через Макс». «Макс» — ещё и частое имя
     * (Максим), поэтому мессенджером слово считается только после предлога:
     * «позвони Максу» — это контакт, «позвони Максу в Макс» — звонок в MAX.
     * Формы перечислены точно, чтобы «Максим» никогда не считался клиентом.
     */
    private fun isMaxWord(w: String, prev: String?): Boolean {
        if (w.isEmpty() || prev == null) return false
        if (prev !in maxPrepositions) return false
        return w == "макс" || w == "максе" || w == "максу" ||
            w == "макса" || w == "максом" || w == "max"
    }

    private fun isMessengerWord(word: String, prev: String? = null): Boolean =
        isTelegramWord(word) || isWhatsappWord(normalize(word), prev) ||
            isVkWord(normalize(word), prev) || isMaxWord(normalize(word), prev)

    /** Возвращает мессенджер, если он назван во фразе; иначе null. */
    fun detectTarget(raw: String): Target? {
        val ws = words(raw).map { normalize(it) }
        for ((i, w) in ws.withIndex()) {
            val prev = ws.getOrNull(i - 1)
            when {
                isTelegramWord(w) -> return Target.TELEGRAM
                isWhatsappWord(w, prev) -> return Target.WHATSAPP
                isVkWord(w, prev) -> return Target.VK
                isMaxWord(w, prev) -> return Target.MAX
            }
        }
        return null
    }

    private val messengerPrepositions = setOf("в", "во", "по", "через", "на")

    /** Первые половинки разбитого распознаванием «вот сап». */
    private val whatsappSplitPrefixes = setOf("вот", "ват", "вотс", "ватс")

    /** Убирает из фразы «в телеграм», «в вотсап», «во вконтакте», «по тг». */
    fun stripTarget(raw: String): String {
        val parts = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val norms = parts.map { words(it).map { w -> normalize(w) }.firstOrNull().orEmpty() }
        val drop = HashSet<Int>()
        for ((i, w) in norms.withIndex()) {
            val prev = norms.getOrNull(i - 1)?.takeIf { it.isNotEmpty() }
            if (isMessengerWord(w, prev)) drop.add(i)
        }
        // «в вот сап»: второй кусок помечен — первый кусок и предлог перед ним тоже.
        for (i in norms.indices) {
            if (norms[i] in whatsappSplitPrefixes && drop.contains(i + 1)) drop.add(i)
        }
        // Предлог перед названием мессенджера: «в ватсап», «во вконтакте».
        for (i in norms.indices) {
            if (norms[i] in messengerPrepositions && drop.contains(i + 1)) drop.add(i)
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
