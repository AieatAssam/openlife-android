// Page layout is a list of pixel positions and sizes; naming each one would
// not make the synthetic pages clearer.
@file:Suppress("MagicNumber")

package org.openlife.eval.ocr.fixtures

import java.awt.Color
import java.awt.Font
import java.awt.geom.Rectangle2D
import java.util.Locale
import java.util.Random

/**
 * Synthetic page content for eval/ocr/v1. Every name, address, number and
 * message is invented here; example.org addresses and 0100 555 numbers are
 * reserved or obviously fictional. Nothing comes from a real person.
 */
internal class PageBuilder(private val fonts: FixtureFonts, val width: Int, val height: Int) {
    val lines = mutableListOf<LineSpec>()
    val decorations = mutableListOf<Decoration>()

    fun text(text: String, font: Font, x: Double, baseline: Double, ink: Color = INK) {
        lines += LineSpec(text, font, x, baseline, ink)
    }

    fun right(text: String, font: Font, rightEdge: Double, baseline: Double, ink: Color = INK) =
        text(text, font, rightEdge - advance(text, font), baseline, ink)

    fun centre(text: String, font: Font, baseline: Double, ink: Color = INK) =
        text(text, font, (width - advance(text, font)) / 2.0, baseline, ink)

    fun advance(text: String, font: Font): Double = FixtureRenderer.advance(text, font, fonts)

    fun box(x: Double, y: Double, w: Double, h: Double, color: Color, filled: Boolean = true, radius: Double = 0.0) {
        decorations += Decoration(Rectangle2D.Double(x, y, w, h), color, filled, radius)
    }

    fun page(gray: Boolean, background: Color, skew: Double = 0.0, noise: Double = 0.0, pageHeight: Int = height) =
        PageSpec(width, pageHeight, gray, background, decorations.toList(), lines.toList(), skew, noise)

    companion object {
        val INK = Color(28, 28, 30)
    }
}

internal object ScreenshotContent {
    private const val WIDTH = 1080
    private const val HEIGHT = 2400
    private val muted = Color(110, 110, 116)

    private val messages = listOf(
        "Are we still on for Friday?", "Yes, see you at 7.30", "Can you bring the receipts?",
        "Running ten minutes late", "Table booked for four", "The parcel arrived today",
        "Meeting moved to 14:00", "Thanks, got it", "Call me after lunch", "Train leaves at 08:15",
        "Did you pay the gas bill?", "Paid it this morning", "Bring an umbrella", "Room 4B on the left",
        "Dentist is on Tuesday", "I will pick up the keys", "Parking is behind the shop", "See you there",
    )
    private val cyrillicMessages = listOf(
        "Привет, как дела?", "Встреча завтра в кафе", "Во сколько?", "В семь вечера",
        "Хорошо, до встречи", "Спасибо!", "Я опоздаю на десять минут", "Ничего страшного",
        "Где ты сейчас?", "Уже иду",
    )
    private val names = listOf(
        "Alex Example",
        "Sam Sample",
        "Jo Placeholder",
        "Robin Test",
        "Kim Fixture",
        "Lee Synthetic",
    )
    private val subjects = listOf(
        "Your order has shipped",
        "Invoice 2027-0412 is ready",
        "Reminder: school trip form",
        "Change to your booking",
        "Your library loan is due",
    )
    private val bodyLines = listOf(
        "Thank you for your order with us.", "Your parcel is on its way today.", "It should arrive within two days.",
        "The invoice total is 84.20 this month.", "Please pay by 30 April 2027.",
        "Reply to this email with any questions.",
        "The trip leaves the school at 09:00.", "Children need a packed lunch.", "Your booking moved to 15:30.",
        "Bring your reference number with you.", "Opening hours are 9.00 to 17.00.", "We look forward to seeing you.",
        "The book is due back on 12 May.", "You can renew it twice online.",
    )
    private val places = listOf(
        "Harbour Clinic",
        "Northside Dental",
        "Elm Street Surgery",
        "City Physio",
        "Riverside Optician",
    )

    fun build(index: Int, fonts: FixtureFonts, random: Random): PageSpec {
        val page = PageBuilder(fonts, WIDTH, HEIGHT)
        page.text(clock(random), fonts.sans.deriveFont(34f), 48.0, 66.0)
        when {
            index == CYRILLIC_CHAT -> chat(page, fonts, random, cyrillicMessages, "Анна Пример", "Сегодня")
            index % 3 == 0 -> chat(page, fonts, random, messages, names[random.nextInt(names.size)], "Today")
            index % 3 == 1 -> email(page, fonts, random, cyrillicSignature = index == MIXED_EMAIL)
            else -> appointment(page, fonts, random)
        }
        return page.page(gray = false, background = Color(250, 249, 246))
    }

    private fun chat(
        page: PageBuilder,
        fonts: FixtureFonts,
        random: Random,
        pool: List<String>,
        contact: String,
        day: String,
    ) {
        page.box(0.0, 100.0, WIDTH.toDouble(), 130.0, Color(238, 235, 228))
        page.text(contact, fonts.sansSemibold.deriveFont(46f), 60.0, 185.0)
        page.centre(day, fonts.sans.deriveFont(32f), 310.0, muted)
        val font = fonts.sans.deriveFont(42f)
        var top = 360.0
        val count = (8 + random.nextInt(3)).coerceAtMost(pool.size)
        pick(random, pool, count).forEachIndexed { i, message ->
            val bubbleWidth = page.advance(message, font) + 64.0
            require(bubbleWidth < WIDTH - 96) { "message too long: $message" }
            val outgoing = i % 2 == 1
            val left = if (outgoing) WIDTH - 48.0 - bubbleWidth else 48.0
            page.box(
                left,
                top,
                bubbleWidth,
                98.0,
                if (outgoing) Color(47, 98, 196) else Color(229, 229, 234),
                radius = 40.0,
            )
            page.text(message, font, left + 32.0, top + 64.0, if (outgoing) Color.WHITE else PageBuilder.INK)
            top += 98.0 + 30.0 + random.nextInt(40)
        }
    }

    private fun email(page: PageBuilder, fonts: FixtureFonts, random: Random, cyrillicSignature: Boolean) {
        val sender = names[random.nextInt(names.size)]
        page.text("Inbox", fonts.sansSemibold.deriveFont(44f), 60.0, 170.0)
        page.text(subjects[random.nextInt(subjects.size)], fonts.sansSemibold.deriveFont(46f), 60.0, 280.0)
        val meta = fonts.sans.deriveFont(36f)
        page.text("From: $sender", meta, 60.0, 360.0, muted)
        page.text("To: me", meta, 60.0, 415.0, muted)
        page.text("Date: ${day(random)} 2027, ${clock(random)}", meta, 60.0, 470.0, muted)
        page.box(60.0, 505.0, WIDTH - 120.0, 2.0, Color(210, 206, 198))
        val body = fonts.sans.deriveFont(38f)
        var baseline = 590.0
        for (line in pick(random, bodyLines, 6 + random.nextInt(4))) {
            require(page.advance(line, body) < WIDTH - 120) { "body line too long: $line" }
            page.text(line, body, 60.0, baseline)
            baseline += 64.0
        }
        page.text("Best regards,", body, 60.0, baseline + 40.0)
        page.text(if (cyrillicSignature) "Олег" else sender.substringBefore(' '), body, 60.0, baseline + 104.0)
    }

    private fun appointment(page: PageBuilder, fonts: FixtureFonts, random: Random) {
        page.box(48.0, 140.0, WIDTH - 96.0, 1320.0, Color.WHITE, radius = 36.0)
        page.text("Appointment confirmed", fonts.sansSemibold.deriveFont(52f), 96.0, 260.0)
        val body = fonts.sans.deriveFont(40f)
        val place = places[random.nextInt(places.size)]
        val rows = listOf(
            place,
            "Date: ${day(random)} 2027",
            "Time: ${clock(random)}",
            "Reference: APT-${10_000 + random.nextInt(90_000)}",
            "${1 + random.nextInt(98)} Example Road, Testford",
            "Please arrive 10 minutes early.",
            "Bring this message with you.",
            "Reply CANCEL to cancel.",
        )
        var baseline = 380.0
        rows.forEach {
            page.text(it, body, 96.0, baseline)
            baseline += 110.0
        }
        page.box(96.0, baseline, 520.0, 110.0, Color(47, 98, 196), radius = 55.0)
        page.text("Add to calendar", fonts.sansSemibold.deriveFont(40f), 140.0, baseline + 70.0, Color.WHITE)
    }

    private const val CYRILLIC_CHAT = 6
    private const val MIXED_EMAIL = 13
}

internal object ReceiptContent {
    private const val WIDTH = 576
    private const val COLUMNS = 34
    private const val LINE_HEIGHT = 36.0

    private val shops = listOf(
        "CORNER GROCER",
        "TESTFORD MARKET",
        "EXAMPLE HARDWARE",
        "SAMPLE PHARMACY",
        "PLACEHOLDER CAFE",
    )
    private val items = listOf(
        "MILK 1L", "BREAD WHOLEMEAL", "COFFEE BEANS 250G", "APPLES 1KG", "PASTA 500G", "TOMATOES TIN",
        "RICE 1KG", "ORANGE JUICE", "BATTERIES AA X4", "SCREWS 40MM", "TOOTHPASTE", "PLASTERS",
        "FLAT WHITE", "CROISSANT", "SOAP BAR", "TEA BAGS X80", "CHEESE 200G", "LIGHT BULB",
    )

    fun build(index: Int, fonts: FixtureFonts, random: Random): PageSpec {
        val mono = fonts.mono.deriveFont(24f)
        val currency = when (index) {
            2, 13 -> "€"
            5 -> "£"
            9 -> "¥"
            else -> ""
        }
        val rows = mutableListOf<String>()
        rows += shops[random.nextInt(shops.size)]
        rows += "${1 + random.nextInt(98)} EXAMPLE STREET"
        rows += "TEL 0100 555 0${100 + random.nextInt(900)}"
        rows += "DATE ${1 + random.nextInt(28)}/0${1 + random.nextInt(9)}/2027 ${clock(random)}"
        var total = 0
        for (item in pick(random, items, 6 + random.nextInt(5))) {
            val pence = 49 + random.nextInt(1200)
            total += pence
            rows += columns(item, money(pence))
        }
        if (index == STRAY_WORD) {
            total += 999
            rows.add(6, columns("ПЛЯЖ TOWEL", money(999)))
        }
        val vat = total / 6
        rows += columns("SUBTOTAL", money(total - vat))
        rows += columns("VAT 20%", money(vat))
        rows += columns("TOTAL", currency + money(total))
        rows += "CARD PAYMENT"
        if (index == STRAY_GLYPH) rows += "REF Ω${1000 + random.nextInt(9000)}"
        rows += "THANK YOU"

        val height = (110 + rows.size * LINE_HEIGHT).toInt()
        val page = PageBuilder(fonts, WIDTH, height)
        rows.forEachIndexed { i, row ->
            val level = 30 + random.nextInt(50)
            val ink = Color(level, level, level)
            val baseline = 70.0 + i * LINE_HEIGHT
            if (i == 0 || row == "THANK YOU") {
                page.centre(row, mono, baseline, ink)
            } else {
                page.text(row, mono, 44.0, baseline, ink)
            }
        }
        val paper = 236 + random.nextInt(13)
        val skew = (random.nextInt(61) - 30) / 10.0
        val noise = (20 + random.nextInt(31)) / 10_000.0
        return page.page(gray = true, background = Color(paper, paper, paper), skew = skew, noise = noise)
    }

    private fun columns(left: String, right: String): String {
        require(left.length + right.length < COLUMNS) { "receipt row too wide: $left" }
        return left + " ".repeat(COLUMNS - left.length - right.length) + right
    }

    private fun money(pence: Int): String = String.format(Locale.ROOT, "%d.%02d", pence / 100, pence % 100)

    private const val STRAY_WORD = 6
    private const val STRAY_GLYPH = 11
}

internal object LetterContent {
    private const val WIDTH = 1240
    private const val HEIGHT = 1754
    private const val MARGIN = 100.0

    private val organisations = listOf(
        "Testford Council",
        "Example Water Ltd",
        "Sample Lane School",
        "Placeholder Insurance",
    )
    private val sentences = listOf(
        "Thank you for your recent letter about your account.",
        "Your next payment of 42.50 is due on 3 April 2027.",
        "Please keep this letter for your records.",
        "If any of these details are wrong, contact our office.",
        "The meeting will take place in room 4B at 10:00.",
        "Our opening hours are 9.00 to 17.00, Monday to Friday.",
        "Bring this letter and a form of identification.",
        "We have received your form and it is complete.",
        "Your reference number is shown at the top of this page.",
        "There is nothing more you need to do at this stage.",
        "The new collection day for your street is Thursday.",
        "A copy of this letter has been sent to your address.",
    )
    private val greek = listOf(
        "Αγαπητέ κύριε Δοκιμαστή,",
        "Σας ευχαριστούμε για την επιστολή σας.",
        "Η συνάντηση θα γίνει την Τρίτη στις δέκα.",
        "Παρακαλούμε φέρτε αυτό το έγγραφο μαζί σας.",
        "Η πληρωμή σας έχει καταχωρηθεί.",
        "Για ερωτήσεις καλέστε το γραφείο μας.",
        "Με εκτίμηση,",
        "Γιώργος Παράδειγμα",
    )
    private val forms = setOf(1, 4, 6, 9)

    fun build(index: Int, fonts: FixtureFonts, random: Random): PageSpec {
        val page = PageBuilder(fonts, WIDTH, HEIGHT)
        when {
            index == GREEK_LETTER -> greekLetter(page, fonts)
            index in forms -> form(page, fonts, random)
            else -> letter(page, fonts, random)
        }
        return page.page(gray = true, background = Color(252, 252, 250))
    }

    private fun letter(page: PageBuilder, fonts: FixtureFonts, random: Random) {
        val body = fonts.sans.deriveFont(32f)
        page.text(organisations[random.nextInt(organisations.size)], fonts.sansSemibold.deriveFont(44f), MARGIN, 150.0)
        page.box(MARGIN, 180.0, WIDTH - 2 * MARGIN, 3.0, Color(60, 60, 60))
        val right = WIDTH - MARGIN
        page.right("${1 + random.nextInt(98)} Example Road", body, right, 260.0)
        page.right("Testford TE1 ${1 + random.nextInt(9)}XX", body, right, 308.0)
        page.right("${1 + random.nextInt(28)} March 2027", body, right, 380.0)
        page.text("Sam Sample", body, MARGIN, 460.0)
        page.text("${1 + random.nextInt(98)} Sample Street", body, MARGIN, 508.0)
        page.text("Ref: L-${100_000 + random.nextInt(900_000)}", body, MARGIN, 556.0)
        page.text("Dear Sam Sample,", body, MARGIN, 650.0)
        var baseline = 730.0
        repeat(2 + random.nextInt(2)) {
            val paragraph = pick(random, sentences, 2 + random.nextInt(2)).joinToString(" ")
            for (line in wrap(page, paragraph, body, WIDTH - 2 * MARGIN)) {
                page.text(line, body, MARGIN, baseline)
                baseline += 50.0
            }
            baseline += 30.0
        }
        page.text("Yours sincerely,", body, MARGIN, baseline + 20.0)
        page.text("Alex Example", body, MARGIN, baseline + 120.0)
    }

    private fun form(page: PageBuilder, fonts: FixtureFonts, random: Random) {
        val body = fonts.sans.deriveFont(32f)
        page.text("Registration form", fonts.sansSemibold.deriveFont(48f), MARGIN, 170.0)
        page.text("Please complete every field in capitals.", body, MARGIN, 240.0)
        val fields = listOf(
            "Full name: ${listOf("ALEX EXAMPLE", "SAM SAMPLE", "JO PLACEHOLDER")[random.nextInt(3)]}",
            "Date of birth: 0${1 + random.nextInt(9)}/0${1 + random.nextInt(9)}/19${50 + random.nextInt(50)}",
            "Address: ${1 + random.nextInt(98)} EXAMPLE ROAD",
            "Postcode: TE${1 + random.nextInt(9)} ${1 + random.nextInt(9)}XX",
            "Phone: 0100 555 0${100 + random.nextInt(900)}",
            "Email: member${random.nextInt(100)}@example.org",
            "Membership no: M-${100_000 + random.nextInt(900_000)}",
            "[X] I agree to the terms",
            "Signed: A. Example",
            "Date: ${1 + random.nextInt(28)}/03/2027",
        )
        var top = 300.0
        fields.forEach { field ->
            page.box(MARGIN, top, WIDTH - 2 * MARGIN, 96.0, Color(70, 70, 70), filled = false)
            page.text(field, body, MARGIN + 24.0, top + 62.0)
            top += 120.0
        }
    }

    private fun greekLetter(page: PageBuilder, fonts: FixtureFonts) {
        val body = fonts.sans.deriveFont(32f)
        page.text("Εταιρεία Δοκιμών", fonts.sansSemibold.deriveFont(44f), MARGIN, 150.0)
        page.box(MARGIN, 180.0, WIDTH - 2 * MARGIN, 3.0, Color(60, 60, 60))
        page.right("14/03/2027", body, WIDTH - MARGIN, 280.0)
        var baseline = 400.0
        greek.forEach {
            page.text(it, body, MARGIN, baseline)
            baseline += if (it.endsWith(",")) 90.0 else 60.0
        }
    }

    private fun wrap(page: PageBuilder, text: String, font: Font, width: Double): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (page.advance(candidate, font) <= width) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private const val GREEK_LETTER = 7
}

internal fun <T> pick(random: Random, pool: List<T>, count: Int): List<T> {
    val remaining = pool.toMutableList()
    return List(count.coerceAtMost(pool.size)) { remaining.removeAt(random.nextInt(remaining.size)) }
}

internal fun clock(random: Random): String = String.format(
    Locale.ROOT,
    "%02d:%02d",
    7 + random.nextInt(12),
    random.nextInt(12) * 5,
)

private val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun")

internal fun day(random: Random): String =
    "${days[random.nextInt(days.size)]} ${1 + random.nextInt(28)} ${months[random.nextInt(months.size)]}"
