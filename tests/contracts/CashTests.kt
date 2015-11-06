import contracts.*
import core.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// TODO: Some basic invariants should be enforced by the platform before contract execution:
// 1. No duplicate input states
// 2. There must be at least one input state (note: not "one of the type the contract wants")

class CashTests {
    val inState = CashState(
            deposit = InstitutionReference(MEGA_CORP, OpaqueBytes.of(1)),
            amount = 1000.DOLLARS,
            owner = DUMMY_PUBKEY_1
    )
    val outState = inState.copy(owner = DUMMY_PUBKEY_2)
    val contract = CashContract

    fun CashState.editInstitution(institution: Institution) = copy(deposit = deposit.copy(institution = institution))
    fun CashState.editDepositRef(ref: Byte) = copy(deposit = deposit.copy(reference = OpaqueBytes.of(ref)))

    @Test
    fun trivial() {
        transaction {
            contract `fails requirement` "there is at least one cash input"

            input { inState }
            contract `fails requirement` "the amounts balance"

            transaction {
                output { outState.copy(amount = 2000.DOLLARS )}
                contract `fails requirement` "the amounts balance"
            }
            transaction {
                output { outState }
                // No command arguments
                contract `fails requirement` "the owning keys are the same as the signing keys"
            }
            transaction {
                output { outState }
                arg(DUMMY_PUBKEY_2) { MoveCashCommand() }
                contract `fails requirement` "the owning keys are the same as the signing keys"
            }
            transaction {
                output { outState }
                output { outState.editInstitution(MINI_CORP) }
                contract `fails requirement` "no output states are unaccounted for"
            }
            // Simple reallocation works.
            transaction {
                output { outState }
                arg(DUMMY_PUBKEY_1) { MoveCashCommand() }
                contract.accepts()
            }
        }
    }

    @Test
    fun testMergeSplit() {
        // Splitting value works.
        transaction {
            arg(DUMMY_PUBKEY_1) { MoveCashCommand() }
            transaction {
                input { inState }
                for (i in 1..4) output { inState.copy(amount = inState.amount / 4) }
                contract.accepts()
            }
            // Merging 4 inputs into 2 outputs works.
            transaction {
                for (i in 1..4) input { inState.copy(amount = inState.amount / 4) }
                output { inState.copy(amount = inState.amount / 2) }
                output { inState.copy(amount = inState.amount / 2) }
                contract.accepts()
            }
            // Merging 2 inputs into 1 works.
            transaction {
                input { inState.copy(amount = inState.amount / 2) }
                input { inState.copy(amount = inState.amount / 2) }
                output { inState }
                contract.accepts()
            }
        }

    }

    @Test
    fun zeroSizedInputs() {
        transaction {
            input { inState }
            input { inState.copy(amount = 0.DOLLARS) }
            contract `fails requirement` "zero sized inputs"
        }
    }

    @Test
    fun trivialMismatches() {
        // Can't change issuer.
        transaction {
            input { inState }
            output { outState.editInstitution(MINI_CORP) }
            contract `fails requirement` "at issuer MegaCorp the amounts balance"
        }
        // Can't change deposit reference when splitting.
        transaction {
            input { inState }
            output { outState.editDepositRef(0).copy(amount = inState.amount / 2) }
            output { outState.editDepositRef(1).copy(amount = inState.amount / 2) }
            contract `fails requirement` "for deposit [01] at issuer MegaCorp the amounts balance"
        }
        // Can't mix currencies.
        transaction {
            input { inState }
            output { outState.copy(amount = 800.DOLLARS) }
            output { outState.copy(amount = 200.POUNDS) }
            contract `fails requirement` "all outputs use the currency of the inputs"
        }
        transaction {
            input { inState }
            input {
                inState.copy(
                    amount = 150.POUNDS,
                    owner = DUMMY_PUBKEY_2
                )
            }
            output { outState.copy(amount = 1150.DOLLARS) }
            contract `fails requirement` "all inputs use the same currency"
        }
        // Can't have superfluous input states from different issuers.
        transaction {
            input { inState }
            input { inState.editInstitution(MINI_CORP) }
            output { outState }
            contract `fails requirement` "at issuer MiniCorp the amounts balance"
        }
        // Can't combine two different deposits at the same issuer.
        transaction {
            input { inState }
            input { inState.editDepositRef(3) }
            output { outState.copy(amount = inState.amount * 2).editDepositRef(3) }
            contract `fails requirement` "for deposit [01]"
        }
    }

    @Test
    fun exitLedger() {
        // Single input/output straightforward case.
        transaction {
            input { inState }
            output { outState.copy(amount = inState.amount - 200.DOLLARS) }

            transaction {
                arg(MEGA_CORP_KEY) { ExitCashCommand(100.DOLLARS) }
                contract `fails requirement` "the amounts balance"
            }

            transaction {
                arg(MEGA_CORP_KEY) { ExitCashCommand(200.DOLLARS) }
                contract `fails requirement` "the owning keys are the same as the signing keys"   // No move command.

                transaction {
                    arg(DUMMY_PUBKEY_1) { MoveCashCommand() }
                    contract.accepts()
                }
            }
        }
        // Multi-issuer case.
        transaction {
            input { inState }
            input { inState.editInstitution(MINI_CORP) }

            output { inState.copy(amount = inState.amount - 200.DOLLARS).editInstitution(MINI_CORP) }
            output { inState.copy(amount = inState.amount - 200.DOLLARS) }

            arg(DUMMY_PUBKEY_1) { MoveCashCommand() }

            contract `fails requirement` "at issuer MegaCorp the amounts balance"

            arg(MEGA_CORP_KEY) { ExitCashCommand(200.DOLLARS) }
            contract `fails requirement` "at issuer MiniCorp the amounts balance"

            arg(MINI_CORP_KEY) { ExitCashCommand(200.DOLLARS) }
            contract.accepts()
        }
    }

    @Test
    fun multiIssuer() {
        transaction {
            // Gather 2000 dollars from two different issuers.
            input { inState }
            input { inState.editInstitution(MINI_CORP) }

            // Can't merge them together.
            transaction {
                output { inState.copy(owner = DUMMY_PUBKEY_2, amount = 2000.DOLLARS) }
                contract `fails requirement` "at issuer MegaCorp the amounts balance"
            }
            // Missing MiniCorp deposit
            transaction {
                output { inState.copy(owner = DUMMY_PUBKEY_2) }
                output { inState.copy(owner = DUMMY_PUBKEY_2) }
                contract `fails requirement` "at issuer MegaCorp the amounts balance"
            }

            // This works.
            output { inState.copy(owner = DUMMY_PUBKEY_2) }
            output { inState.copy(owner = DUMMY_PUBKEY_2).editInstitution(MINI_CORP) }
            arg(DUMMY_PUBKEY_1) { MoveCashCommand() }
            contract.accepts()
        }

        transaction {
            input { inState }
            input { inState }
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Spend crafting

    val OUR_PUBKEY_1 = DUMMY_PUBKEY_1
    val THEIR_PUBKEY_1 = DUMMY_PUBKEY_2
    val WALLET = listOf(
            CashState(InstitutionReference(MEGA_CORP, OpaqueBytes.of(1)), 100.DOLLARS, OUR_PUBKEY_1),
            CashState(InstitutionReference(MEGA_CORP, OpaqueBytes.of(1)), 400.DOLLARS, OUR_PUBKEY_1),
            CashState(InstitutionReference(MINI_CORP, OpaqueBytes.of(1)), 80.DOLLARS, OUR_PUBKEY_1),
            CashState(InstitutionReference(MINI_CORP, OpaqueBytes.of(2)), 80.SWISS_FRANCS, OUR_PUBKEY_1)
    )

    @Test
    fun craftSimpleDirectSpend() {
        assertEquals(
                transaction {
                    input { WALLET[0] }
                    output { WALLET[0].copy(owner = THEIR_PUBKEY_1) }
                    arg(OUR_PUBKEY_1) { MoveCashCommand() }
                },
                contract.craftSpend(100.DOLLARS, THEIR_PUBKEY_1, WALLET)
        )
    }

    @Test
    fun craftSimpleSpendWithChange() {
        assertEquals(
                transaction {
                    input { WALLET[0] }
                    output { WALLET[0].copy(owner = THEIR_PUBKEY_1, amount = 10.DOLLARS) }
                    output { WALLET[0].copy(owner = OUR_PUBKEY_1, amount = 90.DOLLARS) }
                    arg(OUR_PUBKEY_1) { MoveCashCommand() }
                },
                contract.craftSpend(10.DOLLARS, THEIR_PUBKEY_1, WALLET)
        )
    }

    @Test
    fun craftSpendWithTwoInputs() {
        assertEquals(
                transaction {
                    input { WALLET[0] }
                    input { WALLET[1] }
                    output { WALLET[0].copy(owner = THEIR_PUBKEY_1, amount = 500.DOLLARS) }
                    arg(OUR_PUBKEY_1) { MoveCashCommand() }
                },
                contract.craftSpend(500.DOLLARS, THEIR_PUBKEY_1, WALLET)
        )
    }

    @Test
    fun craftSpendMixedDeposits() {
        assertEquals(
                transaction {
                    input { WALLET[0] }
                    input { WALLET[1] }
                    input { WALLET[2] }
                    output { WALLET[0].copy(owner = THEIR_PUBKEY_1, amount = 500.DOLLARS) }
                    output { WALLET[2].copy(owner = THEIR_PUBKEY_1) }
                    arg(OUR_PUBKEY_1) { MoveCashCommand() }
                },
                contract.craftSpend(580.DOLLARS, THEIR_PUBKEY_1, WALLET)
        )
    }

    @Test
    fun craftSpendInsufficientBalance() {
        assertFailsWith(InsufficientBalanceException::class) {
            contract.craftSpend(1000.DOLLARS, THEIR_PUBKEY_1, WALLET)
        }
        assertFailsWith(InsufficientBalanceException::class) {
            contract.craftSpend(81.SWISS_FRANCS, THEIR_PUBKEY_1, WALLET)
        }
    }
}
