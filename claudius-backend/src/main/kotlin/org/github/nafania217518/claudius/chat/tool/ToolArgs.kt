package org.github.nafania217518.claudius.chat.tool

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

private val argsMapper = ObjectMapper()

// The model's JSON arguments; blank (a no-arg call) → an empty object.
fun parseArgs(arguments: String): JsonNode =
    argsMapper.readTree(arguments.ifBlank { "{}" })

fun JsonNode.requireString(name: String): String =
    optString(name) ?: throw IllegalArgumentException("missing argument: $name")

fun JsonNode.optString(name: String): String? =
    get(name)?.takeIf { it.isString }?.asString()
