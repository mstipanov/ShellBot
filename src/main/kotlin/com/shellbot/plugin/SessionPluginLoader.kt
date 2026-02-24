package com.shellbot.plugin

import java.util.ServiceLoader

/**
 * Discovers SessionPlugin implementations via Java SPI and returns
 * the first one whose [SessionPlugin.matches] returns true for the
 * given command, or null (graceful fallback to current behavior).
 */
object SessionPluginLoader {

    fun findPlugin(command: String): SessionPlugin? {
        val loader = ServiceLoader.load(SessionPlugin::class.java)
        for (plugin in loader) {
            if (plugin.matches(command)) {
                return plugin
            }
        }
        return null
    }
}
