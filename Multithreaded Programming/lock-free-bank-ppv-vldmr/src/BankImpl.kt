import kotlinx.atomicfu.*
import java.lang.Integer.max
import java.lang.Integer.min

class BankImpl(override val numberOfAccounts: Int) : Bank {
    private val accounts = atomicArrayOfNulls<Account>(numberOfAccounts)

    init {
        for (i in 0 until numberOfAccounts) accounts[i].value = Account(0)
    }

    private fun account(index: Int) = accounts[index].value!!

    override fun getAmount(index: Int): Long {
        while (true) {
            val account = account(index)
            /*
             * If there is a pending operation on this account, then help to complete it first using
             * its invokeOperation method. If the result is false then there is no pending operation,
             * thus the account amount can be safely returned.
             */
            if (!account.invokeOperation()) return account.amount
        }
    }

    override val totalAmount: Long
        get() {
            val op = TotalAmountOp()
            op.invokeOperation()
            return op.sum
        }

    override fun deposit(index: Int, amount: Long): Long { // First, validate method per-conditions
        require(amount > 0) { "Invalid amount: $amount" }
        check(amount <= MAX_AMOUNT) { "Overflow" }
        while (true) {
            val account = account(index)
            if (account.invokeOperation()) continue
            check(account.amount + amount <= MAX_AMOUNT) { "Overflow" }
            val updated = Account(account.amount + amount)
            if (accounts[index].compareAndSet(account, updated)) return updated.amount
        }
    }

    override fun withdraw(index: Int, amount: Long): Long {
        require(amount > 0) { "Invalid amount: $amount" }
        while (true) {
            val account = account(index)
            if (account.invokeOperation()) continue
            check(account.amount - amount >= 0) { "Underflow" }
            val updated = Account(account.amount - amount)
            if (accounts[index].compareAndSet(account, updated)) return updated.amount
        }
    }

    override fun transfer(fromIndex: Int, toIndex: Int, amount: Long) {
        require(amount > 0) { "Invalid amount: $amount" }
        require(fromIndex != toIndex) { "fromIndex == toIndex" }
        check(amount <= MAX_AMOUNT) { "Underflow/overflow" }
        val op = TransferOp(fromIndex, toIndex, amount)
        op.invokeOperation()
        op.errorMessage?.let { error(it) }
    }

    private fun acquire(index: Int, op: Op): AcquiredAccount? {
        /*
         * This method must loop trying to replace accounts[index] with an instance of
         *     new AcquiredAccount(<old-amount>, op) until that successfully happens and return the
         *     instance of AcquiredAccount in this case.
         *
         * If current account is already "Acquired" by another operation, then this method must help that
         * other operation by invoking "invokeOperation" and continue trying.
         *
         * Because accounts[index] does not have an ABA problem, there is no need to implement full-blown
         * DCSS operation with descriptors for DCSS operation as explained in Harris CASN work. A simple
         * lock-free compareAndSet loop suffices here if op.completed is checked after the accounts[index]
         * is read.
         *
         * Basically, implementation of this method must perform the logic of the following code "atomically":
         */
//        if (op.completed) return null
//        val account = account(index)
//        val acquiredAccount = AcquiredAccount(account.amount, op)
//        accounts[index].value = acquiredAccount
//        return acquiredAccount
        while (true) {
            val account = account(index)
            if (op.completed) return null
            if (account is AcquiredAccount && account.op === op) return account
            if (account.invokeOperation()) continue
            val acquiredAccount = AcquiredAccount(account.amount, op)
            if (accounts[index].compareAndSet(account, acquiredAccount)) {
                return acquiredAccount
            }
        }
    }


    private fun release(index: Int, op: Op) {
        assert(op.completed) // must be called only on operations that were already completed
        val account = account(index)
        if (account is AcquiredAccount && account.op === op) {
            // release performs update at most once while the account is still acquired
            val updated = Account(account.newAmount)
            accounts[index].compareAndSet(account, updated)
        }
    }


    private open class Account(val amount: Long) {
        open fun invokeOperation(): Boolean = false
    }


    private class AcquiredAccount(
        var newAmount: Long, // New amount of funds in this account when op completes.
        val op: Op
    ) : Account(newAmount) {
        override fun invokeOperation(): Boolean {
            op.invokeOperation()
            return true
        }
    }

    private abstract inner class Op {
        @Volatile
        var completed = false

        abstract fun invokeOperation()
    }

    private inner class TotalAmountOp : Op() {
        var sum = 0L

        override fun invokeOperation() {
            var sum = 0L
            var acquired = 0
            while (acquired < numberOfAccounts) {
                val account = acquire(acquired, this) ?: break
                sum += account.newAmount
                acquired++
            }
            if (acquired == numberOfAccounts) {
                /*
                 * If i == n, then all acquired accounts were not null and full sum was calculated.
                 * this.sum = sum assignment below has a benign data race. Multiple threads might to this assignment
                 * concurrently, however, they are all guaranteed to be assigning the same value.
                 */
                this.sum = sum
                completed = true // volatile write to completed field _after_ the sum was written
            }
            /*
             * To ensure lock-freedom, we must release all accounts even if this particular helper operation
             * had failed to acquire all of them before somebody else had completed the operations.
             * By releasing all accounts for completed operation we ensure progress of other operations.
             */
            for (i in 0 until numberOfAccounts) {
                release(i, this)
            }
        }
    }


    private inner class TransferOp(val fromIndex: Int, val toIndex: Int, val amount: Long) : Op() {
        var errorMessage: String? = null

        override fun invokeOperation() {
            // todo: write implementation for this method, use TotalAmountOp as an example
            /*
             * In the implementation of this operation only two accounts (with fromIndex and toIndex) needs
             * to be acquired. Unlike TotalAmountOp, this operation has its own result in errorMessage string,
             * and it must also update AcquiredAccount.newAmount fields before setting completed to true
             * and invoking release on those acquired accounts.
             *
             * Basically, implementation of this method must perform the logic of the following code "atomically":
             */
            acquire(min(fromIndex, toIndex), this)
            val from = acquire(fromIndex, this)
            val to = acquire(toIndex, this)

            if (from != null && to != null) {
                when {
                    amount > from.amount -> errorMessage = "Underflow"
                    to.amount + amount > MAX_AMOUNT -> errorMessage = "Overflow"
                    else -> {
                        from.newAmount = from.amount - amount
                        to.newAmount = to.amount + amount
                    }
                }
            }
            completed = true

            release(max(fromIndex, toIndex), this)
            release(min(fromIndex, toIndex), this)

            /*val from = account(fromIndex)
            val to = account(toIndex)
            when {
                amount > from.amount -> errorMessage = "Underflow"
                to.amount + amount > MAX_AMOUNT -> errorMessage = "Overflow"
                else -> {
                    accounts[fromIndex].value = Account(from.amount - amount)
                    accounts[toIndex].value = Account(to.amount + amount)
                }
            }*/
        }
    }
}