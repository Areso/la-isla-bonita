package islabonita.client

import islabonita.shared.AdvanceResult
import islabonita.shared.ApiError
import islabonita.shared.PeoplePage
import islabonita.shared.PersonDetail
import islabonita.shared.PersonSummary
import islabonita.shared.WorldStatus
import islabonita.shared.YearReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private const val PAGE_SIZE = 20

private val HELP = """
    Commands:
      status                 show the current state of the island
      init                   populate the island (first year only)
      reset                  wipe everything and start over from the first year
      next [n]               simulate n years (default 1)
      run                    simulate until the final year
      history [n]            show the last n simulated years (default 10)
      people [page] [alive|dead|all]
                             list people, $PAGE_SIZE per page (default: page 1, alive)
      find <name>            search people by name
      person <id>            show a person and their family
      help                   show this help
      quit                   exit
""".trimIndent()

fun main(args: Array<String>) = runBlocking {
    val baseUrl = (args.firstOrNull() ?: System.getenv("ISLA_SERVER") ?: "http://localhost:8080").trimEnd('/')
    val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 10 * 60 * 1000 }
    }
    val api = Api(client, baseUrl)

    println("~~~ La Isla Bonita ~~~  (server: $baseUrl)")
    println("Type 'help' for commands.\n")
    try {
        api.status()?.let(::printStatus)
    } catch (e: Exception) {
        println("Could not reach the server: ${e.message}")
    }

    while (true) {
        print("> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) continue
        val parts = line.split(Regex("\\s+"))
        try {
            when (parts[0].lowercase()) {
                "help", "?" -> println(HELP)
                "status" -> api.status()?.let(::printStatus)
                "init" -> api.init()?.let {
                    println("The first settlers have arrived.")
                    printStatus(it)
                }
                "reset" -> {
                    print("This erases all people and history. Are you sure? (y/N) ")
                    if (readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")) {
                        api.reset()?.let {
                            println("The island starts anew.")
                            printStatus(it)
                        }
                    } else {
                        println("Cancelled.")
                    }
                }
                "next" ->api.advance(parts.getOrNull(1)?.toIntOrNull() ?: 1)?.let(::printAdvance)
                "run" -> api.status()?.let { s ->
                    val current = s.currentYear
                    if (current == null) println("Initialize the world first with 'init'.")
                    else api.advance(s.endingYear - current + 1)?.let(::printAdvance)
                }
                "history" -> api.history()?.let { printHistory(it.takeLast(parts.getOrNull(1)?.toIntOrNull() ?: 10)) }
                "people" -> {
                    val page = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val filter = parts.drop(1).firstOrNull { it in setOf("alive", "dead", "all") } ?: "alive"
                    api.people(filter, null, page)?.let { printPage(it, page) }
                }
                "find" -> {
                    val name = parts.drop(1).joinToString(" ")
                    if (name.isBlank()) println("Usage: find <name>")
                    else api.people("all", name, 1)?.let { printPage(it, 1) }
                }
                "person" -> {
                    val id = parts.getOrNull(1)?.toLongOrNull()
                    if (id == null) println("Usage: person <id>") else api.person(id)?.let(::printPerson)
                }
                "quit", "exit", "q" -> break
                else -> println("Unknown command '${parts[0]}'. Type 'help'.")
            }
        } catch (e: Exception) {
            println("Error: ${e.message}")
        }
    }
    client.close()
}

private class Api(private val client: HttpClient, private val base: String) {
    suspend fun status(): WorldStatus? = client.get("$base/world").bodyOrError()
    suspend fun init(): WorldStatus? = client.post("$base/world/init").bodyOrError()
    suspend fun reset(): WorldStatus? = client.post("$base/world/reset").bodyOrError()
    suspend fun advance(years: Int): AdvanceResult? =
        client.post("$base/world/advance") { parameter("years", years) }.bodyOrError()

    suspend fun history(): List<YearReport>? = client.get("$base/history").bodyOrError()
    suspend fun person(id: Long): PersonDetail? = client.get("$base/people/$id").bodyOrError()
    suspend fun people(alive: String, name: String?, page: Int): PeoplePage? =
        client.get("$base/people") {
            parameter("alive", if (alive == "dead") "false" else if (alive == "all") "all" else "true")
            parameter("name", name)
            parameter("offset", (page - 1) * PAGE_SIZE)
            parameter("limit", PAGE_SIZE)
        }.bodyOrError()

    /** Returns the body, or prints the server's error and returns null. */
    private suspend inline fun <reified T> HttpResponse.bodyOrError(): T? {
        if (status.isSuccess()) return body()
        val message = runCatching { body<ApiError>().error }.getOrElse { status.toString() }
        println("Server says: $message")
        return null
    }
}

private fun printStatus(s: WorldStatus) {
    val time = when {
        s.currentYear == null -> "not started (run 'init')"
        s.finished -> "finished (${s.startingYear}–${s.endingYear})"
        else -> "January 1, ${s.currentYear}"
    }
    println("Date:        $time")
    println("Years:       ${s.startingYear}–${s.endingYear}")
    println("Population:  ${s.population}")
    println("Ever lived:  ${s.totalEverLived}")
}

private fun printAdvance(r: AdvanceResult) {
    printHistory(r.reports)
    println()
    printStatus(r.status)
}

private fun printHistory(reports: List<YearReport>) {
    if (reports.isEmpty()) {
        println("No years simulated yet.")
        return
    }
    println("%-6s %10s %8s %8s %10s %10s".format("Year", "Pop start", "Births", "Deaths", "Marriages", "Pop end"))
    for (r in reports) {
        println(
            "%-6d %10d %8d %8d %10d %10d".format(
                r.year, r.populationStart, r.births, r.deaths, r.marriages, r.populationEnd
            )
        )
    }
}

private fun printPage(p: PeoplePage, page: Int) {
    val pages = (p.total + PAGE_SIZE - 1) / PAGE_SIZE
    println("Page $page of ${maxOf(pages, 1)} (${p.total} people)")
    p.items.forEach { println("  " + it.line()) }
}

private fun printPerson(d: PersonDetail) {
    val p = d.person
    println("#${p.id} ${p.name} (${if (p.sex == "M") "male" else "female"})")
    println("  Born:     ${p.dob}")
    println(if (p.dod != null) "  Died:     ${p.dod} at age ${d.age}" else "  Age:      ${d.age}")
    println("  Father:   ${d.father?.line() ?: "unknown"}")
    println("  Mother:   ${d.mother?.line() ?: "unknown"}")
    if (d.spouse != null) println("  Spouse:   ${d.spouse!!.line()}, married ${d.dateOfMarriage}")
    if (d.children.isEmpty()) {
        println("  Children: none")
    } else {
        println("  Children:")
        d.children.forEach { println("    " + it.line()) }
    }
}

private fun PersonSummary.line(): String {
    val life = if (dod != null) "$dob – $dod" else "b. $dob"
    return "#%-6d %-28s %s  %s".format(id, name, sex, life)
}
