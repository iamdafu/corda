package core

import java.math.BigDecimal
import java.security.PublicKey
import java.util.*
import kotlin.math.div

/**
 * Defines a simple domain specific language for the specificiation of financial contracts. Currently covers:
 *
 *  - Code for working with currencies.
 *  - An Amount type that represents a positive quantity of a specific currency.
 *  - A simple language extension for specifying requirements in English, along with logic to enforce them.
 */

// TODO: Look into replacing Currency and Amount with CurrencyUnit and MonetaryAmount from the javax.money API (JSR 354)

// region Misc
inline fun <reified T : Command> List<VerifiedSigned<Command>>.select(signer: PublicKey? = null, institution: Institution? = null) =
        filter { it.value is T }.
        filter { if (signer == null) true else signer == it.signer }.
        filter { if (institution == null) true else institution == it.signingInstitution }.
        map { VerifiedSigned<T>(it.signer, it.signingInstitution, it.value as T) }

inline fun <reified T : Command> List<VerifiedSigned<Command>>.requireSingleCommand() = select<T>().single()

// endregion

// region Currencies
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
fun currency(code: String) = Currency.getInstance(code)

val USD = currency("USD")
val GBP = currency("GBP")
val CHF = currency("CHF")

val Int.DOLLARS: Amount get() = Amount(this * 100, USD)
val Int.POUNDS: Amount get() = Amount(this * 100, GBP)
val Int.SWISS_FRANCS: Amount get() = Amount(this * 100, CHF)
val Double.DOLLARS: Amount get() = Amount((this * 100).toInt(), USD)
val Double.POUNDS: Amount get() = Amount((this * 100).toInt(), USD)
val Double.SWISS_FRANCS: Amount get() = Amount((this * 100).toInt(), USD)
// endregion


// region Requirements
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object Requirements {
    infix fun String.by(expr: Boolean) {
        if (!expr) throw IllegalArgumentException("Failed requirement: $this")
    }
}
fun requireThat(body: Requirements.() -> Unit) {
    Requirements.body()
}

// endregion


// region Amounts
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/**
 * Amount represents a positive quantity of currency, measured in pennies, which are the smallest representable units.
 * Note that "pennies" are not necessarily 1/100ths of a currency unit, but are the actual smallest amount used in
 * whatever currency the amount represents.
 *
 * Amounts of different currencies *do not mix* and attempting to add or subtract two amounts of different currencies
 * will throw [IllegalArgumentException]. Amounts may not be negative.
 *
 * It probably makes sense to replace this with convenience extensions over the JSR 354 MonetaryAmount interface, if
 * that spec doesn't turn out to be too heavy (it looks fairly complicated).
 */
data class Amount(val pennies: Int, val currency: Currency) : Comparable<Amount> {
    init {
        // Negative amounts are of course a vital part of any ledger, but negative values are only valid in certain
        // contexts: you cannot send a negative amount of cash, but you can (sometimes) have a negative balance.
        require(pennies >= 0) { "Negative amounts are not allowed: $pennies" }
    }

    operator fun plus(other: Amount): Amount {
        checkCurrency(other)
        return Amount(pennies + other.pennies, currency)
    }

    operator fun minus(other: Amount): Amount {
        checkCurrency(other)
        return Amount(pennies - other.pennies, currency)
    }

    private fun checkCurrency(other: Amount) {
        require(other.currency == currency) { "Currency mismatch: ${other.currency} vs $currency" }
    }

    operator fun div(other: Int): Amount = Amount(pennies / other, currency)
    operator fun times(other: Int): Amount = Amount(pennies * other, currency)

    override fun toString(): String = currency.currencyCode + " " + (BigDecimal(pennies) / BigDecimal(100)).toPlainString()

    override fun compareTo(other: Amount): Int {
        checkCurrency(other)
        return pennies.compareTo(other.pennies)
    }
}

// Note: this will throw an exception if the iterable is empty.
fun Iterable<Amount>.sum() = reduce { left, right -> left + right }
fun Iterable<Amount>.sumOrZero(currency: Currency) = if (iterator().hasNext()) sum() else Amount(0, currency)

// TODO: Think about how positive-only vs positive-or-negative amounts can be represented in the type system.
// endregion
