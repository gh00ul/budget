package com.gh00ul.budget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Which bank transactions are transfers and which are pay (BudgetLogic.kt: isTransfer, isPayroll).
//
// Covered:
// - isTransfer: "Transfer In" / "Transfer Out", loan payments, loan disbursements (borrowing isn't a refund), credit
//   card payments by category and by name ("PAYMENT TO CREDIT CARD", "CREDIT CARD PAYMENT", "CREDIT CARD PMT",
//   "PAYMENT THANK YOU", "PAYMENT - THANK YOU"), names starting "TRANSFER TO " / "TRANSFER FROM ". NOT transfers
//   (they're spending): ATM/cash withdrawals, money through apps ("from apps"), pay-later ("bnpl") — even when the
//   name or category would otherwise say transfer; ordinary purchases; "TRANSFER TO" in the middle of a name.
// - isPayroll: wages / salary in the category detail; PAYROLL, DIR DEP, DIRECT DEP, SALARY in the name (any case);
//   other income isn't pay.
// The category texts are the server's ("Food And Drink", "Transfer Out", "Transfer Out Withdrawal", "Income Wages").
class BudgetLogicCategoryTest {

    private fun txn(name: String = "SOMETHING", category: String = "", detail: String = "", amount: Double = -10.0) =
        BankTxn("t1", "checking", LocalDate.of(2026, 10, 1), name, amount, false, category, detail)

    // ---- isTransfer: transfers ----

    @Test
    fun `money moved in from another account is a transfer`() {
        assertTrue(isTransfer(txn(category = "Transfer In", detail = "Transfer In Account Transfer", amount = 500.0)))
    }

    @Test
    fun `money moved out to another account is a transfer`() {
        assertTrue(isTransfer(txn(category = "Transfer Out", detail = "Transfer Out Account Transfer")))
    }

    @Test
    fun `a loan payment is a transfer`() {
        assertTrue(isTransfer(txn(category = "Loan Payments", detail = "Loan Payments Car Payment")))
    }

    @Test
    fun `a loan disbursement is a transfer, not a refund`() {
        assertTrue(isTransfer(txn(category = "Loan Disbursements", detail = "Loan Disbursements Personal", amount = 2000.0)))
    }

    @Test
    fun `a credit card payment category is a transfer`() {
        assertTrue(isTransfer(txn(category = "Loan Payments", detail = "Loan Payments Credit Card Payment")))
    }

    @Test
    fun `credit card payment names are transfers`() {
        for (name in listOf("PAYMENT TO CREDIT CARD 1234", "CHASE CREDIT CARD PAYMENT", "AMEX CREDIT CARD PMT")) {
            assertTrue(name, isTransfer(txn(name = name, category = "General Services")))
        }
    }

    @Test
    fun `a card's PAYMENT THANK YOU is a transfer in either spelling and any case`() {
        for (name in listOf("PAYMENT THANK YOU", "Payment - Thank You", "AUTOPAY PAYMENT THANK YOU")) {
            assertTrue(name, isTransfer(txn(name = name, amount = 250.0)))
        }
    }

    @Test
    fun `a name starting TRANSFER TO or TRANSFER FROM is a transfer`() {
        assertTrue(isTransfer(txn(name = "TRANSFER TO SAVINGS 5678")))
        assertTrue(isTransfer(txn(name = "Transfer from Checking 1234", amount = 100.0)))
    }

    // ---- isTransfer: spending ----

    @Test
    fun `a cash withdrawal is spending, not a transfer`() {
        assertFalse(isTransfer(txn(name = "ATM WITHDRAWAL", category = "Transfer Out", detail = "Transfer Out Withdrawal")))
    }

    @Test
    fun `money sent or received through apps is not a transfer`() {
        assertFalse(isTransfer(txn(name = "VENMO", category = "Transfer Out", detail = "Transfer Out From Apps")))
        assertFalse(isTransfer(txn(name = "CASH APP", category = "Transfer In", detail = "Transfer In From Apps", amount = 20.0)))
    }

    @Test
    fun `a pay-later installment is not a transfer`() {
        assertFalse(isTransfer(txn(name = "AFFIRM", category = "Loan Payments", detail = "Loan Payments Bnpl")))
    }

    @Test
    fun `withdrawal, apps and pay-later win over a transfer name`() {
        assertFalse(isTransfer(txn(name = "TRANSFER TO SAVINGS", detail = "Transfer Out Withdrawal")))
        assertFalse(isTransfer(txn(name = "PAYMENT THANK YOU", category = "Bnpl")))
        assertFalse(isTransfer(txn(name = "TRANSFER FROM VENMO", detail = "Transfer In From Apps")))
    }

    @Test
    fun `an ordinary purchase is not a transfer`() {
        assertFalse(isTransfer(txn(name = "CHIPOTLE 1234", category = "Food And Drink", detail = "Food And Drink Restaurant")))
    }

    @Test
    fun `TRANSFER TO in the middle of a name is not a transfer`() {
        assertFalse(isTransfer(txn(name = "WIRE TRANSFER TO JOHN", category = "General Services")))
    }

    // ---- isPayroll ----

    @Test
    fun `wages in the category detail are pay`() {
        assertTrue(isPayroll(txn(name = "ACME CORP", category = "Income", detail = "Income Wages", amount = 1500.0)))
    }

    @Test
    fun `salary in the category detail is pay`() {
        assertTrue(isPayroll(txn(name = "ACME CORP", category = "Income", detail = "Income Salary", amount = 1500.0)))
    }

    @Test
    fun `payroll and direct deposit names are pay in any case`() {
        for (name in listOf("ACME PAYROLL", "ACME DIR DEP", "DIRECT DEPOSIT ACME", "acme salary", "Acme Payroll Ppd")) {
            assertTrue(name, isPayroll(txn(name = name, amount = 1500.0)))
        }
    }

    @Test
    fun `other income is not pay`() {
        assertFalse(isPayroll(txn(name = "INTEREST PAYMENT", category = "Income", detail = "Income Interest Earned", amount = 1.25)))
        assertFalse(isPayroll(txn(name = "IRS TREAS 310 TAX REF", category = "Income", detail = "Income Tax Refund", amount = 900.0)))
    }
}
