/*
 * Imperium, the software collection powering the Xpdustry network.
 * Copyright (C) 2023  Xpdustry
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.xpdustry.imperium.common.account

import com.mongodb.client.model.Filters
import com.xpdustry.imperium.common.application.ImperiumApplication
import com.xpdustry.imperium.common.database.mongo.MongoEntityCollection
import com.xpdustry.imperium.common.database.mongo.MongoProvider
import com.xpdustry.imperium.common.hash.Argon2HashFunction
import com.xpdustry.imperium.common.hash.Argon2Params
import com.xpdustry.imperium.common.hash.GenericSaltyHashFunction
import com.xpdustry.imperium.common.hash.PBKDF2Params
import com.xpdustry.imperium.common.hash.ShaHashFunction
import com.xpdustry.imperium.common.hash.ShaType
import com.xpdustry.imperium.common.misc.encodeBase64
import com.xpdustry.imperium.common.security.DEFAULT_PASSWORD_REQUIREMENTS
import com.xpdustry.imperium.common.security.DEFAULT_USERNAME_REQUIREMENTS
import com.xpdustry.imperium.common.security.Identity
import com.xpdustry.imperium.common.security.RateLimiter
import com.xpdustry.imperium.common.security.UsernameRequirement
import com.xpdustry.imperium.common.security.findMissingPasswordRequirements
import com.xpdustry.imperium.common.security.findMissingUsernameRequirements
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.flow.firstOrNull
import org.bson.types.ObjectId

internal class MongoAccountManager(private val mongo: MongoProvider) :
    AccountManager, ImperiumApplication.Listener {

    private val limiter = RateLimiter<AccountRateLimitKey>(5, Duration.ofMinutes(5L))
    private lateinit var accounts: MongoEntityCollection<Account, ObjectId>
    private lateinit var legacyAccounts: MongoEntityCollection<LegacyAccount, HashedUsername>

    override fun onImperiumInit() {
        accounts = mongo.getCollection("accounts", Account::class)
        legacyAccounts = mongo.getCollection("legacy_accounts", LegacyAccount::class)
    }

    override suspend fun findByIdentity(identity: Identity.Mindustry): Account? {
        val token = createSessionToken(identity)
        return accounts.find(Filters.gt("sessions.$token.expiration", Instant.now())).firstOrNull()
    }

    override suspend fun findByUsername(username: String): Account? =
        accounts.find(Filters.eq("username", username)).firstOrNull()

    override suspend fun findByDiscordId(discordId: Long): Account? =
        accounts.find(Filters.eq("discord", discordId)).firstOrNull()

    override suspend fun updateByIdentity(
        identity: Identity.Mindustry,
        updater: suspend (Account) -> Unit
    ) {
        findByIdentity(identity)?.let {
            updater(it)
            accounts.save(it)
        }
    }

    override suspend fun updateById(id: ObjectId, updater: suspend (Account) -> Unit) {
        accounts.findById(id)?.let {
            updater(it)
            accounts.save(it)
        }
    }

    override suspend fun register(
        username: String,
        password: CharArray,
        identity: Identity.Mindustry
    ): AccountOperationResult {
        if (isRateLimited("register", identity)) {
            return AccountOperationResult.RateLimit
        }

        val normalizedUsername = username.normalizeUsername()

        if (findByUsername(normalizedUsername) != null) {
            return AccountOperationResult.AlreadyRegistered
        }

        if (findLegacyAccountByUsername(normalizedUsername) != null) {
            return AccountOperationResult.InvalidUsername(
                listOf(UsernameRequirement.Reserved(normalizedUsername)))
        }

        val missingPwdRequirements =
            DEFAULT_PASSWORD_REQUIREMENTS.findMissingPasswordRequirements(password)
        if (missingPwdRequirements.isNotEmpty()) {
            return AccountOperationResult.InvalidPassword(missingPwdRequirements)
        }

        val missingUsrRequirements =
            DEFAULT_USERNAME_REQUIREMENTS.findMissingUsernameRequirements(normalizedUsername)
        if (missingUsrRequirements.isNotEmpty()) {
            return AccountOperationResult.InvalidUsername(missingUsrRequirements)
        }

        accounts.save(
            Account(
                username = normalizedUsername,
                password = GenericSaltyHashFunction.create(password, PASSWORD_PARAMS),
            ),
        )

        return AccountOperationResult.Success
    }

    override suspend fun migrate(
        oldUsername: String,
        newUsername: String,
        password: CharArray,
        identity: Identity.Mindustry
    ): AccountOperationResult {
        if (isRateLimited("migrate", identity)) {
            return AccountOperationResult.RateLimit
        }

        val legacy =
            findLegacyAccountByUsername(oldUsername.normalizeUsername())
                ?: return AccountOperationResult.NotRegistered

        if (!GenericSaltyHashFunction.equals(password, legacy.password)) {
            return AccountOperationResult.WrongPassword
        }

        val normalizedUsername = newUsername.normalizeUsername()
        if (findByUsername(normalizedUsername) != null) {
            return AccountOperationResult.AlreadyRegistered
        }

        val missing =
            DEFAULT_USERNAME_REQUIREMENTS.findMissingUsernameRequirements(normalizedUsername)
        if (missing.isNotEmpty()) {
            return AccountOperationResult.InvalidUsername(missing)
        }

        accounts.save(
            Account(
                username = normalizedUsername,
                password = GenericSaltyHashFunction.create(password, PASSWORD_PARAMS),
                playtime = legacy.playtime,
                roles = if (legacy.verified) mutableSetOf(Role.VERIFIED) else mutableSetOf(),
                games = legacy.games,
                achievements =
                    legacy.achievements
                        .map { it.name.lowercase() }
                        .associateWithTo(mutableMapOf()) {
                            Achievement.Progression(completed = true)
                        },
            ),
        )

        legacyAccounts.deleteById(legacy._id)

        return AccountOperationResult.Success
    }

    override suspend fun login(
        username: String,
        password: CharArray,
        identity: Identity.Mindustry
    ): AccountOperationResult {
        if (isRateLimited("login", identity)) {
            return AccountOperationResult.RateLimit
        }

        val account =
            findByUsername(username.normalizeUsername())
                ?: return AccountOperationResult.NotRegistered

        if (!GenericSaltyHashFunction.equals(password, account.password)) {
            return AccountOperationResult.WrongPassword
        }

        val now = Instant.now()
        account.sessions.values.removeIf { it.expiration.isBefore(now) }
        account.sessions[createSessionToken(identity)] =
            Account.Session(Instant.now().plus(SESSION_TOKEN_DURATION))
        accounts.save(account)

        return AccountOperationResult.Success
    }

    override suspend fun logout(identity: Identity.Mindustry, all: Boolean) {
        val account = findByIdentity(identity) ?: return
        if (all) account.sessions.clear() else account.sessions.remove(createSessionToken(identity))
        accounts.save(account)
    }

    override suspend fun refresh(identity: Identity.Mindustry) {
        val account = findByIdentity(identity) ?: return
        account.sessions[createSessionToken(identity)] =
            Account.Session(Instant.now().plus(SESSION_TOKEN_DURATION))
        accounts.save(account)
    }

    override suspend fun changePassword(
        oldPassword: CharArray,
        newPassword: CharArray,
        identity: Identity.Mindustry
    ): AccountOperationResult {
        if (isRateLimited("changePassword", identity)) {
            return AccountOperationResult.RateLimit
        }

        val account = findByIdentity(identity) ?: return AccountOperationResult.NotLogged

        if (!GenericSaltyHashFunction.equals(oldPassword, account.password)) {
            return AccountOperationResult.WrongPassword
        }

        val missing = DEFAULT_PASSWORD_REQUIREMENTS.findMissingPasswordRequirements(newPassword)
        if (missing.isNotEmpty()) {
            return AccountOperationResult.InvalidPassword(missing)
        }

        account.password = GenericSaltyHashFunction.create(newPassword, PASSWORD_PARAMS)
        accounts.save(account)

        return AccountOperationResult.Success
    }

    private suspend fun findLegacyAccountByUsername(username: String): LegacyAccount? {
        val hash = ShaHashFunction.create(username.toCharArray(), ShaType.SHA256)
        return legacyAccounts.findById(hash.hash.encodeBase64())
    }

    internal suspend fun createSessionToken(identity: Identity.Mindustry): String {
        val hash =
            Argon2HashFunction.create(
                identity.uuid.toCharArray(), SESSION_TOKEN_PARAMS, identity.usid.toCharArray())
        return Base64.getEncoder().encodeToString(hash.hash)
    }

    private fun String.normalizeUsername(): String = trim().lowercase()

    private fun isRateLimited(operation: String, identity: Identity.Mindustry): Boolean =
        !limiter.checkAndIncrement(AccountRateLimitKey(operation, identity.address))

    companion object {
        private val SESSION_TOKEN_DURATION = Duration.ofDays(7L)

        private val SESSION_TOKEN_PARAMS =
            Argon2Params(
                memory = 19,
                iterations = 2,
                length = 32,
                saltLength = 8,
                parallelism = 8,
                type = Argon2Params.Type.ID,
                version = Argon2Params.Version.V13,
            )

        private val PASSWORD_PARAMS =
            Argon2Params(
                memory = 64 * 1024,
                iterations = 3,
                parallelism = 2,
                length = 64,
                type = Argon2Params.Type.ID,
                version = Argon2Params.Version.V13,
                saltLength = 64,
            )

        private val LEGACY_PASSWORD_PARAMS =
            PBKDF2Params(
                hmac = PBKDF2Params.Hmac.SHA256,
                iterations = 10000,
                length = 256,
                saltLength = 16,
            )
    }

    private data class AccountRateLimitKey(val operation: String, val address: InetAddress)
}
