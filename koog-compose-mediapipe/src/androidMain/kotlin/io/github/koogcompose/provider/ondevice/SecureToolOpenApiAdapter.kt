package io.github.koogcompose.provider.ondevice.android

import com.google.ai.edge.litertlm.OpenApiTool
import io.github.koogcompose.tool.ParameterDescriptor
import io.github.koogcompose.tool.SecureTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapts a koog-compose SecureTool into a LiteRT-LM OpenApiTool.
 *
 * LiteRT-LM uses the OpenAPI JSON schema to build the tool description
 * injected into the model's system prompt and drive constrained decoding.
 *
 * We supply the schema here but do NOT implement execute for actual dispatch —
 * automaticToolCalling is set to false, so LiteRT-LM never calls execute.
 * All dispatch goes through koog's SecureTool pipeline in LiteRtLmToolOrchestrator.
 */
internal class SecureToolOpenApiAdapter(
    private val secureTool: SecureTool,
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String {
        val parameters = JSONObject().apply {
            put("type", "object")
            put("properties", buildProperties())
            put("required", buildRequired())
        }

        return JSONObject().apply {
            put("name", secureTool.name)
            put("description", secureTool.description)
            put("parameters", parameters)
        }.toString()
    }

    override fun execute(paramsJsonString: String): String {
        error(
            "SecureToolOpenApiAdapter.execute() should never be called directly. " +
                "automaticToolCalling must be false when using koog-compose."
        )
    }

    private fun buildProperties(): JSONObject {
        val props = JSONObject()
        secureTool.describeParameters().forEach { param ->
            props.put(param.name, paramToJsonSchema(param))
        }
        return props
    }

    private fun buildRequired(): JSONArray {
        val required = JSONArray()
        secureTool.describeParameters()
            .filter { it.required }
            .forEach { required.put(it.name) }
        return required
    }

    private fun paramToJsonSchema(param: ParameterDescriptor): JSONObject =
        JSONObject().apply {
            put("type", param.type)
            put("description", param.description)
        }
}
