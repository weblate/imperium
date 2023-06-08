/*
 * Foundation, the software collection powering the Xpdustry network.
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
package com.xpdustry.foundation.common.database.mongo

import com.google.inject.Inject
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCredential
import com.mongodb.ServerAddress
import com.mongodb.ServerApi
import com.mongodb.ServerApiVersion
import com.mongodb.connection.SslSettings
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoDatabase
import com.xpdustry.foundation.common.application.FoundationListener
import com.xpdustry.foundation.common.configuration.FoundationConfig
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface MongoProvider {
    val database: MongoDatabase
}

internal class SimpleMongoProvider @Inject constructor(private val config: FoundationConfig) :
    MongoProvider, FoundationListener {

    override lateinit var database: MongoDatabase
    private lateinit var client: MongoClient

    override fun onFoundationInit() {
        client = MongoClients.create(MongoClientSettings.builder()
            .applicationName("Foundation")
            .applyToClusterSettings {
                it.hosts(listOf(ServerAddress(config.mongo.host, config.mongo.port)))
            }
            .credential(
                MongoCredential.createCredential(
                    config.mongo.username,
                    config.mongo.authDatabase,
                    config.mongo.password.value.toCharArray(),
                ),
            )
            .serverApi(
                ServerApi.builder()
                    .version(ServerApiVersion.V1)
                    .strict(true)
                    .deprecationErrors(true)
                    .build(),
            )
            .applyToSslSettings {
                it.applySettings(SslSettings.builder().enabled(config.mongo.ssl).build())
            }
            .build())

        // Check if client is correctly authenticated
        Flux.from(client.listDatabaseNames())
            .filter { it == config.mongo.database }
            .switchIfEmpty(Mono.error(IllegalStateException("MongoDB authentication failed")))
            .blockFirst()

        database = client.getDatabase(config.mongo.database)
    }

    override fun onFoundationExit() {
        client.close()
    }
}
