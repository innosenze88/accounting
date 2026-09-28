package com.example.data.network

/** Which header a Claude key goes in. Pure Kotlin (unit-tested). */
object ClaudeAuth {
    /**
     * Normal Console API keys ("sk-ant-api...") use the documented "x-api-key" header.
     * OAuth-style access tokens ("sk-ant-oat...") use "Authorization: Bearer".
     * Anything else starts with x-api-key; [ClaudeOcrService] retries with Bearer once on HTTP 401.
     */
    fun preferBearer(key: String): Boolean = key.startsWith("sk-ant-oat")
}
