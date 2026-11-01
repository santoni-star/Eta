package io.github.mangi.eta.agent.context

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

internal class PersonalContextArgumentException(message: String) : IllegalArgumentException(message) {
    val code: String = "INVALID_ARGUMENT"
}

internal enum class PersonalContextAction(val wireName: String) {
    SOURCES("sources"), SEARCH("search"), READ("read"), COUNT("count"), BILL_SUMMARY("bill_summary");
}

internal enum class PersonalContextSort(val wireName: String) { NEWEST("newest"), OLDEST("oldest") }

internal data class PersonalContextRequest(
    val action: PersonalContextAction,
    val source: PersonalContextSource?,
    val query: String? = null,
    val start: Instant? = null,
    val end: Instant? = null,
    val limit: Int = 10,
    val offset: Int = 0,
    val sort: PersonalContextSort = PersonalContextSort.NEWEST,
    val id: String? = null,
) {
    companion object {
        const val MAX_QUERY_CHARACTERS = 200
        const val MAX_LIMIT = 30
        const val MAX_OFFSET = 10_000

        private val timePattern = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?(?:Z|[+-][0-9]{2}:[0-9]{2})$")
        private val idPattern = Regex("^[0-9]{1,19}$")

        fun parse(arguments: JSONObject): PersonalContextRequest {
            val actionName = requiredString(arguments, "action", "повиненОбов'язковонестидляматиефектз action")
            val action = PersonalContextAction.entries.firstOrNull { it.wireName == actionName }
                ?: invalid("немаєвідділенняутримуватицеіндивідуальнийлюдейначальникавнизмистецтваТримайтеробити")
            val source = if (arguments.has("source")) {
                PersonalContextSources.find(requiredString(arguments, "source", "source повиненОбов'язковотакматиефектзномервідповідно доджерелоназвасказати"))
                    ?: invalid("немаєвідділенняутримуватицеіндивідуальнийлюдейначальникавнизмистецтваномервідповідно доджерело")
            } else null
            if (action != PersonalContextAction.SOURCES && source == null) invalid("коливпередТримайтеробитиповиненОбов'язковонестидля source")
            if (action == PersonalContextAction.BILL_SUMMARY && source?.id != "bills") invalid("bill_summary тількивідділенняутримувати bills номервідповідно доджерело")

            val query = optionalString(arguments, "query", "query повиненОбов'язковотакмистецтвакнига")?.let { value ->
                if (value.codePointCount(0, value.length) > MAX_QUERY_CHARACTERS) invalid("query обганятиПас 200 індивідуальнийхарактерсимвол")
                if ('\u0000' in value || !Charsets.UTF_8.newEncoder().canEncode(value)) invalid("query СумкаМіститьнемаєефектхарактерсимвол")
                value.trim().takeIf(String::isNotEmpty)
            }
            val start = parseTime(arguments, "start_time")
            val end = parseTime(arguments, "end_time")
            if (start != null && end != null && start >= end) invalid("годинаміжвентилятороточуютьповиненОбов'язковоПовнийстопа start_time ранокна end_time")
            if ((start != null || end != null) && source?.timeField == null) invalid("ценомервідповідно доджерелобезмативжеправильнорозпізнатизПромисловістьобслуговуваннягодинаміжхарактерчастина，немаєздатнийвідповідно догодинаміжекранвибрати")
            source?.timeUnit?.toBounds(start, end)
            val limit = optionalInteger(arguments, "limit", 10, 1, MAX_LIMIT)
            val offset = optionalInteger(arguments, "offset", 0, 0, MAX_OFFSET)
            val sort = optionalString(arguments, "sort", "sort повиненОбов'язковотак newest або oldest")?.let { value ->
                PersonalContextSort.entries.firstOrNull { it.wireName == value } ?: invalid("sort повиненОбов'язковотак newest або oldest")
            } ?: PersonalContextSort.NEWEST
            val id = if (action == PersonalContextAction.READ) {
                val value = requiredString(arguments, "id", "read повиненОбов'язковонестидлявсеномермистецтвакнига id")
                if (!idPattern.matches(value)) invalid("id повиненОбов'язковотакбільшістьбагато 19 бітзнемаєтягарвсеномермистецтвакнига")
                value.toLongOrNull()?.toString() ?: invalid("id обганятиПасвідділенняутримуватизвсеномервентилятороточують")
            } else null
            return PersonalContextRequest(action, source, query, start, end, limit, offset, sort, id)
        }

        private fun parseTime(arguments: JSONObject, name: String): Instant? {
            val value = optionalString(arguments, name, "годинаміжженьшеньномерповиненОбов'язковотакпринестигодинарайончастковийзміназ ISO 8601 мистецтвакнига") ?: return null
            if (!timePattern.matches(value)) invalid("годинаміжженьшеньномерповиненОбов'язковоСумкаМістить ISO 8601 деньОчікуйте、годинаміжі Z абогодинарайончастковийзміна")
            return try {
                OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            } catch (_: DateTimeException) {
                invalid("годинаміжженьшеньномернемаєтакматиефектзденьОчікуйтеабогодинарайончастковийзміна")
            }
        }

        private fun optionalInteger(arguments: JSONObject, name: String, default: Int, minimum: Int, maximum: Int): Int {
            if (!arguments.has(name)) return default
            val value = arguments.opt(name)
            if (value !is Number) invalid("точкаСторінкаженьшеньномерповиненОбов'язковотак JSON всеномер")
            val integer = try { BigDecimal(value.toString()).intValueExact() } catch (_: NumberFormatException) {
                invalid("точкаСторінкаженьшеньномерповиненОбов'язковотакматиобмеженнявсеномер")
            } catch (_: ArithmeticException) {
                invalid("точкаСторінкаженьшеньномерповиненОбов'язковотаквентилятороточуютьВсерединізвсеномер")
            }
            if (integer !in minimum..maximum) invalid("точкаСторінкаженьшеньномеробганятипозадозволяютьтравнявентилятороточують")
            return integer
        }

        private fun optionalString(arguments: JSONObject, name: String, message: String): String? =
            if (arguments.has(name)) arguments.opt(name) as? String ?: invalid(message) else null

        private fun requiredString(arguments: JSONObject, name: String, message: String): String =
            arguments.opt(name) as? String ?: invalid(message)

        private fun invalid(message: String): Nothing = throw PersonalContextArgumentException(message)
    }
}
