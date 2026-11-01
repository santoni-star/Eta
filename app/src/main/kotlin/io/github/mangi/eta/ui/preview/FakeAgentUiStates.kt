package io.github.mangi.eta.ui.preview

import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi

internal object FakeAgentUiStates {

    val conversations = ConversationPaneUiState(
        selectedConversationId = "c-001",
        searchQuery = "",
        conversations = listOf(
            ConversationSummaryUi(
                id = "c-001",
                title = "нехай Eta Тримайтеробитирукумашина",
                preview = "дозволяютьПриготуйтезловититрубкаекранекраніпрацюватиІнструмент，чекатилікувативнизодинкрокпризначитиобслуговування",
                timeLabel = "заразіснують",
                mode = ConversationModeUi.PhoneAgent,
                isPinned = true,
                isActiveRun = true,
            ),
            ConversationSummaryUi(
                id = "c-002",
                title = "заразнебозвстановитирядокінестиПрокинутися",
                preview = "перевіритиЗапитуйтенебогаз、те самекрокденьПроцедура，іналаштувативстановитипозадверінестиПрокинутися",
                timeLabel = "10:41",
                mode = ConversationModeUi.Chat,
                isPinned = true,
            ),
            ConversationSummaryUi(
                id = "c-003",
                title = "битиВІДЧИНЕНОчистийлегкохмаразвукщасливийтрансляціяпоставитикоженденьштовхатирекомендую",
                preview = "вжезакінченостати 4 індивідуальнийпрацюватиІнструментмелодіявикористовувати，використовуватигодина 8 друге",
                timeLabel = "10:23",
                mode = ConversationModeUi.PhoneAgent,
            ),
            ConversationSummaryUi(
                id = "c-004",
                title = "точкааналізуватиколивпередСторінкалокшинавирізатималюнок",
                preview = "всьоговузолСторінкалокшинавузолструктура，ізвертатися допозавідповідно докнопкушаркласзапитатизапитання",
                timeLabel = "вчоранебо",
                mode = ConversationModeUi.Chat,
            ),
            ConversationSummaryUi(
                id = "c-005",
                title = "перевіритиперевірити Alpine кінецькінецькільцетериторії",
                preview = "Списокпозаможевикористовуватижиттязробити、Python/Node версіякнигаіназадвежа job",
                timeLabel = "тижденьп'ять",
                mode = ConversationModeUi.Terminal,
            ),
            ConversationSummaryUi(
                id = "c-006",
                title = "коженнічвсепричинавнизнавантаженняГоловазапис",
                preview = "Звичайногодинапідмітатислідмистецтваштукіповерненнявидмалюнокшматок、встановитиупаковкаСумкаімистецтвафайли",
                timeLabel = "5-18",
                mode = ConversationModeUi.Automation,
            ),
        ),
    )

    val chatHome: AgentChatHomeUiState = AgentChatHomeUiState(
        messages = emptyList(),
        input = "",
        isStreaming = false,
        thinkingEnabled = false,
    )

    val chat = AgentChatUiState(
        messages = listOf(
            UserMessageUi(
                id = "m-01",
                content = "битиВІДЧИНЕНОналаштувативстановитивсерединізелектроенергіябасейнвідміннозмінити",
            ),
            AgentMessageUi(
                id = "m-02",
                content = "коливпередрукумашинаекранекраншоуПоказатизтак**хостмежалокшина**，закритиключлистінтересяквниз：\n\n| пунктГолова | ВсерединіДозволити |\n| --- | --- |\n| годинаміж | 09:55 |\n| чистиймережі | 5G + Wi-Fi |\n| електроенергіякількість | 68% |",
                usage = TokenUsageUi(
                    contextTokens = 17100,
                    inputTokens = 9000,
                    outputTokens = 111,
                    reasoningTokens = 8200,
                ),
            ),
            ThinkingMessageUi(
                id = "thinking-01",
                content = "використовуватипобутовінадіяпобачитияперевіритиподивітьсяколивпередекранекран，потребахочутьпершемелодіявикористовувати observe_screen отриматиВиберітьекранекранвузолструктура，Зновувсьоговузолможедивлистінтерес。",
                isStreaming = false,
                elapsedSeconds = 24,
                collapsed = true,
            ),
            ToolActivityMessageUi(
                id = "tools-01",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{\"include_screenshot\":true}",
                resultSummary = "ok=true, chars=1820, images=1",
                imageCount = 1,
            ),
        ),
        input = "",
        isStreaming = false,
        thinkingEnabled = true,
    )

    val permissionHealth = PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "accessibility",
                title = "немаєбар'єрперешкоджатиОдягобслуговування",
                summary = "вжепочатоквикористовувати，Agent можеТримайтеробити UI",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = "виситьплавативікноправильнообмеження",
                summary = "вженавчатиправильно，можешоуПоказатитранспортдобреплавативікно",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "notification",
                title = "Пасзнатиправильнообмеження",
                summary = "використовуватинаназадвежапризначитиобслуговуваннязакінченостатинестиПрокинутися",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "location",
                title = "бітвстановитиправильнообмеження",
                summary = "тількиіснують Agent мелодіявикористовуватипрацюватиІнструментгодиначитатиВиберіть",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "root",
                title = "Root правильнообмеження",
                summary = "ще ніотриматиповинні，відділточкакінецькінецьжиттязробитизаобмеження",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "перевіритиперевірити",
            ),
            PermissionHealthItemUi(
                id = "shizuku",
                title = "Shizuku",
                summary = "ще німатчвстановити，ADB класздатнийсиланемаєможевикористовувати",
                status = PermissionStatusUi.Disabled,
                primaryActionLabel = "матчвстановити",
            ),
            PermissionHealthItemUi(
                id = "xposed",
                title = "Hook / Xposed",
                summary = "коробкаполицяще нізахоплюючийжити，Краваткасистемазбільшенняпотужнийздатнийсиланемаєможевикористовувати",
                status = PermissionStatusUi.Missing,
                primaryActionLabel = "перевіритиподивіться",
            ),
            PermissionHealthItemUi(
                id = "background",
                title = "назадвежазберегтижити",
                summary = "електроенергіябасейнвідміннозмінитище нізакритизакрити，довгопризначитиобслуговуванняможездатнийковдрасерединаперерва",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "налаштувативстановити",
            ),
        ),
    )

    val tools = AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = "екранекранТримайтеробити",
                tools = listOf(
                    ToolItemUi("observe_screen", "переглянутиоглядатиекранекран", "читатиВиберітьмежалокшинаФестивальточка，повиненхочутьгодинадодаєтьсяОригіналмалюнок"),
                    ToolItemUi("tap_element", "точкаудар", "точкаударзвертатися доЗвичайносидітизнакабоЮаньбілий"),
                    ToolItemUi("long_press", "довговідповідно до", "довговідповідно дозвертатися доЗвичайноЮаньбілий"),
                    ToolItemUi("swipe", "ковзатирухатися", "ковзатирухатися、ролрухатися、поверненняповерненнячекатирукупотенціал"),
                ),
            ),
            ToolGroupUi(
                id = "input",
                title = "втратитивведітьівідповідно доключ",
                tools = listOf(
                    ToolItemUi("input_text", "втратитивведітьмистецтвахарактер", "існуютьфокусточкаввтратитивведітьмистецтвакнига"),
                    ToolItemUi("paste_text", "палкапалкамистецтвакнига", "зробитивикористовуватиВирізатипалкаплитаНапишітьвведітьмистецтвакнига"),
                    ToolItemUi("wait_for_text", "чекатилікуватимистецтвакнига", "чекатилікуватимежалокшинапозазараззвертатися доЗвичайномистецтвахарактер"),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = "чистийСторінкапереглядатиПереглянути",
                tools = listOf(
                    ToolItemUi("browser_use", "Agent переглядатиПереглянутипристрій", "ЗалиштеекранпереглядатиПереглянутиізберегтиутримуватиможезловититрубказасіданняговорити"),
                    ToolItemUi("browser_read", "читатичитатичистийСторінка", "нестиВиберітьрендеритибарвникназадзчистийСторінкапростомистецтва"),
                    ToolItemUi("browser_interact", "чистийСторінкаплатитивзаємний", "перевіритиспробуйте знайти、точкаударівтратитивведітьЮаньбілий"),
                    ToolItemUi("browser_screenshot", "Сторінкалокшинавирізатималюнок", "пучокчистийСторінкадивротплатитиДайтедивсонцвільтипу"),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "App і URI",
                tools = listOf(
                    ToolItemUi("get_current_context", "годинаміжібітвстановити", "читатиВиберітьКраваткасистемагодинаміжібільшістьзакритибітвстановити"),
                    ToolItemUi("launch_app", "битиВІДЧИНЕНО App", "ПасПасСумканазвапочатокрухатисявідповідьвикористовувати"),
                    ToolItemUi("open_uri", "використовувативідповідьвикористовуватибитиВІДЧИНЕНО", "шоуРежимплатитиДайтеназовнівідділвідповідьвикористовувативпричина"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = "кінецькінецьімистецтваштук",
                tools = listOf(
                    ToolItemUi("terminal", "кінецькінецьжиттязробити", "Android/Alpine/Debian кільцетериторіїВиконатидобре shell"),
                ),
            ),
        ),
    )
}
