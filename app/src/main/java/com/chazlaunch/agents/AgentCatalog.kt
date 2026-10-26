package com.chazlaunch.agents

import android.content.Context
import kotlinx.serialization.json.Json

/** Loads the bundled agents.json manifest (assets/agents.json). */
object AgentCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cache: List<Agent>? = null

    fun load(context: Context): List<Agent> {
        cache?.let { return it }
        val text = context.assets.open("agents.json").bufferedReader().use { it.readText() }
        return json.decodeFromString<List<Agent>>(text).also { cache = it }
    }

    fun defaultSelection(context: Context): Set<String> =
        load(context).filter { it.enabledByDefault }.map { it.id }.toSet()
}
