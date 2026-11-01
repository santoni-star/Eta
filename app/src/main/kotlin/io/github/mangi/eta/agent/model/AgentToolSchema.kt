package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentToolSchema {
    fun coordinateSpace(): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("normalized").put("screen").put("screenshot"))
            .put(
                "description",
                "повинензаповнити。normalized（штовхатирекомендую）：x、y Виберіть 0–999，ВзаємноправильновсешматокекранекранзПорівняйтеприклад，подивітьсявирізатималюнокгодинавикористовуватице，немаєзамалюнокшматокскорочуватисяпоставитифільмкільце；" +
                    "screen：справжнійРеальністьекранекранмалюнокбілий，ui_nodes з center/bounds Відразутакцеіндивідуальнийсидітизнак；" +
                    "screenshot：більшістьзакритиодинДругосортнийдодаєтьсямалюнокзОригіналпочатокмалюнокбілий，тількиколивиправильноЗвичайноподивітьсяприбутизтакще ніскорочуватисяпоставитиОригіналмалюнокгодиназробитивикористовувати。",
            )

    fun function(
        name: String,
        description: String,
        parameters: JSONObject,
    ): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
}
