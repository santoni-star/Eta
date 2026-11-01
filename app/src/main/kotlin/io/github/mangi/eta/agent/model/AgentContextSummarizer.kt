package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/** Підсумок може замінити історію лише після повного успіху; якщо це не вдається, оригінальний текст зберігається, а запит на відтворення не поділяється.*/
internal class AgentContextSummarizer(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean,
) {
    fun summarize(history: List<AgentModelClient.ConversationMessage>): String {
        controller.throwIfCancelled()
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content",
            "витягарвідповідальністьдля Eta народивсястатислідуватиПродовженняпризначитиобслуговуванняМісцепотребазначальникавнизмистецтвавибратихочуть。втратитивведітькалендарісторіїтаклікувативсьоговузолзномервідповідно до，немаєВиконатидобрещосерединазвертатися дозробити，немаємелодіявикористовуватипрацюватиІнструмент。" +
                "зберегтиТримайтеколивпередГоловазнак、використовуватипобутовіприблизнопучок、вжезакінченостатиТримайтеробитиісправжнійРеальністьвузолфрукти、закритиключдорогашляхізнакзнання、досіще ніправильнорозпізнатизрічРеальність、лікуватирозв'язативирішитизапитатизапитанняівнизодинкрок。" +
                (if (roleplay) "іншеназовнізберегтиТримайтерігколірзакритиКраватка、полесцена、драмаПрихильністьВведітьвиставка、ще нірозв'язативирішитизтомурічДрітОтжеівикористовуватипобутовілюдейналаштувати。" +
                    "віртуальнийструктурадрамаПрихильністьісправжнійРеальністьналаштуватиПриготуйтеТримайтеробититочкаВІДЧИНЕНОзапам'ятатизапис；немаєздатнийпучокдрамаПрихильністьрухатисяробитиНапишітьстатиРеальністьміжнароднийпрацюватиІнструментВиконатидобревузолфрукти，немаєздатнийпучоклюдейналаштуватиколиробитивикористовуватипобутовізаразРеальністьрічРеальність。" else "") +
                "зберегтиТримайтематиефектстарийвибратихочуть，видалитивидалитиВажкаскладніівтратитиефектсмакспробувати，немаєздатнийпучоксмакспробуватиколистатидосягненняабокомпілюватизробитирічРеальність。Тількивтратитипозавибратихочутьпростомистецтва，немаєобганятиПас $MAX_SUMMARY_CHARS характерсимвол。"))
            .put(AgentConversationCodec.userTextMessage(buildString {
                append("лікувативсепричиназкалендарісторії：\n")
                history.forEach { append(AgentConversationCodec.toJsonObject(it)).append('\n') }
            }))
        val response = provider.complete(
            ProviderRequest(config, messages, JSONArray(), purpose = ProviderRequestPurpose.COMPACTION),
            controller,
        ) { event ->
            if (event is ProviderEvent.HostedToolStarted ||
                event is ProviderEvent.BlockStart && event.kind == AssistantBlockKind.TOOL_CALL) {
                throw invalidSummary()
            }
        }
        controller.throwIfCancelled()
        val summary = response.assistantMessage.optString("content").trim()
        if (response.stopReason != AssistantStopReason.END_TURN ||
            AgentConversationCodec.parseToolCalls(response.assistantMessage).isNotEmpty() ||
            summary.isBlank() || summary == "null" || summary.length > MAX_SUMMARY_CHARS) {
            throw invalidSummary()
        }
        return summary
    }

    private fun invalidSummary() = AgentModelFailure(
        "CONTEXT_SUMMARY_INVALID", false, "цвільтипуще ніповерненняповерненнязакінченовсеіматимежазвибратихочуть，ОригіналпочатокначальникавнизмистецтвавжезберегтиТримайте。",
    )

    private companion object {
        const val MAX_SUMMARY_CHARS = 12_000
    }
}
