package islabonita.shared

import kotlinx.serialization.Serializable

@Serializable
data class WorldStatus(
    val startingYear: Int,
    val endingYear: Int,
    /** The next year to be simulated; null until the world is initialized. */
    val currentYear: Int?,
    val population: Int,
    val totalEverLived: Int,
    val finished: Boolean,
)

@Serializable
data class YearReport(
    val year: Int,
    val populationStart: Int,
    val births: Int,
    val deaths: Int,
    val marriages: Int,
    val populationEnd: Int,
)

@Serializable
data class AdvanceResult(
    val reports: List<YearReport>,
    val status: WorldStatus,
)

@Serializable
data class PersonSummary(
    val id: Long,
    val name: String,
    val sex: String,
    val dob: String,
    val dod: String?,
)

@Serializable
data class PersonDetail(
    val person: PersonSummary,
    /** Age at death, or age on Jan 1 of the current year if alive. */
    val age: Int,
    val father: PersonSummary?,
    val mother: PersonSummary?,
    val spouse: PersonSummary?,
    val dateOfMarriage: String?,
    val children: List<PersonSummary>,
)

@Serializable
data class PeoplePage(
    val total: Int,
    val offset: Int,
    val items: List<PersonSummary>,
)

@Serializable
data class ApiError(val error: String)
