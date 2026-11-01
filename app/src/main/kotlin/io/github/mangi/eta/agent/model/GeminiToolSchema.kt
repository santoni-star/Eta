package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Gemini вимагає, щоб обов’язковий атрибут складеної гілки було оголошено локально в цій гілці, а вихідний контракт інструменту все ще зберігається в каталозі.*/
internal object GeminiToolSchema {
    private val combinators = setOf("allOf", "anyOf", "oneOf")
    private val schemaMaps = setOf("properties", "patternProperties", "\$defs", "definitions", "dependentSchemas", "dependencies")
    private val singleSchemas = setOf(
        "additionalProperties", "unevaluatedProperties", "propertyNames", "additionalItems", "unevaluatedItems",
        "contains", "contentSchema", "not", "if", "then", "else",
    )
    private val referenceKeywords = setOf(
        "\$id", "id", "\$anchor", "\$dynamicAnchor", "\$recursiveAnchor", "\$ref", "\$dynamicRef", "\$recursiveRef",
    )

    fun project(parameters: JSONObject): JSONObject = JSONObject(parameters.toString()).also {
        projectSchema(it, emptyMap())
    }

    private fun projectSchema(schema: JSONObject, inheritedProperties: Map<String, Any>) {
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        // Закриті правила можуть спостерігати за результатами оцінки власності підгалузей; зовнішні властивості не можуть обійти цей рівень і продовжують передаватись нащадкам.
        val canDeclare = listOf("additionalProperties", "unevaluatedProperties").none {
            schema.has(it) && schema.opt(it) != true
        }
        val inherited = if (!canDeclare || referenceKeywords.any(schema::has)) emptyMap() else inheritedProperties
        if (canDeclare) {
            val required = schema.optJSONArray("required")
            for (index in 0 until (required?.length() ?: 0)) {
                val name = required?.opt(index) as? String ?: continue
                val definition = inherited[name] ?: continue
                if (!properties.has(name) && canCopy(definition)) {
                    properties.put(name, if (definition is JSONObject) JSONObject(definition.toString()) else definition)
                    schema.put("properties", properties)
                }
            }
        }
        val visible = inherited.toMutableMap()
        for (name in properties.keys()) visible[name] = properties.get(name)
        forEachSubschema(schema) { child, sameInstance ->
            projectSchema(child, if (sameInstance) visible else emptyMap())
        }
    }

    private fun canCopy(definition: Any): Boolean {
        if (definition is Boolean) return true
        if (definition !is JSONObject || referenceKeywords.any(definition::has)) return false
        // Ідентифікатори, прив’язки та посилання залишаються у своїх вихідних місцях, щоб уникнути зміни URI або динамічних посилань після копіювання.
        var safe = true
        forEachSubschema(definition) { child, _ -> if (!canCopy(child)) safe = false }
        return safe
    }

    private fun forEachSubschema(schema: JSONObject, visit: (JSONObject, Boolean) -> Unit) {
        for (keyword in combinators) {
            schema.optJSONArray(keyword)?.let { children ->
                for (index in 0 until children.length()) children.optJSONObject(index)?.let { visit(it, true) }
            }
        }
        for (keyword in schemaMaps) {
            schema.optJSONObject(keyword)?.let { children ->
                for (name in children.keys()) children.optJSONObject(name)?.let { visit(it, false) }
            }
        }
        for (keyword in singleSchemas) schema.optJSONObject(keyword)?.let { visit(it, false) }
        for (keyword in listOf("items", "prefixItems")) {
            when (val children = schema.opt(keyword)) {
                is JSONObject -> visit(children, false)
                is JSONArray -> for (index in 0 until children.length()) {
                    children.optJSONObject(index)?.let { visit(it, false) }
                }
            }
        }
    }
}
