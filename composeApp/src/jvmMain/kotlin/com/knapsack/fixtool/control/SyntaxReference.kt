package com.knapsack.fixtool.control

/**
 * The agent-facing reference for FixTool's two mini-languages — template expressions (`${...}`) and
 * assertion matchers — served verbatim by `GET /syntax` and the `fixtool_syntax` MCP tool.
 *
 * It lives in a resource rather than a Kotlin string so the markdown needs no `$` escaping, and so
 * there is exactly one copy: the standalone Node MCP server (`tools/fixtool-mcp`) proxies the
 * endpoint instead of restating the grammar in a tool description.
 */
object SyntaxReference {
    /** The markdown reference, loaded once. */
    val markdown: String by lazy {
        SyntaxReference::class.java.getResourceAsStream("/syntax.md")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: "syntax reference unavailable (syntax.md missing from the bundle)"
    }
}
