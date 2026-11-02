package islabonita.server

import kotlin.random.Random

/**
 * Island-flavoured names: first names from fixed lists, surnames either from a list
 * or built from syllables + a Spanish-style suffix. Names are always "First Surname"
 * (one token each) so a child can inherit the father's surname.
 */
class NameGenerator(private val rnd: Random) {

    fun firstName(sex: Char): String = if (sex == 'M') MALE.random(rnd) else FEMALE.random(rnd)

    fun surname(): String =
        if (rnd.nextBoolean()) SURNAMES.random(rnd) else syllableSurname()

    fun fullName(sex: Char, surname: String = surname()): String = "${firstName(sex)} $surname"

    private fun syllableSurname(): String {
        val parts = rnd.nextInt(1, 3)
        val root = (0 until parts).joinToString("") { ONSETS.random(rnd) + VOWELS.random(rnd) }
        return (root + SUFFIXES.random(rnd)).replaceFirstChar { it.uppercase() }
    }

    companion object {
        fun surnameOf(fullName: String): String = fullName.substringAfterLast(' ')

        private val MALE = listOf(
            "Juan", "Carlos", "Miguel", "José", "Luis", "Antonio", "Manuel", "Pedro", "Rafael", "Francisco",
            "Jorge", "Alejandro", "Diego", "Fernando", "Ricardo", "Eduardo", "Roberto", "Andrés", "Ramón", "Pablo",
            "Sergio", "Javier", "Raúl", "Héctor", "Emilio", "Arturo", "Esteban", "Tomás", "Felipe", "Ignacio",
            "Mateo", "Santiago", "Gabriel", "Hugo", "Óscar", "Julio", "Alberto", "Víctor", "Ernesto", "Mario",
        )
        private val FEMALE = listOf(
            "María", "Carmen", "Ana", "Isabel", "Rosa", "Lucía", "Elena", "Teresa", "Pilar", "Dolores",
            "Sofía", "Paula", "Laura", "Marta", "Beatriz", "Cristina", "Raquel", "Patricia", "Adriana", "Alicia",
            "Inés", "Julia", "Clara", "Victoria", "Gloria", "Esperanza", "Mercedes", "Consuelo", "Luz", "Rocío",
            "Valentina", "Camila", "Daniela", "Gabriela", "Natalia", "Andrea", "Silvia", "Yolanda", "Noemí", "Celia",
        )
        private val SURNAMES = listOf(
            "García", "Rodríguez", "Martínez", "López", "González", "Pérez", "Sánchez", "Ramírez", "Torres", "Flores",
            "Rivera", "Gómez", "Díaz", "Cruz", "Morales", "Reyes", "Ortiz", "Gutiérrez", "Chávez", "Ramos",
            "Vargas", "Castillo", "Jiménez", "Moreno", "Romero", "Herrera", "Medina", "Aguilar", "Vega", "Castro",
            "Mendoza", "Ruiz", "Álvarez", "Delgado", "Navarro", "Salazar", "Fuentes", "Campos", "Rojas", "Domínguez",
        )
        private val ONSETS = listOf("b", "c", "d", "g", "l", "m", "n", "p", "r", "s", "t", "v", "ch", "qu", "br", "gr")
        private val VOWELS = listOf("a", "e", "i", "o", "u", "a", "o")
        private val SUFFIXES = listOf("ez", "ero", "ado", "illo", "ano", "eda", "ales", "on", "ar", "ino")
    }
}
