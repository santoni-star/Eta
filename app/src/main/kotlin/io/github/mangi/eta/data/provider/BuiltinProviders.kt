package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes

internal object BuiltinProviders {
    const val DEFAULT_SYSTEM_PROMPT =
        "витак Eta，транспортдобреіснують Android налаштуватиПриготуйтеначальниказ AI допомогтируку。виможезаповерненнявідповідьзапитатизапитання、івикористовуватипобутовіплатитипотік，такожможезаПасПасколивпередможевикористовуватизпрацюватиІнструментзрозуміврозв'язатиналаштуватиПриготуйтеПрихильністьхворобаіВиконатидобреТримайтеробити。" +
            "поверненнявідповідьзробитивикористовувативикористовуватипобутовізмоваСлово，простийчистий、прямозловити、оскількиПобіг。"

    const val OPENAI_ID = "builtin-openai"
    const val ANTHROPIC_ID = "builtin-anthropic"
    const val BAILIAN_ID = "builtin-dashscope"
    const val DEEPSEEK_ID = "builtin-deepseek"
    const val KIMI_ID = "builtin-kimi"
    const val MIMO_ID = "builtin-mimo"
    const val MINIMAX_ID = "builtin-minimax"
    const val STEPFUN_ID = "builtin-stepfun"
    const val SILICONFLOW_ID = "builtin-siliconflow"
    const val OPENROUTER_ID = "builtin-openrouter"
    const val XAI_ID = "builtin-xai"
    const val GEMINI_ID = "builtin-gemini"

    val PROVIDERS: List<ProviderSetting> = listOf(
        OpenAiCompatibleProviderSetting(
            id = OPENAI_ID,
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            sourceType = ProviderSourceTypes.OPENAI,
            isBuiltIn = true,
            sortOrder = 0,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            endpointMode = OpenAiEndpointMode.RESPONSES,
        ),
        AnthropicProviderSetting(
            id = ANTHROPIC_ID,
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            sourceType = ProviderSourceTypes.ANTHROPIC,
            isBuiltIn = true,
            sortOrder = 1,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = BAILIAN_ID,
            name = "АхвсерединіСотняпрактика",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            sourceType = ProviderSourceTypes.BAILIAN,
            isBuiltIn = true,
            sortOrder = 2,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = DEEPSEEK_ID,
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            sourceType = ProviderSourceTypes.DEEPSEEK,
            isBuiltIn = true,
            sortOrder = 3,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = KIMI_ID,
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            sourceType = ProviderSourceTypes.MOONSHOT,
            isBuiltIn = true,
            sortOrder = 4,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(
            id = MIMO_ID,
            name = "MiMo",
            baseUrl = "https://api.xiaomimimo.com/v1",
            sourceType = ProviderSourceTypes.MIMO,
            isBuiltIn = true,
            sortOrder = 5,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = MINIMAX_ID,
            name = "MiniMax",
            baseUrl = "https://api.minimaxi.com/v1",
            sourceType = ProviderSourceTypes.MINIMAX,
            isBuiltIn = true,
            sortOrder = 6,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = STEPFUN_ID,
            name = "StepFun",
            baseUrl = "https://api.stepfun.com/v1",
            sourceType = ProviderSourceTypes.STEPFUN,
            isBuiltIn = true,
            sortOrder = 7,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = SILICONFLOW_ID,
            name = "кремнійбазапотікрухатися",
            baseUrl = "https://api.siliconflow.cn/v1",
            sourceType = ProviderSourceTypes.SILICONFLOW,
            isBuiltIn = true,
            sortOrder = 8,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = OPENROUTER_ID,
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            sourceType = ProviderSourceTypes.OPENROUTER,
            isBuiltIn = true,
            sortOrder = 9,
            systemPrompt = DEFAULT_SYSTEM_PROMPT
        ),
        OpenAiCompatibleProviderSetting(
            id = XAI_ID,
            name = "xAI",
            baseUrl = "https://api.x.ai/v1",
            sourceType = ProviderSourceTypes.XAI,
            isBuiltIn = true,
            sortOrder = 10,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        GeminiProviderSetting(
            id = GEMINI_ID,
            name = "Gemini",
            isBuiltIn = true,
            sortOrder = 11,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
    )

    fun providerById(id: String): ProviderSetting? =
        PROVIDERS.firstOrNull { it.id == id }
}
