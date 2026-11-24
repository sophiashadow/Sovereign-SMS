package com.sovereignops.sms.vault

/**
 * Migration SQL, kept free of Android types so `VaultMigrationTest` can check it against the
 * schemas Room exports (app/schemas). Every migration is additive: columns are added with
 * defaults, nothing is dropped, renamed or rewritten.
 */
object VaultMigrations {
    val SQL_2_3 = listOf(
        "ALTER TABLE `ledger` ADD COLUMN `basis` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `ledger` ADD COLUMN `cashReceivedCents` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `ledger` ADD COLUMN `refundCents` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `ledger` ADD COLUMN `expenseCents` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `ledger` ADD COLUMN `receivableCents` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `ledger` ADD COLUMN `restrictedCreditCents` INTEGER NOT NULL DEFAULT 0",
    )
}
