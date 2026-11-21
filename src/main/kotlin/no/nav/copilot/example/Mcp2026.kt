package no.nav.copilot.example

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// MCP 2026-07-28 front door. The SDK (0.16.0) speaks the 2025 protocol only, so this layer
// answers server/discover and rejects malformed 2026 requests before the SDK sees them.
// A request is "modern" when it carries MCP-Protocol-Version or the _meta protocol version.
// Everything else (and valid modern requests other than server/discover) goes to the SDK unchanged.
const val MODERN_VERSION = "2026-07-28"
private const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
private const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
private const val HEADER_MISMATCH = -32020
private const val UNSUPPORTED_VERSION = -32022
private const val INVALID_REQUEST = -32600

private suspend fun rpcError(call: ApplicationCall, code: Int, message: String, id: JsonObject? = null) {
    val body = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id?.get("id") ?: JsonNull)
        putJsonObject("error") { put("code", code); put("message", message) }
    }
    call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
}

/** Returns true when it answered the call. Runs after auth, so only authenticated callers reach it. */
suspend fun handleModern(call: ApplicationCall): Boolean {
    when (call.request.local.method.value) {
        "POST" -> {}
        else -> {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respondText("", status = HttpStatusCode.MethodNotAllowed)
            return true
        }
    }
    val versionHeader = call.request.header("MCP-Protocol-Version")
    val raw = call.receiveText() // body is already capped by the auth plugin
    val msg = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
    val params = msg?.get("params") as? JsonObject
    val metaVersion = ((params?.get("_meta") as? JsonObject)?.get(META_VERSION) as? JsonPrimitive)?.contentOrNull
    if (versionHeader == null && metaVersion == null) return false // legacy request

    val method = (msg?.get("method") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (msg == null || method == null || msg["id"] is JsonNull) {
        rpcError(call, INVALID_REQUEST, "invalid request"); return true
    }
    if (versionHeader == null) { rpcError(call, HEADER_MISMATCH, "missing MCP-Protocol-Version", msg); return true }
    if (versionHeader != MODERN_VERSION) { rpcError(call, UNSUPPORTED_VERSION, "unsupported protocol version", msg); return true }
    if (metaVersion != versionHeader) { rpcError(call, HEADER_MISMATCH, "protocol version differs from header", msg); return true }
    if (call.request.header("Mcp-Method") != method) { rpcError(call, HEADER_MISMATCH, "Mcp-Method does not match method", msg); return true }
    if (method == "tools/call" && call.request.header("Mcp-Name") != params?.get("name")?.jsonPrimitive?.contentOrNull) {
        rpcError(call, HEADER_MISMATCH, "Mcp-Name does not match params.name", msg); return true
    }
    if (method != "server/discover") return false

    val result = buildJsonObject {
        putJsonArray("supportedVersions") { add(JsonPrimitive(MODERN_VERSION)) }
        putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
        put("ttlMs", 300000)
        put("cacheScope", "private")
        put("resultType", "complete")
        putJsonObject("_meta") { putJsonObject(META_SERVER_INFO) { put("name", "mcp-server-kotlin"); put("version", "0.1.0") } }
    }
    call.respondText(
        buildJsonObject { put("jsonrpc", "2.0"); put("id", msg["id"] ?: JsonNull); put("result", result) }.toString(),
        ContentType.Application.Json,
    )
    return true
}
