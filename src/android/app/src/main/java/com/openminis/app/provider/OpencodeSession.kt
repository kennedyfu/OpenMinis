package com.openminis.app.provider

import android.content.Context
import okhttp3.Interceptor
import java.util.UUID

/**
 * [T-opencode-go-session] OpenCode Go (https://opencode.ai/docs/go) rejects
 * requests that do not identify a session:
 *
 * ```
 * 400 {"type":"MissingSessionID","message":"...missing x-opencode-session..."}
 * ```
 *
 * Their contract asks every non-OpenCode client for (a) a stable session id
 * per conversation and (b) an honest client identity. Minis has no
 * per-conversation id down at the HTTP layer, so we mint one stable id per
 * install instead: stable across launches (persisted in SharedPreferences),
 * which keeps the upstream routing/session id constant so prompt caching can
 * still reuse prefixes within a conversation.
 *
 * Scope: only requests whose host is `opencode.ai` (or a subdomain) receive
 * the headers. Every other provider's traffic is byte-identical to before
 * this class existed — unlike a blanket injection on every outbound request.
 *
 * Wiring mirrors [MinisUserAgent]/[applyUserAgentOverride]: a single place
 * that owns the rule, installed on the OkHttp clients that can reach a
 * user-supplied base URL ([com.openminis.app.provider.openai.OpenAIProvider]
 * and [com.openminis.app.provider.openai.OpenAIModelsApi]).
 */
object OpencodeSession {
    private const val PREFS = "opencode_session"
    private const val KEY_SESSION = "session_id"
    private const val HEADER_SESSION = "x-opencode-session"
    private const val HEADER_CLIENT = "x-opencode-client"

    /** Self-identifying client name for `x-opencode-client` (never a vendor SDK name). */
    private const val CLIENT_NAME = "minis-android"

    /**
     * Application context, primed from [com.openminis.app.MinisApp.onCreate].
     * Null in unit tests / previews — [sessionId] then falls back to a
     * process-lifetime id, which still satisfies the header contract.
     */
    @Volatile
    private var appContext: Context? = null

    private val processFallback: String by lazy { UUID.randomUUID().toString() }

    /** Zero-I/O handoff of the app Context; the prefs read happens lazily. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Stable per-install id, created on first use and reused forever after.
     * Falls back to a process id only where no Context was installed.
     */
    fun sessionId(): String {
        val context = appContext ?: return processFallback
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_SESSION, fresh).apply()
        return fresh
    }

    /** `opencode.ai` and any subdomain of it (e.g. a future `api.opencode.ai`). */
    fun isOpenCodeHost(host: String): Boolean =
        host == "opencode.ai" || host.endsWith(".opencode.ai")

    /**
     * Adds the two headers only when the request targets OpenCode. Safe to
     * attach to any OkHttp client: non-OpenCode traffic is passed through
     * untouched (same Request instance, no extra allocation).
     */
    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!isOpenCodeHost(request.url.host)) {
            chain.proceed(request)
        } else {
            chain.proceed(
                request.newBuilder()
                    .header(HEADER_SESSION, sessionId())
                    .header(HEADER_CLIENT, CLIENT_NAME)
                    .build(),
            )
        }
    }
}
