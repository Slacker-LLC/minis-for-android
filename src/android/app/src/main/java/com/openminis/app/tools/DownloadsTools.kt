package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.runtime.ToolHandler

/**
 * [T-eta-xposed-groups] The download records the system keeps, through the provider it publishes.
 *
 * Ported from Eta `agent/tool/AgentPersonalDataTools.kt` (`search_downloads`, Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. The URI is Eta's: `downloads/my_downloads` is
 * the view the Download provider grants an ordinary app, so what comes back is the records this app
 * itself created - other apps' downloads are not visible here, and the tool says so rather than
 * looking broken. The read itself is the shared [PersonalDataQueryTools.query].
 */
object DownloadsTools {
    const val SEARCH = "android.downloads.search"

    val aliases: Map<String, List<String>> = mapOf(SEARCH to listOf("search_downloads"))

    fun handlers(): List<ToolHandler> = listOf(DownloadSearchHandler())
}

class DownloadSearchHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = DownloadsTools.SEARCH,
        description = "Search the download records the system holds for this app, by title or " +
            "description. Only the records this app itself created are visible: the Download " +
            "provider does not expose other apps' downloads.",
        parameters = mapOf(
            "query" to AgentToolParam(
                "string",
                "Optional keyword matched against the title or the description",
            ),
            "limit" to AgentToolParam(
                "integer",
                "Max rows (default " + PersonalDataQueryPolicy.DEFAULT_LIMIT + ", max " +
                    PersonalDataQueryPolicy.MAX_LIMIT + ")",
            ),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        PersonalDataQueryTools.query(
            tool = DownloadsTools.SEARCH,
            uri = "content://downloads/my_downloads",
            columns = listOf(
                "_id",
                "title",
                "description",
                "mime_type",
                "total_size",
                "lastmod",
                "status",
                "local_uri",
            ),
            sort = "lastmod DESC",
            fixedWhere = null,
            argsJson = argsJson,
            sessionId = sessionId,
            context = context,
        )
}
