package islabonita.server

import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.name
import kotlin.io.path.readText

private val log = LoggerFactory.getLogger("Database")

fun openDatabase(path: String): Connection {
    val conn = DriverManager.getConnection("jdbc:sqlite:$path")
    conn.createStatement().use {
        it.execute("PRAGMA foreign_keys = ON")
        it.execute("PRAGMA journal_mode = WAL")
    }
    Migrations.run(conn)
    return conn
}

inline fun <T> Connection.transaction(block: (Connection) -> T): T {
    autoCommit = false
    try {
        val result = block(this)
        commit()
        return result
    } catch (e: Throwable) {
        rollback()
        throw e
    } finally {
        autoCommit = true
    }
}

/**
 * Applies `resources/migrations/V###__name.sql` files in order, once each.
 * Statements are split on `;`, so don't put semicolons inside string literals.
 */
object Migrations {
    fun run(conn: Connection) {
        conn.createStatement().use {
            it.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations (version TEXT PRIMARY KEY, applied_at TEXT NOT NULL)"
            )
        }
        val applied = conn.createStatement().use { st ->
            st.executeQuery("SELECT version FROM schema_migrations").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }

        for ((version, sql) in load()) {
            if (version in applied) continue
            log.info("Applying migration {}", version)
            conn.transaction { c ->
                c.createStatement().use { st ->
                    sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) }
                }
                c.prepareStatement("INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)").use {
                    it.setString(1, version)
                    it.setString(2, Instant.now().toString())
                    it.executeUpdate()
                }
            }
        }
    }

    private fun load(): List<Pair<String, String>> {
        val uri = Migrations::class.java.classLoader.getResource("migrations")?.toURI()
            ?: error("migrations folder not found on classpath")
        return if (uri.scheme == "jar") {
            FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { fs -> read(fs.getPath("/migrations")) }
        } else {
            read(Paths.get(uri))
        }
    }

    private fun read(dir: Path): List<Pair<String, String>> =
        Files.list(dir).use { files ->
            files.filter { it.name.endsWith(".sql") }
                .map { it.name.removeSuffix(".sql") to it.readText() }
                .toList()
        }.sortedBy { it.first }
}
