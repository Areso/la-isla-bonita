package islabonita.server

import islabonita.shared.AdvanceResult
import islabonita.shared.PeoplePage
import islabonita.shared.PersonDetail
import islabonita.shared.PersonSummary
import islabonita.shared.WorldStatus
import islabonita.shared.YearReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/** Per-1000-people yearly rates. */
const val BIRTH_RATE = 20.0 / 1000
const val DEATH_RATE = 12.0 / 1000
const val MARRIAGE_RATE = 8.0 / 1000

const val INITIAL_POPULATION = 5000

private const val MIN_MARRIAGE_AGE = 18
private const val MAX_MARRIAGE_AGE = 55
private const val MIN_MOTHER_AGE = 16
private const val MAX_MOTHER_AGE = 45

/** Share of adult women who are already married when the world starts. */
private const val INITIAL_MARRIED_SHARE = 0.65

/**
 * All game state lives in SQLite. A single connection is shared and every
 * operation runs under [mutex], so years are simulated strictly one at a time.
 */
class World(private val conn: Connection, private val rnd: Random = Random.Default) {
    private val log = LoggerFactory.getLogger(World::class.java)
    private val mutex = Mutex()
    private val names = NameGenerator(rnd)

    private suspend fun <T> locked(block: () -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { block() } }

    suspend fun status(): WorldStatus = locked { statusNow() }

    suspend fun initialize(): WorldStatus = locked {
        check(currentYear() == null) { "World is already initialized" }
        conn.transaction { populate(startingYear()) }
        log.info("World initialized with {} people", INITIAL_POPULATION)
        statusNow()
    }

    /** Wipes everyone and the history, then repopulates as of the starting year. */
    suspend fun reset(): WorldStatus = locked {
        conn.transaction { c ->
            c.createStatement().use { st ->
                st.execute("DELETE FROM year_log")
                st.execute("DELETE FROM people")
                st.execute("DELETE FROM world WHERE id = 'current_year'")
            }
            populate(startingYear())
        }
        log.info("World reset and repopulated with {} people", INITIAL_POPULATION)
        statusNow()
    }

    suspend fun advance(years: Int): AdvanceResult = locked {
        require(years >= 1) { "years must be >= 1" }
        val current = currentYear() ?: error("World is not initialized yet")
        val last = endingYear()
        check(current <= last) { "The simulation has already reached $last" }

        val reports = (current..minOf(current + years - 1, last)).map { year ->
            conn.transaction { simulateYear(year) }.also { log.info("Simulated {}", it) }
        }
        AdvanceResult(reports, statusNow())
    }

    suspend fun history(): List<YearReport> = locked {
        conn.prepareStatement("SELECT * FROM year_log ORDER BY year").use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        YearReport(
                            year = rs.getInt("year"),
                            populationStart = rs.getInt("population_start"),
                            births = rs.getInt("births"),
                            deaths = rs.getInt("deaths"),
                            marriages = rs.getInt("marriages"),
                            populationEnd = rs.getInt("population_end"),
                        )
                    )
                }
            }
        }
    }

    suspend fun people(alive: Boolean?, nameLike: String?, offset: Int, limit: Int): PeoplePage = locked {
        val where = buildList {
            when (alive) {
                true -> add("dod IS NULL")
                false -> add("dod IS NOT NULL")
                null -> {}
            }
            if (nameLike != null) add("name LIKE ?")
        }.let { if (it.isEmpty()) "" else "WHERE " + it.joinToString(" AND ") }
        val nameParam = nameLike?.let { "%$it%" }

        val total = conn.prepareStatement("SELECT COUNT(*) FROM people $where").use { st ->
            nameParam?.let { st.setString(1, it) }
            st.executeQuery().use { it.next(); it.getInt(1) }
        }
        val items = conn.prepareStatement("SELECT * FROM people $where ORDER BY id LIMIT ? OFFSET ?").use { st ->
            var i = 1
            nameParam?.let { st.setString(i++, it) }
            st.setInt(i++, limit)
            st.setInt(i, offset)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSummary()) } }
        }
        PeoplePage(total, offset, items)
    }

    suspend fun person(id: Long): PersonDetail = locked {
        val row = conn.prepareStatement("SELECT * FROM people WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) throw NoSuchElementException("Person $id not found")
                Triple(rs.toSummary(), rs.getLongOrNull("father") to rs.getLongOrNull("mother"),
                    rs.getLongOrNull("spouse") to rs.getString("date_of_marriage"))
            }
        }
        val (summary, parents, marriage) = row
        val children = conn.prepareStatement("SELECT * FROM people WHERE father = ? OR mother = ? ORDER BY dob")
            .use { st ->
                st.setLong(1, id)
                st.setLong(2, id)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSummary()) } }
            }
        val asOf = summary.dod?.let(LocalDate::parse) ?: LocalDate.of(currentYear() ?: startingYear(), 1, 1)
        PersonDetail(
            person = summary,
            age = Period.between(LocalDate.parse(summary.dob), asOf).years,
            father = parents.first?.let(::summaryById),
            mother = parents.second?.let(::summaryById),
            spouse = marriage.first?.let(::summaryById),
            dateOfMarriage = marriage.second,
            children = children,
        )
    }

    // ---------------------------------------------------------------- simulation

    private class Living(
        val id: Long,
        val name: String,
        val sex: Char,
        val dob: LocalDate,
        var spouseId: Long?,
        var marriedOn: LocalDate?,
        /** True if [spouseId] points to someone still alive. */
        var married: Boolean,
    ) {
        fun age(on: LocalDate) = Period.between(dob, on).years
    }

    private fun populate(year: Int) {
        val jan1 = LocalDate.of(year, 1, 1)
        val people = (1..INITIAL_POPULATION).map { id ->
            val sex = if (rnd.nextBoolean()) 'M' else 'F'
            val age = initialAge()
            // Born in (year - 1 - age) so they are exactly `age` on Jan 1.
            Living(id.toLong(), names.fullName(sex), sex, randomDate(year - 1 - age), null, null, false)
        }

        // Pair up a share of the adults; marriage happened some time before the world starts.
        val adults = people.filter { it.age(jan1) >= MIN_MARRIAGE_AGE }
        val women = adults.filter { it.sex == 'F' }
        val couples = pairUp(adults.filter { it.sex == 'M' }, women, (women.size * INITIAL_MARRIED_SHARE).toInt())
        for ((man, woman) in couples) {
            val earliest = maxOf(man.dob, woman.dob).plusYears(MIN_MARRIAGE_AGE.toLong())
            val date = randomDateBetween(earliest, jan1.minusDays(1))
            marry(man, woman, date)
        }

        conn.prepareStatement(
            "INSERT INTO people (id, name, sex, dob, spouse, date_of_marriage) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { st ->
            for (p in people) {
                st.setLong(1, p.id)
                st.setString(2, p.name)
                st.setString(3, p.sex.toString())
                st.setString(4, p.dob.toString())
                st.setObject(5, p.spouseId)
                st.setString(6, p.marriedOn?.toString())
                st.addBatch()
            }
            st.executeBatch()
        }
        setWorld("current_year", year.toString())
    }

    private fun simulateYear(year: Int): YearReport {
        val jan1 = LocalDate.of(year, 1, 1)
        val living = loadLiving()
        val popStart = living.size

        // Deaths: older people are much more likely to be picked.
        val deathCount = (popStart * DEATH_RATE).roundToInt()
        val dead = weightedSample(living.values.toList(), deathCount) { mortalityWeight(it.age(jan1)) }
        conn.prepareStatement("UPDATE people SET dod = ? WHERE id = ?").use { st ->
            for (p in dead) {
                st.setString(1, randomDate(year, notBefore = p.dob).toString())
                st.setLong(2, p.id)
                st.addBatch()
                living.remove(p.id)
                p.spouseId?.let { living[it] }?.married = false
            }
            st.executeBatch()
        }

        // Marriages between singles (incl. widowed) of marriageable age.
        val singles = living.values.filter { !it.married && it.age(jan1) in MIN_MARRIAGE_AGE..MAX_MARRIAGE_AGE }
        val couples = pairUp(
            singles.filter { it.sex == 'M' },
            singles.filter { it.sex == 'F' },
            (popStart * MARRIAGE_RATE).roundToInt(),
        )
        conn.prepareStatement("UPDATE people SET spouse = ?, date_of_marriage = ? WHERE id = ?").use { st ->
            for ((man, woman) in couples) {
                val date = randomDate(year)
                marry(man, woman, date)
                for ((a, b) in listOf(man to woman, woman to man)) {
                    st.setLong(1, b.id)
                    st.setString(2, date.toString())
                    st.setLong(3, a.id)
                    st.addBatch()
                }
            }
            st.executeBatch()
        }

        // Births to married women of childbearing age with a living husband.
        val mothers = living.values.filter { it.sex == 'F' && it.married && it.age(jan1) in MIN_MOTHER_AGE..MAX_MOTHER_AGE }
        val birthCount = (popStart * BIRTH_RATE).roundToInt()
        if (mothers.size < birthCount) {
            log.warn("{}: only {} eligible mothers for {} births", year, mothers.size, birthCount)
        }
        var nextId = conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(id), 0) + 1 FROM people").use { it.next(); it.getLong(1) }
        }
        val births = mothers.shuffled(rnd).take(birthCount)
        conn.prepareStatement(
            "INSERT INTO people (id, name, sex, dob, father, mother) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { st ->
            for (mother in births) {
                val father = living.getValue(mother.spouseId!!)
                val sex = if (rnd.nextBoolean()) 'M' else 'F'
                st.setLong(1, nextId++)
                st.setString(2, names.fullName(sex, NameGenerator.surnameOf(father.name)))
                st.setString(3, sex.toString())
                st.setString(4, randomDate(year, notBefore = mother.marriedOn).toString())
                st.setLong(5, father.id)
                st.setLong(6, mother.id)
                st.addBatch()
            }
            st.executeBatch()
        }

        val report = YearReport(
            year = year,
            populationStart = popStart,
            births = births.size,
            deaths = dead.size,
            marriages = couples.size,
            populationEnd = living.size + births.size,
        )
        conn.prepareStatement("INSERT INTO year_log VALUES (?, ?, ?, ?, ?, ?)").use { st ->
            st.setInt(1, report.year)
            st.setInt(2, report.populationStart)
            st.setInt(3, report.births)
            st.setInt(4, report.deaths)
            st.setInt(5, report.marriages)
            st.setInt(6, report.populationEnd)
            st.executeUpdate()
        }
        setWorld("current_year", (year + 1).toString())
        return report
    }

    private fun marry(man: Living, woman: Living, date: LocalDate) {
        man.spouseId = woman.id
        woman.spouseId = man.id
        man.marriedOn = date
        woman.marriedOn = date
        man.married = true
        woman.married = true
    }

    /** Matches each woman with a random man born up to 8 years before or 3 years after her. */
    private fun pairUp(men: List<Living>, women: List<Living>, max: Int): List<Pair<Living, Living>> {
        val menByYear = men.shuffled(rnd).groupByTo(HashMap()) { it.dob.year }
        val couples = mutableListOf<Pair<Living, Living>>()
        for (woman in women.shuffled(rnd)) {
            if (couples.size >= max) break
            val year = (woman.dob.year - 8..woman.dob.year + 3).shuffled(rnd)
                .firstOrNull { !menByYear[it].isNullOrEmpty() } ?: continue
            couples += menByYear.getValue(year).removeLast() to woman
        }
        return couples
    }

    private fun loadLiving(): MutableMap<Long, Living> =
        conn.prepareStatement(
            """
            SELECT p.id, p.name, p.sex, p.dob, p.spouse, p.date_of_marriage,
                   (s.id IS NOT NULL AND s.dod IS NULL) AS spouse_alive
            FROM people p LEFT JOIN people s ON s.id = p.spouse
            WHERE p.dod IS NULL
            """.trimIndent()
        ).use { st ->
            st.executeQuery().use { rs ->
                val map = LinkedHashMap<Long, Living>()
                while (rs.next()) {
                    val p = Living(
                        id = rs.getLong("id"),
                        name = rs.getString("name"),
                        sex = rs.getString("sex")[0],
                        dob = LocalDate.parse(rs.getString("dob")),
                        spouseId = rs.getLongOrNull("spouse"),
                        marriedOn = rs.getString("date_of_marriage")?.let(LocalDate::parse),
                        married = rs.getBoolean("spouse_alive"),
                    )
                    map[p.id] = p
                }
                map
            }
        }

    /** Relative chance of dying: infant mortality bump plus Gompertz-style growth with age. */
    private fun mortalityWeight(age: Int): Double = when (age) {
        0 -> 0.03
        in 1..4 -> 0.004
        else -> maxOf(0.0008, 0.0002 * exp(0.085 * age))
    }

    /** Initial ages follow a rough pyramid: many young people, few old ones. */
    private fun initialAge(): Int {
        while (true) {
            val age = rnd.nextInt(0, 91)
            if (rnd.nextDouble() < exp(-age / 30.0)) return age
        }
    }

    /** Weighted sampling without replacement (Efraimidis–Spirakis). */
    private fun <T> weightedSample(items: List<T>, k: Int, weight: (T) -> Double): List<T> =
        items.map { it to ln(1.0 - rnd.nextDouble()) / weight(it) }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }

    private fun randomDate(year: Int, notBefore: LocalDate? = null): LocalDate {
        val start = maxOf(LocalDate.of(year, 1, 1), notBefore ?: LocalDate.MIN)
        return randomDateBetween(start, LocalDate.of(year, 12, 31))
    }

    private fun randomDateBetween(from: LocalDate, to: LocalDate): LocalDate {
        if (!from.isBefore(to)) return to
        return from.plusDays(rnd.nextLong(ChronoUnit.DAYS.between(from, to) + 1))
    }

    // ---------------------------------------------------------------- db helpers

    private fun statusNow(): WorldStatus {
        val current = currentYear()
        val end = endingYear()
        fun count(sql: String) = conn.createStatement().use { st ->
            st.executeQuery(sql).use { it.next(); it.getInt(1) }
        }
        return WorldStatus(
            startingYear = startingYear(),
            endingYear = end,
            currentYear = current,
            population = count("SELECT COUNT(*) FROM people WHERE dod IS NULL"),
            totalEverLived = count("SELECT COUNT(*) FROM people"),
            finished = current != null && current > end,
        )
    }

    private fun summaryById(id: Long): PersonSummary? =
        conn.prepareStatement("SELECT * FROM people WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { if (it.next()) it.toSummary() else null }
        }

    private fun startingYear() = getWorld("starting_year")!!.toInt()
    private fun endingYear() = getWorld("ending_year")!!.toInt()
    private fun currentYear() = getWorld("current_year")?.toInt()

    private fun getWorld(id: String): String? =
        conn.prepareStatement("SELECT value FROM world WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    private fun setWorld(id: String, value: String) {
        conn.prepareStatement(
            "INSERT INTO world (id, value) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET value = excluded.value"
        ).use { st ->
            st.setString(1, id)
            st.setString(2, value)
            st.executeUpdate()
        }
    }
}

private fun ResultSet.getLongOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

private fun ResultSet.toSummary() = PersonSummary(
    id = getLong("id"),
    name = getString("name"),
    sex = getString("sex"),
    dob = getString("dob"),
    dod = getString("dod"),
)
