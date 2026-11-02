package islabonita.server

import com.typesafe.config.ConfigFactory
import islabonita.shared.ApiError
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    // application.conf, with HOST / PORT / ISLA_DB env overrides; -Disla.host=... also works.
    val config = ConfigFactory.load().getConfig("isla")
    val world = World(openDatabase(config.getString("db")))
    embeddedServer(CIO, host = config.getString("host"), port = config.getInt("port")) { module(world) }
        .start(wait = true)
}

fun Application.module(world: World) {
    install(CallLogging)
    install(ContentNegotiation) { json(Json { prettyPrint = true }) }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Bad request"))
        }
        exception<IllegalStateException> { call, e ->
            call.respond(HttpStatusCode.Conflict, ApiError(e.message ?: "Conflict"))
        }
        exception<NoSuchElementException> { call, e ->
            call.respond(HttpStatusCode.NotFound, ApiError(e.message ?: "Not found"))
        }
    }

    routing {
        get("/world") { call.respond(world.status()) }
        post("/world/init") { call.respond(world.initialize()) }
        post("/world/reset") { call.respond(world.reset()) }
        post("/world/advance") {
            val years = call.request.queryParameters["years"]?.toIntOrNull() ?: 1
            call.respond(world.advance(years))
        }
        get("/history") { call.respond(world.history()) }

        get("/people") {
            val q = call.request.queryParameters
            val alive = when (q["alive"]) {
                null, "all" -> null
                else -> q["alive"] == "true"
            }
            val offset = q["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 20
            call.respond(world.people(alive, q["name"]?.takeIf { it.isNotBlank() }, offset, limit))
        }
        get("/people/{id}") {
            val id = requireNotNull(call.parameters["id"]?.toLongOrNull()) { "Invalid id" }
            call.respond(world.person(id))
        }
    }
}
